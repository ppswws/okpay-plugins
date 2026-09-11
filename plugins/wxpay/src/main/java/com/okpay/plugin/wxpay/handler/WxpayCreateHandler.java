package com.okpay.plugin.wxpay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.wxpay.util.WxpayConfig;
import com.okpay.plugin.wxpay.util.WxpayModeResolver;
import org.slf4j.*;

import java.io.*;
import java.util.*;

/**
 * 微信支付下单处理器（APIv3-only，零第三方 SDK，走 WechatPayV3Client）。
 */
public class WxpayCreateHandler {

    private static final Logger log = LoggerFactory.getLogger(WxpayCreateHandler.class);

    /** 按支付方式分发并提取支付入口 URL（复用 createWithHandlers）。 */
    public BizResult submit(InvokeContext ctx) {
        return Sdk.createResult(ctx, Map.of("wxpay", this::wxpay));
    }

    public PageResponse wxpay(InvokeContext ctx) {
        var cfg = WxpayConfig.from(ctx);
        var modes = cfg.modeSet();
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;
        var mode = WxpayModeResolver.resolve(ua, modes, cfg.mpAppId() != null, cfg.miniAppId() != null);
        if (mode == null) return Responses.respError("当前通道未开启微信支付方式");
        return switch (mode) {
            case QRCODE -> lockCreate(ctx, Native);
            case H5     -> lockCreate(ctx, H5);
            case APP    -> lockCreate(ctx, App);
            case MP     -> handleWxMp(ctx, cfg, ua);
            case MINI   -> handleWxMini(ctx, cfg);
        };
    }

    // =========================================================================
    // 支付模式（APIv3）
    // =========================================================================

    @FunctionalInterface
    private interface Mode { Sdk.FetchResult execute(InvokeContext ctx, WxpayConfig cfg, OrderSnapshot order) throws Exception; }

    /** 支付形态（单笔/合单共用分发） */
    private enum Kind { NATIVE, H5, APP, JSAPI }

    /** 组装下单公共字段（服务商模式用 sp_appid/sp_mchid/sub_mchid 结构） */
    private static Map<String, Object> buildBase(WxpayConfig cfg, OrderSnapshot order) {
        if (order.getReal() <= 0) throw new IllegalArgumentException("订单金额无效");
        var base = new LinkedHashMap<String, Object>();
        base.put("out_trade_no", order.getTradeNo());
        base.put("description", cfg.getGoodsName());
        base.put("notify_url", cfg.getNotifyDomain() + "/pay/notify/" + order.getTradeNo());
        var amount = new LinkedHashMap<String, Object>();
        amount.put("total", Math.toIntExact(order.getReal()));
        amount.put("currency", "CNY");
        base.put("amount", amount);
        if (cfg.isServiceProvider()) {
            base.put("sp_appid", cfg.genericAppId());
            base.put("sp_mchid", cfg.getMchId());
            base.put("sub_mchid", cfg.getSubMchId());
        } else {
            base.put("appid", cfg.genericAppId());
            base.put("mchid", cfg.getMchId());
        }
        return base;
    }

    /** 场景信息（单笔）：四种形态统一带买家 IP payer_client_ip（下单时记录的订单买家 IP）；H5 额外带 h5_info.type=Wap。 */
    private static void putSceneInfo(Map<String, Object> body, Kind kind, OrderSnapshot order) {
        var ip = order.getIpBuyer();
        if ((ip == null || ip.isBlank()) && kind != Kind.H5) return; // 非 H5 且无买家 IP：scene_info 整体可省略
        var scene = new LinkedHashMap<String, Object>();
        if (ip != null && !ip.isBlank()) scene.put("payer_client_ip", ip);
        if (kind == Kind.H5) scene.put("h5_info", Map.of("type", "Wap"));
        body.put("scene_info", scene);
    }

    /** 合单场景信息：仅 H5 形态带 scene_info（其余形态官方请求体无此字段）；payer_client_ip 必填，device_id 为终端标识。 */
    private static void putCombineSceneInfo(Map<String, Object> body, Kind kind, OrderSnapshot order) {
        if (kind != Kind.H5) return;
        var scene = new LinkedHashMap<String, Object>();
        var ip = order.getIpBuyer();
        if (ip != null && !ip.isBlank()) scene.put("payer_client_ip", ip);
        scene.put("h5_info", Map.of("type", "Wap"));
        scene.put("device_id", "10001");
        body.put("scene_info", scene);
    }

    /** 扫码支付 → 二维码 URL（达阈值自动合单拆单） */
    private static final Mode Native = (ctx, cfg, order) ->
            executeWithCombine(ctx, cfg, order, Kind.NATIVE, null, null);

    /** H5 支付 → 跳转 URL（附加 redirect_url；达阈值自动合单拆单） */
    private static final Mode H5 = (ctx, cfg, order) ->
            executeWithCombine(ctx, cfg, order, Kind.H5, null, null);

    /** APP 支付 → weixin:// 调起协议 URL（渲染 wxpay_h5 页，服务端算 sign；达阈值自动合单拆单） */
    private static final Mode App = (ctx, cfg, order) ->
            executeWithCombine(ctx, cfg, order, Kind.APP, null, null);

    /** 微信合单拆单：起拆 3 单；笔数上限分模式——服务商合单 1-50 笔，直连合单 2-10 笔（官方下单一律不得超） */
    private static final int MIN_COMBINE_SUBS = 3;
    private static final int MAX_COMBINE_SUBS_SP = 50;
    private static final int MAX_COMBINE_SUBS_DIRECT = 10;

    /** 拆单判定：通道开关开启 + 全局金额达阈值 → 子单金额列表，否则空（单笔路径） */
    private static List<Long> combinePlan(InvokeContext ctx, WxpayConfig cfg, OrderSnapshot order) {
        if (!cfg.combineOpen()) return List.of();
        var c = ctx.getConfig();
        return CombinePlanner.split(order.getReal(),
                c == null ? 0 : c.getCombineWxpayMinMoneyCents(),
                c == null ? 0 : c.getCombineWxpaySubMoneyCents(),
                MIN_COMBINE_SUBS, cfg.isServiceProvider() ? MAX_COMBINE_SUBS_SP : MAX_COMBINE_SUBS_DIRECT);
    }

    /** 单笔或合单分发（fetch 内执行；合单仅在渠道成功后才落库子单） */
    private static Sdk.FetchResult executeWithCombine(InvokeContext ctx, WxpayConfig cfg, OrderSnapshot order,
                                                      Kind kind, String appId, String openid) throws Exception {
        var plan = combinePlan(ctx, cfg, order);
        if (plan.isEmpty()) return singleExecute(kind, ctx, cfg, order, appId, openid);
        return combineExecute(kind, ctx, cfg, order, plan, appId, openid);
    }

    /** 单笔下单（原 4 形态逻辑） */
    private static Sdk.FetchResult singleExecute(Kind kind, InvokeContext ctx, WxpayConfig cfg,
                                                 OrderSnapshot order, String appId, String openid) throws Exception {
        var body = buildBase(cfg, order);
        putSceneInfo(body, kind, order);
        if (kind == Kind.JSAPI) {
            if (appId == null || appId.isBlank()) throw new IllegalArgumentException("JSAPI 缺少支付 appid");
            if (openid == null || openid.isBlank()) throw new IllegalArgumentException("JSAPI 缺少 openid");
            // openid 必须与支付 appid 同源：服务商走 sp_openid（绑定公众号默认按服务商应用口径），直连走 openid。
            // appid 一律用 OAuth 来源公众号/小程序（appId），不能用 genericAppId 兜底（可能与 openid 不同源）。
            body.put(cfg.isServiceProvider() ? "sp_appid" : "appid", appId);
            body.put("payer", cfg.isServiceProvider() ? Map.of("sp_openid", openid) : Map.of("openid", openid));
        }
        var resp = cfg.client().post(singlePath(kind, cfg), HttpHelper.MAPPER.valueToTree(body), ctx);
        if (kind == Kind.NATIVE) {
            return Sdk.FetchResult.of(Responses.respPageURL("wxpay_qrcode",
                    resp.body().path("code_url").asString()), resp);
        }
        if (kind == Kind.H5) {
            var url = resp.body().path("h5_url").asString(null);
            if (url == null) throw new Sdk.BizFailException("H5 下单未返回 h5_url", resp);
            url += "&redirect_url=" + urlEncode(resultPageUrl(cfg, ctx));
            return Sdk.FetchResult.of(Responses.respJump(url), resp);
        }
        return appJsapiResult(kind, ctx, cfg, resp, appId);
    }

    /** 下单端点：服务商走 partner 端点（/v3/pay/partner/transactions/*），直连走直连端点 */
    private static String singlePath(Kind kind, WxpayConfig cfg) {
        var base = cfg.isServiceProvider()
                ? "/v3/pay/partner/transactions/"
                : "/v3/pay/transactions/";
        return base + switch (kind) {
            case NATIVE -> "native";
            case H5 -> "h5";
            case APP -> "app";
            case JSAPI -> "jsapi";
        };
    }

    /** 合单下单（v3/combine-transactions/*）：顶层 combine_* 字段 + sub_orders[]，子单号确定性生成 */
    private static Sdk.FetchResult combineExecute(Kind kind, InvokeContext ctx, WxpayConfig cfg,
                                                  OrderSnapshot order, List<Long> plan,
                                                  String appId, String openid) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("combine_appid", cfg.genericAppId());
        body.put("combine_mchid", cfg.getMchId());
        body.put("combine_out_trade_no", order.getTradeNo());
        body.put("notify_url", cfg.getNotifyDomain() + "/pay/notify/" + order.getTradeNo());
        var subOrders = new ArrayList<Map<String, Object>>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            var sub = new LinkedHashMap<String, Object>();
            sub.put("out_trade_no", Sdk.subTradeNo(order.getTradeNo(), i + 1));
            // sub_orders.mchid 必填：商品单商户号，与 combine_mchid 有绑定关系；服务商模式下取值即 combine_mchid，
            // 并保留 sub_mchid 标识实际收款子商户
            sub.put("mchid", cfg.getMchId());
            sub.put("description", cfg.getGoodsName());
            sub.put("attach", "combine");
            var amount = new LinkedHashMap<String, Object>();
            amount.put("total_amount", plan.get(i));
            amount.put("currency", "CNY");
            sub.put("amount", amount);
            if (cfg.isServiceProvider()) sub.put("sub_mchid", cfg.getSubMchId());
            subOrders.add(sub);
        }
        body.put("sub_orders", subOrders);
        putCombineSceneInfo(body, kind, order);
        if (kind == Kind.JSAPI) {
            if (appId == null || appId.isBlank()) throw new IllegalArgumentException("合单 JSAPI 缺少支付 appid");
            if (openid == null || openid.isBlank()) throw new IllegalArgumentException("合单 JSAPI 缺少 openid");
            // 合单 payer 挂 combine_appid 下，字段是 combine_payer_info.openid（单 openid，无 sp/sub 之分）；
            // combine_appid 用 OAuth 公众号 appid 保证与 openid 同源
            body.put("combine_appid", appId);
            body.put("combine_payer_info", Map.of("openid", openid));
        }
        var path = switch (kind) {
            case NATIVE -> "/v3/combine-transactions/native";
            case H5 -> "/v3/combine-transactions/h5";
            case APP -> "/v3/combine-transactions/app";
            case JSAPI -> "/v3/combine-transactions/jsapi";
        };
        var resp = cfg.client().post(path, HttpHelper.MAPPER.valueToTree(body), ctx);
        // 渠道成功后才落库（fetch 内）：确定性子单号 + 幂等 upsert，重复下单不产生孤儿行
        var items = new ArrayList<SaveSubOrdersRequest.SubOrderItem>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            items.add(SaveSubOrdersRequest.SubOrderItem.builder()
                    .subTradeNo(Sdk.subTradeNo(order.getTradeNo(), i + 1))
                    .money(plan.get(i)).build());
        }
        Sdk.saveSubOrders(ctx, order.getTradeNo(), items);
        if (kind == Kind.NATIVE) {
            var codeUrl = resp.body().path("code_url").asString(null);
            if (codeUrl == null) throw new Sdk.BizFailException("合单下单未返回 code_url", resp);
            return Sdk.FetchResult.of(Responses.respPageURL("wxpay_qrcode", codeUrl), resp);
        }
        if (kind == Kind.H5) {
            var url = resp.body().path("h5_url").asString(null);
            if (url == null) throw new Sdk.BizFailException("合单 H5 未返回 h5_url", resp);
            url += "&redirect_url=" + urlEncode(resultPageUrl(cfg, ctx));
            return Sdk.FetchResult.of(Responses.respJump(url), resp);
        }
        return appJsapiResult(kind, ctx, cfg, resp, appId);
    }

    /** APP/JSAPI 调起参数（单笔/合单共用；合单 APP 调起键同单笔） */
    private static Sdk.FetchResult appJsapiResult(Kind kind, InvokeContext ctx, WxpayConfig cfg,
                                                  WechatPayV3Client.WxResponse resp, String appId) throws Exception {
        var prepayId = resp.body().path("prepay_id").asString(null);
        if (prepayId == null) throw new Sdk.BizFailException(
                (kind == Kind.APP ? "APP" : "JSAPI") + "下单未返回 prepay_id", resp);
        var timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        var nonceStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        if (kind == Kind.APP) {
            var params = new LinkedHashMap<String, Object>();
            params.put("noncestr", nonceStr);
            params.put("package", "Sign=WXPay");
            params.put("partnerid", cfg.getMchId());
            params.put("prepayid", prepayId);
            params.put("timestamp", timeStamp);
            // APP 调起签名第 4 行用裸 prepayid（不带 prepay_id= 前缀），与 JSAPI paySign 原文不同
            params.put("sign", cfg.client().appPaySign(cfg.genericAppId(),
                    Long.parseLong(timeStamp), nonceStr, prepayId));
            var codeUrl = "weixin://app/" + cfg.genericAppId() + "/pay/?" + formUrlEncode(params);
            return Sdk.FetchResult.of(Responses.respPageURL("wxpay_h5", codeUrl), resp);
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("appId", appId);
        params.put("timeStamp", timeStamp);
        params.put("nonceStr", nonceStr);
        params.put("package", "prepay_id=" + prepayId);
        params.put("signType", "RSA");
        params.put("paySign", cfg.client().paySign(appId, Long.parseLong(timeStamp), nonceStr, prepayId));
        params.put("outTradeNo", ctx.getOrder().getTradeNo());
        return Sdk.FetchResult.of(Responses.respPageData("wxpay_jspay", params), resp);
    }

    // =========================================================================
    // 公众号 / 小程序 OAuth（APIv3 JSAPI）
    // =========================================================================

    private PageResponse handleWxMp(InvokeContext ctx, WxpayConfig cfg, String ua) {
        var mpAppId = cfg.mpAppId();
        if (mpAppId == null) return Responses.respError("缺少公众号配置（请先在后台绑定公众号）");
        var order = ctx.getOrder();
        var code = queryParam(ctx, "code");
        var retUrl = buildReturnUrl(ctx, cfg);
        if (code.isBlank()) {
            if (HttpHelper.isWeChat(ua)) {
                // 授权 URL 由 SDK 内置决策：统一公众号已配置 → 先经宿主 /oauth/wxmp 用系统公众号授权
                //（风控身份落库 buyer + 黑名单），随后微信重定向到本通道公众号授权；未配置 → 直接
                // 通道授权。两条路径最终兑换的 openid 恒为本通道公众号 openid（支付 appid 同源），
                // buyer 由宿主/SDK 内部管理，插件不读不判。参数已由上下文保证非空，无需防御分支
                return Responses.respJump(OAuthHelper.buildMpPayAuthUrl(ctx, mpAppId, retUrl,
                        order.getTradeNo()));
            }
            return Responses.respPageURL("wxpay_qrcode", retUrl);
        }
        // OAuth 兑换在锁外执行：lockOrderExt 只缓存真正下单结果，openid 不做缓存。
        // 兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应全部内置 SDK（exchangeMpOpenid），
        // 插件只提供「拿到 openid 后如何下单」的回调，无任何风控分支
        return OAuthHelper.exchangeMpOpenid(ctx, mpAppId, cfg.mpAppSecret(), code,
                openid -> lockCreate(ctx, (ictx, icfg, iorder) -> {
                    try {
                        return jsapiPage(ictx, icfg, iorder, mpAppId, openid);
                    } catch (RuntimeException e) { throw e;
                    } catch (Exception e) { throw new RuntimeException("公众号支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    private PageResponse handleWxMini(InvokeContext ctx, WxpayConfig cfg) {
        var miniAppId = cfg.miniAppId();
        if (miniAppId == null) return Responses.respError("缺少小程序配置（请先在后台绑定小程序）");
        var code = queryParam(ctx, "auth_code");
        var retUrl = buildReturnUrl(ctx, cfg);
        if (code.isBlank()) {
            try {
                var scheme = OAuthHelper.getMiniScheme(ctx, miniAppId, cfg.miniAppSecret(), "page/pay",
                        "real=" + toYuan(ctx.getOrder().getReal()) + "&url="
                                + java.net.URLEncoder.encode(retUrl, java.nio.charset.StandardCharsets.UTF_8));
                return Responses.respPageURL("wxpay_h5", scheme);
            } catch (Exception e) { log.error("获取小程序 scheme 失败", e); return Responses.respError("小程序跳转失败"); }
        }
        // OAuth 兑换在锁外执行：lockOrderExt 只缓存真正下单结果，openid 不做缓存。
        // 兑换、风控（落库 buyer + 黑名单）、失败/拦截终止响应全部内置 SDK（exchangeMiniOpenid），
        // 插件只提供「拿到 openid 后如何下单」的回调，无任何风控分支
        return OAuthHelper.exchangeMiniOpenid(ctx, miniAppId, cfg.miniAppSecret(), code,
                openid -> lockCreate(ctx, (ictx, icfg, iorder) -> {
                    try {
                        return jsapiPage(ictx, icfg, iorder, miniAppId, openid);
                    } catch (RuntimeException e) { throw e;
                    } catch (Exception e) { throw new RuntimeException("小程序支付失败[" + e.getMessage() + "]", e); }
                }));
    }

    /** APIv3 JSAPI 下单 + 服务端 paySign（RSA），键名保持前端契约 wxpay_jspay；达阈值自动合单拆单 */
    private Sdk.FetchResult jsapiPage(InvokeContext ctx, WxpayConfig cfg, OrderSnapshot order,
                                      String appId, String openid) throws Exception {
        return executeWithCombine(ctx, cfg, order, Kind.JSAPI, appId, openid);
    }

    // =========================================================================
    // lockCreate — 只做包装
    // =========================================================================

    private PageResponse lockCreate(InvokeContext ctx, Mode mode) {
        var cfg = WxpayConfig.from(ctx);
        return Sdk.lockCreate(ctx, () -> {
            try {
                return mode.execute(ctx, cfg, ctx.getOrder());
            } catch (WechatPayException e) {
                if (e.httpStatus() > 0) {
                    // 渠道 HTTP 失败：微信错误 JSON 有 code/message → 透传业务失败文案；
                    // 纯网关失败（无 code，如 502/nginx）→ 统一文案
                    var msg = e.code() == null ? channelFailure(e.httpStatus()) : e.getMessage();
                    throw new Sdk.BizFailException(msg, e);
                }
                // 验签/解密失败（httpStatus=0）：非渠道问题，保持原样冒泡
                throw e;
            } catch (IOException e) {
                // 传输层失败（超时/连接失败）：无 HTTP 响应，归因渠道统一文案
                throw new Sdk.BizFailException(channelFailure(), null, e);
            }
        });
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private String buildReturnUrl(InvokeContext ctx, WxpayConfig cfg) {
        return (cfg != null ? cfg.getSiteDomain() : "") + "/pay/wxpay/" + ctx.getOrder().getTradeNo();
    }

    /** 渠道 H5 同步回跳统一收口：支付结果状态机页（/pay/result/{tradeNo}）。buildReturnUrl 专供 OAuth/中转，勿混用 */
    private static String resultPageUrl(WxpayConfig cfg, InvokeContext ctx) {
        return (cfg != null ? cfg.getSiteDomain() : "") + "/pay/result/" + ctx.getOrder().getTradeNo();
    }

    private static String queryParam(InvokeContext ctx, String key) {
        var q = ctx.getRequest() != null ? ctx.getRequest().getQuery() : null;
        return com.okpay.plugin.sdk.PaymentUtils.parseForm(q).getOrDefault(key, "");
    }

    /** 键值对 → application/x-www-form-urlencoded 查询串（值逐项 URL 编码） */
    private static String formUrlEncode(Map<String, Object> params) {
        var sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(urlEncode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }
}
