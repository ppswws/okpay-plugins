package com.okpay.plugin.wxpay.util;

import com.okpay.plugin.sdk.HttpHelper;

import java.util.Set;

/**
 * 微信支付方式 UA 自动分配（epay submit() 优先级表）。
 *
 * <pre>
 * (a) 微信内 && 开2 && 已绑公众号  → MP    （公众号 JSAPI；非微信 UA 无 code 时展示 jspay 链接码，即 epay wap 页）
 * (b) 微信内 && 开2 && 已绑小程序  → MINI  （小程序 scheme）
 * (c) 微信内 && 开1                → QRCODE（企业微信扫码兜底）
 * (d) 手机 && 开3                  → H5
 * (e) 手机 iPhone && 开4           → APP
 * (f) 手机 && 开2 && 已绑小程序    → MINI
 * (g) 手机 && 开2 && 已绑公众号    → MP
 * (h) 兜底（PC/其他）：开1 → QRCODE；开2 → 已绑 mp/mini；开3 → H5；其余 null
 *     ——epay qrcode() 同款：APP(4) 不参与 PC 兜底，仅含 4 时 PC 报错
 * </pre>
 */
public final class WxpayModeResolver {

    /** 支付方式枚举（与 biztype_wxpay 选项 1/2/3/4 的分发目标） */
    public enum Mode { QRCODE, H5, APP, MP, MINI }

    private WxpayModeResolver() {}

    /**
     * 按访问 UA、已开启方式与公众号/小程序绑定情况解析实际支付模式。
     *
     * @param ua           浏览器 User-Agent（可空）
     * @param modes        已开启方式编号集合（"1"/"2"/"3"/"4"）
     * @param mpConfigured 通道已绑定公众号（mpAppId 非空）
     * @param miniConfigured 通道已绑定小程序（miniAppId 非空）
     * @return 支付模式；所有方式都未命中返回 null
     */
    public static Mode resolve(String ua, Set<String> modes,
                               boolean mpConfigured, boolean miniConfigured) {
        var isMobile = HttpHelper.isMobile(ua);
        var isWeChat = HttpHelper.isWeChat(ua);
        boolean has2 = modes.contains("2");

        // (a) 微信内 + JSAPI + 公众号 → 公众号 JSAPI（双 OAuth 链路）
        if (isWeChat && has2 && mpConfigured) return Mode.MP;
        // (b) 微信内 + JSAPI + 小程序（未绑公众号）→ 小程序 scheme
        if (isWeChat && has2 && miniConfigured) return Mode.MINI;
        // (c) 微信内 + Native → 扫码（企业微信场景兜底）
        if (isWeChat && modes.contains("1")) return Mode.QRCODE;
        // (d) 手机 + H5 → H5
        if (isMobile && modes.contains("3")) return Mode.H5;
        // (e) 手机 iPhone + APP → APP（epay 仅 iPhone 网页触发 APP 支付）
        if (isMobile && modes.contains("4") && ua != null && ua.contains("iPhone"))
            return Mode.APP;
        // (f) 手机 + JSAPI + 小程序 → 小程序
        if (isMobile && has2 && miniConfigured) return Mode.MINI;
        // (g) 手机 + JSAPI + 公众号 → 公众号（无 code 时展示 jspay 链接码）
        if (isMobile && has2 && mpConfigured) return Mode.MP;
        // (h) 兜底（PC/其他）
        if (modes.contains("1")) return Mode.QRCODE;
        if (has2) return mpConfigured ? Mode.MP : (miniConfigured ? Mode.MINI : null);
        if (modes.contains("3")) return Mode.H5;
        return null;
    }
}
