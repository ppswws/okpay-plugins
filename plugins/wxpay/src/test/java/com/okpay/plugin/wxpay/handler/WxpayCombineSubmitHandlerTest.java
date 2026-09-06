package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.SubOrderSnapshot;
import com.okpay.plugin.model.UpdateSubOrderRequest;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import com.okpay.plugin.sdk.Sdk;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WxpaySubmitHandler 合单退款：逐子单提交（out_refund_no=退款单_序号，total=子单金额），
 * 已退部分抵扣（quota=min(remaining, money-refundMoney)，≤0 跳过）保证重跑幂等。
 * 语义：全退完 S_OK / 首单即失败 S_FAIL / 中途失败 S_ING（靠查单续退）。
 */
@DisplayName("WxpaySubmitHandler 合单退款")
class WxpayCombineSubmitHandlerTest {

    private static final String MCH_ID = "MCH1001";
    private static final String APP_ID = "wxAPP1001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String REFUND_NO = "R202608181200000000001";

    private static KeyPair KP;
    private static java.security.PrivateKey PLATFORM_KEY;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
        try (var in = WxpayCombineSubmitHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
            PLATFORM_KEY = RsaKeys.loadPrivateKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
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

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private static byte[] cfgRaw() throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfg.put("appkey", "CERT_SERIAL_1");
        cfg.put("biztype", "1");
        cfg.put("wxcombine_open", true);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(long amount, long refunded1, long refunded2, long refunded3) {
        var refund = RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(amount).build();
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).apiTradeNo("TX1").combine(true).build())
                .refund(refund)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    private static byte[] cfgRawOrNull() {
        try {
            return cfgRaw();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<SubOrderSnapshot> subs(long money, long... refunded) {
        var out = new java.util.ArrayList<SubOrderSnapshot>(refunded.length);
        for (int i = 0; i < refunded.length; i++) {
            out.add(SubOrderSnapshot.builder()
                    .subTradeNo(Sdk.subTradeNo(TRADE_NO, i + 1))
                    .tradeNo(TRADE_NO).money(money).refundMoney(refunded[i]).build());
        }
        return out;
    }

    /** mock /v3/certificates 下载响应（client 懒加载平台证书）。 */
    private static void stubPlatformCertDownload(MockedStatic<HttpHelper> mocked) throws Exception {
        var certPem = new String(
                WxpayCombineSubmitHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
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
    // 合单退款提交
    // =========================================================================

    @Test
    @DisplayName("全子单成功 → 3 次退款请求（out_refund_no 带序号、total=子单金额）+ 逐单累计 → S_OK")
    void allSubsSuccessCompletes() throws Exception {
        var ctx = ctx(30000, 0, 0, 0);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            var tx = new String[]{"TXREF1", "TXREF2", "TXREF3"};
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> {
                        int idx = bodyCaptor.getAllValues().size() - 1;
                        return signedOk("{\"status\":\"SUCCESS\",\"transaction_id\":\""
                                + tx[idx] + "\"}");
                    });

            var result = new WxpaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TXREF3");
            // 报文：子单 out_trade_no、out_refund_no=退款单_序号、refund/total=子单金额
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(3);
            var b1 = HttpHelper.MAPPER.readTree(bodies.get(0));
            assertThat(b1.path("out_trade_no").asString()).isEqualTo(Sdk.subTradeNo(TRADE_NO, 1));
            assertThat(b1.path("out_refund_no").asString()).isEqualTo(REFUND_NO + "_1");
            assertThat(b1.path("amount").path("refund").asInt()).isEqualTo(10000);
            assertThat(b1.path("amount").path("total").asInt()).isEqualTo(10000);
            assertThat(b1.path("notify_url").asString())
                    .isEqualTo("https://pay.example.com/pay/refundnotify/" + REFUND_NO);
            var b2 = HttpHelper.MAPPER.readTree(bodies.get(1));
            assertThat(b2.path("out_refund_no").asString()).isEqualTo(REFUND_NO + "_2");
            // 逐单累计
            var updates = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb, times(3)).updateSubOrder(updates.capture());
            assertThat(updates.getAllValues()).extracting(u -> u.getRefundDelta())
                    .containsExactly(10000L, 10000L, 10000L);
            assertThat(updates.getAllValues()).extracting(UpdateSubOrderRequest::getSubTradeNo)
                    .containsExactly(Sdk.subTradeNo(TRADE_NO, 1), Sdk.subTradeNo(TRADE_NO, 2),
                            Sdk.subTradeNo(TRADE_NO, 3));
        }
    }

    @Test
    @DisplayName("已退满子单重跑 → 跳过不发请求，仅续退未退子单（重跑幂等）")
    void rerunSkipsFullyRefundedSubs() throws Exception {
        var ctx = ctx(30000, 10000, 0, 0);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 10000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"status\":\"SUCCESS\",\"transaction_id\":\"TX\"}"));

            var result = new WxpaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            assertThat(HttpHelper.MAPPER.readTree(bodies.get(0)).path("out_refund_no").asString())
                    .isEqualTo(REFUND_NO + "_2");
        }
    }

    @Test
    @DisplayName("部分已退 → quota 按剩余扣减（防超退），额度耗尽即停")
    void partialRefundedQuotaCapped() throws Exception {
        // 第 1 单已退 5000；本次退 15000 → 第 1 单再退 5000、第 2 单退 5000、第 3 单 skip
        var ctx = ctx(15000, 5000, 0, 0);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 5000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"status\":\"SUCCESS\",\"transaction_id\":\"TX\"}"));

            var result = new WxpaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            var b1 = HttpHelper.MAPPER.readTree(bodies.get(0));
            assertThat(b1.path("out_refund_no").asString()).isEqualTo(REFUND_NO + "_1");
            assertThat(b1.path("amount").path("refund").asInt()).isEqualTo(5000);
            var b2 = HttpHelper.MAPPER.readTree(bodies.get(1));
            assertThat(b2.path("amount").path("refund").asInt()).isEqualTo(5000);
        }
    }

    @Test
    @DisplayName("首个提交即被拒（无任何成功）→ S_FAIL（宿主回滚余额）")
    void firstSubRejectedFails() throws Exception {
        var ctx = ctx(30000, 0, 0, 0);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"status\":\"CLOSED\"}"));

            var result = new WxpaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("REFUND_FAIL");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("中途失败（部分成功）→ S_ING 部分完成，靠查单续退")
    void partialFailureIng() throws Exception {
        var ctx = ctx(30000, 0, 0, 0);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> {
                        int idx = bodyCaptor.getAllValues().size() - 1;
                        if (idx == 1) return signedOk("{\"status\":\"CLOSED\"}");
                        return signedOk("{\"status\":\"SUCCESS\",\"transaction_id\":\"TX\"}");
                    });

            var result = new WxpaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("REFUND_PARTIAL");
            // 第 1、3 单成功累计，第 2 单被拒
            verify(cb, times(2)).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("全部已退完重跑 → 不发请求直接 S_OK（remaining≤0）")
    void fullyRefundedRerunOk() throws Exception {
        var ctx = ctx(30000, 10000, 10000, 10000);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 10000, 10000, 10000));

        var result = new WxpaySubmitHandler().handle(ctx,
                BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

        assertThat(result.getState()).isEqualTo(BizState.S_OK);
        verify(cb, never()).updateSubOrder(any());
    }
}
