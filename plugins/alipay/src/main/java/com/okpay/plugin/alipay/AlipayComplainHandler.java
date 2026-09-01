package com.okpay.plugin.alipay;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.alipay.handler.AlipayComplainCore;
import com.okpay.plugin.model.ComplaintPluginInfo;
import com.okpay.plugin.model.FieldOverride;
import com.okpay.plugin.model.PluginAuthMode;
import com.okpay.plugin.model.PluginMode;
import org.pf4j.Extension;

import java.util.List;
import java.util.Map;

/**
 * 支付宝投诉处理扩展 — 与支付扩展（{@link AlipayPlugin}）同 jar。
 *
 * <p>一个插件 jar 同时提供 PaymentChannel + ComplaintHandler 两种扩展，
 * 宿主按扩展类型分别路由（支付通道 / 投诉应用），配置字段各自独立渲染。</p>
 *
 * <p>配置模式：直连商户 / 服务商(ISV) / 直付通。投诉按应用归属（tradecomplain
 * 交易投诉体系）：服务商/直付通模式 auth_token 留空 = 应用身份，
 * 处理名下所有（已授权）子商户的投诉——一个投诉应用即可覆盖全部子商户，回调
 * 也在官方控制台按应用配置一次；填写子商户授权令牌 = 只查/只处理该授权商户。</p>
 */
@Extension
public class AlipayComplainHandler extends AlipayComplainCore implements ComplaintHandler {

    @Override
    public ComplaintPluginInfo info() {
        // 混合 jar：字段注册表与支付扩展共享（AlipayPluginInfo.fields，ADR-0013），投诉 modes/authModes 只引用其 key。
        // 凭据字段（appkey/appsecret/证书三件）移入 authModes（认证维度），业务 modes 只留业务字段；
        // 证书模式仍需应用私钥（appsecret）签名，故同时出现在 key/cert 两认证模式。
        return ComplaintPluginInfo.builder()
                .id("alipay").name("支付宝投诉处理").kind("alipay")
                .modes(Map.of(
                    "direct", PluginMode.builder().label("直连商户")
                            .fields(List.of("appid", "is_prod")).build(),
                    "isv", PluginMode.builder().label("服务商(ISV)")
                            .fields(List.of("appid", "is_prod", "auth_token"))
                            .overrides(Map.of("auth_token", FieldOverride.builder()
                                    .name("子商户授权令牌")
                                    .note("留空=处理服务商名下所有子商户的投诉；填写=仅该子商户").build()))
                            .build(),
                    "zhifutong", PluginMode.builder().label("直付通")
                            .fields(List.of("appid", "is_prod", "auth_token"))
                            .overrides(Map.of("auth_token", FieldOverride.builder()
                                    .name("子商户授权令牌")
                                    .note("留空=处理直付通名下所有子商户的投诉；填写=仅该子商户").build()))
                            .build()
                ))
                .defaultMode("direct")
                .authModes(List.of(
                    PluginAuthMode.builder().id("key").label("公钥/私钥")
                            .fields(List.of("appkey", "appsecret")).build(),
                    PluginAuthMode.builder().id("cert").label("证书模式")
                            .fields(List.of("app_cert", "alipay_cert", "root_cert", "appsecret")).build()
                ))
                .defaultAuthMode("key")
                .build();
    }
}
