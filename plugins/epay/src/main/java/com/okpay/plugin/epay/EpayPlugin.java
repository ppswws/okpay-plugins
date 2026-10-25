package com.okpay.plugin.epay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.epay.handler.EpayCreateHandler;
import com.okpay.plugin.epay.handler.EpayNotifyHandler;
import com.okpay.plugin.epay.handler.EpayQueryHandler;
import org.pf4j.Extension;

/**
 * 彩虹易支付通道插件。
 *
 * <p>本类只做三件事：PF4J 注册、注册 handler、委托给各 Handler。
 * 业务逻辑分别在 {@code handler/} 下的独立类中。</p>
 */
@Extension
public class EpayPlugin extends AbstractPaymentChannel {

    private final EpayCreateHandler createHandler = new EpayCreateHandler();
    private final EpayNotifyHandler notifyHandler = new EpayNotifyHandler();
    private final EpayQueryHandler queryHandler = new EpayQueryHandler();

    public EpayPlugin() {
        super(EpayPluginDeclaration.declaration());
        onCreate("alipay", createHandler::alipay);
        onCreate("wxpay", createHandler::wxpay);
        onCreate("bank", createHandler::bank);
        onNotify("notify", notifyHandler::handle);
        onQuery(BizType.T_PAY, queryHandler::handle);
    }
}
