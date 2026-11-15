package com.okpay.plugin.alipay;

import com.okpay.plugin.PaymentChannel;
import com.okpay.plugin.PluginDescriptorValidator;
import com.okpay.plugin.enums.BizType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * alipay 插件声明契约测试 — payment 启动加载与 manager 上传共用同一校验器。
 */
@DisplayName("alipay 插件声明契约测试")
class AlipayPluginDeclarationTest {

    @Test
    @DisplayName("descriptor 通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginDescriptorValidator.validate("alipay", new AlipayPlugin().descriptor())).isEmpty();
    }

    @Test
    @DisplayName("能力由注册表派生：支付宝下单、收银台、两个通知、退款/打款提交、四类查询")
    void capabilitiesDerivedFromHandlers() {
        var d = new AlipayPlugin().descriptor();

        assertThat(d.getDeclaration().getPayinMethods()).containsExactly("alipay");
        assertThat(d.getCapabilities().getCreateMethods()).containsExactly("alipay");
        assertThat(d.getCapabilities().getPageFuncs()).containsExactly(PaymentChannel.CASHIER_FUNC);
        assertThat(d.getCapabilities().getNotifyFuncs()).containsExactlyInAnyOrder("notify", "refundnotify");
        assertThat(d.getCapabilities().getSubmitBizTypes())
                .containsExactlyInAnyOrder(BizType.T_REF, BizType.T_XFER);
        assertThat(d.getCapabilities().getQueryBizTypes())
                .containsExactlyInAnyOrder(BizType.T_PAY, BizType.T_REF, BizType.T_XFER, BizType.T_BAL);
    }

    @Test
    @DisplayName("强制获取用户标识开关：字段注册 + 三个支付模式窗口均可见")
    void forceIdentitySwitchDeclared() {
        var declaration = new AlipayPlugin().descriptor().getDeclaration();

        assertThat(declaration.getConfig().field("force_identity")).isNotNull();
        assertThat(declaration.getConfig().getModes().get("direct").getFields())
                .contains("force_identity");
        assertThat(declaration.getConfig().getModes().get("isv").getFields())
                .contains("force_identity");
        assertThat(declaration.getConfig().getModes().get("zhifutong").getFields())
                .contains("force_identity");
    }
}
