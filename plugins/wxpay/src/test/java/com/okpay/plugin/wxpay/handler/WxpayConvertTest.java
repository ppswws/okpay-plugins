package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.sdk.HttpHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WxpayConvert 投诉响应映射")
class WxpayConvertTest {

    @Test
    @DisplayName("完整投诉 JSON 全字段映射")
    void fullMapping() throws Exception {
        var json = """
                {
                  "complaint_id": "20020182022010108000000000001",
                  "complained_mchid": "1900012181",
                  "complaint_state": "PROCESSING",
                  "problem_type": "欺诈",
                  "problem_description": "商品描述不符",
                  "complaint_detail": "详情文字",
                  "payer_phone": "13800000000",
                  "payer_openid": "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o",
                  "apply_refund_amount": 100,
                  "complaint_time": "2022-01-01T08:00:00+08:00",
                  "complaint_order_info": [
                    {"transaction_id": "4200000000000000000000001", "out_trade_no": "T20220101001"}
                  ],
                  "complaint_media_list": [
                    {"media_type": "image", "media_url": ["https://api.mch.weixin.qq.com/v3/merchant-service/images/a1", "https://api.mch.weixin.qq.com/v3/merchant-service/images/a2"]}
                  ]
                }
                """;
        var record = WxpayConvert.toRecord(HttpHelper.MAPPER.readTree(json));

        assertThat(record.getComplaintId()).isEqualTo("20020182022010108000000000001");
        assertThat(record.getMerchantNo()).isEqualTo("1900012181");
        assertThat(record.getTradeNo()).isEqualTo("T20220101001");
        assertThat(record.getOutTradeNo()).isEqualTo("T20220101001");
        assertThat(record.getState()).isEqualTo(1);
        assertThat(record.getRawState()).isEqualTo("PROCESSING");
        assertThat(record.getComplaintType()).isEqualTo("欺诈");
        assertThat(record.getTitle()).isEqualTo("商品描述不符");
        assertThat(record.getContent()).isEqualTo("详情文字");
        assertThat(record.getPhone()).isEqualTo("13800000000");
        assertThat(record.getBuyerId()).isEqualTo("oUpF8uMuAJO_M2pxb1Q9zNjWeS6o");
        assertThat(record.getAmount()).isEqualTo(100L);
        assertThat(record.getOccurredAt()).isEqualTo(java.time.LocalDateTime.of(2022, 1, 1, 8, 0, 0));
        assertThat(record.getImages()).containsExactly(
                "https://api.mch.weixin.qq.com/v3/merchant-service/images/a1",
                "https://api.mch.weixin.qq.com/v3/merchant-service/images/a2");
    }

    @Test
    @DisplayName("状态映射：PENDING→0、PROCESSING→1、其他/缺失→2/0")
    void stateMapping() throws Exception {
        assertThat(state("{\"complaint_state\":\"PENDING\"}")).isEqualTo(0);
        assertThat(state("{\"complaint_state\":\"PROCESSING\"}")).isEqualTo(1);
        assertThat(state("{\"complaint_state\":\"RESOLVED\"}")).isEqualTo(2);
        assertThat(state("{}")).isEqualTo(0);
    }

    @Test
    @DisplayName("缺字段容错：空 order_info/媒体列表/时间为 null 不抛异常")
    void missingFields() throws Exception {
        var record = WxpayConvert.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complaint_id\":\"C1\",\"complaint_state\":\"PENDING\"}"));
        assertThat(record.getComplaintId()).isEqualTo("C1");
        assertThat(record.getTradeNo()).isEmpty();
        assertThat(record.getAmount()).isNull();
        assertThat(record.getOccurredAt()).isNull();
        assertThat(record.getImages()).isEmpty();

        // 非法时间 → null
        var badTime = WxpayConvert.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complaint_id\":\"C2\",\"complaint_time\":\"not-a-time\"}"));
        assertThat(badTime.getOccurredAt()).isNull();
    }

    @Test
    @DisplayName("media_url 多数组展平")
    void mediaFlattening() throws Exception {
        var record = WxpayConvert.toRecord(HttpHelper.MAPPER.readTree("""
                {"complaint_media_list":[
                  {"media_url":["u1"]},
                  {"media_url":["u2","u3"]},
                  {"media_type":"video"}
                ]}
                """));
        assertThat(record.getImages()).isEqualTo(List.of("u1", "u2", "u3"));
    }

    @Test
    @DisplayName("协商历史 /negotiation-historys data[] → messages（operate_type 定角色，内容/时间/图片映射）")
    void historyMessages() throws Exception {
        var historys = HttpHelper.MAPPER.readTree("""
                [
                  {"log_id":"L1","operator":"oUser1","operate_time":"2022-01-01T09:00:00+08:00",
                   "operate_type":"USER_CREATE_COMPLAINT","operate_details":"请退款",
                   "image_list":["img1"],
                   "complaint_media_list":[{"media_type":"USER_COMPLAINT_IMAGE","media_url":["m1","m2"]}]},
                  {"log_id":"L2","operator":"商户A","operate_time":"2022-01-01T10:00:00+08:00",
                   "operate_type":"MERCHANT_RESPONSE",
                   "normal_message":{"blocks":[{"type":"TEXT","text":{"text":"已处理"}}]}},
                  {"log_id":"L3","operator":"平台","operate_time":"2022-01-01T11:00:00+08:00",
                   "operate_type":"PLATFORM_RESPONSE"},
                  {"log_id":"L4","operator":"sys","operate_time":"2022-01-01T12:00:00+08:00",
                   "operate_type":"COMPLAINT_FULL_REFUNDED_SYSTEM_MESSAGE"}
                ]
                """);

        var msgs = WxpayConvert.toMessages(historys);
        assertThat(msgs).hasSize(4);
        // 用户提交投诉：operate_details 优先，图片=image_list + complaint_media_list.media_url 合并
        assertThat(msgs.get(0).getRole()).isEqualTo(1);
        assertThat(msgs.get(0).getName()).isEqualTo("oUser1");
        assertThat(msgs.get(0).getContent()).isEqualTo("请退款");
        assertThat(msgs.get(0).getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2022, 1, 1, 9, 0, 0));
        assertThat(msgs.get(0).getImages()).containsExactly("img1", "m1", "m2");
        // 商户留言：operate_details 缺失 → normal_message TEXT 块拼接
        assertThat(msgs.get(1).getRole()).isEqualTo(2);
        assertThat(msgs.get(1).getName()).isEqualTo("商户A");
        assertThat(msgs.get(1).getContent()).isEqualTo("已处理");
        // 平台留言：无内容 → 回退 operate_type
        assertThat(msgs.get(2).getRole()).isEqualTo(3);
        assertThat(msgs.get(2).getContent()).isEqualTo("PLATFORM_RESPONSE");
        // 系统消息（*_SYSTEM_MESSAGE 后缀）→ 系统角色
        assertThat(msgs.get(3).getRole()).isEqualTo(4);
        assertThat(msgs.get(3).getName()).isEqualTo("sys");
    }

    @Test
    @DisplayName("列表接口无 query_historys 时 messages 恒空")
    void historyMissing() throws Exception {
        var record = WxpayConvert.toRecord(HttpHelper.MAPPER.readTree(
                "{\"complaint_id\":\"C1\"}"));
        assertThat(record.getMessages()).isEmpty();
    }

    @Test
    @DisplayName("extractMediaId 从完整 media_url 提取末段")
    void extractMediaId() {
        assertThat(WxpayConvert.extractMediaId(
                "https://api.mch.weixin.qq.com/v3/merchant-service/images/a1b2")).isEqualTo("a1b2");
        assertThat(WxpayConvert.extractMediaId("a1b2")).isEqualTo("a1b2");
        assertThat(WxpayConvert.extractMediaId(null)).isNull();
    }

    @Test
    @DisplayName("extractMediaId 剥离 query/# 后缀；结尾斜杠或末段为空返回 null")
    void extractMediaIdStripsQueryFragment() {
        assertThat(WxpayConvert.extractMediaId(
                "https://api.mch.weixin.qq.com/v3/images/a1b2?token=x&t=1")).isEqualTo("a1b2");
        assertThat(WxpayConvert.extractMediaId(
                "https://api.mch.weixin.qq.com/v3/images/a1b2#frag")).isEqualTo("a1b2");
        assertThat(WxpayConvert.extractMediaId("https://api.mch.weixin.qq.com/v3/images/")).isNull();
        assertThat(WxpayConvert.extractMediaId("https://api.mch.weixin.qq.com/v3/images")).isEqualTo("images");
    }

    private int state(String json) throws Exception {
        return WxpayConvert.toRecord(HttpHelper.MAPPER.readTree(json)).getState();
    }
}
