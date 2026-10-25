package com.okpay.plugin.helipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toYuan;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.helipay.util.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import org.slf4j.*;

import java.util.*;

/**
 * 合利宝 Submit 处理器（退款/打款）。
 *
 * <p>结果未知（渠道异常/响应不可解析/验签失败）→ S_WAIT，交还通知/查询推进，不误判失败。</p>
 */
public class HelipaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(HelipaySubmitHandler.class);

    public HelipaySubmitHandler() {
        on(BizType.T_REF,  (ctx, req) -> refund(ctx));
        on(BizType.T_XFER, (ctx, req) -> transfer(ctx));
    }

    // =========================================================================
    // 退款
    // =========================================================================

    private BizResult refund(InvokeContext ctx) {
        // 契约：T_REF submit 时内核恒填 refund（tradeNo/refundNo 恒非空），缺失即宿主缺陷——不再判空兜底
        var refund = ctx.getRefund();
        var cfg = HelipayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayRefund");
        params.put("P2_orderId", refund.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_refundOrderId", refund.getRefundNo());
        params.put("P5_amount", toYuan(refund.getAmount()));
        params.put("P6_callbackUrl", PaymentPaths.refundNotify(cfg.getNotifyDomain(), refund.getRefundNo()));
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            var code = m.getOrDefault("rt2_retCode", "");
            var msg = m.getOrDefault("rt3_retMsg", "");
            // 0000 退款成功受理；仅 0003 文档明确“失败”；其余（受理中/已存在/参数/未知）一律
            // S_WAIT，交通知/查询/人工，避免盲目判失败
            var state = switch (code) {
                case "0000" -> BizState.S_OK;
                case "0003" -> BizState.S_FAIL;
                default -> BizState.S_WAIT;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt7_serialNumber")).code(code)
                    .msg(msg.isBlank() ? code : msg)
                    .traced(resp).build();
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("合利宝退款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.wait("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款
    // =========================================================================

    private BizResult transfer(InvokeContext ctx) {
        // 契约：T_XFER submit 时内核恒填 transfer（tradeNo 恒非空），缺失即宿主缺陷——不再判空兜底
        var transfer = ctx.getTransfer();
        var cfg = HelipayConfig.from(ctx);
        // 银行编码来自打款表单下拉选择（通道支持的银行项目直传），不做名称映射
        if (transfer.getBankCode() == null || transfer.getBankCode().isBlank())
            return waitParam("缺少银行编码");
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "Transfer");
        params.put("P2_orderId", transfer.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_amount", toYuan(transfer.getAmount()));
        params.put("P5_bankCode", transfer.getBankCode());
        params.put("P6_bankAccountNo", transfer.getCardNo() != null ? transfer.getCardNo() : "");
        params.put("P7_bankAccountName", transfer.getCardName() != null ? transfer.getCardName() : "");
        // 对公/对私决定业务类型：B2B 对公（联行号必填）、B2C 对私（联行号可选携带）。
        // 未知账户类型是本地参数错误 → S_WAIT（不静默默认对私）
        final boolean isPublic;
        try {
            isPublic = com.okpay.plugin.sdk.PaymentUtils.isPublicAccount(transfer.getAccountType());
        } catch (ExpectedFailureException e) {
            return waitParam(e.getMessage());
        }
        var cnapsNo = transfer.getCnapsNo() != null ? transfer.getCnapsNo() : "";
        if (isPublic && cnapsNo.isBlank())
            return waitParam("对公打款须填写联行号");
        params.put("P8_biz", isPublic ? "B2B" : "B2C");
        if (!cnapsNo.isBlank())
            params.put("P9_bankUnionCode", cnapsNo);
        params.put("P10_feeType", "PAYER");
        params.put("P11_urgency", "true");
        params.put("notifyUrl", PaymentPaths.transferNotify(cfg.getNotifyDomain(), transfer.getTradeNo()));
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            var code = m.getOrDefault("rt2_retCode", "");
            var msg = m.getOrDefault("rt3_retMsg", "");
            // 0000/0001 受理中；0002-0006 为文档明确的受理失败/余额/状态/不存在/限额；
            // 其余未知码一律 S_WAIT，由通知/查询/人工收敛
            var state = switch (code) {
                case "0002", "0003", "0004", "0005", "0006" -> BizState.S_FAIL;
                default -> BizState.S_WAIT;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt6_serialNumber")).code(code)
                    .msg(msg.isBlank() ? code : msg)
                    .traced(resp).build();
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("合利宝打款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.wait("XFER_ERROR", e.getMessage());
        }
    }

    private static BizResult waitParam(String msg) { return Responses.wait("PARAM_ERROR", msg); }
}
