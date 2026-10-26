package com.okpay.plugin.helipay.util;

import static com.okpay.plugin.sdk.PaymentUtils.channelFailure;
import static com.okpay.plugin.sdk.PaymentUtils.encodeForm;

import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sdk.ResultUnknownException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合利宝接口调用封装：追加 signatureType=MD5 + sign，表单 POST 提交，
 * 统一校验响应解析 / 响应验签。
 *
 * <p>失败分类：传输层异常（超时/连接失败）、响应体解析不出 JSON、响应验签失败
 * → 结果未知，统一抛 {@link ResultUnknownException}；HTTP 状态不预先过滤，
 * 可解析的 4xx/5xx 业务报文由调用方按业务码处理。</p>
 *
 * <p>业务码 rt2_retCode 的判定语义随接口不同（下单/退款/查单/余额），由调用方各自处理。</p>
 */
public final class HelipayApi {

    /** 交易接口（下单/退款/转账/查单） */
    public static final String API_URL = "https://pay.trx.helipay.com/trx/app/interface.action";
    /** 商户接口（余额查询） */
    public static final String MERCHANT_API_URL = "https://pay.trx.helipay.com/trx/merchant/interface.action";

    private HelipayApi() {}

    /**
     * POST 表单到渠道接口：追加 signatureType=MD5 + sign（signatureType 不参与签名），
     * 校验 HTTP 层 / 响应解析 / 响应验签；成功返回渠道响应，调用方用
     * {@link PaymentUtils#parseJsonMap} 二次解析业务字段。
     */
    public static HttpHelper.HttpResponse post(InvokeContext ctx, String url, Map<String, String> params, String key)
            throws ResultUnknownException {
        var form = new LinkedHashMap<>(params);
        form.put("signatureType", "MD5");
        form.put("sign", HelipaySignUtil.signRequest(params, key));
        final HttpHelper.HttpResponse resp;
        try {
            resp = HttpHelper.post(ctx, url, encodeForm(form),
                    "application/x-www-form-urlencoded;charset=UTF-8");
        } catch (IOException | InterruptedException e) {
            // 传输层失败（超时/连接失败）：无 HTTP 响应，结果未知
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResultUnknownException(channelFailure() + "[" + e.getClass().getSimpleName() + "]", e);
        }
        // 先解析、后分类：HTTP 状态不作为业务成败判据；无法解析才是结果未知
        var respMap = PaymentUtils.parseJsonMap(resp.bodyAsString());
        if (respMap.isEmpty())
            throw new ResultUnknownException(
                channelFailure(resp.statusCode()) + "[" + safeBody(resp) + "]",
                new IllegalStateException("HTTP " + resp.statusCode() + " 响应不可用"));
        if (!HelipaySignUtil.verifyResponse(respMap, key))
            throw new ResultUnknownException("返回验签失败", resp);
        return resp;
    }

    /** 响应体摘要（仅用于失败记录，避免把错误页原文当异常消息） */
    private static String safeBody(HttpHelper.HttpResponse resp) {
        var body = resp.bodyAsString();
        if (body == null) return "";
        var t = body.trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
