package com.okpay.plugin.wxpay.handler;

import tools.jackson.databind.JsonNode;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.wxpay.util.WxpayConfig;
import org.slf4j.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信支付异步通知处理器（APIv3）。
 *
 * <p>验签 + AES-GCM 解密由 {@link WechatPayV3Client#parseNotify} 完成。</p>
 */
public class WxpayNotifyHandler {

    private static final Logger log = LoggerFactory.getLogger(WxpayNotifyHandler.class);

    // =========================================================================
    // 通知应答（微信 v3 以 HTTP 状态码判定重试：2xx 停止重试，非 2xx 按计划重试）
    // =========================================================================

    /** 应答成功：HTTP 200 + SUCCESS JSON（微信据此停止重试） */
    private static PageResponse ackOk(InvokeContext ctx) {
        return Responses.notifyOk(ctx, "{\"code\":\"SUCCESS\"}");
    }

    /** 应答失败：HTTP 400 + FAIL JSON（微信据此按计划重试） */
    private static PageResponse ackFail(InvokeContext ctx, String reason) {
        return Responses.notifyFail(ctx, "{\"code\":\"FAIL\",\"message\":\"" + reason + "\"}");
    }

    /** 支付通知（APIv3） */
    public PageResponse payNotify(InvokeContext ctx) {
        var order = ctx.getOrder();
        if (order == null || order.getTradeNo() == null || order.getTradeNo().isBlank())
            return ackFail(ctx, "order_mismatch");

        var cfg = WxpayConfig.from(ctx);
        var body = getNotifyBody(ctx);

        try {
            var result = cfg.client().parseNotify(body, headerList(getHeaders(ctx)));

            // 合单通知：顶层 combine_out_trade_no 判定（fail-closed：子单数/状态/金额任一不符拒收等重发）
            if (result.path("combine_out_trade_no").asString(null) != null) {
                return handleCombineNotify(ctx, cfg, order, result);
            }

            // 校验 out_trade_no 与订单一致
            if (!order.getTradeNo().equals(result.path("out_trade_no").asString(null))) {
                return ackFail(ctx, "order_mismatch");
            }

            var tradeState = result.path("trade_state").asString("");

            if (!"SUCCESS".equals(tradeState)) {
                log.debug("微信支付通知状态非成功, tradeNo={}, state={}", order.getTradeNo(), tradeState);
                // CLOSED/REVOKED/PAYERROR → 标记订单失败
                if ("CLOSED".equals(tradeState) || "REVOKED".equals(tradeState)
                        || "PAYERROR".equals(tradeState)) {
                    Sdk.completeOrderFail(ctx, order.getTradeNo(), tradeState, tradeState);
                }
                return ackOk(ctx);
            }

            // 校验金额（fail-closed：无金额或金额不匹配一律拒绝）
            var total = result.path("amount").path("total").asInt(-1);
            if (total < 0 || order.getReal() != (long) total) {
                return ackFail(ctx, "amount_mismatch");
            }

            // 微信官方通知，验签通过即可推进，无需二次查单。
            // 已配置系统公众号（统一OAuth首层已存 buyer 供风控）→ 渠道 openid 不覆盖身份；
            // 未配置 → 渠道 openid 即 buyer（现状）
            var buyer = ctx.getConfig().isOauthWxConfigured()
                    ? null : result.path("payer").path("openid").asString(null);
            try {
                Sdk.completeOrderOk(ctx, order.getTradeNo(),
                        result.path("transaction_id").asString(null), buyer);
            } catch (Exception e) {
                log.error("微信支付完成订单失败, tradeNo={}", order.getTradeNo(), e);
                return ackFail(ctx, "complete_error");
            }

            return ackOk(ctx);

        } catch (Exception e) {
            log.error("微信支付通知处理失败, tradeNo={}", order.getTradeNo(), e);
            return ackFail(ctx, "notify_error");
        }
    }

    /** 退款通知（APIv3） */
    public PageResponse refundNotify(InvokeContext ctx) {
        var refund = ctx.getRefund();
        if (refund == null || refund.getRefundNo() == null || refund.getRefundNo().isBlank())
            return ackFail(ctx, "refund_mismatch");

        var cfg = WxpayConfig.from(ctx);
        var body = getNotifyBody(ctx);

        try {
            var result = cfg.client().parseNotify(body, headerList(getHeaders(ctx)));
            var refundStatus = result.path("refund_status").asString("");
            var tradeNo = refund.getTradeNo() != null ? refund.getTradeNo() : "";
            var subs = tradeNo.isBlank() ? List.<SubOrderSnapshot>of() : Sdk.getSubOrders(ctx, tradeNo);
            if (!"SUCCESS".equals(refundStatus)) {
                // 合单退款逐子单提交，任一子单的 PROCESSING 通知也会推送到主单回调：
                // 不能据单条子单通知判主单 FAIL（否则后续成功通知无法恢复终态），由查单续退兜底；
                // 单笔退款保持判失败语义
                if (subs.isEmpty()) {
                    Sdk.completeRefundFail(ctx, refund.getRefundNo(), "REFUND_FAIL", refundStatus);
                } else {
                    log.debug("合单退款子单通知非成功, refundNo={}, refundStatus={}, 由查单续退兜底",
                            refund.getRefundNo(), refundStatus);
                }
                return ackOk(ctx);
            }
            if (subs.isEmpty()) {
                // 单笔退款通知
                Sdk.completeRefundOk(ctx, refund.getRefundNo(),
                        result.path("transaction_id").asString(null));
                return ackOk(ctx);
            }
            // 合单退款通知：out_refund_no = refundNo_序号 → 累计对应子单（通知重发 → 超限=已累计，幂等）
            var outRefundNo = result.path("out_refund_no").asString("");
            var idx = PaymentUtils.refundIdx(outRefundNo, refund.getRefundNo());
            if (idx > 0 && idx <= subs.size()) {
                // 金额缺失/非法（-1）→ 不累计不推进（防误走已付标记分支），靠查单续退兜底
                var delta = result.path("amount").path("refund").asLong(-1);
                if (delta <= 0) {
                    log.debug("合单退款通知金额缺失, refundNo={}, sub={}",
                            refund.getRefundNo(), subs.get(idx - 1).getSubTradeNo());
                } else {
                    try {
                        Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                                .tradeNo(tradeNo)
                                .subTradeNo(subs.get(idx - 1).getSubTradeNo())
                                .refundDelta(delta).build());
                    } catch (IllegalStateException e) {
                        log.debug("合单退款通知重复累计被拒（已到额）, refundNo={}, sub={}",
                                refund.getRefundNo(), subs.get(idx - 1).getSubTradeNo());
                    }
                }
            }
            // 全部子单退完才推进主退款单终态（部分退完保持 PENDING，靠查单续退）
            var after = Sdk.getSubOrders(ctx, tradeNo);
            if (after.stream().allMatch(s -> s.getRefundMoney() >= s.getMoney())) {
                Sdk.completeRefundOk(ctx, refund.getRefundNo(),
                        result.path("transaction_id").asString(null));
            }
        } catch (Exception e) {
            // 与支付通知对称：处理失败（验签/解密/落库异常）回 FAIL 让微信重发，不吞异常假确认
            log.error("微信退款通知处理失败, refundNo={}", refund.getRefundNo(), e);
            return ackFail(ctx, "notify_error");
        }

        return ackOk(ctx);
    }

    // =========================================================================
    // 合单通知（修正 epay 两坑：不校验子单 trade_state、金额合计不校验 → fail-closed）
    // =========================================================================

    /** 合单支付通知：子单数一致 + 全部 SUCCESS + 逐单金额一致 + 合计==实付，全部通过才推进 */
    private PageResponse handleCombineNotify(InvokeContext ctx, WxpayConfig cfg, OrderSnapshot order,
                                             JsonNode result) {
        if (!order.getTradeNo().equals(result.path("combine_out_trade_no").asString(null))) {
            return ackFail(ctx, "order_mismatch");
        }
        var subs = Sdk.getSubOrders(ctx, order.getTradeNo());
        var subArray = result.path("sub_orders");
        if (subs.isEmpty() || !subArray.isArray() || subArray.size() != subs.size()) {
            log.debug("合单通知子单数量不符, tradeNo={}, notify={}, db={}",
                    order.getTradeNo(), subArray.isArray() ? subArray.size() : -1, subs.size());
            return ackFail(ctx, "amount_mismatch");
        }
        long total = 0;
        String firstApi = null;
        // 按子单号建 map 配对（通知里 sub_orders 顺序无契约保证，禁用下标对位）
        var bySubNo = new LinkedHashMap<String, JsonNode>();
        for (var item : subArray) {
            var subNo = item.path("out_trade_no").asString(null);
            if (subNo == null || subNo.isBlank() || bySubNo.putIfAbsent(subNo, item) != null) {
                return ackFail(ctx, "amount_mismatch");
            }
        }
        for (var sub : subs) {
            var item = bySubNo.get(sub.getSubTradeNo());
            if (item == null) {
                log.debug("合单通知缺少子单, tradeNo={}, sub={}", order.getTradeNo(), sub.getSubTradeNo());
                return ackFail(ctx, "amount_mismatch");
            }
            if (!"SUCCESS".equals(item.path("trade_state").asString(""))) {
                log.debug("合单通知子单未成功, tradeNo={}, sub={}, state={}",
                        order.getTradeNo(), sub.getSubTradeNo(),
                        item.path("trade_state").asString(null));
                return ackFail(ctx, "amount_mismatch");
            }
            var amount = item.path("amount").path("total_amount").asLong(-1);
            if (amount < 0 || amount != sub.getMoney()) {
                log.debug("合单通知子单金额不符, tradeNo={}, sub={}, notify={}, db={}",
                        order.getTradeNo(), sub.getSubTradeNo(), amount, sub.getMoney());
                return ackFail(ctx, "amount_mismatch");
            }
            total += amount;
        }
        if (total != order.getReal()) {
            log.debug("合单通知合计金额不符, tradeNo={}, notify={}, real={}", order.getTradeNo(), total, order.getReal());
            return ackFail(ctx, "amount_mismatch");
        }
        // 全部校验通过：逐子单已付标记（apiTradeNo 已有值不覆盖，通知重发幂等）+ 主单完成
        for (var sub : subs) {
            var item = bySubNo.get(sub.getSubTradeNo());
            Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                    .tradeNo(order.getTradeNo())
                    .subTradeNo(sub.getSubTradeNo())
                    .apiTradeNo(item.path("transaction_id").asString(null))
                    .status(SubOrderSnapshot.STATUS_PAID).build());
            if (firstApi == null) firstApi = item.path("transaction_id").asString(null);
        }
        // 与单笔通知一致：已配置系统公众号 → 渠道 openid 不覆盖身份
        var buyer = ctx.getConfig().isOauthWxConfigured()
                ? null : result.path("combine_payer_info").path("openid").asString(null);
        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(), firstApi, buyer);
        } catch (Exception e) {
            log.error("微信合单完成订单失败, tradeNo={}", order.getTradeNo(), e);
            return ackFail(ctx, "complete_error");
        }
        return ackOk(ctx);
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private String getNotifyBody(InvokeContext ctx) {
        if (ctx.getRequest() != null && ctx.getRequest().getBody() != null)
            return new String(ctx.getRequest().getBody(), StandardCharsets.UTF_8);
        return "";
    }

    private Map<String, String> getHeaders(InvokeContext ctx) {
        if (ctx.getRequest() != null && ctx.getRequest().getHeaders() != null)
            return ctx.getRequest().getHeaders();
        return Map.of();
    }

    /** 扁平头 Map → 多值头 Map（parseNotify 的入参形态） */
    private static Map<String, List<String>> headerList(Map<String, String> headers) {
        var map = new java.util.LinkedHashMap<String, List<String>>();
        headers.forEach((k, v) -> map.put(k, List.of(v)));
        return map;
    }

}
