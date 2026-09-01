package com.okpay.plugin.sumapay;

import com.okpay.plugin.model.*;

import java.util.List;
import java.util.Map;

/**
 * 丰付支付插件元信息。
 */
final class SumapayPluginInfo {

    private SumapayPluginInfo() {}

    static PluginInfo get() {
        return PluginInfo.builder()
                .id("sumapay").name("丰付支付").link("https://www.sumapay.com/")
                .payinMethods(List.of("alipay", "wxpay"))
                .bindWxmp(true)
                .fields(Map.of(
                    "appid",    InputField.builder().name("商户编号").type("input")
                            .note("商户平台开户后获得的商户编号").required(true).build(),
                    "appuserid", InputField.builder().name("二级商户标识").type("input")
                            .note("收款子账户的第三方标识").required(true).build(),
                    "appmchid", InputField.builder().name("子商户编码").type("input")
                            .note("支付宝/微信子商户号").required(true).build(),
                    "appkey",   InputField.builder().name("丰付公钥").type("textarea")
                            .note("支持 PEM 或 Base64 格式").required(true).build(),
                    "appsecret", InputField.builder().name("商户私钥").type("textarea")
                            .note("支持 PEM 或 Base64 格式").required(true).build(),
                    "biztype_alipay", InputField.builder().name("支付宝方式").type("checkbox")
                            .options(Map.of("1", "支付宝H5", "2", "聚合扫码")).build(),
                    "biztype_wxpay",  InputField.builder().name("微信方式").type("checkbox")
                            .options(Map.of("1", "微信H5", "2", "微信公众号", "3", "聚合扫码")).build()
                ))
                .note("支持支付宝/微信 H5、微信公众号与聚合扫码支付。非当日订单退款需先付款至二级户。")
                .build();
    }
}
