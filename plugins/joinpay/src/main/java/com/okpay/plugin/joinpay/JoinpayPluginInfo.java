package com.okpay.plugin.joinpay;

import com.okpay.plugin.model.*;

import java.util.List;
import java.util.Map;

/**
 * 汇聚支付插件元信息 — 字段注册表 + 窗口引用（ADR-0013）。
 *
 * <p>{@code fields} 是全量字段注册表（支付配置 + 付款提交），{@code modes}（支付通道配置）、
 * {@code payoutSubmitFields}（付款提交）、{@code payoutChannelFields}（付款通道配置）都是窗口，
 * 只引用注册表 key。支付配置为单一收单形态（无多模式），声明单模式 {@code default} 仅用于
 * 把支付窗口收敛到支付字段，避免平铺把付款提交字段带进支付通道配置。</p>
 *
 * <p>打款对公/对私：显式 accountType 输入（对公/对私），对公打款 cnapsNo 必填。</p>
 */
final class JoinpayPluginInfo {

    private JoinpayPluginInfo() {}

    static PluginInfo get() {
        return PluginInfo.builder()
                .id("joinpay").name("汇聚支付").link("https://www.joinpay.com/")
                .payinMethods(List.of("alipay", "wxpay", "bank"))
                .payoutMethods(List.of("bank"))
                .payoutSubmitFields(List.of("accountType", "cardNo", "cardName", "cnapsNo"))
                .payoutChannelFields(List.of("appid", "appkey"))
                .modes(Map.of(
                    "default", PluginMode.builder().label("默认配置")
                            .fields(List.of("appid", "appkey", "appmchid",
                                    "biztype_alipay", "biztype_wxpay", "biztype_bank")).build()
                ))
                .defaultMode("default")
                .bindWxmp(true).bindWxa(true)
                .fields(Map.ofEntries(
                    Map.entry("appid", InputField.builder().name("商户编号").type("input").note("商户编号与商户密钥均在汇聚支付门户系统获取").required(true).build()),
                    Map.entry("appkey", InputField.builder().name("商户密钥").type("input").note("MD5 签名商户密钥").required(true).build()),
                    Map.entry("appmchid", InputField.builder().name("报备商户号").type("input").note("报备产品后返回的商户号；支付必填，付款接口为选填").required(true).build()),
                    Map.entry("biztype_alipay", InputField.builder().name("支付宝方式").type("checkbox")
                            .options(Map.of("1","支付宝扫码","2","支付宝H5")).build()),
                    Map.entry("biztype_wxpay", InputField.builder().name("微信方式").type("checkbox")
                            .options(Map.of("1","微信扫码","2","微信H5","3","微信公众号","4","微信小程序")).build()),
                    Map.entry("biztype_bank", InputField.builder().name("云闪付方式").type("checkbox")
                            .options(Map.of("1","云闪付扫码","2","云闪付H5")).build()),
                    Map.entry("accountType", InputField.builder().name("账户类型").type("select")
                            .options(Map.of("private", "对私", "public", "对公"))
                            .note("对公打款须填写联行号").required(true).build()),
                    Map.entry("cardNo", InputField.builder().name("银行卡号").type("input").required(true).build()),
                    Map.entry("cardName", InputField.builder().name("户名").type("input").required(true).build()),
                    Map.entry("cnapsNo", InputField.builder().name("联行号").type("input").note("对公打款必填").build())
                ))
                .note("支持支付宝/微信/云闪付扫码、H5、公众号、小程序支付，以及银行卡打款。")
                .build();
    }
}
