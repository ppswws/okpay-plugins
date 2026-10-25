package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.PaymentChannel;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.ExpectedFailureException;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.ResultUnknownException;
import com.okpay.plugin.sumapay.SumapayPlugin;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SumapayCreateHandler 下单矩阵：H5（scheme 提取/域名跳转）、聚合扫码（二维码图优先）、
 * 桌面端扫码中转、公众号 OAuth、未开启支付方式/渠道业务失败，以及 ext 缺失时的收银台本地页。
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
            var outcome = createAlipay(ctx);

            assertThat(redirectUrl(outcome)).isEqualTo(
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
            var outcome = createAlipay(ctx);

            assertThat(redirectUrl(outcome)).isEqualTo("https://www.sumapay.com/wap/pay?token=abc");
        }
    }

    @Test
    @DisplayName("支付宝H5：payUrl 不含丰付域名 → 扫码页兜底")
    void alipayH5FallbackQrcode() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "payUrl", "https://pay.example.com/qr/ABC")));
            var outcome = createAlipay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("alipay_qrcode");
            assertThat(pageOf(outcome).url()).isEqualTo("https://pay.example.com/qr/ABC");
        }
    }

    @Test
    @DisplayName("支付宝H5：参数错误码 2003 → 结果未定（ExpectedFailureException）")
    void alipayH5ChannelRejected() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of(
                    "result", "2003", "errorMsg", "参数错误", "payUrl", "")));

            assertThatThrownBy(() -> createAlipay(ctx))
                    .isInstanceOf(ExpectedFailureException.class)
                    .hasMessage("[2003]参数错误");
        }
    }

    @Test
    @DisplayName("支付宝H5：渠道成功但未返回 payUrl → 明确失败页")
    void alipayH5MissingPayUrl() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(ALIPAY_H5_VERIFY, Map.of("payUrl", "")));

            var outcome = createAlipay(ctx);
            assertThat(outcome).isInstanceOf(PageOutcome.Failed.class);
            assertThat(((PageOutcome.Failed) outcome).failure().message()).isEqualTo("渠道未返回支付地址");
        }
    }

    @Test
    @DisplayName("支付宝H5：渠道 HTTP 502 → ResultUnknownException（结果未知）")
    void alipayH5ChannelHttpFailure() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, new HttpHelper.HttpResponse(502, Map.of(),
                    "<html><head><title>502 Bad Gateway</title></head></html>"
                            .getBytes(StandardCharsets.UTF_8), "req", 10, 1));

            assertThatThrownBy(() -> createAlipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessageContaining("502");
        }
    }

    @Test
    @DisplayName("支付宝H5：响应不可解析（CDN 拦截页）→ ResultUnknownException（结果未知）")
    void alipayH5UnparseableBody() throws Exception {
        var ctx = ctx("alipay", "1", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, new HttpHelper.HttpResponse(200, Map.of(),
                    "<html>cloudflare 拦截页</html>".getBytes(StandardCharsets.UTF_8), "req", 10, 1));

            assertThatThrownBy(() -> createAlipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessageContaining("HTTP Status Code:200");
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
            var outcome = createAlipay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("alipay_qrcode");
            assertThat(pageOf(outcome).url()).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        }
    }

    @Test
    @DisplayName("聚合扫码（微信）：无二维码图 → 扫码地址页")
    void scanPayCodeUrl() throws Exception {
        var ctx = ctx("wxpay", "3", null, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubApi(mocked, signedResp(SCAN_VERIFY, Map.of(
                    "codeUrl", "https://qr.example.com/wx")));
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_qrcode");
            assertThat(pageOf(outcome).url()).isEqualTo("https://qr.example.com/wx");
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
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_h5");
            assertThat(pageOf(outcome).url()).isEqualTo("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1");
        }
    }

    @Test
    @DisplayName("微信H5（桌面端）：不建单，中转页扫码，重入后手机端发起")
    void wxH5DesktopRelay() throws Exception {
        var ctx = ctx("wxpay", "1", UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_qrcode");
            assertThat(pageOf(outcome).url()).startsWith("https://pay.example.com/pay/cashier/T1?t=");
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
            var outcome = createWxpay(ctx);

            var url = redirectUrl(outcome);
            assertThat(url).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
            assertThat(url).contains("appid=wxMPAPP1");
            assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
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
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_qrcode");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("公众号（携带微信回调 code）→ OAuth 换 openid → IOZ2011 建单 → wxpay_jspay 页带支付参数")
    void wxMpWithCode() throws Exception {
        // 微信 snsapi_base 回调参数名是 code，写错即线上无限跳授权
        var ctx = ctx("wxpay", "2", UA_WECHAT, "code=CODE1");
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
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_jspay");
            // 模板把 data.js_api_parameters 直接喂 WeixinJSBridge.invoke：必须是对象（渠道回的是 JSON 串）
            var params = HttpHelper.MAPPER.valueToTree(pageOf(outcome).data())
                    .path("js_api_parameters");
            assertThat(params.isObject()).isTrue();
            assertThat(params.path("appId").asString()).isEqualTo("wxMPAPP1");
        }
        // OAuth 身份经宿主落库风控（buyer + 黑名单）
        verify(cb).recordOAuthIdentity(any());
    }

    @Test
    @DisplayName("公众号未配置 → ExpectedFailureException 提示缺少公众号配置")
    void wxMpMissingConfig() {
        var ctx = ctxNoMp("wxpay", "2", UA_WECHAT, null);

        assertThatThrownBy(() -> createWxpay(ctx))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessage("缺少公众号配置");
    }

    // =========================================================================
    // 未开启支付方式
    // =========================================================================

    @Test
    @DisplayName("支付宝方式未开启（biztype 仅微信）→ ExpectedFailureException")
    void alipayNotEnabled() {
        var ctx = ctx("alipay", "3", null, null);

        assertThatThrownBy(() -> createAlipay(ctx))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessage("当前通道未开启支付宝支付方式");
    }

    @Test
    @DisplayName("微信方式未开启（biztype 空 → 默认 1）→ H5 正常，未配置公众号时不落公众号分支")
    void wxpayDefaultMode() throws Exception {
        var ctx = ctx("wxpay", null, UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var outcome = createWxpay(ctx);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_qrcode");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    // =========================================================================
    // 收银台（SDK 固定重入：ext 非空还原，ext 空重走 create）
    // =========================================================================

    @Test
    @DisplayName("收银台：订单 ext 为空 + 微信H5桌面端 → 重走 create，本地中转页不请求渠道")
    void cashierBlankExtDesktopRelay() {
        var ctx = ctx("wxpay", "1", UA_DESKTOP, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var outcome = (PageOutcome) new SumapayPlugin().handle(cashierCtx(ctx));

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_qrcode");
            assertThat(pageOf(outcome).url()).startsWith("https://pay.example.com/pay/cashier/T1?t=");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("收银台：订单 ext 为空 + 微信内公众号无 code → 重走 create，本地授权跳转")
    void cashierBlankExtMpAuth() {
        var ctx = ctx("wxpay", "2", UA_WECHAT, null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var outcome = (PageOutcome) new SumapayPlugin().handle(cashierCtx(ctx));

            assertThat(redirectUrl(outcome)).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("收银台：订单 ext 为空 + 手机端 H5 → 重走 create 请求渠道并写 ext，返回支付页")
    void cashierBlankExtMobileBuildsViaCreateFlow() throws Exception {
        var ctx = ctx("wxpay", "1", "Mozilla/5.0 (iPhone) Safari/604.1", null);
        stubHostExtStore(ctx.getCallback());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            stubApi(mocked, signedResp(WX_H5_VERIFY, Map.of(
                    "payUrl", "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1")));
            var outcome = (PageOutcome) new SumapayPlugin().handle(cashierCtx(ctx));

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_h5");
            assertThat(pageOf(outcome).url()).isEqualTo("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?x=1");
        }
    }

    @Test
    @DisplayName("收银台：ext 非空 → 直接还原已固化页面，不再重走 create")
    void cashierRestoresExtWithoutCreate() {
        var ctx = ctx("wxpay", "1", UA_DESKTOP, null);
        var cb = mock(HostCallback.class);
        var cashier = InvokeContext.builder()
                .requestId("r2")
                .funcName(PaymentChannel.CASHIER_FUNC)
                .channel(ctx.getChannel())
                .order(OrderSnapshot.builder().tradeNo("T1").type("wxpay")
                        .ext("{\"kind\":\"page\",\"page\":\"wxpay_h5\",\"url\":\"https://qr.example.com/wx\"}")
                        .build())
                .callback(cb)
                .config(ctx.getConfig())
                .build();
        when(cb.lockOrderExt(any()))
                .thenReturn(LockExtResult.builder()
                        .extData(Map.of("kind", "page", "page", "wxpay_h5", "url", "https://qr.example.com/wx"))
                        .build());
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            var outcome = (PageOutcome) new SumapayPlugin().handle(cashier);

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_h5");
            assertThat(pageOf(outcome).url()).isEqualTo("https://qr.example.com/wx");
            mocked.verify(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()),
                    never());
        }
    }

    @Test
    @DisplayName("收银台：公众号 OAuth 回跳 code → 重走 create 兑换 openid 并下单（中转链第二阶段）")
    void cashierOAuthCallbackContinuesCreate() throws Exception {
        var ctx = ctx("wxpay", "2", UA_WECHAT, "code=CODE1");
        var cb = ctx.getCallback();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubUa(mocked);
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID1\"}".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            stubApi(mocked, signedResp(JSAPI_VERIFY, Map.of(
                    "pay_info", "{\"appId\":\"wxMPAPP1\",\"timeStamp\":\"1700000000\"}")));

            var outcome = (PageOutcome) new SumapayPlugin().handle(cashierCtx(ctx));

            assertThat(pageOf(outcome).page()).isEqualTo("wxpay_jspay");
        }
        verify(cb).recordOAuthIdentity(any());
    }

    /** 模拟宿主 lockOrderExt：首次探测空结果；带 extRaw 写入后回读返回该 ext */
    private static void stubHostExtStore(HostCallback cb) throws Exception {
        var store = new java.util.HashMap<String, Map<String, Object>>();
        when(cb.lockOrderExt(any())).thenAnswer(inv -> {
            var req = (LockExtRequest) inv.getArgument(0);
            var cached = store.get(req.getTradeNo());
            if (cached != null) return new LockExtResult(cached, null);
            if (req.getExtRaw() != null) {
                Map<String, Object> written = HttpHelper.MAPPER.readValue(req.getExtRaw(), EXT_MAP);
                store.put(req.getTradeNo(), written);
            }
            return LockExtResult.builder().build();
        });
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private static final List<String> ALIPAY_H5_VERIFY = List.of("requestId", "result", "payUrl", "errorMsg");
    private static final List<String> WX_H5_VERIFY = List.of("requestId", "result", "payUrl", "mpAppId", "mpPath", "errorMsg");
    private static final List<String> SCAN_VERIFY = List.of("requestId", "result", "codeUrl", "codeImgString", "errorMsg");
    private static final List<String> JSAPI_VERIFY = List.of("requestId", "result", "passThrough", "pay_info");
    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> EXT_MAP =
            new tools.jackson.core.type.TypeReference<>() {};

    private static PageOutcome createAlipay(InvokeContext ctx) {
        return new SumapayCreateHandler().alipay(ctx);
    }

    private static PageOutcome createWxpay(InvokeContext ctx) {
        return new SumapayCreateHandler().wxpay(ctx);
    }

    private static PageOutcome.Page pageOf(PageOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
        return (PageOutcome.Page) outcome;
    }

    private static String redirectUrl(PageOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
        return ((PageOutcome.Redirect) outcome).url();
    }

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
                .callback(callback())
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

    /** 收银台重入：订单无 ext，UA/query 同创建阶段 */
    private static InvokeContext cashierCtx(InvokeContext createCtx) {
        var order = OrderSnapshot.builder()
                .tradeNo("T1").real(100L).type(createCtx.getOrder().getType()).build();
        return InvokeContext.builder()
                .requestId("r2")
                .funcName(PaymentChannel.CASHIER_FUNC)
                .channel(createCtx.getChannel())
                .order(order)
                .callback(createCtx.getCallback())
                .config(createCtx.getConfig())
                .request(createCtx.getRequest())
                .build();
    }

    private static HostCallback callback() {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        return cb;
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

    /** 固定返回渠道响应 */
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
