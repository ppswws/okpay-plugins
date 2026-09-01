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
        return ComplaintPluginInfo.builder()
                .id("wxpay").name("微信投诉处理").kind("wxpay")
                .modes(Map.of(
                    "direct", PluginMode.builder().label("直连商户")
                            .fields(List.of("appid", "appmchid", "appkey", "appsecret", "privateKey")).build(),
                    "service", PluginMode.builder().label("服务商")
                            .fields(List.of("appid", "appmchid", "appkey", "appsecret", "sub_mchid", "privateKey"))
                            .overrides(Map.of("sub_mchid", FieldOverride.builder()
                                    .name("被诉商户号")
                                    .note("留空=处理服务商名下所有子商户的投诉；填写=仅该子商户").build()))
                            .build(),
                    "shoufutong", PluginMode.builder().label("收付通")
                            .fields(List.of("appid", "appmchid", "appkey", "appsecret", "sub_mchid", "privateKey"))
                            .overrides(Map.of("sub_mchid", FieldOverride.builder()
                                    .name("被诉商户号")
                                    .note("留空=处理收付通名下所有子商户的投诉；填写=仅该子商户").build()))
                            .build()
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
