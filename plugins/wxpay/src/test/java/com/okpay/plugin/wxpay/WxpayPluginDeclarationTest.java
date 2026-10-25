package com.okpay.plugin.wxpay;

import com.okpay.plugin.PluginDescriptorValidator;
import com.okpay.plugin.enums.BizType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wxpay 插件声明契约测试 — payment 启动加载与 manager 上传共用同一校验器，
 * 声明任何字段（type/options/modes/window 引用）改动不满足契约时在此失败，而非运行时才暴露。
 */
@DisplayName("wxpay 插件声明契约测试")
class WxpayPluginDeclarationTest {

    @Test
    @DisplayName("descriptor 通过统一校验器")
    void metadataPassesValidator() {
        assertThat(PluginDescriptorValidator.validate("wxpay", new WxpayPlugin().descriptor())).isEmpty();
    }

    @Test
    @DisplayName("能力由注册表派生：微信下单 + cashier、两个通知、退款提交、订单/退款查询；无付款能力")
    void capabilitiesDerivedFromHandlers() {
        var d = new WxpayPlugin().descriptor();

        assertThat(d.getCapabilities().getCreateMethods()).containsExactly("wxpay");
        assertThat(d.getCapabilities().getPageFuncs()).containsExactly("cashier");
        assertThat(d.getCapabilities().getNotifyFuncs())
                .containsExactlyInAnyOrder("notify", "refundnotify");
        assertThat(d.getCapabilities().getSubmitBizTypes()).containsExactly(BizType.T_REF);
        assertThat(d.getCapabilities().getQueryBizTypes())
                .containsExactlyInAnyOrder(BizType.T_PAY, BizType.T_REF);
        // 微信商家转账限制多，插件不提供付款能力
        assertThat(d.getDeclaration().getPayoutMethods()).isEmpty();
    }
}
