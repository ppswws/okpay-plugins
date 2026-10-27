package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.wxpay.util.WxpayConfig;
import tools.jackson.databind.JsonNode;
import org.slf4j.*;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 微信支付查询处理器（APIv3，零第三方 SDK）。
 */
public class WxpayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(WxpayQueryHandler.class);

    public WxpayQueryHandler() {
        on(BizType.T_PAY,  (ctx, req) -> queryOrder(ctx, req.getBizNo()));
        on(BizType.T_REF,  (ctx, req) -> queryRefund(ctx, req.getBizNo()));
    }

    // =========================================================================
    // 订单查询
    // =========================================================================

    /** 支付订单查单不使用 S_FAIL：未支付/不存在/关闭/渠道异常一律结果未定（S_WAIT）。 */
    private BizResult queryOrder(InvokeContext ctx, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank())
            return Responses.wait("PARAM_ERROR", "订单号为空");

        try {
            // 合单查单兜底：主单有子单 → 走合单查询聚合（QueryScanner 依赖此路径）
            var subs = Sdk.getSubOrders(ctx, tradeNo);
            if (!subs.isEmpty()) return queryCombine(ctx, tradeNo, subs);

            var cfg = WxpayConfig.from(ctx);
            var result = queryTransaction(ctx, cfg, tradeNo);
            var node = result.body();

            var state = node.path("trade_state").asString("");
            return switch (state) {
                case "SUCCESS",
                     // REFUND=交易成功后发生退款：交易本身已成功付入，退款归属退款单跟踪，不得判订单失败
                     "REFUND" -> Responses.ok(
                        node.path("transaction_id").asString(null),
                        node.path("payer").path("openid").asString(null), result);
                // CLOSED/REVOKED/PAYERROR/未知状态（含订单不存在）同样不判失败，只保持待定
                default -> Responses.result(BizState.S_WAIT)
                        .code(state).msg(node.path("trade_state_desc").asString(null))
                        .buyer(node.path("payer").path("openid").asString(null))
                        .traced(result).build();
            };
        } catch (WechatPayException e) {
            log.debug("微信订单查询暂时失败, tradeNo={}, code={}", tradeNo, e.code());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        } catch (Exception e) {
            log.debug("微信订单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 合单查询（GET /v3/combine-transactions/out-trade-no/{tradeNo}）
    // =========================================================================

    /**
     * 合单查单聚合：全 SUCCESS + 金额一致 → 逐子单已付 + 主单完成；其余（含未付/处理中/关闭/
     * 子单数或金额不符）一律结果未定（支付订单不使用 S_FAIL）。
     */
    private BizResult queryCombine(InvokeContext ctx, String tradeNo, List<SubOrderSnapshot> subs) {
        try {
            var cfg = WxpayConfig.from(ctx);
            // 官方契约仅有 Path 参数 combine_out_trade_no，无任何 Query 参数（多传有被 PARAM_ERROR 拒绝的风险）
            var result = cfg.client().get(
                    "/v3/combine-transactions/out-trade-no/" + PaymentUtils.urlEncode(tradeNo), ctx);
            var node = result.body();
            var subArray = node.path("sub_orders");
            if (!subArray.isArray() || subArray.size() != subs.size())
                return Responses.wait("COMBINE_MISMATCH", "合单子单数量不符", result);
            // 按子单号建 map 配对（应答顺序无契约保证，禁用下标对位）
            var bySubNo = new LinkedHashMap<String, JsonNode>();
            for (var item : subArray) {
                var subNo = item.path("out_trade_no").asString(null);
                if (subNo == null || subNo.isBlank() || bySubNo.putIfAbsent(subNo, item) != null)
                    return Responses.wait("COMBINE_MISMATCH", "合单子单号缺失或重复", result);
            }
            for (var sub : subs) {
                var item = bySubNo.get(sub.getSubTradeNo());
                if (item == null)
                    return Responses.wait("COMBINE_MISMATCH", "合单应答缺少子单 " + sub.getSubTradeNo(), result);
                var state = item.path("trade_state").asString("");
                if (!"SUCCESS".equals(state)) {
                    return switch (state) {
                        case "NOTPAY", "USERPAYING" -> Responses.wait(state, "合单未支付完成", result);
                        case "CLOSED", "REVOKED" -> Responses.wait(state, "合单已关闭", result);
                        default -> Responses.wait(state, "合单状态未知", result);
                    };
                }
                var amount = item.path("amount").path("total_amount").asLong(-1);
                if (amount < 0 || amount != sub.getMoney())
                    return Responses.wait("COMBINE_MISMATCH", "合单子单金额不符", result);
            }
            // 全部 SUCCESS + 金额一致：逐子单已付标记（幂等）+ 主单完成
            String firstApi = null;
            for (var sub : subs) {
                var item = bySubNo.get(sub.getSubTradeNo());
                Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                        .tradeNo(tradeNo).subTradeNo(sub.getSubTradeNo())
                        .apiTradeNo(item.path("transaction_id").asString(null))
                        .status(SubOrderSnapshot.STATUS_PAID).build());
                if (firstApi == null) firstApi = item.path("transaction_id").asString(null);
            }
            return Responses.ok(firstApi,
                    node.path("combine_payer_info").path("openid").asString(null), result);
        } catch (WechatPayException e) {
            log.debug("微信合单查询暂时失败, tradeNo={}, code={}", tradeNo, e.code());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        } catch (Exception e) {
            log.debug("微信合单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 退款查询
    // =========================================================================

    /** 退款查单：SUCCESS → S_OK；CLOSED → S_FAIL；ABNORMAL/PROCESSING/未知/渠道异常 → S_WAIT。 */
    private BizResult queryRefund(InvokeContext ctx, String refundNo) {
        if (refundNo == null || refundNo.isBlank())
            return Responses.wait("PARAM_ERROR", "退款单号为空");

        try {
            // 合单退款续退：主退款单无渠道单可查（逐单 out_refund_no 带序号），重跑逐单推进（幂等）
            var refund = ctx.getRefund();
            if (refund != null && refund.getTradeNo() != null && !refund.getTradeNo().isBlank()) {
                var subs = Sdk.getSubOrders(ctx, refund.getTradeNo());
                if (!subs.isEmpty()) return Sdk.refundCombine(ctx, refund, subs, WxpaySubmitHandler::submitSubRefund);
            }

            var cfg = WxpayConfig.from(ctx);
            var query = cfg.isServiceProvider()
                    ? "?sub_mchid=" + PaymentUtils.urlEncode(cfg.getSubMchId())
                    : "";
            var result = cfg.client().get("/v3/refund/domestic/refunds/"
                    + PaymentUtils.urlEncode(refundNo) + query, ctx);

            var status = result.body().path("status").asString("");
            if ("SUCCESS".equals(status)) {
                return Responses.ok(result.body().path("refund_id").asString(null), result);
            }
            // CLOSED=退款关闭（官方定义即退款失败）；ABNORMAL 官方定义为非终态，可走异常退款，
            // 必须保持 S_WAIT 等人工处理，不能提前回滚/判死
            if ("CLOSED".equals(status))
                return Responses.fail(status, "退款已关闭", result);
            if ("ABNORMAL".equals(status))
                return Responses.wait(status, "退款异常，需人工处理", result);
            return Responses.wait(status, "退款处理中", result);
        } catch (WechatPayException e) {
            log.debug("微信退款查询暂时失败, refundNo={}, code={}", refundNo, e.code());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        } catch (Exception e) {
            log.debug("微信退款查询暂时失败, refundNo={}, exception={}", refundNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 按商户订单号查单（服务商模式走 partner 端点） */
    private WechatPayV3Client.WxResponse queryTransaction(
            InvokeContext ctx, WxpayConfig cfg, String tradeNo) throws Exception {
        var enc = PaymentUtils.urlEncode(tradeNo);
        if (cfg.isServiceProvider()) {
            return cfg.client().get("/v3/pay/partner/transactions/out-trade-no/" + enc
                    + "?sp_mchid=" + PaymentUtils.urlEncode(cfg.getMchId())
                    + "&sub_mchid=" + PaymentUtils.urlEncode(cfg.getSubMchId()), ctx);
        }
        return cfg.client().get("/v3/pay/transactions/out-trade-no/" + enc
                + "?mchid=" + PaymentUtils.urlEncode(cfg.getMchId()), ctx);
    }
}
