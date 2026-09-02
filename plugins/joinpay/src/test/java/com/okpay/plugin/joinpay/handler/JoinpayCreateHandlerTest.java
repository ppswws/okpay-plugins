package com.okpay.plugin.joinpay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JoinpayCreateHandler 公众号 OAuth 决策（对齐 wxpay 收编：授权 URL 决策内置 SDK）。
 *
 * <p>公众号授权 URL 决策内置 SDK（已配置系统公众号 → 跳 /oauth/wx 包装的通道授权 URL，双 OAuth；
 * 未配置 → 直接通道授权 URL），buyer 由宿主/SDK 管理、插件不读不判；黑名单命中 → 跳失败页终止。</p>
 */
@DisplayName("JoinpayCreateHandler 公众号 OAuth 决策")
class JoinpayCreateHandlerTest {

    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";

    @Test
    @DisplayName("已配置系统公众号 → 跳 /oauth/wx 包装的通道授权 URL（双 OAuth：先系统公众号存风控身份）")
    void unifiedOAuthWrapperWhenConfigured() throws Exception {
        // buyer 有无不参与决策：插件始终经 SDK 构建授权 URL，buyer 由宿主/SDK 管理
        var ctx = ctx(UA_WECHAT, null, true, cfgRaw("3"));

        var resp = new JoinpayCreateHandler().wxpay(ctx);

        assertThat(resp.getType()).isEqualTo("jump");
        var url = resp.getUrl();
        assertThat(url).startsWith("https://pay.example.com/oauth/wx?redirect_uri=");
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
    @DisplayName("公众号回调命中买家黑名单 → 兑换内部自动抛异常 → 跳失败页终止（不再下单）")
    void mpCallbackBlockedByBlacklist() throws Exception {
        var ctx = ctx(UA_WECHAT, "auth_code=CODE1", false, cfgRaw("3"));
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
