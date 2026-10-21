package com.okpay.plugin.epay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EpayRespUtil 契约测试 —— 成功码兼容：标准 code=1 与兼容渠道 code=200 均为成功。
 */
class EpayRespUtilTest {

    @Test
    @DisplayName("code=1 → 成功（标准易支付）")
    void codeOneIsSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "1"))).isTrue();
    }

    @Test
    @DisplayName("code=200 → 成功（兼容渠道）")
    void codeTwoHundredIsSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "200"))).isTrue();
    }

    @Test
    @DisplayName("其他 code 或缺失 code → 失败")
    void otherOrMissingCodeIsNotSuccess() {
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "0"))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of("code", "-1"))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of("code", ""))).isFalse();
        assertThat(EpayRespUtil.isSuccess(Map.of())).isFalse();
    }

}
