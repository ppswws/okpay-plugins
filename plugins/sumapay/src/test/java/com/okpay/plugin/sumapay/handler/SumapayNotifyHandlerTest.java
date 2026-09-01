package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sumapay.TestKeys;
import com.okpay.plugin.sumapay.util.SumapaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SumapayNotifyHandler 通知：GBK 表单解析、双路径退款验签、金额校验、
 * 状态推进（completeOrder/completeRefund）与 ack 文案。
 */
@DisplayName("SumapayNotifyHandler 通知")
class SumapayNotifyHandlerTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final List<String> ORDER_NOTIFY_FIELDS = List.of(
            "requestId", "payId", "fiscalDate", "description", "totalPrice",
            "tradeAmount", "tradeFee");
    private static final List<String> REFUND_NOTIFY_FIELDS = List.of(
            "requestId", "originalRequestId", "refundResult", "refundTime");
    private static final List<String> PAY_MERCHANT_NOTIFY_FIELDS = List.of(
            "requestId", "merchantCode", "result");

    // =========================================================================
    // 支付通知
    // =========================================================================

    @Test
    @DisplayName("支付通知验签通过 → 完成订单（apiNo=channelSn、buyer=openId）→ success")
    void orderNotifyOk() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "T1", "status", "2", "bankCode", "wechatpay",
                "openId", "wxOPENID1", "alipayUserId", "2088-1",
                "channelSn", "FX202608230001", "totalPrice", "1.00"), ORDER_NOTIFY_FIELDS));
        var cb = ctx.getCallback();

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getType()).isEqualTo("html");
        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(com.okpay.plugin.model.CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getApiNo()).isEqualTo("FX202608230001");
        assertThat(captor.getValue().getBuyer()).isEqualTo("wxOPENID1");
    }

    @Test
    @DisplayName("支付通知（非微信支付）→ buyer 取 alipayUserId")
    void orderNotifyAlipayBuyer() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "T1", "status", "2", "bankCode", "alipay",
                "alipayUserId", "2088-2", "channelSn", "FX001", "totalPrice", "1.00"),
                ORDER_NOTIFY_FIELDS));
        var cb = ctx.getCallback();

        new SumapayNotifyHandler().notify(ctx);

        var captor = ArgumentCaptor.forClass(com.okpay.plugin.model.CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBuyer()).isEqualTo("2088-2");
    }

    @Test
    @DisplayName("支付通知无 totalPrice → 跳过金额校验仍成功")
    void orderNotifyWithoutPrice() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "T1", "status", "2", "channelSn", "FX001"),
                ORDER_NOTIFY_FIELDS));

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getDataText()).isEqualTo("success");
    }

    @Test
    @DisplayName("支付通知金额不符 → amount_mismatch")
    void orderNotifyAmountMismatch() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "T1", "status", "2", "totalPrice", "2.00"),
                ORDER_NOTIFY_FIELDS));

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
    }

    @Test
    @DisplayName("支付通知验签失败 → sign_error，不推进状态")
    void orderNotifySignError() throws Exception {
        var ctx = notifyCtx(gbkForm(Map.of(
                "requestId", "T1", "status", "2", "totalPrice", "1.00",
                "resultSignature", "BAD")));
        var cb = ctx.getCallback();

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("支付通知 status != 2 → fail")
    void orderNotifyStatusInvalid() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "T1", "status", "1"), ORDER_NOTIFY_FIELDS));

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    @Test
    @DisplayName("支付通知 requestId 与订单不符 → fail")
    void orderNotifyTradeNoMismatch() throws Exception {
        var ctx = notifyCtx(orderForm(Map.of(
                "requestId", "OTHER1", "status", "2", "totalPrice", "1.00"),
                ORDER_NOTIFY_FIELDS));

        var resp = new SumapayNotifyHandler().notify(ctx);

        assertThat(resp.getDataText()).isEqualTo("fail");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    @Test
    @DisplayName("退款通知 refundResult=0 → 退款成功终态")
    void refundNotifyOk() throws Exception {
        var ctx = refundNotifyCtx(refundForm(Map.of(
                "requestId", "R1", "originalRequestId", "T1",
                "refundResult", "0", "refundTime", "20260823120000")));
        var cb = ctx.getCallback();

        var resp = new SumapayNotifyHandler().refundNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("success");
        var captor = ArgumentCaptor.forClass(com.okpay.plugin.model.CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(com.okpay.plugin.enums.BizState.S_OK);
    }

    @Test
    @DisplayName("退款通知 refundResult=1 → 退款失败终态")
    void refundNotifyFail() throws Exception {
        var ctx = refundNotifyCtx(refundForm(Map.of(
                "requestId", "R1", "originalRequestId", "T1",
                "refundResult", "1", "refundTime", "20260823120000")));
        var cb = ctx.getCallback();

        new SumapayNotifyHandler().refundNotify(ctx);

        var captor = ArgumentCaptor.forClass(com.okpay.plugin.model.CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(com.okpay.plugin.enums.BizState.S_FAIL);
    }

    @Test
    @DisplayName("退款通知 refundResult 其他 → 退款处理中")
    void refundNotifyPending() throws Exception {
        var ctx = refundNotifyCtx(refundForm(Map.of(
                "requestId", "R1", "originalRequestId", "T1",
                "refundResult", "9", "refundTime", "20260823120000")));
        var cb = ctx.getCallback();

        new SumapayNotifyHandler().refundNotify(ctx);

        var captor = ArgumentCaptor.forClass(com.okpay.plugin.model.CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(com.okpay.plugin.enums.BizState.S_ING);
    }

    @Test
    @DisplayName("退款通知 requestId 与退款单号不符 → refund_mismatch")
    void refundNotifyMismatch() throws Exception {
        var ctx = refundNotifyCtx(refundForm(Map.of(
                "requestId", "R9", "originalRequestId", "T1",
                "refundResult", "0", "refundTime", "20260823120000")));

        var resp = new SumapayNotifyHandler().refundNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("refund_mismatch");
    }

    @Test
    @DisplayName("退款通知带 result 字段 → 按付款通知字段表验签（误走退款表则无 resultSignature 直接 sign_error）")
    void refundNotifyWithResultField() throws Exception {
        // requestId 为付款单（F 前缀），验签通过后命中 refund_mismatch 而非 sign_error，
        // 证明验签走了付款通知字段表（signature 而非 resultSignature）
        var ctx = refundNotifyCtx(gbkForm(signedFields(Map.of(
                "requestId", "FR1", "merchantCode", "SM1", "result", "00000"),
                PAY_MERCHANT_NOTIFY_FIELDS, "signature")));

        var resp = new SumapayNotifyHandler().refundNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("refund_mismatch");
    }

    @Test
    @DisplayName("退款通知验签失败 → sign_error")
    void refundNotifySignError() throws Exception {
        var ctx = refundNotifyCtx(gbkForm(Map.of(
                "requestId", "R1", "originalRequestId", "T1",
                "refundResult", "0", "refundTime", "20260823120000",
                "resultSignature", "BAD")));

        var resp = new SumapayNotifyHandler().refundNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("sign_error");
    }

    // =========================================================================
    // 付款至二级户通知
    // =========================================================================

    @Test
    @DisplayName("付款至二级户通知验签通过 → 仅签收 success")
    void payMerchantNotifyOk() throws Exception {
        var ctx = notifyCtx(gbkForm(signedFields(Map.of(
                "requestId", "FR1", "merchantCode", "SM1", "result", "00000"),
                PAY_MERCHANT_NOTIFY_FIELDS, "signature")));

        var resp = new SumapayNotifyHandler().payMerchantNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("success");
    }

    @Test
    @DisplayName("付款至二级户通知验签失败 → sign_error")
    void payMerchantNotifySignError() throws Exception {
        var ctx = notifyCtx(gbkForm(Map.of(
                "requestId", "FR1", "merchantCode", "SM1", "result", "00000")));

        var resp = new SumapayNotifyHandler().payMerchantNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("sign_error");
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private InvokeContext notifyCtx(byte[] body) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").real(100L).type("alipay").build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .request(RequestSnapshot.builder().body(body).build())
                .build();
    }

    private InvokeContext refundNotifyCtx(byte[] body) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").amount(100L).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .request(RequestSnapshot.builder().body(body).build())
                .build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "SM1");
            cfg.put("appuserid", "AU1");
            cfg.put("appmchid", "SUB1");
            cfg.put("appkey", TestKeys.PUBLIC_KEY);
            cfg.put("appsecret", TestKeys.PRIVATE_KEY);
            cfg.put("biztype", "1");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    /** 支付通知表单（GBK 编码 + resultSignature 签名） */
    private static byte[] orderForm(Map<String, String> fields, List<String> signFields) throws Exception {
        return gbkForm(signedFields(fields, signFields, "resultSignature"));
    }

    /** 退款通知表单（GBK 编码 + resultSignature 签名） */
    private static byte[] refundForm(Map<String, String> fields) throws Exception {
        return gbkForm(signedFields(fields, REFUND_NOTIFY_FIELDS, "resultSignature"));
    }

    private static Map<String, String> signedFields(Map<String, String> fields,
                                                    List<String> signFields, String signKey) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("merchantCode", "SM1");
        m.putAll(fields);
        m.put(signKey, SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                SumapaySignUtil.concat(m, signFields)));
        return m;
    }

    /** GBK 编码表单体 */
    private static byte[] gbkForm(Map<String, String> params) throws Exception {
        var sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), GBK)).append('=')
              .append(URLEncoder.encode(e.getValue(), GBK));
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
