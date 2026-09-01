package com.okpay.plugin.alipay.handler;

import com.fasterxml.jackson.core.type.TypeReference;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
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
 * AlipaySubmitHandler 合单退款：逐子单 alipay.trade.refund（out_request_no=refundNo_序号，
 * quota=min(remaining, money-refundMoney)）。全退 S_OK / 首败 S_FAIL / 部分 S_ING。
 */
@DisplayName("AlipaySubmitHandler 合单退款")
class AlipayCombineSubmitHandlerTest {

    private static final String APP_ID = "2021000000000001";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String REFUND_NO = "R202608181200000000001";
    private static KeyPair KP;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private static byte[] cfgRaw() throws Exception {
        return HttpHelper.MAPPER.writeValueAsBytes(Map.of(
                "appid", APP_ID,
                "appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()),
                "appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded())));
    }

    private static byte[] cfgRawOrNull() {
        try {
            return cfgRaw();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InvokeContext ctx(RefundSnapshot refund) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull()).build())
                .refund(refund)
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).apiTradeNo("TX").combine(true).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    private static List<SubOrderSnapshot> subs(long money, long... refunded) {
        var out = new ArrayList<SubOrderSnapshot>(refunded.length);
        for (int i = 0; i < refunded.length; i++) {
            out.add(SubOrderSnapshot.builder()
                    .subTradeNo(Sdk.subTradeNo(TRADE_NO, i + 1))
                    .tradeNo(TRADE_NO).money(money).refundMoney(refunded[i]).build());
        }
        return out;
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 从请求表单体解析 biz_content */
    private static Map<String, Object> parseBizContent(String formBody) throws Exception {
        var params = new LinkedHashMap<String, String>();
        for (var pair : formBody.split("&")) {
            var idx = pair.indexOf("=");
            if (idx > 0) params.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return HttpHelper.MAPPER.readValue(params.get("biz_content"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static String subNo(int idx) {
        return Sdk.subTradeNo(TRADE_NO, idx);
    }

    private static RefundSnapshot refund(long amount) {
        return RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(amount).build();
    }

    @Test
    @DisplayName("全子单退成功 → S_OK（apiNo=最后一次渠道交易号）+ 逐单累计")
    void allSubsSuccessCompletes() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_refund_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TXREF\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TXREF");
            // 逐单退款：out_trade_no=子单号、out_request_no=refundNo_序号、金额=子单额度
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            assertThat(parseBizContent(bodies.get(0)))
                    .containsEntry("out_trade_no", subNo(1))
                    .containsEntry("out_request_no", REFUND_NO + "_1")
                    .containsEntry("refund_amount", "150.00");
            assertThat(parseBizContent(bodies.get(1)))
                    .containsEntry("out_trade_no", subNo(2))
                    .containsEntry("out_request_no", REFUND_NO + "_2");
            var updates = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb, times(2)).updateSubOrder(updates.capture());
            assertThat(updates.getAllValues()).extracting(u -> u.getRefundDelta())
                    .containsExactly(15000L, 15000L);
        }
    }

    @Test
    @DisplayName("重跑续退：已退满子单跳过，仅续退剩余（out_request_no 序号按子单位置）")
    void rerunSkipsFullyRefundedSubs() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        // 第 1 单已退满 → 仅退第 2 单
        when(cb.getSubOrders(any())).thenReturn(subs(15000, 15000, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_refund_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TXREF\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(1);
            assertThat(parseBizContent(bodies.get(0)))
                    .containsEntry("out_trade_no", subNo(2))
                    .containsEntry("out_request_no", REFUND_NO + "_2");
        }
    }

    @Test
    @DisplayName("部分已退：quota=min(remaining, money-refundMoney)，退满即停")
    void partialRefundedQuotaCapped() throws Exception {
        var ctx = ctx(refund(15000));
        var cb = ctx.getCallback();
        // 子单 [10000: 5000已退, 10000, 10000]，退款总额 15000 → 已退 5000 抵扣后
        // remaining=10000：_1 补 5000、_2 退 5000、_3 不触发
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 5000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_refund_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TXREF\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            assertThat(parseBizContent(bodies.get(0)))
                    .containsEntry("out_request_no", REFUND_NO + "_1")
                    .containsEntry("refund_amount", "50.00");
            assertThat(parseBizContent(bodies.get(1)))
                    .containsEntry("out_request_no", REFUND_NO + "_2")
                    .containsEntry("refund_amount", "50.00");
        }
    }

    @Test
    @DisplayName("首个子单即被拒（无任何成功）→ S_FAIL REFUND_FAIL（宿主回滚余额）")
    void firstSubRejectedFails() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_refund_response",
                                    "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                            + "\"sub_code\":\"REFUND_AMOUNT_NOT_ENOUGH\",\"sub_msg\":\"可退金额不足\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("REFUND_FAIL");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("中途失败（部分成功）→ S_ING REFUND_PARTIAL（余额已扣，靠查单续退）")
    void partialFailureIng() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var biz = parseBizContent(inv.getArgument(2).toString());
                var ok = subNo(1).equals(String.valueOf(biz.get("out_trade_no")));
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_refund_response", ok
                                ? "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TXREF1\"}"
                                : "{\"code\":\"40004\",\"msg\":\"Business Failed\",\"sub_code\":\"ERROR\",\"sub_msg\":\"失败\"}"),
                        "req", 10, 1);
            });

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("REFUND_PARTIAL");
            verify(cb, times(1)).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("已全部退完 → 无网络直接 S_OK（重跑幂等）")
    void fullyRefundedRerunOk() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000, 15000, 15000));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("合单订单子单数据缺失 → 拒退不静默降级单笔（REFUND_ERROR）")
    void noSubsRejectsRefund() throws Exception {
        var ctx = ctx(refund(30000));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("REFUND_ERROR");
            // 数据不一致必须显性暴露，不得回退单笔退款（防子单漏拆导致误退超限）
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
    }
}
