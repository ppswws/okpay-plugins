package com.okpay.plugin.epay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.epay.util.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;

/**
 * 易支付下单处理器。
 *
 * <p>负责：构建请求参数 → MD5 签名 → POST 渠道 → 解析支付方式 → LockOrderExt 缓存。</p>
 */
public class EpayCreateHandler {

    private static final Logger log = LoggerFactory.getLogger(EpayCreateHandler.class);

    /** 按支付方式分发并提取支付入口 URL（复用 createWithHandlers）。 */
    public com.okpay.plugin.model.BizResult submit(InvokeContext ctx) {
        return Sdk.createResult(ctx, Map.of(
            "alipay", this::alipay,
            "wxpay",  this::wxpay,
            "bank",   this::bank
        ));
    }

    public PageResponse alipay(InvokeContext ctx) { return handleCreate(ctx, AlipayMapper.INSTANCE); }
    public PageResponse wxpay(InvokeContext ctx)  { return handleCreate(ctx, WxpayMapper.INSTANCE); }
    public PageResponse bank(InvokeContext ctx)   { return handleCreate(ctx, BankMapper.INSTANCE); }

    // =========================================================================
    // 核心下单流程
    // =========================================================================

    private PageResponse handleCreate(InvokeContext ctx, PayMethodMapper mapper) {
        var cfg = EpayConfig.from(ctx);
        return Sdk.lockCreate(ctx, () -> {
            var order = ctx.getOrder();
            var params = buildParams(cfg, order, ctx);
            if (cfg.isSubmitMode()) {
                // Submit 模式：不请求渠道，拼接支付链接（GET 带签名参数直达渠道收银台）直接跳转；
                // 无实际 HTTP 请求，轨迹正常写 0（未写入渠道侧 ext 但请求轨迹仍需落账）
                var payUrl = cfg.getAppurl() + "/submit.php?" + encodeForm(params);
                return Sdk.FetchResult.of(Responses.respJump(payUrl),
                        Sdk.RequestStats.builder().reqCount(0).reqMs(0).build());
            }
            var reqBody = encodeForm(params);
            try {
                var resp = HttpHelper.post(ctx, cfg.getAppurl() + "/mapi.php", reqBody,
                        "application/x-www-form-urlencoded");
                // 非成功响应（502/5xx/CDN 拦截页/HTML 垃圾/业务失败）统一归因渠道：
                // parseJsonMap 对任意 body 吞异常安全返回 map，code 缺失即落入此分支；
                // 只有渠道明确给了 msg 才透传，否则用统一文案（不把渠道挂写成插件内部错误）
                var respMap = parseJsonMap(resp.bodyAsString());
                if (!"1".equals(respMap.get("code"))) {
                    var msg = respMap.get("msg");
                    var errMsg = (msg == null || msg.isBlank())
                            ? channelFailure(resp.statusCode()) : msg;
                    return Sdk.FetchResult.of(Responses.respError(errMsg), resp);
                }
                return Sdk.FetchResult.of(
                        mapper.map(resolveMethod(respMap), resolveUrl(respMap)), resp);
            } catch (IOException e) {
                // 传输层失败（超时/连接失败）：无 HTTP 响应，归因渠道统一文案
                log.error("易支付渠道请求失败, tradeNo={}", order.getTradeNo(), e);
                throw new Sdk.BizFailException(channelFailure(), null, e);
            } catch (Exception e) {
                log.error("易支付下单失败, tradeNo={}", order.getTradeNo(), e);
                throw e;
            }
        });
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private Map<String, String> buildParams(EpayConfig cfg, OrderSnapshot order, InvokeContext ctx) {
        var params = new LinkedHashMap<String, String>();
        params.put("pid", cfg.getAppid());
        params.put("type", order.getType());
        params.put("out_trade_no", order.getTradeNo());
        params.put("notify_url", cfg.getNotifyDomain() + "/pay/notify/" + order.getTradeNo());
        // 渠道同步回跳统一收口：支付结果状态机页（/pay/result/{tradeNo}），不再把商户地址直传上游
        params.put("return_url", cfg.getSiteDomain() + "/pay/result/" + order.getTradeNo());
        params.put("name", cfg.getGoodsName());
        params.put("money", toYuan(order.getReal()));
        if (!cfg.isSubmitMode()) {
            // mapi 模式需 device/clientip；submit 模式走 GET 收银台自行判断来源，不传
            params.put("clientip", order.getIpBuyer() != null ? order.getIpBuyer() : "");
            params.put("device", "jump");
        }
        params.put("param", order.getParam() != null ? order.getParam() : "");
        params.put("sign_type", "MD5");
        params.put("sign", EpaySignUtil.sign(params, cfg.getAppkey()));
        return params;
    }

    private static String resolveMethod(Map<String, String> resp) {
        if (!resp.getOrDefault("payurl", "").isBlank()) return "jump";
        if (!resp.getOrDefault("urlscheme", "").isBlank()) return "scheme";
        if (!resp.getOrDefault("qrcode", "").isBlank()) return "qrcode";
        return "";
    }

    private static String resolveUrl(Map<String, String> resp) {
        for (var k : List.of("payurl", "urlscheme", "qrcode")) {
            var v = resp.get(k); if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    // ---- 支付方式映射器 ------------------------------------------------------

    @FunctionalInterface
    private interface PayMethodMapper {
        PageResponse map(String method, String url);
    }

    private enum AlipayMapper implements PayMethodMapper {
        INSTANCE;
        public PageResponse map(String method, String url) { return switch (method) {
            case "jump"   -> Responses.respJump(url);
            case "qrcode" -> Responses.respPageURL("alipay_qrcode", url);
            default       -> Responses.respError("渠道未返回可用支付地址");
        };}
    }

    private enum WxpayMapper implements PayMethodMapper {
        INSTANCE;
        public PageResponse map(String method, String url) { return switch (method) {
            case "jump"   -> Responses.respJump(url);
            case "scheme" -> Responses.respPageURL("wxpay_h5", url);
            case "qrcode" -> Responses.respPageURL("wxpay_qrcode", url);
            default       -> Responses.respError("渠道未返回可用支付地址");
        };}
    }

    private enum BankMapper implements PayMethodMapper {
        INSTANCE;
        public PageResponse map(String method, String url) { return switch (method) {
            case "jump"   -> Responses.respJump(url);
            case "qrcode" -> Responses.respPageURL("bank_qrcode", url);
            default       -> Responses.respError("渠道未返回可用支付地址");
        };}
    }
}
