package com.okpay.plugin.epay.handler;

import static com.okpay.plugin.sdk.PaymentUtils.*;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.*;
import com.okpay.plugin.epay.util.*;

import java.io.*;
import java.util.*;

/**
 * 易支付下单处理器。
 *
 * <p>负责：构建请求参数 → MD5 签名 → POST 渠道 → 解析支付方式 → LockOrderExt 缓存。</p>
 */
public class EpayCreateHandler {

    public PageOutcome alipay(InvokeContext ctx) { return handleCreate(ctx, AlipayMapper.INSTANCE); }
    public PageOutcome wxpay(InvokeContext ctx)  { return handleCreate(ctx, WxpayMapper.INSTANCE); }
    public PageOutcome bank(InvokeContext ctx)   { return handleCreate(ctx, BankMapper.INSTANCE); }

    // =========================================================================
    // 核心下单流程
    // =========================================================================

    private PageOutcome handleCreate(InvokeContext ctx, PayMethodMapper mapper) {
        var cfg = EpayConfig.from(ctx);
        return Sdk.lockCreate(ctx, () -> {
            var order = ctx.getOrder();
            var params = buildParams(cfg, order, ctx);
            if (cfg.isSubmitMode()) {
                // Submit 模式：不请求渠道，拼接带签名参数的支付链接直接跳转；无 HTTP 请求，请求轨迹记为 0
                var payUrl = cfg.getAppurl() + "/submit.php?" + encodeForm(params);
                return Sdk.FetchResult.of(Pages.redirect(payUrl),
                        Sdk.RequestStats.builder().reqCount(0).reqMs(0).build());
            }
            var reqBody = encodeForm(params);
            try {
                var resp = HttpHelper.post(ctx, cfg.getAppurl() + "/mapi.php", reqBody,
                        "application/x-www-form-urlencoded");
                var respMap = parseJsonMap(resp.bodyAsString());
                // 先解析、后分类：HTTP 状态不作为业务成败判据；无法解析/无业务码才是结果未知
                if (respMap.isEmpty() || respMap.get("code") == null)
                    throw new ResultUnknownException(channelFailure(resp.statusCode()));
                if (!EpayRespUtil.isSuccess(respMap)) {
                    var msg = respMap.get("msg");
                    var errMsg = (msg == null || msg.isBlank())
                            ? channelFailure(resp.statusCode()) : msg;
                    // 渠道有响应且可解析、明确业务拒绝 → S_FAIL
                    return Sdk.FetchResult.of(Pages.fail(errMsg), resp);
                }
                return Sdk.FetchResult.of(
                        mapper.map(resolveMethod(respMap), resolveUrl(respMap)), resp);
            } catch (IOException e) {
                // 传输层失败（超时/连接失败）：结果未知 → S_WAIT
                throw new ResultUnknownException(channelFailure(), e);
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
        params.put("notify_url", PaymentPaths.notify(cfg.getNotifyDomain(), order.getTradeNo()));
        // 渠道同步回跳指向宿主支付结果页，不透传商户地址
        params.put("return_url", PaymentPaths.result(cfg.getSiteDomain(), order.getTradeNo()));
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
        PageOutcome map(String method, String url);
    }

    private enum AlipayMapper implements PayMethodMapper {
        INSTANCE;
        public PageOutcome map(String method, String url) { return switch (method) {
            case "jump"   -> Pages.redirect(url);
            case "qrcode" -> Pages.page("alipay_qrcode", url);
            default       -> Pages.fail("渠道未返回可用支付地址");
        };}
    }

    private enum WxpayMapper implements PayMethodMapper {
        INSTANCE;
        public PageOutcome map(String method, String url) { return switch (method) {
            case "jump"   -> Pages.redirect(url);
            case "scheme" -> Pages.page("wxpay_h5", url);
            case "qrcode" -> Pages.page("wxpay_qrcode", url);
            default       -> Pages.fail("渠道未返回可用支付地址");
        };}
    }

    private enum BankMapper implements PayMethodMapper {
        INSTANCE;
        public PageOutcome map(String method, String url) { return switch (method) {
            case "jump"   -> Pages.redirect(url);
            case "qrcode" -> Pages.page("bank_qrcode", url);
            default       -> Pages.fail("渠道未返回可用支付地址");
        };}
    }
}
