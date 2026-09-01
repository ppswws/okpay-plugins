package com.okpay.plugin.alipay.handler;

import com.okpay.plugin.model.CallbackResult;
import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投诉响应 → ComplaintRecord 映射（交易投诉 tradecomplain 体系）：complaintId 取
 * complain_event_id，trade_amount 元转分、gmt_* 时间解析、状态/协商角色按官方枚举映射。
 * 回调解析：msg_method 网关分流 + biz_content（complain_event_id）。
 */
@DisplayName("AlipayComplainCore 投诉记录映射与回调解析")
class AlipayComplainCoreTest {

    private static final String APP_ID = "2021000000000001";
    private static KeyPair KP;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
    }

    private final AlipayComplainCore core = new AlipayComplainCore();

    @Test
    @DisplayName("complaintId 取 complain_event_id；trade_amount 元转分、时间/金额/交易信息映射")
    void toRecordUsesComplainEventId() throws Exception {
        var node = HttpHelper.MAPPER.readTree("""
                {"complain_event_id": "2026082810002001234567890",
                 "status": "MERCHANT_PROCESSING",
                 "trade_no": "2026082822001400001234567890",
                 "merchant_order_no": "T202608281200000000001",
                 "gmt_create": "2026-08-28 10:00:00",
                 "gmt_modified": "2026-08-28 10:30:00",
                 "leaf_category_name": "商品问题",
                 "complain_reason": "未收到货",
                 "content": "订单已付款但一直未发货",
                 "phone_no": "13800138000",
                 "trade_amount": "100.00",
                 "images": ["https://o.alicdn.com/x.jpg"]}
                """);

        var rec = core.toRecord(node);

        assertThat(rec.getComplaintId()).isEqualTo("2026082810002001234567890");
        assertThat(rec.getRawState()).isEqualTo("MERCHANT_PROCESSING");
        assertThat(rec.getState()).isEqualTo(0);
        assertThat(rec.getApiTradeNo()).isEqualTo("2026082822001400001234567890");
        assertThat(rec.getTradeNo()).isEqualTo("T202608281200000000001");
        assertThat(rec.getOutTradeNo()).isEqualTo("T202608281200000000001");
        assertThat(rec.getComplaintType()).isEqualTo("商品问题");
        assertThat(rec.getTitle()).isEqualTo("未收到货");
        assertThat(rec.getContent()).isEqualTo("订单已付款但一直未发货");
        assertThat(rec.getPhone()).isEqualTo("13800138000");
        assertThat(rec.getAmount()).isEqualTo(10000L);
        assertThat(rec.getOccurredAt().toString()).isEqualTo("2026-08-28T10:00");
        assertThat(rec.getImages()).containsExactly("https://o.alicdn.com/x.jpg");
    }

    @Test
    @DisplayName("状态映射（tradecomplain 官方枚举）：待处理/处理超时 → 待办，已反馈/客服处理中 → 平台介入，完结/撤诉/关闭/举报成功 → 终态")
    void toRecordStateMapping() throws Exception {
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"MERCHANT_PROCESSING\"}")).getState()).isEqualTo(0);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"MERCHANT_FEEDBACK_TIMEOUT\"}")).getState()).isEqualTo(0);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"MERCHANT_FEEDBACKED\"}")).getState()).isEqualTo(1);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"PLATFORM_PROCESSING\"}")).getState()).isEqualTo(1);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"FINISHED\"}")).getState()).isEqualTo(2);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"CANCELLED\"}")).getState()).isEqualTo(2);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"PLATFORM_FINISH\"}")).getState()).isEqualTo(2);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"CLOSED\"}")).getState()).isEqualTo(2);
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"REPORT_SUCCEED\"}")).getState()).isEqualTo(2);
    }

    @Test
    @DisplayName("被诉方映射：target_type=PID 时 merchantNo 取 target_id，其余类型（APPID/PUBLICID）不映射")
    void toRecordMerchantNoFromTarget() throws Exception {
        var pid = core.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complain_event_id\":\"E1\",\"target_id\":\"2088123456789012\",\"target_type\":\"PID\"}"));
        assertThat(pid.getMerchantNo()).isEqualTo("2088123456789012");

        var appid = core.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complain_event_id\":\"E1\",\"target_id\":\"2018001307627807\",\"target_type\":\"APPID\"}"));
        assertThat(appid.getMerchantNo()).isNull();

        var noTarget = core.toRecord(HttpHelper.MAPPER.readTree("{\"complain_event_id\":\"E1\"}"));
        assertThat(noTarget.getMerchantNo()).isNull();
    }

    @Test
    @DisplayName("协商记录 reply_detail_infos：replier_role USER→用户/MERCHANT→商户/其余→系统，附图片与时间")
    void toRecordMessages() throws Exception {
        var node = HttpHelper.MAPPER.readTree("""
                {"complain_event_id": "E1",
                 "reply_detail_infos": [
                   {"replier_role": "USER", "replier_name": "张三", "content": "没收到货",
                    "gmt_create": "2026-08-28 11:00:00", "images": ["https://o.alicdn.com/a.jpg"]},
                   {"replier_role": "MERCHANT", "content": "已补发", "gmt_create": "2026-08-28 12:00:00"},
                   {"replier_role": "SYSTEM", "content": "平台已介入", "gmt_create": "2026-08-28 13:00:00"}]}
                """);

        var msgs = core.toRecord(node).getMessages();

        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).getRole()).isEqualTo(1);
        assertThat(msgs.get(0).getName()).isEqualTo("用户");
        assertThat(msgs.get(0).getContent()).isEqualTo("没收到货");
        assertThat(msgs.get(0).getCreatedAt().toString()).isEqualTo("2026-08-28T11:00");
        assertThat(msgs.get(0).getImages()).containsExactly("https://o.alicdn.com/a.jpg");
        assertThat(msgs.get(1).getRole()).isEqualTo(2);
        assertThat(msgs.get(1).getName()).isEqualTo("商户");
        assertThat(msgs.get(2).getRole()).isEqualTo(4);
        assertThat(msgs.get(2).getName()).isEqualTo("系统");
    }

    @Test
    @DisplayName("无 reply_detail_infos 时 messages 恒空")
    void toRecordMessagesMissing() throws Exception {
        var rec = core.toRecord(HttpHelper.MAPPER.readTree("{\"complain_event_id\": \"E1\"}"));
        assertThat(rec.getMessages()).isEmpty();
    }

    // =========================================================================
    // 回调解析：交易投诉通知（msg_method 网关分流 + biz_content 提取）
    // =========================================================================

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    /** 投诉通道配置：支付宝公钥=测试公钥（通知验签走真实公钥） */
    private ChannelConfig cfg() {
        return ChannelConfig.builder()
                .pluginId("alipay").appId(APP_ID)
                .appKey(pem("PUBLIC KEY", KP.getPublic().getEncoded()))
                .appSecret(pem("PRIVATE KEY", KP.getPrivate().getEncoded()))
                .extras(Map.of("is_prod", "true"))
                .build();
    }

    /** 构造 urlencoded 通知体：RSA2 签名走真实私钥（验签按官方规则剔除 sign/sign_type） */
    private static byte[] signedForm(LinkedHashMap<String, String> params) throws Exception {
        params.put("sign", RsaKeys.sign(
                AlipayOpenApiClient.signContentForVerify(params), KP.getPrivate()));
        params.put("sign_type", "RSA2");
        var sb = new StringBuilder();
        for (var e : params.entrySet())
            sb.append(e.getKey()).append("=")
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append("&");
        return sb.substring(0, sb.length() - 1).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("交易投诉通知：biz_content 提取 complain_event_id，验签通过 ack success")
    void parseCallbackTradeComplainNotify() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("msg_method", "alipay.merchant.tradecomplain.changed");
        params.put("biz_content", "{\"complain_event_id\":\"2026082810002001234567890\"}");
        var result = core.parseCallback(cfg(), Map.of(),
                signedForm(params));

        assertThat(result.getComplaintId()).isEqualTo("2026082810002001234567890");
        assertThat(result.getAckStatus()).isEqualTo(200);
        assertThat(result.getAckContent()).isEqualTo("success");
    }

    @Test
    @DisplayName("网关分流：非交易投诉通知的 msg_method → 不处理（complaintId 空）但 ack success")
    void parseCallbackOtherMsgMethodAcked() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("msg_method", "alipay.open.app.api.status.changed");
        params.put("biz_content", "{}");
        CallbackResult result = core.parseCallback(cfg(), Map.of(),
                signedForm(params));

        assertThat(result.getComplaintId()).isNull();
        assertThat(result.getAckStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("签名不符 → ack fail（ackStatus 500，不吐投诉单号，等上游重发）")
    void parseCallbackBadSignAckedFail() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("msg_method", "alipay.merchant.tradecomplain.changed");
        params.put("biz_content", "{\"complain_event_id\":\"E1\"}");
        var body = new String(signedForm(params), StandardCharsets.UTF_8)
                .replace("complain_event_id%22%3A%22E1", "complain_event_id%22%3A%22E2");
        var result = core.parseCallback(cfg(), Map.of(), body.getBytes(StandardCharsets.UTF_8));

        assertThat(result.getAckStatus()).isEqualTo(500);
        assertThat(result.getAckContent()).isEqualTo("fail");
        assertThat(result.getComplaintId()).isNull();
    }

}
