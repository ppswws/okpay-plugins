package com.okpay.plugin.alipay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlipayConfig 指定收款账号 UID：直付通/服务商形态按字段值互斥。
 */
@DisplayName("AlipayConfig 指定收款账号 UID")
class AlipayConfigSellerIdTest {

    private static AlipayConfig cfg(String sellerId, String appMchid, String authToken) {
        var c = new AlipayConfig();
        c.setSellerId(sellerId);
        c.setAppMchid(appMchid);
        c.setAppAuthToken(authToken);
        return c;
    }

    @Test
    @DisplayName("未配置/空白 → 不传 seller_id")
    void blankSellerIdIsNull() {
        assertThat(cfg(null, null, null).sellerIdForPay()).isNull();
        assertThat(cfg("", null, null).sellerIdForPay()).isNull();
        assertThat(cfg("  ", null, null).sellerIdForPay()).isNull();
    }

    @Test
    @DisplayName("配置后去首尾空白")
    void sellerIdIsTrimmed() {
        assertThat(cfg(" 2088123456789012 ", null, null).sellerIdForPay())
                .isEqualTo("2088123456789012");
    }

    @Test
    @DisplayName("直付通（appmchid 非空）→ 不传 seller_id")
    void appMchidSuppressesSellerId() {
        assertThat(cfg("2088123456789012", "S1", null).sellerIdForPay()).isNull();
    }

    @Test
    @DisplayName("服务商（auth_token 非空）→ 不传 seller_id")
    void authTokenSuppressesSellerId() {
        assertThat(cfg("2088123456789012", null, "TOKEN").sellerIdForPay()).isNull();
    }
}
