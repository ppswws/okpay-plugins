package com.okpay.plugin.joinpay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.joinpay.util.*;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 汇聚支付下单处理器。
 *
 * <p>微信建单在渠道请求前先判定本地页面（授权跳转 / 扫码中转页）；收银台由 SDK
 * 基类在 ext 为空时重走本类建单入口，因此本地页与后续渠道请求只此一条路径。</p>
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

    public PageOutcome alipay(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();
        if (modes.contains("2")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "ALIPAY_H5", "alipay_h5", true));
        if (modes.contains("1")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "ALIPAY_NATIVE", "alipay_qrcode", false));
        throw new ExpectedFailureException("当前通道未开启支付宝支付方式");
    }

    public PageOutcome wxpay(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();

        if (modes.contains("4")) return handleWxMini(ctx, cfg);
        if (modes.contains("2")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_H5_PLUS", "wxpay_h5", true));
        if (modes.contains("3")) return handleWxMp(ctx, cfg);
        if (modes.contains("1")) return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_NATIVE", "wxpay_qrcode", false));
        throw new ExpectedFailureException("当前通道未开启微信支付方式");
    }

    public PageOutcome bank(InvokeContext ctx) {
        var cfg = JoinpayConfig.from(ctx);
        var modes = cfg.modeSet();
        if (HttpHelper.isMobile(uaOf(ctx)) && modes.contains("2"))
            return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "UNIONPAY_H5", null, true));
        if (modes.contains("1"))
            return Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "UNIONPAY_NATIVE", "bank_qrcode", false));
        throw new ExpectedFailureException("当前通道未开启云闪付支付方式");
    }

    // =========================================================================
    // 微信小程序 / 公众号
    // =========================================================================

    private PageOutcome handleWxMini(InvokeContext ctx, JoinpayConfig cfg) {
        var local = miniLocalPage(ctx, cfg);
        if (local != null) return local;
        // OAuth 兑换在锁外执行；兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应均由 SDK 处理，
        // 插件只提供「拿到 openid 后如何下单」的回调
        return OAuthHelper.exchangeMiniOpenid(ctx, cfg.getMini().getAppid(),
                cfg.getMini().getAppsecret(), queryParam(ctx, "auth_code"),
                openid -> Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_XCX",
                        Map.of("q5_OpenId", openid, "q7_AppId", cfg.getMini().getAppid()),
                        "wxpay_jspay", false)));
    }

    /** 小程序本地页：未配置 → 预期失败；无 auth_code → 生成 scheme 跳小程序；有 → null（继续下单） */
    private PageOutcome miniLocalPage(InvokeContext ctx, JoinpayConfig cfg) {
        if (cfg.getMini() == null || cfg.getMini().getAppid() == null)
            throw new ExpectedFailureException("缺少小程序配置");
        if (!queryParam(ctx, "auth_code").isBlank()) return null;
        var retUrl = buildReturnUrl(ctx, cfg);
        try {
            var scheme = OAuthHelper.getMiniScheme(ctx, cfg.getMini().getAppid(),
                    cfg.getMini().getAppsecret(), "page/pay",
                    "real=" + toYuan(ctx.getOrder().getReal()) + "&url="
                            + java.net.URLEncoder.encode(retUrl, StandardCharsets.UTF_8));
            return Pages.page("wxpay_h5", scheme);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultUnknownException("获取小程序 scheme 被中断", e);
        } catch (Exception e) {
            // 生成失败属结果未知（订单尚未建单）：预期异常原样上抛，代码缺陷记 ERROR
            if (!PluginLog.isExpected(e)) log.error("获取小程序 scheme 失败", e);
            throw e instanceof RuntimeException re ? re
                    : new ResultUnknownException("获取小程序 scheme 失败: " + e.getMessage(), e);
        }
    }

    private PageOutcome handleWxMp(InvokeContext ctx, JoinpayConfig cfg) {
        var local = mpLocalPage(ctx, cfg);
        if (local != null) return local;
        // OAuth 兑换在锁外执行；兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应均由 SDK 处理，
        // 插件只提供「拿到 openid 后如何下单」的回调
        return OAuthHelper.exchangeMpOpenid(ctx, cfg.getMp().getAppid(), cfg.getMp().getAppsecret(),
                queryParam(ctx, "code"),
                openid -> Sdk.lockCreate(ctx, () -> createOrder(ctx, cfg, "WEIXIN_GZH",
                        Map.of("q5_OpenId", openid, "q7_AppId", cfg.getMp().getAppid()),
                        "wxpay_jspay", false)));
    }

    /** 公众号本地页：未配置 → 预期失败；微信内无 code → 授权跳转；其余 UA 无 code → 扫码中转页；有 code → null */
    private PageOutcome mpLocalPage(InvokeContext ctx, JoinpayConfig cfg) {
        if (cfg.getMp() == null || cfg.getMp().getAppid() == null)
            throw new ExpectedFailureException("缺少公众号配置");
        if (!queryParam(ctx, "code").isBlank()) return null;
        var retUrl = buildReturnUrl(ctx, cfg);
        if (HttpHelper.isWeChat(uaOf(ctx))) {
            // 授权 URL 由 SDK 决策：已配置系统公众号 → 先经宿主 /oauth/wxmp 授权（风控身份落库
            // buyer），再重定向到本通道公众号授权；两条路径兑换的 openid 均属本通道公众号
            return Pages.redirect(OAuthHelper.buildMpPayAuthUrl(ctx, cfg.getMp().getAppid(), retUrl,
                    ctx.getOrder().getTradeNo()));
        }
        return Pages.page("wxpay_qrcode", retUrl + "?t=" + System.currentTimeMillis() / 1000);
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
        params.put("p9_NotifyUrl", PaymentPaths.notify(cfg.getNotifyDomain(), order.getTradeNo()));
        params.put("q1_FrpCode", frpCode);
        params.put("qa_TradeMerchantNo", cfg.getAppmchid() != null ? cfg.getAppmchid() : "");
        extra.forEach(params::put);
        params.put("hmac", JoinpaySignUtil.sign(params, PAY_REQ_FIELDS, cfg.getAppkey()));

        var reqBody = PaymentUtils.encodeForm(params);
        try {
            var resp = HttpHelper.post(ctx, "https://trade.joinpay.com/tradeRt/uniPay", reqBody,
                    "application/x-www-form-urlencoded");
            // 渠道 HTTP 层失败（502/5xx，body 是 nginx 错误页非 JSON）与无法解析的响应：
            // 渠道是否已建单未知，结果未定（S_WAIT）
            if (!resp.isSuccess())
                throw new ResultUnknownException(channelFailure(resp.statusCode()));
            var respMap = JoinpayUtil.parseJsonNum(resp.bodyAsString());
            if (respMap.isEmpty())
                throw new ResultUnknownException(channelFailure(resp.statusCode()));
            // 可解析但验签不过：应答不可信，同样结果未定
            if (!JoinpaySignUtil.verify(respMap, PAY_RESP_FIELDS, cfg.getAppkey()))
                throw new ResultUnknownException("返回验签失败");
            // 渠道有响应且可解析、明确业务拒绝 → 明确失败
            if (!"100".equals(respMap.get("ra_Code")))
                return Sdk.FetchResult.of(Pages.fail(rejectMessage(respMap)), resp);
            var page = "wxpay_jspay".equals(pageName) ? jsapiPage(respMap)
                    : toPage(respMap, pageName, isH5);
            return Sdk.FetchResult.of(page, resp);
        } catch (IOException e) {
            // 传输层失败（超时/连接失败）：无 HTTP 响应，结果未知
            throw new ResultUnknownException(channelFailure(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultUnknownException(channelFailure(), e);
        }
    }

    /** 渠道明确拒绝的文案：带渠道响应码；描述为空时只保留码 */
    private static String rejectMessage(Map<String, String> respMap) {
        var rb = respMap.getOrDefault("rb_CodeMsg", "");
        return rb.isBlank() ? "[" + respMap.getOrDefault("ra_Code", "") + "]"
                : "[" + respMap.get("ra_Code") + "]" + rb;
    }

    /**
     * 应答映射：成功但缺少预期支付数据 → 明确失败（渠道已受理、可解析，只是拿不到支付页数据）；
     * 支付页 HTML 原样输出；其余按页模板下发。
     */
    private static PageOutcome toPage(Map<String, String> respMap, String pageName, boolean isH5) {
        if (isH5) {
            var url = respMap.getOrDefault("rc_Result", "");
            if (url.isBlank()) return Pages.fail("下单成功但未返回支付地址");
            return JoinpayUtil.isFormOrHtml(url) ? Pages.html(url) : Pages.page(pageName, url);
        }
        var qr = respMap.getOrDefault("rc_Result", "");
        if (qr.isBlank()) {
            var pic = respMap.getOrDefault("rd_Pic", "");
            if (pic.isBlank()) return Pages.fail("下单成功但未返回支付地址");
            qr = "data:image/png;base64," + pic;
        }
        return Pages.page(pageName, qr);
    }

    /** 公众号/小程序调起页：渠道 rc_Result 回支付参数 JSON 串，按 wxpay_jspay 模板 + js_api_parameters 下发。 */
    private static PageOutcome jsapiPage(Map<String, String> respMap) {
        var params = respMap.getOrDefault("rc_Result", "");
        if (params.isBlank()) return Pages.fail("下单成功但未返回支付参数");
        // 模板直接喂 WeixinJSBridge.invoke 须为对象；解析不出说明响应数据不合法（渠道已受理）
        final Object jsParams;
        try { jsParams = HttpHelper.MAPPER.readValue(params, Object.class); }
        catch (Exception e) { return Pages.fail("渠道支付参数解析失败"); }
        return Pages.page("wxpay_jspay", null, Map.of("js_api_parameters", jsParams));
    }

    private String buildReturnUrl(InvokeContext ctx, JoinpayConfig cfg) {
        var order = ctx.getOrder();
        return PaymentPaths.cashier(cfg.getSiteDomain(), order.getTradeNo());
    }

    /** 渠道同步回跳统一收口：支付结果状态机页（/pay/result/{tradeNo}）。buildReturnUrl 专供 OAuth/中转，勿混用 */
    private static String resultPageUrl(InvokeContext ctx, JoinpayConfig cfg) {
        return PaymentPaths.result(cfg.getSiteDomain(), ctx.getOrder().getTradeNo());
    }

    private static String uaOf(InvokeContext ctx) {
        return ctx.getRequest() != null ? ctx.getRequest().getUa() : null;
    }

    private static String queryParam(InvokeContext ctx, String key) {
        var q = ctx.getRequest() != null ? ctx.getRequest().getQuery() : null;
        if (q == null || q.isBlank()) return "";
        for (var pair : q.split("&")) {
            var idx = pair.indexOf("=");
            if (idx >= 0 && pair.substring(0, idx).equals(key))
                return java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
        }
        return "";
    }
}
