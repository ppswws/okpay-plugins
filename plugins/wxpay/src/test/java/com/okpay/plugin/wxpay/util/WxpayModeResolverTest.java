package com.okpay.plugin.wxpay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WxpayModeResolver UA 自动分配（epay submit() 优先级表）。
 *
 * <p>纯函数矩阵：UA（微信内/手机 iPhone/手机 Android/PC/空）× 方式集合 × mp/mini 绑定。</p>
 */
@DisplayName("WxpayModeResolver UA 自动分配")
class WxpayModeResolverTest {

    private static final String UA_WECHAT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.1";
    private static final String UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    private static final String UA_ANDROID =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Mobile";
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private static WxpayModeResolver.Mode resolve(String ua, Set<String> modes,
                                                  boolean mp, boolean mini) {
        return WxpayModeResolver.resolve(ua, modes, mp, mini);
    }

    @Test
    @DisplayName("微信内 + JSAPI + 公众号 → MP（公众号 JSAPI）")
    void wechatJsapiWithMp() {
        assertThat(resolve(UA_WECHAT, Set.of("2"), true, false)).isEqualTo(WxpayModeResolver.Mode.MP);
    }

    @Test
    @DisplayName("微信内 + JSAPI + 双绑 → MP（公众号优先）")
    void wechatJsapiBothBoundPrefersMp() {
        assertThat(resolve(UA_WECHAT, Set.of("2"), true, true)).isEqualTo(WxpayModeResolver.Mode.MP);
    }

    @Test
    @DisplayName("微信内 + JSAPI + 仅小程序 → MINI")
    void wechatJsapiWithMiniOnly() {
        assertThat(resolve(UA_WECHAT, Set.of("2"), false, true)).isEqualTo(WxpayModeResolver.Mode.MINI);
    }

    @Test
    @DisplayName("微信内 + JSAPI 未绑任何应用 → null（缺少公众号/小程序配置）")
    void wechatJsapiUnbound() {
        assertThat(resolve(UA_WECHAT, Set.of("2"), false, false)).isNull();
    }

    @Test
    @DisplayName("微信内 + Native → QRCODE（企业微信扫码兜底）")
    void wechatNativeQrcode() {
        assertThat(resolve(UA_WECHAT, Set.of("1"), false, false)).isEqualTo(WxpayModeResolver.Mode.QRCODE);
    }

    @Test
    @DisplayName("微信内 + 全方式 + 公众号 → MP（JSAPI 优先于 Native）")
    void wechatAllModesPrefersJsapi() {
        assertThat(resolve(UA_WECHAT, Set.of("1", "2", "3", "4"), true, false))
                .isEqualTo(WxpayModeResolver.Mode.MP);
    }

    @Test
    @DisplayName("手机 iPhone + H5 → H5")
    void mobileH5() {
        assertThat(resolve(UA_IPHONE, Set.of("3"), false, false)).isEqualTo(WxpayModeResolver.Mode.H5);
    }

    @Test
    @DisplayName("手机 iPhone + APP → APP（epay 仅 iPhone 网页触发 APP 支付）")
    void mobileIphoneApp() {
        assertThat(resolve(UA_IPHONE, Set.of("4"), false, false)).isEqualTo(WxpayModeResolver.Mode.APP);
    }

    @Test
    @DisplayName("手机 Android + APP → null（非 iPhone 不触发 APP，兜底无 4）")
    void mobileAndroidAppNull() {
        assertThat(resolve(UA_ANDROID, Set.of("4"), false, false)).isNull();
    }

    @Test
    @DisplayName("手机 iPhone + H5+APP → H5（H5 优先于 APP）")
    void mobileH5BeforeApp() {
        assertThat(resolve(UA_IPHONE, Set.of("3", "4"), false, false)).isEqualTo(WxpayModeResolver.Mode.H5);
    }

    @Test
    @DisplayName("手机 + JSAPI + 小程序 → MINI")
    void mobileJsapiWithMini() {
        assertThat(resolve(UA_ANDROID, Set.of("2"), false, true)).isEqualTo(WxpayModeResolver.Mode.MINI);
    }

    @Test
    @DisplayName("手机 + JSAPI + 公众号 → MP（无 code 时展示 jspay 链接码，即 epay wap 页）")
    void mobileJsapiWithMp() {
        assertThat(resolve(UA_ANDROID, Set.of("2"), true, false)).isEqualTo(WxpayModeResolver.Mode.MP);
    }

    @Test
    @DisplayName("手机 + JSAPI 未绑定 → null")
    void mobileJsapiUnbound() {
        assertThat(resolve(UA_ANDROID, Set.of("2"), false, false)).isNull();
    }

    @Test
    @DisplayName("PC + Native → QRCODE")
    void pcNativeQrcode() {
        assertThat(resolve(UA_PC, Set.of("1"), false, false)).isEqualTo(WxpayModeResolver.Mode.QRCODE);
    }

    @Test
    @DisplayName("PC + JSAPI + 公众号 → MP（扫码用户为微信 → 公众号 JSAPI）")
    void pcJsapiWithMp() {
        assertThat(resolve(UA_PC, Set.of("2"), true, false)).isEqualTo(WxpayModeResolver.Mode.MP);
    }

    @Test
    @DisplayName("PC + JSAPI + 小程序 → MINI")
    void pcJsapiWithMini() {
        assertThat(resolve(UA_PC, Set.of("2"), false, true)).isEqualTo(WxpayModeResolver.Mode.MINI);
    }

    @Test
    @DisplayName("PC + JSAPI 未绑定 → null")
    void pcJsapiUnbound() {
        assertThat(resolve(UA_PC, Set.of("2"), false, false)).isNull();
    }

    @Test
    @DisplayName("PC + H5 → H5")
    void pcH5() {
        assertThat(resolve(UA_PC, Set.of("3"), false, false)).isEqualTo(WxpayModeResolver.Mode.H5);
    }

    @Test
    @DisplayName("PC + 仅 APP → null（epay qrcode() 无 5 分支：APP 不参与 PC 兜底）")
    void pcAppOnlyNull() {
        assertThat(resolve(UA_PC, Set.of("4"), false, false)).isNull();
    }

    @Test
    @DisplayName("PC + 全方式 → QRCODE（Native 优先）")
    void pcAllModesPrefersNative() {
        assertThat(resolve(UA_PC, Set.of("1", "2", "3", "4"), true, false))
                .isEqualTo(WxpayModeResolver.Mode.QRCODE);
    }

    @Test
    @DisplayName("空 UA + Native → QRCODE（兜底可用）")
    void nullUaWithNative() {
        assertThat(resolve(null, Set.of("1"), false, false)).isEqualTo(WxpayModeResolver.Mode.QRCODE);
    }

    @Test
    @DisplayName("空方式集 → null")
    void emptyModes() {
        assertThat(resolve(UA_PC, Set.of(), false, false)).isNull();
    }
}
