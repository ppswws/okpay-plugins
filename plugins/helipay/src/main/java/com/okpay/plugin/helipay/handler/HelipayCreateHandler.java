package com.okpay.plugin.helipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.helipay.util.*;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 合利宝下单处理器。
 *
 * <p>分流优先级（支付宝：H5 手机端 → 扫码 → 小程序 → JSAPI；微信：H5 → 扫码 → JSAPI →
 * 小程序；银联：云闪付）。方式 1 是 JSAPI 预下单（AppPayPublic，微信公众号/支付宝服务窗共用）。
 * 微信 H5 手机端支付链接逆向调微信 qbase 取 openlink（浏览器直拉起），
 * 失败回退原始链接；微信 H5 非手机端走中转页（部分安卓扫码二维码白屏规避）。</p>
 */
public class HelipayCreateHandler {

    private static final Logger log = LoggerFactory.getLogger(HelipayCreateHandler.class);

    /** 微信 qbase 环境 ID（微信侧固定值，若变动再配置化） */
    private static final String QBASE_ENV = "cloud1-5g6j2un4a478958c";
    private static final String QBASE_URL = "https://servicewechat.com/wxa-qbase/jsoperatewxdata";
    private static final String H5_PAY_HOST = "h5pay.helipay.com";

    /** 按支付方式分发并提取支付入口 URL（复用 createWithHandlers）。 */
    public com.okpay.plugin.model.BizResult submit(InvokeContext ctx) {
        return Sdk.createResult(ctx, Map.of(
            "alipay", this::alipay, "wxpay", this::wxpay, "bank", this::bank
        ));
    }

    // =========================================================================
    // 支付方式分流
    // =========================================================================

    public PageResponse alipay(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;

        if (modes.contains("3") && HttpHelper.isMobile(ua))
            return Sdk.lockCreate(ctx, () -> wapOrder(ctx, cfg, "alipay"));
        if (modes.contains("4") || modes.contains("3"))
            return Sdk.lockCreate(ctx, () -> scanOrder(ctx, cfg, "alipay"));
        if (modes.contains("2"))
            return Sdk.lockCreate(ctx, () -> appletOrder(ctx, cfg, "alipay"));
        if (modes.contains("1"))
            return Sdk.lockCreate(ctx, () -> publicOrder(ctx, cfg, "alipay"));
        return Responses.respError("当前通道未开启支付宝支付方式");
    }

    public PageResponse wxpay(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;

        if (modes.contains("3")) {
            if (HttpHelper.isMobile(ua)) {
                return Sdk.lockCreate(ctx, () -> {
                    var resp = createOrder(ctx, wapParams(ctx, cfg, "wxpay"), cfg.getAppkey());
                    var payInfo = reverseToOpenlink(ctx, payInfoOf(resp));
                    // 逆向失败时 payInfo 仍是 h5pay 域名完整支付页，可直接跳转；
                    // 逆向成功则是 openlink，走 wxpay_h5 页拉起支付
                    var page = payInfo.contains(H5_PAY_HOST)
                            ? Responses.respJump(payInfo)
                            : Responses.respPageURL("wxpay_h5", payInfo);
                    return Sdk.FetchResult.of(page, resp);
                });
            }
            // 非手机端直接返回手机支付页地址时，部分安卓扫码生成的二维码会白屏，走中转页规避
            return Responses.respPageURL("wxpay_qrcode",
                    buildPayUrl(ctx, cfg, Map.of("t", String.valueOf(System.currentTimeMillis() / 1000))));
        }
        if (modes.contains("4"))
            return Sdk.lockCreate(ctx, () -> scanOrder(ctx, cfg, "wxpay"));
        if (modes.contains("1"))
            return handleWxMp(ctx, cfg, ua);
        if (modes.contains("2"))
            return handleWxMini(ctx, cfg);
        return Responses.respError("当前通道未开启微信支付方式");
    }

    public PageResponse bank(InvokeContext ctx) {
        var cfg = HelipayConfig.from(ctx);
        var modes = cfg.modeSet();
        if (modes.contains("1"))
            return Sdk.lockCreate(ctx, () -> publicOrder(ctx, cfg, "bank"));
        return Responses.respError("当前通道未开启云闪付支付方式");
    }

    // =========================================================================
    // 微信公众号 / 小程序
    // =========================================================================

    private PageResponse handleWxMp(InvokeContext ctx, HelipayConfig cfg, String ua) {
        if (cfg.getMp() == null || cfg.getMp().getAppid() == null)
            return Responses.respError("缺少公众号配置");
        var code = queryParam(ctx, "code");
        var retUrl = buildPayUrl(ctx, cfg, Map.of("t", String.valueOf(System.currentTimeMillis() / 1000)));
        if (code.isBlank()) {
            if (HttpHelper.isWeChat(ua)) {
                // 授权 URL 由 SDK 内置决策：统一公众号已配置 → 先经宿主 /oauth/wx 用系统公众号授权
                //（风控身份落库 buyer + 黑名单），随后微信重定向到本通道公众号授权；未配置 → 直接
                // 通道授权。两条路径最终兑换的 openid 恒为本通道公众号 openid（支付 appid 同源），
                // buyer 由宿主/SDK 内部管理，插件不读不判。参数已由上下文保证非空，无需防御分支
                return Responses.respJump(OAuthHelper.buildMpPayAuthUrl(ctx, cfg.getMp().getAppid(), retUrl,
                        ctx.getOrder().getTradeNo()));
            }
            return Responses.respPageURL("wxpay_qrcode", retUrl);
        }
        // OAuth 兑换在锁外执行：lockOrderExt 只缓存真正下单结果，openid 不做缓存。
        // 兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应全部内置 SDK（exchangeMpOpenid），
        // 插件只提供「拿到 openid 后如何下单」的回调，无任何风控分支
        return OAuthHelper.exchangeMpOpenid(ctx, cfg.getMp().getAppid(), cfg.getMp().getAppsecret(), code,
                openid -> Sdk.lockCreate(ctx, () -> {
                    try {
                        var resp = createOrder(ctx,
                                publicParams(ctx, cfg, "wxpay", cfg.getMp().getAppid(), "1", openid),
                                cfg.getAppkey());
                        return jspayResult(resp, "公众号支付失败");
                    } catch (Exception e) { throw new RuntimeException("公众号支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    private PageResponse handleWxMini(InvokeContext ctx, HelipayConfig cfg) {
        if (cfg.getMini() == null || cfg.getMini().getAppid() == null)
            return Responses.respError("缺少小程序配置");
        var code = queryParam(ctx, "auth_code");
        var retUrl = buildPayUrl(ctx, cfg, Map.of());
        if (code.isBlank()) {
            try {
                var scheme = OAuthHelper.getMiniScheme(ctx, cfg.getMini().getAppid(),
                        cfg.getMini().getAppsecret(), "page/pay",
                        "real=" + toYuan(ctx.getOrder().getReal()) + "&url="
                                + URLEncoder.encode(retUrl, StandardCharsets.UTF_8));
                return Responses.respPageURL("wxpay_h5", scheme);
            } catch (Exception e) { log.error("获取小程序 scheme 失败", e); return Responses.respError("小程序跳转失败"); }
        }
        // OAuth 兑换在锁外执行：lockOrderExt 只缓存真正下单结果，openid 不做缓存。
        // 兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应全部内置 SDK（exchangeMiniOpenid），
        // 插件只提供「拿到 openid 后如何下单」的回调，无任何风控分支
        return OAuthHelper.exchangeMiniOpenid(ctx, cfg.getMini().getAppid(), cfg.getMini().getAppsecret(), code,
                openid -> Sdk.lockCreate(ctx, () -> {
                    try {
                        var resp = createOrder(ctx,
                                appletParams(ctx, cfg, "wxpay", cfg.getMini().getAppid(), "1", openid),
                                cfg.getAppkey());
                        return jspayResult(resp, "小程序支付失败");
                    } catch (Exception e) { throw new RuntimeException("小程序支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    /** JSAPI 调起参数：渠道返回的 payInfo 是 JSON 字符串，原样作为 wxpay_jspay 页载荷 */
    private static Sdk.FetchResult jspayResult(HttpHelper.HttpResponse resp, String failPrefix) throws Exception {
        var payInfo = payInfoOf(resp);
        if (payInfo.isBlank()) throw new Sdk.BizFailException("下单成功但未返回支付参数", resp);
        var jsParams = HttpHelper.MAPPER.readValue(payInfo, Object.class);
        return Sdk.FetchResult.of(Responses.respPageData("wxpay_jspay",
                Map.of("js_api_parameters", jsParams)), resp);
    }

    // =========================================================================
    // 四种预下单
    // =========================================================================

    /** JSAPI 下单（AppPayPublic 公众号/服务窗/JS，支付宝/微信/银联共用；非微信场景 appid/isRaw/openid 传占位值） */
    private Sdk.FetchResult publicOrder(InvokeContext ctx, HelipayConfig cfg, String payType) throws Exception {
        var resp = createOrder(ctx, publicParams(ctx, cfg, payType, "1", "0", "1"), cfg.getAppkey());
        var page = "bank".equals(payType) ? "bank_qrcode" : "alipay_qrcode";
        return Sdk.FetchResult.of(Responses.respPageURL(page, payInfoOf(resp)), resp);
    }

    /** 小程序下单（微信专属；支付宝侧小程序也走此预下单但产物是二维码链接） */
    private Sdk.FetchResult appletOrder(InvokeContext ctx, HelipayConfig cfg, String payType) throws Exception {
        var resp = createOrder(ctx, appletParams(ctx, cfg, payType, "1", "0", "1"), cfg.getAppkey());
        return Sdk.FetchResult.of(Responses.respPageURL("alipay_qrcode", payInfoOf(resp)), resp);
    }

    /** 扫码下单（支付宝/微信共用，二维码页按支付方式区分） */
    private Sdk.FetchResult scanOrder(InvokeContext ctx, HelipayConfig cfg, String payType) throws Exception {
        var resp = createOrder(ctx, scanParams(ctx, cfg, payType), cfg.getAppkey());
        var page = "wxpay".equals(payType) ? "wxpay_qrcode" : "alipay_qrcode";
        return Sdk.FetchResult.of(Responses.respPageURL(page, payInfoOf(resp)), resp);
    }

    /** H5 下单（支付宝：直接跳转支付链接；微信走调用方内联分支） */
    private Sdk.FetchResult wapOrder(InvokeContext ctx, HelipayConfig cfg, String payType) throws Exception {
        var resp = createOrder(ctx, wapParams(ctx, cfg, payType), cfg.getAppkey());
        return Sdk.FetchResult.of(Responses.respJump(payInfoOf(resp)), resp);
    }

    // =========================================================================
    // 请求参数构建（sign 由 createOrder 统一追加）
    // =========================================================================

    /** AppPayPublic/AppPayApplet 预下单共用参数（差异仅 P1_bizType/P4_payType）。 */
    private Map<String, String> appParams(InvokeContext ctx, HelipayConfig cfg,
                                          String payType, String appid, String isRaw, String openid,
                                          String p1BizType, String p4PayType) {
        var order = ctx.getOrder();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", p1BizType);
        params.put("P2_orderId", order.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_payType", p4PayType);
        params.put("P5_appid", appid);
        params.put("P6_deviceInfo", "");
        params.put("P7_isRaw", isRaw);
        params.put("P8_openid", openid);
        params.put("P9_orderAmount", toYuan(order.getReal()));
        params.put("P10_currency", "CNY");
        params.put("P11_appType", mapAppPayType(payType));
        params.put("P12_notifyUrl", notifyUrl(ctx, cfg));
        params.put("P13_successToUrl", buildPayUrl(ctx, cfg, Map.of()));
        params.put("P14_orderIp", order.getIpBuyer());
        params.put("P15_goodsName", cfg.getGoodsName());
        params.put("P16_goodsDetail", "");
        params.put("P17_limitCreditPay", "");
        params.put("P18_desc", "");
        if (notBlank(cfg.getAppmchid())) params.put("P20_subMerchantId", cfg.getAppmchid());
        return params;
    }

    /** 公众号/服务窗/JS 预下单参数（P1_bizType=AppPayPublic、P4_payType=PUBLIC） */
    private Map<String, String> publicParams(InvokeContext ctx, HelipayConfig cfg,
                                             String payType, String appid, String isRaw, String openid) {
        return appParams(ctx, cfg, payType, appid, isRaw, openid, "AppPayPublic", "PUBLIC");
    }

    /** 小程序预下单参数（P1_bizType=AppPayApplet、P4_payType=APPLET） */
    private Map<String, String> appletParams(InvokeContext ctx, HelipayConfig cfg,
                                             String payType, String appid, String isRaw, String openid) {
        return appParams(ctx, cfg, payType, appid, isRaw, openid, "AppPayApplet", "APPLET");
    }

    private Map<String, String> wapParams(InvokeContext ctx, HelipayConfig cfg, String payType) {
        var order = ctx.getOrder();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayH5WFT");
        params.put("P2_orderId", order.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_orderAmount", toYuan(order.getReal()));
        params.put("P5_currency", "CNY");
        params.put("P6_orderIp", order.getIpBuyer());
        params.put("P7_notifyUrl", notifyUrl(ctx, cfg));
        params.put("P8_appPayType", mapAppPayType(payType));
        params.put("P9_payType", "WAP");
        params.put("P10_appName", "短剧剧场");
        params.put("P11_deviceInfo", "iOS_WAP");
        params.put("P12_applicationId", trimSlash(cfg.getSiteDomain()));
        params.put("P13_goodsName", cfg.getGoodsName());
        params.put("P14_goodsDetail", "");
        params.put("P15_desc", "");
        params.put("successToUrl", buildPayUrl(ctx, cfg, Map.of()));
        if (notBlank(cfg.getAppmchid())) params.put("subMerchantId", cfg.getAppmchid());
        if ("wxpay".equals(payType) && cfg.getMini() != null && notBlank(cfg.getMini().getAppid())) {
            params.put("appId", cfg.getMini().getAppid());
            params.put("isRaw", "0");
        }
        return params;
    }

    private Map<String, String> scanParams(InvokeContext ctx, HelipayConfig cfg, String payType) {
        var order = ctx.getOrder();
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPay");
        params.put("P2_orderId", order.getTradeNo());
        params.put("P3_customerNumber", cfg.getAppid());
        params.put("P4_payType", "SCAN");
        params.put("P5_orderAmount", toYuan(order.getReal()));
        params.put("P6_currency", "CNY");
        params.put("P7_authcode", "1");
        params.put("P8_appType", mapAppPayType(payType));
        params.put("P9_notifyUrl", notifyUrl(ctx, cfg));
        params.put("P10_successToUrl", buildPayUrl(ctx, cfg, Map.of()));
        params.put("P11_orderIp", order.getIpBuyer());
        params.put("P12_goodsName", cfg.getGoodsName());
        params.put("P13_goodsDetail", "");
        params.put("P14_desc", "");
        if (notBlank(cfg.getAppmchid())) params.put("P15_subMerchantId", cfg.getAppmchid());
        return params;
    }

    // =========================================================================
    // 核心请求
    // =========================================================================

    /**
     * 预下单：渠道接口提交并校验（HTTP 层 / JSON 解析 / 验签 / 业务码 rt2_retCode），
     * 失败抛 {@link Sdk.BizFailException}（渠道 HTTP 与业务失败归因渠道统一文案），
     * 成功返回渠道响应供调用方提取支付地址。
     */
    private HttpHelper.HttpResponse createOrder(InvokeContext ctx, Map<String, String> params, String key)
            throws Exception {
        var resp = HelipayApi.post(ctx, HelipayApi.API_URL, params, key);
        var respMap = PaymentUtils.parseJsonMap(resp.bodyAsString());
        if (!"0000".equals(respMap.get("rt2_retCode"))) {
            var msg = respMap.getOrDefault("rt3_retMsg", "");
            throw new Sdk.BizFailException(msg.isBlank() ? channelFailure(resp.statusCode())
                    : "[" + respMap.get("rt2_retCode") + "]" + msg, resp);
        }
        return resp;
    }

    /** 支付地址提取：扫码 rt8_qrcode / 公众号 rt8_payInfo / 小程序 rt10_payInfo / H5 rt9_wapurl */
    private static String payInfoOf(HttpHelper.HttpResponse resp) {
        var respMap = PaymentUtils.parseJsonMap(resp.bodyAsString());
        return firstNotEmpty(respMap.get("rt8_qrcode"), respMap.get("rt8_payInfo"),
                respMap.get("rt10_payInfo"), respMap.get("rt9_wapurl"));
    }

    // =========================================================================
    // 微信 H5 逆向取链接
    // =========================================================================

    /** 逆向取 openlink（浏览器可直拉起微信支付）；失败回退原始支付链接 */
    private static String reverseToOpenlink(InvokeContext ctx, String payInfo) {
        try {
            var openlink = jsoperatewxdataFromPayUrl(ctx, payInfo);
            if (openlink != null && !openlink.isBlank()) return openlink;
        } catch (Exception e) {
            log.warn("H5 逆向取链接失败，回退原始支付链接, error={}", e.getMessage());
        }
        return payInfo;
    }

    /**
     * 微信 H5 支付链接逆向取 openlink：解析 payUrl 的 appid/prepayid/sign/apptype（缺省 TH5），
     * 组 qbase 请求 POST servicewechat.com/wxa-qbase/jsoperatewxdata，三层 JSON 解包取 openlink。
     */
    private static String jsoperatewxdataFromPayUrl(InvokeContext ctx, String payUrl) throws Exception {
        var raw = payUrl.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("支付链接为空");
        var qIdx = raw.indexOf('?');
        if (qIdx < 0) throw new IllegalArgumentException("支付链接参数不完整");
        var qs = parseQuery(raw.substring(qIdx + 1));
        var appId = trimToNull(qs.get("appid"));
        var prepayId = trimToNull(qs.get("prepayid"));
        var sign = trimToNull(qs.get("sign"));
        var appType = trimToNull(qs.get("apptype"));
        if (appType == null) appType = "TH5";
        if (appId == null || prepayId == null || sign == null)
            throw new IllegalArgumentException("支付链接参数不完整");

        var query = "appid=" + encodeQueryValue(appId)
                + "&apptype=" + encodeQueryValue(appType)
                + "&prepayid=" + encodeQueryValue(prepayId)
                + "&sign=" + encodeQueryValue(sign);

        var qbaseActionData = HttpHelper.MAPPER.writeValueAsString(Map.of(
                "action", "getUrlScheme",
                "query", query,
                "options", Map.of("envVersion", "release")));

        var qbaseReq = new LinkedHashMap<String, Object>();
        qbaseReq.put("function_name", "public");
        qbaseReq.put("data", qbaseActionData);
        qbaseReq.put("action", 1);
        qbaseReq.put("scene", 1);
        qbaseReq.put("call_id", buildWxRequestId("-"));
        qbaseReq.put("cloudid_list", List.of());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("appid", appId);
        var data = new LinkedHashMap<String, Object>();
        data.put("qbase_api_name", "tcbapi_slowcallfunction_v2");
        data.put("qbase_req", HttpHelper.MAPPER.writeValueAsString(qbaseReq));
        data.put("qbase_options", Map.of("appid", appId, "env", QBASE_ENV));
        data.put("qbase_meta", Map.of(
                "session_id", buildWxRequestId(""),
                "sdk_version", "wx-web-sdk/1.1.0 (1602475903000)",
                "filter_user_info", false));
        data.put("cli_req_id", buildWxRequestId("_"));
        payload.put("data", data);

        var resp = HttpHelper.post(ctx, QBASE_URL, HttpHelper.MAPPER.writeValueAsString(payload),
                "application/json;charset=UTF-8",
                Map.of("Referer", "https://h5pay.helipay.com/", "Origin", "https://h5pay.helipay.com",
                        "Accept", "application/json, text/plain, */*"));
        if (!resp.isSuccess()) throw new IOException("qbase 请求失败[" + resp.statusCode() + "]");

        var root = HttpHelper.MAPPER.readTree(resp.bodyAsString());
        if (root.path("base_resp").path("ret").asInt(-1) != 0)
            throw new IOException("jsoperatewxdata 请求失败");
        var node2 = HttpHelper.MAPPER.readTree(root.path("data").asText(""));
        var node3 = HttpHelper.MAPPER.readTree(node2.path("data").asText(""));
        var openlink = node3.path("openlink").asText("").trim();
        if (openlink.isEmpty()) throw new IOException("未获取到 openlink");
        return openlink;
    }

    /** qbase 请求 ID：时间戳毫秒 + 分隔符 + 小数片段（微信约定格式） */
    private static String buildWxRequestId(String sep) {
        var sb = new StringBuilder(Long.toString(System.currentTimeMillis()));
        if (!sep.isEmpty()) sb.append(sep).append(randomFraction());
        return sb.toString();
    }

    /** 小数片段：0. + 16 位随机数字 */
    private static String randomFraction() {
        return "0." + (1_000_000_000_000_000L
                + ThreadLocalRandom.current().nextLong(9_000_000_000_000_000L));
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private static String mapAppPayType(String payType) {
        return switch (payType) {
            case "alipay" -> "ALIPAY";
            case "bank" -> "UNIONPAY";
            case "wxpay" -> "WXPAY";
            default -> "";
        };
    }

    private static String notifyUrl(InvokeContext ctx, HelipayConfig cfg) {
        return cfg.getNotifyDomain() + "/pay/notify/" + ctx.getOrder().getTradeNo();
    }

    private static String buildPayUrl(InvokeContext ctx, HelipayConfig cfg, Map<String, String> query) {
        var order = ctx.getOrder();
        var siteDomain = trimSlash(cfg.getSiteDomain());
        if (siteDomain.isEmpty()) return "";
        var url = siteDomain + "/pay/" + order.getType() + "/" + order.getTradeNo();
        if (query == null || query.isEmpty()) return url;
        var qs = new StringBuilder();
        query.forEach((k, v) -> {
            if (k == null || k.isEmpty() || v == null || v.isEmpty()) return;
            if (qs.length() > 0) qs.append('&');
            qs.append(k).append('=').append(encodeQueryValue(v));
        });
        return qs.isEmpty() ? url : url + "?" + qs;
    }

    private static String queryParam(InvokeContext ctx, String key) {
        var q = ctx.getRequest() != null ? ctx.getRequest().getQuery() : null;
        if (q == null || q.isBlank()) return "";
        for (var pair : q.split("&")) {
            var idx = pair.indexOf("=");
            if (idx >= 0 && pair.substring(0, idx).equals(key))
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static Map<String, String> parseQuery(String qs) {
        var map = new HashMap<String, String>();
        for (var pair : qs.split("&")) {
            var idx = pair.indexOf("=");
            if (idx >= 0)
                map.put(pair.substring(0, idx),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    /** URL 编码：仅保留 RFC 3986 无保留字符（与 Go url.QueryEscape 一致） */
    private static String encodeQueryValue(String s) {
        var sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String firstNotEmpty(String... vals) {
        for (var v : vals)
            if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        var t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        var t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
