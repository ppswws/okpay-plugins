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
 */
public class HelipaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(HelipaySubmitHandler.class);

    public HelipaySubmitHandler() {
        on(BizType.T_PAY,  (ctx, req) -> Responses.ing());
        on(BizType.T_REF,  (ctx, req) -> refund(ctx));
        on(BizType.T_XFER, (ctx, req) -> transfer(ctx));
    }

    // =========================================================================
    // 退款
    // =========================================================================

    private BizResult refund(InvokeContext ctx) {
        var refund = ctx.getRefund();
        if (refund == null || refund.getRefundNo() == null) return fail("退款单号为空");
        var cfg = HelipayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayRefund");
        params.put("P2_orderId", refund.getTradeNo() != null ? refund.getTradeNo() : "");
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_refundOrderId", refund.getRefundNo());
        params.put("P5_amount", toYuan(refund.getAmount()));
        params.put("P6_callbackUrl", cfg.getNotifyDomain() + "/pay/refundnotify/" + refund.getRefundNo());
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            var code = m.getOrDefault("rt2_retCode", "");
            var msg = m.getOrDefault("rt3_retMsg", "");
            // 0000 退款成功受理；0001/0002 处理中（结果由通知/查询推进）；其余失败
            var state = switch (code) {
                case "0000" -> BizState.S_OK;
                case "0001", "0002" -> BizState.S_ING;
                default -> BizState.S_FAIL;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt7_serialNumber")).code(code)
                    .msg(msg.isBlank() ? code : msg)
                    .traced(resp).build();
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("合利宝退款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款
    // =========================================================================

    private BizResult transfer(InvokeContext ctx) {
        var transfer = ctx.getTransfer();
        if (transfer == null || transfer.getTradeNo() == null) return fail("打款单号为空");
        var cfg = HelipayConfig.from(ctx);
        // 银行编码来自打款表单下拉选择（通道支持的银行项目直传），不做名称映射
        if (transfer.getBankCode() == null || transfer.getBankCode().isBlank())
            return fail("缺少银行编码");
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "Transfer");
        params.put("P2_orderId", transfer.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_amount", toYuan(transfer.getAmount()));
        params.put("P5_bankCode", transfer.getBankCode());
        params.put("P6_bankAccountNo", transfer.getCardNo() != null ? transfer.getCardNo() : "");
        params.put("P7_bankAccountName", transfer.getCardName() != null ? transfer.getCardName() : "");
        // 对公/对私决定业务类型：B2B 对公（联行号必填）、B2C 对私（联行号可选携带）
        var isPublic = "public".equals(transfer.getAccountType());
        var cnapsNo = transfer.getCnapsNo() != null ? transfer.getCnapsNo() : "";
        if (isPublic && cnapsNo.isBlank())
            return fail("对公打款须填写联行号");
        params.put("P8_biz", isPublic ? "B2B" : "B2C");
        if (!cnapsNo.isBlank())
            params.put("P9_bankUnionCode", cnapsNo);
        params.put("P10_feeType", "PAYER");
        params.put("P11_urgency", "true");
        params.put("notifyUrl", cfg.getNotifyDomain() + "/pay/transfernotify/" + transfer.getTradeNo());
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            var code = m.getOrDefault("rt2_retCode", "");
            var msg = m.getOrDefault("rt3_retMsg", "");
            // 0000/0001 视为受理成功（打款结果由通知/查询推进）；其余失败
            var state = ("0000".equals(code) || "0001".equals(code)) ? BizState.S_ING : BizState.S_FAIL;
            return Responses.result(state)
                    .apiNo(m.get("rt6_serialNumber")).code(code)
                    .msg(msg.isBlank() ? code : msg)
                    .traced(resp).build();
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("合利宝打款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.ing("XFER_ERROR", e.getMessage());
        }
    }

    private static BizResult fail(String msg) { return Responses.fail("PARAM_ERROR", msg); }
}
