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
        return ctx(real, biztype, ua, query, miniConfigured, buyer, false, cb, Map.of());
    }

    /** 同上，forceIdentity = 支付前强制获取用户标识开关 */
    private InvokeContext ctx(long real, String biztype, String ua, String query,
                              boolean miniConfigured, String buyer, boolean forceIdentity,
                              HostCallback cb) throws Exception {
        return ctx(real, biztype, ua, query, miniConfigured, buyer, forceIdentity, cb, Map.of());
    }

    /** 同上，extraCfg 追加通道配置字段（如 seller_id / appmchid / auth_token）。 */
    private InvokeContext ctx(long real, String biztype, String ua, String query,
                              boolean miniConfigured, String buyer, boolean forceIdentity,
                              HostCallback cb, Map<String, ?> extraCfg) throws Exception {
        return ctx(real, biztype, ua, query, miniConfigured, buyer, forceIdentity, cb, extraCfg, false);
    }

    /** 同上，webConfigured = 平台网页应用统一 OAuth 是否已配置。 */
    private InvokeContext ctx(long real, String biztype, String ua, String query,
                              boolean miniConfigured, String buyer, boolean forceIdentity,
                              HostCallback cb, Map<String, ?> extraCfg, boolean webConfigured) throws Exception {
        var cfgMap = new LinkedHashMap<String, Object>();
        cfgMap.put("appid", APP_ID);
        cfgMap.put("appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfgMap.put("appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded()));
        if (biztype != null) cfgMap.put("biztype", biztype);
        if (forceIdentity) cfgMap.put("force_identity", true);
        cfgMap.putAll(extraCfg);
        var order = OrderSnapshot.builder().tradeNo("T1").real(real).type("alipay")
                .ip("1.2.3.4").build();
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
        var config = ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                .gatewayDomain("https://gw.example.com")
                .oauthAlipayMiniConfigured(miniConfigured)
                .oauthAlipayWebConfigured(webConfigured);
        if (miniConfigured) {
            config.oauthAlipayMiniAppId("MINI_APP")
                    .oauthAlipayMiniPrivateKey(pem("PRIVATE KEY", KP.getPrivate().getEncoded()))
                    .oauthAlipayMiniProd(true);
        }
        if (webConfigured) {
            config.oauthAlipayWebAppId("WEB_APP")
                    .oauthAlipayWebPrivateKey(pem("PRIVATE KEY", KP.getPrivate().getEncoded()))
                    .oauthAlipayWebProd(true);
        }
        b.config(config.build());
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
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Mobile";

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
    @DisplayName("当面付扫码（3）：precreate 不传 product_code，business_params 为对象（不是 JSON 串）")
    void qrcodeF2FPrecreateShape() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "3", UA_PC, null, false, null, cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2).toString());
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_qrcode");
        }

        var biz = parseBizContent(reqBody.get());
        // 当面付只签约 FACE_TO_FACE_PAYMENT：传 QR_CODE_OFFLINE 会被渠道判参数无效
        assertThat(biz).doesNotContainKey("product_code")
                .doesNotContainKey("seller_id")
                // BusinessParams 必须是对象；二次字符串化会报「参数无效：参数格式错误」
                .containsEntry("business_params", Map.of("mc_create_trade_ip", "1.2.3.4"));
    }

    @Test
    @DisplayName("订单码（7）：precreate 带 product_code=QR_CODE_OFFLINE")
    void qrcodeOrderCodePrecreateShape() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "7", UA_PC, null, false, null, cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2).toString());
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_qrcode");
        }

        assertThat(parseBizContent(reqBody.get()))
                .containsEntry("product_code", "QR_CODE_OFFLINE");
    }

    @Test
    @DisplayName("只开当面付JS + PC：返回本地中转码（授权地址），不调用未启用的 precreate、不写 ext")
    void jsPayOnlyOnPcUsesLocalRelay() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4", UA_PC, null, false, null, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(URLDecoder.decode(page.url(), StandardCharsets.UTF_8))
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("app_id=" + APP_ID)
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("只开当面付JS + 手机H5 + PC：中转码指向收银台（落地走 Wap），不编授权地址")
    void jsPayRelayWithWapUsesCashierUrl() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4,2", UA_PC, null, false, null, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(page.url()).isEqualTo("https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 扫码 + 桌面：返回授权地址二维码，不调渠道、不写 ext")
    void forceIdentityQrcodeDesktopShowsOauthRelay() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_PC, null, false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(URLDecoder.decode(page.url(), StandardCharsets.UTF_8))
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 扫码 + 商户 API 建单（无请求）：返回本地中转页，不调渠道")
    void forceIdentityQrcodeApiCreateReturnsLocalPage() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", null, null, false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(URLDecoder.decode(page.url(), StandardCharsets.UTF_8))
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 扫码 + 支付宝内无 buyer：跳授权页，不调渠道")
    void forceIdentityQrcodeAlipayRedirectsToOauth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_ALIPAY, null, false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var decoded = URLDecoder.decode(redirectUrl(new AlipayCreateHandler().alipay(ctx)),
                    StandardCharsets.UTF_8);
            assertThat(decoded)
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 扫码 + 支付宝回调：兑换 user_id + 风控 → precreate → 二维码页")
    void forceIdentityQrcodeCallbackExchangesThenPrecreate() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "3", UA_ALIPAY, "auth_code=AC1", false, null, true, cb);
        var precreateBody = new AtomicReference<String>();

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
                precreateBody.set(body);
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(page.url()).isEqualTo("https://qr.alipay/T1");
        }

        assertThat(parseBizContent(precreateBody.get())).containsEntry("out_trade_no", "T1");
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("门禁开启 + 扫码 + 黑名单：兑换内部拦截 → 失败页，不 precreate")
    void forceIdentityQrcodeBlacklistBlocksBeforeChannel() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.recordOAuthIdentity(any())).thenReturn(true);
        var ctx = ctx(100L, "3", UA_ALIPAY, "auth_code=AC1", false, null, true, cb);

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
        verify(cb, never()).lockOrderExt(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("门禁开启 + 手机H5 + 普通手机浏览器：返回授权地址中转页，不调渠道")
    void forceIdentityWapMobileBrowserShowsOauthRelay() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "2", UA_MOBILE, null, false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            assertThat(URLDecoder.decode(page.url(), StandardCharsets.UTF_8))
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 手机H5 + 支付宝内无 buyer：跳授权页，不调渠道")
    void forceIdentityWapAlipayRedirectsToOauth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "2", UA_ALIPAY, null, false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var decoded = URLDecoder.decode(redirectUrl(new AlipayCreateHandler().alipay(ctx)),
                    StandardCharsets.UTF_8);
            assertThat(decoded)
                    .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                    .contains("redirect_uri=https://pay.example.com/pay/cashier/T1");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁开启 + 手机H5 + 支付宝回调：兑换后生成 wap.pay 表单")
    void forceIdentityWapCallbackExchangesThenWap() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "2", UA_ALIPAY, "auth_code=AC1", false, null, true, cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1));
            var html = htmlOf(new AlipayCreateHandler().alipay(ctx));
            assertThat(html).contains("alipay.trade.wap.pay");
        }
        verify(cb).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("手机H5 关闭门禁：直接生成 wap.pay 表单，不走 OAuth")
    void wapWithoutGateSkipsOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "2", UA_ALIPAY, null, false, null, cb);

        var html = htmlOf(new AlipayCreateHandler().alipay(ctx));

        assertThat(html).contains("alipay.trade.wap.pay");
        verify(cb, never()).recordOAuthIdentity(any());
        verify(cb, atLeastOnce()).lockOrderExt(any());
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
                .doesNotContainKey("product_code")
                .doesNotContainKey("seller_id");
        // 完成交易不在此处：等支付完成通知，仅创建交易
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("HTTP 400 但响应可解析且签名通过 → Pages.fail（不按 HTTP 状态过滤）")
    void http400ParseableBizRejectionFails() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, true, "2088123456789012", cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(400, Map.of(),
                            signedResponse("alipay_trade_create_response",
                                    "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                            + "\"sub_code\":\"ACQ.TRADE_HAS_CLOSE\",\"sub_msg\":\"交易已关闭\"}"),
                            "req", 10, 1));

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp).isInstanceOf(PageOutcome.Failed.class);
            assertThat(((PageOutcome.Failed) resp).failure().message()).isEqualTo("交易已关闭");
        }
    }

    @Test
    @DisplayName("JSPAY：buyer 非 2088（open_id）→ 失败终止，不发 buyer_open_id")
    void jsPayWithOpenIdBuyerFailsClosed() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, true, "OU_OPEN_ID_1", cb);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                    .isInstanceOf(ExpectedFailureException.class)
                    .hasMessageContaining("需要 user_id");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("JSAPI：全局小程序 buyer_open_id → op_app_id=全局应用 + op_buyer_open_id")
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
                .containsEntry("op_app_id", "MINI_APP")
                .containsEntry("op_buyer_open_id", "OU_OPEN_ID_1")
                .doesNotContainKey("buyer_open_id");
    }

    @Test
    @DisplayName("JSAPI：buyer 2088 开头 → buyer_id（不传 buyer_open_id），op_app_id 仍带")
    void jsApiWithUserIdBuyer() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, null, true, "2088123456789012", cb);
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
                .containsEntry("op_app_id", "MINI_APP")
                .containsEntry("buyer_id", "2088123456789012")
                .doesNotContainKey("buyer_open_id");
    }

    @Test
    @DisplayName("JSAPI：缺少 auth_code → 失败（小程序端必须先 my.getAuthCode）")
    void jsApiWithoutAuthCodeFails() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "6", UA_ALIPAY, null, true, null, cb);

        assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessageContaining("缺少auth_code");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("JSAPI：全局小程序 auth_code → SDK 用全局应用兑换 → op_app_id=全局应用 + buyer_id")
    void jsApiGlobalMiniOAuthPrefersUserId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "auth_code=AC1", true, null, false, cb, Map.of(), true);
        var tokenBody = new AtomicReference<String>();
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    tokenBody.set(body);
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU_MINI_1\",\"access_token\":\"tok\"}"),
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

        assertThat(tokenBody.get()).contains("app_id=MINI_APP");
        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", "MINI_APP")
                .containsEntry("buyer_id", "2088123456789012")
                .doesNotContainKey("op_buyer_open_id");
    }

    @Test
    @DisplayName("JSAPI：全局小程序 auth_code 只有 open_id → op_app_id=全局应用 + op_buyer_open_id")
    void jsApiGlobalMiniOAuthFallsBackToOpenId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "auth_code=AC1", true, null, false, cb, Map.of(), false);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\","
                                            + "\"open_id\":\"OU_MINI_1\",\"access_token\":\"tok\"}"),
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
                .containsEntry("op_app_id", "MINI_APP")
                .containsEntry("op_buyer_open_id", "OU_MINI_1")
                .doesNotContainKey("buyer_id");
    }

    @Test
    @DisplayName("JSAPI：仅配置全局网页应用也不影响通道回调（无全局小程序）→ buyer_id + op_app_id=通道应用")
    void jsApiChannelOAuthPrefersUserIdAndCreates() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "auth_code=AC1", false, null, false, cb, Map.of(), true);
        var reqBody = new AtomicReference<String>();

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
                .containsEntry("buyer_id", "2088123456789012")
                .doesNotContainKey("buyer_open_id");
        // 风控内置：兑换成功即自动经宿主回调落库 buyer + 黑名单（SDK 内部闭环）
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("JSAPI：通道 OAuth 回调（全局未配置）→ user_id 缺失 → open_id 兜底 → buyer_open_id + op_app_id=通道应用")
    void jsApiChannelOAuthFallsBackToOpenId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, "auth_code=AC1", false, null, cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\","
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
                .containsEntry("buyer_open_id", "OU_CHANNEL_1")
                .doesNotContainKey("buyer_id");
        verify(cb).recordOAuthIdentity(any());
    }

    @Test
    @DisplayName("JSPAY：通道 OAuth 回调 user_id 缺失 → open_id 兜底 → buyer_open_id（本应用 open_id 同源）")
    void jsPayChannelOAuthOpenIdUsesBuyerOpenId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, cb);
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\","
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
        assertThat(biz).containsEntry("buyer_open_id", "OU_CHANNEL_1")
                .doesNotContainKey("buyer_id");
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

    // =========================================================================
    // 指定收款账号 UID（seller_id）
    // =========================================================================

    @Test
    @DisplayName("当面付扫码：配置 seller_id → precreate 携带")
    void precreateCarriesSellerId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "3", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012"));
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2).toString());
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_qrcode");
        }

        assertThat(parseBizContent(reqBody.get()))
                .containsEntry("seller_id", "2088123456789012");
    }

    @Test
    @DisplayName("当面付JS：配置 seller_id → trade.create 携带（buyer_id 与 seller_id 互不干扰）")
    void jsPayCarriesSellerId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, null, false, "2088000000000001", false, cb,
                Map.of("seller_id", "2088123456789012"));
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
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_jspay");
        }

        assertThat(parseBizContent(reqBody.get()))
                .containsEntry("seller_id", "2088123456789012")
                .containsEntry("buyer_id", "2088000000000001");
    }

    @Test
    @DisplayName("JSAPI：配置 seller_id → trade.create 携带")
    void jsApiCarriesSellerId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "6", UA_ALIPAY, null, true, "OU_OPEN_ID_1", false, cb,
                Map.of("seller_id", "2088123456789012"));
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
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_jspay");
        }

        assertThat(parseBizContent(reqBody.get()))
                .containsEntry("seller_id", "2088123456789012")
                .containsEntry("product_code", "JSAPI_PAY");
    }

    @Test
    @DisplayName("直付通（appmchid 非空）：不传 seller_id，改挂 sub_merchant")
    void zhifutongSuppressesSellerId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "3", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012", "appmchid", "S1"));
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2).toString());
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_qrcode");
        }

        var biz = parseBizContent(reqBody.get());
        assertThat(biz).doesNotContainKey("seller_id")
                .containsEntry("sub_merchant", Map.of("merchant_id", "S1"));
    }

    @Test
    @DisplayName("服务商（auth_token 非空）：不传 seller_id")
    void isvSuppressesSellerId() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "3", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012", "auth_token", "TOKEN"));
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                reqBody.set(inv.getArgument(2).toString());
                return new HttpHelper.HttpResponse(200, Map.of(),
                        signedResponse("alipay_trade_precreate_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"qr_code\":\"https://qr.alipay/T1\"}"),
                        "req", 10, 1);
            });
            assertThat(pageOutcome((PageOutcome) new AlipayCreateHandler().alipay(ctx)).page())
                    .isEqualTo("alipay_qrcode");
        }

        assertThat(parseBizContent(reqBody.get())).doesNotContainKey("seller_id");
    }

    @Test
    @DisplayName("配置 seller_id + Page/Wap/APP：明确失败，不请求渠道（防静默进主号）")
    void sellerIdUnsupportedMethodsFailFast() throws Exception {
        for (var tc : Map.of("1", UA_PC, "2", UA_MOBILE, "5", UA_PC).entrySet()) {
            var cb = mock(HostCallback.class);
            var ctx = ctx(100L, tc.getKey(), tc.getValue(), null, false, null, false, cb,
                    Map.of("seller_id", "2088123456789012"));

            try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
                letRealUaChecks(mocked);
                assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                        .as("biztype=%s", tc.getKey())
                        .isInstanceOf(ExpectedFailureException.class)
                        .hasMessageContaining("收款账号 UID");
                mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            }
            verify(cb, never()).lockOrderExt(any());
        }
    }

    @Test
    @DisplayName("收银台重建：配置 seller_id + Page → 明确失败（不返回页面）")
    void localPageSellerIdUnsupportedFailsFast() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "1", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012"));

        assertThatThrownBy(() -> new AlipayCreateHandler().localPage(ctx))
                .isInstanceOf(ExpectedFailureException.class)
                .hasMessageContaining("收款账号 UID");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("配置 seller_id + 只开当面付JS + PC：本地中转码放行（最终由 JSPAY 携带）")
    void sellerIdAllowsJsPayRelay() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
            assertThat(page.page()).isEqualTo("alipay_qrcode");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("seller_id 填多个（逗号分隔）：明确失败（每通道仅一个 UID）")
    void sellerIdCommaSeparatedFailsFast() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_PC, null, false, null, false, cb,
                Map.of("seller_id", "2088123456789012,2088123456789013"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                    .isInstanceOf(ExpectedFailureException.class)
                    .hasMessageContaining("仅支持单个");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
        }
        verify(cb, never()).lockOrderExt(any());
    }

    // =========================================================================
    // 平台网页应用统一 OAuth（官方插件直连全局网页应用）
    // =========================================================================

    @Test
    @DisplayName("JSPAY：全局网页应用已配置 → 用全局 appid 直连支付宝 OAuth，不走通道应用")
    void jsPayUsesGlobalWebOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4", UA_ALIPAY, null, false, null, false, cb, Map.of(), true);

        var url = redirectUrl(new AlipayCreateHandler().alipay(ctx));

        var decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=WEB_APP")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1")
                .contains("state=T1")
                .doesNotContain("/oauth/aliweb");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("JSPAY：全局网页应用 auth_code 回跳 → 用全局应用凭证兑换 user_id → buyer_id")
    void jsPayGlobalWebUserIdCreates() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, false, cb, Map.of(), true);
        var tokenBody = new AtomicReference<String>();
        var reqBody = new AtomicReference<String>();

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    tokenBody.set(body);
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"user_id\":\"2088123456789012\","
                                            + "\"open_id\":\"OU_WEB_1\",\"access_token\":\"tok\"}"),
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

        assertThat(tokenBody.get()).contains("app_id=WEB_APP");
        var biz = parseBizContent(reqBody.get());
        assertThat(biz).containsEntry("buyer_id", "2088123456789012")
                .doesNotContainKey("buyer_open_id");
    }

    @Test
    @DisplayName("JSPAY：全局网页应用只返回 open_id → 失败终止（跨应用 open_id 不能用于通道 JSPAY）")
    void jsPayGlobalWebOpenIdFailsClosed() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var ctx = ctx(100L, "4", UA_ALIPAY, "auth_code=AC1", false, null, false, cb, Map.of(), true);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv -> {
                var body = inv.getArgument(2).toString();
                if (body.contains("alipay.system.oauth.token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_system_oauth_token_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\","
                                            + "\"open_id\":\"OU_WEB_1\",\"access_token\":\"tok\"}"),
                            "req", 10, 1);
                }
                throw new AssertionError("全局网页应用 open_id 不应发起 JSPAY trade.create");
            });

            assertThatThrownBy(() -> new AlipayCreateHandler().alipay(ctx))
                    .isInstanceOf(ExpectedFailureException.class)
                    .hasMessageContaining("需要 user_id");
        }
    }

    @Test
    @DisplayName("门禁扫码 + 全局网页应用已配置 + 桌面：中转码指向全局应用 OAuth")
    void forceIdentityQrcodeUsesGlobalWebOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_PC, null, false, null, true, cb, Map.of(), true);

        var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
        assertThat(page.page()).isEqualTo("alipay_qrcode");
        var decoded = URLDecoder.decode(page.url(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=WEB_APP")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1")
                .contains("state=T1");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("门禁扫码 + 全局网页应用已配置 + 支付宝内：跳全局应用 OAuth")
    void forceIdentityQrcodeAlipayUsesGlobalWebOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "3", UA_ALIPAY, null, false, null, true, cb, Map.of(), true);

        var url = redirectUrl(new AlipayCreateHandler().alipay(ctx));
        var decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=WEB_APP")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1")
                .contains("state=T1");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("只开当面付JS + 全局网页应用已配置 + PC：中转码指向全局应用 OAuth")
    void jsPayRelayUsesGlobalWebOAuth() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "4", UA_PC, null, false, null, false, cb, Map.of(), true);

        var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
        assertThat(page.page()).isEqualTo("alipay_qrcode");
        var decoded = URLDecoder.decode(page.url(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .startsWith("https://openauth.alipay.com/oauth2/publicAppAuthorize.htm?")
                .contains("app_id=WEB_APP")
                .contains("redirect_uri=https://pay.example.com/pay/cashier/T1")
                .contains("state=T1");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("只开当面付JS + 全局网页应用已配置 + 同时开 Wap + PC：中转码指向收银台")
    void jsPayRelayWithWapUsesCashier() throws Exception {
        var cb = mock(HostCallback.class);
        var ctx = ctx(100L, "2,4", UA_PC, null, false, null, false, cb, Map.of(), true);

        var page = pageOutcome(new AlipayCreateHandler().alipay(ctx));
        assertThat(page.page()).isEqualTo("alipay_qrcode");
        assertThat(page.url()).isEqualTo("https://pay.example.com/pay/cashier/T1");
        verify(cb, never()).lockOrderExt(any());
    }
}
