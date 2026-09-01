package com.okpay.plugin.sumapay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.model.*;
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
        on("create",  createHandler::create);
        on("alipay",  createHandler::alipay);
        on("wxpay",   createHandler::wxpay);
        on("notify",  notifyHandler::notify);
        on("refundnotify",  notifyHandler::refundNotify);
        on("paymerchantnotify", notifyHandler::payMerchantNotify);
    }

    @Override public PluginInfo info() { return SumapayPluginInfo.get(); }

    @Override
    public BizResult submit(InvokeContext ctx, BizRequest req) {
        return submitHandler.handle(ctx, req);
    }

    @Override
    public BizResult query(InvokeContext ctx, BizRequest req) {
        return queryHandler.handle(ctx, req);
    }
}
