package com.okpay.plugin.alipay;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.PluginDescriptorValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝投诉能力声明契约测试：投诉由支付插件的 descriptor 声明（handler 本身不再携带元信息）。
 */
@DisplayName("支付宝投诉能力声明契约测试")
class AlipayComplainHandlerInfoTest {

    @Test
    @DisplayName("descriptor 声明投诉能力并通过统一校验器")
    void complaintDeclaredAndPassesValidator() {
        var descriptor = new AlipayPlugin().descriptor();
        var complaint = descriptor.getDeclaration().getComplaint();

        assertThat(complaint).isNotNull();
        assertThat(complaint.getKind()).isEqualTo("alipay");
        assertThat(complaint.getDefaultMode()).isEqualTo("direct");
        assertThat(complaint.getModes()).containsOnlyKeys("direct", "isv", "zhifutong");
        assertThat(PluginDescriptorValidator.validate("alipay", descriptor)).isEmpty();
    }

    @Test
    @DisplayName("投诉认证维度继承支付 authModes（不重复声明）、窗口只引用支付字段注册表")
    void complaintInheritsAuthModesAndReferencesRegistry() {
        var declaration = new AlipayPlugin().descriptor().getDeclaration();
        var complaint = declaration.getComplaint();
        var fields = declaration.getConfig().getFields();

        assertThat(declaration.getConfig().getAuthModes()).extracting(i -> i.getId())
                .containsExactly("key", "cert");
        assertThat(complaint.getModes().values()).allSatisfy(mode -> {
            assertThat(mode.getFields()).isNotEmpty();
            for (var key : mode.getFields()) assertThat(fields).containsKey(key);
        });
        // 服务商/直付通投诉：子商户授权令牌语义改写（留空=应用身份处理名下全部子商户）
        assertThat(complaint.getModes().get("isv").getOverrides()).containsKeys("auth_token");
        assertThat(complaint.getModes().get("isv").getOverrides().get("auth_token").getName())
                .isEqualTo("子商户授权令牌");
    }

    @Test
    @DisplayName("同 jar 提供 PaymentChannel + ComplaintHandler 两种扩展")
    void bothExtensionsProvided() {
        assertThat(new AlipayComplainHandler()).isInstanceOf(ComplaintHandler.class);
        assertThat(new AlipayPlugin().descriptor().getDeclaration().getComplaint()).isNotNull();
    }
}
