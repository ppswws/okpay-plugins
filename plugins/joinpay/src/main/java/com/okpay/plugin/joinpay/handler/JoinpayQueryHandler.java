package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.model.*;
import com.okpay.plugin.enums.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.joinpay.util.*;
import org.slf4j.*;

import java.util.*;

/**
 * 汇聚支付查询处理器（订单/退款/打款/余额）。
 *
 * <p>查询失败语义：渠道 HTTP 失败、响应解析失败、返回验签失败一律保持处理中（S_ING）
 * 交还定时轮询，不据以推进任何终态。状态判定严格按文档：订单/退款 ra_Status=100 成功、
 * 101 失败、其余处理中；打款 status=205 成功、204/208/214 失败、其余处理中。</p>
 */
public class JoinpayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(JoinpayQueryHandler.class);

    private static final String ORDER_QUERY_URL = "https://trade.joinpay.com/tradeRt/queryOrder";
    private static final String REFUND_QUERY_URL = "https://trade.joinpay.com/tradeRt/queryRefund";
    private static final String TRANSFER_QUERY_URL = "https://www.joinpay.com/payment/pay/singlePayQuery";

    private static final List<String> ORDER_QUERY_REQ_FIELDS = List.of("p0_Version", "p1_MerchantNo", "p2_OrderNo");
    /** 订单查询应答验签字段（hmac 单独取，不参与拼接） */
    private static final List<String> ORDER_QUERY_RESP_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_Amount","r4_ProductName","r5_TrxNo",
            "r6_BankTrxNo","r7_Fee","r8_FrpCode","ra_Status","rb_Code","rc_CodeMsg",
            "rd_OpenId","re_DiscountAmount","rf_PayTime","rh_cardType","rj_BankCode",
            "rl_ContractId","rm_SpecialInfo","ro_SettleAmount");

    private static final List<String> REFUND_QUERY_REQ_FIELDS = List.of("p0_Version", "p1_MerchantNo", "p2_RefundOrderNo");
    /** 退款查询应答验签字段（rd_MarketRefAmount 当前未使用但验签仍需拼接；hmac 单独取） */
    private static final List<String> REFUND_QUERY_RESP_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_RefundOrderNo","r3_RefundAmount","r4_RefundTrxNo",
            "r5_RefundCompleteTime","r8_RefundWay","r9_ReceiveAccountNo","ra_Status","rb_Code",
            "rc_CodeMsg","rd_MarketRefAmount","re_FundsAccount");

    private static final List<String> TRANSFER_QUERY_REQ_FIELDS = List.of("userNo", "merchantOrderNo");
    /** 打款查询应答 data 验签字段（hmac 单独取，不参与拼接） */
    private static final List<String> TRANSFER_QUERY_RESP_FIELDS = List.of(
            "status","errorCode","errorDesc","userNo","tradeMerchantNo","merchantOrderNo",
            "platformSerialNo","receiverAccountNoEnc","receiverNameEnc","paidAmount","fee");

    public JoinpayQueryHandler() {
        on(BizType.T_PAY,  (ctx, req) -> queryOrder(ctx));
        on(BizType.T_REF,  (ctx, req) -> queryRefund(ctx));
        on(BizType.T_XFER, (ctx, req) -> queryTransfer(ctx));
        on(BizType.T_BAL,  (ctx, req) -> queryBalance(ctx));
    }

    // =========================================================================
    // 订单查询
    // =========================================================================

    private BizResult queryOrder(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("p0_Version","2.6"); params.put("p1_MerchantNo",cfg.getAppid());
        params.put("p2_OrderNo", ctx.getOrder().getTradeNo());
        var body = PaymentUtils.encodeForm(params)
                + "&hmac=" + JoinpaySignUtil.sign(params, ORDER_QUERY_REQ_FIELDS, cfg.getAppkey());
        try {
            var resp = HttpHelper.post(ctx, ORDER_QUERY_URL, body, "application/x-www-form-urlencoded");
            var m = JoinpayUtil.parseJsonNum(resp.bodyAsString());
            if (!resp.isSuccess() || m.isEmpty()
                    || !JoinpaySignUtil.verify(m, ORDER_QUERY_RESP_FIELDS, cfg.getAppkey()))
                return Responses.ing("QUERY_ERROR", "订单查询暂时失败", resp);
            var status = m.getOrDefault("ra_Status", "");
            var state = switch (status) { case "100" -> BizState.S_OK; case "101" -> BizState.S_FAIL; default -> BizState.S_ING; };
            return Responses.result(state)
                    .apiNo(m.get("r5_TrxNo")).code(m.get("rb_Code")).msg(m.get("rc_CodeMsg"))
                    .buyer(m.get("rd_OpenId"))
                    .traced(resp).build();
        } catch (Exception e) { log.debug("汇聚支付订单查询暂时失败, exception={}", e.getClass().getSimpleName()); return Responses.ing("QUERY_ERROR", e.getMessage()); }
    }

    // =========================================================================
    // 退款查询
    // =========================================================================

    private BizResult queryRefund(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var refund = ctx.getRefund();
        var params = new LinkedHashMap<String, String>();
        params.put("p0_Version","2.3"); params.put("p1_MerchantNo",cfg.getAppid());
        params.put("p2_RefundOrderNo", refund != null ? refund.getRefundNo() : "");
        var body = PaymentUtils.encodeForm(params)
                + "&hmac=" + JoinpaySignUtil.sign(params, REFUND_QUERY_REQ_FIELDS, cfg.getAppkey());
        try {
            var resp = HttpHelper.post(ctx, REFUND_QUERY_URL, body, "application/x-www-form-urlencoded");
            var m = JoinpayUtil.parseJsonNum(resp.bodyAsString());
            if (!resp.isSuccess() || m.isEmpty()
                    || !JoinpaySignUtil.verify(m, REFUND_QUERY_RESP_FIELDS, cfg.getAppkey()))
                return Responses.ing("QUERY_ERROR", "退款查询暂时失败", resp);
            var status = m.getOrDefault("ra_Status", "");
            var state = switch (status) { case "100" -> BizState.S_OK; case "101" -> BizState.S_FAIL; default -> BizState.S_ING; };
            return Responses.result(state)
                    .apiNo(m.get("r4_RefundTrxNo")).code(m.get("rb_Code")).msg(m.get("rc_CodeMsg"))
                    .traced(resp).build();
        } catch (Exception e) { log.debug("汇聚支付退款查询暂时失败, exception={}", e.getClass().getSimpleName()); return Responses.ing("QUERY_ERROR", e.getMessage()); }
    }

    // =========================================================================
    // 打款查询
    // =========================================================================

    private BizResult queryTransfer(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var transfer = ctx.getTransfer();
        var params = new LinkedHashMap<String, String>();
        params.put("userNo", cfg.getAppid());
        params.put("merchantOrderNo", transfer != null ? transfer.getTradeNo() : "");
        params.put("hmac", JoinpaySignUtil.sign(params, TRANSFER_QUERY_REQ_FIELDS, cfg.getAppkey()));
        try {
            var resp = HttpHelper.post(ctx, TRANSFER_QUERY_URL,
                    HttpHelper.MAPPER.writeValueAsString(params), "application/json");
            var m = JoinpayUtil.parseJsonNumRaw(resp.bodyAsString());
            var statusCode = String.valueOf(m.getOrDefault("statusCode", ""));
            if (!resp.isSuccess() || !"2001".equals(statusCode))
                return Responses.ing("QUERY_ERROR", "打款查询暂时失败", resp);
            var dataRaw = flatten(m.get("data"));
            if (dataRaw.isEmpty() || !JoinpaySignUtil.verify(dataRaw, TRANSFER_QUERY_RESP_FIELDS, cfg.getAppkey()))
                return Responses.ing("QUERY_ERROR", "打款查询暂时失败", resp);
            var status = dataRaw.getOrDefault("status", "");
            var state = switch (status) { case "205" -> BizState.S_OK; case "204","208","214" -> BizState.S_FAIL; default -> BizState.S_ING; };
            return Responses.result(state)
                    .apiNo(dataRaw.getOrDefault("platformSerialNo", ""))
                    .code(dataRaw.getOrDefault("errorCode", ""))
                    .msg(dataRaw.getOrDefault("errorDesc", ""))
                    .traced(resp).build();
        } catch (Exception e) { log.debug("汇聚支付打款查询暂时失败, exception={}", e.getClass().getSimpleName()); return Responses.ing("QUERY_ERROR", e.getMessage()); }
    }

    // =========================================================================
    // 余额查询
    // =========================================================================

    // 余额接口为 payment/pay/accountBalanceQuery，请求 {"userNo","hmac"} 且需验签回包。
    private static final String BALANCE_URL = "https://www.joinpay.com/payment/pay/accountBalanceQuery";
    private static final List<String> BALANCE_REQUEST_SIGN_FIELDS = List.of("userNo");
    private static final List<String> BALANCE_RESPONSE_SIGN_FIELDS = List.of(
            "statusCode", "message", "userNo", "userName", "currency",
            "useAbleSettAmount", "availableSettAmountFrozen", "errorCode", "errorDesc");

    private BizResult queryBalance(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("userNo", cfg.getAppid());
        params.put("hmac", JoinpaySignUtil.sign(params, BALANCE_REQUEST_SIGN_FIELDS, cfg.getAppkey()));
        String reqBody;
        try { reqBody = HttpHelper.MAPPER.writeValueAsString(params); }
        catch (Exception e) { return Responses.fail("BAL_ERROR", e.getMessage()); }
        try {
            var resp = HttpHelper.post(ctx, BALANCE_URL, reqBody, "application/json");
            var body = resp.bodyAsString();
            var m = JoinpayUtil.parseJsonNumRaw(body);
            var statusCode = String.valueOf(m.getOrDefault("statusCode", ""));
            var message = String.valueOf(m.getOrDefault("message", ""));
            if (!"2001".equals(statusCode))
                return Responses.fail(statusCode, message.isBlank() ? "查询失败" : message, resp);
            var data = flatten(m.get("data"));
            if (data.isEmpty())
                return Responses.fail("BAL_ERROR", "响应解析失败", resp);
            // 回包验签
            var signData = new LinkedHashMap<String, String>();
            signData.put("statusCode", statusCode);
            signData.put("message", message);
            signData.put("hmac", data.getOrDefault("hmac", ""));
            signData.putAll(data);
            if (!JoinpaySignUtil.verify(signData, BALANCE_RESPONSE_SIGN_FIELDS, cfg.getAppkey()))
                return Responses.fail("SIGN_ERROR", "返回验签失败", resp);
            var errCode = data.getOrDefault("errorCode", "");
            if (!errCode.isEmpty()) {
                var errDesc = data.getOrDefault("errorDesc", "");
                return Responses.fail(errCode, errDesc.isEmpty() ? "查询失败" : errDesc, resp);
            }
            var balance = data.getOrDefault("useAbleSettAmount", "");
            if (balance.isEmpty())
                return Responses.fail("BAL_ERROR", "余额为空", resp);
            return Responses.resultBal(balance, resp);
        } catch (Exception e) {
            log.error("查询余额失败", e);
            return Responses.fail("BAL_ERROR", e.getMessage());
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
}
