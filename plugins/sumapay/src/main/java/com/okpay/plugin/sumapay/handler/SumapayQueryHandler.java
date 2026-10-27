package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.enums.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.sumapay.util.*;
import org.slf4j.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 丰付支付订单查询处理器（SearchOrderAction_merSingleQuery）。
 *
 * <p>丰付无退款查询接口，T_REF 未注册（能力门控）。支付订单查单不使用 S_FAIL：
 * status=3（渠道订单失败）/渠道失败/状态未知一律结果未定，由宿主后续重查。</p>
 */
public class SumapayQueryHandler extends AbstractBizHandler {

    private static final Logger log = LoggerFactory.getLogger(SumapayQueryHandler.class);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final List<String> REQ_FIELDS = List.of(
            "requestId", "merchantCode", "originalRequestId");
    private static final List<String> RESP_FIELDS = List.of(
            "requestId", "result", "merchantCode", "originalRequestId",
            "tradeId", "tradeSum", "status", "requestTime");
    private static final List<String> PAYOUT_REQ_FIELDS = List.of(
            "requestId", "merchantCode", "originalRequestId");
    private static final List<String> PAYOUT_RESP_FIELDS = List.of(
            "requestId", "result", "status", "dealRemark");
    private static final List<String> BALANCE_REQ_FIELDS = List.of(
            "requestId", "merchantCode");
    private static final List<String> BALANCE_RESP_FIELDS = List.of(
            "result", "merchantCode", "merchantName", "balance", "withdrawAbleBalance",
            "frozenBalance", "prepaidAccountBalance", "unsettledBalance",
            "riskReserveAccountBalance");

    public SumapayQueryHandler() {
        on(BizType.T_PAY, (ctx, req) -> queryPay(ctx));
        on(BizType.T_XFER, (ctx, req) -> queryPayout(ctx));
        on(BizType.T_BAL, (ctx, req) -> queryBalance(ctx));
    }

    private BizResult queryPay(InvokeContext ctx) {
        var order = ctx.getOrder();
        var cfg = SumapayConfig.from(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("requestType", "SearchOrderAction_merSingleQuery");
        params.put("requestId", "S" + order.getTradeNo());
        params.put("requestStartTime", LocalDateTime.now(SHANGHAI).format(TRADE_TIME));
        params.put("merchantCode", cfg.getAppid());
        params.put("originalRequestId", order.getTradeNo());
        try {
            var resp = SumapayApi.post(ctx, SumapayApi.QUERY_URL, params,
                    cfg.getAppsecret(), cfg.getAppkey(),
                    REQ_FIELDS, "signature", RESP_FIELDS, "signature");
            var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
            var result = m.getOrDefault("result", "");
            if (!"00000".equals(result))
                return Responses.wait("QUERY_FAIL", "订单查询失败[" + result + "]", resp);
            var status = m.getOrDefault("status", "");
            return switch (status) {
                case "2" -> Responses.result(BizState.S_OK)
                        .apiNo(m.get("tradeId")).code(result).traced(resp).build();
                case "3" -> Responses.waitMsg(m.getOrDefault("errorCode", "订单失败"), resp);
                default -> Responses.wait("QUERY_PENDING", "订单处理中", resp);
            };
        } catch (Exception e) {
            log.debug("丰付订单查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 打款查询（singleTransfer_singleTransferQuery）
    // =========================================================================

    private BizResult queryPayout(InvokeContext ctx) {
        var transfer = ctx.getTransfer();
        var cfg = SumapayConfig.fromPayout(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("requestId", "S" + transfer.getTradeNo());
        params.put("merchantCode", cfg.getAppid());
        params.put("originalRequestId", transfer.getTradeNo());
        try {
            var resp = SumapayApi.post(ctx, SumapayApi.PAYOUT_QUERY_URL, params,
                    cfg.getAppsecret(), cfg.getAppkey(),
                    PAYOUT_REQ_FIELDS, "signature", PAYOUT_RESP_FIELDS, "signature");
            var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
            var result = m.getOrDefault("result", "");
            if (!"00000".equals(result))
                return Responses.wait("QUERY_FAIL", "打款查询失败[" + result + "]", resp);
            var status = m.getOrDefault("status", "");
            var msg = m.getOrDefault("dealRemark", "");
            // 渠道查询应答没有上游流水号；优先沿用已记录值，否则用平台单号占位
            var apiNo = transfer.getApiTradeNo() != null && !transfer.getApiTradeNo().isBlank()
                    ? transfer.getApiTradeNo() : transfer.getTradeNo();
            return switch (status) {
                case "2" -> Responses.result(BizState.S_OK)
                        .apiNo(apiNo).code(status).msg(msg).traced(resp).build();
                case "3", "4", "5", "7", "10" -> Responses.fail(status,
                        msg.isBlank() ? "打款失败[" + status + "]" : msg, resp);
                default -> Responses.result(BizState.S_WAIT)
                        .apiNo(apiNo).code(status).msg(msg).traced(resp).build();
            };
        } catch (Exception e) {
            log.debug("丰付打款查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.wait("QUERY_ERROR", e.getMessage());
        }
    }

    // =========================================================================
    // 商户账户余额（MerchantAccountQueryAction_merchantAccountQuery）
    // =========================================================================

    private BizResult queryBalance(InvokeContext ctx) {
        var cfg = SumapayConfig.fromPayout(ctx);
        var params = new LinkedHashMap<String, String>();
        params.put("requestId", "B" + UUID.randomUUID().toString().replace("-", ""));
        params.put("merchantCode", cfg.getAppid());
        try {
            var resp = SumapayApi.post(ctx, SumapayApi.MERCHANT_ACCOUNT_QUERY_URL, params,
                    cfg.getAppsecret(), cfg.getAppkey(),
                    BALANCE_REQ_FIELDS, "signature", BALANCE_RESP_FIELDS, "signature");
            var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
            var result = m.getOrDefault("result", "");
            if (!"00000".equals(result))
                return Responses.fail(result, "余额查询失败[" + result + "]", resp);
            // 打款可用余额取“账户可提现金额”；总余额含预付手续费账户，不用于出款
            var balance = m.getOrDefault("withdrawAbleBalance", "");
            if (balance.isBlank())
                return Responses.fail("BAL_ERROR", "余额为空", resp);
            return Responses.resultBal(balance, resp);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                log.error("丰付余额查询失败", e);
            }
            return Responses.wait("BAL_ERROR", e.getMessage());
        }
    }
}
