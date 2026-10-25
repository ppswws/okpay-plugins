package com.okpay.plugin.helipay;

import com.okpay.plugin.PluginDescriptorValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * helipay 插件声明契约测试 — payment 启动加载与 manager 上传共用同一校验器，
 * 声明任何字段（type/options/modes/window 引用）改动不满足契约时在此失败，而非运行时才暴露。
 */
@DisplayName("helipay 插件声明契约测试")
class HelipayPluginDeclarationTest {

    @Test
    @DisplayName("descriptor 通过统一校验器（窗口引用全部指向注册表字段）")
    void metadataPassesValidator() {
        assertThat(PluginDescriptorValidator.validate("helipay", new HelipayPlugin().descriptor())).isEmpty();
    }
}
