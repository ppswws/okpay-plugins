package com.okpay.plugin.helipay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.helipay.handler.*;
import org.pf4j.Extension;

/**
 * 合利宝通道插件。
 */
@Extension
public class HelipayPlugin extends AbstractPaymentChannel {

    private final HelipayCreateHandler createHandler = new HelipayCreateHandler();
    private final HelipayNotifyHandler notifyHandler = new HelipayNotifyHandler();
    private final HelipaySubmitHandler submitHandler = new HelipaySubmitHandler();
    private final HelipayQueryHandler queryHandler = new HelipayQueryHandler();

    public HelipayPlugin() {
        super(HelipayPluginDeclaration.declaration());
        onCreate("alipay",  createHandler::alipay);
        onCreate("wxpay",   createHandler::wxpay);
        onCreate("bank",    createHandler::bank);
        onNotify("notify",  notifyHandler::payNotify);
        onNotify("refundnotify",  notifyHandler::refundNotify);
        onNotify("transfernotify", notifyHandler::transferNotify);
        onSubmit(BizType.T_REF,  submitHandler::handle);
        onSubmit(BizType.T_XFER, submitHandler::handle);
        onQuery(BizType.T_PAY,  queryHandler::handle);
        onQuery(BizType.T_REF,  queryHandler::handle);
        onQuery(BizType.T_XFER, queryHandler::handle);
        onQuery(BizType.T_BAL,  queryHandler::handle);
    }
}
