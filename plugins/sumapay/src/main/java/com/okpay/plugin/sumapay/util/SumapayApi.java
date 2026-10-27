package com.okpay.plugin.sumapay.util;

import static com.okpay.plugin.sdk.PaymentUtils.channelFailure;

import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.sdk.ExpectedFailureException;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sdk.ResultUnknownException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 丰付支付接口调用封装：请求按接口约定字段表生成 RSA 签名后 GBK 编码传输，
 * 统一校验 HTTP 层 / 响应解码 / 响应验签。
 *
 * <p>传输层、HTTP 层、响应不可解析与验签失败均为结果未知（{@link ResultUnknownException}），
 * 不构造明确失败；业务码 result 的判定语义随接口不同（下单/退款/查单/分账），由调用方各自处理。</p>
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
    /** 代付提交接口（singleTransfer_toTransfer） */
    public static final String PAYOUT_URL = "https://api.sumapay.com/main/singleTransfer_toTransfer";
    /** 代付查询接口（singleTransfer_singleTransferQuery） */
    public static final String PAYOUT_QUERY_URL = "https://api.sumapay.com/main/singleTransfer_singleTransferQuery";
    /** 商户账户查询接口（MerchantAccountQueryAction_merchantAccountQuery） */
    public static final String MERCHANT_ACCOUNT_QUERY_URL =
            "https://api.sumapay.com/main/MerchantAccountQueryAction_merchantAccountQuery";

    private static final Charset GBK = Charset.forName("GBK");
    private static final String GBK_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=GBK";

    private SumapayApi() {}

    /**
     * POST 表单到渠道接口：按签名字段表生成签名追加到参数表，GBK 编码请求体，
     * 校验 HTTP 层 / 响应解码 / 响应验签；成功返回渠道响应，调用方用
     * {@link #decode} 解码后按 {@link PaymentUtils#parseJsonMap} 解析业务字段。
     * 失败分支的响应轨迹随 {@link ResultUnknownException} 携带（异常链），由 SDK 提取。
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
            // 传输层失败（超时/连接失败）：无 HTTP 响应，结果未知
            throw new ResultUnknownException(channelFailure(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResultUnknownException(channelFailure(), e);
        }
        // 先解析、后分类：HTTP 状态不作为业务成败判据；无法解析才是结果未知
        var respMap = PaymentUtils.parseJsonMap(decode(resp));
        if (respMap.isEmpty())
            throw unknown(channelFailure(resp.statusCode()), resp);
        if (!SumapaySignUtil.verify(fengfuPublicKey,
                SumapaySignUtil.concat(respMap, verifyKeys), respMap.get(verifyField)))
            throw expected("返回验签失败", resp);
        return resp;
    }

    /** 结果未知并携带响应轨迹。 */
    private static ResultUnknownException unknown(String message, HttpHelper.HttpResponse resp) {
        return new ResultUnknownException(message, resp);
    }

    /** 预期失败并携带响应轨迹。 */
    private static ExpectedFailureException expected(String message, HttpHelper.HttpResponse resp) {
        return new ExpectedFailureException(message, resp);
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
