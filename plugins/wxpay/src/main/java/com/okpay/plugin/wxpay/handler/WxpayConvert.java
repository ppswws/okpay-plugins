package com.okpay.plugin.wxpay.handler;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.okpay.plugin.model.ComplaintRecord;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 投诉响应 JSON → ComplaintRecord 映射（字段名以微信 v3 投诉接口文档为准）。
 */
final class WxpayConvert {
    private static final Logger log = LoggerFactory.getLogger(WxpayConvert.class);

    private WxpayConvert() {}

    static ComplaintRecord toRecord(JsonNode item) {
        return ComplaintRecord.builder()
                .complaintId(item.path("complaint_id").asString(null))
                .merchantNo(item.path("complained_mchid").asString(null))
                .tradeNo(extractOutTradeNo(item))
                .outTradeNo(extractOutTradeNo(item))
                .state(mapState(item.path("complaint_state").asString(null)))
                .rawState(item.path("complaint_state").asString(null))
                .complaintType(item.path("problem_type").asString(null))
                .title(item.path("problem_description").asString(null))
                .content(item.path("complaint_detail").asString(null))
                .phone(item.path("payer_phone").asString(null))
                .buyerId(item.path("payer_openid").asString(null))
                .amount(item.path("apply_refund_amount").isNumber()
                        ? item.path("apply_refund_amount").asLong() : null)
                .occurredAt(parseTime(item.path("complaint_time").asString(null)))
                .images(extractImages(item))
                .messages(extractMessages(item))
                .build();
    }

    /** 协商历史：query_historys 字段已从投诉详情接口移除，恒为空列表；协商历史由
     *  WxpayComplainCore 调独立接口 /negotiation-historys 后经 {@link #toMessages} 组装 */
    private static List<ComplaintRecord.MessageRecord> extractMessages(JsonNode item) {
        return List.of();
    }

    /**
     * 协商历史（GET /v3/merchant-service/complaints-v2/{id}/negotiation-historys 应答 data[]）→ 消息列表。
     * 内容优先级与图片凭证来源按官方字段：operate_details / normal_message 文本块 / click_message；
     * 图片取 image_list 与 complaint_media_list.media_url（均为媒体 URL，前端经下载代理展示）。
     */
    static List<ComplaintRecord.MessageRecord> toMessages(JsonNode historys) {
        if (historys == null || !historys.isArray()) return List.of();
        var list = new ArrayList<ComplaintRecord.MessageRecord>();
        for (var h : historys) {
            var operateType = h.path("operate_type").asString("");
            var operator = h.path("operator").asString(null);
            list.add(ComplaintRecord.MessageRecord.builder()
                    .role(mapHistoryRole(operateType))
                    .name(operator != null && !operator.isBlank() ? operator : historyRoleName(operateType))
                    .content(historyContent(h, operateType))
                    .createdAt(parseTime(h.path("operate_time").asString(null)))
                    .images(historyImages(h))
                    .build());
        }
        return list;
    }

    /** 协商内容优先级：operate_details → normal_message 文本块拼接 → click_message → operate_type */
    private static String historyContent(JsonNode h, String operateType) {
        var details = h.path("operate_details").asString(null);
        if (details != null && !details.isBlank()) return details;
        var blocks = h.path("normal_message").path("blocks");
        if (blocks.isArray()) {
            var sb = new StringBuilder();
            for (var block : blocks) {
                if ("TEXT".equals(block.path("type").asString(""))) {
                    var text = block.path("text").path("text").asString("");
                    if (!text.isBlank()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(text);
                    }
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        var click = h.path("click_message").path("message_content").asString(null);
        if (click != null && !click.isBlank()) return click;
        return operateType.isBlank() ? null : operateType;
    }

    /** 图片凭证：image_list（即将废弃）+ complaint_media_list[].media_url 合并 */
    private static List<String> historyImages(JsonNode h) {
        var images = new ArrayList<String>(textList(h.path("image_list")));
        var mediaList = h.path("complaint_media_list");
        if (mediaList.isArray()) {
            for (var media : mediaList) {
                images.addAll(textList(media.path("media_url")));
            }
        }
        return images;
    }

    /** 角色按操作类型前缀分类：*（系统消息后缀）→ 系统；USER_* → 用户；MERCHANT_* → 商户；PLATFORM_* → 平台 */
    private static int mapHistoryRole(String operateType) {
        if (operateType == null) return 4;
        if (operateType.endsWith("_SYSTEM_MESSAGE")) return 4;
        if (operateType.startsWith("USER")) return 1;
        if (operateType.startsWith("MERCHANT")) return 2;
        if (operateType.startsWith("PLATFORM")) return 3;
        return 4;
    }

    private static String historyRoleName(String operateType) {
        if (operateType == null) return "系统";
        if (operateType.endsWith("_SYSTEM_MESSAGE")) return "系统";
        if (operateType.startsWith("USER")) return "用户";
        if (operateType.startsWith("MERCHANT")) return "商户";
        if (operateType.startsWith("PLATFORM")) return "平台";
        return "系统";
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var list = new ArrayList<String>();
        for (var v : node) list.add(v.asString());
        return list;
    }

    /**
     * 从图片 URL 提取 media_id（URL 末段）：如
     * {@code https://api.mch.weixin.qq.com/v3/merchant-service/images/{media_id}}。
     * 先剥离 {@code ?}/{@code #} 后缀（上游可能返回带签名 query 的变体），末段为空返回 null。
     */
    static String extractMediaId(String mediaUrl) {
        if (mediaUrl == null) return null;
        // 只剥离 query/fragment 后缀，末尾段取最后一个 '/' 之后（media_id 本身不含 '/'）
        int cut = mediaUrl.length();
        for (char c : new char[]{'?', '#'}) {
            int i = mediaUrl.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        var tail = mediaUrl.substring(0, cut);
        var idx = tail.lastIndexOf('/');
        var id = idx >= 0 ? tail.substring(idx + 1) : tail;
        return id.isBlank() ? null : id;
    }

    private static String extractOutTradeNo(JsonNode item) {
        var orderInfo = item.path("complaint_order_info");
        if (orderInfo != null && orderInfo.isArray() && !orderInfo.isEmpty()) {
            var outTradeNo = orderInfo.get(0).path("out_trade_no").asString(null);
            return outTradeNo != null ? outTradeNo : "";
        }
        return "";
    }

    private static int mapState(String state) {
        if (state == null) return 0;
        return switch (state) { case "PENDING" -> 0; case "PROCESSING" -> 1; default -> 2; };
    }

    private static LocalDateTime parseTime(String time) {
        if (time == null) return null;
        try {
            return LocalDateTime.parse(time.replace("T", " ").substring(0, 19),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            log.warn("投诉时间解析失败, time={}", time, e);
            return null;
        }
    }

    private static List<String> extractImages(JsonNode item) {
        var images = new ArrayList<String>();
        var mediaList = item.path("complaint_media_list");
        if (mediaList != null && mediaList.isArray()) {
            for (var media : mediaList) {
                var urls = media.path("media_url");
                if (urls != null && urls.isArray()) {
                    for (var url : urls) images.add(url.asString());
                }
            }
        }
        return images;
    }
}
