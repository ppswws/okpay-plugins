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
 * WxpayQueryHandler 合单分支：聚合查询（QueryScanner 兜底），
 * 全 SUCCESS → 逐子单已付 + 主单完成；含未付 → ing；含关闭 → fail；数量/金额不符 → fail。
 * 退款查询（T_REF）：主单有子单 → 复用 refundCombine 逐单续退。
 */
@DisplayName("WxpayQueryHandler 合单查询")
class WxpayCombineQueryHandlerTest {

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
        try (var in = WxpayCombineQueryHandlerTest.class.getResourceAsStream("/certs/platform_test_key.pem")) {
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

    private static byte[] cfgRaw(String subMchId) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appmchid", MCH_ID);
        cfg.put("appsecret", API_V3_KEY);
        cfg.put("privateKey", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfg.put("appkey", "CERT_SERIAL_1");
        cfg.put("biztype", "1");
        cfg.put("wxcombine_open", true);
        if (subMchId != null) cfg.put("sub_mchid", subMchId);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(String subMchId, RefundSnapshot refund) {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull(subMchId)).build())
                .refund(refund)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build());
        if (refund != null) {
            b.order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).apiTradeNo("TX1").build());
        }
        return b.build();
    }

    private static byte[] cfgRawOrNull(String subMchId) {
        try {
            return cfgRaw(subMchId);
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

    /** 查询响应 stub：/v3/certificates → 证书下载；其余按 URL 前缀匹配返回验签 JSON。 */
    private static void stubGet(MockedStatic<HttpHelper> mocked, String queryJson) throws Exception {
        stubGet(mocked, Map.of("/v3/combine-transactions", queryJson));
    }

    private static void stubGet(MockedStatic<HttpHelper> mocked, Map<String, String> byPrefix)
            throws Exception {
        var certPem = new String(
                WxpayCombineQueryHandlerTest.class.getResourceAsStream("/certs/platform_test_cert.pem")
                        .readAllBytes(), StandardCharsets.UTF_8);
        var certCipher = aesGcmEncrypt("cert-nonce", "cert-aad", certPem, API_V3_KEY);
        var certJson = "{\"data\":[{\"serial_no\":\"SERIAL1\",\"encrypt_certificate\":{"
                + "\"nonce\":\"cert-nonce\",\"associated_data\":\"cert-aad\","
                + "\"ciphertext\":\"" + certCipher + "\"}}]}";
        var certResp = new HttpHelper.HttpResponse(200, Map.of(),
                certJson.getBytes(StandardCharsets.UTF_8), "req", 10, 1);
        mocked.when(() -> HttpHelper.get(any(), any(), any())).thenAnswer(inv -> {
            var url = inv.getArgument(1).toString();
            if (url.contains("/v3/certificates")) return certResp;
            for (var e : byPrefix.entrySet()) {
                if (url.contains(e.getKey())) return signedOk(e.getValue());
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

    private static String subJson(int idx, String txId, String state, long amount) {
        return "{\"out_trade_no\":\"" + Sdk.subTradeNo(TRADE_NO, idx) + "\","
                + "\"transaction_id\":\"" + txId + "\",\"trade_state\":\"" + state + "\","
                + "\"amount\":{\"total_amount\":" + amount + "}}";
    }

    private static String combineQueryPlain(String... subsJson) {
        return "{\"combine_out_trade_no\":\"" + TRADE_NO + "\","
                + "\"sub_orders\":[" + String.join(",", subsJson) + "],"
                + "\"combine_payer_info\":{\"openid\":\"OPENID1\"}}";
    }

    // =========================================================================
    // 合单订单查询
    // =========================================================================

    @Test
    @DisplayName("全 SUCCESS + 金额一致 → S_OK（apiNo=首个子单）+ 逐子单已付标记")
    void allSuccessCompletes() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "TX2", "SUCCESS", 10000),
                    subJson(3, "TX3", "SUCCESS", 10000)));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TX1");
            assertThat(result.getBuyer()).isEqualTo("OPENID1");
            var updates = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb, times(3)).updateSubOrder(updates.capture());
            assertThat(updates.getAllValues()).extracting(u -> u.getSubTradeNo())
                    .containsExactly(Sdk.subTradeNo(TRADE_NO, 1), Sdk.subTradeNo(TRADE_NO, 2),
                            Sdk.subTradeNo(TRADE_NO, 3));
            assertThat(updates.getAllValues()).allMatch(u -> u.getStatus() == SubOrderSnapshot.STATUS_PAID);
        }
    }

    @Test
    @DisplayName("含未支付子单 → S_ING（保持待支付状态，等 QueryScanner 再扫）")
    void notPayIng() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "", "NOTPAY", 10000),
                    subJson(3, "", "USERPAYING", 10000)));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("含已关闭子单 → S_FAIL")
    void closedFails() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "", "CLOSED", 10000),
                    subJson(3, "", "SUCCESS", 10000)));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("CLOSED");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("子单数量不符 → S_FAIL COMBINE_MISMATCH")
    void countMismatchFails() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "TX2", "SUCCESS", 10000)));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("COMBINE_MISMATCH");
        }
    }

    @Test
    @DisplayName("子单金额不符 → S_FAIL COMBINE_MISMATCH")
    void amountMismatchFails() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "TX2", "SUCCESS", 9999),
                    subJson(3, "TX3", "SUCCESS", 10000)));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("COMBINE_MISMATCH");
        }
    }

    @Test
    @DisplayName("合单查询 URL 无任何 Query 参数（官方契约仅 Path 参数 combine_out_trade_no）")
    void serviceProviderQueryUrl() throws Exception {
        var ctx = ctx("SUB1001", null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, combineQueryPlain(
                    subJson(1, "TX1", "SUCCESS", 10000),
                    subJson(2, "TX2", "SUCCESS", 10000),
                    subJson(3, "TX3", "SUCCESS", 10000)));
            var urlCaptor = ArgumentCaptor.forClass(String.class);

            new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            mocked.verify(() -> HttpHelper.get(any(), urlCaptor.capture(), any()), org.mockito.Mockito.atLeastOnce());
            var urls = urlCaptor.getAllValues();
            assertThat(urls).anyMatch(u -> u.contains("/v3/combine-transactions/out-trade-no/" + TRADE_NO)
                    && !u.contains("?"));
            assertThat(urls).noneMatch(u -> u.contains("/v3/combine-transactions/")
                    && (u.contains("sp_mchid=") || u.contains("sub_mchid=") || u.contains("mchid=")));
        }
    }

    @Test
    @DisplayName("无子单 → 走单笔查询路径（不聚合）")
    void noSubsFallsBackToSingle() throws Exception {
        var ctx = ctx(null, null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, Map.of("/v3/pay/transactions/out-trade-no",
                    "{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"TX9\","
                            + "\"payer\":{\"openid\":\"O1\"}}"));
            var urlCaptor = ArgumentCaptor.forClass(String.class);

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TX9");
            mocked.verify(() -> HttpHelper.get(any(), urlCaptor.capture(), any()), org.mockito.Mockito.atLeastOnce());
            assertThat(urlCaptor.getAllValues())
                    .anyMatch(u -> u.contains("/v3/pay/transactions/out-trade-no/"));
        }
    }

    // =========================================================================
    // 合单退款查询（续退）
    // =========================================================================

    @Test
    @DisplayName("T_REF 且主单有子单 → 复用 refundCombine 逐单续退（S_OK）")
    void queryRefundContinuesCombine() throws Exception {
        var refund = RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(30000).build();
        var ctx = ctx(null, refund);
        var cb = ctx.getCallback();
        // 第 1 单已退满 → 仅续退 2/3
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 10000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, Map.of());
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenAnswer(inv -> signedOk("{\"status\":\"SUCCESS\",\"transaction_id\":\"TX\"}"));

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            assertThat(HttpHelper.MAPPER.readTree(bodies.get(0)).path("out_refund_no").asString())
                    .isEqualTo(REFUND_NO + "_2");
        }
    }

    @Test
    @DisplayName("T_REF 且主单无子单 → 走单笔退款查询")
    void queryRefundWithoutSubs() throws Exception {
        var refund = RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(30000).build();
        var ctx = ctx(null, refund);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubGet(mocked, Map.of("/v3/refund/domestic/refunds",
                    "{\"status\":\"SUCCESS\",\"transaction_id\":\"TXREF9\"}"));
            var urlCaptor = ArgumentCaptor.forClass(String.class);

            var result = new WxpayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TXREF9");
            mocked.verify(() -> HttpHelper.get(any(), urlCaptor.capture(), any()), org.mockito.Mockito.atLeastOnce());
            assertThat(urlCaptor.getAllValues())
                    .anyMatch(u -> u.contains("/v3/refund/domestic/refunds/" + REFUND_NO));
        }
    }
}
