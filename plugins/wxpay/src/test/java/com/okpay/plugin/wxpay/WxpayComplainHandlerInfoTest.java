package com.okpay.plugin.wxpay;

import com.okpay.plugin.PluginDescriptorValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信投诉声明契约测试 — 投诉窗口随支付声明（complaint）下发，payment 启动加载与 manager 上传
 * 共用同一校验器，声明任何字段改动不满足契约时在此失败，而非运行时才暴露。
 */
@DisplayName("微信投诉声明契约测试")
class WxpayComplainHandlerInfoTest {

    @Test
    @DisplayName("声明携带投诉能力且通过统一校验器")
    void descriptorDeclaresComplaint() {
        var descriptor = new WxpayPlugin().descriptor();

        var complaint = descriptor.getDeclaration().getComplaint();
        assertThat(complaint).isNotNull();
        assertThat(complaint.getKind()).isEqualTo("wxpay");
        assertThat(complaint.getDefaultMode()).isEqualTo("direct");
        assertThat(PluginDescriptorValidator.validate("wxpay", descriptor)).isEmpty();
    }

    @Test
    @DisplayName("投诉模式只引用支付注册表中的凭据字段（服务商/收付通覆盖商户号展示名）")
    void complaintModesReferencePaymentRegistry() {
        var complaint = new WxpayPlugin().descriptor().getDeclaration().getComplaint();

        assertThat(complaint.getModes().keySet()).containsExactlyInAnyOrder("direct", "service", "shoufutong");
        var credentials = List.of("appmchid", "appkey", "appsecret", "privateKey");
        assertThat(complaint.getModes().get("direct").getFields()).isEqualTo(credentials);
        assertThat(complaint.getModes().get("service").getOverrides().get("appmchid").getName())
                .isEqualTo("商户号/服务商商户号");
        assertThat(complaint.getModes().get("shoufutong").getOverrides().get("appmchid").getName())
                .isEqualTo("商户号/服务商商户号");
    }
}
