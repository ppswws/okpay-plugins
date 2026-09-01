package com.okpay.plugin.epay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.epay.util.EpaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * EpayCreateHandler 下单单元测试 —— Submit 模式（不请求渠道，拼接支付链接直接跳转）
 * 与常规 POST 模式的参数构造、appurl 尾斜杠规范化。
 */
@DisplayName("EpayCreateHandler 下单")
class EpayCreateHandlerTest {

    private static final String APPID = "EP1001";
    private static final String APPKEY = "epkey123";

    private static byte[] cfgRaw(String appurl, boolean submit) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appurl", appurl);
        cfg.put("appid", APPID);
        cfg.put("appkey", APPKEY);
        if (submit) cfg.put("submit", true);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private static InvokeContext ctx(byte[] cfgRaw) {
        var order = OrderSnapshot.builder()
                .tradeNo("T1").type("alipay").real(100L)
                .ipBuyer("1.2.3.4").param("P1").build();
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(order)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder()
                        .siteDomain("https://pay.example.com")
                        .notifyDomain("https://notify.example.com")
                        .goodsName("测试商品").build())
                .build();
    }

    /** 解析 x-www-form-urlencoded（encodeForm 的编码规则，与 query string 一致） */
    private static Map<String, String> parseForm(String body) {
        var params = new LinkedHashMap<String, String>();
        for (var pair : body.split("&")) {
            var kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    // =========================================================================
    // Submit 模式
    // =========================================================================

    @Test
    @DisplayName("Submit 模式：不请求渠道，拼接 submit.php 支付链接 jump 返回（无 device/clientip，签名有效）")
    void submitModeBuildsPayLinkWithoutChannelRequest() throws Exception {
        var ctx = ctx(cfgRaw("https://gw.test", true));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        var resp = new EpayCreateHandler().alipay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        assertThat(resp.getUrl()).startsWith("https://gw.test/submit.php?");
        var params = parseForm(resp.getUrl().substring(resp.getUrl().indexOf('?') + 1));
        // Submit 模式不带 device/clientip；业务参数与签名正常携带
        assertThat(params).doesNotContainKey("clientip").doesNotContainKey("device");
        assertThat(params).containsEntry("pid", APPID)
                .containsEntry("out_trade_no", "T1")
                .containsEntry("money", "1.00")
                .containsEntry("param", "P1");
        assertThat(EpaySignUtil.verify(params, APPKEY)).isTrue();
    }

    @Test
    @DisplayName("Submit 模式：appurl 带尾斜杠时规范化为单斜杠拼接（不出现 //submit.php）")
    void submitModeNormalizesTrailingSlash() throws Exception {
        var ctx = ctx(cfgRaw("https://gw.test//", true));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        var resp = new EpayCreateHandler().alipay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        assertThat(resp.getUrl()).startsWith("https://gw.test/submit.php?");
    }

    // =========================================================================
    // 常规 POST 模式
    // =========================================================================

    @Test
    @DisplayName("渠道 HTTP 层失败（502 nginx 错误页）：归类为渠道异常返回 error 页（含状态码），不抛异常")
    void channelHttpFailureReturnsChannelErrorPage() throws Exception {
        var ctx = ctx(cfgRaw("https://gw.test/", false));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            ("<html><head><title>502 Bad Gateway</title></head><body>"
                                    + "<center><h1>502 Bad Gateway</h1></center></body></html>")
                                    .getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var resp = new EpayCreateHandler().alipay(ctx);

            // 渠道挂是渠道问题：error 页文案归因渠道，宿主按渠道失败落库，不得报"插件内部错误"
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg())
                    .isEqualTo("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
        }
    }

    @Test
    @DisplayName("渠道 200 但响应体不是预期 JSON（HTML/CDN 拦截页）：同样归类为渠道异常，不抛异常")
    void channelUnparseableBodyReturnsChannelErrorPage() throws Exception {
        var ctx = ctx(cfgRaw("https://gw.test/", false));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "<html>cloudflare 拦截页</html>".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var resp = new EpayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg())
                    .isEqualTo("接口通道服务异常，未返回预期响应！HTTP Status Code:200");
        }
    }

    @Test
    @DisplayName("常规模式：POST 渠道 mapi.php（尾斜杠已规范化），参数含 device/clientip")
    void postModeSendsChannelRequestWithDeviceAndClientip() throws Exception {
        var ctx = ctx(cfgRaw("https://gw.test/", false));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        String[] captured = new String[2];
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                captured[0] = (String) inv.getArgument(1);
                captured[1] = (String) inv.getArgument(2);
                return new HttpHelper.HttpResponse(200, Map.of(),
                        "{\"code\":\"1\",\"payurl\":\"https://chan/alipay?pay=1\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        "req", 10, 1);
            });
            var resp = new EpayCreateHandler().alipay(ctx);

            assertThat(captured[0]).isEqualTo("https://gw.test/mapi.php"); // 无 //mapi.php
            var params = parseForm(captured[1]);
            assertThat(params).containsEntry("clientip", "1.2.3.4").containsEntry("device", "jump");
            assertThat(params).containsEntry("pid", APPID);
            assertThat(EpaySignUtil.verify(params, APPKEY)).isTrue();
            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo("https://chan/alipay?pay=1");
        }
    }
}
