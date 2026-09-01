package com.okpay.plugin.epay;

import com.okpay.plugin.PluginInfoValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * epay 插件元信息契约测试 — payment 启动加载与 manager 上传共用同一校验器，
 * 元信息任何字段（type/options/modes）改动不满足契约时在此失败，而非运行时才暴露。
 */
@DisplayName("epay 插件元信息契约测试")
class EpayPluginInfoTest {

    @Test
    @DisplayName("元信息通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginInfoValidator.validatePluginInfo("epay", EpayPluginInfo.get())).isEmpty();
    }
}
