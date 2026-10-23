package com.okpay.plugin.joinpay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.joinpay.util.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;

/**
 * 汇聚支付下单处理器。
 */
public class JoinpayCreateHandler {

    private static final Logger log = LoggerFactory.getLogger(JoinpayCreateHandler.class);

    /** uniPay 下单请求签名字段（hmac 不进表，签名时空值跳过） */
    private static final List<String> PAY_REQ_FIELDS = List.of(
            "p0_Version","p1_MerchantNo","p2_OrderNo","p3_Amount","p4_Cur","p5_ProductName",
            "p6_ProductDesc","p7_Mp","p8_ReturnUrl","p9_NotifyUrl","q1_FrpCode","q2_MerchantBankCode",
            "q3_SubMerchantNo","q4_IsShowPic","q5_OpenId","q6_AuthCode","q7_AppId","q8_TerminalNo",
            "q9_TransactionModel","qa_TradeMerchantNo","qb_buyerId","qh_HbFqNum","qi_FqSellerPercen",
            "qj_DJPlan","qk_DisablePayModel","ql_TerminalIp","qm_ContractId","qn_SpecialInfo");
    /** uniPay 下单应答验签字段（hmac 单独取，不参与拼接） */
    private static final List<String> PAY_RESP_FIELDS = List.of(
            "r0_Version","r1_MerchantNo","r2_OrderNo","r3_Amount","r4_Cur","r5_Mp",
            "r6_FrpCode","r7_TrxNo","r8_MerchantBankCode","r9_SubMerchantNo",
            "ra_Code","rb_CodeMsg","rc_Result","rd_Pic");

    public com.okpay.plugin.model.BizResult submit(InvokeContext ctx) {
        return Sdk.createResult(ctx, Map.of(
            "alipay", this::alipay, "wxpay", this::wxpay, "bank", this::bank
        ));
    }

    public PageResponse alipay(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();
        if (modes.contains("2")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "ALIPAY_H5", "alipay_h5", true));
        if (modes.contains("1")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "ALIPAY_NATIVE", "alipay_qrcode", false));
        return Responses.respError("当前通道未开启支付宝支付方式");
    }

    public PageResponse wxpay(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;

        if (modes.contains("4")) return handleWxMini(ctx, cfg);
        if (modes.contains("2")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_H5_PLUS", "wxpay_h5", true));
        if (modes.contains("3")) return handleWxMp(ctx, cfg, ua);
        if (modes.contains("1")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_NATIVE", "wxpay_qrcode", false));
        return Responses.respError("当前通道未开启微信支付方式");
    }

    public PageResponse bank(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;
        if (HttpHelper.isMobile(ua) && modes.contains("2"))
            return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "UNIONPAY_H5", null, true));
        if (modes.contains("1"))
            return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "UNIONPAY_NATIVE", "bank_qrcode", false));
        return Responses.respError("当前通道未开启云闪付支付方式");
    }

    // =========================================================================
    // 微信小程序 / 公众号
    // =========================================================================

    private PageResponse handleWxMini(InvokeContext ctx, JoinpayConfig cfg) {
        if (cfg.getMini() == null || cfg.getMini().getAppid() == null)
            return Responses.respError("缺少小程序配置");
        var code = queryParam(ctx, "auth_code");
        var retUrl = buildReturnUrl(ctx, cfg);
        if (code.isBlank()) {
            try {
                var scheme = OAuthHelper.getMiniScheme(ctx, cfg.getMini().getAppid(),
                        cfg.getMini().getAppsecret(), "page/pay",
                        "real=" + toYuan(ctx.getOrder().getReal()) + "&url=" + java.net.URLEncoder.encode(retUrl, java.nio.charset.StandardCharsets.UTF_8));
                return Responses.respPageURL("wxpay_h5", scheme);
            } catch (Exception e) {
                if (!PluginLog.isExpected(e)) {
                    log.error("获取小程序 scheme 失败", e);
                }
                return Responses.respError("小程序跳转失败");
            }
        }
        // OAuth 兑换在锁外执行；兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应均由 SDK 处理，
        // 插件只提供「拿到 openid 后如何下单」的回调
        return OAuthHelper.exchangeMiniOpenid(ctx, cfg.getMini().getAppid(),
                cfg.getMini().getAppsecret(), code,
                openid -> Sdk.lockCreate(ctx, () -> {
                    try {
                        return createOrder(ctx, cfg, "WEIXIN_XCX",
                                Map.of("q5_OpenId", openid, "q7_AppId", cfg.getMini().getAppid()), "wxpay_jspay", false);
                    } catch (RuntimeException e) { throw e;
                    } catch (Exception e) { throw new RuntimeException("小程序支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    private PageResponse handleWxMp(InvokeContext ctx, JoinpayConfig cfg, String ua) {
        if (cfg.getMp() == null || cfg.getMp().getAppid() == null)
            return Responses.respError("缺少公众号配置");
        var code = queryParam(ctx, "code");
        var retUrl = buildReturnUrl(ctx, cfg);
        if (code.isBlank()) {
            if (HttpHelper.isWeChat(ua)) {
                // 授权 URL 由 SDK 决策：已配置系统公众号 → 先经宿主 /oauth/wxmp 授权（风控身份落库
                // buyer），再重定向到本通道公众号授权；两条路径兑换的 openid 均属本通道公众号
                return Responses.respJump(OAuthHelper.buildMpPayAuthUrl(ctx, cfg.getMp().getAppid(), retUrl,
                        ctx.getOrder().getTradeNo()));
            }
            return Responses.respPageURL("wxpay_qrcode", retUrl + "?t=" + System.currentTimeMillis() / 1000);
        }
        // OAuth 兑换在锁外执行；兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应均由 SDK 处理，
        // 插件只提供「拿到 openid 后如何下单」的回调
        return OAuthHelper.exchangeMpOpenid(ctx, cfg.getMp().getAppid(), cfg.getMp().getAppsecret(), code,
                openid -> Sdk.lockCreate(ctx, () -> {
                    try {
                        return createOrder(ctx, cfg, "WEIXIN_GZH",
                                Map.of("q5_OpenId", openid, "q7_AppId", cfg.getMp().getAppid()), "wxpay_jspay", false);
                    } catch (RuntimeException e) { throw e;
                    } catch (Exception e) { throw new RuntimeException("公众号支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    // =========================================================================
    // 核心方法
    // =========================================================================

    /** 无扩展参数的下单入口：多数支付方式不带 q5_OpenId/q7_AppId 等附加字段。 */
    Sdk.FetchResult createOrder(InvokeContext ctx, JoinpayConfig cfg, String frpCode,
                                String pageName, boolean isH5) {
        return createOrder(ctx, cfg, frpCode, Map.of(), pageName, isH5);
    }

    Sdk.FetchResult createOrder(InvokeContext ctx, JoinpayConfig cfg, String frpCode,
                                Map<String, String> extra, String pageName, boolean isH5) {
        var order = ctx.getOrder();
        var productName = JoinpayUtil.limitLength(cfg.getGoodsName(), 30);
        var params = new LinkedHashMap<String, String>();
        params.put("p0_Version", "2.6");
        params.put("p1_MerchantNo", cfg.getAppid());
        params.put("p2_OrderNo", order.getTradeNo());
        params.put("p3_Amount", toYuan(order.getReal()));
        params.put("p4_Cur", "1");
        params.put("p5_ProductName", productName);
        params.put("p6_ProductDesc", JoinpayUtil.limitLength(productName, 300));
        params.put("p7_Mp", JoinpayUtil.limitLength(order.getParam(), 100));
        params.put("p8_ReturnUrl", resultPageUrl(ctx, cfg));
        params.put("p9_NotifyUrl", cfg.getNotifyDomain() + "/pay/notify/" + order.getTradeNo());
        params.put("q1_FrpCode", frpCode);
        params.put("qa_TradeMerchantNo", cfg.getAppmchid() != null ? cfg.getAppmchid() : "");
        extra.forEach(params::put);
        params.put("hmac", JoinpaySignUtil.sign(params, PAY_REQ_FIELDS, cfg.getAppkey()));

        var reqBody = PaymentUtils.encodeForm(params);
        try {
            var resp = HttpHelper.post(ctx, "https://trade.joinpay.com/tradeRt/uniPay", reqBody,
                    "application/x-www-form-urlencoded");
            // 渠道 HTTP 层失败（502/5xx，body 是 nginx 错误页非 JSON）：归因渠道、统一文案
            if (!resp.isSuccess())
                throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
            var respMap = JoinpayUtil.parseJsonNum(resp.bodyAsString());
            // 预期 JSON 完全解析不出（CDN 拦截页/HTML 垃圾/空 body）→ 验签无从谈起，先归因渠道
            if (respMap.isEmpty())
                throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
            if (!JoinpaySignUtil.verify(respMap, PAY_RESP_FIELDS, cfg.getAppkey()))
                throw new Sdk.BizFailException("返回验签失败", resp);
            if (!"100".equals(respMap.get("ra_Code"))) {
                var rb = respMap.getOrDefault("rb_CodeMsg", "");
                throw new Sdk.BizFailException(
                        rb.isBlank() ? channelFailure(resp.statusCode())
                                     : "[" + respMap.get("ra_Code") + "]" + rb, resp);
            }
            var page = "wxpay_jspay".equals(pageName) ? jsapiPage(respMap, resp)
                    : toPage(respMap, pageName, isH5);
            return Sdk.FetchResult.of(page, resp);
        } catch (RuntimeException e) { throw e;
        } catch (IOException e) {
            // 传输层失败（超时/连接失败）：无 HTTP 响应，归因渠道统一文案
            throw new Sdk.BizFailException(channelFailure(), null, e);
        } catch (Exception e) { throw new RuntimeException("汇聚支付下单失败", e); }
    }

    private PageResponse toPage(Map<String, String> respMap, String pageName, boolean isH5) {
        if (isH5) {
            var url = respMap.getOrDefault("rc_Result", "");
            return JoinpayUtil.isFormOrHtml(url) ? Responses.respHTML(url)
                    : Responses.respPageURL(pageName, url);
        }
        var qr = respMap.getOrDefault("rc_Result",
                "data:image/png;base64," + respMap.getOrDefault("rd_Pic", ""));
        return Responses.respPageURL(pageName, qr);
    }

    /** 公众号/小程序调起页：渠道 rc_Result 回支付参数 JSON 串，按 wxpay_jspay 模板 + js_api_parameters 下发。 */
    private static PageResponse jsapiPage(Map<String, String> respMap, HttpHelper.HttpResponse resp) {
        var params = respMap.getOrDefault("rc_Result", "");
        if (params.isBlank()) throw new Sdk.BizFailException("下单成功但未返回支付参数", resp);
        // 模板直接喂 WeixinJSBridge.invoke 须为对象；解析不出归因渠道（不冒泡成插件内部错误）
        final Object jsParams;
        try { jsParams = HttpHelper.MAPPER.readValue(params, Object.class); }
        catch (Exception e) { throw new Sdk.BizFailException("渠道支付参数解析失败", resp); }
        return Responses.respPageData("wxpay_jspay", Map.of("js_api_parameters", jsParams));
    }

    private String buildReturnUrl(InvokeContext ctx, JoinpayConfig cfg) {
        var order = ctx.getOrder();
        return cfg.getSiteDomain() + "/pay/" + order.getType() + "/" + order.getTradeNo();
    }

    /** 渠道同步回跳统一收口：支付结果状态机页（/pay/result/{tradeNo}）。buildReturnUrl 专供 OAuth/中转，勿混用 */
    private static String resultPageUrl(InvokeContext ctx, JoinpayConfig cfg) {
        return cfg.getSiteDomain() + "/pay/result/" + ctx.getOrder().getTradeNo();
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
