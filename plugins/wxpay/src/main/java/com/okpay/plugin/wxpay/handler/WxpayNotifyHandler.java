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
    private static NotifyResponse ackOk() {
        return Notify.ok("{\"code\":\"SUCCESS\"}");
    }

    /** 应答失败：HTTP 400 + FAIL JSON（微信据此按计划重试） */
    private static NotifyResponse ackFail(String reason) {
        return Notify.fail("{\"code\":\"FAIL\",\"message\":\"" + reason + "\"}");
    }

    /** 支付通知（APIv3） */
    public NotifyResponse payNotify(InvokeContext ctx) {
        // 契约：notify 回调按 tradeNo 前缀路由，实体缺失不发插件（PluginPageService.dispatch），order 恒非空
        var order = ctx.getOrder();
        var cfg = WxpayConfig.from(ctx);
        var body = getNotifyBody(ctx);

        try {
            var result = cfg.client().parseNotify(body, headerList(getHeaders(ctx)));

            // 合单通知：顶层 combine_out_trade_no 判定（fail-closed：子单数/状态/金额任一不符拒收等重发）
            if (result.path("combine_out_trade_no").asString(null) != null) {
                return handleCombineNotify(ctx, order, result);
            }

            // 校验 out_trade_no 与订单一致
            if (!order.getTradeNo().equals(result.path("out_trade_no").asString(null))) {
                return ackFail("order_mismatch");
            }

            var tradeState = result.path("trade_state").asString("");

            if (!"SUCCESS".equals(tradeState)) {
                // 支付订单无失败终态：非成功状态（NOTPAY/USERPAYING/CLOSED/REVOKED/PAYERROR…）
                // 只应答，不写状态/结果（结果由查单决定）
                log.debug("微信支付通知状态非成功, tradeNo={}, state={}", order.getTradeNo(), tradeState);
                return ackOk();
            }

            // 校验金额（fail-closed：无金额或金额不匹配一律拒绝）
            var total = result.path("amount").path("total").asInt(-1);
            if (total < 0 || order.getReal() != (long) total) {
                return ackFail("amount_mismatch");
            }

            // 微信官方通知，验签通过即可推进，无需二次查单。
            // 已配置系统公众号（统一 OAuth 首层已存 buyer 供风控）→ 渠道 openid 不覆盖身份；
            // 未配置 → 渠道 openid 即 buyer
            var buyer = ctx.getConfig().isOauthWxConfigured()
                    ? null : result.path("payer").path("openid").asString(null);
            try {
                Sdk.completeOrderOk(ctx, order.getTradeNo(),
                        result.path("transaction_id").asString(null), buyer);
            } catch (Exception e) {
                return ackFail("complete_error");
            }

            return ackOk();

        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                log.error("微信支付通知处理失败, tradeNo={}", order.getTradeNo(), e);
            }
            return ackFail("notify_error");
        }
    }

    /** 退款通知（APIv3） */
    public NotifyResponse refundNotify(InvokeContext ctx) {
        // 契约：notify 回调实体恒非空（同 payNotify）；refund.tradeNo 恒非空（RefundSnapshot 契约）
        var refund = ctx.getRefund();
        var cfg = WxpayConfig.from(ctx);
        var body = getNotifyBody(ctx);

        try {
            var result = cfg.client().parseNotify(body, headerList(getHeaders(ctx)));
            var refundStatus = result.path("refund_status").asString("");
            var tradeNo = refund.getTradeNo();
            List<SubOrderSnapshot> subs;
            try {
                subs = Sdk.getSubOrders(ctx, tradeNo);
            } catch (Exception e) {
                // 宿主回调失败已由宿主记录，回 FAIL 让通道重试。
                return ackFail("notify_error");
            }
            if (!"SUCCESS".equals(refundStatus)) {
                // 合单退款逐子单提交，任一子单的 PROCESSING 通知也会推送到主单回调：
                // 不能据单条子单通知判主单 FAIL（否则后续成功通知无法恢复终态），由查单续退兜底；
                // 单笔退款按渠道退款状态推进（CLOSED/ABNORMAL 终态失败，PROCESSING/未知等结果）
                if (subs.isEmpty()) {
                    try {
                        completeRefundByStatus(ctx, refund.getRefundNo(), refundStatus,
                                result.path("transaction_id").asString(null));
                    } catch (Exception e) {
                        return ackFail("notify_error");
                    }
                } else {
                    log.debug("合单退款子单通知非成功, refundNo={}, refundStatus={}, 由查单续退兜底",
                            refund.getRefundNo(), refundStatus);
                }
                return ackOk();
            }
            if (subs.isEmpty()) {
                // 单笔退款通知
                try {
                    Sdk.completeRefundOk(ctx, refund.getRefundNo(),
                            result.path("transaction_id").asString(null));
                } catch (Exception e) {
                    return ackFail("notify_error");
                }
                return ackOk();
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
                        // 重复累计被拒（已到额）是正常重复通知结果，不落日志。
                    } catch (Exception e) {
                        return ackFail("notify_error");
                    }
                }
            }
            // 全部子单退完才推进主退款单终态（部分退完保持 PENDING，靠查单续退）
            try {
                var after = Sdk.getSubOrders(ctx, tradeNo);
                if (after.stream().allMatch(s -> s.getRefundMoney() >= s.getMoney())) {
                    Sdk.completeRefundOk(ctx, refund.getRefundNo(),
                            result.path("transaction_id").asString(null));
                }
            } catch (Exception e) {
                // 宿主回调失败已由宿主记录，回 FAIL 让通道重试。
                return ackFail("notify_error");
            }
        } catch (Exception e) {
            // 仅解析/校验阶段的代码缺陷写 ERROR；渠道数据问题由回调记录承载。
            if (!PluginLog.isExpected(e)) {
                log.error("微信退款通知处理失败, refundNo={}", refund.getRefundNo(), e);
            }
            return ackFail("notify_error");
        }

        return ackOk();
    }

    /**
     * 按渠道退款状态推进退款单（单笔退款通知复用）：SUCCESS → 成功；CLOSED（退款关闭）→ 失败；
     * ABNORMAL（退款异常，官方定义为非终态，可走异常退款）/ PROCESSING／未知 → 结果未定（S_WAIT），
     * 由查单/人工处理兜底。
     */
    static void completeRefundByStatus(InvokeContext ctx, String refundNo, String status, String apiNo) {
        switch (status) {
            case "SUCCESS" -> Sdk.completeRefundOk(ctx, refundNo, apiNo);
            case "CLOSED" -> Sdk.completeRefundFail(ctx, refundNo, status, "退款已关闭");
            case "ABNORMAL" -> Sdk.completeRefundWait(ctx, refundNo, apiNo, status, "退款异常，需人工处理");
            default -> Sdk.completeRefundWait(ctx, refundNo, apiNo, status, "退款处理中");
        }
    }

    // =========================================================================
    // 合单通知（fail-closed：子单数/状态/金额任一不符拒收等重发）
    // =========================================================================

    /** 合单支付通知：子单数一致 + 全部 SUCCESS + 逐单金额一致 + 合计==实付，全部通过才推进 */
    private NotifyResponse handleCombineNotify(InvokeContext ctx, OrderSnapshot order, JsonNode result) {
        if (!order.getTradeNo().equals(result.path("combine_out_trade_no").asString(null))) {
            return ackFail("order_mismatch");
        }
        var subs = Sdk.getSubOrders(ctx, order.getTradeNo());
        var subArray = result.path("sub_orders");
        if (subs.isEmpty() || !subArray.isArray() || subArray.size() != subs.size()) {
            log.debug("合单通知子单数量不符, tradeNo={}, notify={}, db={}",
                    order.getTradeNo(), subArray.isArray() ? subArray.size() : -1, subs.size());
            return ackFail("amount_mismatch");
        }
        long total = 0;
        String firstApi = null;
        // 按子单号建 map 配对（通知里 sub_orders 顺序无契约保证，禁用下标对位）
        var bySubNo = new LinkedHashMap<String, JsonNode>();
        for (var item : subArray) {
            var subNo = item.path("out_trade_no").asString(null);
            if (subNo == null || subNo.isBlank() || bySubNo.putIfAbsent(subNo, item) != null) {
                return ackFail("amount_mismatch");
            }
        }
        for (var sub : subs) {
            var item = bySubNo.get(sub.getSubTradeNo());
            if (item == null) {
                log.debug("合单通知缺少子单, tradeNo={}, sub={}", order.getTradeNo(), sub.getSubTradeNo());
                return ackFail("amount_mismatch");
            }
            if (!"SUCCESS".equals(item.path("trade_state").asString(""))) {
                log.debug("合单通知子单未成功, tradeNo={}, sub={}, state={}",
                        order.getTradeNo(), sub.getSubTradeNo(),
                        item.path("trade_state").asString(null));
                return ackFail("amount_mismatch");
            }
            var amount = item.path("amount").path("total_amount").asLong(-1);
            if (amount < 0 || amount != sub.getMoney()) {
                log.debug("合单通知子单金额不符, tradeNo={}, sub={}, notify={}, db={}",
                        order.getTradeNo(), sub.getSubTradeNo(), amount, sub.getMoney());
                return ackFail("amount_mismatch");
            }
            total += amount;
        }
        if (total != order.getReal()) {
            log.debug("合单通知合计金额不符, tradeNo={}, notify={}, real={}", order.getTradeNo(), total, order.getReal());
            return ackFail("amount_mismatch");
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
            return ackFail("complete_error");
        }
        return ackOk();
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
