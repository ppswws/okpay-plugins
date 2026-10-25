package com.okpay.plugin.alipay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.alipay.handler.*;
import org.pf4j.Extension;

/**
 * 支付宝支付通道插件。
 *
 * <p>零第三方 SDK，走 AlipayOpenApiClient；支付方式通过 {@code biztype_alipay} 配置勾选，
 * 插件根据配置 + UA 自动选择最合适的支付模式。</p>
 */
@Extension
public class AlipayPlugin extends AbstractPaymentChannel {

    private final AlipayCreateHandler createHandler = new AlipayCreateHandler();
    private final AlipayNotifyHandler notifyHandler = new AlipayNotifyHandler();
    private final AlipayQueryHandler queryHandler = new AlipayQueryHandler();
    private final AlipaySubmitHandler submitHandler = new AlipaySubmitHandler();

    public AlipayPlugin() {
        super(AlipayPluginDeclaration.declaration());
        onCreate("alipay", createHandler::alipay);
        onNotify("notify", notifyHandler::payNotify);
        onNotify("refundnotify", notifyHandler::refundNotify);
        onSubmit(BizType.T_REF, submitHandler::handle);
        onSubmit(BizType.T_XFER, submitHandler::handle);
        onQuery(BizType.T_PAY, queryHandler::handle);
        onQuery(BizType.T_REF, queryHandler::handle);
        onQuery(BizType.T_XFER, queryHandler::handle);
        onQuery(BizType.T_BAL, queryHandler::handle);
    }
}
