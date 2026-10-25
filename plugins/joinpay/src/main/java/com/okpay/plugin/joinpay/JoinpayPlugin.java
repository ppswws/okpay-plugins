package com.okpay.plugin.joinpay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.joinpay.handler.*;
import org.pf4j.Extension;

/**
 * 汇聚支付通道插件。
 */
@Extension
public class JoinpayPlugin extends AbstractPaymentChannel {

    private final JoinpayCreateHandler createHandler = new JoinpayCreateHandler();
    private final JoinpayNotifyHandler notifyHandler = new JoinpayNotifyHandler();
    private final JoinpaySubmitHandler submitHandler = new JoinpaySubmitHandler();
    private final JoinpayQueryHandler queryHandler = new JoinpayQueryHandler();

    public JoinpayPlugin() {
        super(JoinpayPluginDeclaration.declaration());
        onCreate("alipay", createHandler::alipay);
        onCreate("wxpay", createHandler::wxpay);
        onCreate("bank", createHandler::bank);
        onNotify("notify", notifyHandler::payNotify);
        onNotify("refundnotify", notifyHandler::refundNotify);
        onNotify("transfernotify", notifyHandler::transferNotify);
        onSubmit(BizType.T_REF, submitHandler::handle);
        onSubmit(BizType.T_XFER, submitHandler::handle);
        onQuery(BizType.T_PAY, queryHandler::handle);
        onQuery(BizType.T_REF, queryHandler::handle);
        onQuery(BizType.T_XFER, queryHandler::handle);
        onQuery(BizType.T_BAL, queryHandler::handle);
    }
}
