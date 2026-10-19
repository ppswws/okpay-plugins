package com.okpay.plugin.alipay;

import com.okpay.plugin.PluginInfoValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝投诉插件元信息契约测试：元信息须通过统一校验器。
 */
@DisplayName("支付宝投诉插件元信息契约测试")
class AlipayComplainHandlerInfoTest {

    @Test
    @DisplayName("元信息通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginInfoValidator.validateComplaintInfo("alipay",
                new AlipayComplainHandler().info(), AlipayPluginInfo.get().getFields())).isEmpty();
    }
}
