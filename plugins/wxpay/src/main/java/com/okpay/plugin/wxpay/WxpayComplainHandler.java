package com.okpay.plugin.wxpay;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.model.ComplaintPluginInfo;
import com.okpay.plugin.model.FieldOverride;
import com.okpay.plugin.model.PluginMode;
import com.okpay.plugin.wxpay.handler.WxpayComplainCore;
import org.pf4j.Extension;

import java.util.List;
import java.util.Map;

/**
 * 微信支付投诉处理扩展 — 与支付扩展（{@link WxpayPlugin}）同 jar。
 *
 * <p>一个插件 jar 同时提供 PaymentChannel + ComplaintHandler 两种扩展，
 * 宿主按扩展类型分别路由（支付通道 / 投诉应用），配置字段各自独立渲染。</p>
 */
@Extension
public class WxpayComplainHandler extends WxpayComplainCore implements ComplaintHandler {

    @Override
    public ComplaintPluginInfo info() {
        // 混合 jar：字段注册表与支付扩展共享（WxpayPluginInfo.fields，ADR-0013），投诉 modes 只引用其 key。
        // 服务商/收付通恒拉名下全部子商户，不提供按子商户过滤的配置项。
        var credentials = List.of("appmchid", "appkey", "appsecret", "privateKey");
        // 服务商/收付通的 appmchid 是签名主体（服务商商户号），与直连的自身商户号区分展示
        var spMchOverride = Map.of("appmchid", FieldOverride.builder()
                .name("商户号/服务商商户号")
                .note("直连商户填写自己的微信支付商户号；服务商/收付通填写服务商商户号").build());
        return ComplaintPluginInfo.builder()
                .id("wxpay").name("微信投诉处理").kind("wxpay")
                .modes(Map.of(
                    "direct", PluginMode.builder().label("直连商户").fields(credentials).build(),
                    "service", PluginMode.builder().label("服务商").fields(credentials)
                            .overrides(spMchOverride).build(),
                    "shoufutong", PluginMode.builder().label("收付通").fields(credentials)
                            .overrides(spMchOverride).build()
                ))
                .defaultMode("direct")
                .build();
    }

    /** 微信无商家补充凭证 API，留空。 */
    @Override
    public void supplementSubmit(ChannelConfig config, String complaintId,
                                 String content, List<String> images) {
        // 微信侧无此能力
    }
}
