package com.okpay.plugin.helipay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HelipaySignUtil 字段序签名：请求按 P1_bizType、响应按 rt1_bizType、通知按内容特征，
 * 拼接 &值序列 + &key 取 MD5 hex。
 */
@DisplayName("HelipaySignUtil 签名/验签")
class HelipaySignUtilTest {

    // =========================================================================
    // 请求签名
    // =========================================================================

    @Test
    @DisplayName("AppPayQuery 请求签名向量：&P1&P2&P3&key 的 MD5（P4_serialNumber 不参与）")
    void queryRequestSignVector() {
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayQuery");
        params.put("P2_orderId", "O1");
        params.put("P3_customerNumber", "C1");
        params.put("P4_serialNumber", "SN1");

        assertThat(HelipaySignUtil.signRequest(params, "KEY"))
                .isEqualTo("86cbc7027d99a6a37b5b6e8f436c8333");
    }

    @Test
    @DisplayName("P4_serialNumber 有无不影响 AppPayQuery 签名（字段序不含它）")
    void queryRequestIgnoresSerialNumber() {
        var base = Map.of("P1_bizType", "AppPayQuery", "P2_orderId", "O1", "P3_customerNumber", "C1");
        var withSerial = new LinkedHashMap<>(base);
        withSerial.put("P4_serialNumber", "SN-CHANGED");

        assertThat(HelipaySignUtil.signRequest(withSerial, "KEY"))
                .isEqualTo(HelipaySignUtil.signRequest(base, "KEY"));
    }

    @Test
    @DisplayName("AppPayPublic 请求按 P1..P18 字段序签名（P20_subMerchantId 不参与）")
    void publicRequestUsesFullOrder() {
        var base = new LinkedHashMap<String, String>();
        base.put("P1_bizType", "AppPayPublic");
        for (int i = 2; i <= 18; i++) base.put("P" + i + "_f" + i, "v" + i);
        var withSub = new LinkedHashMap<>(base);
        withSub.put("P20_subMerchantId", "SUB");

        assertThat(HelipaySignUtil.signRequest(withSub, "KEY"))
                .isEqualTo(HelipaySignUtil.signRequest(base, "KEY"));
    }

    @Test
    @DisplayName("退款请求字段序 P1..P6（P6_callbackUrl 参与签名）")
    void refundRequestSignVector() {
        var params = new LinkedHashMap<String, String>();
        params.put("P1_bizType", "AppPayRefund");
        params.put("P2_orderId", "O1");
        params.put("P3_customerNumber", "C1");
        params.put("P4_refundOrderId", "R1");
        params.put("P5_amount", "1.00");
        params.put("P6_callbackUrl", "https://cb.example.com");
        // 仅断言参与字段序（签名随 P6 值变化），值已知时与手算一致
        assertThat(HelipaySignUtil.signRequest(params, "KEY"))
                .isEqualTo("8ffee0d33d62533053f8ff32cd1d4061");
    }

    @Test
    @DisplayName("未知 P1_bizType 按空字段序签名（&key）")
    void unknownBizTypeSignsEmptyOrder() {
        assertThat(HelipaySignUtil.signRequest(Map.of("P1_bizType", "NoSuchType", "P2_orderId", "O1"), "KEY"))
                .isEqualTo(HelipaySignUtil.signRequest(Map.of(), "KEY"));
    }

    // =========================================================================
    // 响应验签
    // =========================================================================

    @Test
    @DisplayName("响应验签：sign 缺失按生产实现放行，sign 错误拒绝，sign 正确通过")
    void verifyResponseSemantics() {
        var params = new LinkedHashMap<String, String>();
        params.put("rt1_bizType", "AppPayQuery");
        params.put("rt2_retCode", "0000");
        params.put("rt4_customerNumber", "C1");
        params.put("rt5_orderId", "O1");
        params.put("rt6_serialNumber", "SN1");
        params.put("rt7_orderStatus", "SUCCESS");
        params.put("rt8_orderAmount", "1.00");
        params.put("rt9_currency", "CNY");

        assertThat(HelipaySignUtil.verifyResponse(params, "KEY")).isTrue(); // 无 sign 放行

        params.put("sign", "bogus");
        assertThat(HelipaySignUtil.verifyResponse(params, "KEY")).isFalse();

        params.put("sign", HelipaySignUtil.signResponse(params, "KEY"));
        assertThat(HelipaySignUtil.verifyResponse(params, "KEY")).isTrue();
    }

    @Test
    @DisplayName("AppPayQuery 响应字段序：rt1..rt9（rt3_retMsg 不参与）")
    void queryResponseIgnoresRetMsg() {
        var base = Map.of("rt1_bizType", "AppPayQuery", "rt2_retCode", "0000",
                "rt5_orderId", "O1", "rt6_serialNumber", "SN1",
                "rt7_orderStatus", "SUCCESS");
        var withRetMsg = new LinkedHashMap<>(base);
        withRetMsg.put("rt3_retMsg", "任意说明");

        assertThat(HelipaySignUtil.signResponse(withRetMsg, "KEY"))
                .isEqualTo(HelipaySignUtil.signResponse(base, "KEY"));
    }

    @Test
    @DisplayName("余额响应字段序 rt1..rt12（rt15_amountToBeSettled 不参与）")
    void balanceResponseIgnoresAmountToBeSettled() {
        var base = new LinkedHashMap<String, String>();
        base.put("rt1_bizType", "MerchantAccountQuery");
        base.put("rt2_retCode", "0000");
        for (int i = 3; i <= 12; i++) base.put("rt" + i + "_f" + i, "v" + i);
        var withBal = new LinkedHashMap<>(base);
        withBal.put("rt15_amountToBeSettled", "100.00");

        assertThat(HelipaySignUtil.signResponse(withBal, "KEY"))
                .isEqualTo(HelipaySignUtil.signResponse(base, "KEY"));
    }

    // =========================================================================
    // 通知验签
    // =========================================================================

    @Test
    @DisplayName("支付通知签名向量：&rt1..&rt8&key 的 MD5")
    void orderNotifySignVector() {
        var params = new LinkedHashMap<String, String>();
        params.put("rt1_customerNumber", "N1");
        params.put("rt2_orderId", "O1");
        params.put("rt3_systemSerial", "S1");
        params.put("rt4_status", "SUCCESS");
        params.put("rt5_orderAmount", "1.00");
        params.put("rt6_currency", "CNY");
        params.put("rt7_timestamp", "TS");
        params.put("rt8_desc", "D");

        assertThat(HelipaySignUtil.signNotify(params, "KEY"))
                .isEqualTo("9763bd9f5d126f5e70f6e69f3beb4177");
    }

    @Test
    @DisplayName("通知类型判定：转账（Transfer + rt7_orderStatus）→ 退款（rt3_refundOrderId）→ 支付")
    void notifyTypeDiscrimination() {
        var transfer = Map.of("rt1_bizType", "Transfer", "rt5_orderId", "T1",
                "rt6_serialNumber", "SN1", "rt7_orderStatus", "DOING");
        var refund = Map.of("rt1_customerNumber", "N1", "rt2_orderId", "O1",
                "rt3_refundOrderId", "R1", "rt4_systemSerial", "S1",
                "rt5_status", "SUCCESS", "rt6_amount", "1.00",
                "rt7_currency", "CNY", "rt8_timestamp", "TS");
        var order = Map.of("rt1_customerNumber", "N1", "rt2_orderId", "O1",
                "rt3_systemSerial", "S1", "rt4_status", "SUCCESS",
                "rt5_orderAmount", "1.00", "rt6_currency", "CNY",
                "rt7_timestamp", "TS", "rt8_desc", "D");

        assertThat(HelipaySignUtil.signNotify(transfer, "KEY"))
                .isEqualTo("3498e856e8704f4a30972bb102546c47");
        // 支付/退款字段不同序：同 key 下签名互不相同（判定按内容特征而非 rt1）
        assertThat(HelipaySignUtil.signNotify(refund, "KEY")).isNotEqualTo(
                HelipaySignUtil.signNotify(order, "KEY"));
    }

    @Test
    @DisplayName("通知验签：sign 缺失一律拒绝，sign 正确通过")
    void verifyNotifySemantics() {
        var params = new LinkedHashMap<String, String>();
        params.put("rt1_customerNumber", "N1");
        params.put("rt2_orderId", "O1");
        params.put("rt3_systemSerial", "S1");
        params.put("rt4_status", "SUCCESS");
        params.put("rt5_orderAmount", "1.00");
        params.put("rt6_currency", "CNY");
        params.put("rt7_timestamp", "TS");
        params.put("rt8_desc", "D");

        assertThat(HelipaySignUtil.verifyNotify(params, "KEY")).isFalse(); // 缺 sign

        params.put("sign", HelipaySignUtil.signNotify(params, "KEY"));
        assertThat(HelipaySignUtil.verifyNotify(params, "KEY")).isTrue();
    }
}
