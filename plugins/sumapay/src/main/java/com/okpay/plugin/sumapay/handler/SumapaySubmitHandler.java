package com.okpay.plugin.sumapay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;
import static com.okpay.plugin.sdk.PaymentUtils.toYuan;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.sumapay.util.*;
import org.slf4j.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 丰付支付退款处理器。
 *
 * <p>退款编排（非当日订单）：IFSU0043 二级户余额查询（requestId="Q"+退款单号）→
 * 余额不足时 IFSU0040 付款至二级户（"F"+退款单号）→ Refund_do（requestId=退款单号）。
 * 当日订单直接 Refund_do。先查余额再转入：退款失败重试时若余额已充足则跳过转入，
 * 避免同一笔退款重复付款至二级户。</p>
 */
public class SumapaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(SumapaySubmitHandler.class);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 受理中（已受理，非终态） */
    private static final List<String> ACCEPTED = List.of("00001", "200300305", "200300304");

    private static final List<String> BALANCE_REQ_FIELDS = List.of(
            "requestType", "requestId", "merchantCode", "userIdIdentity");
    private static final List<String> BALANCE_RESP_FIELDS = List.of(
            "requestId", "result", "userIdIdentity", "availSum");
    private static final List<String> FUND_REQ_FIELDS = List.of(
            "requestType", "requestId", "merchantCode", "userIdIdentity", "sum", "reason", "noticeUrl");
    private static final List<String> FUND_RESP_FIELDS = List.of(
            "requestId", "result", "merchantCode", "userIdIdentity");
    private static final List<String> REFUND_REQ_FIELDS = List.of(
            "requestId", "originalRequestId", "tradeProcess", "fund", "noticeUrl", "remark");
    private static final List<String> REFUND_RESP_FIELDS = List.of(
            "requestId", "result", "remark");

    public SumapaySubmitHandler() {
        on(BizType.T_REF, (ctx, req) -> refund(ctx));
    }

    private BizResult refund(InvokeContext ctx) {
        // 契约：T_REF submit 时内核恒填 refund+order（退款/主单快照），缺失即宿主缺陷——不再判空兜底
        var order = ctx.getOrder();
        var refund = ctx.getRefund();
        var cfg = SumapayConfig.from(ctx);
        var refundNo = refund.getRefundNo();
        try {
            // 非当日订单：退款资金来自二级户，先查余额，不足再付款至二级户
            if (!isToday(order)) {
                var avail = queryBalance(ctx, cfg, refundNo);
                if (avail < refund.getAmount())
                    fundToSecond(ctx, cfg, refundNo, refund.getAmount());
            }
            return doRefund(ctx, cfg, order, refund);
        } catch (ChannelRejectedException e) {
            // 渠道业务拒绝（余额查询/付款至二级户失败）：退款未提交，终态失败
            return Responses.fail("REFUND_FAIL", e.getMessage());
        } catch (Exception e) {
            // 传输/HTTP/验签失败：退款结果未知，保持处理中待重试
            log.debug("丰付退款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 二级户余额查询（IFSU0043）
    // =========================================================================

    /** 返回二级户可用余额（分）；渠道业务失败抛 {@link ChannelRejectedException} */
    private static long queryBalance(InvokeContext ctx, SumapayConfig cfg, String refundNo) throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("requestType", "IFSU0043");
        params.put("requestId", "Q" + refundNo);
        params.put("merchantCode", cfg.getAppid());
        params.put("userIdIdentity", cfg.getAppuserid());
        var resp = SumapayApi.post(ctx, SumapayApi.FUND_SHARING_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                BALANCE_REQ_FIELDS, "signature", BALANCE_RESP_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        var result = m.getOrDefault("result", "");
        if (!"00000".equals(result))
            throw new ChannelRejectedException("二级户余额查询失败[" + result + "]");
        var availSum = m.get("availSum");
        if (availSum == null || availSum.isBlank())
            throw new ChannelRejectedException("二级户余额查询失败[余额为空]");
        return toCents(availSum);
    }

    // =========================================================================
    // 付款至二级户（IFSU0040）
    // =========================================================================

    private static void fundToSecond(InvokeContext ctx, SumapayConfig cfg, String refundNo, long amount)
            throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("requestType", "IFSU0040");
        params.put("requestId", "F" + refundNo);
        params.put("merchantCode", cfg.getAppid());
        params.put("userIdIdentity", cfg.getAppuserid());
        params.put("sum", toYuan(amount));
        params.put("reason", "退款所需金额");
        params.put("noticeUrl", cfg.getNotifyDomain() + "/pay/paymerchantnotify/" + refundNo);
        var resp = SumapayApi.post(ctx, SumapayApi.FUND_SHARING_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                FUND_REQ_FIELDS, "signature", FUND_RESP_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        var result = m.getOrDefault("result", "");
        if ("00000".equals(result)) return;
        // 受理中的转入无法确认到账，重试可能重复转入 → 直接终止退款（PHP 行为）
        if (ACCEPTED.contains(result))
            throw new ChannelRejectedException("付款至二级户已受理，请稍后再尝试退款");
        throw new ChannelRejectedException("付款至二级户失败[" + result + "]");
    }

    // =========================================================================
    // 退款（Refund_do）
    // =========================================================================

    private static BizResult doRefund(InvokeContext ctx, SumapayConfig cfg,
                                      OrderSnapshot order, RefundSnapshot refund) throws Exception {
        var refundNo = refund.getRefundNo();
        var params = new LinkedHashMap<String, String>();
        params.put("requestId", refundNo);
        params.put("originalRequestId", order.getTradeNo());
        params.put("tradeProcess", cfg.getAppid());
        params.put("fund", toYuan(refund.getAmount()));
        params.put("noticeUrl", cfg.getNotifyDomain() + "/pay/refundnotify/" + refundNo);
        params.put("reason", "协商退款");
        params.put("refundMothed", "1");
        params.put("remark", refundNo);
        var resp = SumapayApi.post(ctx, SumapayApi.REFUND_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                REFUND_REQ_FIELDS, "mersignature", REFUND_RESP_FIELDS, "resultSignature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        var result = m.getOrDefault("result", "");
        if ("00000".equals(result))
            return Responses.ok(refundNo, resp);
        if (ACCEPTED.contains(result))
            return Responses.ing(result, "退款请求已受理，正在处理", resp);
        if ("200300162".equals(result))
            return Responses.fail(result, "账号余额不足[200300162]", resp);
        if (result.isBlank())
            return Responses.fail("REFUND_FAIL", "退款返回解析失败", resp);
        return Responses.fail(result, "退款失败[" + result + "]", resp);
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 订单是否为当日（丰付按订单日期区分退款路径）；order 恒非空、单号恒 24 位（内核契约），不再判空/短号 */
    private static boolean isToday(OrderSnapshot order) {
        // 单号格式 [PRT]yyyyMMddHHmmss+序号，日期位 1-8
        var orderDate = order.getTradeNo().substring(1, 9);
        return orderDate.equals(LocalDate.now(SHANGHAI).format(DATE));
    }

    /** 渠道业务拒绝（余额查询/付款至二级户返回非成功码）：退款未提交，终态失败 */
    private static final class ChannelRejectedException extends RuntimeException {
        private ChannelRejectedException(String message) { super(message); }
    }
}
