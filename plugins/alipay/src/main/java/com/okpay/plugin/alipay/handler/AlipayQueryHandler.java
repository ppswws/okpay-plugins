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
 *
 * <p>查询失败语义：支付订单查单不使用 S_FAIL（未支付/不存在/关闭/撤销/渠道异常一律结果未定，
 * 交还定时轮询）；退款查单仅文档“仅 REFUND_SUCCESS 表示退款成功，否则表示退款没有执行成功”这一口径，
 * 文档未给出明确的“退款失败”状态，故非成功一律 S_WAIT（退款重试由操作人按查单结果决定）；
 * 打款查单仅文档明确的状态 FAIL（失败）/REFUND（退票）判 S_FAIL。渠道业务错误码不单独判失败。</p>
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
            throw new ExpectedFailureException("订单号为空");

        try {
            // 合单查询：主单有子单 → 逐子单聚合（QueryScanner 兜底）
            var subs = Sdk.getSubOrders(ctx, tradeNo);
            if (!subs.isEmpty()) return queryCombine(ctx, tradeNo, subs);

            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_trade_no", tradeNo);

            var resp = cfg.client().execute(ctx, "alipay.trade.query", biz, cfg.extras(null));
            // 支付订单查单不使用 S_FAIL：未支付/不存在/关闭/撤销/业务错误一律结果未定
            if (!resp.ok())
                return Responses.wait(resp.subCode(), resp.subMsg(), resp);

            var status = resp.text("trade_status");
            if ("TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status)) {
                return Responses.ok(resp.text("trade_no"), resp.text("buyer_user_id"), resp);
            }
            // WAIT_BUYER_PAY / TRADE_CLOSED（未付款超时关闭或已全额退款）/ 未知状态 → 等待
            return Responses.wait(status.isBlank() ? "QUERY_WAIT" : status,
                    "TRADE_CLOSED".equals(status) ? "交易已关闭" : "等待买家付款", resp);
        } catch (Exception e) {
            log.debug("支付宝订单查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 退款查询
    // =========================================================================

    private BizResult queryRefund(InvokeContext ctx, String refundNo) {
        if (refundNo == null || refundNo.isBlank())
            throw new ExpectedFailureException("退款单号为空");

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
            if (resp.ok() && "REFUND_SUCCESS".equals(resp.text("refund_status"))) {
                return Responses.ok(resp.text("trade_no"), resp);
            }
            // 非 REFUND_SUCCESS（含 ACQ.TRADE_NOT_EXIST 等业务错误）：文档未定义明确失败，结果未定
            return Responses.wait(msgCode(resp), waitMsg(resp, "退款处理中"), resp);
        } catch (Exception e) {
            log.debug("支付宝退款查询暂时失败, refundNo={}, exception={}", refundNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 合单查询（逐子单聚合）
    // =========================================================================

    /**
     * 合单查询聚合（QueryScanner 兜底）：逐子单 alipay.trade.query（N≤6）。
     * 全 TRADE_SUCCESS/FINISHED 且金额一致 → 逐子单已付 + S_OK（apiNo=首个子单渠道交易号）；
     * 其余（等待/关闭/撤销/业务错误/金额不符）一律 S_WAIT：支付订单查单不使用 S_FAIL。
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
                    return Responses.wait(resp.subCode(), resp.subMsg(), resp);
                var status = resp.text("trade_status");
                if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status))
                    return Responses.wait(status.isBlank() ? "QUERY_WAIT" : status,
                            "TRADE_CLOSED".equals(status) ? "交易已关闭" : "等待买家付款", resp);
                // 金额校验（fail-closed）：查询返回元字符串 → toCents 与落库子单金额一致
                if (sub.getMoney() != toCents(resp.text("total_amount")))
                    return Responses.wait("COMBINE_MISMATCH", "合单子单金额不符", resp);
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
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款查询
    // =========================================================================

    private BizResult queryTransfer(InvokeContext ctx, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank())
            throw new ExpectedFailureException("打款单号为空");

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
                return Responses.wait(resp.subCode(), resp.subMsg(), resp);

            // 官方 status 枚举：SUCCESS/FAIL/DEALING/REFUND（REFUND=退票，终态失败）
            var status = resp.text("status");
            return switch (status) {
                case "SUCCESS" -> Responses.ok(resp.text("order_id"), resp);
                case "FAIL", "REFUND" -> Responses.fail(status.isBlank() ? "XFER_FAIL" : status,
                        resp.text("error_msg"), resp);
                default -> Responses.wait("XFER_DEALING", "打款处理中", resp);
            };
        } catch (Exception e) {
            log.debug("支付宝打款查询暂时失败, tradeNo={}, exception={}", tradeNo, e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
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
            // alipay.fund.account.query 的账户标识是账户 PID；未配置时退回 appid
            var accountId = cfg.getPid() != null && !cfg.getPid().isBlank() ? cfg.getPid() : cfg.getAppId();
            biz.put("alipay_user_id", accountId);

            var resp = cfg.clientForPayout().execute(ctx, "alipay.fund.account.query", biz, cfg.extras(null));
            var amount = resp.text("available_amount");
            if (resp.ok() && !amount.isEmpty()) {
                return Responses.resultBal(amount, resp);
            }
            return Responses.wait(msgCode(resp), waitMsg(resp, "余额查询失败"), resp);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                log.error("支付宝余额查询失败", e);
            }
            // 不返回伪造的 0.00 成功结果：失败透出（S_WAIT），payment 侧会抛业务错误提示操作人
            return Responses.wait("BAL_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 响应错误码：sub_code 优先，其次 code；成功响应（code=10000）返回空串（不据以判失败） */
    private static String msgCode(AlipayOpenApiClient.ApiResponse resp) {
        return resp.ok() ? "" : resp.subCode();
    }

    /** 等待文案：业务错误取 sub_msg/msg，成功响应用给定默认文案 */
    private static String waitMsg(AlipayOpenApiClient.ApiResponse resp, String fallback) {
        if (resp.ok()) return fallback;
        for (var v : List.of(resp.subMsg(), resp.msg())) {
            if (v != null && !v.isBlank()) return v;
        }
        return fallback;
    }

}
