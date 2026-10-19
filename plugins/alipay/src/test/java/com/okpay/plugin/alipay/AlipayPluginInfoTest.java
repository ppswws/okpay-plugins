package com.okpay.plugin.alipay;

import com.okpay.plugin.PluginInfoValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * alipay 插件元信息契约测试：元信息须通过统一校验器。
 */
@DisplayName("alipay 插件元信息契约测试")
class AlipayPluginInfoTest {

    @Test
    @DisplayName("元信息通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginInfoValidator.validatePluginInfo("alipay", AlipayPluginInfo.get())).isEmpty();
    }
}
