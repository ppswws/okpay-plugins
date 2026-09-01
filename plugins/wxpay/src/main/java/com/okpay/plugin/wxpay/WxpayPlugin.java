package com.okpay.plugin.wxpay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.*;
import com.okpay.plugin.wxpay.handler.*;
import org.pf4j.Extension;

/**
 * 微信支付通道插件。
 *
 * <p>基于 WxJava SDK，支付方式通过 {@code biztype_wxpay} 配置勾选，
 * 插件根据配置 + UA 自动选择最合适的支付模式。</p>
 */
@Extension
public class WxpayPlugin extends AbstractPaymentChannel {

    private final WxpayCreateHandler createHandler = new WxpayCreateHandler();
    private final WxpayNotifyHandler notifyHandler = new WxpayNotifyHandler();
    private final WxpayQueryHandler queryHandler = new WxpayQueryHandler();
    private final WxpaySubmitHandler submitHandler = new WxpaySubmitHandler();

    public WxpayPlugin() {
        on("wxpay",  createHandler::wxpay);

        // 通知
        on("notify",       notifyHandler::payNotify);
        on("refundnotify", notifyHandler::refundNotify);
    }

    @Override
    public PluginInfo info() {
        return WxpayPluginInfo.get();
    }

    @Override
    public BizResult submit(InvokeContext ctx, BizRequest req) {
        if (req.getBizType() == BizType.T_PAY) {
            return createHandler.submit(ctx);
        }
        return submitHandler.handle(ctx, req);
    }

    @Override
    public BizResult query(InvokeContext ctx, BizRequest req) {
        return queryHandler.handle(ctx, req);
    }
}
