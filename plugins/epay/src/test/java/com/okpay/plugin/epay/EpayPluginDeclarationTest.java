package com.okpay.plugin.epay;

import com.okpay.plugin.PluginDescriptorValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * epay 插件声明契约测试 — payment 启动加载与 manager 上传共用同一校验器。
 */
@DisplayName("epay 插件声明契约测试")
class EpayPluginDeclarationTest {

    @Test
    @DisplayName("descriptor 通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginDescriptorValidator.validate("epay", new EpayPlugin().descriptor())).isEmpty();
    }
}
