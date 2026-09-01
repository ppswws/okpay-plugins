package com.okpay.plugin.helipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.helipay.util.HelipaySignUtil;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * HelipayQueryHandler 四类查询状态机：订单/退款/打款按渠道状态映射 S_OK/S_FAIL/S_ING
 * （渠道业务失败与异常保持处理中交还轮询）；余额查询失败直接失败返回，余额取 rt15_amountToBeSettled。
 */
@DisplayName("HelipayQueryHandler 查询状态机")
class HelipayQueryHandlerTest {

    private static final String KEY = "HLKEY1";

    // =========================================================================
    // 订单查询
    // =========================================================================

    @Test
    @DisplayName("查单 SUCCESS → S_OK（apiNo=rt6、buyer=rt11_openId）；携带 P4_serialNumber（有渠道单号时）")
    void queryOrderSuccess() throws Exception {
        var ctx = orderCtx("SN1");
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayQuery", Map.of(
                    "rt4_customerNumber", "HL1", "rt5_orderId", "T1",
                    "rt6_serialNumber", "SN1", "rt7_orderStatus", "SUCCESS",
                    "rt8_orderAmount", "1.00", "rt9_currency", "CNY",
                    "rt11_openId", "wxOPENID_MP1")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("SN1");
            assertThat(result.getBuyer()).isEqualTo("wxOPENID_MP1");
            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayQuery")
                .containsEntry("P2_orderId", "T1")
                .containsEntry("P4_serialNumber", "SN1");
    }

    @Test
    @DisplayName("查单无渠道单号 → 不携带 P4_serialNumber")
    void queryOrderWithoutSerialOmitsP4() throws Exception {
        var ctx = orderCtx(null);
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayQuery", Map.of(
                    "rt5_orderId", "T1", "rt7_orderStatus", "SUCCESS")));
            new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        assertThat(PaymentUtils.parseForm(bodyCaptor.getValue())).doesNotContainKey("P4_serialNumber");
    }

    @Test
    @DisplayName("查单 FAIL/CLOSE/CANCEL → S_FAIL")
    void queryOrderFailed() throws Exception {
        var ctx = orderCtx("SN1");
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayQuery", Map.of(
                    "rt5_orderId", "T1", "rt7_orderStatus", "CLOSE")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
        }
    }

    @Test
    @DisplayName("查单处理中状态 → S_ING（保持轮询）")
    void queryOrderIng() throws Exception {
        var ctx = orderCtx("SN1");
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayQuery", Map.of(
                    "rt5_orderId", "T1", "rt7_orderStatus", "PAYING")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
        }
    }

    @Test
    @DisplayName("查单业务失败（rt2 非 0000）→ S_ING（携带渠道码与说明，交还轮询）")
    void queryOrderBizErrorKeepsIng() throws Exception {
        var ctx = orderCtx("SN1");
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayQuery", Map.of(
                    "rt2_retCode", "0005", "rt3_retMsg", "订单不存在", "rt5_orderId", "T1")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("0005");
            assertThat(result.getMsg()).isEqualTo("订单不存在");
        }
    }

    @Test
    @DisplayName("查单渠道异常 → S_ING QUERY_ERROR（不误判失败）")
    void queryOrderChannelErrorKeepsIng() throws Exception {
        var ctx = orderCtx("SN1");
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_PAY));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("QUERY_ERROR");
        }
    }

    // =========================================================================
    // 退款查询
    // =========================================================================

    @Test
    @DisplayName("退款查询 SUCCESS → S_OK（apiNo=rt7）；FAIL/CLOSE → S_FAIL；其余 → S_ING（msg=retReasonDesc）")
    void queryRefundStates() throws Exception {
        assertState(refundCtx(), Map.of("rt8_orderStatus", "SUCCESS", "rt7_serialNumber", "SN1"),
                BizState.S_OK, "SN1");
        assertState(refundCtx(), Map.of("rt8_orderStatus", "FAIL"), BizState.S_FAIL, null);
        assertState(refundCtx(), Map.of("rt8_orderStatus", "DOING",
                "retReasonDesc", "退费受理中"), BizState.S_ING, null);
    }

    @Test
    @DisplayName("退款查询业务失败 → S_ING")
    void queryRefundBizErrorKeepsIng() throws Exception {
        var ctx = refundCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayRefundQuery", Map.of(
                    "rt2_retCode", "0005", "rt3_retMsg", "退款单不存在", "rt5_orderId", "T1")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_REF));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("0005");
        }
    }

    // =========================================================================
    // 打款查询
    // =========================================================================

    @Test
    @DisplayName("打款查询 SUCCESS → S_OK；FAIL/REFUND → S_FAIL；其余 → S_ING（msg=rt8_reason）")
    void queryTransferStates() throws Exception {
        assertState(transferCtx(), Map.of("rt7_orderStatus", "SUCCESS", "rt6_serialNumber", "SN1"),
                BizState.S_OK, "SN1");
        assertState(transferCtx(), Map.of("rt7_orderStatus", "REFUND"), BizState.S_FAIL, null);
        assertState(transferCtx(), Map.of("rt7_orderStatus", "DOING",
                "rt8_reason", "银行处理中"), BizState.S_ING, null);
    }

    // =========================================================================
    // 余额查询（商户接口）
    // =========================================================================

    @Test
    @DisplayName("余额查询 0000 → S_OK（余额=rt15_amountToBeSettled，走商户接口）")
    void balanceSuccess() throws Exception {
        var ctx = balanceCtx();
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.equals("https://pay.trx.helipay.com/trx/merchant/interface.action"))
                    return signedOk("MerchantAccountQuery", Map.of(
                            "rt3_retMsg", "查询成功", "rt4_customerNumber", "HL1",
                            "rt5_accountStatus", "ACTIVE", "rt6_balance", "100.00",
                            "rt7_frozenBalance", "0.00", "rt10_currency", "CNY",
                            "rt15_amountToBeSettled", "88.50"));
                throw new IllegalStateException("unexpected POST " + url);
            });
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_BAL));

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getBalance()).isEqualTo("88.50");
            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "MerchantAccountQuery")
                .containsEntry("P2_customerNumber", "HL1");
        assertThat(form.get("P3_timestamp")).matches("\\d{14}");
    }

    @Test
    @DisplayName("余额查询 rt15 为空 → fail BAL_ERROR 余额为空")
    void balanceEmptyFails() throws Exception {
        var ctx = balanceCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    signedOk("MerchantAccountQuery", Map.of("rt3_retMsg", "查询成功")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_BAL));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("BAL_ERROR");
            assertThat(result.getMsg()).isEqualTo("余额为空");
        }
    }

    @Test
    @DisplayName("余额查询业务失败 → fail（渠道错误码与说明）")
    void balanceBizErrorFails() throws Exception {
        var ctx = balanceCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    signedOk("MerchantAccountQuery", Map.of(
                            "rt2_retCode", "0003", "rt3_retMsg", "余额不足")));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_BAL));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("0003");
            assertThat(result.getMsg()).isEqualTo("余额不足");
        }
    }

    @Test
    @DisplayName("余额查询渠道异常 → fail BAL_ERROR")
    void balanceChannelErrorFails() throws Exception {
        var ctx = balanceCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new HelipayQueryHandler().handle(ctx, req(BizType.T_BAL));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("BAL_ERROR");
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 多状态共用断言：按响应字段跑一次查询并核对状态/apiNo。 */
    private void assertState(InvokeContext ctx, Map<String, String> extra, BizState expect, String apiNo)
            throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk(queryBizType(ctx), extra));
            var result = new HelipayQueryHandler().handle(ctx, req(bizTypeOf(ctx)));

            assertThat(result.getState()).isEqualTo(expect);
            if (apiNo != null) assertThat(result.getApiNo()).isEqualTo(apiNo);
        }
    }

    private static String queryBizType(InvokeContext ctx) {
        return ctx.getRefund() != null ? "AppPayRefundQuery" : "TransferQuery";
    }

    private static BizType bizTypeOf(InvokeContext ctx) {
        return ctx.getRefund() != null ? BizType.T_REF : BizType.T_XFER;
    }

    private static BizRequest req(BizType bizType) {
        return BizRequest.builder().bizType(bizType).build();
    }

    private InvokeContext orderCtx(String apiTradeNo) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").apiTradeNo(apiTradeNo).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com").build())
                .build();
    }

    private InvokeContext refundCtx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").apiRefundNo("SN1").build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com").build())
                .build();
    }

    private InvokeContext transferCtx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .transfer(TransferSnapshot.builder().tradeNo("T1").build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com").build())
                .build();
    }

    private InvokeContext balanceCtx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com").build())
                .build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "HL1");
            cfg.put("appkey", KEY);
            cfg.put("biztype", "1");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    private static void stubFormApi(MockedStatic<HttpHelper> mocked, HttpHelper.HttpResponse resp) throws Exception {
        mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            var url = inv.getArgument(1).toString();
            if (url.equals("https://pay.trx.helipay.com/trx/app/interface.action"))
                return resp;
            throw new IllegalStateException("unexpected POST " + url);
        });
    }

    private static HttpHelper.HttpResponse signedOk(String bizType, Map<String, String> fields) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("rt1_bizType", bizType);
        m.put("rt2_retCode", "0000");
        m.putAll(fields);
        m.put("sign", HelipaySignUtil.signResponse(m, KEY));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }
}
