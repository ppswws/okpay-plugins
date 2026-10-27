package com.okpay.plugin.epay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.epay.util.EpaySignUtil;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.NotifyResponse;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * EpayNotifyHandler 通知 —— 服务器二次查单必须以 status=1 确认真实支付。
 */
@DisplayName("EpayNotifyHandler 通知")
class EpayNotifyHandlerTest {

    private static final String APPID = "1001";
    private static final String APPKEY = "epkey123";
    private static final String TRADE_NO = "P20261028000000000000001";
    private static final long REAL = 100L;

    @Test
    @DisplayName("二次查单 status=1 → 完成订单，apiNo 取通道 trade_no")
    void paidQueryCompletesOrder() {
        var cb = mock(HostCallback.class);
        var ctx = ctx(cb, query());

        try (MockedStatic<EpayQueryHandler> q = mockStatic(EpayQueryHandler.class)) {
            q.when(() -> EpayQueryHandler.queryRaw(any(), eq(TRADE_NO)))
                    .thenReturn(new EpayQueryHandler.QueryResult(
                            Map.of("code", "1", "status", "1", "trade_no", "EP-UPSTREAM"), null));

            var resp = new EpayNotifyHandler().handle(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Ok.class);
            assertThat(((NotifyResponse.Ok) resp).body()).isEqualTo("success");
        }

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizNo()).isEqualTo(TRADE_NO);
        assertThat(captor.getValue().getState()).isEqualTo(BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("EP-UPSTREAM");
    }

    @Test
    @DisplayName("二次查单 status=0（订单存在但未支付）→ 拒绝，不完成订单")
    void unpaidQueryRejectsNotify() {
        var cb = mock(HostCallback.class);
        var ctx = ctx(cb, query());

        try (MockedStatic<EpayQueryHandler> q = mockStatic(EpayQueryHandler.class)) {
            q.when(() -> EpayQueryHandler.queryRaw(any(), eq(TRADE_NO)))
                    .thenReturn(new EpayQueryHandler.QueryResult(
                            Map.of("code", "1", "status", "0", "trade_no", "EP-UPSTREAM"), null));

            var resp = new EpayNotifyHandler().handle(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Fail.class);
            assertThat(((NotifyResponse.Fail) resp).body()).isEqualTo("query_unpaid");
        }
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("二次查单缺 status → 拒绝（查询成功不等于支付成功）")
    void missingStatusRejectsNotify() {
        var cb = mock(HostCallback.class);
        var ctx = ctx(cb, query());

        try (MockedStatic<EpayQueryHandler> q = mockStatic(EpayQueryHandler.class)) {
            q.when(() -> EpayQueryHandler.queryRaw(any(), eq(TRADE_NO)))
                    .thenReturn(new EpayQueryHandler.QueryResult(
                            Map.of("code", "1", "trade_no", "EP-UPSTREAM"), null));

            var resp = new EpayNotifyHandler().handle(ctx);

            assertThat(resp).isInstanceOf(NotifyResponse.Fail.class);
            assertThat(((NotifyResponse.Fail) resp).body()).isEqualTo("query_unpaid");
        }
        verify(cb, never()).completeBiz(any());
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private String query() {
        var params = new LinkedHashMap<String, String>();
        params.put("pid", APPID);
        params.put("trade_no", "EP-UPSTREAM");
        params.put("out_trade_no", TRADE_NO);
        params.put("type", "alipay");
        params.put("name", "Test");
        params.put("money", "1.00");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("sign_type", "MD5");
        params.put("sign", EpaySignUtil.sign(params, APPKEY));
        return params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private InvokeContext ctx(HostCallback cb, String query) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(REAL).type("alipay").build())
                .config(ConfigSnapshot.builder()
                        .siteDomain("https://pay.example.com")
                        .notifyDomain("https://notify.example.com").build())
                .request(RequestSnapshot.builder().method("GET").query(query).build())
                .callback(cb)
                .build();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appurl", "https://gw.test");
            cfg.put("appid", APPID);
            cfg.put("appkey", APPKEY);
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }
}
