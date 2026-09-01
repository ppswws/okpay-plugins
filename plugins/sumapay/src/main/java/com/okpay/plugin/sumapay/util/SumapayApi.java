package com.okpay.plugin.sumapay.util;

import static com.okpay.plugin.sdk.PaymentUtils.channelFailure;

import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sdk.Sdk;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 丰付支付接口调用封装：请求按接口约定字段表生成 RSA 签名后 GBK 编码传输，
 * 统一校验 HTTP 层 / 响应解码 / 响应验签，失败抛 {@link Sdk.BizFailException}
 * （渠道 HTTP 与解析失败归因渠道统一文案）。
 *
 * <p>业务码 result 的判定语义随接口不同（下单/退款/查单/分账），由调用方各自处理。</p>
 */
public final class SumapayApi {

    /** 交易网关（IOZ2010 支付宝H5 / IOZ1017 微信H5 / IOZ1016 聚合扫码） */
    public static final String CREATE_URL = "https://www.sumapay.com/wechatTransitGateway/merchant.do";
    /** 微信公众号支付网关（IOZ2011） */
    public static final String JSAPI_URL = "https://www.sumapay.com/aggregatePayMobile/merchantService.do";
    /** 退款接口（Refund_do） */
    public static final String REFUND_URL = "https://api.sumapay.com/main/Refund_do";
    /** 分账接口（IFSU0043 二级户余额查询 / IFSU0040 付款至二级户） */
    public static final String FUND_SHARING_URL = "https://api.sumapay.com/fundSharing/merchant.do";
    /** 订单查询接口（SearchOrderAction_merSingleQuery） */
    public static final String QUERY_URL = "https://api.sumapay.com/main/SearchOrderAction_merSingleQuery";

    private static final Charset GBK = Charset.forName("GBK");
    private static final String GBK_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=GBK";

    private SumapayApi() {}

    /**
     * POST 表单到渠道接口：按签名字段表生成签名追加到参数表，GBK 编码请求体，
     * 校验 HTTP 层 / 响应解码 / 响应验签；成功返回渠道响应，调用方用
     * {@link #decode} 解码后按 {@link PaymentUtils#parseJsonMap} 解析业务字段。
     *
     * @param signKeys    请求签名参与字段（顺序敏感，空值跳过）
     * @param signField   请求签名字段名（signature / mersignature）
     * @param verifyKeys  响应验签参与字段（顺序敏感，空值跳过）
     * @param verifyField 响应签名字段名（signature / resultSignature）
     */
    public static HttpHelper.HttpResponse post(InvokeContext ctx, String url, Map<String, String> params,
                                               String merchantPrivateKey, String fengfuPublicKey,
                                               List<String> signKeys, String signField,
                                               List<String> verifyKeys, String verifyField) throws Exception {
        var form = new LinkedHashMap<>(params);
        form.put(signField, SumapaySignUtil.sign(merchantPrivateKey,
                SumapaySignUtil.concat(params, signKeys)));
        final HttpHelper.HttpResponse resp;
        try {
            resp = HttpHelper.postBytes(ctx, url, encodeFormGBK(form), GBK_CONTENT_TYPE, Map.of());
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
        var respMap = PaymentUtils.parseJsonMap(decode(resp));
        // 预期 JSON 完全解析不出（CDN 拦截页/HTML 垃圾/空 body）→ 验签无从谈起，先归因渠道
        if (respMap.isEmpty())
            throw new Sdk.BizFailException(channelFailure(resp.statusCode()), resp);
        if (!SumapaySignUtil.verify(fengfuPublicKey,
                SumapaySignUtil.concat(respMap, verifyKeys), respMap.get(verifyField)))
            throw new Sdk.BizFailException("返回验签失败", resp);
        return resp;
    }

    /**
     * 渠道响应 GBK 编码：Content-Type 缺 charset 或声明错误时按 UTF-8 解码必现替换符，
     * 检出后按 GBK 从原始字节重解。
     */
    public static String decode(HttpHelper.HttpResponse resp) {
        var text = resp.bodyAsString();
        return text.contains("\uFFFD") ? new String(resp.body(), GBK) : text;
    }

    /** GBK 编码的 x-www-form-urlencoded 请求体（键值先 GBK 字节再百分号编码） */
    static byte[] encodeFormGBK(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(percentEncode(e.getKey().getBytes(GBK))).append('=')
              .append(percentEncode(e.getValue().getBytes(GBK)));
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final String HEX = "0123456789ABCDEF";

    private static String percentEncode(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            }
        }
        return sb.toString();
    }
}
