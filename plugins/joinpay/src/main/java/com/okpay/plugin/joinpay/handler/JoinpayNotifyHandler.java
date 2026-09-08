package com.okpay.plugin.joinpay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toCents;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.joinpay.util.*;
import org.slf4j.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 汇聚支付异步通知处理器（支付/退款/打款）。
 *
 * <p>成功语义严格按官方文档：支付通知 r6_Status=100 才是支付成功；退款通知 ra_Status=100/101/其他；
 * 打款通知 status=205 成功、204/208/214 失败、其余处理中。验签字段表与文档签名顺序一致，
 * hmac 单独取值不参与拼接。</p>
 */
public class JoinpayNotifyHandler {

    private static final Logger log = LoggerFactory.getLogger(JoinpayNotifyHandler.class);

    /** uniPay 支付通知（GET，验签字段序与文档一致；hmac 单独取不进表） */
    static final List<String> ORDER_NOTIFY_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_Amount","r4_Cur","r5_Mp","r6_Status","r7_TrxNo",
            "r8_BankOrderNo","r9_BankTrxNo","ra_PayTime","rb_DealTime","rc_BankCode","rd_OpenId",
            "re_DiscountAmount","rh_cardType","rj_Fee","rk_FrpCode","rl_ContractId","rm_SpecialInfo","ro_SettleAmount");
    /** 退款通知（退款应答通知参数列表；hmac 单独取不进表） */
    static final List<String> REFUND_NOTIFY_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_RefundOrderNo","r4_RefundAmount","r5_RefundTrxNo",
            "ra_Status","rb_Code","rc_CodeMsg","re_FundsAccount");
    /** 单笔代付异步通知（hmac 单独取不进表） */
    static final List<String> TRANSFER_NOTIFY_FIELDS = List.of(
            "status","errorCode","errorCodeDesc","userNo","tradeMerchantNo","merchantOrderNo","platformSerialNo",
            "receiverAccountNoEnc","receiverNameEnc","paidAmount","fee");

    // =========================================================================
    // 支付通知
    // =========================================================================

    public PageResponse payNotify(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !JoinpaySignUtil.verify(params, ORDER_NOTIFY_FIELDS, cfg.getAppkey()))
            return Responses.notifyFail(ctx, params.isEmpty() ? "invalid_notify_params" : "sign_error");
        var status = params.getOrDefault("r6_Status", "");
        if (!"100".equals(status))
            return Responses.notifyFail(ctx, "status=" + status);
        // 契约：notify 回调实体恒非空（PluginPageService.dispatch 无单据即 404）
        var order = ctx.getOrder();
        if (!order.getTradeNo().equals(params.get("r2_OrderNo")))
            return Responses.notifyFail(ctx, "order_mismatch");
        if (order.getReal() != toCents(params.getOrDefault("r3_Amount", "0")))
            return Responses.notifyFail(ctx, "amount_mismatch");
        try {
            Sdk.completeOrderOk(ctx, order.getTradeNo(),
                    params.get("r7_TrxNo"), params.get("rd_OpenId"));
        } catch (Exception e) { log.error("完成订单失败", e); return Responses.notifyFail(ctx, "fail"); }
        return Responses.notifyOk(ctx, "success");
    }

    // =========================================================================
    // 退款通知
    // =========================================================================

    public PageResponse refundNotify(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !JoinpaySignUtil.verify(params, REFUND_NOTIFY_FIELDS, cfg.getAppkey()))
            return Responses.notifyFail(ctx, params.isEmpty() ? "invalid_notify_params" : "sign_error");
        var status = params.getOrDefault("ra_Status", "");
        var refund = ctx.getRefund();
        if (!refund.getRefundNo().equals(params.get("r3_RefundOrderNo")))
            return Responses.notifyFail(ctx, "refund_mismatch");
        if (refund.getAmount() != toCents(params.getOrDefault("r4_RefundAmount", "0")))
            return Responses.notifyFail(ctx, "amount_mismatch");
        try {
            switch (status) {
                case "100" -> Sdk.completeRefundOk(ctx, refund.getRefundNo(), params.get("r5_RefundTrxNo"), params.get("rc_CodeMsg"));
                case "101" -> Sdk.completeRefundFail(ctx, refund.getRefundNo(), status, params.get("rc_CodeMsg"));
                default -> Sdk.completeRefundIng(ctx, refund.getRefundNo(), params.get("r5_RefundTrxNo"), status, params.get("rc_CodeMsg"));
            }
        } catch (Exception e) { log.error("完成退款失败", e); return Responses.notifyFail(ctx, "fail"); }
        return "100".equals(status)
                   ? Responses.notifyOk(ctx, "success")
                   : Responses.notifyFail(ctx, "status=" + status);
    }

    // =========================================================================
    // 打款通知
    // =========================================================================

    public PageResponse transferNotify(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var params = parseNotifyBody(ctx);
        if (params.isEmpty() || !JoinpaySignUtil.verify(params, TRANSFER_NOTIFY_FIELDS, cfg.getAppkey()))
            return Responses.notifyFail(ctx, params.isEmpty() ? "invalid_notify_params" : "sign_error");
        var status = params.getOrDefault("status", "");
        var transfer = ctx.getTransfer();
        if (!transfer.getTradeNo().equals(params.get("merchantOrderNo")))
            return Responses.notifyFail(ctx, "transfer_mismatch");
        try {
            switch (status) {
                case "205" -> Sdk.completeTransferOk(ctx, transfer.getTradeNo(),
                        params.get("platformSerialNo"), params.get("errorCodeDesc"));
                case "204","208","214" -> Sdk.completeTransferFail(ctx, transfer.getTradeNo(),
                        params.get("errorCode"), params.get("errorCodeDesc"));
                default -> Sdk.completeTransferIng(ctx, transfer.getTradeNo(),
                        params.get("platformSerialNo"), params.get("errorCode"), params.get("errorCodeDesc"));
            }
        } catch (Exception e) { log.error("完成打款失败", e); return Responses.notifyFail(ctx, "fail"); }
        return "205".equals(status)
                   ? Responses.notifyOk(ctx, "success")
                   : Responses.notifyFail(ctx, "status=" + status);
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /**
     * 通知参数解析：JSON body 优先（数值保真，验签按原文拼接），失败/为空回退 query 表单
     * （支付通知为 GET 请求，参数在 query 上且需 URL 解码后验签）。
     */
    private Map<String, String> parseNotifyBody(InvokeContext ctx) {
        var req = ctx.getRequest();
        if (req == null) return Map.of();
        if (req.getBody() != null && req.getBody().length > 0) {
            var m = JoinpayUtil.parseJsonNum(new String(req.getBody(), StandardCharsets.UTF_8));
            if (!m.isEmpty()) return m;
            log.debug("通知体非 JSON, fallback 到 form 解析");
        }
        return PaymentUtils.parseForm(req.getQuery());
    }

}
