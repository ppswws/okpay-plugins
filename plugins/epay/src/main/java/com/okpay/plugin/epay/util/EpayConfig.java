package com.okpay.plugin.epay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import lombok.Data;

/**
 * 易支付通道配置。
 */
@Data
public class EpayConfig implements ChannelConfig {

    @JsonProperty("appurl") private String appurl;
    @JsonProperty("appid")  private String appid;
    @JsonProperty("appkey") private String appkey;
    /** Submit 模式：不请求渠道接口，拼接支付链接（带签名参数）直接跳转 */
    @JsonProperty("submit") private boolean submitMode;

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;

    /** 从 InvokeContext 解析通道配置 */
    public static EpayConfig from(InvokeContext ctx) {
        var cfg = ChannelConfigSupport.from(ctx, EpayConfig.class,
                "通道配置不完整（缺少 appurl/appid/appkey）",
                c -> c.appurl == null || c.appid == null || c.appkey == null);
        // 接口地址常带尾斜杠输入（https://x.com/），统一去除，避免拼出 //mapi.php、//submit.php
        cfg.appurl = cfg.appurl.replaceAll("/+$", "");
        return cfg;
    }
}
