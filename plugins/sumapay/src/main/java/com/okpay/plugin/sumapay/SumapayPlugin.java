package com.okpay.plugin.sumapay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.sumapay.handler.*;
import org.pf4j.Extension;

/**
 * 丰付支付通道插件。
 */
@Extension
public class SumapayPlugin extends AbstractPaymentChannel {

    private final SumapayCreateHandler createHandler = new SumapayCreateHandler();
    private final SumapayNotifyHandler notifyHandler = new SumapayNotifyHandler();
    private final SumapaySubmitHandler submitHandler = new SumapaySubmitHandler();
    private final SumapayQueryHandler queryHandler = new SumapayQueryHandler();

    public SumapayPlugin() {
        super(SumapayPluginDeclaration.declaration());
        onCreate("alipay", createHandler::alipay);
        onCreate("wxpay", createHandler::wxpay);
        onNotify("notify", notifyHandler::notify);
        onNotify("refundnotify", notifyHandler::refundNotify);
        onNotify("paymerchantnotify", notifyHandler::payMerchantNotify);
        onNotify("transfernotify", notifyHandler::transferNotify);
        onSubmit(BizType.T_REF, submitHandler::handle);
        onSubmit(BizType.T_XFER, submitHandler::handle);
        onQuery(BizType.T_PAY, queryHandler::handle);
        onQuery(BizType.T_XFER, queryHandler::handle);
        onQuery(BizType.T_BAL, queryHandler::handle);
    }
}
