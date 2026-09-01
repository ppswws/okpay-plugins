package com.okpay.plugin.sumapay.util;

import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.Sdk;
import com.okpay.plugin.sumapay.TestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * 丰付接口调用封装：GBK 表单编码、GBK 响应解码（U+FFFD 兜底）、
 * HTTP/解析/验签失败归因渠道统一文案。
 */
@DisplayName("SumapayApi 接口封装")
class SumapayApiTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final List<String> SIGN_KEYS = List.of("requestId", "merchantCode");
    private static final List<String> VERIFY_KEYS = List.of("requestId", "result");

    // =========================================================================
    // GBK 表单编码
    // =========================================================================

    @Test
    @DisplayName("encodeFormGBK：中文值按 GBK 字节百分号编码，可 GBK 解码还原")
    void encodeFormGbk() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("goodsDesc", "元宝充值");
        params.put("requestId", "R1");

        var body = new String(SumapayApi.encodeFormGBK(params), StandardCharsets.ISO_8859_1);

        // 中文 GBK 双字节 → 双百分号编码
        assertThat(body).isEqualTo("goodsDesc=%D4%AA%B1%A6%B3%E4%D6%B5&requestId=R1");
        var decoded = new LinkedHashMap<String, String>();
        for (var pair : body.split("&")) {
            var idx = pair.indexOf('=');
            decoded.put(URLDecoder.decode(pair.substring(0, idx), GBK),
                    URLDecoder.decode(pair.substring(idx + 1), GBK));
        }
        assertThat(decoded).containsEntry("goodsDesc", "元宝充值").containsEntry("requestId", "R1");
    }

    // =========================================================================
    // 响应 GBK 解码
    // =========================================================================

    @Test
    @DisplayName("decode：Content-Type 声明 UTF-8 但响应为 GBK → 检出 U+FFFD 按 GBK 重解")
    void decodeGbkWhenUfffd() {
        var gbkJson = "{\"errorMsg\":\"订单中文描述\"}".getBytes(GBK);
        var resp = new HttpHelper.HttpResponse(200,
                Map.of("content-type", List.of("application/json;charset=UTF-8")),
                gbkJson, "req", 10, 1);

        assertThat(resp.bodyAsString()).contains("�");
        assertThat(SumapayApi.decode(resp)).contains("订单中文描述").doesNotContain("�");
    }

    @Test
    @DisplayName("decode：UTF-8 响应原样返回")
    void decodeUtf8Passthrough() {
        var resp = new HttpHelper.HttpResponse(200, Map.of(),
                "{\"result\":\"00000\"}".getBytes(StandardCharsets.UTF_8), "req", 10, 1);

        assertThat(SumapayApi.decode(resp)).isEqualTo("{\"result\":\"00000\"}");
    }

    // =========================================================================
    // post：HTTP/解析/验签失败归因
    // =========================================================================

    @Test
    @DisplayName("post 传输层失败（IOException）→ 渠道统一文案，无响应轨迹")
    void postTransportFailure() {
        var ctx = InvokeContext.builder().build();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenThrow(new IOException("timeout"));
            assertThatThrownBy(() -> SumapayApi.post(ctx, "http://x", Map.of("a", "1"),
                    TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, SIGN_KEYS, "signature",
                    VERIFY_KEYS, "signature"))
                    .isInstanceOf(Sdk.BizFailException.class)
                    .hasMessage("接口通道服务异常，未返回预期响应！");
        }
    }

    @Test
    @DisplayName("post HTTP 层失败（502）→ 渠道统一文案含状态码")
    void postHttpFailure() {
        var ctx = InvokeContext.builder().build();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head></html>"
                                    .getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            assertThatThrownBy(() -> SumapayApi.post(ctx, "http://x", Map.of("a", "1"),
                    TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, SIGN_KEYS, "signature",
                    VERIFY_KEYS, "signature"))
                    .isInstanceOf(Sdk.BizFailException.class)
                    .hasMessage("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
        }
    }

    @Test
    @DisplayName("post 垃圾响应（非 JSON）→ 归因渠道统一文案")
    void postGarbageBody() {
        var ctx = InvokeContext.builder().build();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            "<html>CDN 拦截页</html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            assertThatThrownBy(() -> SumapayApi.post(ctx, "http://x", Map.of("a", "1"),
                    TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, SIGN_KEYS, "signature",
                    VERIFY_KEYS, "signature"))
                    .isInstanceOf(Sdk.BizFailException.class)
                    .hasMessage("接口通道服务异常，未返回预期响应！HTTP Status Code:200");
        }
    }

    @Test
    @DisplayName("post 响应验签失败 → 返回验签失败，轨迹携带")
    void postVerifyFail() throws Exception {
        var ctx = InvokeContext.builder().build();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> okResponse(Map.of("requestId", "R1", "result", "00000"), false));
            assertThatThrownBy(() -> SumapayApi.post(ctx, "http://x", Map.of("a", "1"),
                    TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, SIGN_KEYS, "signature",
                    VERIFY_KEYS, "signature"))
                    .isInstanceOf(Sdk.BizFailException.class)
                    .hasMessage("返回验签失败");
        }
    }

    @Test
    @DisplayName("post 成功：GBK 表单请求体含签名域，响应验签通过原样返回")
    void postSuccess() throws Exception {
        var ctx = InvokeContext.builder().build();
        var bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        var contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), bodyCaptor.capture(),
                    contentTypeCaptor.capture(), any()))
                    .thenAnswer(inv -> okResponse(Map.of("requestId", "R1", "result", "00000"), true));
            var resp = SumapayApi.post(ctx, "http://x", Map.of("requestId", "R1", "merchantCode", "M1"),
                    TestKeys.PRIVATE_KEY, TestKeys.PUBLIC_KEY, SIGN_KEYS, "signature",
                    VERIFY_KEYS, "signature");

            assertThat(resp.isSuccess()).isTrue();
        }
        assertThat(contentTypeCaptor.getValue()).isEqualTo("application/x-www-form-urlencoded;charset=GBK");
        var form = new LinkedHashMap<String, String>();
        for (var pair : new String(bodyCaptor.getValue(), StandardCharsets.ISO_8859_1).split("&")) {
            var idx = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, idx), GBK),
                    URLDecoder.decode(pair.substring(idx + 1), GBK));
        }
        assertThat(form).containsEntry("requestId", "R1").containsEntry("merchantCode", "M1");
        assertThat(form.get("signature")).isBase64();
        // 签名内容 = 按字段序非空值拼接
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "R1M1", form.get("signature"))).isTrue();
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static HttpHelper.HttpResponse okResponse(Map<String, String> fields, boolean signed)
            throws Exception {
        var m = new LinkedHashMap<>(fields);
        if (signed)
            m.put("signature", SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                    SumapaySignUtil.concat(m, VERIFY_KEYS)));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }
}
