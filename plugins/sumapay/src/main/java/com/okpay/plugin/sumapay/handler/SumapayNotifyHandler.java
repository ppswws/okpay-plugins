package com.okpay.plugin.sumapay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.sumapay.util.*;
import org.slf4j.*;

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

    private static final Logger log = LoggerFactory.getLogger(SumapayNotifyHandler.class);

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

    public PageResponse notify(InvokeContext ctx) {
        var order = ctx.getOrder();
        if (order == null || order.getTradeNo() == null || order.getTradeNo().isBlank())
            return Responses.notifyFail(ctx, "fail");
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        if (!SumapaySignUtil.verify(cfg.getAppkey(), SumapaySignUtil.concat(params, ORDER_NOTIFY_FIELDS),
                params.get("resultSignature")))
            return Responses.notifyFail(ctx, "sign_error");
        if (!"2".equals(params.get("status")))
            return Responses.notifyFail(ctx, "fail");
        if (!order.getTradeNo().equals(params.get("requestId")))
            return Responses.notifyFail(ctx, "fail");
        // 金额校验仅在有 totalPrice 时进行（部分场景渠道不返回金额字段）
        var totalPrice = params.get("totalPrice");
        if (totalPrice != null && !totalPrice.isBlank() && order.getReal() != toCents(totalPrice))
            return Responses.notifyFail(ctx, "amount_mismatch");
        var buyer = "wechatpay".equals(params.get("bankCode"))
                ? params.get("openId") : params.get("alipayUserId");
        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(), params.get("channelSn"), buyer);
        } catch (Exception e) {
            log.error("完成订单失败", e);
            return Responses.notifyFail(ctx, "fail");
        }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    public PageResponse refundNotify(InvokeContext ctx) {
        var refund = ctx.getRefund();
        if (refund == null || refund.getRefundNo() == null || refund.getRefundNo().isBlank())
            return Responses.notifyOk(ctx, "success");
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        // 双路径验签：带 result 字段按付款至二级户通知验（可能复用退款回调地址），否则按退款通知验
        var verifyOk = params.containsKey("result")
                ? SumapaySignUtil.verify(cfg.getAppkey(),
                        SumapaySignUtil.concat(params, PAY_MERCHANT_NOTIFY_FIELDS), params.get("signature"))
                : SumapaySignUtil.verify(cfg.getAppkey(),
                        SumapaySignUtil.concat(params, REFUND_NOTIFY_FIELDS), params.get("resultSignature"));
        if (!verifyOk)
            return Responses.notifyFail(ctx, "sign_error");
        var requestId = params.get("requestId");
        if (requestId != null && !requestId.isBlank() && !refund.getRefundNo().equals(requestId))
            return Responses.notifyFail(ctx, "refund_mismatch");
        var refundResult = params.get("refundResult");
        try {
            switch (refundResult) {
                case "0" -> Sdk.completeRefundOk(ctx, refund.getRefundNo(), requestId, null);
                case "1" -> Sdk.completeRefundFail(ctx, refund.getRefundNo(), refundResult, null);
                default -> Sdk.completeRefundIng(ctx, refund.getRefundNo(), requestId, refundResult, null);
            }
        } catch (Exception e) {
            log.error("完成退款失败", e);
            return Responses.notifyFail(ctx, "complete_error");
        }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 付款至二级户通知
    // =========================================================================

    public PageResponse payMerchantNotify(InvokeContext ctx) {
        var cfg = SumapayConfig.from(ctx);
        var params = parseNotifyForm(ctx);
        if (!SumapaySignUtil.verify(cfg.getAppkey(), SumapaySignUtil.concat(params, PAY_MERCHANT_NOTIFY_FIELDS),
                params.get("signature")))
            return Responses.notifyFail(ctx, "sign_error");
        // 仅确认签收，不推进任何业务状态（转入结果由后续退款查单/重试兜底）
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 内部
    // =========================================================================

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
