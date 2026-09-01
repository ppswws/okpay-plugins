package com.okpay.plugin.helipay.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ChannelAppAccount;
import com.okpay.plugin.sdk.ChannelConfig;
import com.okpay.plugin.sdk.ChannelConfigSupport;
import lombok.Data;

import java.util.Set;

/**
 * 合利宝通道配置。
 *
 * <p>biztype 为前端把 biztype_alipay/biztype_wxpay/biztype_bank 合并后的逗号分隔串
 * （通道绑定单一支付方式，合并无歧义）：1 JSAPI（公众号/服务窗）、2 小程序、3 WAP(H5)、4 扫码。</p>
 */
@Data
public class HelipayConfig implements ChannelConfig {

    @JsonProperty("appid") private String appid;
    @JsonProperty("appkey") private String appkey;
    @JsonProperty("appmchid") private String appmchid;
    @JsonProperty("biztype") private String biztype;

    private ChannelAppAccount mp;
    private ChannelAppAccount mini;

    private transient String siteDomain;
    private transient String notifyDomain;
    private transient String goodsName;

    public static HelipayConfig from(InvokeContext ctx) {
        return ChannelConfigSupport.from(ctx, HelipayConfig.class,
                "通道配置不完整（缺少 appid/appkey）",
                cfg -> cfg.appid == null || cfg.appkey == null);
    }

    public Set<String> modeSet() {
        return ChannelConfigSupport.modeSet(biztype, Set.of());
    }
}
