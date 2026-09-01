package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sumapay.TestKeys;
import com.okpay.plugin.sumapay.util.SumapaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * SumapayQueryHandler 查单：status 2 成功（apiNo=tradeId）、3 失败、其余/渠道失败处理中。
 */
@DisplayName("SumapayQueryHandler 查单")
class SumapayQueryHandlerTest {

    private static final List<String> RESP_FIELDS = List.of(
            "requestId", "result", "merchantCode", "originalRequestId",
            "tradeId", "tradeSum", "status", "requestTime");

    @Test
    @DisplayName("status=2 → S_OK，apiNo 为渠道交易号")
    void statusOk() throws Exception {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(Map.of(
                    "originalRequestId", "T1", "tradeId", "FX202608230001",
                    "tradeSum", "1.00", "status", "2", "requestTime", "20260823120000")));
            var result = new SumapayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("FX202608230001");
        }
    }

    @Test
    @DisplayName("status=3 → S_FAIL，附渠道错误码")
    void statusFail() throws Exception {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(Map.of(
                    "originalRequestId", "T1", "tradeId", "",
                    "tradeSum", "1.00", "status", "3", "requestTime", "20260823120000",
                    "errorCode", "TRADE_CLOSED")));
            var result = new SumapayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("ORDER_FAIL");
            assertThat(result.getMsg()).isEqualTo("TRADE_CLOSED");
        }
    }

    @Test
    @DisplayName("status 其他（处理中）→ S_ING")
    void statusPending() throws Exception {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(Map.of(
                    "originalRequestId", "T1", "tradeId", "",
                    "tradeSum", "1.00", "status", "1", "requestTime", "20260823120000")));
            var result = new SumapayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("QUERY_PENDING");
        }
    }

    @Test
    @DisplayName("result != 00000 → S_ING（渠道查询失败视为未终态）")
    void channelQueryFailKeepsIng() throws Exception {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(Map.of("result", "2003")));
            var result = new SumapayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("QUERY_FAIL");
            assertThat(result.getMsg()).isEqualTo("订单查询失败[2003]");
        }
    }

    @Test
    @DisplayName("渠道异常（502）→ S_ING QUERY_ERROR")
    void channelErrorKeepsIng() {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><body>502</body></html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var result = new SumapayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("QUERY_ERROR");
        }
    }

    @Test
    @DisplayName("退款查询未注册 → UNSUPPORTED（丰付无退款查询接口）")
    void refundQueryUnsupported() {
        var ctx = ctx();
        var result = new SumapayQueryHandler().handle(ctx,
                BizRequest.builder().bizType(BizType.T_REF).build());

        assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(result.getCode()).isEqualTo("UNSUPPORTED");
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static BizRequest req() {
        return BizRequest.builder().bizType(BizType.T_PAY).build();
    }

    private InvokeContext ctx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").real(100L).type("alipay").build())
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

    private static void stubApi(MockedStatic<HttpHelper> mocked, HttpHelper.HttpResponse resp) {
        mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                .thenReturn(resp);
    }

    private static HttpHelper.HttpResponse signedResp(Map<String, String> fields) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("requestId", "ST1");
        m.put("result", "00000");
        m.put("merchantCode", "SM1");
        m.putAll(fields);
        m.put("signature", SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                SumapaySignUtil.concat(m, RESP_FIELDS)));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }
}
