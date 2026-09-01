package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WxpayNotifyHandler 合单分支：fail-closed（子单数/状态/金额任一不符拒收等重发），
 * 全通过 → 逐子单已付 + 主单完成；合单退款通知按 out_refund_no 序号累计子单，全退完才推进主退款单。
 */
@DisplayName("WxpayNotifyHandler 合单通知")
class WxpayCombineNotifyHandlerTest {

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
        try (var in = WxpayCombineNotifyHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
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

    /** 微信官方通知报文：外层 resource 用 apiV3Key 加密明文 JSON，头按官方格式签名（平台证书公钥验签）。 */
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

        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).build())
                .refund(refund ? RefundSnapshot.builder().refundNo(REFUND_NO).tradeNo(TRADE_NO).build() : null)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .oauthWxConfigured(false).build())
                .request(RequestSnapshot.builder().body(envelope.getBytes(StandardCharsets.UTF_8))
                        .headers(headers).build());
        return b.build();
    }

    /** 通道配置：合单开启 + 商户密钥 + 平台证书（懒加载需 mock /v3/certificates）。 */
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

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    /** 平台证书下载 stub：client 懒加载，/v3/certificates 响应经 apiV3Key 加密。 */
    private static void stubPlatformCertDownload(MockedStatic<HttpHelper> mocked) throws Exception {
        var certPem = new String(
                WxpayCombineNotifyHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
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

    private static SubOrderSnapshot sub(int idx, long money, long refunded) {
        return SubOrderSnapshot.builder()
                .subTradeNo(Sdk.subTradeNo(TRADE_NO, idx))
                .tradeNo(TRADE_NO)
                .money(money)
                .refundMoney(refunded)
                .build();
    }

    private static List<SubOrderSnapshot> subs(long money, long refunded) {
        return List.of(sub(1, money, refunded), sub(2, money, refunded), sub(3, money, refunded));
    }

    private static String subJson(int idx, String txId, String state, long amount) {
        return "{\"out_trade_no\":\"" + Sdk.subTradeNo(TRADE_NO, idx) + "\","
                + "\"transaction_id\":\"" + txId + "\",\"trade_state\":\"" + state + "\","
                + "\"amount\":{\"total_amount\":" + amount + "}}";
    }

    private static String combinePayPlain(String... subsJson) {
        return "{\"combine_out_trade_no\":\"" + TRADE_NO + "\","
                + "\"sub_orders\":[" + String.join(",", subsJson) + "],"
                + "\"combine_payer_info\":{\"openid\":\"OPENID1\"}}";
    }

    // =========================================================================
    // 合单支付通知
    // =========================================================================

    @Test
    @DisplayName("全 SUCCESS + 金额逐单一致 + 合计==实付 → 逐子单已付 + 主单完成（buyer=渠道 openid）")
    void allSuccessCompletesMainOrder() throws Exception {
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "SUCCESS", 10000),
                subJson(3, "TX3", "SUCCESS", 10000)), false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            // 逐子单已付标记：subTradeNo 与报文一致 + status=1 + 各子单 transaction_id
            var captor = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb, org.mockito.Mockito.times(3)).updateSubOrder(captor.capture());
            var updates = captor.getAllValues();
            assertThat(updates).extracting(u -> u.getSubTradeNo())
                    .containsExactly(Sdk.subTradeNo(TRADE_NO, 1), Sdk.subTradeNo(TRADE_NO, 2),
                            Sdk.subTradeNo(TRADE_NO, 3));
            assertThat(updates).allMatch(u -> u.getStatus() == SubOrderSnapshot.STATUS_PAID);
            assertThat(updates).extracting(UpdateSubOrderRequest::getApiTradeNo)
                    .containsExactly("TX1", "TX2", "TX3");
            // 主单完成：首个子单 transaction_id + buyer=combine_payer_info.openid（未配置系统公众号）
            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            var d = done.getValue();
            assertThat(d.getBizType()).isEqualTo(BizType.T_PAY);
            assertThat(d.getState()).isEqualTo(BizState.S_OK);
            assertThat(d.getBizNo()).isEqualTo(TRADE_NO);
            assertThat(d.getApiNo()).isEqualTo("TX1");
            assertThat(d.getBuyer()).isEqualTo("OPENID1");
        }
    }

    @Test
    @DisplayName("子单金额不符 → amount_mismatch 拒收，不推进任何子单/主单")
    void subAmountMismatchRejected() throws Exception {
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "SUCCESS", 9999),
                subJson(3, "TX3", "SUCCESS", 10000)), false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"FAIL\",\"message\":\"amount_mismatch\"}");
            assertThat(resp.getStatus()).isEqualTo(400);
            verify(cb, never()).updateSubOrder(any());
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("子单 trade_state 非 SUCCESS → amount_mismatch 拒收（修 epay 不校验子单状态之坑）")
    void subNotSuccessRejected() throws Exception {
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "USERPAYING", 10000),
                subJson(3, "TX3", "SUCCESS", 10000)), false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"FAIL\",\"message\":\"amount_mismatch\"}");
            assertThat(resp.getStatus()).isEqualTo(400);
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("通知子单数 != 库内子单数 → amount_mismatch 拒收")
    void subCountMismatchRejected() throws Exception {
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "SUCCESS", 10000)), false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"FAIL\",\"message\":\"amount_mismatch\"}");
            assertThat(resp.getStatus()).isEqualTo(400);
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("合计 != 实付 → amount_mismatch 拒收（修 epay 不校验合计之坑）")
    void sumMismatchRejected() throws Exception {
        // 单笔金额都等于子单金额，但合计 29000 != real 30000
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "SUCCESS", 10000),
                subJson(3, "TX3", "SUCCESS", 9000)), false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"FAIL\",\"message\":\"amount_mismatch\"}");
            assertThat(resp.getStatus()).isEqualTo(400);
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("combine_out_trade_no ≠ 订单号 → order_mismatch 拒收")
    void wrongMainTradeNoRejected() throws Exception {
        var plain = "{\"combine_out_trade_no\":\"P999999999999999999999\","
                + "\"sub_orders\":[" + subJson(1, "TX1", "SUCCESS", 10000) + "]}";
        var ctx = notifyCtx(plain, false);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().payNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"FAIL\",\"message\":\"order_mismatch\"}");
            assertThat(resp.getStatus()).isEqualTo(400);
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("已配置系统公众号 → 主单完成不带渠道 openid（buyer 不覆盖身份）")
    void oauthWxConfiguredSkipsBuyer() throws Exception {
        var ctx = notifyCtx(combinePayPlain(
                subJson(1, "TX1", "SUCCESS", 10000),
                subJson(2, "TX2", "SUCCESS", 10000),
                subJson(3, "TX3", "SUCCESS", 10000)), false);
        ctx.getConfig().setOauthWxConfigured(true);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            new WxpayNotifyHandler().payNotify(ctx);

            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            var d = done.getValue();
            assertThat(d.getBizType()).isEqualTo(BizType.T_PAY);
            assertThat(d.getState()).isEqualTo(BizState.S_OK);
            assertThat(d.getBizNo()).isEqualTo(TRADE_NO);
            assertThat(d.getApiNo()).isEqualTo("TX1");
            assertThat(d.getBuyer()).isNull();
        }
    }

    // =========================================================================
    // 合单退款通知
    // =========================================================================

    @Test
    @DisplayName("合单退款通知：out_refund_no 带序号 → 累计对应子单；全退完才推进主退款单")
    void refundNotifyAccumulatesAndCompletes() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "_2\","
                + "\"refund_status\":\"SUCCESS\",\"transaction_id\":\"TXREF2\","
                + "\"amount\":{\"refund\":10000}}", true);
        var cb = ctx.getCallback();
        // 第一次读：全部未退；第二次读（推进检查）：第 2 笔已累计 10000，其余仍 0 → 不全退，不推进
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0)).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            var captor = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb).updateSubOrder(captor.capture());
            var u = captor.getValue();
            assertThat(u.getTradeNo()).isEqualTo(TRADE_NO);
            assertThat(u.getSubTradeNo()).isEqualTo(Sdk.subTradeNo(TRADE_NO, 2));
            assertThat(u.getRefundDelta()).isEqualTo(10000);
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("全部子单退完 → 推进主退款单 S_OK")
    void refundNotifyCompletesWhenAllRefunded() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "_3\","
                + "\"refund_status\":\"SUCCESS\",\"transaction_id\":\"TXREF3\","
                + "\"amount\":{\"refund\":10000}}", true);
        var cb = ctx.getCallback();
        // 第一次读：2/3 已退；after 读：全退（本次通知累计第 3 笔后满额）
        var twoRefunded = List.of(sub(1, 10000, 10000), sub(2, 10000, 10000), sub(3, 10000, 0));
        var allRefunded = List.of(sub(1, 10000, 10000), sub(2, 10000, 10000), sub(3, 10000, 10000));
        when(cb.getSubOrders(any())).thenReturn(twoRefunded).thenReturn(allRefunded);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            var d = done.getValue();
            assertThat(d.getBizType()).isEqualTo(BizType.T_REF);
            assertThat(d.getState()).isEqualTo(BizState.S_OK);
            assertThat(d.getBizNo()).isEqualTo(REFUND_NO);
            assertThat(d.getApiNo()).isEqualTo("TXREF3");
        }
    }

    @Test
    @DisplayName("通知重发（子单已累计到额）→ 超退累计被拒捕获，仍按已满状态推进，不重复完成")
    void duplicateRefundNotifyIdempotent() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "_1\","
                + "\"refund_status\":\"SUCCESS\",\"transaction_id\":\"TXREF1\","
                + "\"amount\":{\"refund\":10000}}", true);
        var cb = ctx.getCallback();
        // 已全退：重发时 updateSubOrder 超退抛 IllegalStateException → 捕获幂等
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 10000));
        org.mockito.Mockito.doThrow(new IllegalStateException("超退")).when(cb).updateSubOrder(any());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            assertThat(done.getValue().getBizNo()).isEqualTo(REFUND_NO);
            assertThat(done.getValue().getState()).isEqualTo(BizState.S_OK);
        }
    }

    @Test
    @DisplayName("退款通知金额缺失 → 不累计不推进（防误走已付分支，靠查单续退兜底）")
    void refundNotifyMissingAmountSkips() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "_1\","
                + "\"refund_status\":\"SUCCESS\",\"transaction_id\":\"TXREF1\"}", true);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            verify(cb, never()).updateSubOrder(any());
            verify(cb, never()).completeBiz(any());
        }
    }

    @Test
    @DisplayName("refund_status 非 SUCCESS（合单）→ 不判主单失败，由查单续退兜底")
    void refundNotifyFailStatus() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "_1\","
                + "\"refund_status\":\"REFUNDCLOSE\",\"amount\":{\"refund\":10000}}", true);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            // 合单退款逐子单提交：单条子单的非成功通知（PROCESSING 受理中先于 SUCCESS 到达、
            // REFUNDCLOSE 子单被拒）不判主单 FAIL——否则后续成功通知无法恢复终态且部分已退被回滚，
            // 终态由查单续退兜底
            verify(cb, never()).completeBiz(any());
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("refund_status 非 SUCCESS（单笔）→ 主退款单失败")
    void refundNotifyFailStatusSingleRefund() throws Exception {
        var ctx = notifyCtx("{\"out_refund_no\":\"" + REFUND_NO + "\","
                + "\"refund_status\":\"REFUNDCLOSE\",\"amount\":{\"refund\":10000}}", true);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubPlatformCertDownload(mocked);

            var resp = new WxpayNotifyHandler().refundNotify(ctx);

            assertThat(resp.getDataText()).isEqualTo("{\"code\":\"SUCCESS\"}");
            assertThat(resp.getStatus()).isEqualTo(200);
            var done = ArgumentCaptor.forClass(CompleteBizRequest.class);
            verify(cb).completeBiz(done.capture());
            var d = done.getValue();
            assertThat(d.getBizType()).isEqualTo(BizType.T_REF);
            assertThat(d.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(d.getBizNo()).isEqualTo(REFUND_NO);
            assertThat(d.getCode()).isEqualTo("REFUND_FAIL");
            assertThat(d.getMsg()).isEqualTo("REFUNDCLOSE");
            verify(cb, never()).updateSubOrder(any());
        }
    }
}
