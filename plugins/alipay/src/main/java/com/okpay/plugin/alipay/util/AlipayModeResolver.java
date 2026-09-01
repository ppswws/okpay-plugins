package com.okpay.plugin.alipay.util;

import com.okpay.plugin.sdk.HttpHelper;

import java.util.Set;

/**
 * 支付宝支付方式 UA 自动分配优先级表。
 *
 * <pre>
 * (a) 支付宝客户端 && 开 4 未开 2      → JSPAY（当面付JS，AlipayJSBridge）
 * (b) 手机 && 开 3/4/7 未开 2，
 *     或 PC 未开 1 但开了扫码类(3/4/7) → QRCODE（当面付扫码/订单码）
 * (c) 微信内                           → QRCODE
 * (d) 手机 && 开 2                     → WAP（手机网站）
 * (e) 开 1 && (当面付优先 || 手机端)    → QRCODE（当面付扫码替代电脑表单）
 * (f) 开 1                             → PAGE（电脑网站）
 * (g) 开 5                             → APP
 * (h) 开 6                             → JSAPI
 * </pre>
 */
public final class AlipayModeResolver {

    /** 支付方式枚举（与 biztype_alipay 选项 1-7 一一对应） */
    public enum Mode { PAGE, WAP, QRCODE, JSPAY, APP, JSAPI }

    private AlipayModeResolver() {}

    /**
     * 按访问 UA 与已开启方式解析实际支付模式；所有方式都未命中返回 null。
     *
     * @param ua      浏览器 User-Agent（可空）
     * @param modes   已开启方式编号集合（"1".."7"）
     * @param paymode 当面付优先（alipay_paymode）：开电脑网站时改走当面付扫码
     */
    public static Mode resolve(String ua, Set<String> modes, boolean paymode) {
        var isAlipayClient = HttpHelper.isAlipay(ua);
        var isMobile = HttpHelper.isMobile(ua);
        var isWeChat = HttpHelper.isWeChat(ua);
        boolean has2 = modes.contains("2");

        // (a) 支付宝 App 内置浏览器（AlipayClient）：当面付JS 调起
        if (isAlipayClient && modes.contains("4") && !has2) return Mode.JSPAY;
        // (b) 手机且开扫码类（3/4/7）未开 Wap → 扫码；PC 未开电脑网站但开了扫码类 → 扫码
        //（PC 兜底，但须已开扫码类之一，否则落配置兜底/报错）
        if ((isMobile && (modes.contains("3") || modes.contains("4") || modes.contains("7")) && !has2)
                || (!isMobile && !modes.contains("1")
                    && (modes.contains("3") || modes.contains("4") || modes.contains("7"))))
            return Mode.QRCODE;
        // (c) 微信内 → 扫码（唤起支付受限场景兜底）
        if (isWeChat) return Mode.QRCODE;
        // (d) 手机且开 Wap → 手机网站
        if (isMobile && has2) return Mode.WAP;
        // (e) 当面付优先（alipay_paymode）或手机端开电脑网站 → 扫码（手机扫码比电脑表单顺手）
        if (modes.contains("1") && (paymode || isMobile)) return Mode.QRCODE;
        // (f-h) 按配置兜底
        if (modes.contains("1")) return Mode.PAGE;
        if (modes.contains("5")) return Mode.APP;
        if (modes.contains("6")) return Mode.JSAPI;
        return null;
    }
}
