package com.okpay.plugin.alipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.alipay.util.AlipayConfig;
import org.slf4j.*;

import java.util.*;

/**
 * 支付宝查询处理器 — 订单/退款/打款/余额查询（零第三方 SDK，走 AlipayOpenApiClient）。
 */
public class AlipayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(AlipayQueryHandler.class);

    public AlipayQueryHandler() {
        on(BizType.T_PAY,  (ctx, req) -> queryOrder(ctx, req.getBizNo()));
        on(BizType.T_REF,  (ctx, req) -> queryRefund(ctx, req.getBizNo()));
        on(BizType.T_XFER, (ctx, req) -> queryTransfer(ctx, req.getBizNo()));
        on(BizType.T_BAL,  (ctx, req) -> queryBalance(ctx));
    }

    // =========================================================================
    // 订单查询
    // =========================================================================

    private BizResult queryOrder(InvokeContext ctx, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank())
            return Responses.fail("PARAM_ERROR", "订单号为空");

        try {
            // 合单查询：主单有子单 → 逐子单聚合（QueryScanner 兜底）
            var subs = Sdk.getSubOrders(ctx, tradeNo);
            if (!subs.isEmpty()) return queryCombine(ctx, tradeNo, subs);

            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_trade_no", tradeNo);

            var resp = cfg.client().execute(ctx, "alipay.trade.query", biz, cfg.extras(null));
            if (!resp.ok()) {
                // 交易不存在 = 订单未创建/未支付（官方语义建议再查确认），保持查单兜底，不作失败终态
                if ("ACQ.TRADE_NOT_EXIST".equals(resp.subCode()))
                    return Responses.ingMsg("交易不存在，等待买家付款", resp);
                return Responses.fail(resp.subCode(), resp.subMsg(), resp);
            }

            var status = resp.text("trade_status");
            if ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status)) {
                return Responses.ok(resp.text("trade_no"), resp.text("buyer_user_id"), resp);
            } else if ("WAIT_BUYER_PAY".equals(status)) {
                return Responses.result(BizState.S_ING)
                        .code(status).msg("等待买家付款")
                        .buyer(resp.text("buyer_user_id"))
                        .traced(resp).build();
            } else {
                return Responses.fail(status, "交易已关闭或取消", resp);
            }
        } catch (Exception e) {
            log.debug("支付宝订单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 退款查询
    // =========================================================================

    private BizResult queryRefund(InvokeContext ctx, String refundNo) {
        if (refundNo == null || refundNo.isBlank())
            return Responses.fail("PARAM_ERROR", "退款单号为空");

        try {
            // 合单退款查单续退：主单有子单 → 复用 refundCombine 逐单续退
            var refund = ctx.getRefund();
            var tradeNo = refund != null ? refund.getTradeNo() : "";
            if (tradeNo != null && !tradeNo.isBlank()) {
                var subs = Sdk.getSubOrders(ctx, tradeNo);
                if (!subs.isEmpty()) return Sdk.refundCombine(ctx, refund, subs, AlipaySubmitHandler::submitSubRefund);
            }

            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_request_no", refundNo);
            biz.put("out_trade_no", tradeNo);

            var resp = cfg.client().execute(ctx, "alipay.trade.fastpay.refund.query", biz, cfg.extras(null));
            if (!resp.ok())
                return Responses.fail(resp.subCode(), resp.subMsg(), resp);

            if ("REFUND_SUCCESS".equals(resp.text("refund_status"))) {
                return Responses.ok(resp.text("trade_no"), resp);
            }
            return Responses.ingMsg("退款处理中", resp);
        } catch (Exception e) {
            log.debug("支付宝退款查询暂时失败, refundNo={}, exception={}", refundNo, e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 合单查询（逐子单聚合）
    // =========================================================================

    /**
     * 合单查询聚合（QueryScanner 兜底）：逐子单 alipay.trade.query（N≤6）。
     * 全 TRADE_SUCCESS/FINISHED 且金额一致 → 逐子单已付 + S_OK（apiNo=首个子单渠道交易号）；
     * 任一 WAIT_BUYER_PAY → S_ING；任一 TRADE_CLOSED → S_FAIL；查询异常/业务处理中 → S_ING（状态抖动正常）。
     */
    private BizResult queryCombine(InvokeContext ctx, String tradeNo, List<SubOrderSnapshot> subs) {
        try {
            var cfg = AlipayConfig.from(ctx);
            String firstApi = null;
            String buyer = null;
            for (var sub : subs) {
                var biz = new LinkedHashMap<String, Object>();
                biz.put("out_trade_no", sub.getSubTradeNo());

                var resp = cfg.client().execute(ctx, "alipay.trade.query", biz, cfg.extras(null));
                if (!resp.ok())
                    return Responses.ing(resp.subCode(), resp.subMsg(), resp);
                var status = resp.text("trade_status");
                if ("WAIT_BUYER_PAY".equals(status)) {
                    return Responses.result(BizState.S_ING)
                            .code(status).msg("等待买家付款").traced(resp).build();
                }
                if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
                    return Responses.fail(status, "交易已关闭或取消", resp);
                }
                // 金额校验（fail-closed）：查询返回元字符串 → toCents 与落库子单金额一致
                if (sub.getMoney() != toCents(resp.text("total_amount")))
                    return Responses.fail("COMBINE_MISMATCH", "合单子单金额不符", resp);
                var api = resp.text("trade_no");
                if (firstApi == null) firstApi = api;
                if (buyer == null) buyer = resp.text("buyer_user_id");
                Sdk.updateSubOrder(ctx, UpdateSubOrderRequest.builder()
                        .tradeNo(tradeNo).subTradeNo(sub.getSubTradeNo())
                        .apiTradeNo(api).status(SubOrderSnapshot.STATUS_PAID).build());
            }
            return Responses.ok(firstApi, buyer, null);
        } catch (Exception e) {
            log.debug("支付宝合单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款查询
    // =========================================================================

    private BizResult queryTransfer(InvokeContext ctx, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank())
            return Responses.fail("PARAM_ERROR", "打款单号为空");

        try {
            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_biz_no", tradeNo);
            // 用 out_biz_no 查询时 product_code 区分转账类型：银行卡/账户（与提交时的 product_code 对应）
            var transfer = ctx.getTransfer();
            biz.put("product_code", transfer != null && "bank".equals(transfer.getType())
                    ? "TRANS_BANKCARD_NO_PWD" : "TRANS_ACCOUNT_NO_PWD");
            biz.put("biz_scene", "DIRECT_TRANSFER");

            var resp = cfg.clientForPayout().execute(ctx, "alipay.fund.trans.common.query", biz, cfg.extras(null));
            if (!resp.ok())
                return Responses.fail(resp.subCode(), resp.subMsg(), resp);

            // 官方 status 枚举：SUCCESS/FAIL/DEALING/REFUND（REFUND=退票，终态失败）
            if ("SUCCESS".equals(resp.text("status"))) {
                return Responses.ok(resp.text("order_id"), resp);
            } else if ("FAIL".equals(resp.text("status")) || "REFUND".equals(resp.text("status"))) {
                return Responses.fail(resp.text("status"), resp.text("error_code"), resp);
            }
            return Responses.ingMsg("打款处理中", resp);
        } catch (Exception e) {
            log.debug("支付宝打款查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 余额查询
    // =========================================================================

    private BizResult queryBalance(InvokeContext ctx) {
        try {
            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("account_type", "ACCTRANS_ACCOUNT");
            // alipay.fund.account.query 的账户标识是账户 PID；未配置时退回 appid 保持旧行为
            var accountId = cfg.getPid() != null && !cfg.getPid().isBlank() ? cfg.getPid() : cfg.getAppId();
            biz.put("alipay_user_id", accountId);

            var resp = cfg.clientForPayout().execute(ctx, "alipay.fund.account.query", biz, cfg.extras(null));
            var amount = resp.text("available_amount");
            if (resp.ok() && !amount.isEmpty()) {
                return Responses.resultBal(amount, resp);
            }
            return Responses.fail(resp.subCode(), resp.subMsg(), resp);
        } catch (Exception e) {
            log.error("支付宝余额查询失败", e);
            // 不返回伪造的 0.00 成功结果：按 S_FAIL 透出，payment 侧会抛业务错误提示操作人
            return Responses.fail("BAL_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

}
