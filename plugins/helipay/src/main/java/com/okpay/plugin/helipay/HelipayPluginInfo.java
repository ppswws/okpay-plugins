package com.okpay.plugin.helipay;

import com.okpay.plugin.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合利宝插件元信息 — 字段注册表 + 窗口引用（ADR-0013）。
 *
 * <p>{@code fields} 是全量字段注册表（支付配置 + 付款提交），{@code modes}（支付通道配置）、
 * {@code payoutSubmitFields}（付款提交）、{@code payoutChannelFields}（付款通道配置）都是窗口，
 * 只引用注册表 key。支付配置为单一收单形态（无多模式），声明单模式 {@code default} 仅用于
 * 把支付窗口收敛到支付字段，避免平铺把付款提交字段带进支付通道配置。</p>
 *
 * <p>打款对公/对私：显式 accountType 输入（对公/对私，跟随 joinpay 定义），对公打款 cnapsNo 必填。</p>
 */
final class HelipayPluginInfo {

    private HelipayPluginInfo() {}

    /** 打款银行下拉：通道支持的银行项目（编码 → 银行名，按通道银行编码表） */
    private static final Map<String, String> BANKS = banks();

    static PluginInfo get() {
        return PluginInfo.builder()
                .id("helipay").name("合利宝").link("https://www.helipay.com/")
                .payinMethods(List.of("alipay", "wxpay", "bank"))
                .payoutMethods(List.of("bank"))
                .payoutSubmitFields(List.of("accountType", "bankCode", "cardNo", "cardName", "cnapsNo"))
                .payoutChannelFields(List.of("appid", "appkey"))
                .modes(Map.of(
                    "default", PluginMode.builder().label("默认配置")
                            .fields(List.of("appid", "appkey", "appmchid",
                                    "biztype_alipay", "biztype_wxpay", "biztype_bank")).build()
                ))
                .defaultMode("default")
                .bindWxmp(true).bindWxa(true)
                .fields(Map.ofEntries(
                    Map.entry("appid", InputField.builder().name("商户编号").type("input").note("合利宝分配的商户号").required(true).build()),
                    Map.entry("appkey", InputField.builder().name("商户密钥").type("input")
                            .note("商户平台「密钥管理」获取的 MD5 签名密钥").required(true).build()),
                    Map.entry("appmchid", InputField.builder().name("报备商户号").type("input").note("报备时获得的报备商户号").build()),
                    Map.entry("biztype_alipay", InputField.builder().name("支付宝方式").type("checkbox")
                            .options(Map.of("1","JSAPI","2","小程序","3","WAP(H5)","4","扫码")).build()),
                    Map.entry("biztype_wxpay", InputField.builder().name("微信方式").type("checkbox")
                            .options(Map.of("1","JSAPI","2","小程序","3","WAP(H5)","4","扫码")).build()),
                    Map.entry("biztype_bank", InputField.builder().name("云闪付方式").type("checkbox")
                            .options(Map.of("1","云闪付")).build()),
                    Map.entry("accountType", InputField.builder().name("账户类型").type("select")
                            .options(Map.of("private", "对私", "public", "对公"))
                            .note("对公打款须填写联行号").required(true).build()),
                    Map.entry("bankCode", InputField.builder().name("银行").type("select")
                            .options(BANKS).required(true).build()),
                    Map.entry("cardNo", InputField.builder().name("银行卡号").type("input").required(true).build()),
                    Map.entry("cardName", InputField.builder().name("户名").type("input").required(true).build()),
                    Map.entry("cnapsNo", InputField.builder().name("联行号").type("input").note("对公账户必填").build())
                ))
                .note("支持支付宝/微信/云闪付扫码、WAP、JSAPI、小程序支付，以及银行卡打款。")
                .build();
    }

    private static Map<String, String> banks() {
        var map = new LinkedHashMap<String, String>();
        map.put("ABC", "中国农业银行");
        map.put("CMBCHINA", "招商银行");
        map.put("CCB", "中国建设银行");
        map.put("BOCO", "交通银行");
        map.put("BOC", "中国银行");
        map.put("CMBC", "中国民生银行");
        map.put("CGB", "广发银行");
        map.put("HXB", "华夏银行");
        map.put("POST", "中国邮政储蓄银行");
        map.put("ECITIC", "中信银行");
        map.put("CEB", "中国光大银行");
        map.put("PINGAN", "平安银行");
        map.put("CIB", "兴业银行");
        map.put("SPDB", "浦发银行");
        map.put("BCCB", "北京银行");
        map.put("BON", "南京银行");
        map.put("NBCB", "宁波银行");
        map.put("BEA", "东亚银行");
        map.put("SRCB", "上海农商银行");
        map.put("SHB", "上海银行");
        map.put("CZB", "浙商银行");
        map.put("TCCB", "天津银行");
        map.put("HSBANK", "徽商银行");
        map.put("HFBANK", "恒丰银行");
        map.put("CBHB", "渤海银行");
        map.put("JSB", "江苏银行");
        map.put("CITI", "花旗银行");
        map.put("THX", "贵阳银行");
        map.put("HANGSENGBANK", "恒生银行");
        map.put("GDNYBANK", "南粤银行");
        map.put("LZBANK", "兰州银行");
        return map;
    }
}
