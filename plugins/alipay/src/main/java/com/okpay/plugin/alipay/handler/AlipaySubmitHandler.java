package com.okpay.plugin.alipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toYuan;

import com.okpay.plugin.sdk.PaymentUtils;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.alipay.util.AlipayConfig;
import org.slf4j.*;

import java.util.*;

/**
 * 支付宝 Submit 处理器 — 退款/打款（零第三方 SDK，走 AlipayOpenApiClient）。
 *
 * <p>失败语义：退款提交“受理成功（10000）≠ 资金变动成功”，fund_change=Y 才成功；业务拒绝/SYSTEM_ERROR/
 * 网络未知均为结果未定（S_WAIT），文档未给出明确的“退款失败”状态码，不据此判失败。
 * 打款提交业务拒绝（如收款账号/姓名不符）为渠道明确拒绝 → S_FAIL；SYSTEM_ERROR/网络未知 → S_WAIT。
 * 打款本地参数校验（账户类型缺失/未知、登录号收款缺姓名）为明确拒绝 → S_FAIL（不发起请求）。</p>
 */
public class AlipaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(AlipaySubmitHandler.class);

    public AlipaySubmitHandler() {
        on(BizType.T_REF,  this::submitRefund);
        on(BizType.T_XFER, this::submitTransfer);
    }

    // =========================================================================
    // 退款
    // =========================================================================

    private com.okpay.plugin.model.BizResult submitRefund(InvokeContext ctx, BizRequest req) {
        // 契约：T_REF submit 时内核恒填 refund+order（退款/主单快照），缺失即宿主缺陷——不再判空兜底
        var refund = ctx.getRefund();
        var order = ctx.getOrder();
        try {
            // 合单退款：合单订单才查子单 → 逐子单提交（出参语义：全退 S_OK / 无任何成功 S_FAIL / 部分 S_WAIT）；
            // is_combine=1 但子单缺失 = 数据不一致，拒退不静默降级单笔
            if (order.isCombine()) {
                var subs = Sdk.getSubOrders(ctx, order.getTradeNo());
                if (subs.isEmpty()) return Responses.wait("REFUND_ERROR", "合单订单子单数据缺失");
                return Sdk.refundCombine(ctx, refund, subs, AlipaySubmitHandler::submitSubRefund);
            }

            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_trade_no", order.getTradeNo());
            biz.put("out_request_no", refund.getRefundNo());
            biz.put("refund_amount", toYuan(refund.getAmount()));
            biz.put("refund_reason", refund.getRemark() != null ? refund.getRemark() : "退款");
            biz.put("notify_url", PaymentPaths.refundNotify(cfg.getNotifyDomain(), refund.getRefundNo()));

            var resp = cfg.client().execute(ctx, "alipay.trade.refund", biz, cfg.extras(null));
            if (resp.ok()) {
                // 官方口径：code=10000 仅代表受理成功，退款成功以 fund_change=Y 为准；
                // N/缺失（同 out_request_no 幂等重放等场景）需查询确认 → 保持查单兜底
                if ("Y".equals(resp.text("fund_change"))) {
                    return Responses.ok(resp.text("trade_no"), resp);
                }
                log.debug("支付宝退款受理但资金未变动, refundNo={}, fundChange={}",
                        refund.getRefundNo(), resp.text("fund_change"));
                return Responses.waitMsg("退款受理，待查询确认", resp);
            }
            // 业务拒绝/SYSTEM_ERROR/响应不可解析：文档无明确退款失败状态，结果未定交查单
            return Responses.wait(refundErrCode(resp), resp.subMsg(), resp);
        } catch (Exception e) {
            log.debug("支付宝退款提交状态未知, refundNo={}, exception={}", refund.getRefundNo(), e.getClass().getSimpleName());
            return Responses.wait("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 合单退款（逐子单；submit 与 query(T_REF) 共用 Sdk.refundCombine 骨架）
    // =========================================================================

    /** 40004+SYSTEM_ERROR = 系统错误结果未知 → 等待（交查单确认），不作失败终态 */
    static boolean isUnknownOutcome(AlipayOpenApiClient.ApiResponse resp) {
        return "40004".equals(resp.code()) && "SYSTEM_ERROR".equals(resp.subCode());
    }

    /** 退款等待结果的错误码：SYSTEM_ERROR → 原样透出；其余业务拒绝用 CHANNEL_REJECT（不据以判失败） */
    static String refundErrCode(AlipayOpenApiClient.ApiResponse resp) {
        if (isUnknownOutcome(resp)) return resp.code();
        return resp.subCode() == null || resp.subCode().isBlank() ? "CHANNEL_REJECT" : resp.subCode();
    }

    /**
     * 单子单退款提交（Sdk.refundCombine 子提交器）：成功 S_OK（apiNo=渠道交易号）/
     * 受理中、业务拒绝或网络未知 S_WAIT（文档无明确退款失败状态，不判失败）。
     * out_request_no=refundNo_序号 唯一（重跑幂等）。
     */
    static com.okpay.plugin.model.BizResult submitSubRefund(InvokeContext ctx,
            com.okpay.plugin.model.RefundSnapshot refund, SubOrderSnapshot sub, long quota, int idx) {
        try {
            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_trade_no", sub.getSubTradeNo());
            biz.put("out_request_no", refund.getRefundNo() + "_" + idx);
            biz.put("refund_amount", toYuan(quota));
            biz.put("refund_reason", refund.getRemark() != null ? refund.getRemark() : "退款");
            biz.put("notify_url", PaymentPaths.refundNotify(cfg.getNotifyDomain(), refund.getRefundNo()));
            var resp = cfg.client().execute(ctx, "alipay.trade.refund", biz, cfg.extras(null));
            if (resp.ok()) {
                // 同单笔退款：受理成功 ≠ 资金变动成功，fund_change=Y 才计成功（N/缺失交查单确认）
                if ("Y".equals(resp.text("fund_change"))) {
                    return Responses.ok(resp.text("trade_no"), resp);
                }
                log.debug("合单退款子单受理但资金未变动, refundNo={}, sub={}, fundChange={}",
                        refund.getRefundNo(), sub.getSubTradeNo(), resp.text("fund_change"));
                return Responses.waitMsg("退款受理，待查询确认", resp);
            }
            log.debug("合单退款子单被拒, refundNo={}, sub={}, subCode={}",
                    refund.getRefundNo(), sub.getSubTradeNo(), resp.subCode());
            return Responses.wait(refundErrCode(resp), resp.subMsg(), resp);
        } catch (Exception e) {
            log.debug("合单退款子单状态未知, refundNo={}, sub={}, exception={}",
                    refund.getRefundNo(), sub.getSubTradeNo(), e.getClass().getSimpleName());
            return Responses.wait("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款（单笔转账到支付宝账户）
    // =========================================================================

    private com.okpay.plugin.model.BizResult submitTransfer(InvokeContext ctx, BizRequest req) {
        // 契约：T_XFER submit 时内核恒填 transfer，缺失即宿主缺陷——不再判空兜底
        var transfer = ctx.getTransfer();
        // 银行卡打款的账户类型是本地参数契约（PaymentUtils 单点判定）：本地前置校验失败 → S_WAIT
        String bankAccountType = null;
        if ("bank".equals(transfer.getType())) {
            try {
                bankAccountType = PaymentUtils.isPublicAccount(transfer.getAccountType()) ? "1" : "2";
            } catch (ExpectedFailureException e) {
                return Responses.wait("PARAM_ERROR", e.getMessage());
            }
        }
        try {
            var cfg = AlipayConfig.from(ctx);
            var biz = new LinkedHashMap<String, Object>();
            biz.put("out_biz_no", transfer.getTradeNo());
            biz.put("trans_amount", toYuan(transfer.getAmount()));
            biz.put("biz_scene", "DIRECT_TRANSFER");
            biz.put("order_title", transfer.getRemark() != null ? transfer.getRemark() : "转账");
            if (transfer.getRemark() != null && !transfer.getRemark().isBlank())
                biz.put("remark", transfer.getRemark());

            var payee = new LinkedHashMap<String, Object>();
            if ("bank".equals(transfer.getType())) {
                biz.put("product_code", "TRANS_BANKCARD_NO_PWD");
                payee.put("identity_type", "BANKCARD_ACCOUNT");
                payee.put("identity", transfer.getCardNo());
                payee.put("bankcard_ext_info", Map.of("account_type", bankAccountType));
                // 银行卡打款必须校验收款人姓名（cardNo 与姓名不符时上游拒绝打款）
                if (isBlank(transfer.getCardName()))
                    return Responses.wait("PARAM_ERROR", "银行卡打款必须填写收款人姓名");
            } else {
                biz.put("product_code", "TRANS_ACCOUNT_NO_PWD");
                var identity = transfer.getCardNo();
                var identityType = identityTypeOf(identity);
                payee.put("identity_type", identityType);
                payee.put("identity", identity);
                biz.put("business_params", "{\"payer_show_name_use_alias\":\"true\"}");
                // 官方要求：identity_type=ALIPAY_LOGON_ID（手机号/邮箱）时 name 必填
                if ("ALIPAY_LOGON_ID".equals(identityType) && isBlank(transfer.getCardName()))
                    return Responses.wait("PARAM_ERROR", "按登录账号（手机号/邮箱）打款必须填写收款人姓名");
            }
            if (!isBlank(transfer.getCardName()))
                payee.put("name", transfer.getCardName());
            biz.put("payee_info", payee);

            // 大额转账场景报备（两分支都报备；未配置场景名则跳过）
            applyTransferSceneReport(biz, cfg);

            var resp = cfg.clientForPayout().execute(ctx, "alipay.fund.trans.uni.transfer", biz, cfg.extras(null));
            if (resp.ok()) {
                return Responses.ok(resp.text("order_id"), resp);
            }
            // SYSTEM_ERROR 结果未知 → 等待；其余业务码为渠道明确拒绝
            if (isUnknownOutcome(resp)) return Responses.wait(resp.code(), resp.subMsg(), resp);
            return Responses.fail(resp.subCode(), resp.subMsg(), resp);
        } catch (Exception e) {
            log.debug("支付宝打款提交状态未知, tradeNo={}, exception={}", transfer.getTradeNo(), e.getClass().getSimpleName());
            return Responses.wait("XFER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /**
     * 收款账号 identity_type 按值推断（转账到支付宝账户）：
     * 2088 开头纯数字 → ALIPAY_USER_ID；含 @ 或纯数字（手机号）→ ALIPAY_LOGON_ID；其余 → ALIPAY_OPEN_ID。
     */
    static String identityTypeOf(String account) {
        if (account == null) return "ALIPAY_OPEN_ID";
        var v = account.trim();
        if (v.matches("\\d+") && v.startsWith("2088")) return "ALIPAY_USER_ID";
        if (v.contains("@") || v.matches("\\d+")) return "ALIPAY_LOGON_ID";
        return "ALIPAY_OPEN_ID";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 大额转账场景报备：transfer_scene_name 非空时组装 transfer_scene_report_infos。
     * info_type/info_content 均按 | 拆分一一对应，content 缺省回退第一个。
     */
    static void applyTransferSceneReport(Map<String, Object> biz, AlipayConfig cfg) {
        var sceneName = cfg.getTransferAlipaySceneName();
        if (sceneName == null || sceneName.isBlank()) return;
        biz.put("transfer_scene_name", sceneName);
        var types = splitPipe(cfg.getTransferAlipayInfoType());
        var contents = splitPipe(cfg.getTransferAlipayInfoContent());
        var infos = new ArrayList<Map<String, Object>>(types.size());
        for (int i = 0; i < types.size(); i++) {
            infos.add(Map.of("info_type", types.get(i),
                    "info_content", i < contents.size() ? contents.get(i) : contents.get(0)));
        }
        biz.put("transfer_scene_report_infos", infos);
    }

    /** 按 | 拆分（保留尾部空串）；空/缺省 → 单个空元素 */
    private static List<String> splitPipe(String s) {
        if (s == null) return List.of("");
        return Arrays.asList(s.split("\\|", -1));
    }

}
