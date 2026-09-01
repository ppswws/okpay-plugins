package com.okpay.plugin.alipay.util;

import com.okpay.plugin.alipay.util.AlipayModeResolver.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UA × 支付方式自动分配矩阵。
 *
 * <p>覆盖优先级：支付宝客户端 → 扫码类兜底（含微信内）→ Wap → 配置兜底。</p>
 */
@DisplayName("AlipayModeResolver UA 自动分配")
class AlipayModeResolverTest {

    private static final String UA_ALIPAY = "Mozilla/5.0 (iPhone) Mobile AlipayClient/10.0.1";
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Mobile";
    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String UA_WECHAT = "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1";
    private static final String UA_ALIPAY_WECHAT = "Mozilla/5.0 (iPhone) MicroMessenger/8.0.1 AlipayClient/10.0.1";

    @Test
    @DisplayName("支付宝客户端 + 开 4 未开 2 → JSPAY")
    void alipayClientWithJsPay() {
        assertThat(AlipayModeResolver.resolve(UA_ALIPAY, Set.of("4"), false)).isEqualTo(Mode.JSPAY);
        assertThat(AlipayModeResolver.resolve(UA_ALIPAY, Set.of("4", "1"), false)).isEqualTo(Mode.JSPAY);
    }

    @Test
    @DisplayName("支付宝客户端但开 2 → 让位于 Wap（不选 JSPAY）")
    void alipayClientWithWapTakesPrecedenceOverJsPay() {
        // (a) 条件含 !含2；(d) 手机含 2 → WAP
        assertThat(AlipayModeResolver.resolve(UA_ALIPAY, Set.of("4", "2"), false)).isEqualTo(Mode.WAP);
    }

    @Test
    @DisplayName("手机 + 开 3/4/7 未开 2 → QRCODE")
    void mobilePrefersQrcode() {
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("3"), false)).isEqualTo(Mode.QRCODE);
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("4"), false)).isEqualTo(Mode.QRCODE);
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("7"), false)).isEqualTo(Mode.QRCODE);
    }

    @Test
    @DisplayName("PC 未开 1 但开了扫码类（3/4/7）→ QRCODE；仅 Wap 无扫码类 → 配置兜底/null")
    void pcWithoutPageFallsBackToQrcode() {
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("3"), false)).isEqualTo(Mode.QRCODE);
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("7"), false)).isEqualTo(Mode.QRCODE);
        // 只开 Wap（无扫码类）：不再无条件 QRCODE，落配置兜底
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("2"), false)).isNull();
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("2", "5"), false)).isEqualTo(Mode.APP);
    }

    @Test
    @DisplayName("微信内 → QRCODE（非支付宝客户端 UA）")
    void wechatFallsBackToQrcode() {
        assertThat(AlipayModeResolver.resolve(UA_WECHAT, Set.of("1", "2"), false)).isEqualTo(Mode.QRCODE);
    }

    @Test
    @DisplayName("支付宝客户端 UA 优先于微信（(a) 在 (c) 前）")
    void alipayClientBeatsWechat() {
        assertThat(AlipayModeResolver.resolve(UA_ALIPAY_WECHAT, Set.of("4"), false)).isEqualTo(Mode.JSPAY);
        // 未开 4 时微信内 → QRCODE
        assertThat(AlipayModeResolver.resolve(UA_ALIPAY_WECHAT, Set.of("1"), false)).isEqualTo(Mode.QRCODE);
    }

    @Test
    @DisplayName("手机 + 开 2 → WAP")
    void mobileWithWap() {
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("2"), false)).isEqualTo(Mode.WAP);
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("2", "3"), false)).isEqualTo(Mode.WAP);
    }

    @Test
    @DisplayName("PC 开 1 → PAGE")
    void pcWithPage() {
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("1"), false)).isEqualTo(Mode.PAGE);
    }

    @Test
    @DisplayName("当面付优先（paymode）开 1 → QRCODE；未开 1 不受影响")
    void paymodePreferredOverPage() {
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("1"), true)).isEqualTo(Mode.QRCODE);
        // paymode 只作用于电脑网站：未开 1 时照常落 APP 兜底
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("5"), true)).isEqualTo(Mode.APP);
        // 手机端开电脑网站恒走扫码
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("1"), false)).isEqualTo(Mode.QRCODE);
        // 手机开 1+2 → WAP 优先（(d) 在 (e) 前）
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("1", "2"), false)).isEqualTo(Mode.WAP);
    }

    @Test
    @DisplayName("配置兜底：开 5 → APP、开 6 → JSAPI")
    void configFallbacks() {
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of("5"), false)).isEqualTo(Mode.APP);
        assertThat(AlipayModeResolver.resolve(UA_MOBILE, Set.of("6"), false)).isEqualTo(Mode.JSAPI);
    }

    @Test
    @DisplayName("全部未开 → null")
    void noModeConfigured() {
        assertThat(AlipayModeResolver.resolve(UA_PC, Set.of(), false)).isNull();
        assertThat(AlipayModeResolver.resolve(null, Set.of(), false)).isNull();
    }
}
