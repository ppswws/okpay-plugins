package com.okpay.plugin.alipay.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 支付宝打款：转账提交（账户/银行卡分支 + identity_type 推断 + 场景报备）与转账查询 product_code 分支。
 */
@DisplayName("AlipaySubmitHandler/QueryHandler 打款")
class AlipayTransferHandlerTest {

    private static final String APP_ID = "2021000000000001";
    private static final String XFER_NO = "XF202608181200000000001";
    private static KeyPair KP;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private InvokeContext ctx(TransferSnapshot transfer, Map<String, Object> extraCfg) throws Exception {
        var cfgMap = new LinkedHashMap<String, Object>();
        cfgMap.put("appid", APP_ID);
        cfgMap.put("appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfgMap.put("appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded()));
        if (extraCfg != null) cfgMap.putAll(extraCfg);
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(HttpHelper.MAPPER.writeValueAsBytes(cfgMap)).build())
                .transfer(transfer)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 类型化读取嵌套 Map（payee_info/bankcard_ext_info），避免散落未检查强转 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }

    /** 从请求表单体解析 biz_content */
    private static Map<String, Object> parseBizContent(String formBody) throws Exception {
        var params = new LinkedHashMap<String, String>();
        for (var pair : formBody.split("&")) {
            var idx = pair.indexOf("=");
            if (idx > 0) params.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return HttpHelper.MAPPER.readValue(params.get("biz_content"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static TransferSnapshot transfer(String type, String accountType) {
        return TransferSnapshot.builder()
                .tradeNo(XFER_NO).type(type).accountType(accountType)
                .cardNo("6225880000001234").cardName("张三")
                .remark("货款").amount(500000).build();
    }

    // =========================================================================
    // 转账提交：账户转账（identity_type 按值推断）
    // =========================================================================

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "2088123456789012, ALIPAY_USER_ID",   // 2088 开头数字 → 支付宝 UID
            "13800138000, ALIPAY_LOGON_ID",        // 纯数字（手机号）→ 登录号
            "user@example.com, ALIPAY_LOGON_ID",   // 含 @ → 登录号
            "2088a2b3c4d5e6f7g, ALIPAY_OPEN_ID"    // 其余 → openid
    })
    @DisplayName("账户转账 identity_type 按收款账号值推断")
    void identityTypeInferred(String account, String expected) {
        assertThat(AlipaySubmitHandler.identityTypeOf(account)).isEqualTo(expected);
    }

    @Test
    @DisplayName("账户转账：TRANS_ACCOUNT_NO_PWD + 推断身份 + payer_show_name_use_alias + remark")
    void accountTransferBuildsUniTransfer() throws Exception {
        var xfer = TransferSnapshot.builder()
                .tradeNo(XFER_NO).type("alipay")
                .cardNo("13800138000").cardName("张三").remark("货款")
                .amount(500000).build();
        var ctx = ctx(xfer, null);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("O20260818");
            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz)
                    .containsEntry("out_biz_no", XFER_NO)
                    .containsEntry("product_code", "TRANS_ACCOUNT_NO_PWD")
                    .containsEntry("biz_scene", "DIRECT_TRANSFER")
                    .containsEntry("trans_amount", "5000.00")
                    .containsEntry("order_title", "货款")
                    .containsEntry("remark", "货款")
                    .containsEntry("business_params", "{\"payer_show_name_use_alias\":\"true\"}");
            var payee = mapOf(biz, "payee_info");
            assertThat(payee)
                    .containsEntry("identity_type", "ALIPAY_LOGON_ID")
                    .containsEntry("identity", "13800138000")
                    .containsEntry("name", "张三");
            assertThat(payee).doesNotContainKey("bankcard_ext_info");
        }
    }

    @Test
    @DisplayName("账户转账：UID 收款 name 空则不带（条件填充；登录号收款姓名必填见下）")
    void accountTransferOmitsBlankRemarkAndName() throws Exception {
        var xfer = TransferSnapshot.builder()
                .tradeNo(XFER_NO).type("alipay").cardNo("2088123456789012")
                .amount(500000).build();
        var ctx = ctx(xfer, null);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz).doesNotContainKey("remark");
            assertThat(biz).containsEntry("order_title", "转账");
            var payee = mapOf(biz, "payee_info");
            assertThat(payee)
                    .containsEntry("identity_type", "ALIPAY_USER_ID")
                    .doesNotContainKey("name");
        }
    }

    @Test
    @DisplayName("账户转账：登录号（手机号/邮箱）收款缺姓名 → PARAM_ERROR 拒绝（不发起请求）")
    void accountTransferLogonIdRequiresName() throws Exception {
        var xfer = TransferSnapshot.builder()
                .tradeNo(XFER_NO).type("alipay").cardNo("user@example.com")
                .amount(500000).build();
        var ctx = ctx(xfer, null);

        var result = new AlipaySubmitHandler().handle(ctx,
                BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

        assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(result.getMsg()).contains("收款人姓名");
    }

    @Test
    @DisplayName("银行卡转账缺收款人姓名 → PARAM_ERROR 拒绝（不发起请求）")
    void bankcardTransferRequiresName() throws Exception {
        var xfer = TransferSnapshot.builder()
                .tradeNo(XFER_NO).type("bank").cardNo("6225880000001234")
                .amount(500000).build();
        var ctx = ctx(xfer, null);

        var result = new AlipaySubmitHandler().handle(ctx,
                BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

        assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
        assertThat(result.getMsg()).contains("收款人姓名");
    }

    // =========================================================================
    // 转账提交：银行卡转账
    // =========================================================================

    @Test
    @DisplayName("银行卡转账（对私默认）：TRANS_BANKCARD_NO_PWD + BANK_CARD_NO + account_type=2，无 business_params")
    void bankcardTransferPrivateDefault() throws Exception {
        var ctx = ctx(transfer("bank", null), null);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz)
                    .containsEntry("product_code", "TRANS_BANKCARD_NO_PWD")
                    .containsEntry("biz_scene", "DIRECT_TRANSFER");
            assertThat(biz).doesNotContainKey("business_params");
            var payee = mapOf(biz, "payee_info");
            assertThat(payee)
                    .containsEntry("identity_type", "BANKCARD_ACCOUNT")
                    .containsEntry("identity", "6225880000001234")
                    .containsEntry("name", "张三");
            var bankcard = mapOf(payee, "bankcard_ext_info");
            assertThat(bankcard).containsEntry("account_type", "2");
        }
    }

    @Test
    @DisplayName("银行卡转账（对公）：account_type=1")
    void bankcardTransferPublicAccountType() throws Exception {
        var ctx = ctx(transfer("bank", "public"), null);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            var biz = parseBizContent(bodyCaptor.getValue());
            var payee = mapOf(biz, "payee_info");
            var bankcard = mapOf(payee, "bankcard_ext_info");
            assertThat(bankcard).containsEntry("account_type", "1");
        }
    }

    // =========================================================================
    // 场景报备（可留空；scene_name 非空才报）
    // =========================================================================

    @Test
    @DisplayName("场景报备：scene_name 非空 → transfer_scene_name + report_infos（| 拆分，content 缺省回退第一个）")
    void sceneReportBuildsWhenConfigured() throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("transfer_alipay_scene_name", "转账汇款");
        cfg.put("transfer_alipay_info_type", "t1|t2");
        cfg.put("transfer_alipay_info_content", "c1");
        var ctx = ctx(transfer("alipay", null), cfg);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz).containsEntry("transfer_scene_name", "转账汇款");
            var infos = (java.util.List<?>) biz.get("transfer_scene_report_infos");
            assertThat(infos).hasSize(2);
            assertThat(infos.get(0)).isEqualTo(Map.of("info_type", "t1", "info_content", "c1"));
            // 第 2 个 info_type 缺 content → 回退第一个
            assertThat(infos.get(1)).isEqualTo(Map.of("info_type", "t2", "info_content", "c1"));
        }
    }

    @Test
    @DisplayName("场景报备：银行卡转账同样报备")
    void sceneReportAppliesToBankcard() throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("transfer_alipay_scene_name", "转账汇款");
        cfg.put("transfer_alipay_info_type", "t1");
        cfg.put("transfer_alipay_info_content", "c1");
        var ctx = ctx(transfer("bank", null), cfg);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz).containsEntry("transfer_scene_name", "转账汇款");
        }
    }

    @Test
    @DisplayName("场景报备：scene_name 留空 → 不发任何场景字段")
    void sceneReportSkippedWhenBlank() throws Exception {
        var ctx = ctx(transfer("alipay", null), null);

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_uni_transfer_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));

            new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());

            var biz = parseBizContent(bodyCaptor.getValue());
            assertThat(biz).doesNotContainKey("transfer_scene_name");
            assertThat(biz).doesNotContainKey("transfer_scene_report_infos");
        }
    }

    // =========================================================================
    // 转账查询：product_code 按转账类型分支
    // =========================================================================

    @Test
    @DisplayName("转账查询：银行卡 → product_code=TRANS_BANKCARD_NO_PWD")
    void queryTransferBankcardProductCode() throws Exception {
        var ctx = ctx(transfer("bank", null), null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_common_query_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"status\":\"SUCCESS\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));
            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());
            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(parseBizContent(bodyCaptor.getValue()))
                    .containsEntry("product_code", "TRANS_BANKCARD_NO_PWD");
        }
    }

    @Test
    @DisplayName("转账查询：账户 → product_code=TRANS_ACCOUNT_NO_PWD（默认）")
    void queryTransferAccountProductCode() throws Exception {
        var ctx = ctx(transfer("alipay", null), null);
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_fund_trans_common_query_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"status\":\"SUCCESS\",\"order_id\":\"O20260818\"}"),
                            "req", 10, 1));
            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_XFER).bizNo(XFER_NO).build());
            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(parseBizContent(bodyCaptor.getValue()))
                    .containsEntry("product_code", "TRANS_ACCOUNT_NO_PWD");
        }
    }
}
