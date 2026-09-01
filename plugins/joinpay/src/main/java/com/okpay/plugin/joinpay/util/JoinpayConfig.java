package com.okpay.plugin.joinpay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ChannelAppAccount;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import lombok.Data;

import java.util.Set;

/**
 * 汇聚支付通道配置。
 */
@Data
public class JoinpayConfig implements ChannelConfig {

    @JsonProperty("appid") private String appid;
    @JsonProperty("appkey") private String appkey;
    @JsonProperty("appmchid") private String appmchid;
    @JsonProperty("biztype") private String biztype;

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;
    private ChannelAppAccount mp;
    private ChannelAppAccount mini;

    public static JoinpayConfig from(InvokeContext ctx) {
        return ChannelConfigSupport.from(ctx, JoinpayConfig.class,
                "通道配置不完整（缺少 appid/appkey）",
                cfg -> cfg.appid == null || cfg.appkey == null);
    }

    public Set<String> modeSet() {
        return ChannelConfigSupport.modeSet(biztype, Set.of());
    }
}
