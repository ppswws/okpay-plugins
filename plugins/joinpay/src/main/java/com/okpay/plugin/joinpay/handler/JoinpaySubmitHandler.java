package com.okpay.plugin.joinpay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.channelFailure;
import static com.okpay.plugin.sdk.PaymentUtils.toYuan;

import com.okpay.plugin.model.*;
import com.okpay.plugin.enums.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.joinpay.util.*;
import org.slf4j.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 汇聚支付 Submit 处理器（退款/打款）。
 *
 * <p>退款走 tradeRt/refund（应答 ra_Status=100 成功/101 失败/其余处理中，响应验签）；
 * 打款走 payment/pay/singlePay（statusCode=2002 受理失败，2001/2003 及其余未知均为处理中，
 * 由通知/查询收敛，响应 data 验签）。</p>
 */
public class JoinpaySubmitHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(JoinpaySubmitHandler.class);

    private static final String REFUND_URL = "https://trade.joinpay.com/tradeRt/refund";
    private static final String TRANSFER_URL = "https://www.joinpay.com/payment/pay/singlePay";

    /** 退款请求签名字段（hmac 不进表，签名时空值跳过） */
    private static final List<String> REFUND_REQ_FIELDS = List.of(
            "p0_Version","p1_MerchantNo","p2_OrderNo","p3_RefundOrderNo","p4_RefundAmount",
            "p5_RefundReason","p6_NotifyUrl","pa_FundsAccount");
    /** 退款应答验签字段（hmac 单独取，不参与拼接） */
    private static final List<String> REFUND_RESP_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_RefundOrderNo","r4_RefundAmount",
            "r5_RefundTrxNo","ra_Status","rb_Code","rc_CodeMsg","re_FundsAccount");
    /** 打款请求签名字段（文档签名顺序：tradeMerchantNo 居第 2 位；hmac 不进表） */
    private static final List<String> TRANSFER_REQ_FIELDS = List.of(
            "userNo","tradeMerchantNo","productCode","requestTime","merchantOrderNo",
            "receiverAccountNoEnc","receiverNameEnc","receiverAccountType","receiverBankChannelNo",
            "paidAmount","currency","isChecked","paidDesc","paidUse","callbackUrl","firstProductCode");
    /** 打款应答 data 验签字段（hmac 单独取，不参与拼接） */
    private static final List<String> TRANSFER_RESP_FIELDS = List.of(
            "errorCode","errorDesc","userNo","merchantOrderNo");

    public JoinpaySubmitHandler() {
        on(BizType.T_REF,  (ctx, req) -> refund(ctx));
        on(BizType.T_XFER, (ctx, req) -> transfer(ctx));
    }

    // =========================================================================
    // 退款
    // =========================================================================

    private BizResult refund(InvokeContext ctx) {
        // 契约：T_REF submit 时内核恒填 refund+order（退款/主单快照），缺失即宿主缺陷——不再判空兜底
        var order = ctx.getOrder();
        var refund = ctx.getRefund();
        var cfg = JoinpayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("p0_Version", "2.3");
        params.put("p1_MerchantNo", cfg.getAppid());
        params.put("p2_OrderNo", order.getTradeNo());
        params.put("p3_RefundOrderNo", refund.getRefundNo());
        params.put("p4_RefundAmount", toYuan(refund.getAmount()));
        params.put("p5_RefundReason", "申请退款");
        params.put("p6_NotifyUrl", cfg.getNotifyDomain() + "/pay/refundnotify/" + refund.getRefundNo());
        params.put("hmac", JoinpaySignUtil.sign(params, REFUND_REQ_FIELDS, cfg.getAppkey()));
        try {
            var reqBody = PaymentUtils.encodeForm(params);
            var resp = HttpHelper.post(ctx, REFUND_URL, reqBody, "application/x-www-form-urlencoded");
            // 渠道 HTTP 层失败：状态未知，归因渠道文案，保持处理中交还轮询
            if (!resp.isSuccess())
                throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
            var m = JoinpayUtil.parseJsonNum(resp.bodyAsString());
            if (m.isEmpty())
                throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
            if (!JoinpaySignUtil.verify(m, REFUND_RESP_FIELDS, cfg.getAppkey()))
                return Responses.fail("SIGN_ERROR", "返回验签失败", resp);
            var status = m.getOrDefault("ra_Status", "");
            var state = switch (status) { case "100" -> BizState.S_OK; case "101" -> BizState.S_FAIL; default -> BizState.S_ING; };
            return Responses.result(state)
                    .apiNo(m.get("r5_RefundTrxNo")).code(m.get("rb_Code")).msg(m.get("rc_CodeMsg"))
                    .traced(resp).build();
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("汇聚支付退款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.ing("REFUND_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款
    // =========================================================================

    private BizResult transfer(InvokeContext ctx) {
        // 契约：T_XFER submit 时内核恒填 transfer（tradeNo 恒非空），缺失即宿主缺陷——不再判空兜底
        var transfer = ctx.getTransfer();
        var cfg = JoinpayConfig.from(ctx);
        // 平台语义 private 对私 / public 对公 → JOINPAY 代付 receiverAccountType：201 对私 / 204 对公
        var accountType = "public".equals(transfer.getAccountType()) ? "204" : "201";
        // 对公代付必须提供联行号（receiverBankChannelNo）
        if ("204".equals(accountType) && (transfer.getCnapsNo() == null || transfer.getCnapsNo().isBlank()))
            return fail("对公打款必须提供联行号");
        var params = new LinkedHashMap<String, String>();
        params.put("userNo", cfg.getAppid());
        if (cfg.getAppmchid() != null && !cfg.getAppmchid().isBlank()) params.put("tradeMerchantNo", cfg.getAppmchid());
        params.put("productCode", "BANK_PAY_DAILY_ORDER");
        params.put("requestTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("merchantOrderNo", transfer.getTradeNo());
        params.put("receiverAccountNoEnc", transfer.getCardNo() != null ? transfer.getCardNo() : "");
        params.put("receiverNameEnc", transfer.getCardName() != null ? transfer.getCardName() : "");
        params.put("receiverAccountType", accountType);
        params.put("receiverBankChannelNo", transfer.getCnapsNo() != null ? transfer.getCnapsNo() : "");
        params.put("paidAmount", toYuan(transfer.getAmount()));
        params.put("currency", "201");
        params.put("isChecked", "202");
        params.put("paidDesc", "工资发放");
        params.put("paidUse", "201");
        params.put("callbackUrl", cfg.getNotifyDomain() + "/pay/transfernotify/" + transfer.getTradeNo());
        params.put("hmac", JoinpaySignUtil.sign(params, TRANSFER_REQ_FIELDS, cfg.getAppkey()));
        try {
            var resp = HttpHelper.post(ctx, TRANSFER_URL,
                    HttpHelper.MAPPER.writeValueAsString(params), "application/json");
            if (!resp.isSuccess())
                throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
            var m = JoinpayUtil.parseJsonNumRaw(resp.bodyAsString());
            var code = String.valueOf(m.getOrDefault("statusCode", ""));
            var msg = String.valueOf(m.getOrDefault("message", ""));
            // 应答 data 必验签（受理结果不可信未验签报文）
            var dataRaw = flatten(m.get("data"));
            if (dataRaw.isEmpty() || !JoinpaySignUtil.verify(dataRaw, TRANSFER_RESP_FIELDS, cfg.getAppkey()))
                return Responses.fail("SIGN_ERROR", "返回验签失败", resp);
            // 2002 受理失败；2001 受理成功 / 2003 未知 → 均非终态，由通知/查询收敛
            if ("2002".equals(code) || (!"2001".equals(code) && !"2003".equals(code)))
                return Responses.fail(code.isBlank() ? "XFER_ERROR" : code, msg, resp);
            return Responses.ing(code, msg.isBlank() ? code : msg, resp);
        } catch (Exception e) {
            // 状态未知（渠道异常/响应异常）：保持处理中交还轮询，不误判失败
            log.debug("汇聚支付打款提交状态未知, exception={}", e.getClass().getSimpleName());
            return Responses.ing("XFER_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 嵌套 data 对象 → 扁平字符串 Map（数值保真由解析侧保证） */
    private static Map<String, String> flatten(Object data) {
        var out = new LinkedHashMap<String, String>();
        if (data instanceof Map<?, ?> m)
            m.forEach((k, v) -> out.put(String.valueOf(k), v != null ? String.valueOf(v) : ""));
        return out;
    }

    private static BizResult fail(String msg) { return Responses.fail("PARAM_ERROR", msg); }
}
