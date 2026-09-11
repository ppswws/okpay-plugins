package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.joinpay.util.JoinpaySignUtil;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * JoinpayNotifyHandler 三类通知（支付/退款/打款）：
 * 验签失败/参数缺失 → 拒绝且不推进；成功语义严格按文档（支付 r6_Status=100、退款 ra_Status=100/101、
 * 打款 status=205/204/208/214）；金额/单号不匹配按语义应答。未知单号由宿主拦截（实体缺失即 404，
 * 不进插件），本层不做判空。
 *
 * <p>支付通知为 GET（参数在 query，需 URL 解码后验签）；退款/打款通知为 JSON body。</p>
 */
@DisplayName("JoinpayNotifyHandler 通知应答")
class JoinpayNotifyHandlerTest {

    private static final String KEY = "JPKEY1";

    // =========================================================================
    // 支付通知（GET query）
    // =========================================================================

    @Test
    @DisplayName("支付通知 r6_Status=100 验签通过 → 订单完成推进 + success（apiNo=r7_TrxNo、buyer=rd_OpenId）")
    void payNotifySuccess() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(cb, null, orderQuery(orderNotify("100", "1.00", "T1")), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        var req = captor.getValue();
        assertThat(req.getBizType()).isEqualTo(BizType.T_PAY);
        assertThat(req.getBizNo()).isEqualTo("T1");
        assertThat(req.getState()).isEqualTo(BizState.S_OK);
        assertThat(req.getApiNo()).isEqualTo("TRX1");
        assertThat(req.getBuyer()).isEqualTo("OPENID1");
    }

    @Test
    @DisplayName("支付通知 r6_Status=101（支付失败）→ 不推进，应答 status=101")
    void payNotifyFailureStatusNoProgress() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(cb, null, orderQuery(orderNotify("101", "1.00", "T1")), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("status=101");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("支付通知验签失败 → sign_error，不推进")
    void payNotifyBadSignRejected() {
        var params = orderNotify("100", "1.00", "T1");
        params.put("hmac", "bogus");
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(cb, null, orderQuery(params), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("支付通知金额不符 → amount_mismatch，不推进")
    void payNotifyAmountMismatch() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(cb, null, orderQuery(orderNotify("100", "9.99", "T1")), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("支付通知单号不匹配 → order_mismatch")
    void payNotifyOrderMismatch() {
        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(mock(HostCallback.class), null, orderQuery(orderNotify("100", "1.00", "OTHER1")), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("order_mismatch");
    }

    @Test
    @DisplayName("支付通知完成推进抛异常 → fail（交还渠道补发重试）")
    void payNotifyCompleteFailureAcksFail() {
        var cb = mock(HostCallback.class);
        doThrow(new RuntimeException("db down")).when(cb).completeBiz(any());

        var resp = new JoinpayNotifyHandler().payNotify(
                ctx(cb, null, orderQuery(orderNotify("100", "1.00", "T1")), "T1", 100L, null, null, null));

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    // =========================================================================
    // 退款通知（JSON body）
    // =========================================================================

    @Test
    @DisplayName("退款通知 ra_Status=100 → 退款完成推进 + success（apiNo=r5_RefundTrxNo）")
    void refundNotifySuccess() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().refundNotify(
                ctx(cb, json(refundNotify("100", "1.00", "R1")), null, null, 0L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(BizType.T_REF);
        assertThat(captor.getValue().getBizNo()).isEqualTo("R1");
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("RTRX1");
    }

    @Test
    @DisplayName("退款通知 ra_Status=101 → 退款失败推进 + status=101 应答")
    void refundNotifyFail() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().refundNotify(
                ctx(cb, json(refundNotify("101", "1.00", "R1")), null, null, 0L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("status=101");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_FAIL);
    }

    @Test
    @DisplayName("退款通知 ra_Status=102（处理中）→ 退款处理中推进")
    void refundNotifyIng() {
        var cb = mock(HostCallback.class);

        new JoinpayNotifyHandler().refundNotify(
                ctx(cb, json(refundNotify("102", "1.00", "R1")), null, null, 0L, "R1", 100L, null));

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_ING);
    }

    @Test
    @DisplayName("退款通知金额不符 → amount_mismatch，不推进")
    void refundNotifyAmountMismatch() {
        var resp = new JoinpayNotifyHandler().refundNotify(
                ctx(mock(HostCallback.class), json(refundNotify("100", "0.50", "R1")), null, null, 0L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
    }

    @Test
    @DisplayName("退款通知单号不匹配 → refund_mismatch")
    void refundNotifyNoMismatch() {
        var resp = new JoinpayNotifyHandler().refundNotify(
                ctx(mock(HostCallback.class), json(refundNotify("100", "1.00", "R-OTHER")), null, null, 0L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("refund_mismatch");
    }

    @Test
    @DisplayName("退款通知验签失败 → sign_error，不推进")
    void refundNotifyBadSignRejected() {
        var params = refundNotify("100", "1.00", "R1");
        params.put("hmac", "bogus");
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().refundNotify(
                ctx(cb, json(params), null, null, 0L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).completeBiz(any());
    }

    // =========================================================================
    // 打款通知（JSON body）
    // =========================================================================

    @Test
    @DisplayName("打款通知 status=205 验签通过 → 打款完成推进 + success（apiNo=platformSerialNo）")
    void transferNotifySuccess() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().transferNotify(
                ctx(cb, json(transferNotify("205", "T1")), null, null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(BizType.T_XFER);
        assertThat(captor.getValue().getBizNo()).isEqualTo("T1");
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("PSN1");
    }

    @Test
    @DisplayName("打款通知 status=204 → 打款失败推进（code=errorCode）+ status=204 应答")
    void transferNotifyFail() {
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().transferNotify(
                ctx(cb, json(transferNotify("204", "T1", "300002014", "订单记录不存在")), null, null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("status=204");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_FAIL);
        assertThat(captor.getValue().getCode()).isEqualTo("300002014");
    }

    @Test
    @DisplayName("打款通知伪造 hmac（无验签即推进=资金风险）→ sign_error，不推进")
    void transferNotifyForgedSignRejected() {
        var params = transferNotify("205", "T1");
        params.put("hmac", "forged");
        var cb = mock(HostCallback.class);

        var resp = new JoinpayNotifyHandler().transferNotify(
                ctx(cb, json(params), null, null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("打款通知未知状态 202（处理中）→ 打款处理中推进")
    void transferNotifyIng() {
        var cb = mock(HostCallback.class);

        new JoinpayNotifyHandler().transferNotify(
                ctx(cb, json(transferNotify("202", "T1")), null, null, 0L, null, null, "T1"));

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_ING);
        assertThat(captor.getValue().getApiNo()).isEqualTo("PSN1");
    }

    @Test
    @DisplayName("打款通知单号不匹配 → transfer_mismatch")
    void transferNotifyNoMismatch() {
        var resp = new JoinpayNotifyHandler().transferNotify(
                ctx(mock(HostCallback.class), json(transferNotify("205", "OTHER1")), null, null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("transfer_mismatch");
    }

    @Test
    @DisplayName("打款通知数值型字段（status=205 数字、paidAmount=1.00）→ 按原文拼接验签通过")
    void transferNotifyNumericFieldsVerifyByLiteral() throws Exception {
        // 渠道通知的金额/状态可能是 JSON 数值：验签须按上游原文（1.00 不能变成 1.0/1）
        var m = new LinkedHashMap<String, Object>();
        m.put("status", Integer.parseInt("205"));
        m.put("errorCode", "");
        m.put("errorCodeDesc", "");
        m.put("userNo", "JP1");
        m.put("tradeMerchantNo", "JPMCH1");
        m.put("merchantOrderNo", "T1");
        m.put("platformSerialNo", "PSN1");
        m.put("receiverAccountNoEnc", "6225882010007926");
        m.put("receiverNameEnc", "张三");
        m.put("paidAmount", new java.math.BigDecimal("1.00"));
        m.put("fee", new java.math.BigDecimal("0.11"));
        var signData = new LinkedHashMap<String, String>();
        m.forEach((k, v) -> signData.put(k, v != null ? v.toString() : ""));
        m.put("hmac", JoinpaySignUtil.sign(signData, JoinpayNotifyHandler.TRANSFER_NOTIFY_FIELDS, KEY));

        var resp = new JoinpayNotifyHandler().transferNotify(
                ctx(mock(HostCallback.class), HttpHelper.MAPPER.writeValueAsBytes(m), null, null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("success");
    }

    // =========================================================================
    // 内部：通知报文构造（字段序与验签同源）
    // =========================================================================

    private static Map<String, String> orderNotify(String status, String amount, String orderNo) {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.6");
        m.put("r1_MerchantNo", "JP1");
        m.put("r2_OrderNo", orderNo);
        m.put("r3_Amount", amount);
        m.put("r4_Cur", "1");
        m.put("r6_Status", status);
        m.put("r7_TrxNo", "TRX1");
        m.put("r8_BankOrderNo", "BO1");
        m.put("r9_BankTrxNo", "BT1");
        m.put("ra_PayTime", "2026-08-23 12:00:00");
        m.put("rb_DealTime", "2026-08-23 12:00:01");
        m.put("rc_BankCode", "WX");
        m.put("rd_OpenId", "OPENID1");
        m.put("re_DiscountAmount", "0.00");
        m.put("rj_Fee", "0.10");
        m.put("rk_FrpCode", "WEIXIN_NATIVE");
        m.put("ro_SettleAmount", "0.90");
        m.put("hmac", JoinpaySignUtil.sign(m, JoinpayNotifyHandler.ORDER_NOTIFY_FIELDS, KEY));
        return m;
    }

    private static Map<String, String> refundNotify(String status, String amount, String refundNo) {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.3");
        m.put("r1_MerchantNo", "JP1");
        m.put("r2_OrderNo", "T1");
        m.put("r3_RefundOrderNo", refundNo);
        m.put("r4_RefundAmount", amount);
        m.put("r5_RefundTrxNo", "RTRX1");
        m.put("ra_Status", status);
        m.put("rb_Code", "100");
        m.put("rc_CodeMsg", "");
        m.put("re_FundsAccount", "SETTLED");
        m.put("hmac", JoinpaySignUtil.sign(m, JoinpayNotifyHandler.REFUND_NOTIFY_FIELDS, KEY));
        return m;
    }

    private static Map<String, String> transferNotify(String status, String orderNo) {
        return transferNotify(status, orderNo, "", "");
    }

    private static Map<String, String> transferNotify(String status, String orderNo,
                                                      String errorCode, String errorCodeDesc) {
        var m = new LinkedHashMap<String, String>();
        m.put("status", status);
        m.put("errorCode", errorCode);
        m.put("errorCodeDesc", errorCodeDesc);
        m.put("userNo", "JP1");
        m.put("tradeMerchantNo", "JPMCH1");
        m.put("merchantOrderNo", orderNo);
        m.put("platformSerialNo", "PSN1");
        m.put("receiverAccountNoEnc", "6225882010007926");
        m.put("receiverNameEnc", "张三");
        m.put("paidAmount", "1.00");
        m.put("fee", "0.11");
        m.put("hmac", JoinpaySignUtil.sign(m, JoinpayNotifyHandler.TRANSFER_NOTIFY_FIELDS, KEY));
        return m;
    }

    /** GET 支付通知：参数在 query（验签前 URL 解码） */
    private static String orderQuery(Map<String, String> params) {
        return PaymentUtils.encodeForm(params);
    }

    private static byte[] json(Map<String, String> params) {
        try {
            return HttpHelper.MAPPER.writeValueAsBytes(params);
        } catch (Exception e) {
            throw new IllegalStateException("通知报文序列化失败", e);
        }
    }

    private static InvokeContext ctx(HostCallback cb, byte[] body, String query,
                                     String orderTradeNo, Long orderReal,
                                     String refundNo, Long refundAmount, String transferNo) {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .callback(cb)
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .request(RequestSnapshot.builder()
                        .body(body == null ? new byte[0] : body)
                        .query(query)
                        .build());
        if (orderTradeNo != null)
            b.order(OrderSnapshot.builder().tradeNo(orderTradeNo).real(orderReal).build());
        if (refundNo != null)
            b.refund(RefundSnapshot.builder().refundNo(refundNo).tradeNo("T1").amount(refundAmount).build());
        if (transferNo != null)
            b.transfer(TransferSnapshot.builder().tradeNo(transferNo).build());
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
