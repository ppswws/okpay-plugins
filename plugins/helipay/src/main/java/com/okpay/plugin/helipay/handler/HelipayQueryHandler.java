package com.okpay.plugin.helipay.handler;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.helipay.util.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import org.slf4j.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 合利宝查询处理器（订单/退款/打款/余额）。
 *
 * <p>查询失败的语义：查单接口业务失败（rt2_retCode != 0000）或渠道异常
 * 一律保持处理中（S_ING）交还定时轮询；余额查询失败直接失败返回。</p>
 */
public class HelipayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(HelipayQueryHandler.class);

    public HelipayQueryHandler() {
        on(BizType.T_PAY,  (ctx, req) -> queryOrder(ctx));
        on(BizType.T_REF,  (ctx, req) -> queryRefund(ctx));
        on(BizType.T_XFER, (ctx, req) -> queryTransfer(ctx));
        on(BizType.T_BAL,  (ctx, req) -> queryBalance(ctx));
    }

    // =========================================================================
    // 订单查询（AppPayQuery）
    // =========================================================================

    private BizResult queryOrder(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var order = ctx.getOrder();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayQuery");
        params.put("P2_orderId", order != null ? order.getTradeNo() : "");
        params.put("P3_customerNumber", cfg.getAppid());
        if (order != null && notBlank(order.getApiTradeNo()))
            params.put("P4_serialNumber", order.getApiTradeNo());
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            if (!"0000".equals(m.get("rt2_retCode")))
                return processing(m, resp);
            var status = upper(m.get("rt7_orderStatus"));
            var state = switch (status) {
                case "SUCCESS" -> BizState.S_OK;
                case "FAIL", "CLOSE", "CANCEL" -> BizState.S_FAIL;
                default -> BizState.S_ING;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt6_serialNumber")).buyer(m.get("rt11_openId"))
                    .code(m.get("rt2_retCode")).msg(m.get("rt3_retMsg"))
                    .traced(resp).build();
        } catch (Exception e) {
            // 查询失败（渠道异常）：保持处理中交还轮询
            log.debug("合利宝订单查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 退款查询（AppPayRefundQuery）
    // =========================================================================

    private BizResult queryRefund(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var refund = ctx.getRefund();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayRefundQuery");
        params.put("P2_refundOrderId", refund != null ? refund.getRefundNo() : "");
        params.put("P3_customerNumber", cfg.getAppid());
        if (refund != null && notBlank(refund.getApiRefundNo()))
            params.put("P4_serialNumber", refund.getApiRefundNo());
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            if (!"0000".equals(m.get("rt2_retCode")))
                return processing(m, resp);
            var status = upper(m.get("rt8_orderStatus"));
            var state = switch (status) {
                case "SUCCESS" -> BizState.S_OK;
                case "FAIL", "CLOSE" -> BizState.S_FAIL;
                default -> BizState.S_ING;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt7_serialNumber")).code(m.get("rt2_retCode"))
                    .msg(m.get("retReasonDesc"))
                    .traced(resp).build();
        } catch (Exception e) {
            log.debug("合利宝退款查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款查询（TransferQuery）
    // =========================================================================

    private BizResult queryTransfer(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var transfer = ctx.getTransfer();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "TransferQuery");
        params.put("P2_orderId", transfer != null ? transfer.getTradeNo() : "");
        params.put("P3_customerNumber", cfg.getAppid());
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            if (!"0000".equals(m.get("rt2_retCode")))
                return processing(m, resp);
            var status = upper(m.get("rt7_orderStatus"));
            var state = switch (status) {
                case "SUCCESS" -> BizState.S_OK;
                case "FAIL", "REFUND" -> BizState.S_FAIL;
                default -> BizState.S_ING;
            };
            return Responses.result(state)
                    .apiNo(m.get("rt6_serialNumber")).code(m.get("rt2_retCode"))
                    .msg(m.get("rt8_reason"))
                    .traced(resp).build();
        } catch (Exception e) {
            log.debug("合利宝打款查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 余额查询（MerchantAccountQuery，走商户接口）
    // =========================================================================

    private BizResult queryBalance(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "MerchantAccountQuery");
        params.put("P2_customerNumber", cfg.getAppid());
        params.put("P3_timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        try {
            var resp = HelipayApi.post(ctx, HelipayApi.MERCHANT_API_URL, params, cfg.getAppkey());
            var m = PaymentUtils.parseJsonMap(resp.bodyAsString());
            if (!"0000".equals(m.get("rt2_retCode"))) {
                var msg = firstNonBlank(m.get("rt3_retMsg"), m.get("rt2_retCode"), "查询失败");
                return Responses.fail(m.getOrDefault("rt2_retCode", "BAL_ERROR"), msg, resp);
            }
            var balance = m.getOrDefault("rt15_amountToBeSettled", "");
            if (balance.isBlank())
                return Responses.fail("BAL_ERROR", "余额为空", resp);
            return Responses.resultBal(balance, resp);
        } catch (Exception e) {
            log.error("查询余额失败", e);
            return Responses.fail("BAL_ERROR", e.getMessage());
        }
    }

    /** 渠道侧查询失败（无业务结果）：保持处理中，携带渠道错误码与说明 */
    private static BizResult processing(Map<String, String> m, HttpHelper.HttpResponse resp) {
        return Responses.result(BizState.S_ING)
                .code(m.get("rt2_retCode"))
                .msg(firstNonBlank(m.get("rt3_retMsg"), m.get("rt2_retCode")))
                .traced(resp).build();
    }

    private static String upper(String s) {
        return s != null ? s.toUpperCase(Locale.ROOT) : "";
    }

    private static String firstNonBlank(String... vals) {
        for (var v : vals)
            if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
