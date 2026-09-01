package com.okpay.plugin.epay.handler;

import com.okpay.plugin.model.*;
import com.okpay.plugin.enums.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.epay.util.*;
import org.slf4j.*;

import java.util.Map;

/**
 * 易支付订单查询处理器。
 */
public class EpayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(EpayQueryHandler.class);

    public EpayQueryHandler() {
        on(BizType.T_PAY, this::query);
    }

    private BizResult query(InvokeContext ctx, BizRequest req) {
        var order = ctx.getOrder();
        if (order == null || order.getTradeNo() == null || order.getTradeNo().isBlank())
            return Responses.fail("PARAM_ERROR", "订单为空");

        var qr = queryRaw(ctx, order.getTradeNo());
        var m = qr.body();
        var state = "1".equals(m.get("code")) && "1".equals(m.get("status")) ? BizState.S_OK : BizState.S_ING;
        return Responses.result(state)
                .apiNo(m.get("api_trade_no")).code(m.get("code"))
                .msg(state == BizState.S_OK ? m.get("msg") : m.getOrDefault("msg", "").trim())
                .buyer(m.get("buyer")).traced(qr.resp()).build();
    }

    /** 查询结果：解析后的响应体 + HTTP 响应（供轨迹闭环回传）。 */
    record QueryResult(Map<String, String> body, HttpHelper.HttpResponse resp) {

        /** 查询瞬时失败的哨兵值：空响应体、无请求轨迹。 */
        static QueryResult empty() { return new QueryResult(Map.of(), null); }
    }

    /**
     * 内部查询 — 瞬时失败返回空响应体；resp 携带本次 HTTP 请求轨迹。
     * 供 NotifyHandler 做服务器二次确认复用。
     */
    static QueryResult queryRaw(InvokeContext ctx, String tradeNo) {
        var cfg = EpayConfig.from(ctx);
        var url = cfg.getAppurl() + "/api.php?act=order&pid="
                + PaymentUtils.urlEncode(cfg.getAppid()) + "&key=" + PaymentUtils.urlEncode(cfg.getAppkey())
                + "&out_trade_no=" + PaymentUtils.urlEncode(tradeNo);
        try {
            var resp = HttpHelper.get(ctx, url);
            return new QueryResult(PaymentUtils.parseJsonMap(resp.bodyAsString()), resp);
        } catch (Exception e) {
            log.debug("易支付订单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return QueryResult.empty();
        }
    }
}
