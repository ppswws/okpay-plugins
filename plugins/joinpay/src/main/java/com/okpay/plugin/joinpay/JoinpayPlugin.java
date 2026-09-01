package com.okpay.plugin.joinpay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.model.*;
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
        on("create",  createHandler::create);
        on("alipay",  createHandler::alipay);
        on("wxpay",   createHandler::wxpay);
        on("bank",    createHandler::bank);
        on("notify",  notifyHandler::payNotify);
        on("refundnotify",  notifyHandler::refundNotify);
        on("transfernotify", notifyHandler::transferNotify);
    }

    @Override public PluginInfo info() { return JoinpayPluginInfo.get(); }

    @Override
    public BizResult submit(InvokeContext ctx, BizRequest req) {
        return submitHandler.handle(ctx, req);
    }

    @Override
    public BizResult query(InvokeContext ctx, BizRequest req) {
        return queryHandler.handle(ctx, req);
    }
}
