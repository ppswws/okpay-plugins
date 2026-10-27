package com.okpay.plugin.epay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EpayRespUtil 契约测试 —— 查询成功（code=1/200）与支付成功（status=1）分离。
 */
class EpayRespUtilTest {

    @Test
    @DisplayName("code=1 → 查询成功（标准易支付）")
    void codeOneIsSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "1"))).isTrue();
    }

    @Test
    @DisplayName("code=200 → 查询成功（兼容渠道）")
    void codeTwoHundredIsSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "200"))).isTrue();
    }

    @Test
    @DisplayName("其他 code 或缺失 code → 查询失败")
    void otherOrMissingCodeIsNotSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "0"))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "-1"))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of("code", ""))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of())).isFalse();
    }

    @Test
    @DisplayName("code=1 + status=1 → 已支付")
    void paidRequiresStatusOne() {
        assertThat(EpayRespUtil.isPaid(Map.of("code", "1", "status", "1"))).isTrue();
        assertThat(EpayRespUtil.isPaid(Map.of("code", "200", "status", "1"))).isTrue();
    }

    @Test
    @DisplayName("status=0/缺失或 code 失败 → 未支付")
    void unpaidWhenStatusNotOne() {
        assertThat(EpayRespUtil.isPaid(Map.of("code", "1", "status", "0"))).isFalse();
        assertThat(EpayRespUtil.isPaid(Map.of("code", "1"))).isFalse();
        assertThat(EpayRespUtil.isPaid(Map.of("code", "-1", "status", "1"))).isFalse();
    }
}
