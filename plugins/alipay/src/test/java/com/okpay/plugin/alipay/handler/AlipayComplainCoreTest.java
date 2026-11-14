package com.okpay.plugin.alipay.handler;

import com.okpay.plugin.model.CallbackResult;
import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.model.ComplaintFinishParams;
import com.okpay.plugin.model.ComplaintMediaRef;
import com.okpay.plugin.model.ComplaintRecord;
import com.okpay.plugin.model.ComplaintRef;
import com.okpay.plugin.model.ComplaintSource;
import com.okpay.plugin.model.ReplyParams;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

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

    private static ComplaintRef tradeRef(String id) {
        return new ComplaintRef(ComplaintSource.ALIPAY_TRADE, id);
    }

    private static ComplaintRef riskRef(String id) {
        return new ComplaintRef(ComplaintSource.ALIPAY_RISK, id);
    }

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
                 "trade_amount": "100.00"}
                """);

        var rec = core.toRecord(node);

        assertThat(rec.getSource()).isEqualTo(ComplaintSource.ALIPAY_TRADE);
        assertThat(rec.getComplaintId()).isEqualTo("2026082810002001234567890");
        // 未检测到平台信号时返回 null：由宿主保留详情已写入的升级状态
        assertThat(rec.getEscalated()).isNull();
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
        // 未知官方状态按处理中保留，不擅自判终态
        assertThat(core.toRecord(HttpHelper.MAPPER.readTree("{\"status\":\"SOME_NEW_STATUS\"}")).getState()).isEqualTo(1);
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
    @DisplayName("协商记录 reply_detail_infos：USER/MERCHANT/SYSTEM→角色，优先 replier_name，审核小二/政府单位保留名称")
    void toRecordMessages() throws Exception {
        var node = HttpHelper.MAPPER.readTree("""
                {"complain_event_id": "E1",
                 "reply_detail_infos": [
                   {"replier_role": "USER", "replier_name": "张三", "content": "没收到货",
                    "gmt_create": "2026-08-28 11:00:00", "images": ["https://o.alicdn.com/a.jpg"]},
                   {"replier_role": "MERCHANT", "content": "已补发", "gmt_create": "2026-08-28 12:00:00"},
                   {"replier_role": "SYSTEM", "content": "平台已介入", "gmt_create": "2026-08-28 13:00:00"},
                   {"replier_role": "AUDITOR", "replier_name": "小二甲", "content": "审核处理",
                    "gmt_create": "2026-08-28 14:00:00"},
                   {"replier_role": "GOVERNMENT", "content": "政府单位介入", "gmt_create": "2026-08-28 15:00:00"}]}
                """);

        var msgs = core.toRecord(node).getMessages();

        assertThat(msgs).hasSize(5);
        assertThat(msgs.get(0).getRole()).isEqualTo(1);
        assertThat(msgs.get(0).getName()).isEqualTo("张三");
        assertThat(msgs.get(0).getContent()).isEqualTo("没收到货");
        assertThat(msgs.get(0).getCreatedAt().toString()).isEqualTo("2026-08-28T11:00");
        assertThat(msgs.get(0).getImages()).containsExactly("https://o.alicdn.com/a.jpg");
        assertThat(msgs.get(1).getRole()).isEqualTo(2);
        assertThat(msgs.get(1).getName()).isEqualTo("商户");
        assertThat(msgs.get(2).getRole()).isEqualTo(4);
        assertThat(msgs.get(2).getName()).isEqualTo("系统");
        assertThat(msgs.get(3).getRole()).isEqualTo(4);
        assertThat(msgs.get(3).getName()).isEqualTo("小二甲（审核小二）");
        assertThat(msgs.get(4).getRole()).isEqualTo(4);
        assertThat(msgs.get(4).getName()).isEqualTo("政府单位");
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

        assertThat(result.getSource()).isEqualTo(ComplaintSource.ALIPAY_TRADE);
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

    // =========================================================================
    // 图片上传
    // =========================================================================

    @Test
    @DisplayName("uploadMedia：失败透出支付宝 sub_msg，不再返回 null")
    void uploadMediaSurfacesUpstreamFailure() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(byte[].class), anyString(), anyMap()))
                    .thenReturn(jsonResponse("""
                            {"alipay_merchant_image_upload_response":
                              {"code":"40004","msg":"Business Failed","sub_code":"isv.image-format-error","sub_msg":"图片格式不支持"}}
                            """));

            assertThatThrownBy(() -> core.uploadMedia(cfg(), tradeRef("E1"), new byte[]{1, 2}, "a.jpg"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("图片格式不支持");
        }
    }

    @Test
    @DisplayName("uploadMedia：成功但缺 image_id 明确失败")
    void uploadMediaRequiresImageId() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(byte[].class), anyString(), anyMap()))
                    .thenReturn(jsonResponse("""
                            {"alipay_merchant_image_upload_response":{"code":"10000","msg":"Success"}}
                            """));

            assertThatThrownBy(() -> core.uploadMedia(cfg(), tradeRef("E1"), new byte[]{1, 2}, "a.jpg"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未返回 image_id");
        }
    }

    @Test
    @DisplayName("uploadMedia：成功返回 image_id，文件字段名为 image_content")
    void uploadMediaReturnsImageId() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            var bodyCaptor = ArgumentCaptor.forClass(byte[].class);
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), bodyCaptor.capture(), anyString(), anyMap()))
                    .thenReturn(jsonResponse("""
                            {"alipay_merchant_image_upload_response":{"code":"10000","msg":"Success","image_id":"IMG-1"}}
                            """));

            assertThat(core.uploadMedia(cfg(), tradeRef("E1"), new byte[]{1, 2}, "a.jpg"))
                    .isEqualTo(ComplaintMediaRef.ofId("IMG-1"));
            var multipart = new String(bodyCaptor.getValue(), StandardCharsets.ISO_8859_1);
            assertThat(multipart)
                    .contains("name=\"image_content\"")
                    .doesNotContain("name=\"file_content\"");
        }
    }

    // =========================================================================
    // RiskGO（小程序/代扣投诉）
    // =========================================================================

    @Test
    @DisplayName("RiskGO 映射：source=alipay_risk + 原始主键，交易信息/金额/时间/状态映射")
    void toRiskRecordMapsFields() throws Exception {
        var node = HttpHelper.MAPPER.readTree("""
                {"id":"31304792","status":"PROCESSING",
                 "complain_content":"商品存在瑕疵，要求退款","contact":"13288888888",
                 "complain_amount":"0.01","gmt_complain":"2026-11-13 10:00:00",
                 "gmt_overdue":"2026-11-15 10:00:00","opposite_pid":"2088001000000000",
                 "process_message":"已联系用户，协商一致","process_remark":"已退款",
                 "process_img_url_list":["https://example.com/proof.jpg"],
                 "gmt_process":"2026-11-13 12:00:00",
                 "complaint_trade_info_list":[
                   {"trade_no":"2026111322001400001234567890","out_no":"T202611131200000000001",
                    "amount":"0.01","status":"OVERDUE"}]}
                """);

        var rec = core.toRiskRecord(node);

        assertThat(rec.getSource()).isEqualTo(ComplaintSource.ALIPAY_RISK);
        assertThat(rec.getComplaintId()).isEqualTo("31304792");
        // 列表未带回标签/升级字段时保持 null，宿主保留已有值
        assertThat(rec.getEscalated()).isNull();
        assertThat(rec.getLabels()).isNull();
        assertThat(rec.getRawState()).isEqualTo("PROCESSING");
        assertThat(rec.getState()).isEqualTo(1);
        // RiskGO 是 API 家族，不等于“小程序投诉”：有交易信息即按交易投诉展示
        assertThat(rec.getTitle()).isEqualTo("交易投诉");
        assertThat(rec.getComplaintType()).isEqualTo("交易投诉");
        assertThat(rec.getContent()).isEqualTo("商品存在瑕疵，要求退款");
        assertThat(rec.getTradeNo()).isEqualTo("T202611131200000000001");
        assertThat(rec.getApiTradeNo()).isEqualTo("2026111322001400001234567890");
        assertThat(rec.getAmount()).isEqualTo(1L);
        assertThat(rec.getPhone()).isEqualTo("13288888888");
        assertThat(rec.getOccurredAt().toString()).isEqualTo("2026-11-13T10:00");
        assertThat(rec.getDeadlineAt().toString()).isEqualTo("2026-11-15T10:00");
        assertThat(rec.getMerchantNo()).isEqualTo("2088001000000000");
        // 无协商历史接口：首条为用户投诉内容，process_* 作为商家处理记录
        assertThat(rec.getMessages()).hasSize(2);
        assertThat(rec.getMessages().get(0).getRole()).isEqualTo(1);
        assertThat(rec.getMessages().get(0).getContent()).isEqualTo("商品存在瑕疵，要求退款");
        assertThat(rec.getMessages().get(0).getCreatedAt().toString()).isEqualTo("2026-11-13T10:00");
        assertThat(rec.getMessages().get(1).getRole()).isEqualTo(2);
        assertThat(rec.getMessages().get(1).getName()).isEqualTo("商家处理记录");
        assertThat(rec.getMessages().get(1).getContent()).isEqualTo("已联系用户，协商一致；已退款");
        assertThat(rec.getMessages().get(1).getImages()).containsExactly("https://example.com/proof.jpg");
        assertThat(rec.getMessages().get(1).getCreatedAt().toString()).isEqualTo("2026-11-13T12:00");
    }

    @Test
    @DisplayName("RiskGO 非交易类投诉：无交易信息/交易号时按消费者投诉展示")
    void toRiskRecordNonTradeTitle() throws Exception {
        var rec = core.toRiskRecord(HttpHelper.MAPPER.readTree(
                "{\"id\":\"1\",\"status\":\"PROCESSING\",\"complain_content\":\"服务问题\"}"));

        assertThat(rec.getTitle()).isEqualTo("消费者投诉");
        assertThat(rec.getComplaintType()).isEqualTo("消费者投诉");
    }

    @Test
    @DisplayName("RiskGO 升级投诉：upgrade_content/label_tag_list/high_risk_tag → 归一化字段")
    void toRiskRecordEscalation() throws Exception {
        var node = HttpHelper.MAPPER.readTree("""
                {"id":"1","status":"PROCESSING","complain_content":"要求退款",
                 "upgrade_content":"用户升级投诉","gmt_upgrade":"2026-11-14 09:00:00",
                 "label_tag_list":["升级投诉","加急"],"high_risk_tag":true}
                """);

        var rec = core.toRiskRecord(node);

        assertThat(rec.getEscalated()).isTrue();
        assertThat(rec.getEscalationContent()).isEqualTo("用户升级投诉");
        assertThat(rec.getEscalatedAt().toString()).isEqualTo("2026-11-14T09:00");
        assertThat(rec.getLabels()).containsExactly("升级投诉", "加急", "高风险");
        // 升级标记不改变状态：官方 PROCESSING 仍映射为处理中
        assertThat(rec.getState()).isEqualTo(1);
    }

    @Test
    @DisplayName("交易投诉升级信号：平台处理中 / 审核小二回复 → escalated + 平台介入")
    void toRecordEscalation() throws Exception {
        var platform = core.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complain_event_id\":\"E1\",\"status\":\"PLATFORM_PROCESSING\"}"));
        assertThat(platform.getEscalated()).isTrue();
        assertThat(platform.getLabels()).containsExactly("平台介入");
        // 升级标记不改变状态：PLATFORM_PROCESSING 仍按官方状态映射为处理中
        assertThat(platform.getState()).isEqualTo(1);

        var auditor = core.toRecord(HttpHelper.MAPPER.readTree("""
                {"complain_event_id":"E2","status":"MERCHANT_PROCESSING",
                 "reply_detail_infos":[{"replier_role":"AUDITOR","content":"已介入"}]}
                """));
        assertThat(auditor.getEscalated()).isTrue();
        assertThat(auditor.getLabels()).containsExactly("平台介入");
        // 审核小二介入也不改变官方 MERCHANT_PROCESSING → 待处理
        assertThat(auditor.getState()).isEqualTo(0);
    }

    @Test
    @DisplayName("RiskGO 图片上传：file.upload + file_content，返回 url/key")
    void uploadRiskMediaReturnsFileRef() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            var bodyCaptor = ArgumentCaptor.forClass(byte[].class);
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), bodyCaptor.capture(), anyString(), anyMap()))
                    .thenReturn(jsonResponse("""
                            {"alipay_security_risk_complaint_file_upload_response":
                              {"code":"10000","msg":"Success","file_name":"a.jpg",
                               "file_key":"KEY-1","file_url":"https://x/a.jpg"}}
                            """));

            var media = core.uploadMedia(cfg(), riskRef("31304792"),
                    new byte[]{1, 2}, "a.jpg");

            assertThat(media).isEqualTo(ComplaintMediaRef.ofFile("https://x/a.jpg", "KEY-1"));
            var multipart = new String(bodyCaptor.getValue(), StandardCharsets.ISO_8859_1);
            assertThat(multipart)
                    .contains("name=\"file_content\"")
                    .doesNotContain("name=\"image_content\"");
        }
    }

    @Test
    @DisplayName("RiskGO 完成带图片：img_file_list 使用 url+key")
    void finishRiskComplaintWithImages() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_security_risk_complaint_process_finish_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"complaint_process_success\":true}");
                    });

            core.finish(cfg(), ComplaintFinishParams.builder()
                    .ref(riskRef("31304792"))
                    .resultCode("REFUND").content("已退款")
                    .images(List.of(ComplaintMediaRef.ofFile("https://x/a.jpg", "KEY-1")))
                    .build());
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("\"img_file_list\":[")
                .contains("\"img_url\":\"https://x/a.jpg\"")
                .contains("\"img_url_key\":\"KEY-1\"");
    }

    @Test
    @DisplayName("交易投诉完成：feedback.submit + feedback_code/content")
    void finishTradeComplaint() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_merchant_tradecomplain_feedback_submit_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\"}");
                    });

            core.finish(cfg(), ComplaintFinishParams.builder()
                    .ref(tradeRef("2026082810002001234567890"))
                    .resultCode("02").content("已退款").build());
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("method=alipay.merchant.tradecomplain.feedback.submit")
                .contains("\"feedback_code\":\"02\"")
                .contains("\"feedback_content\":\"已退款\"")
                .contains("\"complain_event_id\":\"2026082810002001234567890\"");
    }

    @Test
    @DisplayName("RiskGO 完成：process.finish + id_list/process_code/reply_content，并校验 complaint_process_success")
    void finishRiskComplaint() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_security_risk_complaint_process_finish_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"complaint_process_success\":true}");
                    });

            core.finish(cfg(), ComplaintFinishParams.builder()
                    .ref(riskRef("31304792"))
                    .resultCode("REFUND").content("已退款").build());
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("method=alipay.security.risk.complaint.process.finish")
                .contains("\"id_list\":[31304792]")
                .contains("\"process_code\":\"REFUND\"")
                .contains("\"reply_content\":\"已退款\"");
    }

    @Test
    @DisplayName("RiskGO 完成：complaint_process_success=false 明确失败")
    void finishRiskComplaintRejected() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> signedJson("alipay_security_risk_complaint_process_finish_response",
                            "{\"code\":\"10000\",\"msg\":\"Success\",\"complaint_process_success\":false}"));

            assertThatThrownBy(() -> core.finish(cfg(), ComplaintFinishParams.builder()
                    .ref(riskRef("31304792"))
                    .resultCode("REFUND").content("已退款").build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未确认成功");
        }
    }

    @Test
    @DisplayName("交易投诉补充凭证：supplement.submit + supplement_content")
    void supplementTradeComplaint() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_merchant_tradecomplain_supplement_submit_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\"}");
                    });

            core.supplementSubmit(cfg(), tradeRef("2026082810002001234567890"),
                    "补充交易凭证", List.of());
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("method=alipay.merchant.tradecomplain.supplement.submit")
                .contains("\"supplement_content\":\"补充交易凭证\"")
                .contains("\"complain_event_id\":\"2026082810002001234567890\"");
    }

    @Test
    @DisplayName("RiskGO 回调：source=alipay_risk + 原始 complaint_id，USER_REPLY 携带留言")
    void parseCallbackRiskNotify() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("msg_method", "alipay.security.risk.complaints.merchants.notify");
        params.put("notify_id", "N-1");
        params.put("biz_content",
                "{\"complaint_id\":\"31304792\",\"message_type\":\"USER_REPLY\","
                        + "\"reply_content\":\"要求退款\",\"reply_time\":\"2026-11-13 15:00:00\"}");

        var result = core.parseCallback(cfg(), Map.of(), signedForm(params));

        assertThat(result.getSource()).isEqualTo(ComplaintSource.ALIPAY_RISK);
        assertThat(result.getComplaintId()).isEqualTo("31304792");
        assertThat(result.getNotifyId()).isEqualTo("N-1");
        assertThat(result.getActionType()).isEqualTo("USER_REPLY");
        assertThat(result.getMessage().getContent()).isEqualTo("要求退款");
        assertThat(result.getMessage().getCreatedAt().toString()).isEqualTo("2026-11-13T15:00");
        assertThat(result.getAckStatus()).isEqualTo(200);
        assertThat(result.getAckContent()).isEqualTo("success");
    }

    @Test
    @DisplayName("同步合并：交易投诉 + RiskGO 同时拉取，RiskGO 记录带前缀")
    void syncListMergesTradeAndRisk() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        var body = inv.getArgument(2).toString();
                        if (body.contains("method=alipay.security.risk.complaint.info.batchquery")) {
                            return signedJson("alipay_security_risk_complaint_info_batchquery_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"total_size\":1,\"complaint_list\":["
                                            + "{\"id\":\"31304792\",\"status\":\"PROCESSING\","
                                            + "\"complain_content\":\"商品有瑕疵\",\"complain_amount\":\"0.01\","
                                            + "\"contact\":\"13800138000\",\"gmt_complain\":\"2026-11-13 10:00:00\","
                                            + "\"opposite_pid\":\"2088123456789012\","
                                            + "\"complaint_trade_info_list\":[{\"trade_no\":\"2026111322001400001234567890\","
                                            + "\"out_no\":\"T202611131200000000001\"}]}]}");
                        }
                        return signedJson("alipay_merchant_tradecomplain_batchquery_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"total_num\":1,"
                                        + "\"trade_complain_infos\":[{\"complain_event_id\":\"2026111310002001234567890\","
                                        + "\"status\":\"MERCHANT_PROCESSING\",\"merchant_order_no\":\"T202611131200000000002\"}]}");
                    });

            var result = core.syncList(cfg(), LocalDate.of(2026, 11, 13), 50);

            assertThat(result.getComplaints()).extracting(ComplaintRecord::getComplaintId)
                    .containsExactlyInAnyOrder("2026111310002001234567890", "31304792");
            var risk = result.getComplaints().stream()
                    .filter(c -> ComplaintSource.ALIPAY_RISK.equals(c.getSource()))
                    .findFirst().orElseThrow();
            assertThat(risk.getTradeNo()).isEqualTo("T202611131200000000001");
            assertThat(risk.getState()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("同步业务错误不再静默为 0 条：交易投诉 40004 → 同步抛错并透出 sub_msg")
    void syncListSurfacesTradeBusinessError() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        var body = inv.getArgument(2).toString();
                        if (body.contains("method=alipay.security.risk.complaint.info.batchquery")) {
                            return signedJson("alipay_security_risk_complaint_info_batchquery_response",
                                    "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                            + "\"sub_code\":\"isv.insufficient-permission\",\"sub_msg\":\"无权限\"}");
                        }
                        return signedJson("alipay_merchant_tradecomplain_batchquery_response",
                                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                        + "\"sub_code\":\"ACQ.ACCESS_FORBIDDEN\",\"sub_msg\":\"无权限使用接口\"}");
                    });

            assertThatThrownBy(() -> core.syncList(cfg(), LocalDate.of(2026, 11, 13), 50))
                    .hasMessageContaining("支付宝投诉列表同步失败")
                    .hasRootCauseMessage("支付宝交易投诉同步失败: 无权限使用接口");
        }
    }

    @Test
    @DisplayName("RiskGO 回复：按前缀路由到 risk.complaint.reply.send，字段为 record_id")
    void replyRiskRoutesToRiskApi() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_security_risk_complaint_reply_send_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\"}");
                    });

            core.reply(cfg(), ReplyParams.builder()
                    .ref(riskRef("31304792")).content("已联系用户，协商一致").build());
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("method=alipay.security.risk.complaint.reply.send")
                .contains("\"record_id\":\"31304792\"")
                .contains("\"reply_content\":\"已联系用户，协商一致\"")
                .doesNotContain("complain_event_id");
    }

    @Test
    @DisplayName("RiskGO 详情：source=alipay_risk 路由到 risk.complaint.info.query，请求体用 record_id")
    void syncDetailRoutesRisk() {
        var bodyRef = new AtomicReference<String>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        bodyRef.set(inv.getArgument(2).toString());
                        return signedJson("alipay_security_risk_complaint_info_query_response",
                                "{\"code\":\"10000\",\"msg\":\"Success\",\"id\":\"31304792\","
                                        + "\"status\":\"PROCESSING\",\"complain_content\":\"要求退款\","
                                        + "\"complain_amount\":\"0.01\"}");
                    });

            var rec = core.syncDetail(cfg(), riskRef("31304792"));

            assertThat(rec.getSource()).isEqualTo(ComplaintSource.ALIPAY_RISK);
            assertThat(rec.getComplaintId()).isEqualTo("31304792");
            assertThat(rec.getRawState()).isEqualTo("PROCESSING");
            assertThat(rec.getContent()).isEqualTo("要求退款");
        }

        var decoded = URLDecoder.decode(bodyRef.get(), StandardCharsets.UTF_8);
        assertThat(decoded)
                .contains("method=alipay.security.risk.complaint.info.query")
                .contains("\"record_id\":\"31304792\"")
                .doesNotContain("complain_event_id");
    }

    @Test
    @DisplayName("RiskGO 图片回复明确拒绝，不静默丢图")
    void replyRiskRejectsImages() {
        assertThatThrownBy(() -> core.reply(cfg(), ReplyParams.builder()
                .ref(riskRef("31304792")).content("已联系用户，协商一致")
                .images(List.of(ComplaintMediaRef.ofId("IMG-1"))).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂不支持图片回复");
    }

    @Test
    @DisplayName("同步容错：交易投诉失败但 RiskGO 成功 → 返回 RiskGO 数据，不整段失败")
    void syncListKeepsRiskWhenTradeFails() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpHelper.post(any(), anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        var body = inv.getArgument(2).toString();
                        if (body.contains("method=alipay.security.risk.complaint.info.batchquery")) {
                            return signedJson("alipay_security_risk_complaint_info_batchquery_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"total_size\":1,\"complaint_list\":["
                                            + "{\"id\":\"31304792\",\"status\":\"PROCESSING\","
                                            + "\"complain_content\":\"要求退款\"}]}");
                        }
                        return signedJson("alipay_merchant_tradecomplain_batchquery_response",
                                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                        + "\"sub_code\":\"ACQ.ACCESS_FORBIDDEN\",\"sub_msg\":\"无权限使用接口\"}");
                    });

            var result = core.syncList(cfg(), LocalDate.of(2026, 11, 13), 50);

            assertThat(result.getComplaints()).extracting(ComplaintRecord::getComplaintId)
                    .containsExactly("31304792");
        }
    }

    /** 构造带响应签名的支付宝 JSON 响应（响应验签走真实公钥） */
    private static HttpHelper.HttpResponse signedJson(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        var body = ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return new HttpHelper.HttpResponse(200, Map.of(), body, "req", 10L, 1);
    }

    private static HttpHelper.HttpResponse jsonResponse(String json) {
        return new HttpHelper.HttpResponse(200, Map.of(), json.getBytes(StandardCharsets.UTF_8), "", 1L, 1);
    }

}
