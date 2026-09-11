package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
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
import java.net.URLDecoder;
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
 * WxpayCreateHandler 支付方式分发（epay 编号 1/2/3/5 + UA 分流）。
 *
 * <p>公众号授权 URL 决策内置 SDK（已配置系统公众号 → 跳 /oauth/wxmp 包装的通道授权 URL，双 OAuth；
 * 未配置 → 直接通道授权 URL），buyer 由宿主/SDK 管理、插件不读不判；与小程序/扫码/APP 分发。</p>
 *
 * <p>lockCreate 用例走真实签名/验签路径：平台证书经 {@code /v3/certificates} 下载响应
 * （mock 层加密返回测试证书）自动注入，HTTP 仅被 {@code HttpHelper} 静态 mock 截断。</p>
 */
@DisplayName("WxpayCreateHandler 支付方式分发")
class WxpayCreateHandlerTest {

    private static final String MCH_ID = "MCH1001";
    private static final String APP_ID = "wxAPP1001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String CERT_SERIAL = "CERT_SERIAL_1";
    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";
    private static final String UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private static KeyPair KP;
    private static java.security.PrivateKey PLATFORM_KEY;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
        try (var in = WxpayCreateHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
            PLATFORM_KEY = RsaKeys.loadPrivateKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    /** JDK 原生 AES-256-GCM 加密（模拟微信证书下载响应的 encrypt_certificate）。 */
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

    /**
     * 通道配置：biztype（方式编号字符串）、商户密钥（lockCreate 用例必填）、公众号/小程序绑定。
     */
    private static byte[] cfgRaw(String biztype, boolean withKeys, boolean mp, boolean mini)
            throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        if (withKeys) {
            cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
            cfg.put("appkey", CERT_SERIAL);
        }
        if (biztype != null) cfg.put("biztype", biztype);
        if (mp) cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
        if (mini) cfg.put("mini", Map.of("appid", "wxMINIAPP1", "appsecret", "wxMINISECRET"));
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    /** 服务商通道配置：mp 绑定 + 子商户号 sub_mchid。 */
    private static byte[] cfgRawService(String biztype, boolean withKeys, boolean mp, String subMchId)
            throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        if (withKeys) {
            cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
            cfg.put("appkey", CERT_SERIAL);
        }
        if (biztype != null) cfg.put("biztype", biztype);
        if (mp) cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
        if (subMchId != null) cfg.put("sub_mchid", subMchId);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(String ua, String query, boolean oauthWx, String buyer,
                              byte[] cfgRaw) {
        var order = OrderSnapshot.builder().tradeNo("T1").real(100L).build();
        if (buyer != null) order.setBuyer(buyer);
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(order)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .gatewayDomain("https://gw.example.com")
                        .oauthWxConfigured(oauthWx).build());
        if (ua != null || query != null) {
            var req = RequestSnapshot.builder();
            if (ua != null) req.ua(ua);
            if (query != null) req.query(query);
            b.request(req.build());
        }
        return b.build();
    }

    // =========================================================================
    // mock 辅助：UA 判定放行 + 平台证书下载 + 带验签头的成功响应
    // =========================================================================

    /** mockStatic(HttpHelper) 会连 UA 判定一起拦截，未 stub 时全 false → 方式错配；此处放行真实实现。 */
    private static void letRealUaChecks(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isAlipay(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isWeChat(any())).thenCallRealMethod();
    }

    /** mock /v3/certificates 下载响应：平台证书经 apiV3Key 加密返回，client 解密后自动注入。 */
    private static void stubPlatformCertDownload(MockedStatic<HttpHelper> mocked) throws Exception {
        var certPem = new String(
                WxpayCreateHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
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

    /** 构造带微信验签头的成功响应（响应验签走平台证书公钥，真实签名）。 */
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
    // 公众号双 OAuth（biztype=2 JSAPI + 公众号绑定）
    // =========================================================================

    @Test
    @DisplayName("已配置系统公众号 → 跳 /oauth/wxmp 包装的通道授权 URL（双 OAuth：先系统公众号存风控身份）")
    void unifiedOAuthWrapperWhenConfigured() throws Exception {
        // buyer 有无不参与决策：插件始终经 SDK 构建授权 URL，buyer 由宿主/SDK 管理
        var ctx = ctx(UA_WECHAT, null, true, null, cfgRaw("2", false, true, false));

        var resp = new WxpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        var url = resp.getUrl();
        // 首层入口基地址恒为平台网关域（系统公众号绑定的授权域名），非通道前端域
        assertThat(url).startsWith("https://gw.example.com/oauth/wxmp?redirect_uri=");
        // redirect_uri 是通道公众号授权 URL（第二层 OAuth 的发起方是通道自己的公众号）
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://open.weixin.qq.com/connect/oauth2/authorize")
                .contains("appid=wxMPAPP1")
                .contains("state=T1");
    }

    @Test
    @DisplayName("未配置系统公众号 → 直接通道公众号授权 URL（单层）")
    void channelOAuthWhenNotConfigured() throws Exception {
        var ctx = ctx(UA_WECHAT, null, false, null, cfgRaw("2", false, true, false));

        var resp = new WxpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        assertThat(resp.getUrl()).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
        assertThat(resp.getUrl()).contains("appid=wxMPAPP1");
    }

    @Test
    @DisplayName("宿主回跳的 oauth_done 参数被插件忽略——照常走通道兑换（buyer/流程标记均不归插件判断）")
    void mpCallbackIgnoresOauthDoneMarker() throws Exception {
        var ctx = ctx(UA_WECHAT, "oauth_done=1&code=CODE1", true, null, cfgRaw("2", true, true, false));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

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
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv ->
                    signedOk("{\"prepay_id\":\"wxPREPAY1234567890\"}"));
            var resp = new WxpayCreateHandler().wxpay(ctx);
            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
        }
        // 兑换照常经 SDK 内部自动风控（落库 + 黑名单），oauth_done 不产生任何分支
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("PC + JSAPI + 公众号 → 二维码页展示 jspay 链接（epay wap 页语义：扫码者微信打开走 JSAPI）")
    void qrcodeFallbackOutsideWechat() throws Exception {
        var ctx = ctx(UA_PC, null, false, null, cfgRaw("2", false, true, false));

        var resp = new WxpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("page");
        assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
    }

    // =========================================================================
    // lockCreate 分发（真实签名/验签，仅 HTTP mock）
    // =========================================================================

    @Test
    @DisplayName("渠道 HTTP 层失败（502 非 JSON 错误页）：归类为渠道异常返回 error 页（含状态码），不抛异常")
    void channelHttpFailureReturnsChannelErrorPage() throws Exception {
        var ctx = ctx(UA_PC, null, false, null, cfgRaw("1", true, false, false));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head><body></body></html>"
                                    .getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var resp = new WxpayCreateHandler().wxpay(ctx);

            // 渠道挂是渠道问题：error 页文案归因渠道，不得报"插件内部错误"
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg())
                    .isEqualTo("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
        }
    }

    @Test
    @DisplayName("PC + Native(1) → 微信下单 → 扫码页 wxpay_qrcode（code_url 进页面 URL）")
    void pcNativeRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, null, cfgRaw("1", true, false, false));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv ->
                    signedOk("{\"code_url\":\"weixin://wxpay/bizpayurl?pr=ABC123\"}"));
            var resp = new WxpayCreateHandler().wxpay(ctx);
            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
        }
    }

    @Test
    @DisplayName("手机 iPhone + APP(4) → weixin:// 调起协议 → wxpay_h5 页（修复 wxpay_app 404 坏点）")
    void iphoneAppRendersH5Scheme() throws Exception {
        var ctx = ctx(UA_IPHONE, null, false, null, cfgRaw("4", true, false, false));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv ->
                    signedOk("{\"prepay_id\":\"wxPREPAY1234567890\"}"));
            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            var url = resp.getUrl();
            assertThat(url).startsWith("weixin://app/" + APP_ID + "/pay/?");
            assertThat(url).contains("prepayid=wxPREPAY1234567890")
                    .contains("partnerid=" + MCH_ID)
                    .contains("package=Sign%3DWXPay")
                    .contains("noncestr=")
                    .contains("timestamp=")
                    .contains("sign=");
        }
    }

    @Test
    @DisplayName("PC + 仅 APP(4) → 报错（epay qrcode() 无 4 分支：APP 不参与 PC 兜底）且不调网络")
    void pcAppOnlyFailsWithoutNetwork() throws Exception {
        var ctx = ctx(UA_PC, null, false, null, cfgRaw("4", true, false, false));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).contains("当前通道未开启微信支付方式");
            // resolver 内 isMobile/isWeChat 走真实实现也算 interaction，故不用 verifyNoInteractions；
            // 意图是「不调网络」——显式验证所有 HTTP 入口从未调用
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()), never());
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            mocked.verify(() -> HttpHelper.get(any(), any()), never());
            mocked.verify(() -> HttpHelper.get(any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("微信内 + JSAPI(2) + 小程序绑定（无公众号）→ 小程序 scheme 页（wxpay_h5）")
    void wechatJsapiWithMiniRendersScheme() throws Exception {
        var ctx = ctx(UA_WECHAT, null, false, null, cfgRaw("2", false, false, true));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                // access_token 现走 stable_token（POST），对齐 epay 多实例安全
                if (url.contains("stable_token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"access_token\":\"TOKEN1\",\"expires_in\":7200}"
                                    .getBytes(StandardCharsets.UTF_8), "req", 10, 1);
                }
                if (url.contains("generatescheme")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"errcode\":0,\"errmsg\":\"ok\",\"openlink\":\"weixin://dl/business/?t=ABC123\"}"
                                    .getBytes(StandardCharsets.UTF_8), "req", 10, 1);
                }
                throw new IllegalStateException("unexpected POST " + url);
            });
            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            assertThat(resp.getUrl()).isEqualTo("weixin://dl/business/?t=ABC123");
        }
    }

    // =========================================================================
    // 通道路径 OAuth 兑换 + SDK 内部自动风控（真实 getMpOpenid/getMiniOpenid，仅 HTTP mock）
    // =========================================================================

    @Test
    @DisplayName("公众号回调（code）→ SDK 兑换 openid → 内部自动风控 → JSAPI 下单 wxpay_jspay")
    void mpCallbackExchangesAndAutoRisks() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=CODE1", false, null, cfgRaw("2", true, true, false));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        // 真实 OAuthHelper.getMpOpenid（真实 HTTP 流程）：自动风控在方法体内真实执行，
        // 可验证 cb.recordOAuthIdentity 被内部自动调用（插件没有显式调用代码）
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
                    .thenAnswer(inv -> signedOk("{\"prepay_id\":\"wxPREPAY1234567890\"}"));
            var resp = new WxpayCreateHandler().wxpay(ctx);
            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
            // 直连 JSAPI：appid 用 OAuth 公众号（openid 同源），payer.openid 承载 openid
            assertThat(urlCaptor.getValue())
                    .isEqualTo("https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi");
            var body = HttpHelper.MAPPER.readTree(bodyCaptor.getValue());
            assertThat(body.path("appid").asString()).isEqualTo("wxMPAPP1");
            assertThat(body.path("payer").path("openid").asString()).isEqualTo("wxOPENID_MP1");
        }
        // 风控内置：兑换成功即自动经宿主回调落库 openid + 黑名单（SDK 内部闭环，插件无跳过路径）
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("公众号回调命中买家黑名单 → 兑换内部自动抛异常 → 跳失败页终止（不再下单）")
    void mpCallbackBlockedByBlacklist() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=CODE1", false, null, cfgRaw("2", false, true, false));
        var cb = ctx.getCallback();
        // 宿主回调：黑名单命中（recordOAuthIdentity=true，订单已推进 -3）
        when(cb.recordOAuthIdentity(any())).thenReturn(true);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_BLOCKED\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo("https://pay.example.com/pay/result/T1");
        }
        // 命中后插件未做任何拦截判断——兑换方法内部已抛异常终止；被拦截：不发起支付、不写 ext
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("小程序回调（auth_code）→ SDK 兑换 openid → 内部自动风控 → JSAPI 下单 wxpay_jspay")
    void miniCallbackExchangesAndAutoRisks() throws Exception {
        var ctx = ctx(UA_WECHAT, "auth_code=MINICODE1", false, null, cfgRaw("2", true, false, true));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.contains("jscode2session")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_MINI1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1);
                }
                throw new IllegalStateException("unexpected GET " + url);
            });
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any())).thenAnswer(inv ->
                    signedOk("{\"prepay_id\":\"wxPREPAY1234567890\"}"));
            var resp = new WxpayCreateHandler().wxpay(ctx);
            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
        }
        // 风控内置：小程序通道路径同样自动落库 openid + 黑名单
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("服务商 + 公众号 JSAPI → partner 端点 + payer.sp_openid（sp_appid 与 OAuth 公众号同源）")
    void serviceMpJsapiPostsPartnerEndpointWithSpOpenid() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=CODE1", false, null, cfgRawService("2", true, true, "SUB1001"));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

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
                    .thenAnswer(inv -> signedOk("{\"prepay_id\":\"wxPREPAY1234567890\"}"));

            var resp = new WxpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
            // 服务商下单走 partner 端点（直连端点不适用）
            assertThat(urlCaptor.getValue())
                    .isEqualTo("https://api.mch.weixin.qq.com/v3/pay/partner/transactions/jsapi");
            var body = HttpHelper.MAPPER.readTree(bodyCaptor.getValue());
            assertThat(body.path("sp_mchid").asString()).isEqualTo(MCH_ID);
            assertThat(body.path("sub_mchid").asString()).isEqualTo("SUB1001");
            // sp_appid 用 OAuth 公众号（openid 同源），payer 只承载 sp_openid
            assertThat(body.path("sp_appid").asString()).isEqualTo("wxMPAPP1");
            assertThat(body.path("payer").path("sp_openid").asString()).isEqualTo("wxOPENID_MP1");
            assertThat(body.path("payer").path("openid").isMissingNode()).isTrue();
            assertThat(body.path("payer").path("sub_openid").isMissingNode()).isTrue();
        }
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }
}
