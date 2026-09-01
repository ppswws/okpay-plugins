package com.okpay.plugin.epay;

import com.okpay.plugin.model.*;

import java.util.List;
import java.util.Map;

/**
 * 易支付插件元信息 — 集中定义，避免散落在插件主类中。
 */
final class EpayPluginInfo {

    private EpayPluginInfo() {}

    static PluginInfo get() {
        return PluginInfo.builder()
                .id("epay")
                .name("彩虹易支付")
                .link("https://pay.cccyun.cc/")
                .payinMethods(List.of("alipay", "wxpay", "bank"))
                .fields(Map.of(
                    "appurl", InputField.builder().name("接口地址").type("input")
                            .note("必须以 http:// 或 https:// 开头").required(true).build(),
                    "appid",  InputField.builder().name("商户ID").type("input").required(true).build(),
                    "appkey", InputField.builder().name("商户密钥").type("input").required(true).build(),
                    "submit", InputField.builder().name("Submit模式").type("switch")
                            .note("开启后买家支付时直接跳转支付链接").build()
                ))
                .note("支持支付宝/微信/云闪付扫码与H5支付。")
                .build();
    }
}
