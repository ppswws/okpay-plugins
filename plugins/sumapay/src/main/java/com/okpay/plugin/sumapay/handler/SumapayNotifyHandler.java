package com.okpay.plugin.sumapay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.sumapay.util.*;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 丰付支付异步通知处理器（支付通知 / 退款通知 / 付款至二级户通知）。
 *
 * <p>通知体为 GBK 编码表单，全部 ack 按渠道约定返回文本。</p>
 */
public class SumapayNotifyHandler {

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

    public NotifyResponse notify(InvokeContext ctx) {
        var order = ctx.getOrder();
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        if (!SumapaySignUtil.verify(cfg.getAppkey(), SumapaySignUtil.concat(params, ORDER_NOTIFY_FIELDS),
                params.get("resultSignature")))
            return Notify.fail("sign_error");
        if (!"2".equals(params.get("status")))
            return Notify.fail("fail");
        if (!order.getTradeNo().equals(params.get("requestId")))
            return Notify.fail("fail");
        // 金额校验仅在有 totalPrice 时进行（部分场景渠道不返回金额字段）
        var totalPrice = params.get("totalPrice");
        if (totalPrice != null && !totalPrice.isBlank() && order.getReal() != toCents(totalPrice))
            return Notify.fail("amount_mismatch");
        var buyer = "wechatpay".equals(params.get("bankCode"))
                ? params.get("openId") : params.get("alipayUserId");
        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(), params.get("channelSn"), buyer);
        } catch (Exception e) {
            return Notify.fail("fail");
        }
        return Notify.ok("success");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    public NotifyResponse refundNotify(InvokeContext ctx) {
        // 契约：notify 回调实体恒非空（同 notify）；refund.tradeNo 恒非空
        var refund = ctx.getRefund();
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        // 双路径验签：付款至二级户通知可能复用退款回调地址。按 result 字段判别给出优先序
        // （渠道两种报文的主特征），判别路失败再试另一套字段表——字段扩充击穿判别码时
        // 仍有第二条路可通，不整类拒收（判别只决定顺序，不决定成败）。
        var verifyOk = params.containsKey("result")
                ? (verifyMerchant(cfg, params) || verifyRefund(cfg, params))
                : (verifyRefund(cfg, params) || verifyMerchant(cfg, params));
        if (!verifyOk)
            return Notify.fail("sign_error");
        var requestId = params.get("requestId");
        if (requestId != null && !requestId.isBlank() && !refund.getRefundNo().equals(requestId))
            return Notify.fail("refund_mismatch");
        var refundResult = params.get("refundResult");
        try {
            switch (refundResult == null ? "" : refundResult) {
                case "0" -> Sdk.completeRefundOk(ctx, refund.getRefundNo(), requestId, null);
                case "1" -> Sdk.completeRefundFail(ctx, refund.getRefundNo(), refundResult, null);
                default -> Sdk.completeRefundWait(ctx, refund.getRefundNo(), requestId, refundResult, null);
            }
        } catch (Exception e) {
            return Notify.fail("complete_error");
        }
        return Notify.ok("success");
    }

    // =========================================================================
    // 付款至二级户通知
    // =========================================================================

    public NotifyResponse payMerchantNotify(InvokeContext ctx) {
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        if (!SumapaySignUtil.verify(cfg.getAppkey(), SumapaySignUtil.concat(params, PAY_MERCHANT_NOTIFY_FIELDS),
                params.get("signature")))
            return Notify.fail("sign_error");
        // 仅确认签收，不推进任何业务状态（转入结果由后续退款查单/重试兜底）
        return Notify.ok("success");
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 付款至二级户通知字段表验签 */
    private static boolean verifyMerchant(SumapayConfig cfg, Map<String, String> params) {
        return SumapaySignUtil.verify(cfg.getAppkey(),
                SumapaySignUtil.concat(params, PAY_MERCHANT_NOTIFY_FIELDS), params.get("signature"));
    }

    /** 退款通知字段表验签 */
    private static boolean verifyRefund(SumapayConfig cfg, Map<String, String> params) {
        return SumapaySignUtil.verify(cfg.getAppkey(),
                SumapaySignUtil.concat(params, REFUND_NOTIFY_FIELDS), params.get("resultSignature"));
    }

    /** 通知参数解析：query + body 合并（GBK 表单），body 后出现覆盖 query */
    private static Map<String, String> parseNotifyForm(InvokeContext ctx) {
        var params = new LinkedHashMap<String, String>();
        var req = ctx.getRequest();
        if (req == null) return params;
        parsePair(params, req.getQuery());
        if (req.getBody() != null && req.getBody().length > 0)
            parsePair(params, new String(req.getBody(), GBK));
        return params;
    }

    private static void parsePair(Map<String, String> params, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (var pair : raw.split("&")) {
            var idx = pair.indexOf('=');
            if (idx <= 0) continue;
            var key = decode(pair.substring(0, idx));
            var value = decode(pair.substring(idx + 1));
            if (!key.isBlank()) params.put(key, value);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, GBK);
        } catch (Exception e) {
            return s;
        }
    }
}
