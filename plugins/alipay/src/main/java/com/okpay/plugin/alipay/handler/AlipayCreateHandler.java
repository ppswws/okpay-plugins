package com.okpay.plugin.alipay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.alipay.util.AlipayConfig;
import com.okpay.plugin.alipay.util.AlipayModeResolver;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付宝支付下单处理器（零第三方 SDK，走 AlipayOpenApiClient）。
 *
 * <p>支付方式按访问 UA 自动分配（{@link AlipayModeResolver} 优先级表）。
 * 当面付JS/JSAPI 需 OAuth 身份（buyer）：
 * <ul>
 *   <li>JSAPI（小程序入口）：全局小程序应用已配置（payment.alipay.oauth_mini，
 *       alipay_mini_login）→ 跳宿主 /oauth/alipaymini，全局应用 + 黑名单风控 + buyer 落库；
 *       全局未配置 → 插件直调 SDK 用通道本身的应用完成 OAuth</li>
 *   <li>当面付JS（网页入口）：无全局应用键——user_id（2088）是账号级标识跨应用一致，
 *       恒走通道应用 OAuth</li>
 * </ul>
 * 通道路径身份经 {@link OAuthHelper} exchange 方法内部自动闭环风控（落库 buyer + 买家黑名单，
 * 命中黑名单直接构造失败页跳转终止支付）——风控全内置 SDK，插件无感知无分支，只管支付。</p>
 */
@Slf4j
public class AlipayCreateHandler {

    public PageResponse create(InvokeContext ctx) {
        return Sdk.createWithHandlers(ctx, Map.of("alipay", this::alipay));
    }

    public PageResponse alipay(InvokeContext ctx) {
        var cfg = AlipayConfig.from(ctx);
        var ua = ctx.getRequest() != null ? ctx.getRequest().getUa() : null;
        var mode = AlipayModeResolver.resolve(ua, cfg.modeSet(), cfg.paymode());
        if (mode == null) return Responses.respError("当前通道未开启支付宝支付方式");
        var order = ctx.getOrder();
        // 统一 OAuth（当面付JS/JSAPI）在 lockCreate 之外执行：跳转载荷若被锁持久化进 ext，
        // 回跳请求被 ext 缓存短路，流程死循环（OAuth 跳转不写 ext 不记轨迹）
        if ((mode == AlipayModeResolver.Mode.JSPAY || mode == AlipayModeResolver.Mode.JSAPI)
                && (order.getBuyer() == null || order.getBuyer().isBlank())) {
            return oauth(ctx, cfg, order, mode);
        }
        // APP 在 lockCreate 之外执行：sdkExecute 纯字符串构建（无 HTTP 轨迹），唤起页载荷不写 ext
        if (mode == AlipayModeResolver.Mode.APP) {
            // 合单 APP：merge.precreate 有 HTTP 轨迹 + 落库，须进锁（唤起页载荷写 ext，重入短路不重发）
            var combinePlan = combinePlan(ctx, cfg, order);
            if (!combinePlan.isEmpty()) {
                return Sdk.lockCreate(ctx, () -> appCombine(ctx, cfg, order, combinePlan));
            }
            try {
                var fr = App.execute(ctx, cfg, order);
                return Responses.buildReturnPage(fr.payload());
            } catch (Exception e) {
                return Responses.respError("拉起支付宝失败[" + e.getMessage() + "]");
            }
        }
        return Sdk.lockCreate(ctx, () -> switch (mode) {
            case JSPAY -> jsPay(ctx, cfg, order, order.getBuyer());
            case JSAPI -> jsApi(ctx, cfg, order, order.getBuyer());
            case QRCODE -> Precreate.execute(ctx, cfg, order);
            case WAP -> Wap.execute(ctx, cfg, order);
            case PAGE -> Page.execute(ctx, cfg, order);
            default -> throw new IllegalStateException("unreachable");
        });
    }

    // =========================================================================
    // 支付模式
    // =========================================================================

    @FunctionalInterface
    private interface Mode { Sdk.FetchResult execute(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order) throws Exception; }

    private static String notifyUrl(AlipayConfig cfg, String tradeNo) {
        return cfg.getNotifyDomain() + "/pay/notify/" + tradeNo;
    }

    /** 直付通单笔：SMID 非空时注入 sub_merchant + settle_info（结算到该子商户进件默认账号，账期 1d 最快自动确认）。与合单子单同构。 */
    private static void applyDirectPaySettle(AlipayConfig cfg, Map<String, Object> biz) {
        var smids = cfg.smidList();
        if (smids.isEmpty()) return;
        var smid = smids.get(ThreadLocalRandom.current().nextInt(smids.size()));
        biz.put("sub_merchant", Map.of("merchant_id", smid));
        biz.put("settle_info", Map.of(
                "settle_period_time", "1d",
                "settle_detail_infos", List.of(Map.of(
                        "trans_in_type", "defaultSettle",
                        "amount", biz.get("total_amount")))));
    }

    /** 客户端 IP 风控字段：business_params={"mc_create_trade_ip":ip}（business_params 是 biz_content 内的 JSON 串）；
     *  全 pay 方法（含合单子单）都带；IP 缺失（如本地/测试）不注入。 */
    private static void putClientIpBusinessParams(InvokeContext ctx, Map<String, Object> biz) {
        var ip = ctx.getRequest() != null ? ctx.getRequest().getIp() : null;
        if (ip != null && !ip.isBlank())
            biz.put("business_params", "{\"mc_create_trade_ip\":\"" + ip + "\"}");
    }

    /** 电脑网站支付 → 返回 HTML 表单 */
    private static final Mode Page = (ctx, cfg, order) -> {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        biz.put("product_code", "FAST_INSTANT_TRADE_PAY");
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        var extras = new LinkedHashMap<String, String>();
        extras.put("notify_url", notifyUrl(cfg, order.getTradeNo()));
        extras.put("return_url", order.getReturnUrl());
        return Sdk.FetchResult.of(Responses.respHTML(
                cfg.client().pageExecute("alipay.trade.page.pay", biz, cfg.extras(extras))), null);
    };

    /** 手机网站支付 → 返回 HTML 表单；达合单阈值 → 两段式（merge.precreate → wap.merge.pay） */
    private static final Mode Wap = (ctx, cfg, order) -> {
        var combine = combinePlan(ctx, cfg, order);
        if (!combine.isEmpty()) return wapCombine(ctx, cfg, order, combine);
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        biz.put("product_code", "QUICK_WAP_WAY");
        // quit_url 是 alipay.trade.wap.pay 的业务字段（商户退出后的返回地址），须放 biz_content
        biz.put("quit_url", order.getReturnUrl());
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        var extras = new LinkedHashMap<String, String>();
        extras.put("notify_url", notifyUrl(cfg, order.getTradeNo()));
        extras.put("return_url", order.getReturnUrl());
        return Sdk.FetchResult.of(Responses.respHTML(
                cfg.client().pageExecute("alipay.trade.wap.pay", biz, cfg.extras(extras))), null);
    };

    /** 扫码/订单码支付（方式 3/8）→ 返回二维码 URL（官方当面付扫码统一带 QR_CODE_OFFLINE） */
    private static final Mode Precreate = (ctx, cfg, order) -> {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        biz.put("product_code", "QR_CODE_OFFLINE");
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        var resp = cfg.client().execute(ctx, "alipay.trade.precreate", biz,
                cfg.extras(Map.of("notify_url", notifyUrl(cfg, order.getTradeNo()))));
        if (!resp.ok()) throw new Sdk.BizFailException(alipayErrMsg(resp), resp);
        return Sdk.FetchResult.of(Responses.respPageURL("alipay_qrcode", resp.text("qr_code")), resp);
    };

    // =========================================================================
    // 合单（大单拆小单，直付通 merge 协议）
    // =========================================================================

    /** 直付通合单上限（merge 协议）；起始单数 = SMID 数（clamp 到 [1,6]） */
    private static final int MAX_COMBINE_SUBS = 6;

    /** 合单拆单判定：直付通（SMID 非空）且通道开关开启 + 全局金额达阈值 → 拆单金额列表；否则空（走单笔路径） */
    private static List<Long> combinePlan(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order) {
        if (!cfg.combineOpen()) return List.of();
        var smidCount = cfg.smidList().size();
        if (smidCount == 0) return List.of(); // 非直付通不可合单（合单仅直付通形态）
        var c = ctx.getConfig();
        return CombinePlanner.split(order.getReal(),
                c == null ? 0 : c.getCombineAlipayMinMoneyCents(),
                c == null ? 0 : c.getCombineAlipaySubMoneyCents(),
                Math.min(smidCount, MAX_COMBINE_SUBS),
                MAX_COMBINE_SUBS);
    }

    /** WAP 合单：merge.precreate（建子单拿预订单号 + 落库）→ wap.merge.pay 只凭 pre_order_no 出表单 */
    private static Sdk.FetchResult wapCombine(InvokeContext ctx, AlipayConfig cfg,
                                              OrderSnapshot order, List<Long> plan) throws Exception {
        var preOrderNo = mergePrecreate(ctx, cfg, order, plan, "QUICK_WAP_WAY");
        var extras = new LinkedHashMap<String, String>();
        extras.put("notify_url", notifyUrl(cfg, order.getTradeNo()));
        extras.put("return_url", order.getReturnUrl());
        var html = cfg.client().pageExecute("alipay.trade.wap.merge.pay",
                Map.of("pre_order_no", preOrderNo), cfg.extras(extras));
        return Sdk.FetchResult.of(Responses.respHTML(html), null);
    }

    /** APP 合单：merge.precreate → app.merge.pay 只凭 pre_order_no → 唤起字符串（在 lockCreate 内执行） */
    private static Sdk.FetchResult appCombine(InvokeContext ctx, AlipayConfig cfg,
                                              OrderSnapshot order, List<Long> plan) throws Exception {
        var preOrderNo = mergePrecreate(ctx, cfg, order, plan, "QUICK_MSECURITY_PAY");
        var extras = new LinkedHashMap<String, String>();
        extras.put("notify_url", notifyUrl(cfg, order.getTradeNo()));
        var orderStr = cfg.client().sdkExecute("alipay.trade.app.merge.pay",
                Map.of("pre_order_no", preOrderNo), cfg.extras(extras));
        return Sdk.FetchResult.of(Responses.respPageData("alipay_h5",
                Map.of("url", alipayScheme(orderStr), "outTradeNo", order.getTradeNo())), null);
    }

    /** 合单创建公共步骤：merge.precreate 成功 → saveSubOrders（报文与落库共用同一份子单号/金额）→ 返回预订单号 */
    private static String mergePrecreate(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order,
                                         List<Long> plan, String productCode) throws Exception {
        var biz = mergeBiz(ctx, cfg, order, plan, productCode);
        var resp = cfg.client().execute(ctx, "alipay.trade.merge.precreate", biz,
                cfg.extras(Map.of("notify_url", notifyUrl(cfg, order.getTradeNo()))));
        if (!resp.ok()) throw new Sdk.BizFailException(alipayErrMsg(resp), resp);
        var preOrderNo = resp.text("pre_order_no");
        if (preOrderNo.isBlank())
            throw new Sdk.BizFailException("合单预创建未返回 pre_order_no", resp);
        var items = new ArrayList<SaveSubOrdersRequest.SubOrderItem>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            items.add(SaveSubOrdersRequest.SubOrderItem.builder()
                    .subTradeNo(Sdk.subTradeNo(order.getTradeNo(), i + 1))
                    .money(plan.get(i)).build());
        }
        Sdk.saveSubOrders(ctx, order.getTradeNo(), items);
        return preOrderNo;
    }

    /** 合单 biz_content：out_merge_no + order_details（app_id/子单号/金额/商品/客户端IP；
     *  直付通轮询 sub_merchant + settle_info 结算到进件默认账号） */
    private static Map<String, Object> mergeBiz(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order,
                                                List<Long> plan, String productCode) {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_merge_no", order.getTradeNo());
        var smids = cfg.smidList();
        var details = new ArrayList<Map<String, Object>>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            var d = new LinkedHashMap<String, Object>();
            d.put("app_id", cfg.getAppId());
            putClientIpBusinessParams(ctx, d);
            d.put("out_trade_no", Sdk.subTradeNo(order.getTradeNo(), i + 1));
            d.put("product_code", productCode);
            d.put("total_amount", toYuan(plan.get(i)));
            d.put("subject", cfg.getGoodsName());
            if (!smids.isEmpty()) {
                d.put("sub_merchant", Map.of("merchant_id", smids.get(i % smids.size())));
                // 结算到商户进件默认账号（trans_in_type=defaultSettle，trans_in 留空）：
                // amount 须与交易金额一致，settle_period_time=1d 账期最短自动确认结算
                d.put("settle_info", Map.of(
                        "settle_period_time", "1d",
                        "settle_detail_infos", List.of(Map.of(
                                "trans_in_type", "defaultSettle",
                                "amount", toYuan(plan.get(i))))));
            }
            details.add(d);
        }
        biz.put("order_details", details);
        return biz;
    }

    /** APP 支付（方式 6）→ 唤起字符串 → 支付宝 scheme → alipay_h5 页面 */
    private static final Mode App = (ctx, cfg, order) -> {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        biz.put("product_code", "QUICK_MSECURITY_PAY");
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        var orderStr = cfg.client().sdkExecute("alipay.trade.app.pay", biz,
                cfg.extras(Map.of("notify_url", notifyUrl(cfg, order.getTradeNo()))));
        return Sdk.FetchResult.of(Responses.respPageData("alipay_h5",
                Map.of("url", alipayScheme(orderStr), "outTradeNo", order.getTradeNo())), null);
    };

    /** 当面付JS（方式 4）：支付宝 App 内 AlipayJSBridge 调起。
     *
     * <p>需 OAuth 身份：buyer 空 → oauth()（lockCreate 之外，全局跳宿主 / 通道直调 SDK）；
     * buyer 非空 → alipay.trade.create（无 product_code）。
     * 身份类型按值推断：2088 开头 → buyer_id（user_id），否则 buyer_open_id。</p>
     */
    private static Sdk.FetchResult jsPay(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order,
                                         String buyer) throws Exception {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        if (buyer.startsWith("2088")) biz.put("buyer_id", buyer);
        else biz.put("buyer_open_id", buyer);
        var resp = cfg.client().execute(ctx, "alipay.trade.create", biz,
                cfg.extras(Map.of("notify_url", notifyUrl(cfg, order.getTradeNo()))));
        if (!resp.ok()) throw new Sdk.BizFailException(alipayErrMsg(resp), resp);
        return Sdk.FetchResult.of(Responses.respPageData("alipay_jspay",
                Map.of("alipay_trade_no", resp.text("trade_no"))), resp);
    }

    /**
     * JSAPI（方式 7）：生活号/小程序 H5 内 JSAPI 调起。
     *
     * <p>与当面付JS 同走统一 OAuth；调起方侧 my.tradePay 未实现（页面流程降级处理）。
     * buyer_open_id 恒传身份值：身份非 open_id 时渠道校验失败即渠道抛错（配置有误由渠道抛错）。
     * op_app_id 与 buyer_open_id 必须同源（open_id 是应用级标识）：全局小程序应用路径下宿主
     * 回跳携带 op_app_id（OAuth 来源应用），通道兜底路径身份即通道应用 open_id → op_app_id=通道应用。</p>
     */
    private static Sdk.FetchResult jsApi(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order,
                                         String buyer) throws Exception {
        var biz = new LinkedHashMap<String, Object>();
        biz.put("out_trade_no", order.getTradeNo());
        biz.put("total_amount", toYuan(order.getReal()));
        biz.put("subject", cfg.getGoodsName());
        applyDirectPaySettle(cfg, biz);
        putClientIpBusinessParams(ctx, biz);
        biz.put("product_code", "JSAPI_PAY");
        biz.put("op_app_id", opAppIdOf(ctx, cfg));
        biz.put("buyer_open_id", buyer);
        var resp = cfg.client().execute(ctx, "alipay.trade.create", biz,
                cfg.extras(Map.of("notify_url", notifyUrl(cfg, order.getTradeNo()))));
        if (!resp.ok()) throw new Sdk.BizFailException(alipayErrMsg(resp), resp);
        return Sdk.FetchResult.of(Responses.respPageData("alipay_jspay",
                Map.of("alipay_trade_no", resp.text("trade_no"))), resp);
    }

    /** JSAPI 的 op_app_id：宿主回跳携带的 OAuth 来源应用（全局小程序应用）优先，否则通道应用（通道兜底同源） */
    private static String opAppIdOf(InvokeContext ctx, AlipayConfig cfg) {
        var carried = queryParam(ctx, "op_app_id");
        return carried.isBlank() ? cfg.getAppId() : carried;
    }

    /**
     * 统一 OAuth（当面付JS/JSAPI 共用，lockCreate 之外执行）：
     * <ul>
     *   <li>JSAPI + 全局小程序应用已配置 → 跳宿主 /oauth/alipaymini（payment.alipay.oauth_mini，
     *       全局应用 + buyer 风控落库）；oauth_done 回跳后 buyer 仍空 → 失败终止
     *       （获取失败即失败，不降级为通道 OAuth 兜底）</li>
     *   <li>当面付JS 恒走通道应用 OAuth（user_id 账号级跨应用一致，无全局网页应用键）；
     *       JSAPI 全局未配置时同样走通道应用 OAuth——直调 SDK 用通道本身的应用
     *       （channel.config 的 appid/私钥）完成兑换</li>
     * </ul>
     * 兑换经 {@link OAuthHelper} exchange 方法内部自动闭环风控（落库 buyer + 黑名单，
     * 命中构造失败页跳转终止），插件无跳过路径。返回页面载荷，不写 ext 不记轨迹。
     */
    private static PageResponse oauth(InvokeContext ctx, AlipayConfig cfg, OrderSnapshot order,
                                      AlipayModeResolver.Mode mode) {
        var miniHostOAuth = mode == AlipayModeResolver.Mode.JSAPI
                && ctx.getConfig().isOauthAlipayMiniConfigured();
        var returnUrl = cfg.getSiteDomain() + "/pay/alipay/" + order.getTradeNo();
        // 首层：JSAPI + 全局小程序应用已配置 → 跳宿主统一 OAuth（redirect_uri 回本站支付页，state=tradeNo）
        if (miniHostOAuth && !queryParam(ctx, "oauth_done").equals("1")) {
            return Responses.respJump(cfg.getSiteDomain() + "/oauth/alipaymini"
                    + "?redirect_uri=" + URLEncoder.encode(returnUrl, StandardCharsets.UTF_8)
                    + "&state=" + order.getTradeNo());
        }
        var authCode = queryParam(ctx, "auth_code");
        if (authCode.isBlank()) {
            // 全局已配置但 oauth_done 回跳后 buyer 仍空 → 获取失败即失败（不降级通道 OAuth）
            if (miniHostOAuth) return Responses.respError("获取支付宝身份失败");
            // 通道应用 OAuth：跳授权页（redirect 回本站页面，回调收 auth_code）。
            // appid/私钥由 AlipayConfig.from 配置校验保证非空（通道必填字段），此处无需再防御
            return Responses.respJump(OAuthHelper.buildAliOAuthUrl(cfg.getAppId(), returnUrl,
                    order.getTradeNo(), isProd(cfg)));
        }
        if (miniHostOAuth) return Responses.respError("获取支付宝身份失败");
        // 通道 OAuth 回调：exchange 内部完成兑换、风控（落库 buyer + 黑名单）与失败/拦截终止
        // 响应构造（SDK 全内置，插件无风控分支）。identity 与支付一致：JSPAY 取 user_id
        // （2088 按值推断）、JSAPI 恒 open_id——SDK 按同一选择自动风控；插件只提供下单回调
        return OAuthHelper.exchangeAliIdentity(ctx, cfg.getAppId(),
                cfg.getAppPrivateKey(), authCode, isProd(cfg),
                mode == AlipayModeResolver.Mode.JSAPI ? "open_id" : "user_id",
                cfg.getAlipayPublicKey(),
                buyer -> Sdk.lockCreate(ctx, () -> switch (mode) {
                    case JSPAY -> jsPay(ctx, cfg, order, buyer);
                    case JSAPI -> jsApi(ctx, cfg, order, buyer);
                    default -> throw new IllegalStateException("unreachable");
                }));
    }

    /** is_prod 是 String 配置字段（"true"/"false"），转 boolean（缺省视为正式环境） */
    /** 渠道业务失败文案：sub_msg → msg → sub_code → code 逐级兜底，全缺 → 统一文案 */
    private static String alipayErrMsg(AlipayOpenApiClient.ApiResponse resp) {
        for (var v : List.of(resp.subMsg(), resp.msg(), resp.subCode(), resp.code())) {
            if (v != null && !v.isBlank()) return v;
        }
        return channelFailure(200);
    }

    private static boolean isProd(AlipayConfig cfg) {
        return !"false".equals(cfg.getIsProd());
    }

    // =========================================================================
    // 内部
    // =========================================================================

    /** 读取请求 query 参数（OAuth 回跳标记 oauth_done） */
    private static String queryParam(InvokeContext ctx, String key) {
        var q = ctx.getRequest() != null ? ctx.getRequest().getQuery() : null;
        return PaymentUtils.parseForm(q).getOrDefault(key, "");
    }

    /** 支付宝 App 唤起 scheme（orderSuffix 为 urlencode 的唤起字符串；APP/合单 APP 共用） */
    private static String alipayScheme(String orderStr) {
        return "alipays://platformapi/startApp?appId=20000125&orderSuffix="
                + URLEncoder.encode(orderStr, StandardCharsets.UTF_8)
                + "#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;end";
    }

}
