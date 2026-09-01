package com.okpay.plugin.epay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.epay.util.*;
import org.slf4j.*;

import java.util.Map;

/**
 * 易支付异步通知处理器。
 *
 * <p>流程：验签 → 校验字段 → 服务器二次查询确认（复用 {@link EpayQueryHandler#queryRaw}）
 * → 推进订单状态机。</p>
 */
public class EpayNotifyHandler {

    private static final Logger log = LoggerFactory.getLogger(EpayNotifyHandler.class);

    public PageResponse handle(InvokeContext ctx) {
        var order = ctx.getOrder();
        if (order == null || order.getTradeNo() == null || order.getTradeNo().isBlank())
            return Responses.notifyFail(ctx, "order_mismatch");

        var cfg = EpayConfig.from(ctx);
        var params = parseNotifyParams(ctx);
        if (params.isEmpty() || !EpaySignUtil.verify(params, cfg.getAppkey()))
            return Responses.notifyFail(ctx, params.isEmpty() ? "invalid_notify_params" : "sign_error");
        if (!"TRADE_SUCCESS".equals(params.get("trade_status")))
            return Responses.notifyFail(ctx, "trade_status_invalid");
        if (!order.getTradeNo().equals(params.get("out_trade_no")))
            return Responses.notifyFail(ctx, "order_mismatch");
        if (order.getReal() != toCents(params.getOrDefault("money", "0")))
            return Responses.notifyFail(ctx, "amount_mismatch");

        // 服务器二次确认（复用 QueryHandler）
        var qr = EpayQueryHandler.queryRaw(ctx, order.getTradeNo()).body();
        if (!"1".equals(qr.get("code")) || !"1".equals(qr.get("status")))
            return Responses.notifyFail(ctx, "query_unpaid");

        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(),
                    params.get("trade_no"), params.get("buyer"));
        } catch (Exception e) {
            log.error("易支付完成订单失败, tradeNo={}", order.getTradeNo(), e);
            return Responses.notifyFail(ctx, "complete_error");
        }
        return Responses.notifyOk(ctx, "success");
    }

    private Map<String, String> parseNotifyParams(InvokeContext ctx) {
        if (ctx.getRequest() == null) return Map.of();
        return PaymentUtils.parseForm(ctx.getRequest().getQuery());
    }
}
