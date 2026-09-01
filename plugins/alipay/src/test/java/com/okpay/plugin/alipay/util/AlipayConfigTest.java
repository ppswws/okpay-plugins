package com.okpay.plugin.alipay.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlipayConfig 认证模式判定：显式 authMode 优先，其次三证书齐全推断证书模式。
 */
@DisplayName("AlipayConfig 证书模式判定")
class AlipayConfigTest {

    private AlipayConfig cfg(String authMode, boolean certs) {
        var c = new AlipayConfig();
        c.setAuthMode(authMode);
        if (certs) {
            c.setAppCert("a"); c.setAlipayCert("b"); c.setRootCert("c");
        }
        return c;
    }

    @Test
    @DisplayName("显式 authMode=cert → 证书模式（即使残留证书字段缺失也以显式为准）")
    void explicitCertWins() {
        assertThat(cfg("cert", true).certMode()).isTrue();
    }

    @Test
    @DisplayName("显式 authMode=key → 公钥模式（残留证书字段不生效，显式优先）")
    void explicitKeyOverridesResidualCerts() {
        assertThat(cfg("key", true).certMode()).isFalse();
        assertThat(cfg("key", false).certMode()).isFalse();
    }

    @Test
    @DisplayName("authMode 未显式（null/空）：三证书齐全 → 证书模式")
    void certsInferCertModeWhenAuthModeBlank() {
        assertThat(cfg(null, true).certMode()).isTrue();
        assertThat(cfg("", true).certMode()).isTrue();
    }

    @Test
    @DisplayName("authMode 未显式：任一证书缺失 → 公钥模式")
    void missingCertFallsBackToKey() {
        var c = new AlipayConfig();
        c.setAppCert("a"); c.setAlipayCert("b"); // root 缺失
        assertThat(c.certMode()).isFalse();
        assertThat(cfg(null, false).certMode()).isFalse();
    }

    @Test
    @DisplayName("authMode 非法值 → 公钥模式")
    void unknownAuthModeIsKey() {
        assertThat(cfg("rsa", true).certMode()).isFalse();
    }
}
