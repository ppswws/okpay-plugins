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

/**
 * 丰付支付订单查询处理器（SearchOrderAction_merSingleQuery）。
 *
 * <p>丰付无退款查询接口，T_REF 未注册由 {@link AbstractBizHandler} 统一返回
 * {@link Responses#unsupported}。查单结果与退款一致以处理中兜底：渠道失败/状态未知
 * 均视为未终态，由宿主后续重查。</p>
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

    public SumapayQueryHandler() {
        on(BizType.T_PAY, (ctx, req) -> queryPay(ctx));
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
                return Responses.ing("QUERY_FAIL", "订单查询失败[" + result + "]", resp);
            var status = m.getOrDefault("status", "");
            return switch (status) {
                case "2" -> Responses.result(BizState.S_OK)
                        .apiNo(m.get("tradeId")).code(result).traced(resp).build();
                case "3" -> Responses.fail("ORDER_FAIL",
                        m.getOrDefault("errorCode", "订单失败"), resp);
                default -> Responses.ing("QUERY_PENDING", "订单处理中", resp);
            };
        } catch (Exception e) {
            log.debug("丰付订单查询暂时失败, exception={}", e.getClass().getSimpleName());
            return Responses.ing("QUERY_ERROR", e.getMessage());
        }
    }
}
