package com.okpay.plugin.wxpay;

import com.okpay.plugin.model.*;

import java.util.List;
import java.util.Map;

/**
 * 微信支付插件声明 — 字段注册表 + 窗口引用。
 *
 * <p>{@code config.fields} 是全量字段注册表（支付配置），{@code config.modes}（支付通道配置）、
 * {@code config.authModes}（验签凭据维度）与投诉窗口 {@code complaint.modes} 都只引用注册表 key。</p>
 *
 * <p>字段语义：{@code appid}=服务号/小程序/开放平台AppID、{@code appmchid}=商户号；
 * 子商户号独立为 {@code sub_mchid}（服务商/收付通）。</p>
 *
 * <p>配置模式（modes）：直连商户 / 服务商 / 收付通。模式只影响前端渲染字段集合，
 * 运行期逻辑由字段值驱动（sub_mchid 有值即服务商/收付通，下单/查单挂 sub_mchid），
 * 插件无需感知 mode。服务商与收付通共享同一套字段（收付通为服务商资质下的产品，
 * 运行期 API 无差异），独立成模式仅为运营按资质看清该填哪些字段；
 * sub_mchid 按模式覆盖展示名（服务商=子商户号、收付通=收付通商户号）。</p>
 *
 * <p>认证凭据维度（authModes）：微信 v3 请求签名恒用商户 API 证书（privateKey+appkey），
 * 本维度只管响应/回调验签凭据——平台证书模式（自动下载）/ 微信支付公钥模式（新商户默认）。</p>
 *
 * <p>投诉（complaint）：与支付扩展同 jar 的 {@link WxpayComplainHandler} 共用本注册表与
 * authModes，投诉窗口只引用凭据字段 key；服务商/收付通恒拉名下全部子商户，
 * 不提供按子商户过滤的配置项。</p>
 *
 * <p>注：微信官方商家转账能力限制较多（新版须场景ID+报备+小程序内确认收款），
 * 本插件不提供付款能力，仅保留收款（支付）。</p>
 */
final class WxpayPluginDeclaration {

    private WxpayPluginDeclaration() {}

    static PluginDeclaration declaration() {
        var credentials = List.of("appmchid", "appkey", "appsecret", "privateKey");
        // 服务商/收付通的 appmchid 是签名主体（服务商商户号），与直连的自身商户号区分展示
        var spMchOverride = Map.of("appmchid", FieldOverride.builder()
                .name("商户号/服务商商户号")
                .note("直连商户填写自己的微信支付商户号；服务商/收付通填写服务商商户号").build());
        return PluginDeclaration.builder()
                .id("wxpay")
                .name("微信支付")
                .link("https://pay.weixin.qq.com/")
                .payinMethods(List.of("wxpay"))
                .config(PluginDeclaration.Config.builder()
                        .fields(Map.ofEntries(
                                Map.entry("appid", InputField.builder().name("服务号/小程序/开放平台AppID").type("input")
                                        .note("可留空：自动用绑定的公众号 → 小程序；APP支付须填开放平台移动应用AppID；须已在微信支付后台关联对应账号").build()),
                                Map.entry("appmchid", InputField.builder().name("商户号").type("input").required(true).build()),
                                Map.entry("appkey", InputField.builder().name("API证书序列号").type("input").required(true).build()),
                                Map.entry("appsecret", InputField.builder().name("APIv3密钥").type("input").required(true)
                                        .note("商户平台「账户中心-API安全」申请，32 位").build()),
                                Map.entry("privateKey", InputField.builder().name("商户API私钥").type("textarea").required(true)
                                        .note("商户平台申请 API 证书时下载的私钥文件内容").build()),
                                Map.entry("sub_mchid", InputField.builder().name("子商户号").type("input")
                                        .note("服务商/收付通模式必填，直连商户留空").build()),
                                Map.entry("publickeyid", InputField.builder().name("微信支付公钥ID").type("input")
                                        .note("微信支付公钥模式必填；平台证书模式留空").build()),
                                Map.entry("wxpay_public_key", InputField.builder().name("微信支付公钥").type("textarea")
                                        .note("商户平台「账户中心-API安全」下载的 pub_key.pem 文件内容（标准 PEM：-----BEGIN PUBLIC KEY-----）").build()),
                                Map.entry("biztype_wxpay", InputField.builder().name("微信支付方式").type("checkbox")
                                        .options(Map.of(
                                                "1", "扫码(Native)",
                                                "2", "JSAPI支付",
                                                "3", "H5支付",
                                                "4", "APP支付"
                                        )).note("多选；按买家访问设备自动分配（微信内→公众号，手机→H5，电脑→扫码，iPhone→APP）").build()),
                                Map.entry("wxcombine_open", InputField.builder().name("合单支付（大单拆小单）").type("switch")
                                        .note("起拆金额在平台配置维护").build())
                        ))
                        .modes(Map.of(
                                "direct", PluginMode.builder().label("直连商户")
                                        .fields(List.of("appid", "appmchid", "appkey", "appsecret", "privateKey",
                                                "wxcombine_open", "biztype_wxpay")).build(),
                                "service", PluginMode.builder().label("服务商")
                                        .fields(List.of("appid", "appmchid", "appkey", "appsecret", "privateKey", "sub_mchid",
                                                "wxcombine_open", "biztype_wxpay"))
                                        .overrides(Map.of("sub_mchid", FieldOverride.builder()
                                                .name("子商户号").note("服务商模式必填").required(true).build()))
                                        .build(),
                                "shoufutong", PluginMode.builder().label("收付通")
                                        .fields(List.of("appid", "appmchid", "appkey", "appsecret", "privateKey", "sub_mchid",
                                                "wxcombine_open", "biztype_wxpay"))
                                        .overrides(Map.of("sub_mchid", FieldOverride.builder()
                                                .name("收付通商户号").note("微信收付通平台子商户号，必填").required(true).build()))
                                        .build()
                        ))
                        .authModes(List.of(
                                PluginAuthMode.builder().id("platform_cert").label("平台证书模式")
                                        .fields(List.of()).build(),
                                PluginAuthMode.builder().id("public_key").label("微信支付公钥模式")
                                        .fields(List.of("publickeyid", "wxpay_public_key")).build()
                        ))
                        .defaultAuthMode("public_key")
                        .defaultMode("direct")
                        .bindWxmp(true)
                        .bindWxa(true)
                        .note("支持扫码/H5/公众号/小程序/APP支付。")
                        .build())
                .complaint(PluginDeclaration.ComplaintConfig.builder()
                        .kind("wxpay")
                        .modes(Map.of(
                                "direct", PluginMode.builder().label("直连商户").fields(credentials).build(),
                                "service", PluginMode.builder().label("服务商").fields(credentials)
                                        .overrides(spMchOverride).build(),
                                "shoufutong", PluginMode.builder().label("收付通").fields(credentials)
                                        .overrides(spMchOverride).build()
                        ))
                        .defaultMode("direct")
                        .build())
                .build();
    }
}
