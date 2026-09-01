package com.okpay.plugin.helipay.util;

import static com.okpay.plugin.sdk.PaymentUtils.channelFailure;
import static com.okpay.plugin.sdk.PaymentUtils.encodeForm;

import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sdk.Sdk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合利宝接口调用封装：追加 signatureType=MD5 + sign，表单 POST 提交，
 * 统一校验 HTTP 层 / 响应解析 / 响应验签，失败抛 {@link Sdk.BizFailException}
 * （渠道 HTTP 与解析失败归因渠道统一文案）。
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
            throws Exception {
        var form = new LinkedHashMap<>(params);
        form.put("signatureType", "MD5");
        form.put("sign", HelipaySignUtil.signRequest(params, key));
        final HttpHelper.HttpResponse resp;
        try {
            resp = HttpHelper.post(ctx, url, encodeForm(form),
                    "application/x-www-form-urlencoded;charset=UTF-8");
        } catch (IOException e) {
            // 传输层失败（超时/连接失败）：无 HTTP 响应，归因渠道统一文案
            throw new Sdk.BizFailException(channelFailure(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Sdk.BizFailException(channelFailure(), null, e);
        }
        // 渠道 HTTP 层失败（502/5xx，body 是 nginx 错误页非 JSON）：归因渠道、统一文案
        if (!resp.isSuccess())
            throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
        var respMap = PaymentUtils.parseJsonMap(resp.bodyAsString());
        // 预期 JSON 完全解析不出（CDN 拦截页/HTML 垃圾/空 body）→ 验签无从谈起，先归因渠道
        if (respMap.isEmpty())
            throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
        if (!HelipaySignUtil.verifyResponse(respMap, key))
            throw new Sdk.BizFailException("返回验签失败", resp);
        return resp;
    }
}
