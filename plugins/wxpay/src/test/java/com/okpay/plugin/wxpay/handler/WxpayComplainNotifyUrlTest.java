package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * 投诉回调地址设置：先查上游已配置地址，有则 PUT 更新、无则 POST 创建。
 * 公钥模式（配置私钥/公钥对）免平台证书下载 stub，响应验签用测试密钥自签。
 */
@DisplayName("微信投诉回调地址设置（先查后定）")
class WxpayComplainNotifyUrlTest {

    private static final String MCH_ID = "1900006891";
    private static final String CERT_SERIAL = "5157F09EFDC096DE15EBE81A47057A7232F1B8E1";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String PUB_KEY_ID = "PUB_KEY_ID_001";
    private static final String NOTIFY_URL = "https://pay.example.com/pay/complaint/1";

    private static KeyPair kp;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        kp = kpg.generateKeyPair();
    }

    @Test
    @DisplayName("上游已配置其他地址 → PUT 更新，不发 POST")
    void updatesWhenExists() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any(), any()))
                    .thenReturn(signedOk("{\"mchid\":\"" + MCH_ID
                            + "\",\"url\":\"https://old.example.com/pay/complaint/1\"}"));
            mocked.when(() -> HttpHelper.put(any(), any(), any(), any(), any()))
                    .thenReturn(signedOk("{}"));

            new WxpayComplainCore().setNotifyUrl(cfg(), NOTIFY_URL);

            mocked.verify(() -> HttpHelper.put(any(), any(), any(), any(), any()));
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("上游未配置（查询按错误返回）→ POST 创建，不发 PUT")
    void createsWhenQueryFails() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any(), any()))
                    .thenReturn(new HttpHelper.HttpResponse(404, Map.of(),
                            "{\"code\":\"RESOURCE_NOT_EXISTS\"}".getBytes(StandardCharsets.UTF_8), "req", 10, 1));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any()))
                    .thenReturn(signedOk("{}"));

            new WxpayComplainCore().setNotifyUrl(cfg(), NOTIFY_URL);

            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()));
            mocked.verify(() -> HttpHelper.put(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("上游已配置地址与目标一致 → 跳过写入（不发 POST/PUT）")
    void skipsWhenUnchanged() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any(), any()))
                    .thenReturn(signedOk("{\"mchid\":\"" + MCH_ID + "\",\"url\":\"" + NOTIFY_URL + "\"}"));

            new WxpayComplainCore().setNotifyUrl(cfg(), NOTIFY_URL);

            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()), never());
            mocked.verify(() -> HttpHelper.put(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("查询报非 RESOURCE_NOT_EXISTS 错误（如 500）→ 原样失败，不误走创建")
    void failsOnUnexpectedQueryError() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any(), any()))
                    .thenReturn(new HttpHelper.HttpResponse(500, Map.of(),
                            "{\"code\":\"SYSTEM_ERROR\",\"message\":\"系统繁忙\"}".getBytes(StandardCharsets.UTF_8),
                            "req", 10, 1));

            assertThatThrownBy(() -> new WxpayComplainCore().setNotifyUrl(cfg(), NOTIFY_URL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("设置通知地址失败");

            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()), never());
            mocked.verify(() -> HttpHelper.put(any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    @DisplayName("查询成功但 url 为空 → POST 创建")
    void createsWhenUrlBlank() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), any(), any()))
                    .thenReturn(signedOk("{\"mchid\":\"" + MCH_ID + "\"}"));
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any(), any()))
                    .thenReturn(signedOk("{}"));

            new WxpayComplainCore().setNotifyUrl(cfg(), NOTIFY_URL);

            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any(), any()));
            mocked.verify(() -> HttpHelper.put(any(), any(), any(), any(), any()), never());
        }
    }

    /** 投诉通道配置：公钥模式（authMode=public_key + 公钥ID/公钥），响应验签走配置公钥。 */
    private static ChannelConfig cfg() {
        var extras = new LinkedHashMap<String, String>();
        extras.put("privateKey", pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        extras.put("appkey", CERT_SERIAL);
        extras.put("appsecret", API_V3_KEY);
        extras.put("authMode", "public_key");
        extras.put("publickeyid", PUB_KEY_ID);
        extras.put("wxpay_public_key", pem("PUBLIC KEY", kp.getPublic().getEncoded()));
        return ChannelConfig.builder()
                .appMchId(MCH_ID).appKey(CERT_SERIAL).appSecret(API_V3_KEY)
                .extras(extras).build();
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    /** 上游成功响应：按公钥模式签名（serial 命中 publickeyid → 客户端用配置公钥验签）。 */
    private static HttpHelper.HttpResponse signedOk(String bodyJson) throws Exception {
        var timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        var nonce = "resp-nonce";
        var signature = RsaKeys.sign(timestamp + "\n" + nonce + "\n" + bodyJson + "\n", kp.getPrivate());
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("wechatpay-timestamp", List.of(timestamp));
        headers.put("wechatpay-nonce", List.of(nonce));
        headers.put("wechatpay-serial", List.of(PUB_KEY_ID));
        headers.put("wechatpay-signature", List.of(signature));
        return new HttpHelper.HttpResponse(200, headers,
                bodyJson.getBytes(StandardCharsets.UTF_8), "req", 10, 1);
    }
}
