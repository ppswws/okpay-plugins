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
        // 契约：T_PAY query 时内核恒填 order，缺失即宿主缺陷——不再判空兜底
        var order = ctx.getOrder();
        var qr = queryRaw(ctx, order.getTradeNo());
        var m = qr.body();
        // 支付订单查单不使用 S_FAIL：未支付/不存在/渠道异常一律结果未定
        var paid = EpayRespUtil.isPaid(m);
        var state = paid ? BizState.S_OK : BizState.S_WAIT;
        return Responses.result(state)
                .apiNo(m.get("trade_no")).code(m.get("code"))
                .msg(paid ? m.get("msg") : m.getOrDefault("msg", "").trim())
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
