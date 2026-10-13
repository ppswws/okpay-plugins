package com.okpay.plugin.wxpay.util;

import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WxpayConfig 配置解析：AppID 兜底链（appid → 绑定公众号 → 绑定小程序）与完整性校验。
 */
@DisplayName("WxpayConfig AppID 兜底与完整性校验")
class WxpayConfigTest {

    @Test
    @DisplayName("appid 留空 + 绑定公众号 → 通过校验，取公众号 AppID")
    void fallsBackToBoundMp() throws Exception {
        var cfg = WxpayConfig.from(ctx(cfgJson(null, Map.of("appid", "wxMP1", "appsecret", "s"), null)));
        assertThat(cfg.genericAppId()).isEqualTo("wxMP1");
    }

    @Test
    @DisplayName("appid 与公众号均空 + 绑定小程序 → 取小程序 AppID")
    void fallsBackToBoundMini() throws Exception {
        var cfg = WxpayConfig.from(ctx(cfgJson(null, null, Map.of("appid", "wxMINI1", "appsecret", "s"))));
        assertThat(cfg.genericAppId()).isEqualTo("wxMINI1");
    }

    @Test
    @DisplayName("显式 appid 优先于绑定")
    void explicitAppIdWins() throws Exception {
        var cfg = WxpayConfig.from(ctx(cfgJson("wxAPP1", Map.of("appid", "wxMP1", "appsecret", "s"), null)));
        assertThat(cfg.genericAppId()).isEqualTo("wxAPP1");
    }

    @Test
    @DisplayName("appid/公众号/小程序 全空 → 判定配置不完整")
    void rejectsWhenAllAppIdsMissing() throws Exception {
        var raw = cfgJson(null, null, null);
        assertThatThrownBy(() -> WxpayConfig.from(ctx(raw)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("通道配置不完整");
    }

    @Test
    @DisplayName("APP 支付 appid 仅取显式配置，不参与绑定兜底")
    void appPayAppIdIgnoresBindings() throws Exception {
        var cfg = WxpayConfig.from(ctx(
                cfgJson(null, Map.of("appid", "wxMP1", "appsecret", "s"), null)));
        assertThat(cfg.appPayAppId()).isNull();
        assertThat(cfg.genericAppId()).isEqualTo("wxMP1");
    }

    @Test
    @DisplayName("开启 APP 支付但未填显式 appid（仅绑定公众号）→ 判定配置不完整")
    void rejectsAppPayWithoutExplicitAppId() throws Exception {
        var raw = cfgJson(null, Map.of("appid", "wxMP1", "appsecret", "s"), null, "1,4");
        assertThatThrownBy(() -> WxpayConfig.from(ctx(raw)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("通道配置不完整");
    }

    /** 通道配置 JSON：appmchid/appsecret 恒有，appid 与 mp/mini 绑定按需。 */
    private static byte[] cfgJson(String appid, Map<String, String> mp, Map<String, String> mini)
            throws Exception {
        return cfgJson(appid, mp, mini, null);
    }

    private static byte[] cfgJson(String appid, Map<String, String> mp, Map<String, String> mini,
                                  String biztype) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appmchid", "1900000001");
        cfg.put("appsecret", "0123456789abcdef0123456789abcdef");
        if (appid != null) cfg.put("appid", appid);
        if (mp != null) cfg.put("mp", mp);
        if (mini != null) cfg.put("mini", mini);
        if (biztype != null) cfg.put("biztype", biztype);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private static InvokeContext ctx(byte[] cfgRaw) {
        return InvokeContext.builder()
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .build();
    }
}
