package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.joinpay.JoinpayPlugin;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.HandleOutcome;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.PageOutcome;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.joinpay.util.JoinpaySignUtil;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.ResultUnknownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JoinpayCreateHandler 公众号 OAuth 决策与下单应答映射。
 *
 * <p>公众号授权 URL 决策内置 SDK（已配置系统公众号 → 跳 /oauth/wxmp 包装的通道授权 URL，双 OAuth；
 * 未配置 → 直接通道授权 URL），buyer 由宿主/SDK 管理、插件不读不判；黑名单命中 → 跳失败页终止。
 * 建单应答只有「渠道可解析且明确拒绝/缺少支付数据」才是明确失败，其余一律结果未定。</p>
 */
@DisplayName("JoinpayCreateHandler 公众号 OAuth 决策")
class JoinpayCreateHandlerTest {

    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";

    @Test
    @DisplayName("已配置系统公众号 → 跳 /oauth/wxmp 包装的通道授权 URL（双 OAuth：先系统公众号存风控身份）")
    void unifiedOAuthWrapperWhenConfigured() throws Exception {
        // buyer 有无不参与决策：插件始终经 SDK 构建授权 URL，buyer 由宿主/SDK 管理
        var ctx = ctx(UA_WECHAT, null, true, cfgRaw("3"));

        var outcome = new JoinpayCreateHandler().wxpay(ctx);

        assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
        var url = ((PageOutcome.Redirect) outcome).url();
        // 首层入口基地址恒为平台网关域（系统公众号绑定的授权域名），非通道前端域
        assertThat(url).startsWith("https://gw.example.com/oauth/wxmp?redirect_uri=");
        // redirect_uri 是通道公众号授权 URL（第二层 OAuth 的发起方是通道自己的公众号）
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://open.weixin.qq.com/connect/oauth2/authorize")
                .contains("appid=wxMPAPP1")
                .contains("state=T1");
    }

    @Test
    @DisplayName("未配置系统公众号 → 直接通道公众号授权 URL（单层）")
    void channelOAuthWhenNotConfigured() throws Exception {
        var ctx = ctx(UA_WECHAT, null, false, cfgRaw("3"));

        var outcome = new JoinpayCreateHandler().wxpay(ctx);

        assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
        var url = ((PageOutcome.Redirect) outcome).url();
        assertThat(url).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
        assertThat(url).contains("appid=wxMPAPP1");
    }

    @Test
    @DisplayName("渠道 HTTP 层失败（502 nginx 错误页）：结果未知，抛 ResultUnknownException（不判失败）")
    void channelHttpFailureThrowsResultUnknown() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head><body></body></html>"
                                    .getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));

            // 渠道挂时是否已建单未知：不得构造明确失败
            assertThatThrownBy(() -> new JoinpayCreateHandler().alipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessage("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
        }
    }

    @Test
    @DisplayName("渠道 200 但响应体非 JSON（CDN 拦截页）：结果未知，抛 ResultUnknownException")
    void channelUnparseableBodyThrowsResultUnknown() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "<html>cloudflare 拦截页</html>".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));

            assertThatThrownBy(() -> new JoinpayCreateHandler().alipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessageContaining("HTTP Status Code:200");
        }
    }

    @Test
    @DisplayName("传输层失败（超时/断连）：结果未知，抛 ResultUnknownException")
    void transportFailureThrowsResultUnknown() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenThrow(new IOException("connect timed out"));

            assertThatThrownBy(() -> new JoinpayCreateHandler().alipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessage("接口通道服务异常，未返回预期响应！");
        }
    }

    @Test
    @DisplayName("微信授权回调（?code=）→ 兑换 openid → 建单返回支付页（不得回跳授权，线上无限循环形态）")
    void wechatCallbackQueryConsumesCode() throws Exception {
        // 微信 snsapi_base 回调把凭证放在 code（非 auth_code），state=订单号——与真实回调一致
        var ctx = ctx(UA_WECHAT, "code=WXCODE1&state=T1", false, cfgRaw("3"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("{\"appId\":\"wxMPAPP1\"}"));

            var outcome = new JoinpayCreateHandler().wxpay(ctx);

            // 回调 code 必须被消费：兑换请求 URL 带上它；未消费即走授权回跳分支
            mocked.verify(() -> HttpHelper.get(any(), contains("code=WXCODE1")), times(1));
            // 公众号建单返回 JSAPI 调起页：模板名 + js_api_parameters（渠道 rc_Result 解析后透传），
            // 缺模板名宿主会渲染成 {"type":"page","data":""}，调起参数全丢
            assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
            var page = (PageOutcome.Page) outcome;
            assertThat(page.page()).isEqualTo("wxpay_jspay");
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) page.data();
            assertThat(data).containsKey("js_api_parameters");
        }
    }

    @Test
    @DisplayName("渠道业务拒绝（ra_Code≠100）→ 返回失败页透传渠道原文（渠道可解析的明确拒绝）")
    void channelBizRejectionReturnsFailedPage() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=WXCODE2&state=T1", false, cfgRaw("3"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("10080061", "交易渠道为空,交易商户无可用通道", ""));

            var outcome = new JoinpayCreateHandler().wxpay(ctx);

            // 渠道明确拒绝 → 明确失败，文案原文透出（不包成插件内部错误）
            assertThat(outcome).isInstanceOf(PageOutcome.Failed.class);
            assertThat(((PageOutcome.Failed) outcome).failure().message())
                    .isEqualTo("[10080061]交易渠道为空,交易商户无可用通道");
        }
    }

    @Test
    @DisplayName("HTTP 400 但响应可解析且验签通过 → 按业务码判明确失败（不按 HTTP 状态过滤）")
    void http400ParseableBizRejectionStillFails() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp(400, "10080061", "交易渠道为空,交易商户无可用通道", ""));

            var outcome = new JoinpayCreateHandler().alipay(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Failed.class);
            assertThat(((PageOutcome.Failed) outcome).failure().message())
                    .isEqualTo("[10080061]交易渠道为空,交易商户无可用通道");
        }
    }

    @Test
    @DisplayName("下单成功但渠道未返回支付地址（rc_Result 空）→ 明确失败，不吐空页面")
    void successWithoutPayDataFailsExplicitly() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("100", "", ""));

            var outcome = new JoinpayCreateHandler().alipay(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Failed.class);
            assertThat(((PageOutcome.Failed) outcome).failure().message())
                    .isEqualTo("下单成功但未返回支付地址");
        }
    }

    @Test
    @DisplayName("支付宝扫码下单成功 → alipay_qrcode 页面（rc_Result 为二维码地址）")
    void alipayNativeReturnsQrPage() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("100", "", "https://qr.joinpay.com/abc"));

            var outcome = new JoinpayCreateHandler().alipay(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
            var page = (PageOutcome.Page) outcome;
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(page.url()).isEqualTo("https://qr.joinpay.com/abc");
        }
    }

    @Test
    @DisplayName("公众号回调命中买家黑名单 → 兑换内部自动抛异常 → 跳失败页终止（不再下单）")
    void mpCallbackBlockedByBlacklist() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=CODE1", false, cfgRaw("3"));
        var cb = ctx.getCallback();
        // 宿主回调：黑名单命中（recordOAuthIdentity=true，订单已推进 -3）
        when(cb.recordOAuthIdentity(any())).thenReturn(true);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_BLOCKED\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var outcome = new JoinpayCreateHandler().wxpay(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
            assertThat(((PageOutcome.Redirect) outcome).url())
                    .isEqualTo("https://pay.example.com/pay/result/T1");
        }
        // 命中后插件未做任何拦截判断——兑换方法内部已抛异常终止；被拦截：不发起支付、不写 ext
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).lockOrderExt(any());
    }

    // =========================================================================
    // 收银台（SDK 固定重入：ext 非空还原，ext 空重走 create）
    // =========================================================================

    @Test
    @DisplayName("收银台（桌面微信无 code）→ 扫码中转页（与建单判定同源，不请求渠道）")
    void cashierRebuildsRelayPageIdenticallyToCreate() {
        var createCtx = ctx(null, null, false, cfgRaw("3"));
        stubEmptyExt(createCtx);
        var createOutcome = new JoinpayCreateHandler().wxpay(createCtx);
        assertThat(createOutcome).isInstanceOf(PageOutcome.Page.class);
        assertThat(((PageOutcome.Page) createOutcome).page()).isEqualTo("wxpay_qrcode");

        var cashierCtx = ctx(null, null, false, cfgRaw("3"));
        cashierCtx.setFuncName("cashier");
        stubEmptyExt(cashierCtx);
        var cashierOutcome = new JoinpayPlugin().handle(cashierCtx);

        assertThat(cashierOutcome).isInstanceOf(PageOutcome.Page.class);
        var page = (PageOutcome.Page) cashierOutcome;
        assertThat(page.page()).isEqualTo("wxpay_qrcode");
        // 中转页地址 = /pay/cashier/{tradeNo} + 时间戳（建单与收银台同构）
        assertThat(page.url()).matches("https://pay\\.example\\.com/pay/cashier/T1\\?t=\\d+");
        verify(cashierCtx.getCallback(), never()).lockOrderExt(any());
        verify(cashierCtx.getCallback(), never()).completeBiz(any());
    }

    @Test
    @DisplayName("收银台（微信内公众号无 code）→ 与建单相同的授权跳转地址")
    void cashierRebuildsOAuthRedirectIdenticallyToCreate() {
        var createCtx = ctx(UA_WECHAT, null, true, cfgRaw("3"));
        stubEmptyExt(createCtx);
        var createUrl = redirectUrl(new JoinpayCreateHandler().wxpay(createCtx));

        var cashierCtx = ctx(UA_WECHAT, null, true, cfgRaw("3"));
        cashierCtx.setFuncName("cashier");
        stubEmptyExt(cashierCtx);
        var cashierOutcome = new JoinpayPlugin().handle(cashierCtx);

        assertThat(redirectUrl(cashierOutcome)).isEqualTo(createUrl);
    }

    @Test
    @DisplayName("收银台（ext 为空 + 公众号 OAuth 回跳 code）→ 继续建单，不得返回空结果")
    void cashierContinuesCreateAfterOAuthCallback() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=WXCODE1&state=T1", false, cfgRaw("3"));
        ctx.setFuncName("cashier");
        stubEmptyExt(ctx);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("{\"appId\":\"wxMPAPP1\"}"));

            var outcome = new JoinpayPlugin().handle(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
            var page = (PageOutcome.Page) outcome;
            assertThat(page.page()).isEqualTo("wxpay_jspay");
            mocked.verify(() -> HttpHelper.get(any(), contains("code=WXCODE1")), times(1));
        }
        // 线上 buyer_id 已落库即这一步成功：OAuth 身份必须被宿主消费后才继续建单
        verify(ctx.getCallback()).recordOAuthIdentity(any());
    }

    @Test
    @DisplayName("收银台（ext 为空 + 微信扫码直达建单）→ 重走 create 请求渠道并写 ext")
    void cashierBlankExtDirectModeReentersCreate() {
        var ctx = ctx(null, null, false, cfgRaw("1"));
        ctx.setFuncName("cashier");
        stubEmptyExt(ctx);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("https://qr.joinpay.com/abc"));

            var outcome = new JoinpayPlugin().handle(ctx);

            assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
            var page = (PageOutcome.Page) outcome;
            assertThat(page.page()).isEqualTo("wxpay_qrcode");
            assertThat(page.url()).isEqualTo("https://qr.joinpay.com/abc");
        }
        verify(ctx.getCallback(), atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("收银台（ext 已生成）→ 原样还原支付页，不再判定本地页")
    void cashierRestoresLockedPage() {
        var ctx = ctx(null, null, false, cfgRaw("3"));
        ctx.setFuncName("cashier");
        // 快照 ext 非空即进入还原分支；宿主的 lockOrderExt 探测已有 ext 时直接返回缓存
        ctx.getOrder().setExt("{\"kind\":\"page\",\"page\":\"wxpay_qrcode\",\"url\":\"weixin://wxpay/bizpayurl?pr=X\"}");
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().extData(
                Map.<String, Object>of("kind", "page", "page", "wxpay_qrcode", "url", "weixin://wxpay/bizpayurl?pr=X")).build());

        var outcome = new JoinpayPlugin().handle(ctx);

        assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
        var page = (PageOutcome.Page) outcome;
        assertThat(page.page()).isEqualTo("wxpay_qrcode");
        assertThat(page.url()).isEqualTo("weixin://wxpay/bizpayurl?pr=X");
        verify(ctx.getCallback()).lockOrderExt(any());
        verify(ctx.getCallback(), never()).completeBiz(any());
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /**
     * 订单尚无 ext 的宿主探测：必须用 thenAnswer 返回已有类型（LockExtResult），
     * 用 thenReturn 会绑定到泛型推断结果、传入值被丢弃。
     */
    private static void stubEmptyExt(InvokeContext ctx) {
        when(ctx.getCallback().lockOrderExt(any())).thenAnswer(inv -> LockExtResult.builder().build());
    }

    private static String redirectUrl(HandleOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
        return ((PageOutcome.Redirect) outcome).url();
    }

    /** 下单应答验签字段（与实现侧 PAY_RESP_FIELDS 同序：测试侧独立声明，实现侧改字段会在此暴露）。 */
    private static final List<String> PAY_RESP_VERIFY = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_Amount","r4_Cur","r5_Mp",
            "r6_FrpCode","r7_TrxNo","r8_MerchantBankCode","r9_SubMerchantNo",
            "ra_Code","rb_CodeMsg","rc_Result","rd_Pic");

    private static HttpHelper.HttpResponse signedPayResp(String rcResult) throws Exception {
        return signedPayResp("100", "", rcResult);
    }

    private static HttpHelper.HttpResponse signedPayResp(String raCode, String rbCodeMsg,
                                                         String rcResult) throws Exception {
        return signedPayResp(200, raCode, rbCodeMsg, rcResult);
    }

    private static HttpHelper.HttpResponse signedPayResp(int status, String raCode, String rbCodeMsg,
                                                         String rcResult) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.6");
        m.put("r2_OrderNo", "T1");
        m.put("ra_Code", raCode);
        m.put("rb_CodeMsg", rbCodeMsg);
        m.put("rc_Result", rcResult);
        m.put("hmac", JoinpaySignUtil.sign(m, PAY_RESP_VERIFY, "JPKEY1"));
        return new HttpHelper.HttpResponse(status, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }

    /**
     * 通道配置：微信只开公众号（biztype=3 → WEIXIN_GZH，不被小程序/微信H5/微信扫码分支截走）+ 公众号绑定。
     * 断言公众号链路的用例必须用它：开微信扫码（1）时 wxpay 走扫码而非公众号分支。
     */
    private static byte[] cfgRaw(String biztype) {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "JPAPP1");
            cfg.put("appkey", "JPKEY1");
            cfg.put("biztype", biztype);
            cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    private InvokeContext ctx(String ua, String query, boolean oauthWx, byte[] cfgRaw) {
        // 渠道请求（UA/query）只出现在 wxpay 链路：订单支付方式必须同为 wxpay
        var order = OrderSnapshot.builder().tradeNo("T1").real(100L).type("wxpay")
                .ip("1.2.3.4").build();
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(order)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .gatewayDomain("https://gw.example.com")
                        .oauthWxConfigured(oauthWx).build());
        if (ua != null || query != null) {
            var req = RequestSnapshot.builder();
            if (ua != null) req.ua(ua);
            if (query != null) req.query(query);
            b.request(req.build());
        }
        return b.build();
    }
}
