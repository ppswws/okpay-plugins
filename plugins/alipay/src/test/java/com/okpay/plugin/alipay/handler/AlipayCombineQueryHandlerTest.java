package com.okpay.plugin.alipay.handler;

import tools.jackson.core.type.TypeReference;
import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.SubOrderSnapshot;
import com.okpay.plugin.model.UpdateSubOrderRequest;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import com.okpay.plugin.sdk.Sdk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlipayQueryHandler 合单分支：逐子单 alipay.trade.query 聚合（QueryScanner 兜底），
 * 全 TRADE_SUCCESS/FINISHED 且金额一致 → 逐子单已付 + 主单完成；含 WAIT_BUYER_PAY → ing；
 * 含 TRADE_CLOSED → fail；金额不符 → fail；查询异常 → ing。
 * 退款查询（T_REF）：主单有子单 → 复用 refundCombine 逐单续退。
 */
@DisplayName("AlipayQueryHandler 合单查询")
class AlipayCombineQueryHandlerTest {

    private static final String APP_ID = "2021000000000001";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String REFUND_NO = "R202608181200000000001";
    private static KeyPair KP;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private static byte[] cfgRaw() throws Exception {
        return HttpHelper.MAPPER.writeValueAsBytes(Map.of(
                "appid", APP_ID,
                "appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()),
                "appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded())));
    }

    private static byte[] cfgRawOrNull() {
        try {
            return cfgRaw();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InvokeContext ctx(RefundSnapshot refund) {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull()).build())
                .refund(refund)
                .callback(mock(HostCallback.class));
        if (refund != null) {
            b.order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).apiTradeNo("TX1").build());
        }
        return b.build();
    }

    private static List<SubOrderSnapshot> subs(long money, long... refunded) {
        var out = new ArrayList<SubOrderSnapshot>(refunded.length);
        for (int i = 0; i < refunded.length; i++) {
            out.add(SubOrderSnapshot.builder()
                    .subTradeNo(Sdk.subTradeNo(TRADE_NO, i + 1))
                    .tradeNo(TRADE_NO).money(money).refundMoney(refunded[i]).build());
        }
        return out;
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 从请求表单体解析 biz_content */
    private static Map<String, Object> parseBizContent(String formBody) throws Exception {
        var params = new LinkedHashMap<String, String>();
        for (var pair : formBody.split("&")) {
            var idx = pair.indexOf("=");
            if (idx > 0) params.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return HttpHelper.MAPPER.readValue(params.get("biz_content"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static String subNo(int idx) {
        return Sdk.subTradeNo(TRADE_NO, idx);
    }

    /** 子单查询响应：status/total_amount（元字符串）/trade_no/buyer_user_id */
    private static byte[] queryResp(String txId, String status, String amount, String buyer)
            throws Exception {
        return signedResponse("alipay_trade_query_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"" + txId + "\","
                        + "\"trade_status\":\"" + status + "\",\"total_amount\":\"" + amount + "\","
                        + "\"buyer_user_id\":\"" + buyer + "\"}");
    }

    /** 按子单号分发的查询 stub：idx 1..n 各返回对应响应（biz_content 解析后匹配） */
    private static void stubQueryBySub(MockedStatic<HttpHelper> mocked, String[] txIds,
                                       String[] statuses, String[] amounts) throws Exception {
        mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
            var biz = parseBizContent(inv.getArgument(2).toString());
            var queried = String.valueOf(biz.get("out_trade_no"));
            for (int i = 0; i < txIds.length; i++) {
                if (subNo(i + 1).equals(queried)) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            queryResp(txIds[i], statuses[i], amounts[i], "B" + (i + 1)),
                            "req", 10, 1);
                }
            }
            throw new IllegalStateException("unexpected query " + queried);
        });
    }

    @Test
    @DisplayName("全 TRADE_SUCCESS 且金额一致 → S_OK（apiNo=首个子单）+ 逐子单已付标记")
    void allSuccessCompletes() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubQueryBySub(mocked, new String[]{"TX1", "TX2", "TX3"},
                    new String[]{"TRADE_SUCCESS", "TRADE_SUCCESS", "TRADE_SUCCESS"},
                    new String[]{"100.00", "100.00", "100.00"});

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TX1");
            assertThat(result.getBuyer()).isEqualTo("B1");
            var updates = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
            verify(cb, times(3)).updateSubOrder(updates.capture());
            assertThat(updates.getAllValues()).extracting(u -> u.getSubTradeNo())
                    .containsExactly(subNo(1), subNo(2), subNo(3));
            assertThat(updates.getAllValues()).allMatch(u -> u.getStatus() == SubOrderSnapshot.STATUS_PAID);
            assertThat(updates.getAllValues()).extracting(u -> u.getApiTradeNo())
                    .containsExactly("TX1", "TX2", "TX3");
        }
    }

    @Test
    @DisplayName("含 WAIT_BUYER_PAY → S_ING（保持待支付，等 QueryScanner 再扫）")
    void waitPayIng() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            // 顺序推进：首个未付子单即停（不推进任何已付标记）
            stubQueryBySub(mocked, new String[]{"", "", ""},
                    new String[]{"WAIT_BUYER_PAY", "WAIT_BUYER_PAY", "WAIT_BUYER_PAY"},
                    new String[]{"100.00", "100.00", "100.00"});

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("WAIT_BUYER_PAY");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("含 TRADE_CLOSED → S_FAIL")
    void closedFails() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            // 顺序推进：首个关闭子单即停（不推进任何已付标记）
            stubQueryBySub(mocked, new String[]{"", "", ""},
                    new String[]{"TRADE_CLOSED", "TRADE_CLOSED", "TRADE_CLOSED"},
                    new String[]{"100.00", "100.00", "100.00"});

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("TRADE_CLOSED");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("子单金额不符（查询 total_amount 元字符串）→ S_FAIL COMBINE_MISMATCH")
    void amountMismatchFails() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            // 顺序推进：首个金额不符子单即停（不推进任何已付标记）
            stubQueryBySub(mocked, new String[]{"", "", ""},
                    new String[]{"TRADE_SUCCESS", "TRADE_SUCCESS", "TRADE_SUCCESS"},
                    new String[]{"99.99", "100.00", "100.00"});

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("COMBINE_MISMATCH");
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("查询异常（业务处理中/网络抖动）→ S_ING（不误判终态）")
    void queryErrorIng() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 0, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_query_response",
                                    "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                            + "\"sub_code\":\"ACQ.TRADE_NOT_EXIST\",\"sub_msg\":\"交易不存在\"}"),
                            "req", 10, 1));

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            verify(cb, never()).updateSubOrder(any());
        }
    }

    @Test
    @DisplayName("T_REF 且主单有子单 → 复用 refundCombine 逐单续退（S_OK）")
    void queryRefundContinuesCombine() throws Exception {
        var refund = RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(30000).build();
        var ctx = ctx(refund);
        var cb = ctx.getCallback();
        // 第 1 单已退满 → 仅续退 2/3
        when(cb.getSubOrders(any())).thenReturn(subs(10000, 10000, 0, 0));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_refund_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TXREF\"}"),
                            "req", 10, 1));

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var bodies = bodyCaptor.getAllValues();
            assertThat(bodies).hasSize(2);
            assertThat(parseBizContent(bodies.get(0)))
                    .containsEntry("out_request_no", REFUND_NO + "_2");
        }
    }

    @Test
    @DisplayName("T_REF 且主单无子单 → 走单笔退款查询")
    void queryRefundWithoutSubs() throws Exception {
        var refund = RefundSnapshot.builder()
                .refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(30000).build();
        var ctx = ctx(refund);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_fastpay_refund_query_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"TXREF9\","
                                            + "\"refund_status\":\"REFUND_SUCCESS\"}"),
                            "req", 10, 1));

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TXREF9");
            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz).containsEntry("out_request_no", REFUND_NO)
                    .containsEntry("out_trade_no", TRADE_NO);
        }
    }

    @Test
    @DisplayName("T_PAY 无子单 → 走单笔查询路径（不聚合）")
    void noSubsFallsBackToSingle() throws Exception {
        var ctx = ctx(null);
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            queryResp("TX9", "TRADE_SUCCESS", "300.00", "B1"),
                            "req", 10, 1));

            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TX9");
            assertThat(parseBizContent(bodyCaptor.getValue())).containsEntry("out_trade_no", TRADE_NO);
        }
    }
}
