package com.okpay.plugin.wxpay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ChannelAppAccount;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import com.okpay.plugin.sdk.WechatPayV3Client;
import lombok.Data;

import java.io.IOException;
import java.util.*;

/**
 * 微信支付通道配置（APIv3-only，零第三方 SDK）。
 */
@Data
public class WxpayConfig implements ChannelConfig {

    // 字段语义：appid=AppID、appmchid=商户号；子商户号独立为 sub_mchid
    @JsonProperty("appid") private String wxAppId;       // 服务号/小程序/开放平台 AppID
    @JsonProperty("appmchid") private String mchId;      // 商户号
    @JsonProperty("appkey") private String certSerialNo;
    @JsonProperty("appsecret") private String apiV3Key;
    @JsonProperty("privateKey") private String privateKey;
    @JsonProperty("sub_mchid") private String subMchId;  // 子商户号（服务商/收付通）
    @JsonProperty("publickeyid") private String publicKeyId;       // 微信支付公钥ID（公钥模式验签）
    @JsonProperty("wxpay_public_key") private String publicKeyPem; // 微信支付公钥 PEM（公钥模式验签，标准 SPKI：-----BEGIN PUBLIC KEY-----）
    @JsonProperty("authMode") private String authMode;             // 认证凭据维度：public_key / platform_cert
    // 合单支付（大单拆小单）：开关（金额参数走全局 payment.combine，宿主注入 ConfigSnapshot）
    @JsonProperty("wxcombine_open") private boolean combineOpen;
    // 前端 ChannelList 保存时把 biztype_* 合并进 biztype 键（前后端一致，无老数据兼容）
    @JsonProperty("biztype") private String biztype;

    /** 合单开关已开启 */
    public boolean combineOpen() { return combineOpen; }

    private ChannelAppAccount mp;
    private ChannelAppAccount mini;

    /** 公众号 AppId（仅用于 handleWxMp） */
    public String mpAppId() { return mp != null && mp.getAppid() != null && !mp.getAppid().isBlank() ? mp.getAppid() : null; }
    /** 公众号 AppSecret（仅用于 handleWxMp OAuth） */
    public String mpAppSecret() { return mp != null && mp.getAppsecret() != null && !mp.getAppsecret().isBlank() ? mp.getAppsecret() : null; }
    /** 小程序 AppId（仅用于 handleWxMini） */
    public String miniAppId() { return mini != null && mini.getAppid() != null && !mini.getAppid().isBlank() ? mini.getAppid() : null; }
    /** 小程序 AppSecret（仅用于 handleWxMini OAuth） */
    public String miniAppSecret() { return mini != null && mini.getAppsecret() != null && !mini.getAppsecret().isBlank() ? mini.getAppsecret() : null; }

    /**
     * 通用 AppId：优先配置 appid（服务号/小程序/开放平台）→ 绑定公众号 → 绑定小程序。
     * 不回退商户号——商户号当 appid 上送微信必然拒绝（曾以 mchId 兜底的坏点）。
     */
    public String genericAppId() {
        if (wxAppId != null && !wxAppId.isBlank()) return wxAppId;
        if (mp != null && mp.getAppid() != null && !mp.getAppid().isBlank()) return mp.getAppid();
        if (mini != null && mini.getAppid() != null && !mini.getAppid().isBlank()) return mini.getAppid();
        return null;
    }

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;
    /** 每次调用惰性构建，复用同一配置实例内的客户端（密钥只解析一次） */
    private transient WechatPayV3Client client;

    public static WxpayConfig from(InvokeContext ctx) {
        return ChannelConfigSupport.from(ctx, WxpayConfig.class,
                "通道配置不完整（缺少 appmchid/appid/appsecret）",
                cfg -> cfg.mchId == null || cfg.wxAppId == null || cfg.apiV3Key == null);
    }

    public Set<String> modeSet() {
        return ChannelConfigSupport.modeSet(biztype, Set.of("1"));
    }

    public boolean isServiceProvider() {
        return subMchId != null && !subMchId.isBlank();
    }

    /** 构建 WechatPayV3Client（零第三方 SDK：RSA 签名 + 平台证书/微信支付公钥验签 + AES-GCM）。 */
    public WechatPayV3Client client() {
        if (client == null) {
            try {
                // 认证凭据维度（authMode）：public_key 传微信支付公钥ID+公钥；platform_cert/缺省走平台证书自动下载
                var pubKeyMode = "public_key".equals(authMode);
                client = new WechatPayV3Client(mchId, privateKey, certSerialNo, apiV3Key,
                        pubKeyMode ? publicKeyId : null,
                        pubKeyMode ? publicKeyPem : null);
            } catch (IOException e) {
                throw new IllegalStateException("微信客户端初始化失败（请检查 privateKey/appkey/微信支付公钥 格式）", e);
            }
        }
        return client;
    }
}
