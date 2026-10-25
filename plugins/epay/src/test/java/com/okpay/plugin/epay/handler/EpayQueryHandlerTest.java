package com.okpay.plugin.epay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * EpayQueryHandler 查单 —— 成功码兼容（code=1/200）。
 */
@DisplayName("EpayQueryHandler 查单")
class EpayQueryHandlerTest {

    @Test
    @DisplayName("code=200 → S_OK（兼容渠道）")
    void code200WithoutStatusIsOk() throws Exception {
        var result = queryWithBody("{\"code\":\"200\",\"api_trade_no\":\"EP202610210001\"}");

        assertThat(result.getState()).isEqualTo(BizState.S_OK);
        assertThat(result.getApiNo()).isEqualTo("EP202610210001");
        assertThat(result.getCode()).isEqualTo("200");
    }

    @Test
    @DisplayName("code=1 → S_OK（标准易支付）")
    void codeOneIsOk() throws Exception {
        var result = queryWithBody("{\"code\":\"1\",\"api_trade_no\":\"EP202610210002\"}");

        assertThat(result.getState()).isEqualTo(BizState.S_OK);
        assertThat(result.getApiNo()).isEqualTo("EP202610210002");
    }

    @Test
    @DisplayName("code 非成功 → S_WAIT（支付订单查单不使用 S_FAIL）")
    void nonSuccessCodeIsWait() throws Exception {
        var result = queryWithBody("{\"code\":\"0\",\"msg\":\"订单未支付\"}");

        assertThat(result.getState()).isEqualTo(BizState.S_WAIT);
    }

    @Test
    @DisplayName("渠道查询异常 → S_WAIT（瞬时失败视为未终态）")
    void channelErrorKeepsWait() {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenThrow(new IOException("timeout"));
            var result = new EpayQueryHandler().handle(ctx, req());

            assertThat(result.getState()).isEqualTo(BizState.S_WAIT);
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private BizResult queryWithBody(String body) throws Exception {
        var ctx = ctx();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            body.getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            return new EpayQueryHandler().handle(ctx, req());
        }
    }

    private static BizRequest req() {
        return BizRequest.builder().bizType(BizType.T_PAY).build();
    }

    private InvokeContext ctx() {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").real(100L).type("alipay").build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder()
                        .siteDomain("https://pay.example.com")
                        .notifyDomain("https://notify.example.com").build())
                .build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appurl", "https://gw.test");
            cfg.put("appid", "EP1001");
            cfg.put("appkey", "epkey123");
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }
}
