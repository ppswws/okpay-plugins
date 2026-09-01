package com.okpay.plugin.helipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.helipay.util.HelipaySignUtil;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HelipayCreateHandler 支付方式分流：支付宝 H5 手机端跳链接 / 扫码 / 小程序 / 公众号，
 * 微信 H5 手机端逆向取 openlink（失败回退）+ 非手机中转页、公众号/小程序 OAuth 兑换后 JSAPI，
 * 银联扫码；渠道 HTTP 失败归因渠道文案。
 */
@DisplayName("HelipayCreateHandler 支付方式分流")
class HelipayCreateHandlerTest {

    private static final String KEY = "HLKEY1";
    private static final String UA_WECHAT = "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";
    private static final String UA_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String QBASE_URL = "https://servicewechat.com/wxa-qbase/jsoperatewxdata";

    // =========================================================================
    // 支付宝
    // =========================================================================

    @Test
    @DisplayName("支付宝 H5 手机端（方式3）→ AppPayH5WFT 下单 → 跳转 WAP 链接")
    void alipayH5MobileJumpsWapUrl() throws Exception {
        var ctx = ctx(UA_IPHONE, null, false, cfgRaw("3", false, false, null));
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealMobileCheck(mocked);
            stubFormApi(mocked, signedOk("AppPayH5WFT", Map.of(
                    "rt4_customerNumber", "HL1", "rt5_orderId", "T1",
                    "rt6_serialNumber", "SN1", "rt7_appName", "短剧剧场",
                    "rt8_payInfo", "https://h5pay.helipay.com/wap?token=WAP1",
                    "rt9_orderAmount", "1.00", "rt10_currency", "CNY", "rt11_payType", "WAP")));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo("https://h5pay.helipay.com/wap?token=WAP1");
        }
        verify(cb, atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("支付宝 H5 手机端请求参数：P8_appPayType=ALIPAY、P12_applicationId=站点域名、P10/P11 固定值")
    void alipayH5RequestParams() throws Exception {
        var ctx = ctx(UA_IPHONE, null, false, cfgRaw("3", false, false, "SUB1"));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealMobileCheck(mocked);
            stubFormApi(mocked, signedOk("AppPayH5WFT", Map.of(
                    "rt5_orderId", "T1", "rt8_payInfo", "https://h5pay.helipay.com/wap?token=WAP1")));
            new HelipayCreateHandler().alipay(ctx);

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayH5WFT")
                .containsEntry("P8_appPayType", "ALIPAY")
                .containsEntry("P9_payType", "WAP")
                .containsEntry("P10_appName", "短剧剧场")
                .containsEntry("P11_deviceInfo", "iOS_WAP")
                .containsEntry("P12_applicationId", "https://pay.example.com")
                .containsEntry("P2_orderId", "T1")
                .containsEntry("subMerchantId", "SUB1")
                .containsEntry("successToUrl", "https://pay.example.com/pay/wxpay/T1");
    }

    @Test
    @DisplayName("支付宝扫码（方式4）→ AppPay 下单 → alipay_qrcode 页")
    void alipayScanRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("4", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPay", Map.of(
                    "rt5_orderId", "T1", "rt8_qrcode", "https://qr.example.com/ALI1")));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("alipay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/ALI1");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPay")
                .containsEntry("P4_payType", "SCAN")
                .containsEntry("P8_appType", "ALIPAY");
    }

    @Test
    @DisplayName("支付宝小程序（方式2）→ AppPayApplet 下单 → alipay_qrcode 页（二维码链接）")
    void alipayMiniRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("2", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayApplet", Map.of(
                    "rt5_orderId", "T1", "rt10_payInfo", "https://qr.example.com/ALIAPP")));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("alipay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/ALIAPP");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayApplet")
                .containsEntry("P4_payType", "APPLET");
    }

    @Test
    @DisplayName("支付宝公众号（方式1）→ AppPayPublic 下单 → alipay_qrcode 页")
    void alipayPublicRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("1", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayPublic", Map.of(
                    "rt5_orderId", "T1", "rt10_payInfo", "https://qr.example.com/ALIPUB")));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getPage()).isEqualTo("alipay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/ALIPUB");
        }
    }

    @Test
    @DisplayName("支付宝未开启任何方式 → error 页")
    void alipayNoModeError() {
        var resp = new HelipayCreateHandler().alipay(ctx(UA_PC, null, false, cfgRaw("", false, false, null)));
        assertThat(resp.getType()).isEqualTo("error");
        assertThat(resp.getMsg()).isEqualTo("当前通道未开启支付宝支付方式");
    }

    // =========================================================================
    // 微信
    // =========================================================================

    @Test
    @DisplayName("微信 H5 手机端 → qbase 逆向取 openlink → wxpay_h5 页拉起")
    void wxpayH5MobileReverseOpenlink() throws Exception {
        var ctx = ctx(UA_IPHONE, null, false, cfgRaw("3", false, true, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealMobileCheck(mocked);
            // 第一发：H5 下单（微信小程序绑定 → 附 appId/isRaw=0）
            stubFormApi(mocked, signedOk("AppPayH5WFT", Map.of(
                    "rt5_orderId", "T1",
                    "rt8_payInfo", "https://h5pay.helipay.com/pay?appid=wxMPAPP1&prepayid=PP1&sign=SIG1&apptype=TH5")));
            // 第二发：qbase jsoperatewxdata（带 Referer/Origin 头）
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.equals(QBASE_URL)) {
                    Map<String, String> headers = inv.getArgument(4);
                    assertThat(headers)
                            .containsEntry("Referer", "https://h5pay.helipay.com/")
                            .containsEntry("Origin", "https://h5pay.helipay.com");
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            openlinkResp("weixin://dl/business/?t=OL1").getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1);
                }
                throw new IllegalStateException("unexpected POST " + url);
            });
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            assertThat(resp.getUrl()).isEqualTo("weixin://dl/business/?t=OL1");
        }
        verify(ctx.getCallback(), atLeastOnce()).lockOrderExt(any());
    }

    @Test
    @DisplayName("微信 H5 手机端逆向失败 → 回退原始 h5pay 支付链接直接跳转")
    void wxpayH5ReverseFailFallsBackToPayUrl() throws Exception {
        var ctx = ctx(UA_IPHONE, null, false, cfgRaw("3", false, true, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealMobileCheck(mocked);
            stubFormApi(mocked, signedOk("AppPayH5WFT", Map.of(
                    "rt5_orderId", "T1",
                    "rt8_payInfo", "https://h5pay.helipay.com/pay?appid=wxMPAPP1&prepayid=PP1&sign=SIG1")));
            // qbase 拒绝：base_resp.ret != 0
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.equals(QBASE_URL)) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"base_resp\":{\"ret\":1}}".getBytes(StandardCharsets.UTF_8), "req", 10, 1);
                }
                throw new IllegalStateException("unexpected POST " + url);
            });
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo(
                    "https://h5pay.helipay.com/pay?appid=wxMPAPP1&prepayid=PP1&sign=SIG1");
        }
    }

    @Test
    @DisplayName("微信 H5 非手机端 → wxpay_qrcode 中转页（部分安卓扫码白屏规避）")
    void wxpayH5NonMobileRendersRelayPage() {
        var ctx = ctx(UA_PC, null, false, cfgRaw("3", false, false, null));
        var resp = new HelipayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("page");
        assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
        // 中转页 URL 为支付页地址 + t 时间戳（重进页面刷新订单态）
        assertThat(resp.getUrl()).startsWith("https://pay.example.com/pay/wxpay/T1?t=");
        // 中转页不占下单锁、不发渠道请求
        verify(ctx.getCallback(), never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("微信扫码（方式4）→ wxpay_qrcode 页（code_url 进页面 URL）")
    void wxpayScanRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("4", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPay", Map.of(
                    "rt5_orderId", "T1", "rt8_qrcode", "https://qr.example.com/WXP1")));
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/WXP1");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P8_appType", "WXPAY")
                .containsEntry("P7_authcode", "1");
    }

    // =========================================================================
    // 微信公众号（OAuth 决策内置 SDK）
    // =========================================================================

    @Test
    @DisplayName("公众号微信内无 code → 跳授权 URL（SDK 内置：已配置系统公众号 → /oauth/wx 包装）")
    void wxpayMpWechatNoCodeJumpsOAuth() {
        var ctx = ctx(UA_WECHAT, null, true, cfgRaw("1", true, false, null));
        var resp = new HelipayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        var url = resp.getUrl();
        assertThat(url).startsWith("https://pay.example.com/oauth/wx?redirect_uri=");
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://open.weixin.qq.com/connect/oauth2/authorize")
                .contains("appid=wxMPAPP1")
                .contains("state=T1");
        verify(ctx.getCallback(), never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("公众号非微信端无 code → wxpay_qrcode 页展示支付链接（扫码者微信打开走授权）")
    void wxpayMpOutsideWechatRendersQrcode() {
        var ctx = ctx(UA_PC, null, false, cfgRaw("1", true, false, null));
        var resp = new HelipayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("page");
        assertThat(resp.getPage()).isEqualTo("wxpay_qrcode");
        assertThat(resp.getUrl()).startsWith("https://pay.example.com/pay/wxpay/T1?t=");
        verify(ctx.getCallback(), never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("公众号有 code → 兑换 openid → AppPayPublic 下单 → wxpay_jspay（js_api_parameters 载荷）")
    void wxpayMpCodeExchangesAndJspay() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=CODE1", false, cfgRaw("1", true, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            // OAuth code 兑换（sns/oauth2/access_token）
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.contains("sns/oauth2/access_token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_MP1\"}".getBytes(StandardCharsets.UTF_8), "req", 10, 1);
                }
                throw new IllegalStateException("unexpected GET " + url);
            });
            stubFormApi(mocked, signedOk("AppPayPublic", Map.of(
                    "rt5_orderId", "T1",
                    "rt10_payInfo", "{\"timeStamp\":\"TS1\",\"nonceStr\":\"NS1\","
                            + "\"package\":\"prepay_id=PP1\",\"signType\":\"MD5\",\"paySign\":\"PS1\"}")));
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
            // 模板契约：wxpay_jspay 页读 paydata.js_api_parameters
            assertThat(new String(resp.getDataRaw(), StandardCharsets.UTF_8))
                    .contains("\"js_api_parameters\"")
                    .contains("\"paySign\":\"PS1\"");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayPublic")
                .containsEntry("P4_payType", "PUBLIC")
                .containsEntry("P5_appid", "wxMPAPP1")
                .containsEntry("P7_isRaw", "1")
                .containsEntry("P8_openid", "wxOPENID_MP1")
                .containsEntry("P11_appType", "WXPAY");
        // 兑换经 SDK 内部自动风控（落库 buyer + 黑名单）
        verify(ctx.getCallback()).recordOAuthIdentity(any());
    }

    // =========================================================================
    // 微信小程序
    // =========================================================================

    @Test
    @DisplayName("小程序无 auth_code → 生成 URL Scheme → wxpay_h5 页拉起小程序")
    void wxpayMiniNoCodeGeneratesScheme() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("2", false, true, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            // stable_token（取 token）与 generatescheme 同为 4 参 POST，按 URL 区分
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.contains("cgi-bin/stable_token")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"access_token\":\"TK1\",\"expires_in\":7200}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1);
                }
                if (url.contains("wxa/generatescheme")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openlink\":\"weixin://dl/business/?t=SCHEME1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1);
                }
                throw new IllegalStateException("unexpected POST " + url);
            });
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_h5");
            assertThat(resp.getUrl()).isEqualTo("weixin://dl/business/?t=SCHEME1");
        }
        // 未兑换、未下单：无风控落库、不占下单锁
        verify(ctx.getCallback(), never()).recordOAuthIdentity(any());
        verify(ctx.getCallback(), never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("小程序有 auth_code → 兑换 openid → AppPayApplet 下单 → wxpay_jspay")
    void wxpayMiniAuthCodeExchangesAndJspay() throws Exception {
        var ctx = ctx(UA_PC, "auth_code=CODE1", false, cfgRaw("2", false, true, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString())).thenAnswer(inv -> {
                var url = inv.getArgument(1).toString();
                if (url.contains("sns/jscode2session")) {
                    return new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_MINI1\"}".getBytes(StandardCharsets.UTF_8), "req", 10, 1);
                }
                throw new IllegalStateException("unexpected GET " + url);
            });
            stubFormApi(mocked, signedOk("AppPayApplet", Map.of(
                    "rt5_orderId", "T1",
                    "rt10_payInfo", "{\"timeStamp\":\"TS1\",\"package\":\"prepay_id=PP1\",\"paySign\":\"PS1\"}")));
            var resp = new HelipayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P1_bizType", "AppPayApplet")
                .containsEntry("P4_payType", "APPLET")
                .containsEntry("P5_appid", "wxMINIAPP1")
                .containsEntry("P8_openid", "wxOPENID_MINI1");
        verify(ctx.getCallback()).recordOAuthIdentity(any());
    }

    // =========================================================================
    // 银联
    // =========================================================================

    @Test
    @DisplayName("云闪付（方式1）→ AppPayPublic UNIONPAY 下单 → bank_qrcode 页")
    void bankRendersQrcodePage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("1", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());
        var bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPayPublic", Map.of(
                    "rt5_orderId", "T1", "rt10_payInfo", "https://qr.example.com/BANK1")));
            var resp = new HelipayCreateHandler().bank(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("bank_qrcode");
            assertThat(resp.getUrl()).isEqualTo("https://qr.example.com/BANK1");

            mocked.verify(() -> HttpHelper.post(any(), anyString(), bodyCaptor.capture(), anyString()));
        }
        var form = PaymentUtils.parseForm(bodyCaptor.getValue());
        assertThat(form).containsEntry("P11_appType", "UNIONPAY");
    }

    @Test
    @DisplayName("云闪付未开启 → error 页")
    void bankNoModeError() {
        var resp = new HelipayCreateHandler().bank(ctx(UA_PC, null, false, cfgRaw("4", false, false, null)));
        assertThat(resp.getType()).isEqualTo("error");
        assertThat(resp.getMsg()).isEqualTo("当前通道未开启云闪付支付方式");
    }

    // =========================================================================
    // 渠道失败归因
    // =========================================================================

    @Test
    @DisplayName("渠道 HTTP 层失败（502 nginx 错误页）：error 页文案归因渠道，不报插件内部错误")
    void channelHttpFailureReturnsChannelErrorPage() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("4", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head><body></body></html>"
                                    .getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg())
                    .isEqualTo("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
        }
    }

    @Test
    @DisplayName("渠道业务失败（rt2_retCode 非 0000 带说明）：error 页文案带业务码")
    void channelBizFailureIncludesCodeAndMsg() throws Exception {
        var ctx = ctx(UA_PC, null, false, cfgRaw("4", false, false, null));
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            stubFormApi(mocked, signedOk("AppPay", Map.of(
                    "rt2_retCode", "0002", "rt3_retMsg", "余额不足", "rt5_orderId", "T1")));
            var resp = new HelipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).isEqualTo("[0002]余额不足");
        }
    }

    // =========================================================================
    // 内部：上下文/配置/响应构造
    // =========================================================================

    /** mockStatic(HttpHelper) 会连 UA 判定一起拦截，未 stub 时全 false → 方式错配；
     *  按用例实际调用点放行真实实现（strict stubs 下未命中的 stub 会报 UnnecessaryStubbing）。 */
    private static void letRealMobileCheck(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
    }

    /** 表单接口 stub（合利宝交易接口：校验请求走统一 HelipayApi 封装）。 */
    private static void stubFormApi(MockedStatic<HttpHelper> mocked, HttpHelper.HttpResponse resp) throws Exception {
        mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            var url = inv.getArgument(1).toString();
            if (url.equals("https://pay.trx.helipay.com/trx/app/interface.action"))
                return resp;
            throw new IllegalStateException("unexpected POST " + url);
        });
    }

    /** 渠道成功响应：按响应字段序签名（验签与请求签名走同一实现，全链路闭环）。 */
    private static HttpHelper.HttpResponse signedOk(String bizType, Map<String, String> fields) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.put("rt1_bizType", bizType);
        m.put("rt2_retCode", "0000");
        m.putAll(fields);
        m.put("sign", HelipaySignUtil.signResponse(m, KEY));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }

    /** qbase 三层 JSON 解包响应（openlink 在第三层 data 字符串内）。 */
    private static String openlinkResp(String openlink) throws Exception {
        var inner = HttpHelper.MAPPER.writeValueAsString(Map.of("openlink", openlink));
        var mid = HttpHelper.MAPPER.writeValueAsString(Map.of("data", inner));
        return HttpHelper.MAPPER.writeValueAsString(Map.of("base_resp", Map.of("ret", 0), "data", mid));
    }

    /** 通道配置：biztype（方式编号串）+ 公众号/小程序绑定 + 报备商户号。 */
    private static byte[] cfgRaw(String biztype, boolean mp, boolean mini, String appmchid) {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "HL1");
            cfg.put("appkey", KEY);
            cfg.put("biztype", biztype);
            if (mp) cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
            if (mini) cfg.put("mini", Map.of("appid", "wxMINIAPP1", "appsecret", "wxMINISECRET"));
            if (appmchid != null) cfg.put("appmchid", appmchid);
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    private InvokeContext ctx(String ua, String query, boolean oauthWx, byte[] cfgRaw) {
        var order = OrderSnapshot.builder().tradeNo("T1").real(100L).type("wxpay")
                .ipBuyer("127.0.0.1").build();
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(order)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com")
                        .goodsName("短剧剧场")
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
