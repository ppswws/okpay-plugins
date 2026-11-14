package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.model.ComplaintRecord;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * 微信投诉列表分页：单页固定 10（避免 limit 过大触发“当前查询结果数据量过大”），
 * 游标即 offset，total_count 只在首页返回。
 */
@DisplayName("微信投诉列表分页同步")
class WxpayComplainSyncTest {

    private static final String MCH_ID = "1900006891";
    private static final String CERT_SERIAL = "5157F09EFDC096DE15EBE81A47057A7232F1B8E1";
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    private static final String PUB_KEY_ID = "PUB_KEY_ID_001";

    private static KeyPair kp;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        kp = kpg.generateKeyPair();
    }

    @Test
    @DisplayName("首页固定 limit=10，total_count 决定是否还有下一页")
    void firstPageUsesTenAndReturnsCursor() throws Exception {
        var url = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        url.set(inv.getArgument(1));
                        return signedOk(page(0, 10, 25));
                    });

            var result = new WxpayComplainCore().syncListPage(
                    cfg(), LocalDate.of(2026, 10, 17), null, 50);

            assertThat(url.get()).contains("limit=10", "offset=0");
            assertThat(result.getComplaints()).hasSize(10);
            assertThat(result.getNextCursor()).isEqualTo("10");
        }
    }

    @Test
    @DisplayName("宿主按游标逐页调用，短页即停，拉到没有为止")
    void followsCursorUntilShortPage() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        var request = inv.<String>getArgument(1);
                        int offset = offsetOf(request);
                        return signedOk(offset < 20 ? page(offset, 10, 25) : page(offset, 5, 25));
                    });

            var core = new WxpayComplainCore();
            var all = new ArrayList<ComplaintRecord>();
            String cursor = null;
            do {
                var page = core.syncListPage(cfg(), LocalDate.of(2026, 10, 17), cursor, 10);
                all.addAll(page.getComplaints());
                cursor = page.getNextCursor();
            } while (cursor != null);

            assertThat(all).hasSize(25);
        }
    }

    @Test
    @DisplayName("单页入口只取首页 10 条并带回下一页游标")
    void firstPageStopsAtTenAndKeepsCursor() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.get(any(), anyString(), any()))
                    .thenReturn(signedOk(page(0, 10, 25)));

            var result = new WxpayComplainCore().syncListPage(
                    cfg(), LocalDate.of(2026, 10, 17), null, 10);

            assertThat(result.getComplaints()).hasSize(10);
            assertThat(result.getNextCursor()).isEqualTo("10");
        }
    }

    private static int offsetOf(String url) {
        var marker = "offset=";
        int start = url.indexOf(marker) + marker.length();
        int end = url.indexOf('&', start);
        return Integer.parseInt(url.substring(start, end < 0 ? url.length() : end));
    }

    private static String page(int offset, int count, int total) {
        var sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            int n = offset + i;
            sb.append("{\"complaint_id\":\"C").append(n).append("\",")
                    .append("\"complaint_state\":\"PENDING\",")
                    .append("\"complaint_detail\":\"投诉内容").append(n).append("\"}");
        }
        return sb.append("],\"limit\":10,\"offset\":").append(offset)
                .append(",\"total_count\":").append(total).append('}').toString();
    }

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
