package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.joinpay.util.JoinpaySignUtil;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.BizResult;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.ExpectedFailureException;
import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 退款/打款提交与查单的结果状态边界（依据 api.txt）：
 * 订单查单永不 S_FAIL；退款仅 ra_Status=101 失败；打款提交仅 statusCode=2002 受理失败，
 * 打款查单仅文档明确的 204/208/214 失败；验签/解析/网络/未知一律 S_WAIT。
 */
@DisplayName("Joinpay 退款/打款/查单状态边界")
class JoinpayBizResultTest {

    private static final String KEY = "JPKEY1";

    // =========================================================================
    // 退款提交
    // =========================================================================

    @Test
    @DisplayName("退款提交 ra_Status=100 → S_OK（apiNo=r5_RefundTrxNo）")
    void refundSubmitOk() throws Exception {
        var r = submit(BizType.T_REF, signed(formResp("100", "100", "")));

        assertThat(r.getState()).isEqualTo(BizState.S_OK);
        assertThat(r.getApiNo()).isEqualTo("RTRX1");
    }

    @Test
    @DisplayName("退款提交 ra_Status=101 → S_FAIL（文档明确的退款失败）")
    void refundSubmitFail() throws Exception {
        var r = submit(BizType.T_REF, signed(formResp("101", "20090001", "支付成功记录不存在")));

        assertThat(r.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(r.getMsg()).isEqualTo("支付成功记录不存在");
    }

    @Test
    @DisplayName("退款提交 ra_Status=102 → S_WAIT（处理中）")
    void refundSubmitInProgressWaits() throws Exception {
        assertThat(submit(BizType.T_REF, signed(formResp("102", "100", ""))).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("退款提交 20090004（退款记录不存在）非明确失败 → S_WAIT（由查询/通知收敛）")
    void refundSubmitUntrustedCodeWaits() throws Exception {
        assertThat(submit(BizType.T_REF, signed(formResp("", "20090004", "退款记录不存在"))).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("退款提交验签失败 → S_WAIT（应答不可信，不判失败）")
    void refundSubmitBadSignWaits() throws Exception {
        var m = formResp("101", "20090001", "支付成功记录不存在");
        m.put("hmac", "bogus");

        var r = submit(BizType.T_REF, resp(200, HttpHelper.MAPPER.writeValueAsString(m)));

        assertThat(r.getState()).isEqualTo(BizState.S_WAIT);
        assertThat(r.getCode()).isEqualTo("SIGN_ERROR");
    }

    @Test
    @DisplayName("退款提交网络异常 → S_WAIT（状态未知，不误判失败）")
    void refundSubmitNetworkWaits() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenThrow(new IOException("timeout"));

            var r = new JoinpaySubmitHandler().handle(refundCtx(), ref(BizType.T_REF));

            assertThat(r.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(r.getCode()).isEqualTo("REFUND_ERROR");
        }
    }

    // =========================================================================
    // 打款提交
    // =========================================================================

    @Test
    @DisplayName("打款提交 statusCode=2001 受理成功 → S_WAIT（非终态，由通知/查询收敛）")
    void transferSubmitAcceptedWaits() throws Exception {
        assertThat(submit(BizType.T_XFER, resp(200, transferResp("2001", "受理成功", ""))).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("打款提交 statusCode=2003 未知 → S_WAIT")
    void transferSubmitUnknownWaits() throws Exception {
        assertThat(submit(BizType.T_XFER, resp(200, transferResp("2003", "未知", ""))).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("打款提交 statusCode=2002 受理失败 → S_FAIL（文档明确失败）")
    void transferSubmitRejectedFails() throws Exception {
        var r = submit(BizType.T_XFER, resp(200, transferResp("2002", "受理失败", "")));

        assertThat(r.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(r.getCode()).isEqualTo("2002");
    }

    @Test
    @DisplayName("打款提交未文档化的应答码 → S_WAIT（不臆断失败）")
    void transferSubmitUndocumentedCodeWaits() throws Exception {
        assertThat(submit(BizType.T_XFER, resp(200, transferResp("5000", "未知应答", ""))).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("打款提交应答 data 验签失败 → S_WAIT（受理结果不可信未验签报文）")
    void transferSubmitBadDataSignWaits() throws Exception {
        var body = "{\"statusCode\":2002,\"message\":\"受理失败\",\"data\":"
                + "{\"errorCode\":\"\",\"errorDesc\":\"\",\"userNo\":\"JP1\",\"merchantOrderNo\":\"T1\","
                + "\"hmac\":\"forged\"}}";

        assertThat(submit(BizType.T_XFER, resp(200, body)).getState()).isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("对公打款缺联行号 → 本地校验失败（预期失败，S_WAIT）")
    void transferPublicWithoutCnapsIsExpectedFailure() {
        var ctx = ctx(b -> b.transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L)
                .accountType("public").cardNo("6222000011112222").cardName("张三").build()));

        assertThatThrownBy(() -> new JoinpaySubmitHandler().handle(ctx, ref(BizType.T_XFER)))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessage("对公打款必须提供联行号");
    }

    // =========================================================================
    // 查单
    // =========================================================================

    @Test
    @DisplayName("订单查单 ra_Status=101 → S_WAIT（支付订单查单永不 S_FAIL）")
    void orderQueryNeverFails() throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.6");
        m.put("r2_OrderNo", "T1");
        m.put("ra_Status", "101");
        m.put("rb_Code", "100");
        m.put("rc_CodeMsg", "支付失败");
        m.put("hmac", JoinpaySignUtil.sign(m, ORDER_QUERY_RESP, KEY));

        var r = query(BizType.T_PAY, resp(200, HttpHelper.MAPPER.writeValueAsString(m)),
                ctx(b -> b.order(OrderSnapshot.builder().tradeNo("T1").real(100L).build())));

        assertThat(r.getState()).isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("订单查单 ra_Status=100 → S_OK（apiNo=r5_TrxNo、buyer=rd_OpenId）")
    void orderQueryOk() throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.6");
        m.put("r5_TrxNo", "TRX1");
        m.put("ra_Status", "100");
        m.put("rd_OpenId", "OPENID1");
        m.put("hmac", JoinpaySignUtil.sign(m, ORDER_QUERY_RESP, KEY));

        var r = query(BizType.T_PAY, resp(200, HttpHelper.MAPPER.writeValueAsString(m)),
                ctx(b -> b.order(OrderSnapshot.builder().tradeNo("T1").real(100L).build())));

        assertThat(r.getState()).isEqualTo(BizState.S_OK);
        assertThat(r.getApiNo()).isEqualTo("TRX1");
        assertThat(r.getBuyer()).isEqualTo("OPENID1");
    }

    @Test
    @DisplayName("订单查单渠道失败/验签失败 → S_WAIT")
    void orderQueryFailureWaits() throws Exception {
        var ctx = ctx(b -> b.order(OrderSnapshot.builder().tradeNo("T1").real(100L).build()));
        assertThat(query(BizType.T_PAY, resp(502, "<html>Bad Gateway</html>"), ctx).getState())
                .isEqualTo(BizState.S_WAIT);

        var m = new LinkedHashMap<String, String>();
        m.put("ra_Status", "100");
        m.put("hmac", "bogus");
        assertThat(query(BizType.T_PAY, resp(200, HttpHelper.MAPPER.writeValueAsString(m)), ctx).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("退款查单 ra_Status=101 → S_FAIL")
    void refundQueryFail() throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.3");
        m.put("r2_RefundOrderNo", "R1");
        m.put("ra_Status", "101");
        m.put("rb_Code", "20090001");
        m.put("rc_CodeMsg", "支付成功记录不存在");
        m.put("hmac", JoinpaySignUtil.sign(m, REFUND_QUERY_RESP, KEY));

        var r = query(BizType.T_REF, resp(200, HttpHelper.MAPPER.writeValueAsString(m)),
                ctx(b -> b.refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").amount(100L).build())));

        assertThat(r.getState()).isEqualTo(BizState.S_FAIL);
    }

    @Test
    @DisplayName("打款查单 status=214（代付失败）→ S_FAIL")
    void transferQueryFail() throws Exception {
        assertThat(query(BizType.T_XFER, resp(200, transferQueryResp("214", "300002014", "订单记录不存在")),
                ctx(b -> b.transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L).build())))
                .getState()).isEqualTo(BizState.S_FAIL);
    }

    @Test
    @DisplayName("打款查单 status=203（处理中）→ S_WAIT")
    void transferQueryInProgressWaits() throws Exception {
        assertThat(query(BizType.T_XFER, resp(200, transferQueryResp("203", "", "")),
                ctx(b -> b.transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L).build())))
                .getState()).isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("打款查单 statusCode≠2001 → S_WAIT（查询失败不是打款失败）")
    void transferQueryChannelErrorWaits() throws Exception {
        var body = "{\"statusCode\":\"2002\",\"message\":\"查询失败\",\"data\":{}}";
        assertThat(query(BizType.T_XFER, resp(200, body),
                ctx(b -> b.transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L).build())))
                .getState()).isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("余额查询渠道应答非 2001 → S_WAIT（不推进余额失败态）")
    void balanceChannelErrorWaits() throws Exception {
        var body = "{\"statusCode\":\"2002\",\"message\":\"查询失败\",\"data\":{}}";
        assertThat(query(BizType.T_BAL, resp(200, body), ctx(b -> {})).getState())
                .isEqualTo(BizState.S_WAIT);
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static final List<String> ORDER_QUERY_RESP = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_Amount","r4_ProductName","r5_TrxNo",
            "r6_BankTrxNo","r7_Fee","r8_FrpCode","ra_Status","rb_Code","rc_CodeMsg",
            "rd_OpenId","re_DiscountAmount","rf_PayTime","rh_cardType","rj_BankCode",
            "rl_ContractId","rm_SpecialInfo","ro_SettleAmount");
    private static final List<String> REFUND_QUERY_RESP = List.of(
            "r0_Version","r1_MerchantNo","r2_RefundOrderNo","r3_RefundAmount","r4_RefundTrxNo",
            "r5_RefundCompleteTime","r8_RefundWay","r9_ReceiveAccountNo","ra_Status","rb_Code",
            "rc_CodeMsg","rd_MarketRefAmount","re_FundsAccount");
    private static final List<String> REFUND_RESP = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_RefundOrderNo","r4_RefundAmount",
            "r5_RefundTrxNo","ra_Status","rb_Code","rc_CodeMsg","re_FundsAccount");
    private static final List<String> TRANSFER_RESP = List.of(
            "errorCode","errorDesc","userNo","merchantOrderNo");
    private static final List<String> TRANSFER_QUERY_RESP = List.of(
            "status","errorCode","errorDesc","userNo","tradeMerchantNo","merchantOrderNo",
            "platformSerialNo","receiverAccountNoEnc","receiverNameEnc","paidAmount","fee");

    private static Map<String, String> formResp(String status, String code, String msg) {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.3");
        m.put("r2_OrderNo", "T1");
        m.put("r3_RefundOrderNo", "R1");
        m.put("r5_RefundTrxNo", "RTRX1");
        m.put("ra_Status", status);
        m.put("rb_Code", code);
        m.put("rc_CodeMsg", msg);
        return m;
    }

    private static HttpHelper.HttpResponse signed(Map<String, String> m) throws Exception {
        m.put("hmac", JoinpaySignUtil.sign(m, REFUND_RESP, KEY));
        return resp(200, HttpHelper.MAPPER.writeValueAsString(m));
    }

    private static String transferResp(String statusCode, String message, String errorCode) throws Exception {
        var data = new LinkedHashMap<String, String>();
        data.put("errorCode", errorCode);
        data.put("errorDesc", "");
        data.put("userNo", "JP1");
        data.put("merchantOrderNo", "T1");
        data.put("hmac", JoinpaySignUtil.sign(data, TRANSFER_RESP, KEY));
        var root = new LinkedHashMap<String, Object>();
        root.put("statusCode", statusCode);
        root.put("message", message);
        root.put("data", data);
        return HttpHelper.MAPPER.writeValueAsString(root);
    }

    private static String transferQueryResp(String status, String errorCode, String errorDesc) throws Exception {
        var data = new LinkedHashMap<String, String>();
        data.put("status", status);
        data.put("errorCode", errorCode);
        data.put("errorDesc", errorDesc);
        data.put("userNo", "JP1");
        data.put("merchantOrderNo", "T1");
        data.put("platformSerialNo", "PSN1");
        data.put("hmac", JoinpaySignUtil.sign(data, TRANSFER_QUERY_RESP, KEY));
        var root = new LinkedHashMap<String, Object>();
        root.put("statusCode", "2001");
        root.put("message", "查询成功");
        root.put("data", data);
        // 渠道原文直出：status=214 为 JSON 数值时验签仍按 "214" 拼接
        return HttpHelper.MAPPER.writeValueAsString(root).replace("\"status\":\"" + status + "\"",
                "\"status\":" + status);
    }

    /** 提交类调用（固定打款收款信息，联行号齐全） */
    private static BizResult submit(BizType type, HttpHelper.HttpResponse resp) throws Exception {
        var ctx = type == BizType.T_REF
                ? refundCtx()
                : ctx(b -> b.transfer(TransferSnapshot.builder().tradeNo("T1").amount(100L)
                        .accountType("private").cardNo("6222000011112222").cardName("张三").build()));
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenReturn(resp);
            return new JoinpaySubmitHandler().handle(ctx, ref(type));
        }
    }

    private static BizResult query(BizType type, HttpHelper.HttpResponse resp, InvokeContext ctx) throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenReturn(resp);
            return new JoinpayQueryHandler().handle(ctx, ref(type));
        }
    }

    private static HttpHelper.HttpResponse resp(int status, String body) {
        return new HttpHelper.HttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8), "req", 10, 1);
    }

    private static InvokeContext refundCtx() {
        return ctx(b -> {
            b.order(OrderSnapshot.builder().tradeNo("T1").real(100L).build());
            b.refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").amount(100L).build());
        });
    }

    private static BizRequest ref(BizType type) {
        return BizRequest.builder().bizType(type).build();
    }

    private static InvokeContext ctx(java.util.function.Consumer<InvokeContext.InvokeContextBuilder> customizer) {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder()
                        .siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .request(RequestSnapshot.builder().build());
        customizer.accept(b);
        return b.build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "JP1");
            cfg.put("appkey", KEY);
            cfg.put("biztype", "1");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }
}
