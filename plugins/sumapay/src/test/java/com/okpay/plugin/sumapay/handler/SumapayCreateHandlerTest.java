package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sumapay.TestKeys;
import com.okpay.plugin.sumapay.util.SumapaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SumapayCreateHandler 下单矩阵：H5（scheme 提取/域名跳转）、聚合扫码（二维码图优先）、
 * 桌面端扫码中转、公众号 OAuth、未开启支付方式/渠道业务失败。
 */
@DisplayName("SumapayCreateHandler 下单")
class SumapayCreateHandlerTest {

    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0";

    // =========================================================================
    // 支付宝 H5（IOZ2010）
    // =========================================================================

    @Test
    @DisplayName("支付宝H5：payUrl 经丰付域名中转 → 取 scheme 跳转")
    void alipayH5SchemeJump() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "payUrl", "https://www.sumapay.com/wap/pay?scheme="
                            + "alipays%3A%2F%2Fplatformapi%2Fstartapp%3FappId%3D20000123")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo(
                    "alipays://platformapi/startapp?appId=20000123");
        }
    }

    @Test
    @DisplayName("支付宝H5：payUrl 为丰付页面（无 scheme）→ 跳转原始地址")
    void alipayH5JumpRaw() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "payUrl", "https://www.sumapay.com/wap/pay?token=abc")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo("https://www.sumapay.com/wap/pay?token=abc");
        }
    }

    @Test
    @DisplayName("支付宝H5：payUrl 不含丰付域名 → 扫码页兜底")
    void alipayH5FallbackQrcode() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "payUrl", "https://pay.example.com/qr/ABC")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("alipay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://pay.example.com/qr/ABC");
        }
    }

    @Test
    @DisplayName("支付宝H5：result 非 00000 → error 页带渠道错误码，不落 ext")
    void alipayH5ChannelRejected() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "result", "2003", "errorMsg", "参数错误", "payUrl", "")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).isEqualTo("[2003]参数错误");
        }
    }

    @Test
    @DisplayName("支付宝H5：渠道未返回 payUrl → error 页")
    void alipayH5MissingPayUrl() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of("payUrl", "")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).isEqualTo("渠道未返回支付地址");
        }
    }

    // =========================================================================
    // 聚合扫码（IOZ1016）
    // =========================================================================

    @Test
    @DisplayName("聚合扫码（支付宝）：返回二维码图 → data URI 扫码页")
    void scanPayCodeImg() throws Exception {
        var ctx = ctx("alipay", "2", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(SCAN_VERIFY, Map.of(
                    "codeImgString", "iVBORw0KGgo=", "codeUrl", "https://qr.example.com/x")));
            var resp = new SumapayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("alipay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        }
    }

    @Test
    @DisplayName("聚合扫码（微信）：无二维码图 → 扫码地址页")
    void scanPayCodeUrl() throws Exception {
        var ctx = ctx("wxpay", "3", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(SCAN_VERIFY, Map.of(
                    "codeUrl", "https://qr.example.com/wx")));
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/wx");
        }
    }

    // =========================================================================
    // 微信 H5（IOZ1017）
    // =========================================================================

    @Test
    @DisplayName("微信H5（手机端）：payUrl 非丰付域名 → wxpay_h5 页")
    void wxH5Mobile() throws Exception {
        var ctx = ctx("wxpay", "1", "Mozilla/5.0 (iPhone) Safari/604.1", null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            stubApi(mocked, signedResp(WX_H5_VERIFY, Map.of(
                    "payUrl", "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1")));
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            assertThat(resp.getUrl()).isEqualTo("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1");
        }
    }

    @Test
    @DisplayName("微信H5（桌面端）：不建单，中转页扫码，重入后手机端发起")
    void wxH5DesktopRelay() throws Exception {
        var ctx = ctx("wxpay", "1", UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            assertThat(resp.getUrl()).startsWith("https://pay.example.com/pay/wxpay/T1?t=");
            // 桌面端不发起任何渠道请求
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    // =========================================================================
    // 微信公众号（IOZ2011）
    // =========================================================================

    @Test
    @DisplayName("公众号（微信内无 code）→ 跳通道公众号授权 URL")
    void wxMpAuthUrl() throws Exception {
        var ctx = ctx("wxpay", "2", UA_WECHAT, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
            assertThat(resp.getUrl()).contains("appid=wxMPAPP1");
            assertThat(URLDecoder.decode(resp.getUrl(), StandardCharsets.UTF_8))
                    .contains("redirect_uri=https://pay.example.com/pay/wxpay/T1");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("公众号（桌面端无 code）→ 中转页扫码，不建单")
    void wxMpDesktopRelay() throws Exception {
        var ctx = ctx("wxpay", "2", UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("公众号（携带 auth_code）→ OAuth 换 openid → IOZ2011 建单 → wxpay_jspay 页带支付参数")
    void wxMpWithCode() throws Exception {
        var ctx = ctx("wxpay", "2", UA_WECHAT, "auth_code=CODE1");
        var cb = ctx.getCallback();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            // OAuth code 兑换 openid
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            // IOZ2011 建单：pay_info 为 JS 支付参数 JSON
            stubApi(mocked, signedResp(JSAPI_VERIFY, Map.of(
                    "pay_info", "{\"appId\":\"wxMPAPP1\",\"timeStamp\":\"1700000000\"}")));
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
            assertThat(new String(resp.getDataRaw(), StandardCharsets.UTF_8))
                    .contains("js_api_parameters")
                    .contains("wxMPAPP1");
        }
        // OAuth 身份经宿主落库风控（buyer + 黑名单）
        verify(cb).recordOAuthIdentity(any());
    }

    @Test
    @DisplayName("公众号未配置 → error 提示缺少公众号配置")
    void wxMpMissingConfig() {
        var ctx = ctxNoMp("wxpay", "2", UA_WECHAT, null);

        var resp = new SumapayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("error");
        assertThat(resp.getMsg()).isEqualTo("缺少公众号配置");
    }

    // =========================================================================
    // 未开启支付方式
    // =========================================================================

    @Test
    @DisplayName("支付宝方式未开启（biztype 仅微信）→ error")
    void alipayNotEnabled() {
        var ctx = ctx("alipay", "3", null, null);

        var resp = new SumapayCreateHandler().alipay(ctx);

        assertThat(resp.getType()).isEqualTo("error");
        assertThat(resp.getMsg()).isEqualTo("当前通道未开启支付宝支付方式");
    }

    @Test
    @DisplayName("微信方式未开启（biztype 空 → 默认 1）→ H5 正常，未配置公众号时不落公众号分支")
    void wxpayDefaultMode() throws Exception {
        var ctx = ctx("wxpay", null, UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var resp = new SumapayCreateHandler().wxpay(ctx);

            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static final List<String> ALIPAY_H5_VERIFY = List.of("requestId", "result", "payUrl", "errorMsg");
    private static final List<String> WX_H5_VERIFY = List.of("requestId", "result", "payUrl", "mpAppId", "mpPath", "errorMsg");
    private static final List<String> SCAN_VERIFY = List.of("requestId", "result", "codeUrl", "codeImgString", "errorMsg");
    private static final List<String> JSAPI_VERIFY = List.of("requestId", "result", "passThrough", "pay_info");

    private InvokeContext ctx(String payType, String biztype, String ua, String query) {
        return ctx(payType, biztype, ua, query, true);
    }

    private InvokeContext ctxNoMp(String payType, String biztype, String ua, String query) {
        return ctx(payType, biztype, ua, query, false);
    }

    private InvokeContext ctx(String payType, String biztype, String ua, String query, boolean withMp) {
        var order = OrderSnapshot.builder().tradeNo("T1").real(100L).type(payType).build();
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw(biztype, withMp)).build())
                .order(order)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").goodsName("元宝充值").build());
        if (ua != null || query != null) {
            var req = RequestSnapshot.builder();
            if (ua != null) req.ua(ua);
            if (query != null) req.query(query);
            b.request(req.build());
        }
        return b.build();
    }

    private static byte[] cfgRaw(String biztype, boolean withMp) {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "SM1");
            cfg.put("appuserid", "AU1");
            cfg.put("appmchid", "SUB1");
            cfg.put("appkey", TestKeys.PUBLIC_KEY);
            cfg.put("appsecret", TestKeys.PRIVATE_KEY);
            // biztype 缺失 = 未配置 → modeSet 回退默认
            if (biztype != null) cfg.put("biztype", biztype);
            if (withMp) cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    /** mockStatic 下 UA 判定走真实实现（否则默认 false 误判为桌面端） */
    private static void stubUa(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isWeChat(any())).thenCallRealMethod();
    }

    /** 按 URL 分发渠道响应（顺序消费） */
    private static void stubApi(MockedStatic<HttpHelper> mocked, HttpHelper.HttpResponse resp) {
        mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                .thenReturn(resp);
    }

    /** 按验签字段表签名渠道响应（商户私钥签名，插件用配置公钥验） */
    private static HttpHelper.HttpResponse signedResp(List<String> verifyKeys, Map<String, String> fields)
            throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("requestId", "T1");
        m.put("result", "00000");
        m.put("errorMsg", "");
        m.putAll(fields);
        m.put("signature", SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                SumapaySignUtil.concat(m, verifyKeys)));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }
}
