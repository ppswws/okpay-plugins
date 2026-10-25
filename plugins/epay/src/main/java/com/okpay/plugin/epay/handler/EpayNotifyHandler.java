package com.okpay.plugin.epay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.epay.util.*;

import java.util.Map;

/**
 * 易支付异步通知处理器。
 *
 * <p>流程：验签 → 校验字段 → 服务器二次查询确认（复用 {@link EpayQueryHandler#queryRaw}）
 * → 推进订单状态机。</p>
 */
public class EpayNotifyHandler {

    public NotifyResponse handle(InvokeContext ctx) {
        // 契约：notify 回调按 tradeNo 前缀路由，实体缺失不发插件（PluginPageService.dispatch→404），order 恒非空
        var order = ctx.getOrder();
        var cfg = EpayConfig.from(ctx);
        var params = parseNotifyParams(ctx);
        if (params.isEmpty() || !EpaySignUtil.verify(params, cfg.getAppkey()))
            return Notify.fail(params.isEmpty() ? "invalid_notify_params" : "sign_error");
        if (!"TRADE_SUCCESS".equals(params.get("trade_status")))
            return Notify.fail("trade_status_invalid");
        if (!order.getTradeNo().equals(params.get("out_trade_no")))
            return Notify.fail("order_mismatch");
        if (order.getReal() != toCents(params.getOrDefault("money", "0")))
            return Notify.fail("amount_mismatch");

        // 服务器二次确认（复用 QueryHandler）
        var qr = EpayQueryHandler.queryRaw(ctx, order.getTradeNo()).body();
        if (!EpayRespUtil.isSuccess(qr))
            return Notify.fail("query_unpaid");

        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(),
                    params.get("trade_no"), params.get("buyer"));
        } catch (Exception e) {
            return Notify.fail("complete_error");
        }
        return Notify.ok("success");
    }

    private Map<String, String> parseNotifyParams(InvokeContext ctx) {
        if (ctx.getRequest() == null) return Map.of();
        return PaymentUtils.parseForm(ctx.getRequest().getQuery());
    }
}
