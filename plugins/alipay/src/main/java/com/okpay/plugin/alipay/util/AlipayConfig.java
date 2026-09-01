package com.okpay.plugin.alipay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import lombok.Data;

import java.util.*;

/**
 * 支付宝通道配置。
 */
@Data
public class AlipayConfig implements ChannelConfig {

    @JsonProperty("appid") private String appId;
    @JsonProperty("appkey") private String alipayPublicKey;
    @JsonProperty("appsecret") private String appPrivateKey;
    @JsonProperty("pid") private String pid;                      // 付款账户 PID（余额查询 alipay_user_id），仅付款通道配置收集；支付收单不消费（服务商靠 auth_token、直付通靠 appmchid）
    @JsonProperty("auth_token") private String appAuthToken;      // 第三方应用授权令牌 app_auth_token（服务商/直付通代商户调用；直付通留空）
    // 合单支付（大单拆小单，直付通 merge 协议）：开关（金额参数走全局 payment.combine，宿主注入 ConfigSnapshot）
    @JsonProperty("alicombine_open") private boolean combineOpen;
    /** 当面付优先：开电脑网站(Page)时改用当面付扫码；手机端开电脑网站恒走扫码 */
    @JsonProperty("alipay_paymode") private boolean paymode;
    /** 直付通子商户 SMID，逗号分隔（多 SMID 按子单顺序轮询）；空 = 非直付通直连，合单不可用（门控见 AlipayCreateHandler.combinePlan） */
    @JsonProperty("appmchid") private String appMchid;
    // 前端 ChannelList 保存时把 biztype_* 合并进 biztype 键（前后端一致，无老数据兼容）
    @JsonProperty("biztype")
    private String biztype;
    @JsonProperty("is_prod") private String isProd;
    // 认证模式（凭据维度）：key=公钥/私钥，cert=证书模式；前端认证选择器写入，运行期据此选择验签密钥与 SN 上报
    @JsonProperty("authMode") private String authMode;
    // 证书模式（app_cert=应用公钥证书/alipay_cert=支付宝公钥证书/root_cert=支付宝根证书，均为 PEM）
    @JsonProperty("app_cert") private String appCert;
    @JsonProperty("alipay_cert") private String alipayCert;
    @JsonProperty("root_cert") private String rootCert;
    // 大额转账场景报备（transfer_scene_name + transfer_scene_report_infos）；info_type/info_content 按 | 拆分，content 缺省回退第一个
    @JsonProperty("transfer_alipay_scene_name") private String transferAlipaySceneName;
    @JsonProperty("transfer_alipay_info_type") private String transferAlipayInfoType;
    @JsonProperty("transfer_alipay_info_content") private String transferAlipayInfoContent;

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;
    /** 每次调用惰性构建，复用同一配置实例内的客户端（密钥只解析一次） */
    private transient AlipayOpenApiClient client;
    /** 付款专用客户端（转账/转账查询/余额查询共用） */
    private transient AlipayOpenApiClient payoutClient;

    public static AlipayConfig from(InvokeContext ctx) {
        return ChannelConfigSupport.from(ctx, AlipayConfig.class,
                "通道配置不完整（缺少 appid/appsecret）",
                cfg -> cfg.appId == null || cfg.appPrivateKey == null);
    }

    public Set<String> modeSet() {
        return ChannelConfigSupport.modeSet(biztype, Set.of("1", "3"));
    }

    public boolean combineOpen() { return combineOpen; }

    public boolean paymode() { return paymode; }

    /** 直付通 SMID 列表（逗号分隔去空）；非直付通/未配置 → 空列表 */
    public List<String> smidList() {
        if (appMchid == null || appMchid.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (var s : appMchid.split(",")) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    public String getGateway() {
        // 默认正式环境（与投诉插件 is_prod 默认值一致）；显式 "false" 才走沙箱
        return "false".equals(isProd)
                ? "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
                : "https://openapi.alipay.com/gateway.do";
    }

    /**
     * 证书模式判定：显式 authMode=cert，或未显式指定但三证书齐全（老配置/手工配置兼容）；
     * 显式 authMode=key 时即使残留证书字段也走公钥模式（显式优先）。
     */
    public boolean certMode() {
        if ("cert".equals(authMode)) return true;
        if (authMode != null && !authMode.isBlank()) return false;
        return notBlank(appCert) && notBlank(alipayCert) && notBlank(rootCert);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 构建 OpenAPI 客户端（零第三方 SDK，RSA2 签名 + HttpHelper 承载 HTTP）。
     * 客户端无服务商标识注入：收单服务商关系由 auth_token（app_auth_token）/
     * appmchid（sub_merchant）承载，pid 是付款账户语义仅余额查询用，不参与支付。
     * 凭据维度由 certMode() 决定：证书模式传三证书，公钥模式传支付宝公钥。
     */
    public AlipayOpenApiClient client() {
        if (client == null) {
            try {
                client = buildClient();
            } catch (Exception e) {
                throw new IllegalStateException("支付宝客户端初始化失败（请检查 "
                        + (certMode() ? "appsecret/app_cert/alipay_cert/root_cert 证书格式" : "appsecret/appkey 密钥格式")
                        + "）", e);
            }
        }
        return client;
    }

    /**
     * 付款专用客户端（转账/转账查询/余额查询）。付款为直连转账，pid 语义是
     * 付款账户 PID（余额查询 alipay_user_id），无服务商标识概念。
     */
    public AlipayOpenApiClient clientForPayout() {
        if (payoutClient == null) {
            try {
                payoutClient = buildClient();
            } catch (Exception e) {
                throw new IllegalStateException("支付宝客户端初始化失败（请检查 "
                        + (certMode() ? "appsecret/app_cert/alipay_cert/root_cert 证书格式" : "appsecret/appkey 密钥格式")
                        + "）", e);
            }
        }
        return payoutClient;
    }

    private AlipayOpenApiClient buildClient() throws Exception {
        return new AlipayOpenApiClient(getGateway(), appId, appPrivateKey,
                alipayPublicKey, appCert, alipayCert, rootCert);
    }

    /** 组装请求附加参数：notify/return 等 + ISV app_auth_token 注入。 */
    public Map<String, String> extras(Map<String, String> base) {
        var map = new LinkedHashMap<String, String>();
        if (base != null) map.putAll(base);
        if (appAuthToken != null && !appAuthToken.isBlank()) map.put("app_auth_token", appAuthToken);
        return map;
    }
}
