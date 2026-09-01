package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sumapay.TestKeys;
import com.okpay.plugin.sumapay.util.SumapaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * SumapaySubmitHandler 退款编排：非当日订单先查二级户余额、不足付款至二级户再退款；
 * 当日订单直退；业务拒绝终态失败、传输失败保持处理中。
 */
@DisplayName("SumapaySubmitHandler 退款编排")
class SumapaySubmitHandlerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Charset GBK = Charset.forName("GBK");

    private static final List<String> BALANCE_RESP_FIELDS = List.of(
            "requestId", "result", "userIdIdentity", "availSum");
    private static final List<String> FUND_RESP_FIELDS = List.of(
            "requestId", "result", "merchantCode", "userIdIdentity");
    private static final List<String> REFUND_RESP_FIELDS = List.of(
            "requestId", "result", "remark");

    // =========================================================================
    // 当日订单：直接退款
    // =========================================================================

    @Test
    @DisplayName("当日订单 → 跳过余额查询/付款至二级户，直接 Refund_do")
    void sameDayRefundsDirectly() throws Exception {
        var ctx = refundCtx(todayTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(refundResp("00000")));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("R1");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()));
        }
    }

    @Test
    @DisplayName("退款 00000 → S_OK；请求含 requestId=退款单号/原单号/回调地址，签名域 mersignature")
    void refundOkCapturesRequest() throws Exception {
        var tradeNo = todayTradeNo();
        var ctx = refundCtx(tradeNo);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = new java.util.concurrent.atomic.AtomicReference<byte[]>();
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        bodyCaptor.set(inv.getArgument(2));
                        return refundResp("00000");
                    });
            new SumapaySubmitHandler().handle(ctx, req());

            var raw = new String(bodyCaptor.get(), StandardCharsets.ISO_8859_1);
            var form = PaymentUtils.parseForm(raw);
            assertThat(form).containsEntry("requestId", "R1")
                    .containsEntry("originalRequestId", tradeNo)
                    .containsEntry("tradeProcess", "SM1")
                    .containsEntry("fund", "1.00")
                    .containsEntry("noticeUrl", "https://pay.example.com/pay/refundnotify/R1")
                    .containsEntry("refundMothed", "1")
                    .containsEntry("remark", "R1");
            // reason 中文经 GBK 百分号编码传输，从原始表单提取百分号序列再按 GBK 还原
            assertThat(URLDecoder.decode(formValue(raw, "reason"), GBK)).isEqualTo("协商退款");
            // mersignature 覆盖 [requestId,originalRequestId,tradeProcess,fund,noticeUrl,remark]
            assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY,
                    "R1" + tradeNo + "SM1" + "1.00"
                            + "https://pay.example.com/pay/refundnotify/R1" + "R1",
                    form.get("mersignature"))).isTrue();
        }
    }

    // =========================================================================
    // 非当日订单：余额查询 + 按需付款至二级户
    // =========================================================================

    @Test
    @DisplayName("非当日订单且二级户余额充足 → 只查余额（IFSU0043），不转入直接退款")
    void oldOrderBalanceSufficientSkipsFund() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var firstBody = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
            var count = new AtomicInteger();
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        var body = PaymentUtils.parseForm(
                                new String(inv.getArgument(2), StandardCharsets.ISO_8859_1));
                        if (count.getAndIncrement() == 0) {
                            firstBody.set(body);
                            return balanceResp("5.00");
                        }
                        return refundResp("00000");
                    });
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            // 两个请求：IFSU0043（fundSharing）+ Refund_do，无 IFSU0040
            assertThat(firstBody.get()).containsEntry("requestType", "IFSU0043")
                    .containsEntry("requestId", "QR1");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    org.mockito.Mockito.times(2));
        }
    }

    @Test
    @DisplayName("非当日订单且余额不足 → IFSU0043 → IFSU0040 付款至二级户 → Refund_do（不重复转入）")
    void oldOrderBalanceInsufficientFunds() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodies = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
            var raws = new java.util.concurrent.atomic.AtomicReference<String>();
            var count = new AtomicInteger();
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        var raw = new String(inv.getArgument(2), StandardCharsets.ISO_8859_1);
                        var body = PaymentUtils.parseForm(raw);
                        var idx = count.getAndIncrement();
                        if (idx == 1) { // IFSU0040 请求体
                            bodies.set(body);
                            raws.set(raw);
                        }
                        return switch (idx) {
                            case 0 -> balanceResp("0.50");
                            case 1 -> fundResp("00000");
                            default -> refundResp("00000");
                        };
                    });
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var fund = bodies.get();
            assertThat(fund).containsEntry("requestType", "IFSU0040")
                    .containsEntry("requestId", "FR1")
                    .containsEntry("sum", "1.00")
                    .containsEntry("noticeUrl", "https://pay.example.com/pay/paymerchantnotify/R1");
            // reason 中文经 GBK 百分号编码传输，从原始表单提取百分号序列再按 GBK 还原
            assertThat(URLDecoder.decode(formValue(raws.get(), "reason"), GBK))
                    .isEqualTo("退款所需金额");
        }
    }

    @Test
    @DisplayName("非当日订单余额查询业务失败 → S_FAIL 终态，不发起转入与退款")
    void balanceQueryRejected() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(
                    signedResp("IFSU0043", BALANCE_RESP_FIELDS, Map.of("result", "2003"))));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("REFUND_FAIL");
            assertThat(result.getMsg()).isEqualTo("二级户余额查询失败[2003]");
        }
    }

    @Test
    @DisplayName("付款至二级户受理中（00001）→ S_FAIL 终止退款（防重试重复转入）")
    void fundAcceptedFails() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(
                    balanceResp("0.50"),
                    signedResp("IFSU0040", FUND_RESP_FIELDS, Map.of("result", "00001"))));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getMsg()).isEqualTo("付款至二级户已受理，请稍后再尝试退款");
        }
    }

    @Test
    @DisplayName("付款至二级户余额不足（200300162）→ S_FAIL")
    void fundRejected() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(
                    balanceResp("0.50"),
                    signedResp("IFSU0040", FUND_RESP_FIELDS, Map.of("result", "200300162"))));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getMsg()).isEqualTo("付款至二级户失败[200300162]");
        }
    }

    @Test
    @DisplayName("余额查询传输失败（502）→ S_ING 保持处理中（状态未知不误判失败）")
    void balanceQueryChannelErrorKeepsIng() throws Exception {
        var ctx = refundCtx(oldTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("REFUND_ERROR");
        }
    }

    // =========================================================================
    // Refund_do 结果码
    // =========================================================================

    @Test
    @DisplayName("Refund_do 受理中（00001）→ S_ING")
    void refundAcceptedKeepsIng() throws Exception {
        var ctx = refundCtx(todayTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(refundResp("00001")));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("00001");
        }
    }

    @Test
    @DisplayName("Refund_do 二级户余额不足（200300162）→ S_FAIL 明确文案")
    void refundInsufficientBalanceFails() throws Exception {
        var ctx = refundCtx(todayTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(refundResp("200300162")));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getMsg()).isEqualTo("账号余额不足[200300162]");
        }
    }

    @Test
    @DisplayName("Refund_do 其余业务码 → S_FAIL 带错误码")
    void refundOtherCodeFails() throws Exception {
        var ctx = refundCtx(todayTradeNo());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubSequential(mocked, List.of(refundResp("300101")));
            var result = new SumapaySubmitHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getMsg()).isEqualTo("退款失败[300101]");
        }
    }

    @Test
    @DisplayName("T_PAY submit → 处理中占位")
    void submitPayPlaceholder() {
        var ctx = refundCtx(todayTradeNo());
        var result = new SumapaySubmitHandler().handle(ctx,
                BizRequest.builder().bizType(BizType.T_PAY).build());

        assertThat(result.getState()).isEqualTo(BizState.S_ING);
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static BizRequest req() {
        return BizRequest.builder().bizType(BizType.T_REF).build();
    }

    private static String todayTradeNo() {
        return "P" + LocalDate.now(SHANGHAI).format(DATE) + "123456789";
    }

    private static String oldTradeNo() {
        return "P20200101123456789";
    }

    private InvokeContext refundCtx(String tradeNo) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo(tradeNo).real(100L).type("alipay").build())
                .refund(RefundSnapshot.builder().refundNo("R1").tradeNo(tradeNo).amount(100L).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "SM1");
            cfg.put("appuserid", "AU1");
            cfg.put("appmchid", "SUB1");
            cfg.put("appkey", TestKeys.PUBLIC_KEY);
            cfg.put("appsecret", TestKeys.PRIVATE_KEY);
            cfg.put("biztype", "1");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    /** 从原始表单串提取键值（百分号编码原样保留，供 GBK 还原中文） */
    private static String formValue(String rawForm, String key) {
        for (var pair : rawForm.split("&")) {
            var idx = pair.indexOf('=');
            if (idx > 0 && key.equals(pair.substring(0, idx)))
                return pair.substring(idx + 1);
        }
        return "";
    }

    /** 顺序消费渠道响应（IFSU0043 → IFSU0040 → Refund_do） */
    private static void stubSequential(MockedStatic<HttpHelper> mocked, List<HttpHelper.HttpResponse> responses) {
        var idx = new AtomicInteger();
        mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    var i = idx.getAndIncrement();
                    if (i >= responses.size())
                        throw new IllegalStateException("超出预期请求次数: " + i);
                    return responses.get(i);
                });
    }

    private static HttpHelper.HttpResponse balanceResp(String availSum) throws Exception {
        return signedResp("IFSU0043", BALANCE_RESP_FIELDS,
                Map.of("userIdIdentity", "AU1", "availSum", availSum));
    }

    private static HttpHelper.HttpResponse fundResp(String result) throws Exception {
        return signedResp("IFSU0040", FUND_RESP_FIELDS,
                Map.of("merchantCode", "SM1", "userIdIdentity", "AU1", "result", result));
    }

    private static HttpHelper.HttpResponse refundResp(String result) throws Exception {
        // Refund_do 响应验签键为 resultSignature（区别于查询/转入类接口的 signature）
        return signedResp("Refund_do", REFUND_RESP_FIELDS, "resultSignature",
                Map.of("requestId", "R1", "remark", "R1", "result", result));
    }

    private static HttpHelper.HttpResponse signedResp(String requestType, List<String> verifyKeys,
                                                      Map<String, String> fields) throws Exception {
        return signedResp(requestType, verifyKeys, "signature", fields);
    }

    private static HttpHelper.HttpResponse signedResp(String requestType, List<String> verifyKeys,
                                                      String signKey, Map<String, String> fields) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("requestId", "R1");
        m.put("merchantCode", "SM1");
        m.put("userIdIdentity", "AU1");
        m.put("result", "00000");
        m.putAll(fields);
        m.put(signKey, SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                SumapaySignUtil.concat(m, verifyKeys)));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }
}
