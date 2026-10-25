package com.okpay.plugin.alipay.handler;

import tools.jackson.core.type.TypeReference;
import com.okpay.plugin.HostCallback;
import com.okpay.plugin.PaymentChannel;
import com.okpay.plugin.alipay.AlipayPlugin;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.PageOutcome;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.sdk.ExpectedFailureException;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import com.okpay.plugin.sdk.ResultUnknownException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付方式 UA 自动分配与各模式下单（真实密钥 + 真实签名/验签：仅 HTTP 被
 * {@code HttpHelper} 静态 mock 截断，请求参数与响应验签均走真实 SDK 路径）。
 */
@DisplayName("AlipayCreateHandler 支付下单")
class AlipayCreateHandlerTest {

    private static final String APP_ID = "2021000000000001";
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

    /**
     * 全参数 ctx：支付方式（biztype_alipay 值，null 用默认）、UA、query、
     * 是否配置全局小程序应用（oauth_mini，含站点域名）、订单 buyer。
     */
    private InvokeContext ctx(long real, String biztype, String ua, String query,
                              boolean miniConfigured, String buyer, HostCallback cb) throws Exception {
        var cfgMap = new LinkedHashMap<String, Object>();
        cfgMap.put("appid", APP_ID);
        cfgMap.put("appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfgMap.put("appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded()));
        if (biztype != null) cfgMap.put("biztype", biztype);
        var order = OrderSnapshot.builder().tradeNo("T1").real(real).type("alipay").build();
        if (buyer != null) order.setBuyer(buyer);
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(HttpHelper.MAPPER.writeValueAsBytes(cfgMap)).build())
                .order(order)
                .callback(cb);
        if (ua != null || query != null) {
            var req = RequestSnapshot.builder();
            if (ua != null) req.ua(ua);
            if (query != null) req.query(query);
            b.request(req.build());
        }
        // 真实宿主 buildPluginConfig 永远给出 config 快照（未配置即标记 false）
        b.config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                .gatewayDomain("https://gw.example.com")
                .oauthAlipayMiniConfigured(miniConfigured).build());
        return b.build();
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 模拟支付宝 App 侧：orderSuffix 是双层 urlencode，解码一层还原唤起 orderStr */
    private static String orderStrFrom(String schemeUrl) throws Exception {
        var decoded = URLDecoder.decode(schemeUrl, StandardCharsets.UTF_8);
        var suffix = decoded.substring(decoded.indexOf("orderSuffix=") + 12);
        suffix = suffix.substring(0, suffix.indexOf("#Intent"));
        return URLDecoder.decode(suffix, StandardCharsets.UTF_8);
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

    private static PageOutcome.Page pageOutcome(PageOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Page.class);
        return (PageOutcome.Page) outcome;
    }

    private static String redirectUrl(PageOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Redirect.class);
        return ((PageOutcome.Redirect) outcome).url();
    }

    private static String htmlOf(PageOutcome outcome) {
        assertThat(outcome).isInstanceOf(PageOutcome.Html.class);
        return ((PageOutcome.Html) outcome).html();
    }

    // =========================================================================
    // 本地收银台（ext 为空时重建本地页面）
    // =========================================================================

    @Test
    @DisplayName("收银台：ext 已生成 → 直接还原渠道支付页（不再重建/请求渠道）")
    void cashierRestoresPersistedOutcome() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_PC, null, false, null, cb);
        ctx.setFuncName(PaymentChannel.CASHIER_FUNC);
        // ext 已生成：订单快照带 ext，宿主锁返回缓存载荷
        ctx.getOrder().setExt("{\"kind\":\"page\"}");
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder()
                .extData(Map.of("kind", "page", "page", "alipay_qrcode", "url", "https://qr.alipay/T1"))
                .build());

        var page = pageOutcome((PageOutcome) new AlipayPlugin().handle(ctx));

        assertThat(page.page()).isEqualTo("alipay_qrcode");
        assertThat(page.url()).isEqualTo("https://qr.alipay/T1");
    }

    @Test
    @DisplayName("收银台：ext 为空（OAuth 中转路径）→ 重走 create，返回与建单一致的授权跳转")
    void cashierRebuildsOAuthRedirectWhenExtBlank() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4", UA_ALIPAY, null, false, null, cb);

        var outer = redirectUrl(new AlipayCreateHandler().alipay(ctx));
        ctx.setFuncName(PaymentChannel.CASHIER_FUNC);
        var rebuilt = redirectUrl((PageOutcome) new AlipayPlugin().handle(ctx));

        assertThat(rebuilt).isEqualTo(outer);
        assertThat(URLDecoder.decode(rebuilt, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("收银台：OAuth 回跳 auth_code → 重走 create 兑换身份并下单（中转链第二阶段）")
    void cashierOAuthCallbackContinuesCreate() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, cb);
        ctx.setFuncName(PaymentChannel.CASHIER_FUNC);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1);
                }
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });

            assertThat(pageOutcome((PageOutcome) new AlipayPlugin().handle(ctx)).page())
                    .isEqualTo("alipay_jspay");
        }
        verify(cb).recordOAuthIdentity(any());
    }

    private static final String UA_ALIPAY = "Mozilla/5.0 (iPhone) Mobile AlipayClient/10.0.1";
    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /**
     * mockStatic(HttpHelper) 会连 UA 判定（isAlipay/isMobile/isWeChat）一起拦截，
     * 未 stub 时全 false → 支付方式错配到 QRCODE；此处放行走真实实现。
     */
    private static void letRealUaChecks(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isAlipay(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isWeChat(any())).thenCallRealMethod();
    }

    @Test
    @DisplayName("渠道 HTTP 层失败（502 nginx 错误页）：结果未知，抛 ResultUnknownException（宿主 S_WAIT）")
    void channelHttpFailureThrowsResultUnknown() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        // PC + 当面付扫码（3）：QRCODE 走 Precreate.execute → 真实 HTTP 调用
        var ctx = ctx(100L, "3", UA_PC, null, false, null, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head><body></body></html>"
                                    .getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            // 渠道挂是渠道问题：不得构造明确拒绝（否则订单被关成失败）
            assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                    .isInstanceOf(ResultUnknownException.class)
                    .hasMessageContaining("502");
        }
    }

    @Test
    @DisplayName("JSPAY：全局未配置 → 插件直调 SDK 用通道应用 OAuth（跳授权页，redirect 回本站收 auth_code），不写 ext")
    void jsPayChannelOAuthWhenGlobalUnconfigured() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, false, null, cb);

        var url = redirectUrl(new AlipayCreateHandler().alipay(ctx));

        // 通道应用授权页（is_prod 缺省正式环境）；redirect_uri 回宿主本地收银台 /pay/cashier/{tradeNo}
        var decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=" + APP_ID)
                .contains("scope=auth_base")
                .contains("state=T1")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
        // 契约：OAuth 跳转发生在 lockCreate 之外，绝不写 ext（否则回跳后被缓存短路成死循环）
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("JSPAY：通道 OAuth 回调（auth_code）→ SDK 兑换 user_id → 内部自动风控 → trade.create buyer_id")
    void jsPayChannelOAuthCallbackExchangesAndCreates() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, cb);
        var reqBody = new AtomicReference<String>();

        // 真实 OAuthHelper 兑换（真实 RSA 签名 + 响应解析）：自动风控落库 buyer 由 SDK 内部回调宿主，插件无显式调用
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1);
                }
                reqBody.set(body);
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome(new AlipayCreateHandler().alipay(ctx)).page()).isEqualTo("alipay_jspay");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("out_trade_no", "T1")
                .containsEntry("buyer_id", "2088123456789012");
        // 风控内置：兑换成功即自动经宿主回调落库 buyer + 黑名单（SDK 内部闭环，插件无跳过路径）
        verify(cb).recordOAuthIdentity(any());
        // 下单走 lockCreate（lockOrderExt 锁内可多次调用），创建交易不算完成交易
        verify(cb, atLeastOnce()).lockOrderExt(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("JSPAY：通道回调命中买家黑名单 → 兑换内部自动抛异常 → 直接跳失败页终止（不再下单）")
    void jsPayChannelOAuthBlockedByBlacklist() throws Exception {
        var cb = mock(HostCallback.class);
        // 宿主回调：黑名单命中（recordOAuthIdentity=true，订单已推进 -3）
        when(cb.recordOAuthIdentity(any())).thenReturn(true);
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1));
            assertThat(redirectUrl(new AlipayCreateHandler().alipay(ctx)))
                    .isEqualTo("https://pay.example.com/pay/result/T1");
        }
        // 命中后插件未做任何拦截判断——兑换方法内部已抛异常终止；被拦截：不发起支付、不写 ext
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).lockOrderExt(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("JSPAY：buyer 2088 开头 → trade.create 无 product_code + buyer_id → alipay_jspay")
    void jsPayWithUserIdBuyer() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, true, "2088123456789012", cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2));
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome(new AlipayCreateHandler().alipay(ctx)).page()).isEqualTo("alipay_jspay");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("out_trade_no", "T1")
                .containsEntry("total_amount", "1.00")
                .containsEntry("buyer_id", "2088123456789012")
                .doesNotContainKey("product_code");
        // 完成交易不在此处：等支付完成通知，仅创建交易
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("JSPAY：buyer 非 2088 → buyer_open_id")
    void jsPayWithOpenIdBuyer() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, true, "OU_OPEN_ID_1", cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2));
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            new AlipayCreateHandler().alipay(ctx);
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("buyer_open_id", "OU_OPEN_ID_1")
                .doesNotContainKey("buyer_id");
    }

    @Test
    @DisplayName("JSAPI：JSAPI_PAY + op_app_id + buyer_open_id → alipay_jspay")
    void jsApiPay() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, null, true, "OU_OPEN_ID_1", cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2));
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome(new AlipayCreateHandler().alipay(ctx)).page()).isEqualTo("alipay_jspay");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", APP_ID)
                .containsEntry("buyer_open_id", "OU_OPEN_ID_1");
    }

    @Test
    @DisplayName("JSAPI：宿主回跳携带 op_app_id（全局小程序应用）→ op_app_id 用携带值而非通道应用（open_id 同源）")
    void jsApiUsesCarriedOpAppIdFromHostOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "oauth_done=1&op_app_id=GLOBAL_MINI_APP",
                true, "OU_GLOBAL_MINI_1", cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2));
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome(new AlipayCreateHandler().alipay(ctx)).page()).isEqualTo("alipay_jspay");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", "GLOBAL_MINI_APP")
                .containsEntry("buyer_open_id", "OU_GLOBAL_MINI_1");
    }

    @Test
    @DisplayName("JSAPI：无 buyer 且小程序全局应用已配置 → 跳宿主 /oauth/alimini（独立键，不经网页入口）")
    void jsApiJumpsToMiniOAuthWhenNoBuyer() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, null, true, null, cb);

        var url = redirectUrl(new AlipayCreateHandler().alipay(ctx));

        // 首层入口基地址恒为平台网关域；内层 redirect_uri 回本次支付链路域下的收银台页
        assertThat(url).startsWith("https://gw.example.com/oauth/alimini?redirect_uri=");
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1")
                .contains("state=T1");
        verify(cb, never()).completeBiz(any());
        // 契约：OAuth 跳转发生在 lockCreate 之外，绝不写 ext（否则回跳后被缓存短路成死循环）
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("JSAPI：无 buyer 且小程序全局未配置 → 通道 OAuth 兜底（直调 SDK 跳授权页），不写 ext")
    void jsApiChannelOAuthWhenMiniUnconfigured() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, null, false, null, cb);

        var decoded = URLDecoder.decode(redirectUrl(new AlipayCreateHandler().alipay(ctx)), StandardCharsets.UTF_8);

        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=" + APP_ID)
                .contains("state=T1")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("JSAPI：通道 OAuth 回调（全局未配置）→ 兑换 open_id → 内部自动风控 → trade.create buyer_open_id + op_app_id=通道应用")
    void jsApiChannelOAuthCallbackExchangesAndCreates() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "auth_code=AC1", false, null, cb);
        var reqBody = new AtomicReference<String>();

        // 真实 OAuthHelper 兑换：验证 identity=open_id 请求与自动风控落库 buyer
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU_CHANNEL_1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1);
                }
                reqBody.set(body);
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_create_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_NO\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome(new AlipayCreateHandler().alipay(ctx)).page()).isEqualTo("alipay_jspay");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", APP_ID)
                .containsEntry("buyer_open_id", "OU_CHANNEL_1");
        // 风控内置：兑换成功即自动经宿主回调落库 buyer + 黑名单（SDK 内部闭环）
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("JSAPI：oauth_done 回跳后 buyer 仍空（mini 全局已配置）→ 获取失败即失败（不降级，结果未知不构造明确拒绝）")
    void jsApiFailsAfterOauthDone() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "oauth_done=1", true, null, cb);

        assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessageContaining("获取支付宝身份失败");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("APP：sdkExecute 唤起串 → alipays scheme → alipay_h5 页面（复用现成模板）")
    void appPayRendersAlipayH5() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        // PC 开 6：配置兜底 → APP
        var ctx = ctx(100L, "5", UA_PC, null, false, null, cb);

        var resp = pageOutcome(new AlipayCreateHandler().alipay(ctx));

        assertThat(resp.page()).isEqualTo("alipay_h5");
        var data = (Map<?, ?>) resp.data();
        var url = data.get("url").toString();
        assertThat(url).startsWith("alipays://platformapi/startApp?appId=20000125&orderSuffix=");
        // scheme 内嵌唤起串（App 解码 orderSuffix 还原）：APP 支付参数齐全
        var orderStr = orderStrFrom(url);
        assertThat(orderStr).contains("method=alipay.trade.app.pay");
        var biz = parseBizContent(orderStr);
        assertThat(biz).containsEntry("out_trade_no", "T1")
                .containsEntry("total_amount", "1.00")
                .containsEntry("product_code", "QUICK_MSECURITY_PAY");
        // 契约：唤起页载荷在 lockCreate 之外构建，不写 ext
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("biztype 键（前端合并键名）+ PC → 电脑网站表单")
    void biztypeKeyWithPage() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "1", UA_PC, null, false, null, cb);

        var html = htmlOf(new AlipayCreateHandler().alipay(ctx));

        assertThat(html).contains("punchout_form").contains("alipay.trade.page.pay");
        // 渠道同步回跳统一收口：return_url 指向结果页，不直传商户 return_url
        assertThat(html).contains("return_url").contains("/pay/result/T1");
    }
}
