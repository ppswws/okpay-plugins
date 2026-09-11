package com.okpay.plugin.helipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.helipay.util.HelipaySignUtil;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HelipayNotifyHandler 三类通知（支付/退款/打款）：验签与必填缺失 → fail、金额/单号不匹配按语义应答；
 * 打款完成推进失败仍应答 success（结果由查询轮询收敛）。未知单号由宿主拦截（实体缺失即 404，不进插件），
 * 本层不做判空。
 */
@DisplayName("HelipayNotifyHandler 通知应答")
class HelipayNotifyHandlerTest {

    private static final String KEY = "HLKEY1";

    // =========================================================================
    // 支付通知
    // =========================================================================

    @Test
    @DisplayName("支付通知验签通过 → 订单完成推进 + success 应答（apiNo=rt3、buyer=rt10）")
    void payNotifySuccess() {
        var params = orderNotify("SUCCESS", "1.00", "T1", "desc");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(cb, orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        var req = captor.getValue();
        assertThat(req.getBizType()).isEqualTo(BizType.T_PAY);
        assertThat(req.getBizNo()).isEqualTo("T1");
        assertThat(req.getState()).isEqualTo(BizState.S_OK);
        assertThat(req.getApiNo()).isEqualTo("S1");
        assertThat(req.getBuyer()).isEqualTo("OPENID1");
    }

    @Test
    @DisplayName("支付通知金额不符 → amount_mismatch，不推进")
    void payNotifyAmountMismatch() {
        var params = orderNotify("SUCCESS", "9.99", "T1", "desc");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(cb, orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("支付通知单号不匹配 → fail")
    void payNotifyOrderMismatch() {
        var params = orderNotify("SUCCESS", "1.00", "OTHER1", "desc");

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(mock(HostCallback.class), orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("支付通知渠道状态非 SUCCESS → fail")
    void payNotifyNonSuccessStatusFails() {
        var params = orderNotify("PAYING", "1.00", "T1", "desc");

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(mock(HostCallback.class), orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("支付通知验签失败 → fail")
    void payNotifyBadSignFails() {
        var params = orderNotify("SUCCESS", "1.00", "T1", "desc");
        params.put("sign", "bogus");

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(mock(HostCallback.class), orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("支付通知完成推进抛异常 → fail（交还渠道重试）")
    void payNotifyCompleteFailureAcksFail() {
        var params = orderNotify("SUCCESS", "1.00", "T1", "desc");
        var cb = mock(HostCallback.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(cb).completeBiz(any());

        var resp = new HelipayNotifyHandler().payNotify(
                notifyCtx(cb, orderNotifyBody(params), "T1", 100L));

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    @Test
    @DisplayName("退款通知 SUCCESS → 退款完成推进 + success")
    void refundNotifySuccess() {
        var params = refundNotify("SUCCESS", "1.00", "R1");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().refundNotify(
                notifyCtx(cb, refundNotifyBody(params), "T1", 100L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(BizType.T_REF);
        assertThat(captor.getValue().getBizNo()).isEqualTo("R1");
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("S1");
    }

    @Test
    @DisplayName("退款通知 FAIL → 退款失败推进 + success 应答")
    void refundNotifyFail() {
        var params = refundNotify("FAIL", "1.00", "R1");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().refundNotify(
                notifyCtx(cb, refundNotifyBody(params), "T1", 100L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_FAIL);
        assertThat(captor.getValue().getCode()).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("退款通知处理中状态 → 退款处理中推进")
    void refundNotifyIng() {
        var params = refundNotify("REFUNDING", "1.00", "R1");
        var cb = mock(HostCallback.class);

        new HelipayNotifyHandler().refundNotify(
                notifyCtx(cb, refundNotifyBody(params), "T1", 100L, "R1", 100L, null));

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_ING);
    }

    @Test
    @DisplayName("退款通知单号不匹配 → refund_mismatch")
    void refundNotifyNoMismatch() {
        var params = refundNotify("SUCCESS", "1.00", "R-OTHER");

        var resp = new HelipayNotifyHandler().refundNotify(
                notifyCtx(mock(HostCallback.class), refundNotifyBody(params), "T1", 100L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("refund_mismatch");
    }

    @Test
    @DisplayName("退款通知金额不符 → amount_mismatch")
    void refundNotifyAmountMismatch() {
        var params = refundNotify("SUCCESS", "0.50", "R1");

        var resp = new HelipayNotifyHandler().refundNotify(
                notifyCtx(mock(HostCallback.class), refundNotifyBody(params), "T1", 100L, "R1", 100L, null));

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
    }

    // =========================================================================
    // 打款通知
    // =========================================================================

    @Test
    @DisplayName("打款通知 SUCCESS → 打款完成推进 + success")
    void transferNotifySuccess() {
        var params = transferNotify("SUCCESS", "T1", "SN1");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().transferNotify(
                notifyCtx(cb, transferNotifyBody(params), null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(BizType.T_XFER);
        assertThat(captor.getValue().getBizNo()).isEqualTo("T1");
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("SN1");
        assertThat(captor.getValue().getMsg()).isEqualTo("成功");
    }

    @Test
    @DisplayName("打款通知 FAIL → 打款失败推进（code=rt2_retCode）")
    void transferNotifyFail() {
        var params = transferNotify("FAIL", "T1", "SN1", "9999");
        var cb = mock(HostCallback.class);

        new HelipayNotifyHandler().transferNotify(
                notifyCtx(cb, transferNotifyBody(params), null, 0L, null, null, "T1"));

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_FAIL);
        assertThat(captor.getValue().getCode()).isEqualTo("9999");
    }

    @Test
    @DisplayName("打款通知处理中（RECEIVE/INIT/DOING）→ 打款处理中推进")
    void transferNotifyIng() {
        var params = transferNotify("DOING", "T1", "SN1");
        var cb = mock(HostCallback.class);

        new HelipayNotifyHandler().transferNotify(
                notifyCtx(cb, transferNotifyBody(params), null, 0L, null, null, "T1"));

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_ING);
        assertThat(captor.getValue().getApiNo()).isEqualTo("SN1");
    }

    @Test
    @DisplayName("打款通知未知状态 → 不推进，仍 success 应答")
    void transferNotifyUnknownStatusNoProgress() {
        var params = transferNotify("UNKNOWN", "T1", "SN1");
        var cb = mock(HostCallback.class);

        var resp = new HelipayNotifyHandler().transferNotify(
                notifyCtx(cb, transferNotifyBody(params), null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("success");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("打款完成推进抛异常 → 仍应答 success（打款结果由查询轮询收敛，重试通知无意义）")
    void transferNotifyCompleteFailureStillSuccess() {
        var params = transferNotify("SUCCESS", "T1", "SN1");
        var cb = mock(HostCallback.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(cb).completeBiz(any());

        var resp = new HelipayNotifyHandler().transferNotify(
                notifyCtx(cb, transferNotifyBody(params), null, 0L, null, null, "T1"));

        assertThat(resp.getDataText()).isEqualTo("success");
    }

    // =========================================================================
    // 内部：通知报文构造（字段序与验签同源）
    // =========================================================================

    private static Map<String, String> orderNotify(String status, String amount, String orderId, String desc) {
        var m = new LinkedHashMap<String, String>();
        m.put("rt1_customerNumber", "HL1");
        m.put("rt2_orderId", orderId);
        m.put("rt3_systemSerial", "S1");
        m.put("rt4_status", status);
        m.put("rt5_orderAmount", amount);
        m.put("rt6_currency", "CNY");
        m.put("rt7_timestamp", "20260822120000");
        m.put("rt8_desc", desc);
        m.put("rt10_openId", "OPENID1");
        m.put("sign", HelipaySignUtil.signNotify(m, KEY));
        return m;
    }

    private static Map<String, String> refundNotify(String status, String amount, String refundNo) {
        var m = new LinkedHashMap<String, String>();
        m.put("rt1_customerNumber", "HL1");
        m.put("rt2_orderId", "T1");
        m.put("rt3_refundOrderId", refundNo);
        m.put("rt4_systemSerial", "S1");
        m.put("rt5_status", status);
        m.put("rt6_amount", amount);
        m.put("rt7_currency", "CNY");
        m.put("rt8_timestamp", "20260822120000");
        m.put("sign", HelipaySignUtil.signNotify(m, KEY));
        return m;
    }

    private static Map<String, String> transferNotify(String status, String orderId, String serial) {
        return transferNotify(status, orderId, serial, "0000");
    }

    private static Map<String, String> transferNotify(String status, String orderId, String serial,
                                                      String retCode) {
        var m = new LinkedHashMap<String, String>();
        m.put("rt1_bizType", "Transfer");
        m.put("rt2_retCode", retCode);
        m.put("rt3_retMsg", "受理成功");
        m.put("rt4_customerNumber", "HL1");
        m.put("rt5_orderId", orderId);
        m.put("rt6_serialNumber", serial);
        m.put("rt7_orderStatus", status);
        m.put("rt8_notifyType", "transfer");
        m.put("rt9_reason", "成功");
        m.put("rt10_createDate", "20260822");
        m.put("rt11_completeDate", "20260822");
        m.put("sign", HelipaySignUtil.signNotify(m, KEY));
        return m;
    }

    private static byte[] orderNotifyBody(Map<String, String> params) {
        return json(params);
    }

    private static byte[] refundNotifyBody(Map<String, String> params) {
        return json(params);
    }

    private static byte[] transferNotifyBody(Map<String, String> params) {
        return json(params);
    }

    private static byte[] json(Map<String, String> params) {
        try {
            return HttpHelper.MAPPER.writeValueAsBytes(params);
        } catch (Exception e) {
            throw new IllegalStateException("通知报文序列化失败", e);
        }
    }

    /** 支付通知用例重载：无退款/打款单。 */
    private static InvokeContext notifyCtx(HostCallback cb, byte[] body, String orderTradeNo, long orderReal) {
        return notifyCtx(cb, body, orderTradeNo, orderReal, null, null, null);
    }

    private static InvokeContext notifyCtx(HostCallback cb, byte[] body, String orderTradeNo, long orderReal,
                                           String refundNo, Long refundAmount, String transferNo) {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .callback(cb)
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .request(RequestSnapshot.builder().body(body).build());
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
            cfg.put("appid", "HL1");
            cfg.put("appkey", KEY);
            cfg.put("biztype", "1");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }
}
