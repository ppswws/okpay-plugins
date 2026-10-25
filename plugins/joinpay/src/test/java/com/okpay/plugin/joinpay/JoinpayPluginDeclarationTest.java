package com.okpay.plugin.joinpay;

import com.okpay.plugin.PluginDescriptorValidator;
import com.okpay.plugin.enums.BizType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * joinpay 插件声明契约测试 — payment 启动加载与 manager 上传共用同一校验器。
 */
@DisplayName("joinpay 插件声明契约测试")
class JoinpayPluginDeclarationTest {

    @Test
    @DisplayName("descriptor 通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginDescriptorValidator.validate("joinpay", new JoinpayPlugin().descriptor())).isEmpty();
    }

    @Test
    @DisplayName("能力由注册表派生：三种支付方式下单、退款/打款提交、四类查询、三个通知")
    void capabilitiesDerivedFromHandlers() {
        var d = new JoinpayPlugin().descriptor();

        assertThat(d.getCapabilities().getCreateMethods()).containsExactlyInAnyOrder("alipay", "wxpay", "bank");
        assertThat(d.getCapabilities().getPageFuncs()).containsExactly("cashier");
        assertThat(d.getCapabilities().getNotifyFuncs())
                .containsExactlyInAnyOrder("notify", "refundnotify", "transfernotify");
        assertThat(d.getCapabilities().getSubmitBizTypes())
                .containsExactlyInAnyOrder(BizType.T_REF, BizType.T_XFER);
        assertThat(d.getCapabilities().getQueryBizTypes())
                .containsExactlyInAnyOrder(BizType.T_PAY, BizType.T_REF, BizType.T_XFER, BizType.T_BAL);
    }
}
