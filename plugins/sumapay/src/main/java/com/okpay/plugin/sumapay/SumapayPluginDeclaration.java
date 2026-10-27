package com.okpay.plugin.sumapay;

import com.okpay.plugin.model.InputField;
import com.okpay.plugin.model.PluginDeclaration;

import java.util.List;
import java.util.Map;

/**
 * 丰付支付插件声明 — 集中定义，避免散落在插件主类中。
 */
final class SumapayPluginDeclaration {

    private SumapayPluginDeclaration() {}

    static PluginDeclaration declaration() {
        return PluginDeclaration.builder()
                .id("sumapay").name("丰付支付").link("https://www.sumapay.com/")
                .payinMethods(List.of("alipay", "wxpay"))
                .payoutMethods(List.of("bank"))
                .config(PluginDeclaration.Config.builder()
                        .fields(Map.ofEntries(
                                Map.entry("appid",    InputField.builder().name("商户编号").type("input")
                                        .note("商户平台开户后获得的商户编号").required(true).build()),
                                Map.entry("appuserid", InputField.builder().name("二级商户标识").type("input")
                                        .note("收款子账户的第三方标识").required(true).build()),
                                Map.entry("appmchid", InputField.builder().name("子商户编码").type("input")
                                        .note("支付宝/微信子商户号").required(true).build()),
                                Map.entry("appkey",   InputField.builder().name("丰付公钥").type("textarea")
                                        .note("支持 PEM 或 Base64 格式").required(true).build()),
                                Map.entry("appsecret", InputField.builder().name("商户私钥").type("textarea")
                                        .note("支持 PEM 或 Base64 格式").required(true).build()),
                                Map.entry("biztype_alipay", InputField.builder().name("支付宝方式").type("checkbox")
                                        .options(Map.of("1", "支付宝H5", "2", "聚合扫码")).build()),
                                Map.entry("biztype_wxpay",  InputField.builder().name("微信方式").type("checkbox")
                                        .options(Map.of("1", "微信H5", "2", "微信公众号", "3", "聚合扫码")).build()),
                                Map.entry("cardNo", InputField.builder().name("银行卡号").type("input").required(true).build()),
                                Map.entry("cardName", InputField.builder().name("户名").type("input").required(true).build())
                        ))
                        .payoutSubmitFields(List.of("cardNo", "cardName"))
                        .payoutChannelFields(List.of("appid", "appkey", "appsecret"))
                        .bindWxmp(true)
                        .note("支持支付宝/微信 H5、微信公众号与聚合扫码支付，以及银行卡打款（对私）、余额查询。")
                        .build())
                .build();
    }
}
