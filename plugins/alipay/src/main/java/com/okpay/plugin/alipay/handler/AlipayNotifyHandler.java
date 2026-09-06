package com.okpay.plugin.alipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import tools.jackson.databind.JsonNode;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.alipay.util.AlipayConfig;
import org.slf4j.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 支付宝异步通知处理器。
 *
 * <p>流程：验签（RSA2）→ 校验字段 → 推进订单/退款状态机。</p>
 */
public class AlipayNotifyHandler {

    private static final Logger log = LoggerFactory.getLogger(AlipayNotifyHandler.class);

    /** 支付通知 */
    public PageResponse payNotify(InvokeContext ctx) {
        var order = ctx.getOrder();
        if (order == null || order.getTradeNo() == null || order.getTradeNo().isBlank())
            return Responses.notifyFail(ctx, "order_mismatch");

        var cfg = AlipayConfig.from(ctx);
        var params = parseNotifyParams(ctx);
        if (params.isEmpty()) return Responses.notifyFail(ctx, "invalid_params");

        try {
            // RSA2 验签（AlipayOpenApiClient 内置，对照官方 rsaCheckV1）
            if (!cfg.client().verifyNotify(params)) {
                log.debug("支付宝通知验签失败, tradeNo={}", order.getTradeNo());
                return Responses.notifyFail(ctx, "sign_error");
            }

            // app_id 校验
            if (!cfg.getAppId().equals(params.get("app_id"))) {
                return Responses.notifyFail(ctx, "app_mismatch");
            }

            // 商户订单号交叉校验（fail-closed）：验签通过仅证明来自支付宝，不保证回调就是这笔本地订单
            if (!order.getTradeNo().equals(params.get("out_trade_no"))) {
                return Responses.notifyFail(ctx, "order_mismatch");
            }

            // 合单支付通知（直付通 merge 协议）：merge_pay_status 判定，order_detail_results 参与 RSA2 签名
            var mergeStatus = params.get("merge_pay_status");
            if (mergeStatus != null) {
                return handleMergeNotify(ctx, order, params, mergeStatus);
            }

            var tradeStatus = params.get("trade_status");
            if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
                log.debug("支付宝通知状态非终态, tradeNo={}, status={}", order.getTradeNo(), tradeStatus);
                if ("TRADE_CLOSED".equals(tradeStatus)) {
                    Sdk.completeOrderFail(ctx, order.getTradeNo(), tradeStatus, tradeStatus);
                }
                return Responses.notifyOk(ctx, "success");
            }

            // 校验金额（fail-closed）
            var notifyAmount = params.get("total_amount");
            if (notifyAmount == null || order.getReal() != toCents(notifyAmount)) {
                return Responses.notifyFail(ctx, "amount_mismatch");
            }

            // 推进状态机
            try {
                Sdk.completeOrderOk(ctx, order.getTradeNo(),
                        params.get("trade_no"), buyerIdOf(params));
            } catch (Exception e) {
                log.error("支付宝完成订单失败, tradeNo={}", order.getTradeNo(), e);
                return Responses.notifyFail(ctx, "complete_error");
            }

            return Responses.notifyOk(ctx, "success");

        } catch (Exception e) {
            log.error("支付宝通知处理失败, tradeNo={}", order.getTradeNo(), e);
            return Responses.notifyFail(ctx, "notify_error");
        }
    }

    /** 退款通知 */
    public PageResponse refundNotify(InvokeContext ctx) {
        var refund = ctx.getRefund();
        if (refund == null || refund.getRefundNo() == null || refund.getRefundNo().isBlank())
            return Responses.notifyFail(ctx, "refund_mismatch");

        var cfg = AlipayConfig.from(ctx);
        var params = parseNotifyParams(ctx);
        if (params.isEmpty()) return Responses.notifyFail(ctx, "invalid_refund_params");

        try {
            if (!cfg.client().verifyNotify(params))
                return Responses.notifyFail(ctx, "refund_sign_error");

            var refundStatus = params.get("refund_status");
            if (!"REFUND_SUCCESS".equals(refundStatus)) {
                log.debug("支付宝退款通知非成功, refundNo={}, status={}",
                        refund.getRefundNo(), refundStatus);
                return Responses.notifyOk(ctx, "success");
            }
            var tradeNo = refund.getTradeNo() != null ? refund.getTradeNo() : "";
            // 退款单缺 tradeNo 属异常数据：不调 getSubOrders（宿主对空单号抛参），按单笔路径处理
            var subs = tradeNo.isBlank() ? List.<SubOrderSnapshot>of() : Sdk.getSubOrders(ctx, tradeNo);
            if (subs.isEmpty()) {
                // 单笔退款通知：REFUND_SUCCESS 即终态
                Sdk.completeRefundOk(ctx, refund.getRefundNo(), params.get("trade_no"));
                return Responses.notifyOk(ctx, "success");
            }
            // 合单退款通知：out_request_no = refundNo_序号 → 累计对应子单（通知重发 → 超限=已累计，幂等）
            var idx = PaymentUtils.refundIdx(params.get("out_request_no"), refund.getRefundNo());
            if (idx > 0 && idx <= subs.size()) {
                var fee = refundFeeCents(params.get("refund_fee"));
                if (fee == null) {
                    // 金额缺失/非法 → 不累计不推进，靠查单续退兜底
                    log.debug("合单退款通知金额缺失, refundNo={}, sub={}",
                            refund.getRefundNo(), subs.get(idx - 1).getSubTradeNo());
                } else {
                    try {
                        Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                                .tradeNo(tradeNo)
                                .subTradeNo(subs.get(idx - 1).getSubTradeNo())
                                .refundDelta(fee).build());
                    } catch (IllegalStateException e) {
                        log.debug("合单退款通知重复累计被拒（已到额）, refundNo={}, sub={}",
                                refund.getRefundNo(), subs.get(idx - 1).getSubTradeNo());
                    }
                }
            }
            // 全部子单退完才推进主退款单终态（部分退完保持 PENDING，靠查单续退）
            var after = Sdk.getSubOrders(ctx, tradeNo);
            if (after.stream().allMatch(s -> s.getRefundMoney() >= s.getMoney())) {
                Sdk.completeRefundOk(ctx, refund.getRefundNo(), params.get("trade_no"));
            }
        } catch (Exception e) {
            log.error("支付宝退款通知处理失败, refundNo={}", refund.getRefundNo(), e);
        }

        return Responses.notifyOk(ctx, "success");
    }

    /** 退款金额（元字符串）→ 分；缺失/非法 → null（不累计，防误推进，靠查单兜底） */
    private static Long refundFeeCents(String yuan) {
        if (yuan == null || yuan.isBlank()) return null;
        try {
            return PaymentUtils.toCents(yuan);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 合单支付通知：merge_pay_status=='FINISHED' 才推进。
     * order_detail_results 是 JSON 数组字符串（参与 RSA2 签名，验签已通过），
     * 逐项校验与库内子单一一对应（数量一致、无重复、全 SUCCESS）；
     * 全通过 → 逐子单已付（apiTradeNo=渠道交易号）→ 主单完成。
     * 任一不符 → notifyFail 拒收（宿主保持 PENDING，等重发/查单兜底）。
     */
    private PageResponse handleMergeNotify(InvokeContext ctx, OrderSnapshot order,
                                           Map<String, String> params, String mergeStatus) {
        if (!"FINISHED".equals(mergeStatus)) {
            log.debug("支付宝合单通知非终态, tradeNo={}, status={}", order.getTradeNo(), mergeStatus);
            return Responses.notifyOk(ctx, "success");
        }
        var subs = Sdk.getSubOrders(ctx, order.getTradeNo());
        if (subs.isEmpty()) {
            log.debug("支付宝合单通知无子单记录, tradeNo={}", order.getTradeNo());
            return Responses.notifyFail(ctx, "order_mismatch");
        }
        try {
            var results = HttpHelper.MAPPER.readTree(params.get("order_detail_results"));
            if (results == null || !results.isArray() || results.size() != subs.size())
                return Responses.notifyFail(ctx, "amount_mismatch");
            var bySubNo = new LinkedHashMap<String, JsonNode>();
            for (var r : results) {
                var no = r.path("out_trade_no").asString("");
                if (no.isEmpty() || !"SUCCESS".equals(r.path("result_code").asString(""))
                        || bySubNo.put(no, r) != null) {
                    return Responses.notifyFail(ctx, "amount_mismatch");
                }
            }
            String firstApi = null;
            for (var sub : subs) {
                var r = bySubNo.get(sub.getSubTradeNo());
                if (r == null) return Responses.notifyFail(ctx, "amount_mismatch");
                // 子单金额校验（fail-closed）：通知金额与库内子单不一致拒绝推进
                var subAmount = r.path("total_amount").asString(null);
                if (subAmount == null || sub.getMoney() != toCents(subAmount)) {
                    log.debug("支付宝合单通知子单金额不符, tradeNo={}, sub={}",
                            order.getTradeNo(), sub.getSubTradeNo());
                    return Responses.notifyFail(ctx, "amount_mismatch");
                }
                var api = r.path("trade_no").asString("");
                if (firstApi == null) firstApi = api;
                Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                        .tradeNo(order.getTradeNo()).subTradeNo(sub.getSubTradeNo())
                        .apiTradeNo(api).status(SubOrderSnapshot.STATUS_PAID).build());
            }
            Sdk.completeOrderOk(ctx, order.getTradeNo(), firstApi, buyerIdOf(params));
            return Responses.notifyOk(ctx, "success");
        } catch (Exception e) {
            log.error("支付宝合单通知解析失败, tradeNo={}", order.getTradeNo(), e);
            return Responses.notifyFail(ctx, "notify_error");
        }
    }

    /** 买家标识：buyer_id（账号级 userId）缺失时回退 buyer_open_id（应用级，随新版接口返回） */
    private static String buyerIdOf(Map<String, String> params) {
        var buyer = params.get("buyer_id");
        return buyer != null && !buyer.isBlank() ? buyer : params.get("buyer_open_id");
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private Map<String, String> parseNotifyParams(InvokeContext ctx) {
        if (ctx.getRequest() == null || ctx.getRequest().getBody() == null)
            return Map.of();

        var body = new String(ctx.getRequest().getBody(), StandardCharsets.UTF_8);
        var params = new LinkedHashMap<String, String>();
        for (var p : body.split("&")) {
            var idx = p.indexOf("=");
            if (idx > 0) {
                params.put(
                    URLDecoder.decode(p.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

}
