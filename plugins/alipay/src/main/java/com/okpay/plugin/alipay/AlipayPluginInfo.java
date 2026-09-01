package com.okpay.plugin.alipay;

import com.okpay.plugin.model.*;

import java.util.List;
import java.util.Map;

/**
 * 支付宝插件元信息 — 字段注册表 + 窗口引用（ADR-0013）。
 *
 * <p>{@code fields} 是全量字段注册表（支付配置 + 付款提交），{@code modes}（支付通道配置）、
 * {@code payoutSubmitFields}（付款提交）、{@code payoutChannelFields}（付款通道配置）都是窗口，
 * 只引用注册表 key。混合同 jar 投诉扩展（{@link AlipayComplainHandler}）也引用本注册表 key。</p>
 *
 * <p>配置模式（modes）：直连商户 / 服务商(ISV) / 直付通。模式只影响前端渲染字段集合，
 * 运行期逻辑由字段值驱动（auth_token 非空即注入 ISV app_auth_token、appmchid 有值即直付通
 * 挂 sub_merchant），插件无需感知 mode。直付通合单（大单拆小单）：appmchid=子商户 SMID
 * （逗号分隔轮询）、alicombine_open 为合单开关（金额参数走全局 payment.combine）。</p>
 *
 * <p>字段语义边界：{@code pid} 是付款账户 PID（余额查询 alipay_user_id），仅付款通道配置
 * （{@code payoutChannelFields}）收集，支付收单不消费——服务商收单靠 {@code auth_token}
 * （app_auth_token）、直付通靠 {@code appmchid}（sub_merchant），故支付 modes 不含 pid。</p>
 *
 * <p>认证维度（{@code authModes}，与 modes 正交）：公钥/私钥 vs 证书。凭据字段（appkey/appsecret/
 * 证书三件）从 modes 与 payoutChannelFields 移入 authModes，前端渲染认证选择器（config["authMode"]），
 * 运行期 {@link com.okpay.plugin.alipay.util.AlipayConfig#certMode()} 决定验签密钥与 SN 上报。
 * 证书模式仍需应用私钥（appsecret）签名，故同时出现在 key/cert 两认证模式。</p>
 */
final class AlipayPluginInfo {

    private AlipayPluginInfo() {}

    static PluginInfo get() {
        return PluginInfo.builder()
                .id("alipay")
                .name("支付宝支付")
                .link("https://open.alipay.com/")
                .payinMethods(List.of("alipay"))
                .payoutMethods(List.of("alipay"))
                .payoutSubmitFields(List.of("cardNo", "cardName"))
                // 直连转账配置：应用标识/环境/付款账户PID/大额转账场景报备；凭据字段（appkey/appsecret/证书）在 authModes
                .payoutChannelFields(List.of("appid", "is_prod", "pid",
                        "transfer_alipay_scene_name", "transfer_alipay_info_type", "transfer_alipay_info_content"))
                .fields(Map.ofEntries(
                    Map.entry("appid", InputField.builder().name("应用APPID").type("input").required(true).build()),
                    Map.entry("appkey", InputField.builder().name("支付宝公钥").type("textarea").required(true).build()),
                    Map.entry("appsecret", InputField.builder().name("应用私钥").type("textarea").required(true).build()),
                    Map.entry("app_cert", InputField.builder().name("应用公钥证书").type("textarea").required(true)
                            .note("证书模式必填；开放平台「应用信息-开发设置」下载的 应用公钥证书 .pem 内容").build()),
                    Map.entry("alipay_cert", InputField.builder().name("支付宝公钥证书").type("textarea").required(true)
                            .note("证书模式必填；支付宝公钥证书 .pem 内容，用于验签支付宝响应/通知").build()),
                    Map.entry("root_cert", InputField.builder().name("支付宝根证书").type("textarea").required(true)
                            .note("证书模式必填；支付宝根证书 .pem 内容，可能含多张根证书").build()),
                    Map.entry("is_prod", InputField.builder().name("环境").type("select")
                            .options(Map.of("true", "正式环境", "false", "沙箱环境")).build()),
                    Map.entry("pid", InputField.builder().name("付款账户PID").type("input").required(true)
                            .note("支付宝账户用户ID（2088开头16位）").build()),
                    Map.entry("auth_token", InputField.builder().name("应用授权令牌").type("input")
                            .note("ISV 第三方应用授权令牌（app_auth_token），仅服务商模式需要").build()),
                    Map.entry("biztype_alipay", InputField.builder().name("支付宝支付方式").type("checkbox")
                            .options(Map.of(
                                "1", "电脑网站(Page)",
                                "2", "手机H5(Wap)",
                                "3", "当面付扫码",
                                "4", "当面付JS",
                                "5", "APP支付",
                                "6", "JSAPI",
                                "7", "订单码"
                            )).note("多选；按买家访问设备自动分配").build()),
                    Map.entry("alipay_paymode", InputField.builder().name("当面付优先").type("switch")
                            .note("开启后电脑网站(Page)支付改用当面付扫码二维码；手机端开电脑网站恒走扫码").build()),
                    Map.entry("appmchid", InputField.builder().name("直付通子商户号").type("input")
                            .note("多个子商户号用逗号分隔").build()),
                    Map.entry("alicombine_open", InputField.builder().name("合单支付").type("switch")
                            .note("开启大单拆小单（直付通）；起拆金额在平台配置维护").build()),
                    Map.entry("cardNo", InputField.builder().name("支付宝账号").type("input").required(true)
                            .note("收款方支付宝登录账号（手机号/邮箱）或 UID").build()),
                    Map.entry("cardName", InputField.builder().name("收款人姓名").type("input")
                            .note("选填，与支付宝实名信息不符时转账可能被拒").build()),
                    Map.entry("transfer_alipay_scene_name", InputField.builder().name("转账场景名称").type("input")
                            .note("大额转账场景报备名称，如\"转账汇款\"；留空则不做场景报备").build()),
                    Map.entry("transfer_alipay_info_type", InputField.builder().name("报备信息类型").type("input")
                            .note("info_type 列表，多个用 | 分隔").build()),
                    Map.entry("transfer_alipay_info_content", InputField.builder().name("报备信息内容").type("input")
                            .note("info_content 列表（与类型一一对应），多个用 | 分隔；缺省取第一个").build())
                ))
                .modes(Map.of(
                    "direct", PluginMode.builder().label("直连商户")
                            .fields(List.of("appid", "is_prod", "biztype_alipay",
                                    "alipay_paymode")).build(),
                    "isv", PluginMode.builder().label("服务商(ISV)")
                            .fields(List.of("appid", "is_prod", "auth_token",
                                    "biztype_alipay", "alipay_paymode")).build(),
                    "zhifutong", PluginMode.builder().label("直付通")
                            .fields(List.of("appid", "is_prod", "appmchid",
                                    "alicombine_open", "biztype_alipay", "alipay_paymode")).build()
                ))
                .defaultMode("direct")
                // 认证维度：公钥/私钥 vs 证书模式（凭据字段在 authModes，与 modes 正交）；证书模式仍需 appsecret 签名
                .authModes(List.of(
                    com.okpay.plugin.model.PluginAuthMode.builder().id("key").label("公钥/私钥")
                            .fields(List.of("appkey", "appsecret")).build(),
                    com.okpay.plugin.model.PluginAuthMode.builder().id("cert").label("证书模式")
                            .fields(List.of("app_cert", "alipay_cert", "root_cert", "appsecret")).build()
                ))
                .defaultAuthMode("key")
                .note("支持电脑网站/手机H5/扫码/APP；服务商(ISV)模式带子商户授权，直付通模式支持合单支付（大单拆小单）。")
                .build();
    }
}
