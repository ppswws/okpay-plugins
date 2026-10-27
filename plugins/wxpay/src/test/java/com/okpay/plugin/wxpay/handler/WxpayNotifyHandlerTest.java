package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.NotifyResponse;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WxpayNotifyHandler 单笔通知：支付通知非成功状态只应答（支付订单无失败终态，不推进任何状态）；
 * 退款通知按渠道退款状态推进——SUCCESS → S_OK，CLOSED → S_FAIL，ABNORMAL/PROCESSING → S_WAIT，
 * PROCESSING/未知 → S_WAIT。
 *
 * <p>通知报文按微信官方信封（resource AES-GCM 加密 + 验签头）构造，验签走测试平台证书。</p>
 */
@DisplayName("WxpayNotifyHandler 单笔通知状态推进")
class WxpayNotifyHandlerTest {

    private static final String MCH_ID = "MCH1001";
    private static final String APP_ID = "wxAPP1001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String REFUND_NO = "R202608181200000000001";

    private static KeyPair KP;
    private static PrivateKey PLATFORM_KEY;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
        try (var in = WxpayNotifyHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
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

    /** 微信官方通知报文：外层 resource 用 apiV3Key 加密明文 JSON，头按官方格式签名。 */
    private static InvokeContext notifyCtx(String plainJson, boolean refund) throws Exception {
        var ciphertext = aesGcmEncrypt("notify-nonce", "notify-aad", plainJson, API_V3_KEY);
        var envelope = "{\"id\":\"EVT1\",\"event_type\":\"TRANSACTION.SUCCESS\","
                + "\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"nonce\":\"notify-nonce\",\"associated_data\":\"notify-aad\","
                + "\"ciphertext\":\"" + ciphertext + "\"}}";
        var timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        var hdrNonce = "notify-hdr-nonce";
        var signature = RsaKeys.sign(timestamp + "\n" + hdrNonce + "\n" + envelope + "\n", PLATFORM_KEY);
        var headers = new LinkedHashMap<String, String>();
        headers.put("wechatpay-timestamp", timestamp);
        headers.put("wechatpay-nonce", hdrNonce);
        headers.put("wechatpay-serial", "SERIAL1");
        headers.put("wechatpay-signature", signature);

        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).build())
                .refund(refund ? RefundSnapshot.builder().refundNo(REFUND_NO).tradeNo(TRADE_NO).build() : null)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .oauthWxConfigured(false).build())
                .request(RequestSnapshot.builder().body(envelope.getBytes(StandardCharsets.UTF_8))
                        .headers(headers).build())
                .build();
    }

    private static byte[] cfgRaw() throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfg.put("appkey", "CERT_SERIAL_1");
        cfg.put("biztype", "1");
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    /** 平台证书下载 stub（client 懒加载）。 */
    private static void stubPlatformCertDownload(MockedStatic<HttpHelper> mocked) throws Exception {
        var certPem = new String(
                WxpayNotifyHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
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

    private static String payPlain(String tradeState) {
        return "{\"out_trade_no\":\"" + TRADE_NO + "\",\"trade_state\":\"" + tradeState + "\","
                + "\"transaction_id\":\"TX1\",\"amount\":{\"total\":30000},"
                + "\"payer\":{\"openid\":\"OPENID1\"}}";
    }

    // =========================================================================
    // 支付通知：非成功状态只应答
    // =========================================================================

    @Test
    @DisplayName("支付通知 CLOSED → 应答成功且不写任何状态/结果（支付订单无失败终态）")
    void payClosedAckedWithoutWrite() throws Exception {
        assertPayNonSuccess("CLOSED");
    }

    @Test
    @DisplayName("支付通知 REVOKED → 应答成功且不写任何状态/结果")
    void payRevokedAckedWithoutWrite() throws Exception {
        assertPayNonSuccess("REVOKED");
    }

    @Test
    @DisplayName("支付通知 PAYERROR → 应答成功且不写任何状态/结果")
    void payErrorAckedWithoutWrite() throws Exception {
        assertPayNonSuccess("PAYERROR");
    }

    private void assertPayNonSuccess(String tradeState) throws Exception {
        var ctx = notifyCtx(payPlain(tradeState), false);
        var cb = ctx.getCallback();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            assertThat(((NotifyResponse.Ok) resp).body()).isEqualTo("{\"code\":\"SUCCESS\"}");
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("支付通知 SUCCESS + 金额一致 → 推进主单 S_OK（apiNo=transaction_id + buyer）")
    void paySuccessCompletes() throws Exception {
        var ctx = notifyCtx(payPlain("SUCCESS"), false);
        var cb = ctx.getCallback();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            assertThat(done.getValue().getBizType()).isEqualTo(BizType.T_PAY);
            assertThat(done.getValue().getState()).isEqualTo(BizState.S_OK);
            assertThat(done.getValue().getApiNo()).isEqualTo("TX1");
            assertThat(done.getValue().getBuyer()).isEqualTo("OPENID1");
        }
    }

    @Test
    @DisplayName("支付通知验签失败 → 应答 FAIL（渠道重试），不推进任何状态")
    void payBadSignatureFails() throws Exception {
        var ctx = notifyCtx(payPlain("SUCCESS"), false);
        // 篡改签名头 → 验签失败
        ctx.getRequest().getHeaders().put("wechatpay-signature", "bad-signature");
        var cb = ctx.getCallback();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(failBody(resp)).isEqualTo("{\"code\":\"FAIL\",\"message\":\"notify_error\"}");
            verify(cb, never()).completeBiz(any());
        }
    }

    // =========================================================================
    // 退款通知：按渠道退款状态推进（单笔）
    // =========================================================================

    @Test
    @DisplayName("退款通知 CLOSED（退款关闭，官方定义即退款失败）→ 退款单 S_FAIL")
    void refundClosedFails() throws Exception {
        var ctx = refundCtx("{\"out_refund_no\":\"" + REFUND_NO + "\",\"refund_status\":\"CLOSED\","
                + "\"refund_id\":\"RF-CLOSED\",\"transaction_id\":\"TXREF1\"}");
        when(ctx.getCallback().getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            var d = capturedComplete(ctx).getValue();
            assertThat(d.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(d.getCode()).isEqualTo("CLOSED");
            assertThat(d.getBizNo()).isEqualTo(REFUND_NO);
        }
    }

    @Test
    @DisplayName("退款通知 ABNORMAL（官方定义非终态，可走异常退款）→ 退款单 S_WAIT")
    void refundAbnormalWaits() throws Exception {
        var ctx = refundCtx("{\"out_refund_no\":\"" + REFUND_NO + "\",\"refund_status\":\"ABNORMAL\","
                + "\"refund_id\":\"RF-ABNORMAL\"}");
        when(ctx.getCallback().getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            var d = capturedComplete(ctx).getValue();
            assertThat(d.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(d.getCode()).isEqualTo("ABNORMAL");
            assertThat(d.getApiNo()).isEqualTo("RF-ABNORMAL");
        }
    }

    @Test
    @DisplayName("退款通知 PROCESSING（非终态）→ 退款单 S_WAIT，不判失败")
    void refundProcessingWaits() throws Exception {
        var ctx = refundCtx("{\"out_refund_no\":\"" + REFUND_NO + "\",\"refund_status\":\"PROCESSING\","
                + "\"refund_id\":\"RF-PROCESSING\"}");
        when(ctx.getCallback().getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            var d = capturedComplete(ctx).getValue();
            assertThat(d.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(d.getCode()).isEqualTo("PROCESSING");
            assertThat(d.getApiNo()).isEqualTo("RF-PROCESSING");
        }
    }

    @Test
    @DisplayName("退款通知 SUCCESS → 退款单 S_OK（apiNo=refund_id）")
    void refundSuccessCompletes() throws Exception {
        var ctx = refundCtx("{\"out_refund_no\":\"" + REFUND_NO + "\",\"refund_status\":\"SUCCESS\","
                + "\"refund_id\":\"RF9\",\"transaction_id\":\"TXREF9\"}");
        when(ctx.getCallback().getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            new WxpayNotifyHandler().refundNotify(ctx);

            var d = capturedComplete(ctx).getValue();
            assertThat(d.getState()).isEqualTo(BizState.S_OK);
            assertThat(d.getApiNo()).isEqualTo("RF9");
        }
    }

    private static InvokeContext refundCtx(String plainJson) throws Exception {
        return notifyCtx(plainJson, true);
    }

    private static ArgumentCaptor<CompleteBizRequest> capturedComplete(InvokeContext ctx) {
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(ctx.getCallback()).completeBiz(captor.capture());
        return captor;
    }

    private static String failBody(NotifyResponse response) {
        assertThat(response).isInstanceOf(NotifyResponse.Fail.class);
        return ((NotifyResponse.Fail) response).body();
    }
}
