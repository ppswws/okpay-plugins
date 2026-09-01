package com.okpay.plugin.helipay.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 合利宝 MD5 签名：拼接字段值（每字段前置 &）+ 尾部 &key，取 MD5 hex。
 *
 * <p>请求按 P1_bizType 分类型字段序、响应按 rt1_bizType 分类型字段序、通知按内容特征
 * （转账 rt1_bizType=Transfer + rt7_orderStatus、退款 rt3_refundOrderId、其余为支付）。
 * 字段序取自 okpay-pro helipay-go 生产实现，并补全其缺失的
 * AppPayRefundQuery/TransferQuery 两型（按接口文档"不参与签名"排除 P4_serialNumber
 * 与 rt8 之后的响应字段）。</p>
 */
public final class HelipaySignUtil {

    private HelipaySignUtil() {}

    // ---- 请求字段序（按 P1_bizType） ---------------------------------------

    static final List<String> REQ_APP_PAY = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_orderAmount",
            "P6_currency", "P7_authcode", "P8_appType", "P9_notifyUrl", "P10_successToUrl",
            "P11_orderIp", "P12_goodsName", "P13_goodsDetail", "P14_desc");
    static final List<String> REQ_APP_PAY_PUBLIC = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_appid",
            "P6_deviceInfo", "P7_isRaw", "P8_openid", "P9_orderAmount", "P10_currency",
            "P11_appType", "P12_notifyUrl", "P13_successToUrl", "P14_orderIp", "P15_goodsName",
            "P16_goodsDetail", "P17_limitCreditPay", "P18_desc");
    static final List<String> REQ_APP_PAY_H5 = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber", "P4_orderAmount", "P5_currency",
            "P6_orderIp", "P7_notifyUrl", "P8_appPayType", "P9_payType", "P10_appName",
            "P11_deviceInfo", "P12_applicationId", "P13_goodsName", "P14_goodsDetail", "P15_desc");
    static final List<String> REQ_APP_PAY_REFUND = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber", "P4_refundOrderId", "P5_amount", "P6_callbackUrl");
    /** P4_serialNumber 不参与签名（接口文档明确） */
    static final List<String> REQ_APP_PAY_QUERY = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber");
    /** P4_serialNumber 不参与签名（接口文档明确） */
    static final List<String> REQ_APP_PAY_REFUND_QUERY = List.of(
            "P1_bizType", "P2_refundOrderId", "P3_customerNumber");
    /** 单笔代付：notifyUrl 按生产实现纳入签名（接口文档标注不参与，以实测为准） */
    static final List<String> REQ_TRANSFER = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber", "P4_amount", "P5_bankCode",
            "P6_bankAccountNo", "P7_bankAccountName", "P8_biz", "P9_bankUnionCode", "P10_feeType",
            "P11_urgency", "P12_summary", "notifyUrl", "payerName", "payerShowName", "payerAccountNo");
    static final List<String> REQ_TRANSFER_QUERY = List.of(
            "P1_bizType", "P2_orderId", "P3_customerNumber");
    static final List<String> REQ_MERCHANT_ACCOUNT_QUERY = List.of(
            "P1_bizType", "P2_customerNumber", "P3_timestamp");

    private static final Map<String, List<String>> REQ_ORDERS = Map.of(
            "AppPay", REQ_APP_PAY,
            "AppPayPublic", REQ_APP_PAY_PUBLIC,
            "AppPayApplet", REQ_APP_PAY_PUBLIC,
            "AppPayH5WFT", REQ_APP_PAY_H5,
            "AppPayRefund", REQ_APP_PAY_REFUND,
            "AppPayQuery", REQ_APP_PAY_QUERY,
            "AppPayRefundQuery", REQ_APP_PAY_REFUND_QUERY,
            "Transfer", REQ_TRANSFER,
            "TransferQuery", REQ_TRANSFER_QUERY,
            "MerchantAccountQuery", REQ_MERCHANT_ACCOUNT_QUERY);

    // ---- 响应字段序（按 rt1_bizType，rt3_retMsg 与标注"不参与签名"的字段排除） ----

    static final List<String> RESP_APP_PAY = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber",
            "rt7_payType", "rt8_qrcode", "rt9_wapurl", "rt10_orderAmount", "rt11_currency");
    static final List<String> RESP_APP_PAY_PUBLIC = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber",
            "rt7_payType", "rt8_appid", "rt9_tokenId", "rt10_payInfo", "rt11_orderAmount", "rt12_currency");
    static final List<String> RESP_APP_PAY_H5 = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber",
            "rt7_appName", "rt8_payInfo", "rt9_orderAmount", "rt10_currency", "rt11_payType");
    static final List<String> RESP_APP_PAY_REFUND = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_refundOrderNum",
            "rt7_serialNumber", "rt8_amount", "rt9_currency");
    static final List<String> RESP_APP_PAY_QUERY = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber",
            "rt7_orderStatus", "rt8_orderAmount", "rt9_currency");
    /** rt11 起不参与签名（接口文档明确） */
    static final List<String> RESP_APP_PAY_REFUND_QUERY = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_refundOrderNum",
            "rt7_serialNumber", "rt8_orderStatus", "rt9_amount", "rt10_currency");
    static final List<String> RESP_TRANSFER = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber");
    /** rt8 起不参与签名（接口文档明确） */
    static final List<String> RESP_TRANSFER_QUERY = List.of(
            "rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber",
            "rt7_orderStatus");
    /** rt15_amountToBeSettled 不参与签名（接口文档明确） */
    static final List<String> RESP_MERCHANT_ACCOUNT_QUERY = List.of(
            "rt1_bizType", "rt2_retCode", "rt3_retMsg", "rt4_customerNumber", "rt5_accountStatus",
            "rt6_balance", "rt7_frozenBalance", "rt8_d0Balance", "rt9_T1Balance", "rt10_currency",
            "rt11_createDate", "rt12_desc");

    private static final Map<String, List<String>> RESP_ORDERS = Map.of(
            "AppPay", RESP_APP_PAY,
            "AppPayPublic", RESP_APP_PAY_PUBLIC,
            "AppPayApplet", RESP_APP_PAY_PUBLIC,
            "AppPayH5WFT", RESP_APP_PAY_H5,
            "AppPayRefund", RESP_APP_PAY_REFUND,
            "AppPayQuery", RESP_APP_PAY_QUERY,
            "AppPayRefundQuery", RESP_APP_PAY_REFUND_QUERY,
            "Transfer", RESP_TRANSFER,
            "TransferQuery", RESP_TRANSFER_QUERY,
            "MerchantAccountQuery", RESP_MERCHANT_ACCOUNT_QUERY);

    // ---- 通知字段序（按内容特征判定） ---------------------------------------

    /** 支付通知 */
    static final List<String> NOTIFY_ORDER = List.of(
            "rt1_customerNumber", "rt2_orderId", "rt3_systemSerial", "rt4_status",
            "rt5_orderAmount", "rt6_currency", "rt7_timestamp", "rt8_desc");
    /** 退款通知 */
    static final List<String> NOTIFY_REFUND = List.of(
            "rt1_customerNumber", "rt2_orderId", "rt3_refundOrderId", "rt4_systemSerial",
            "rt5_status", "rt6_amount", "rt7_currency", "rt8_timestamp");
    /** 转账通知（rt12_amount 不参与签名） */
    static final List<String> NOTIFY_TRANSFER = List.of(
            "rt1_bizType", "rt2_retCode", "rt3_retMsg", "rt4_customerNumber", "rt5_orderId",
            "rt6_serialNumber", "rt7_orderStatus", "rt8_notifyType", "rt9_reason",
            "rt10_createDate", "rt11_completeDate");

    // ---- 签名/验签 ----------------------------------------------------------

    /**
     * 按请求字段序签名（P1_bizType 决定字段序；缺失或未知类型按空串签名）。
     */
    public static String signRequest(Map<String, String> params, String key) {
        return sign(params, REQ_ORDERS.getOrDefault(keyOf(params.get("P1_bizType")), List.of()), key);
    }

    /**
     * 按响应字段序签名（rt1_bizType 决定字段序；缺失或未知类型按空串签名）。
     */
    public static String signResponse(Map<String, String> params, String key) {
        return sign(params, RESP_ORDERS.getOrDefault(keyOf(params.get("rt1_bizType")), List.of()), key);
    }

    /** Map.of 常量表不接受 null 键：bizType 缺失时兜底空串查表（命中默认空序） */
    private static String keyOf(String bizType) {
        return bizType != null ? bizType : "";
    }

    /**
     * 按内容特征判定通知字段序并签名：转账（rt1_bizType=Transfer 且 rt7_orderStatus 非空）
     * → 退款（rt3_refundOrderId 非空）→ 支付。
     */
    public static String signNotify(Map<String, String> params, String key) {
        if ("Transfer".equals(params.get("rt1_bizType")) && notBlank(params.get("rt7_orderStatus")))
            return sign(params, NOTIFY_TRANSFER, key);
        if (notBlank(params.get("rt3_refundOrderId")))
            return sign(params, NOTIFY_REFUND, key);
        return sign(params, NOTIFY_ORDER, key);
    }

    /** 响应验签：无 sign 字段时按生产实现直接放行 */
    public static boolean verifyResponse(Map<String, String> params, String key) {
        var sign = params.get("sign");
        if (sign == null || sign.isBlank()) return true;
        return sign.equalsIgnoreCase(signResponse(params, key));
    }

    /** 通知验签：sign 缺失一律拒绝 */
    public static boolean verifyNotify(Map<String, String> params, String key) {
        var sign = params.get("sign");
        if (sign == null || sign.isBlank()) return false;
        return sign.equalsIgnoreCase(signNotify(params, key));
    }

    private static String sign(Map<String, String> params, List<String> order, String key) {
        var sb = new StringBuilder(256);
        for (String field : order) {
            var v = params.get(field);
            sb.append('&').append(v != null ? v : "");
        }
        sb.append('&').append(key);
        return md5Hex(sb.toString());
    }

    private static String md5Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
