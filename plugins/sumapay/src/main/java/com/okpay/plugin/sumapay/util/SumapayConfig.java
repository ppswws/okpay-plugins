package com.okpay.plugin.sumapay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ChannelAppAccount;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import lombok.Data;

import java.util.Set;

/**
 * 丰付支付通道配置。
 */
@Data
public class SumapayConfig implements ChannelConfig {

    @JsonProperty("appid") private String appid;          // 商户编号（merchantCode）
    @JsonProperty("appuserid") private String appuserid;  // 第三方标识（userIdIdentity）
    @JsonProperty("appmchid") private String appmchid;    // 子商户编码（subMerchantId）
    @JsonProperty("appkey") private String appkey;        // 丰付公钥（响应验签）
    @JsonProperty("appsecret") private String appsecret;  // 商户私钥（请求签名）
    @JsonProperty("biztype") private String biztype;

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;
    /** 绑定公众号（IOZ2011 公众号支付 subAppId 来源） */
    private ChannelAppAccount mp;

    public static SumapayConfig from(InvokeContext ctx) {
        return ChannelConfigSupport.from(ctx, SumapayConfig.class,
                "通道配置不完整（缺少 appid/appuserid/appmchid/appkey/appsecret）",
                cfg -> cfg.appid == null || cfg.appuserid == null || cfg.appmchid == null
                        || cfg.appkey == null || cfg.appsecret == null);
    }

    public Set<String> modeSet() {
        return ChannelConfigSupport.modeSet(biztype, Set.of("1"));
    }
}
