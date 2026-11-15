package com.okpay.plugin.alipay.util;

import com.okpay.plugin.sdk.HttpHelper;

import java.util.Set;

/**
 * 支付宝支付方式 UA 自动分配优先级表。
 *
 * <p><b>产品与展示分离</b>：QRCODE 只代表「当面付扫码/订单码」这个渠道产品（3/7），
 * 未启用时绝不能为了给非支付宝环境出码而调用 precreate；只开当面付JS（4）时，
 * 非支付宝环境走 {@link Mode#JSPAY_RELAY}——本地展示中转码，扫码进入支付宝后再走 JSPAY。</p>
 *
 * <pre>
 * (a) 支付宝客户端 && 开 4 未开 2      → JSPAY（当面付JS，AlipayJSBridge）
 * (b) 手机未开 2 / PC 未开 1：
 *     开 3/7                          → QRCODE（当面付扫码/订单码）
 *     未开 3/7 但开 4                  → JSPAY_RELAY（本地中转码 → 支付宝内 JSPAY）
 * (c) 手机 && 开 2                     → WAP（手机网站）
 * (d) 开 1 && 当面付优先 && 开 3/7      → QRCODE（当面付扫码替代电脑表单）
 *     开 1 && 手机且无扫码/当面付JS/Wap → null（Page 仅桌面）
 * (e) 开 1                             → PAGE（电脑网站）
 * (f) 开 5                             → APP
 * (g) 开 6                             → JSAPI
 * </pre>
 */
public final class AlipayModeResolver {

    /** 支付方式枚举（与 biztype_alipay 选项 1-7 一一对应；JSPAY_RELAY 是 JSPAY 的本地展示形态，不是独立渠道产品） */
    public enum Mode { PAGE, WAP, QRCODE, JSPAY, JSPAY_RELAY, APP, JSAPI }

    private AlipayModeResolver() {}

    /**
     * 按访问 UA 与已开启方式解析实际支付模式；所有方式都未命中返回 null。
     *
     * @param ua      浏览器 User-Agent（可空）
     * @param modes   已开启方式编号集合（"1".."7"）
     * @param paymode 当面付优先（alipay_paymode）：开电脑网站且有扫码产品时改走当面付扫码
     */
    public static Mode resolve(String ua, Set<String> modes, boolean paymode) {
        var isAlipayClient = HttpHelper.isAlipay(ua);
        var isMobile = HttpHelper.isMobile(ua);
        var isWeChat = HttpHelper.isWeChat(ua);
        boolean hasPage = modes.contains("1");
        boolean hasWap = modes.contains("2");
        boolean hasScan = modes.contains("3") || modes.contains("7");
        boolean hasJsPay = modes.contains("4");

        // (a) 支付宝 App 内置浏览器（AlipayClient）：当面付JS 调起；开手机网站支付时让位 Wap
        if (isAlipayClient && hasJsPay && !hasWap) return Mode.JSPAY;

        // (b) 非支付宝环境：开扫码产品 → 直接出渠道码；只开当面付JS → 出本地中转码，
        //     扫码进支付宝后再走 JSPAY。QRCODE 绝不用于未启用的扫码产品。
        if ((isMobile && !hasWap) || (!isMobile && !hasPage)) {
            if (hasScan) return Mode.QRCODE;
            if (hasJsPay) return Mode.JSPAY_RELAY;
        }

        // 微信内：开了扫码产品优先出渠道码（Wap 在微信内通常不可用）
        if (isWeChat && hasScan) return Mode.QRCODE;

        // (c) 手机网站支付（手机端优先于电脑网站）
        if (isMobile && hasWap) return Mode.WAP;

        // (d) 电脑网站支付：当面付优先只在有扫码产品时生效；手机端 Page 不可用
        if (hasPage && paymode && hasScan) return Mode.QRCODE;
        if (hasPage && isMobile) return null;

        // (e-g) 按配置兜底
        if (hasPage) return Mode.PAGE;
        if (modes.contains("5")) return Mode.APP;
        if (modes.contains("6")) return Mode.JSAPI;
        return null;
    }
}
