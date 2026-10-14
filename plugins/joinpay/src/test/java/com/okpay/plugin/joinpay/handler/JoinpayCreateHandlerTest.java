package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.joinpay.util.JoinpaySignUtil;
import com.okpay.plugin.sdk.HttpHelper;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JoinpayCreateHandler 公众号 OAuth 决策（对齐 wxpay 收编：授权 URL 决策内置 SDK）。
 *
 * <p>公众号授权 URL 决策内置 SDK（已配置系统公众号 → 跳 /oauth/wxmp 包装的通道授权 URL，双 OAuth；
 * 未配置 → 直接通道授权 URL），buyer 由宿主/SDK 管理、插件不读不判；黑名单命中 → 跳失败页终止。</p>
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

        var resp = new JoinpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        var url = resp.getUrl();
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

        var resp = new JoinpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        assertThat(resp.getUrl()).startsWith("https://open.weixin.qq.com/connect/oauth2/authorize");
        assertThat(resp.getUrl()).contains("appid=wxMPAPP1");
    }

    @Test
    @DisplayName("渠道 HTTP 层失败（502 nginx 错误页）：归类为渠道异常返回 error 页（含状态码），不抛异常")
    void channelHttpFailureReturnsChannelErrorPage() throws Exception {
        var ctx = ctx(null, null, false, cfgRaw("1"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(502, Map.of(),
                            "<html><head><title>502 Bad Gateway</title></head><body></body></html>"
                                    .getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            var resp = new JoinpayCreateHandler().alipay(ctx);

            // 渠道挂是渠道问题：error 页文案归因渠道，不得报"插件内部错误"
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg())
                    .isEqualTo("接口通道服务异常，未返回预期响应！HTTP Status Code:502");
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

            var resp = new JoinpayCreateHandler().wxpay(ctx);

            // 回调 code 必须被消费：兑换请求 URL 带上它；未消费即走授权回跳分支
            mocked.verify(() -> HttpHelper.get(any(), contains("code=WXCODE1")), times(1));
            // 回跳授权是 jump 响应——正是线上 302 死循环的形态
            assertThat(resp.getType()).isEqualTo("page");
            assertThat(String.valueOf(resp.getUrl())).doesNotContain("open.weixin.qq.com");
            // 公众号建单返回 JSAPI 调起页：模板名 + js_api_parameters（渠道 rc_Result 解析后透传），
            // 缺模板名宿主会渲染成 {"type":"page","data":""}，调起参数全丢
            assertThat(resp.getPage()).isEqualTo("wxpay_jspay");
            assertThat(new String(resp.getDataRaw(), StandardCharsets.UTF_8))
                    .contains("js_api_parameters")
                    .contains("wxMPAPP1");
        }
    }

    @Test
    @DisplayName("渠道业务拒绝（ra_Code≠100）→ 返回 error 页透传渠道原文，不抛异常（页面可直接展示）")
    void channelBizRejectionReturnsErrorPage() throws Exception {
        var ctx = ctx(UA_WECHAT, "code=WXCODE2&state=T1", false, cfgRaw("3"));

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any())).thenAnswer(inv ->
                    new HttpHelper.HttpResponse(200, Map.of(),
                            "{\"openid\":\"wxOPENID_1\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any())).thenAnswer(inv ->
                    signedPayResp("10080061", "交易渠道为空,交易商户无可用通道", ""));

            var resp = new JoinpayCreateHandler().wxpay(ctx);

            // 渠道拒绝是业务失败（BizFailException），必须还原成 error 页透出原文；
            // 包成普通异常会被宿主按「插件内部错误」兜底，渠道文案只剩日志里有
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).isEqualTo("[10080061]交易渠道为空,交易商户无可用通道");
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
            var resp = new JoinpayCreateHandler().wxpay(ctx);

            assertThat(resp.getType()).isEqualTo("jump");
            assertThat(resp.getUrl()).isEqualTo("https://pay.example.com/pay/result/T1");
        }
        // 命中后插件未做任何拦截判断——兑换方法内部已抛异常终止；被拦截：不发起支付、不写 ext
        verify(cb).recordOAuthIdentity(any());
        verify(cb, never()).lockOrderExt(any());
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
        var m = new LinkedHashMap<String, String>();
        m.put("r0_Version", "2.6");
        m.put("r2_OrderNo", "T1");
        m.put("ra_Code", raCode);
        m.put("rb_CodeMsg", rbCodeMsg);
        m.put("rc_Result", rcResult);
        m.put("hmac", JoinpaySignUtil.sign(m, PAY_RESP_VERIFY, "JPKEY1"));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }

    /** 通道配置：biztype=3（公众号 WEIXIN_GZH）+ 公众号绑定。 */
    private static byte[] cfgRaw(String biztype) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", "JPAPP1");
        cfg.put("appkey", "JPKEY1");
        cfg.put("biztype", biztype);
        cfg.put("mp", Map.of("appid", "wxMPAPP1", "appsecret", "wxMPSECRET"));
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(String ua, String query, boolean oauthWx, byte[] cfgRaw) {
        var order = OrderSnapshot.builder().tradeNo("T1").real(100L).type("wxpay").build();
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
