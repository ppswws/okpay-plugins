package com.okpay.plugin.wxpay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.wxpay.handler.*;
import org.pf4j.Extension;

/**
 * 微信支付通道插件。
 *
 * <p>支付方式通过 {@code biztype_wxpay} 配置勾选，
 * 插件根据配置 + UA 自动选择最合适的支付模式。</p>
 */
@Extension
public class WxpayPlugin extends AbstractPaymentChannel {

    private final WxpayCreateHandler createHandler = new WxpayCreateHandler();
    private final WxpayNotifyHandler notifyHandler = new WxpayNotifyHandler();
    private final WxpayQueryHandler queryHandler = new WxpayQueryHandler();
    private final WxpaySubmitHandler submitHandler = new WxpaySubmitHandler();

    public WxpayPlugin() {
        super(WxpayPluginDeclaration.declaration());
        onCreate("wxpay", createHandler::wxpay);
        // 通知
        onNotify("notify", notifyHandler::payNotify);
        onNotify("refundnotify", notifyHandler::refundNotify);
        onSubmit(BizType.T_REF, submitHandler::handle);
        onQuery(BizType.T_PAY, queryHandler::handle);
        onQuery(BizType.T_REF, queryHandler::handle);
    }
}
