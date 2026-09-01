package com.okpay.plugin.sumapay.util;

import com.okpay.plugin.sumapay.TestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 丰付签名工具：RSA-SHA256 签名/验签、密钥格式兼容（PKCS8/X509/PKCS1/裸 Base64）、
 * 拼接规则（按字段序取非空值）。
 */
@DisplayName("SumapaySignUtil 签名/验签")
class SumapaySignUtilTest {

    // =========================================================================
    // concat 拼接规则
    // =========================================================================

    @Test
    @DisplayName("concat 按字段序取非空值拼接，空值跳过")
    void concatSkipsBlankInOrder() {
        var params = new LinkedHashMap<String, String>();
        params.put("requestId", "R1");
        params.put("merchantCode", "M1");
        params.put("totalPrice", "");
        params.put("goodsDesc", null);

        assertThat(SumapaySignUtil.concat(params, List.of(
                "requestId", "merchantCode", "totalPrice", "goodsDesc")))
                .isEqualTo("R1M1");
        assertThat(SumapaySignUtil.concat(params, List.of("totalPrice", "requestId")))
                .isEqualTo("R1");
    }

    // =========================================================================
    // 签名/验签（各密钥格式）
    // =========================================================================

    @Test
    @DisplayName("PKCS8 私钥 + X509 公钥往返，中文签名字节按 UTF-8")
    void roundTripPkcs8X509() {
        var plain = "R1M1订单-金额1.00";
        var signature = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, plain);

        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, plain, signature)).isTrue();
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, plain + "x", signature)).isFalse();
    }

    @Test
    @DisplayName("PKCS1 私钥（SEQUENCE{n,e,d,p,q,dp,dq,qinv} 兜底解析）往返")
    void roundTripPkcs1Private() {
        var signature = SumapaySignUtil.sign(TestKeys.PRIVATE_PKCS1, "PKCS1");

        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "PKCS1", signature)).isTrue();
    }

    @Test
    @DisplayName("裸 Base64 公钥（PEM 去头尾）验签")
    void roundTripBarePublicKey() {
        var signature = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, "BARE");

        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_BARE, "BARE", signature)).isTrue();
    }

    @Test
    @DisplayName("PKCS1 公钥（SEQUENCE{n,e} 兜底解析）验签")
    void roundTripPkcs1Public() {
        var signature = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, "PKCS1PUB");

        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_PKCS1, "PKCS1PUB", signature)).isTrue();
    }

    @Test
    @DisplayName("验签失败路径：签名缺失/空白/非法 Base64/篡改 → false 不抛异常")
    void verifyFailsGracefully() {
        var signature = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, "X");

        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "X", null)).isFalse();
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "X", "  ")).isFalse();
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "X", "not-base64!!")).isFalse();
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "Y", signature)).isFalse();
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY, "X", "AAAA")).isFalse();
    }

    @Test
    @DisplayName("签名产物稳定：同明文同密钥恒同签名（供跨语言对拍）")
    void signatureDeterministic() {
        var plain = "R1M1T1";

        var a = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, plain);
        var b = SumapaySignUtil.sign(TestKeys.PRIVATE_KEY, plain);

        assertThat(a).isEqualTo(b);
        assertThat(a).isBase64();
    }
}
