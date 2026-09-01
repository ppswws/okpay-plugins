package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.SaveSubOrdersRequest;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WxpayCreateHandler 合单拆单（combine）：达阈值 → /v3/combine-transactions/* + 锁内 saveSubOrders；
 * 未达阈值 → 单笔路径不落库。报文/落库共用同一份确定性子单号。
 */
@DisplayName("WxpayCreateHandler 合单拆单")
class WxpayCombineCreateHandlerTest {

    private static final String MCH_ID = "MCH1001";
    private static final String APP_ID = "wxAPP1001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String CERT_SERIAL = "CERT_SERIAL_1";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String BASE = "https://api.mch.weixin.qq.com";
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";

    private static KeyPair KP;
    private static java.security.PrivateKey PLATFORM_KEY;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
        try (var in = WxpayCombineCreateHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
            PLATFORM_KEY = RsaKeys.loadPrivateKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private static String aesGcmEncrypt(String nonce, String aad, String plaintext, String key)
            throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    /** 通道配置：合单开关 + 支付方式 + 商户密钥。subMchId 非空 → 服务商模式；金额参数走全局 ConfigSnapshot 注入。 */
    private static byte[] cfgRaw(String biztype, boolean withKeys, String subMchId) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        if (withKeys) {
            cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
            cfg.put("appkey", CERT_SERIAL);
        }
        cfg.put("biztype", biztype);
        cfg.put("wxcombine_open", true);
        if (subMchId != null) cfg.put("sub_mchid", subMchId);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(long real, byte[] cfgRaw) {
        return ctx(real, cfgRaw, UA_PC, null);
    }

    private InvokeContext ctx(long real, byte[] cfgRaw, String ua, String query) {
        var req = RequestSnapshot.builder().ua(ua);
        if (query != null) req.query(query);
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(real).build())
                .callback(mock(HostCallback.class))
                .request(req.build())
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").goodsName("合单测试商品")
                        // 全局合单金额：起拆 100 元、单笔上限 200 元
                        .combineWxpayMinMoneyCents(10000).combineWxpaySubMoneyCents(20000).build())
                .build();
    }

    /** JSAPI 合单通道配置：mp 绑定 + 合单开关 + 商户密钥。 */
    private static byte[] cfgRawJsapi(String subMchId) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfg.put("appkey", CERT_SERIAL);
        cfg.put("biztype", "2");
        cfg.put("wxcombine_open", true);
        cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
        if (subMchId != null) cfg.put("sub_mchid", subMchId);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private static void letRealUaChecks(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isAlipay(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isWeChat(any())).thenCallRealMethod();
    }

    private static void stubPlatformCertDownload(MockedStatic<HttpHelper> mocked) throws Exception {
        var certPem = new String(
                WxpayCombineCreateHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
                        .readAllBytes(), StandardCharsets.UTF_8);
        var ciphertext = aesGcmEncrypt("cert-nonce", "cert-aad", certPem, API_V3_KEY);
        var certJson = "{\"data\":[{\"serial_no\":\"SERIAL1\",\"encrypt_certificate\":{"
                + "\"nonce\":\"cert-nonce\",\"associated_data\":\"cert-aad\","
                + "\"ciphertext\":\"" + ciphertext + "\"}}]}";
        mocked.when(() -> HttpHelper.get(any(), any(), any())).thenAnswer(inv -> {
            var url = inv.getArgument(1).toString();
            if (url.contains("/v3/certificates")) {
                return new HttpHelper.HttpResponse(200, Map.of(),
                        certJson.getBytes(StandardCharsets.UTF_8), "req", 10, 1);
            }
            throw new IllegalStateException("unexpected GET " + url);
        });
    }

    private static HttpHelper.HttpResponse signedOk(String bodyJson) throws Exception {
        var timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        var nonce = "resp-nonce";
        var signature = RsaKeys.sign(timestamp + "\n" + nonce + "\n" + bodyJson + "\n", PLATFORM_KEY);
        var headers = new LinkedHashMap<String, java.util.List<String>>();
        headers.put("wechatpay-timestamp", java.util.List.of(timestamp));
        headers.put("wechatpay-nonce", java.util.List.of(nonce));
        headers.put("wechatpay-serial", java.util.List.of("SERIAL1"));
        headers.put("wechatpay-signature", java.util.List.of(signature));
        return new HttpHelper.HttpResponse(200, headers,
                bodyJson.getBytes(StandardCharsets.UTF_8), "req", 10, 1);
    }

    // =========================================================================
    // Native 合单（报文 + 落库一致）
    // =========================================================================

    @Test
    @DisplayName("达阈值 Native：combine 报文 + 锁内 saveSubOrders（子单号与报文一致）")
    void nativeCombineSplitsAndSaves() throws Exception {
        // real=30000 分（300 元）≥ minmoney=100 元；均分 3 单各 10000 分（≤ submoney=200 元）
        var ctx = ctx(30000, cfgRaw("1", true, null));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                assertThat(url).isEqualTo(BASE + "/v3/combine-transactions/native");
                return signedOk("{\"code_url\":\"weixin://wxpay/bizpayurl?pr=COMBINE1\"}");
            });

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            // 落库与报文共用同一份确定性子单号
            var saved = ArgumentCaptor.forClass(SaveSubOrdersRequest.class);
            verify(cb).saveSubOrders(saved.capture());
            assertThat(saved.getValue().getTradeNo()).isEqualTo(TRADE_NO);
            assertThat(saved.getValue().getItems()).hasSize(3);
            assertThat(saved.getValue().getItems().get(0).getSubTradeNo())
                    .isEqualTo(SdkSubNo(TRADE_NO, 1));
            assertThat(saved.getValue().getItems().get(0).getMoney()).isEqualTo(10000);
            assertThat(saved.getValue().getItems().get(2).getMoney()).isEqualTo(10000);
        }
    }

    @Test
    @DisplayName("合单报文结构：combine_appid/mchid/out_trade_no/notify_url + sub_orders 金额合计")
    void combineBodyStructure() throws Exception {
        var ctx = ctx(30000, cfgRaw("1", true, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"code_url\":\"wx://combine\"}"));

            new WxpayCreateHandler().wxpay(ctx);

            var body = HttpHelper.MAPPER.readTree(bodyCaptor.getValue());
            assertThat(body.path("combine_appid").asText()).isEqualTo(APP_ID);
            assertThat(body.path("combine_mchid").asText()).isEqualTo(MCH_ID);
            assertThat(body.path("combine_out_trade_no").asText()).isEqualTo(TRADE_NO);
            assertThat(body.path("notify_url").asText())
                    .isEqualTo("https://pay.example.com/pay/notify/" + TRADE_NO);
            var subs = body.path("sub_orders");
            assertThat(subs.size()).isEqualTo(3);
            long total = 0;
            for (int i = 0; i < 3; i++) {
                assertThat(subs.get(i).path("out_trade_no").asText())
                        .isEqualTo(SdkSubNo(TRADE_NO, i + 1));
                // sub_orders.mchid 必填（直连=商户号）
                assertThat(subs.get(i).path("mchid").asText()).isEqualTo(MCH_ID);
                assertThat(subs.get(i).path("attach").asText()).isEqualTo("combine");
                assertThat(subs.get(i).path("description").asText()).isNotEmpty();
                assertThat(subs.get(i).path("amount").path("currency").asText()).isEqualTo("CNY");
                total += subs.get(i).path("amount").path("total_amount").asLong();
            }
            assertThat(total).isEqualTo(30000);
        }
    }

    @Test
    @DisplayName("未达阈值 → 单笔路径（native 报文 + 不落库）")
    void belowThresholdStaysSingle() throws Exception {
        // real=5000 分（50 元）< minmoney=100 元
        var ctx = ctx(5000, cfgRaw("1", true, null));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                assertThat(inv.getArgument(1).toString()).isEqualTo(BASE + "/v3/pay/transactions/native");
                return signedOk("{\"code_url\":\"weixin://single\"}");
            });

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("服务商模式：子单带 sub_mchid")
    void serviceProviderAddsSubMchid() throws Exception {
        var ctx = ctx(30000, cfgRaw("1", true, "SUB1001"));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"code_url\":\"wx://combine\"}"));

            new WxpayCreateHandler().wxpay(ctx);

            var body = HttpHelper.MAPPER.readTree(bodyCaptor.getValue());
            // 服务商合单：sub_orders.mchid 取值 combine_mchid（官方服务商合单要求），sub_mchid 并存
            assertThat(body.path("sub_orders").get(0).path("mchid").asText())
                    .isEqualTo(MCH_ID);
            assertThat(body.path("sub_orders").get(0).path("sub_mchid").asText())
                    .isEqualTo("SUB1001");
        }
    }

    @Test
    @DisplayName("ext 已存在（锁内缓存命中）→ 不重发渠道、不重复落库")
    void extShortCircuitSkipsNetworkAndSave() throws Exception {
        var ctx = ctx(30000, cfgRaw("1", true, null));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder()
                .extData(Map.of("type", "page", "url", "wx://cached")).build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getUrl()).isEqualTo("wx://cached");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()), never());
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("H5 合单：h5_url + redirect_url 跳转页")
    void h5CombineRendersJump() throws Exception {
        var ctx = ctx(30000, cfgRaw("3", true, null));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        ctx.setRequest(RequestSnapshot.builder().ua(UA_IPHONE).build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                assertThat(inv.getArgument(1).toString()).isEqualTo(BASE + "/v3/combine-transactions/h5");
                return signedOk("{\"h5_url\":\"https://wx.tenpay.com/combine-h5\"}");
            });

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).startsWith("https://wx.tenpay.com/combine-h5&redirect_url=");
            verify(cb).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("APP 合单：weixin:// 调起协议（prepay_id 进签名参数）")
    void appCombineRendersScheme() throws Exception {
        var ctx = ctx(30000, cfgRaw("4", true, null));
        ctx.setRequest(RequestSnapshot.builder().ua(UA_IPHONE).build());
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                assertThat(inv.getArgument(1).toString()).isEqualTo(BASE + "/v3/combine-transactions/app");
                return signedOk("{\"prepay_id\":\"wxCOMBINEPREPAY\"}");
            });

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            assertThat(resp.getUrl()).startsWith("weixin://app/" + APP_ID + "/pay/?");
            assertThat(resp.getUrl()).contains("prepayid=wxCOMBINEPREPAY");
            verify(cb).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("公众号 JSAPI 合单 → combine_payer_info.openid（combine_appid 与 OAuth 公众号同源）")
    void combineJsapiUsesCombinePayerInfo() throws Exception {
        // real=30000 分（300 元）≥ minmoney=100 元 → 合单；微信内 + JSAPI(2) + 公众号 → MP
        var ctx = ctx(30000, cfgRawJsapi(null), UA_WECHAT, "code=CODE1");
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.contains("sns/oauth2/access_token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_MP1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1);
                }
                throw new IllegalStateException("unexpected GET " + url);
            });
            var urlCaptor = ArgumentCaptor.forClass(String.class);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), urlCaptor.capture(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"prepay_id\":\"wxCOMBINEPREPAY\"}"));

            new WxpayCreateHandler().wxpay(ctx);

            assertThat(urlCaptor.getValue()).isEqualTo(BASE + "/v3/combine-transactions/jsapi");
            var body = HttpHelper.MAPPER.readTree(bodyCaptor.getValue());
            // combine_appid 与 OAuth 公众号同源，payer 挂 combine_payer_info.openid（无顶层 payer）
            assertThat(body.path("combine_appid").asText()).isEqualTo("wxMPAPP1");
            assertThat(body.path("combine_mchid").asText()).isEqualTo(MCH_ID);
            assertThat(body.path("combine_payer_info").path("openid").asText())
                    .isEqualTo("wxOPENID_MP1");
            assertThat(body.path("payer").isMissingNode()).isTrue();
        }
        verify(ctx.getCallback(), atLeastOnce()).recordOAuthIdentity(any());
    }

    private static String SdkSubNo(String mainTradeNo, int idx) {
        return com.okpay.plugin.sdk.Sdk.subTradeNo(mainTradeNo, idx);
    }
}
