package com.okpay.plugin.helipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.helipay.util.HelipaySignUtil;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
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
 * HelipaySubmitHandler 退款/打款：业务码状态机（退款 0000 成功、0001/0002 处理中；
 * 打款 0000/0001 受理成功交还轮询）、打款银行编码表单直传（含联行号条件字段）、
 * 渠道异常保持处理中不误判失败。
 */
@DisplayName("HelipaySubmitHandler 退款/打款")
class HelipaySubmitHandlerTest {

    private static final String KEY = "HLKEY1";

    // =========================================================================
    // 退款
    // =========================================================================

    @Test
    @DisplayName("退款 0000 → S_OK（携带渠道流水号），参数含 P4_refundOrderId/P6_callbackUrl")
    void refundAccepted() throws Exception {
        var ctx = refundCtx();
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayRefund", Map.of(
                    "rt4_customerNumber", "HL1", "rt5_orderId", "T1",
                    "rt6_refundOrderNum", "R1", "rt7_serialNumber", "SN1",
                    "rt8_amount", "1.00", "rt9_currency", "CNY")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_REF));

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("SN1");
            assertThat(result.getCode()).isEqualTo("0000");
            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayRefund")
                .containsEntry("P2_orderId", "T1")
                .containsEntry("P4_refundOrderId", "R1")
                .containsEntry("P5_amount", "1.00")
                .containsEntry("P6_callbackUrl", "https://pay.example.com/pay/refundnotify/R1");
    }

    @Test
    @DisplayName("退款 0001/0002 → S_ING（结果由通知/查询推进）")
    void refundPending() throws Exception {
        var ctx = refundCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayRefund", Map.of(
                    "rt2_retCode", "0001", "rt3_retMsg", "处理中", "rt5_orderId", "T1")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_REF));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("0001");
            assertThat(result.getMsg()).isEqualTo("处理中");
        }
    }

    @Test
    @DisplayName("退款其余业务码 → S_FAIL")
    void refundRejected() throws Exception {
        var ctx = refundCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayRefund", Map.of(
                    "rt2_retCode", "9999", "rt3_retMsg", "参数错误", "rt5_orderId", "T1")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_REF));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("9999");
        }
    }

    @Test
    @DisplayName("退款渠道异常（502）→ S_ING REFUND_ERROR（状态未知不误判失败）")
    void refundChannelErrorKeepsIng() throws Exception {
        var ctx = refundCtx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_REF));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("REFUND_ERROR");
        }
    }

    // =========================================================================
    // 打款
    // =========================================================================

    @Test
    @DisplayName("打款 0000 → S_ING（受理成功，结果由通知/查询推进）；银行编码表单直传、联行号条件携带")
    void transferAcceptedBankCodeDirect() throws Exception {
        var ctx = transferCtx("ABC", "6222021234567890", "张三", "104100099999");
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("Transfer", Map.of(
                    "rt4_customerNumber", "HL1", "rt5_orderId", "T1", "rt6_serialNumber", "SN1")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getApiNo()).isEqualTo("SN1");
            assertThat(result.getCode()).isEqualTo("0000");
            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "Transfer")
                .containsEntry("P2_orderId", "T1")
                .containsEntry("P4_amount", "1.00")
                // 银行编码来自打款表单下拉（通道银行项目），直传不做名称映射
                .containsEntry("P5_bankCode", "ABC")
                .containsEntry("P6_bankAccountNo", "6222021234567890")
                .containsEntry("P7_bankAccountName", "张三")
                .containsEntry("P8_biz", "B2C")
                .containsEntry("P9_bankUnionCode", "104100099999")
                .containsEntry("P10_feeType", "PAYER")
                .containsEntry("P11_urgency", "true")
                .containsEntry("notifyUrl", "https://pay.example.com/pay/transfernotify/T1");
    }

    @Test
    @DisplayName("打款联行号为空 → 不携带 P9_bankUnionCode")
    void transferWithoutCnapsOmitsBankUnionCode() throws Exception {
        var ctx = transferCtx("ABC", "6222021234567890", "张三", null);
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("Transfer", Map.of(
                    "rt5_orderId", "T1", "rt6_serialNumber", "SN1")));
            new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P5_bankCode", "ABC").doesNotContainKey("P9_bankUnionCode");
    }

    @Test
    @DisplayName("打款缺银行编码 → PARAM_ERROR 失败（表单必填前置校验）")
    void transferMissingBankCodeFails() {
        var ctx = transferCtx(null, "6222021234567890", "张三", null);
        var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

        assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(result.getCode()).isEqualTo("PARAM_ERROR");
        assertThat(result.getMsg()).isEqualTo("缺少银行编码");
    }

    @Test
    @DisplayName("对公打款 → B2B 提交且携带联行号；对公缺联行号 → PARAM_ERROR 拒绝提交")
    void transferPublicAccountSendsB2BWithUnionCode() throws Exception {
        // 对公 + 联行号 → B2B 提交
        var ctx = transferCtx("ABC", "6222021234567890", "某某科技有限公司", "104100099999", "public");
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("Transfer", Map.of(
                    "rt5_orderId", "T1", "rt6_serialNumber", "SN1")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P8_biz", "B2B").containsEntry("P9_bankUnionCode", "104100099999");

        // 对公缺联行号 → 提交前拒绝，不发请求
        var ctxNoCnaps = transferCtx("ABC", "6222021234567890", "某某科技有限公司", null, "public");
        var resultNo = new HelipaySubmitHandler().handle(ctxNoCnaps, req(BizType.T_XFER));
        assertThat(resultNo.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(resultNo.getCode()).isEqualTo("PARAM_ERROR");
        assertThat(resultNo.getMsg()).isEqualTo("对公打款须填写联行号");
    }

    @Test
    @DisplayName("打款其余业务码 → S_FAIL")
    void transferRejected() throws Exception {
        var ctx = transferCtx("ABC", "6222021234567890", "张三", null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("Transfer", Map.of(
                    "rt2_retCode", "0002", "rt3_retMsg", "余额不足", "rt5_orderId", "T1")));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("0002");
        }
    }

    @Test
    @DisplayName("打款渠道异常 → S_ING XFER_ERROR（状态未知不误判失败）")
    void transferChannelErrorKeepsIng() throws Exception {
        var ctx = transferCtx("ABC", "6222021234567890", "张三", null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new HelipaySubmitHandler().handle(ctx, req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("XFER_ERROR");
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static BizRequest req(BizType bizType) {
        return BizRequest.builder().bizType(bizType).build();
    }

    private InvokeContext refundCtx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").amount(100L).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    private InvokeContext transferCtx(String bankCode, String cardNo, String cardName, String cnapsNo) {
        return transferCtx(bankCode, cardNo, cardName, cnapsNo, null);
    }

    private InvokeContext transferCtx(String bankCode, String cardNo, String cardName, String cnapsNo,
                                      String accountType) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L)
                        .bankCode(bankCode).cardNo(cardNo).cardName(cardName).cnapsNo(cnapsNo)
                        .accountType(accountType).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
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
