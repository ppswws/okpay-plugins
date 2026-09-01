package com.okpay.plugin.helipay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.helipay.handler.*;
import com.okpay.plugin.model.*;
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
        on("create",  createHandler::create);
        on("alipay",  createHandler::alipay);
        on("wxpay",   createHandler::wxpay);
        on("bank",    createHandler::bank);
        on("notify",  notifyHandler::payNotify);
        on("refundnotify",  notifyHandler::refundNotify);
        on("transfernotify", notifyHandler::transferNotify);
    }

    @Override public PluginInfo info() { return HelipayPluginInfo.get(); }

    @Override
    public BizResult submit(InvokeContext ctx, BizRequest req) {
        return submitHandler.handle(ctx, req);
    }

    @Override
    public BizResult query(InvokeContext ctx, BizRequest req) {
        return queryHandler.handle(ctx, req);
    }
}
