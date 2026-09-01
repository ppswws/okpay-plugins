package com.okpay.plugin.alipay;

import com.okpay.plugin.AbstractPaymentChannel;
import com.okpay.plugin.model.*;
import com.okpay.plugin.alipay.handler.*;
import org.pf4j.Extension;

/**
 * 支付宝支付通道插件。
 *
 * <p>基于 alipay-sdk-java，支付方式通过 {@code biztype_alipay} 配置勾选，
 * 插件根据配置 + UA 自动选择最合适的支付模式。</p>
 */
@Extension
public class AlipayPlugin extends AbstractPaymentChannel {

    private final AlipayCreateHandler createHandler = new AlipayCreateHandler();
    private final AlipayNotifyHandler notifyHandler = new AlipayNotifyHandler();
    private final AlipayQueryHandler queryHandler = new AlipayQueryHandler();
    private final AlipaySubmitHandler submitHandler = new AlipaySubmitHandler();

    public AlipayPlugin() {
        // 下单（内部根据 biztype_alipay 配置分发具体模式）
        on("create",  createHandler::create);
        on("alipay",  createHandler::alipay);

        // 通知
        on("notify",       notifyHandler::payNotify);
        on("refundnotify", notifyHandler::refundNotify);
    }

    @Override
    public PluginInfo info() {
        return AlipayPluginInfo.get();
    }

    @Override
    public BizResult submit(InvokeContext ctx, BizRequest req) {
        return submitHandler.handle(ctx, req);
    }

    @Override
    public BizResult query(InvokeContext ctx, BizRequest req) {
        return queryHandler.handle(ctx, req);
    }
}
