package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.wxpay.util.WxpayConfig;
import org.slf4j.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 微信支付 Submit 处理器 — 退款（APIv3，零第三方 SDK）。
 */
public class WxpaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(WxpaySubmitHandler.class);

    public WxpaySubmitHandler() {
        on(BizType.T_REF,  this::submitRefund);
    }

    // =========================================================================
    // 退款
    // =========================================================================

    private BizResult submitRefund(InvokeContext ctx, BizRequest req) {
        var refund = ctx.getRefund();
        if (refund == null || refund.getRefundNo() == null || refund.getRefundNo().isBlank())
            return Responses.fail("PARAM_ERROR", "退款单为空");

        var order = ctx.getOrder();
        var tradeNo = refund.getTradeNo() != null ? refund.getTradeNo() : "";
        if (order == null || order.getApiTradeNo() == null || order.getApiTradeNo().isBlank())
            return Responses.fail("PARAM_ERROR", "订单无上游交易号");

        try {
            // 合单退款：合单订单才查子单 → 逐子单提交（出参语义：全退 S_OK / 首败 S_FAIL / 部分 S_ING）；
            // is_combine=1 但子单缺失 = 数据不一致，拒退不静默降级单笔
            if (order.isCombine()) {
                var subs = Sdk.getSubOrders(ctx, tradeNo);
                if (subs.isEmpty()) return Responses.fail("REFUND_ERROR", "合单订单子单数据缺失");
                return Sdk.refundCombine(ctx, refund, subs, WxpaySubmitHandler::submitSubRefund);
            }

            var cfg = WxpayConfig.from(ctx);
            var body = new LinkedHashMap<String, Object>();
            if (cfg.isServiceProvider()) body.put("sub_mchid", cfg.getSubMchId());
            body.put("out_trade_no", tradeNo);
            body.put("out_refund_no", refund.getRefundNo());
            var amount = new LinkedHashMap<String, Object>();
            amount.put("refund", Math.toIntExact(refund.getAmount()));
            amount.put("total", Math.toIntExact(order.getReal()));
            amount.put("currency", "CNY");
            body.put("amount", amount);
            body.put("reason", refundReason(refund));
            body.put("notify_url", getNotifyDomain(ctx) + "/pay/refundnotify/" + refund.getRefundNo());

            var result = cfg.client().post("/v3/refund/domestic/refunds",
                    HttpHelper.MAPPER.valueToTree(body), ctx);
            var status = result.body().path("status").asString("");
            if ("SUCCESS".equals(status)) {
                return Responses.ok(result.body().path("transaction_id").asString(null), result);
            }
            // 受理即终态的失败（渠道侧不再变化）直接判失败退出查单池，与查单映射一致
            if ("CLOSED".equals(status) || "ABNORMAL".equals(status)) {
                return Responses.fail(status, "CLOSED".equals(status) ? "退款已关闭" : "退款异常，需人工处理", result);
            }
            return Responses.ing(status, "退款处理中", result);
        } catch (WechatPayException e) {
            log.debug("微信退款提交状态未知, refundNo={}, code={}", refund.getRefundNo(), e.code());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        } catch (Exception e) {
            log.debug("微信退款提交状态未知, refundNo={}, exception={}", refund.getRefundNo(), e.getClass().getSimpleName());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 合单退款（逐子单；submit 与 query(T_REF) 共用 Sdk.refundCombine 骨架）
    // =========================================================================

    /**
     * 单子单退款提交（Sdk.refundCombine 子提交器）：SUCCESS → S_OK（apiNo=渠道交易号）/
     * PROCESSING 受理中 → S_ING / 其余状态或微信确定性拒绝 → S_FAIL。out_refund_no=refundNo_序号 唯一（重跑幂等）。
     */
    static BizResult submitSubRefund(InvokeContext ctx, com.okpay.plugin.model.RefundSnapshot refund,
                                     SubOrderSnapshot sub, long quota, int idx) {
        try {
            var cfg = WxpayConfig.from(ctx);
            var body = new LinkedHashMap<String, Object>();
            if (cfg.isServiceProvider()) body.put("sub_mchid", cfg.getSubMchId());
            body.put("out_trade_no", sub.getSubTradeNo());
            body.put("out_refund_no", refund.getRefundNo() + "_" + idx);
            var amount = new LinkedHashMap<String, Object>();
            amount.put("refund", Math.toIntExact(quota));
            amount.put("total", Math.toIntExact(sub.getMoney()));
            amount.put("currency", "CNY");
            body.put("amount", amount);
            body.put("reason", refundReason(refund));
            body.put("notify_url", getNotifyDomain(ctx) + "/pay/refundnotify/" + refund.getRefundNo());
            var result = cfg.client().post("/v3/refund/domestic/refunds",
                    HttpHelper.MAPPER.valueToTree(body), ctx);
            var status = result.body().path("status").asString("");
            if ("SUCCESS".equals(status)) return Responses.ok(result.body().path("transaction_id").asString(null), result);
            if ("PROCESSING".equals(status)) return Responses.ing(status, "退款处理中", result);
            // 确定性拒绝（其余状态）→ 计入失败；首单失败且无任何成功 → S_FAIL
            log.debug("合单退款子单被拒, refundNo={}, sub={}, status={}",
                    refund.getRefundNo(), sub.getSubTradeNo(), status);
            return Responses.fail("REFUND_FAIL", status, result);
        } catch (WechatPayException e) {
            log.debug("合单退款子单拒绝, refundNo={}, sub={}, code={}",
                    refund.getRefundNo(), sub.getSubTradeNo(), e.code());
            return Responses.fail("REFUND_FAIL", e.getMessage());
        } catch (Exception e) {
            // 网络/未知：本轮未知，S_ING 靠查单续退
            log.debug("合单退款子单状态未知, refundNo={}, sub={}, exception={}",
                    refund.getRefundNo(), sub.getSubTradeNo(), e.getClass().getSimpleName());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 退款原因：官方 reason 上限 80 字节（超长被拒），备注为空兜底「退款」，按 UTF-8 字节截断 */
    static String refundReason(RefundSnapshot refund) {
        var remark = refund.getRemark() != null && !refund.getRemark().isBlank()
                ? refund.getRemark() : "退款";
        var bytes = remark.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 80) return remark;
        var trimmed = new String(bytes, 0, 80, StandardCharsets.UTF_8);
        // 末尾可能是被截半的多字节字符：UTF-8 解码会把残缺字节替换为 �，去掉它
        return trimmed.endsWith("�") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String getNotifyDomain(InvokeContext ctx) {
        if (ctx != null && ctx.getConfig() != null && ctx.getConfig().getNotifyDomain() != null)
            return ctx.getConfig().getNotifyDomain();
        return "";
    }
}
