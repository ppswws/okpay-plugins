package com.okpay.plugin.epay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.enums.BizType;
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
        // 契约：notify 回调按 tradeNo 前缀路由，实体缺失不发插件（PluginPageService.dispatch→404），order 恒非空
        var order = ctx.getOrder();
        var cfg = EpayConfig.from(ctx);
        var params = parseNotifyParams(ctx);
        if (params.isEmpty() || !EpaySignUtil.verify(params, cfg.getAppkey()))
            return Responses.notifyFail(params.isEmpty() ? "invalid_notify_params" : "sign_error", BizType.T_PAY);
        if (!"TRADE_SUCCESS".equals(params.get("trade_status")))
            return Responses.notifyFail("trade_status_invalid", BizType.T_PAY);
        if (!order.getTradeNo().equals(params.get("out_trade_no")))
            return Responses.notifyFail("order_mismatch", BizType.T_PAY);
        if (order.getReal() != toCents(params.getOrDefault("money", "0")))
            return Responses.notifyFail("amount_mismatch", BizType.T_PAY);

        // 服务器二次确认（复用 QueryHandler）
        var qr = EpayQueryHandler.queryRaw(ctx, order.getTradeNo()).body();
        if (!EpayRespUtil.isSuccess(qr))
            return Responses.notifyFail("query_unpaid", BizType.T_PAY);

        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(),
                    params.get("trade_no"), params.get("buyer"));
        } catch (Exception e) {
            return Responses.notifyFail("complete_error", BizType.T_PAY);
        }
        return Responses.notifyOk("success", BizType.T_PAY);
    }

    private Map<String, String> parseNotifyParams(InvokeContext ctx) {
        if (ctx.getRequest() == null) return Map.of();
        return PaymentUtils.parseForm(ctx.getRequest().getQuery());
    }
}
