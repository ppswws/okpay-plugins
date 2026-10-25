package com.okpay.plugin.wxpay;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.wxpay.handler.WxpayComplainCore;
import org.pf4j.Extension;

import java.util.List;

/**
 * 微信支付投诉处理扩展 — 与支付扩展（{@link WxpayPlugin}）同 jar。
 *
 * <p>一个插件 jar 同时提供 PaymentChannel + ComplaintHandler 两种扩展，
 * 宿主按扩展类型分别路由（支付通道 / 投诉应用）。投诉窗口由
 * {@link WxpayPluginDeclaration#declaration()} 的 {@code complaint} 声明（字段注册表与
 * authModes 复用支付配置），本扩展只承载投诉 API 操作。</p>
 */
@Extension
public class WxpayComplainHandler extends WxpayComplainCore implements ComplaintHandler {

    /** 微信无商家补充凭证 API，留空。 */
    @Override
    public void supplementSubmit(ChannelConfig config, String complaintId,
                                 String content, List<String> images) {
    }
}
