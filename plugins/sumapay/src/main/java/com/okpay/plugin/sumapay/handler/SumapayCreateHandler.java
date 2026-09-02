package com.okpay.plugin.sumapay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.toYuan;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.sumapay.util.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 丰付支付下单处理器。
 *
 * <p>接口矩阵：IOZ2010 支付宝H5 / IOZ1017 微信H5 / IOZ1016 聚合扫码（交易网关），
 * IOZ2011 微信公众号后台支付（JSAPI 网关）。productId/productName/merAcct/bizType
 * 等产品参数不参与签名，暂按固定值下发。</p>
 */
public class SumapayCreateHandler {

    /** 业务类型（固定） */
    private static final String TOTAL_BIZ_TYPE = "BIZ01104";
    /** 终端 IP（固定） */
    private static final String TERMINAL_IP = "114.139.221.228";
    /** 产品参数（不参与签名，固定值） */
    private static final String PRODUCT_ID = "CQSJWS";
    private static final String PRODUCT_NAME = "元宝充值";
    private static final String MER_ACCT = "CSSH";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final List<String> ALIPAY_H5_SIGN_FIELDS = List.of(
            "requestType","requestId","merchantCode","totalBizType","totalPrice","goodsDesc",
            "noticeUrl","backUrl","userType","userIdIdentity","subMerchantId");
    private static final List<String> ALIPAY_H5_VERIFY_FIELDS = List.of(
            "requestId","result","payUrl","errorMsg");
    private static final List<String> WX_H5_SIGN_FIELDS = List.of(
            "requestType","requestId","merchantCode","totalBizType","totalPrice","goodsDesc",
            "envFlag","noticeUrl","backUrl","userType","userIdIdentity","subMerchantId");
    private static final List<String> WX_H5_VERIFY_FIELDS = List.of(
            "requestId","result","payUrl","mpAppId","mpPath","errorMsg");
    private static final List<String> SCAN_SIGN_FIELDS = List.of(
            "requestType","requestId","merchantCode","totalBizType","totalPrice","goodsDesc",
            "noticeUrl","passThrough","userType","userIdIdentity",
            "wechatSubMerchantId","alipaySubMerchantId");
    private static final List<String> SCAN_VERIFY_FIELDS = List.of(
            "requestId","result","codeUrl","codeImgString","errorMsg");
    private static final List<String> JSAPI_SIGN_FIELDS = List.of(
            "requestType","requestId","totalBizType","merchantCode","totalPrice","subAppId",
            "subOpenId","passThrough","goodsDesc","noticeUrl","userType","userIdIdentity",
            "subMerchantId");
    private static final List<String> JSAPI_VERIFY_FIELDS = List.of(
            "requestId","result","passThrough","pay_info");

    /** 按支付方式分发并提取支付入口 URL（复用 createWithHandlers）。 */
    public com.okpay.plugin.model.BizResult submit(InvokeContext ctx) {
        return Sdk.createResult(ctx, Map.of(
            "alipay", this::alipay, "wxpay", this::wxpay
        ));
    }

    public PageResponse alipay(InvokeContext ctx) {
        var cfg = SumapayConfig.from(ctx);
        // 支付方式编号为跨渠道共享语义（前端合并 biztype_* 为单一 biztype）：
        // 1=H5、2=聚合扫码
        var modes = cfg.modeSet();
        if (modes.contains("1")) return Sdk.lockCreate(ctx, () -> createAlipayH5(ctx, cfg));
        if (modes.contains("2")) return Sdk.lockCreate(ctx, () -> createScanPay(ctx, cfg, "alipay"));
        return Responses.respError("当前通道未开启支付宝支付方式");
    }

    public PageResponse wxpay(InvokeContext ctx) {
        var cfg = SumapayConfig.from(ctx);
        // 微信通道配置只含微信侧勾选的编号（前端按支付方式显示对应 biztype_* 组）：
        // 1=H5、2=微信公众号、3=聚合扫码
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;
        if (modes.contains("1")) return handleWxH5(ctx, cfg, ua);
        if (modes.contains("2")) return handleWxMp(ctx, cfg, ua);
        if (modes.contains("3")) return Sdk.lockCreate(ctx, () -> createScanPay(ctx, cfg, "wxpay"));
        return Responses.respError("当前通道未开启微信支付方式");
    }

    // =========================================================================
    // 微信 H5 / 公众号
    // =========================================================================

    private PageResponse handleWxH5(InvokeContext ctx, SumapayConfig cfg, String ua) {
        if (HttpHelper.isMobile(ua))
            return Sdk.lockCreate(ctx, () -> createWxH5(ctx, cfg));
        // 桌面端扫码支付：中转页规避安卓扫描部分链接二维码白屏，手机端重入后再建单
        return Responses.respPageURL("wxpay_qrcode", relayUrl(ctx, cfg));
    }

    private PageResponse handleWxMp(InvokeContext ctx, SumapayConfig cfg, String ua) {
        if (cfg.getMp() == null || cfg.getMp().getAppid() == null)
            return Responses.respError("缺少公众号配置");
        var code = queryParam(ctx, "auth_code");
        var retUrl = buildReturnUrl(ctx, cfg);
        if (code.isBlank()) {
            if (HttpHelper.isWeChat(ua)) {
                // 授权 URL 内置 SDK（统一公众号决策 + 风控落库），插件只透传
                return Responses.respJump(OAuthHelper.buildMpPayAuthUrl(ctx, cfg.getMp().getAppid(), retUrl,
                        ctx.getOrder().getTradeNo()));
            }
            return Responses.respPageURL("wxpay_qrcode", relayUrl(ctx, cfg));
        }
        // OAuth 兑换在锁外执行：兑换、风控、失败/拦截终止响应全部内置 SDK，
        // 插件只提供「拿到 openid 后如何下单」的回调
        return OAuthHelper.exchangeMpOpenid(ctx, cfg.getMp().getAppid(), cfg.getMp().getAppsecret(), code,
                openid -> Sdk.lockCreate(ctx, () -> createWxJsapi(ctx, cfg, openid)));
    }

    // =========================================================================
    // 下单实现（均走 lockOrderExt，返回 FetchResult）
    // =========================================================================

    /** IOZ2010 支付宝H5：payUrl 带 scheme 参数时取 scheme 为拉起地址；原始地址含域名跳转、否则扫码页兜底 */
    private Sdk.FetchResult createAlipayH5(InvokeContext ctx, SumapayConfig cfg) throws Exception {
        var params = baseParams(ctx, cfg);
        params.put("requestType", "IOZ2010");
        params.put("noticeUrl", notifyUrl(ctx, cfg));
        params.put("backUrl", resultPageUrl(ctx, cfg));
        params.put("terminalIp", TERMINAL_IP);
        params.put("subMerchantId", cfg.getAppmchid());
        var resp = SumapayApi.post(ctx, SumapayApi.CREATE_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                ALIPAY_H5_SIGN_FIELDS, "signature", ALIPAY_H5_VERIFY_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        requireSuccess(m, resp);
        var raw = m.get("payUrl");
        if (raw == null || raw.isBlank()) throw new Sdk.BizFailException("渠道未返回支付地址", resp);
        var payUrl = resolveScheme(raw);
        if (payUrl.isEmpty()) payUrl = raw;
        if (raw.contains("sumapay.com"))
            return Sdk.FetchResult.of(Responses.respJump(payUrl), resp);
        return Sdk.FetchResult.of(Responses.respPageURL("alipay_qrcode", payUrl), resp);
    }

    /** IOZ1017 微信H5：envFlag=3，页面流同支付宝H5（微信 H5 中转页） */
    private Sdk.FetchResult createWxH5(InvokeContext ctx, SumapayConfig cfg) throws Exception {
        var params = baseParams(ctx, cfg);
        params.put("requestType", "IOZ1017");
        params.put("envFlag", "3");
        params.put("noticeUrl", notifyUrl(ctx, cfg));
        params.put("backUrl", resultPageUrl(ctx, cfg));
        params.put("terminalIp", TERMINAL_IP);
        params.put("subMerchantId", cfg.getAppmchid());
        var resp = SumapayApi.post(ctx, SumapayApi.CREATE_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                WX_H5_SIGN_FIELDS, "signature", WX_H5_VERIFY_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        requireSuccess(m, resp);
        var raw = m.get("payUrl");
        if (raw == null || raw.isBlank()) throw new Sdk.BizFailException("渠道未返回支付地址", resp);
        var payUrl = resolveScheme(raw);
        if (payUrl.isEmpty()) payUrl = raw;
        if (raw.contains("sumapay.com"))
            return Sdk.FetchResult.of(Responses.respJump(payUrl), resp);
        return Sdk.FetchResult.of(Responses.respPageURL("wxpay_h5", payUrl), resp);
    }

    /** IOZ1016 聚合扫码：按支付方式填对应子商户编码，二维码图优先、否则扫码地址 */
    private Sdk.FetchResult createScanPay(InvokeContext ctx, SumapayConfig cfg, String payType) throws Exception {
        var params = baseParams(ctx, cfg);
        params.put("requestType", "IOZ1016");
        params.put("noticeUrl", notifyUrl(ctx, cfg));
        params.put("terminalIp", TERMINAL_IP);
        if ("wxpay".equals(payType)) params.put("wechatSubMerchantId", cfg.getAppmchid());
        else params.put("alipaySubMerchantId", cfg.getAppmchid());
        var resp = SumapayApi.post(ctx, SumapayApi.CREATE_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                SCAN_SIGN_FIELDS, "signature", SCAN_VERIFY_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        requireSuccess(m, resp);
        var codeImg = m.get("codeImgString");
        var qr = codeImg != null && !codeImg.isBlank()
                ? "data:image/png;base64," + codeImg : m.getOrDefault("codeUrl", "");
        if (qr.isEmpty()) throw new Sdk.BizFailException("渠道未返回支付地址", resp);
        var page = "wxpay".equals(payType) ? "wxpay_qrcode" : "alipay_qrcode";
        return Sdk.FetchResult.of(Responses.respPageURL(page, qr), resp);
    }

    /** IOZ2011 微信公众号后台支付：subOpenId 由 OAuth 兑换，返回原生 JS 支付参数交 wxpay_jspay 页面 */
    private Sdk.FetchResult createWxJsapi(InvokeContext ctx, SumapayConfig cfg, String openid) throws Exception {
        var params = baseParams(ctx, cfg);
        params.put("requestType", "IOZ2011");
        params.put("subAppId", cfg.getMp().getAppid());
        params.put("subOpenId", openid);
        params.put("noticeUrl", notifyUrl(ctx, cfg));
        params.put("subMerchantId", cfg.getAppmchid());
        var resp = SumapayApi.post(ctx, SumapayApi.JSAPI_URL, params,
                cfg.getAppsecret(), cfg.getAppkey(),
                JSAPI_SIGN_FIELDS, "signature", JSAPI_VERIFY_FIELDS, "signature");
        var m = PaymentUtils.parseJsonMap(SumapayApi.decode(resp));
        requireSuccess(m, resp);
        var payInfo = m.get("pay_info");
        if (payInfo == null || payInfo.isBlank()) throw new Sdk.BizFailException("渠道未返回支付参数", resp);
        return Sdk.FetchResult.of(Responses.respPageData("wxpay_jspay",
                Map.of("js_api_parameters", payInfo)), resp);
    }

    // =========================================================================
    // 公共参数
    // =========================================================================

    /** 公共请求参数（requestType/签名等按接口追加） + 产品参数（不参与签名） */
    private static Map<String, String> baseParams(InvokeContext ctx, SumapayConfig cfg) {
        var order = ctx.getOrder();
        var params = new LinkedHashMap<String, String>();
        params.put("requestId", order.getTradeNo());
        params.put("requestStartTime", LocalDateTime.now(SHANGHAI).format(TRADE_TIME));
        params.put("merchantCode", cfg.getAppid());
        params.put("totalBizType", TOTAL_BIZ_TYPE);
        params.put("totalPrice", toYuan(order.getReal()));
        params.put("goodsDesc", cfg.getGoodsName());
        params.put("passThrough", "");
        params.put("userType", "2");
        params.put("userIdIdentity", cfg.getAppuserid());
        params.put("productId", PRODUCT_ID);
        params.put("productName", PRODUCT_NAME);
        params.put("fund", toYuan(order.getReal()));
        params.put("merAcct", MER_ACCT);
        params.put("bizType", TOTAL_BIZ_TYPE);
        params.put("productNumber", "1");
        return params;
    }

    /** 业务失败（result != 00000）统一归因渠道，文案带渠道错误码 */
    private static void requireSuccess(Map<String, String> m, HttpHelper.HttpResponse resp) {
        var result = m.getOrDefault("result", "");
        if (!"00000".equals(result)) {
            var msg = m.getOrDefault("errorMsg", "");
            if (msg.isBlank()) msg = "返回数据解析失败";
            throw new Sdk.BizFailException("[" + result + "]" + msg, resp);
        }
    }

    /** payUrl 可能为带 scheme 查询参数的过渡页，取 scheme 为真实拉起地址 */
    private static String resolveScheme(String payUrl) {
        if (payUrl == null || payUrl.isBlank()) return "";
        var raw = payUrl.trim();
        try {
            var q = new URI(raw).getQuery();
            if (q != null) {
                for (var pair : q.split("&")) {
                    var idx = pair.indexOf("=");
                    if (idx > 0 && "scheme".equals(pair.substring(0, idx)))
                        return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            // 非标准 URL 原样返回
        }
        return raw;
    }

    private String notifyUrl(InvokeContext ctx, SumapayConfig cfg) {
        return cfg.getNotifyDomain() + "/pay/notify/" + ctx.getOrder().getTradeNo();
    }

    private String buildReturnUrl(InvokeContext ctx, SumapayConfig cfg) {
        var order = ctx.getOrder();
        return cfg.getSiteDomain() + "/pay/" + order.getType() + "/" + order.getTradeNo();
    }

    /** 渠道同步回跳统一收口：支付结果状态机页（/pay/result/{tradeNo}）。buildReturnUrl/relayUrl 专供 OAuth/桌面扫码中转，勿混用 */
    private static String resultPageUrl(InvokeContext ctx, SumapayConfig cfg) {
        return cfg.getSiteDomain() + "/pay/result/" + ctx.getOrder().getTradeNo();
    }

    /** 桌面端扫码中转页：真实支付在手机端重入后发起 */
    private String relayUrl(InvokeContext ctx, SumapayConfig cfg) {
        return buildReturnUrl(ctx, cfg) + "?t=" + System.currentTimeMillis() / 1000;
    }

    private static String queryParam(InvokeContext ctx, String key) {
        var q = ctx.getRequest() != null ? ctx.getRequest().getQuery() : null;
        if (q == null || q.isBlank()) return "";
        for (var pair : q.split("&")) {
            var idx = pair.indexOf("=");
            if (idx >= 0 && pair.substring(0, idx).equals(key))
                return java.net.URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }
}
