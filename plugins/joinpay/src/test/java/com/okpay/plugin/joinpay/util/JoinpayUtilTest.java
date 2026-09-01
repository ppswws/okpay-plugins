package com.okpay.plugin.joinpay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JoinpayUtil 契约测试 —— 限长语义：未超限返回原串、超限按 max 截断、null 返回空。
 */
class JoinpayUtilTest {

    @Test
    @DisplayName("未超限 → 返回原串")
    void withinLimitReturnsOriginal() {
        assertThat(JoinpayUtil.limitLength("商品", 30)).isEqualTo("商品");
        assertThat(JoinpayUtil.limitLength("", 30)).isEmpty();
    }

    @Test
    @DisplayName("恰好等于 max → 返回原串")
    void exactlyAtLimitReturnsOriginal() {
        assertThat(JoinpayUtil.limitLength("12345", 5)).isEqualTo("12345");
    }

    @Test
    @DisplayName("超限 → 截断到 max")
    void overLimitTruncates() {
        assertThat(JoinpayUtil.limitLength("123456", 5)).isEqualTo("12345");
    }

    @Test
    @DisplayName("null → 返回空串")
    void nullReturnsEmpty() {
        assertThat(JoinpayUtil.limitLength(null, 30)).isEmpty();
    }
}
