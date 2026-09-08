package com.okpay.plugin.helipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.helipay.util.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import org.slf4j.*;

import java.nio.charset.*;
import java.util.*;

/**
 * 合利宝异步通知处理器（支付/退款/打款）。
 *
 * <p>应答语义：验签/必要字段缺失/单号不匹配 → fail；金额不符 → amount_mismatch；
 * 支付/退款完成推进失败 → fail（交还渠道重试）；打款完成推进失败 → 仍 success（打款结果由查询轮询收敛）。
 * 实体缺失不发插件：宿主按单号路由时无单据直接 404（PluginPageService.dispatch），插件内不再判空。</p>
 */
public class HelipayNotifyHandler {

    private static final Logger log = LoggerFactory.getLogger(HelipayNotifyHandler.class);

    // =========================================================================
    // 支付通知
    // =========================================================================

    public PageResponse payNotify(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !requiredOrderNotify(params)
                || !HelipaySignUtil.verifyNotify(params, cfg.getAppkey()))
            return Responses.notifyFail(ctx, "fail");
        if (!"SUCCESS".equals(params.get("rt4_status")))
            return Responses.notifyFail(ctx, "fail");
        // 契约：notify 回调实体恒非空（PluginPageService.dispatch 无单据即 404）
        var order = ctx.getOrder();
        if (!order.getTradeNo().equals(params.get("rt2_orderId")))
            return Responses.notifyFail(ctx, "fail");
        if (order.getReal() != toCents(params.getOrDefault("rt5_orderAmount", "0")))
            return Responses.notifyFail(ctx, "amount_mismatch");
        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(),
                    params.get("rt3_systemSerial"), params.get("rt10_openId"));
        } catch (Exception e) { log.error("完成订单失败", e); return Responses.notifyFail(ctx, "fail"); }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    public PageResponse refundNotify(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !requiredRefundNotify(params)
                || !HelipaySignUtil.verifyNotify(params, cfg.getAppkey()))
            return Responses.notifyFail(ctx, "fail");
        var refund = ctx.getRefund();
        if (!refund.getRefundNo().equals(params.get("rt3_refundOrderId")))
            return Responses.notifyFail(ctx, "refund_mismatch");
        if (notBlank(params.get("rt6_amount")) && refund.getAmount() != toCents(params.get("rt6_amount")))
            return Responses.notifyFail(ctx, "amount_mismatch");
        var status = upper(params.get("rt5_status"));
        try {
            switch (status) {
                case "SUCCESS" -> Sdk.completeRefundOk(ctx, refund.getRefundNo(), params.get("rt4_systemSerial"));
                case "FAIL", "CLOSE" -> Sdk.completeRefundFail(ctx, refund.getRefundNo(), params.get("rt5_status"), null);
                default -> Sdk.completeRefundIng(ctx, refund.getRefundNo(), params.get("rt4_systemSerial"), params.get("rt5_status"), null);
            }
        } catch (Exception e) { log.error("完成退款失败", e); return Responses.notifyFail(ctx, "fail"); }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 打款通知
    // =========================================================================

    public PageResponse transferNotify(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !requiredTransferNotify(params)
                || !HelipaySignUtil.verifyNotify(params, cfg.getAppkey()))
            return Responses.notifyFail(ctx, "fail");
        var transfer = ctx.getTransfer();
        var status = upper(params.get("rt7_orderStatus"));
        try {
            switch (status) {
                case "SUCCESS" -> Sdk.completeTransferOk(ctx, transfer.getTradeNo(),
                        params.get("rt6_serialNumber"), params.get("rt9_reason"));
                case "FAIL", "REFUND" -> Sdk.completeTransferFail(ctx, transfer.getTradeNo(),
                        params.get("rt2_retCode"), params.get("rt9_reason"));
                case "RECEIVE", "INIT", "DOING" -> Sdk.completeTransferIng(ctx, transfer.getTradeNo(),
                        params.get("rt6_serialNumber"), params.get("rt2_retCode"), params.get("rt9_reason"));
                default -> { /* 未知状态不推进，交由查询轮询 */ }
            }
        } catch (Exception e) {
            // 打款完成推进失败仍应答 success：打款结果由查询轮询收敛，重试通知无意义
            log.error("完成打款失败", e);
        }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 支付通知必填字段（与验签同序） */
    private static boolean requiredOrderNotify(Map<String, String> p) {
        return notBlank(p.get("rt1_customerNumber")) && notBlank(p.get("rt2_orderId"))
                && notBlank(p.get("rt3_systemSerial")) && notBlank(p.get("rt4_status"))
                && notBlank(p.get("rt5_orderAmount")) && notBlank(p.get("rt6_currency"))
                && notBlank(p.get("rt7_timestamp"));
    }

    /** 退款通知必填字段（与验签同序） */
    private static boolean requiredRefundNotify(Map<String, String> p) {
        return notBlank(p.get("rt1_customerNumber")) && notBlank(p.get("rt2_orderId"))
                && notBlank(p.get("rt3_refundOrderId")) && notBlank(p.get("rt4_systemSerial"))
                && notBlank(p.get("rt5_status")) && notBlank(p.get("rt6_amount"))
                && notBlank(p.get("rt7_currency")) && notBlank(p.get("rt8_timestamp"));
    }

    /** 转账通知必填字段（与验签同序） */
    private static boolean requiredTransferNotify(Map<String, String> p) {
        return notBlank(p.get("rt1_bizType")) && notBlank(p.get("rt5_orderId"))
                && notBlank(p.get("rt6_serialNumber")) && notBlank(p.get("rt7_orderStatus"));
    }

    /** 通知体解析：JSON 优先，回退表单格式（parseJsonMap 内置回退）；body 为空时兜底 query */
    private Map<String, String> parseNotifyBody(InvokeContext ctx) {
        var req = ctx.getRequest();
        if (req == null) return Map.of();
        if (req.getBody() != null && req.getBody().length > 0)
            return PaymentUtils.parseJsonMap(new String(req.getBody(), StandardCharsets.UTF_8));
        return PaymentUtils.parseForm(req.getQuery());
    }

    private static String upper(String s) {
        return s != null ? s.toUpperCase(Locale.ROOT) : "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
