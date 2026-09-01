package com.okpay.plugin.epay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.*;
import com.okpay.plugin.epay.handler.*;
import org.pf4j.Extension;

/**
 * 彩虹易支付通道插件。
 *
 * <p>本类只做三件事：PF4J 注册、声明元信息、委托给各 Handler。
 * 业务逻辑分别在 {@code handler/} 下的独立类中。</p>
 */
@Extension
public class EpayPlugin extends AbstractPaymentChannel {

    private final EpayCreateHandler createHandler = new EpayCreateHandler();
    private final EpayNotifyHandler notifyHandler = new EpayNotifyHandler();
    private final EpayQueryHandler queryHandler = new EpayQueryHandler();
    private final EpaySubmitHandler submitHandler = new EpaySubmitHandler();

    public EpayPlugin() {
        on("alipay", createHandler::alipay);
        on("wxpay",  createHandler::wxpay);
        on("bank",   createHandler::bank);
        on("notify", notifyHandler::handle);
    }

    @Override
    public PluginInfo info() {
        return EpayPluginInfo.get();
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
