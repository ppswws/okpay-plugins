package com.okpay.plugin.wxpay.handler;

import tools.jackson.databind.JsonNode;
import com.okpay.plugin.model.ComplaintRecord;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.okpay.plugin.sdk.ExpectedFailureException;
/**
 * 投诉响应 JSON → ComplaintRecord 映射（字段名以微信 v3 投诉接口文档为准）。
 */
final class WxpayConvert {

    private WxpayConvert() {}

    static ComplaintRecord toRecord(JsonNode item) {
        var orderInfo = item.path("complaint_order_info");
        var outTradeNo = extractOutTradeNo(orderInfo);
        var orderAmount = extractOrderAmount(orderInfo);
        var refundAmount = item.path("apply_refund_amount").isNumber()
                ? item.path("apply_refund_amount").asLong() : null;
        return ComplaintRecord.builder()
                .complaintId(item.path("complaint_id").asString(null))
                .merchantNo(item.path("complainted_mchid").asString(null))
                .tradeNo(outTradeNo)
                .outTradeNo(outTradeNo)
                .state(mapState(item.path("complaint_state").asString(null)))
                .rawState(item.path("complaint_state").asString(null))
                .complaintType(item.path("problem_type").asString(null))
                .title(item.path("problem_description").asString(null))
                .content(item.path("complaint_detail").asString(null))
                .phone(item.path("payer_phone").asString(null))
                .buyerId(item.path("payer_openid").asString(null))
                .incomingUserResponse(item.path("incoming_user_response").asBoolean(false))
                // 金额语义为交易金额：优先订单金额，缺失才回退申请退款金额
                .amount(orderAmount != null ? orderAmount : refundAmount)
                .occurredAt(parseTime(item.path("complaint_time").asString(null)))
                .images(extractImages(item))
                .messages(extractMessages(item))
                .build();
    }

    /** 恒为空：详情接口已无 query_historys；协商历史由 WxpayComplainCore 调独立接口 /negotiation-historys 后经 {@link #toMessages} 组装 */
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
                    .content(historyContent(h))
                    .createdAt(parseTime(h.path("operate_time").asString(null)))
                    .images(historyImages(h))
                    .build());
        }
        return list;
    }

    /** 协商内容优先级：operate_details → normal_message 文本块拼接 → click_message；都没有则不合成文案 */
    private static String historyContent(JsonNode h) {
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
        return click == null || click.isBlank() ? null : click;
    }

    /** 协商历史图片：只取官方推荐的 complaint_media_list.media_url（image_list 已废弃，不读）。 */
    private static List<String> historyImages(JsonNode h) {
        var media = h.path("complaint_media_list");
        return media.isObject() ? new ArrayList<>(textList(media.path("media_url"))) : List.of();
    }

    /** 角色按操作类型前缀分类：*（系统消息后缀）→ 系统；USER_* → 用户；MERCHANT_* → 商户；PLATFORM_* → 平台 */
    private static int mapHistoryRole(String operateType) {
        if (operateType == null) return ComplaintRecord.MessageRecord.ROLE_SYSTEM;
        if (operateType.endsWith("_SYSTEM_MESSAGE")) return ComplaintRecord.MessageRecord.ROLE_SYSTEM;
        if (operateType.startsWith("USER")) return ComplaintRecord.MessageRecord.ROLE_USER;
        if (operateType.startsWith("MERCHANT")) return ComplaintRecord.MessageRecord.ROLE_MERCHANT;
        if (operateType.startsWith("PLATFORM")) return ComplaintRecord.MessageRecord.ROLE_PLATFORM;
        return ComplaintRecord.MessageRecord.ROLE_SYSTEM;
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
     * 从图片 URL（{@code .../v3/merchant-service/images/{media_id}}）提取末段 media_id。
     * 先剥离上游可能追加的 {@code ?}/{@code #} 后缀，末段为空返回 null。
     */
    static String extractMediaId(String mediaUrl) {
        if (mediaUrl == null) return null;
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

    /** 微信支持 jpg/jpeg/png/bmp；仅校验后缀，保留原文件名（.jpeg 合法，不重写）。 */
    static String validateMediaFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ExpectedFailureException("图片文件名不能为空");
        }
        var dot = filename.lastIndexOf('.');
        var ext = dot > 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!Set.of("jpg", "jpeg", "png", "bmp").contains(ext)) {
            throw new ExpectedFailureException("仅支持 jpg/jpeg/png/bmp 图片");
        }
        return filename;
    }

    /** 上游 URL 末段可能已 URL 编码（如 base64 padding %3D%3D），下载前先还原一次再编码。 */
    static String decodeMediaId(String mediaId) {
        if (mediaId == null) return null;
        try {
            // URLDecoder 是 form 语义，会把字面量 + 变成空格；base64 的 + 必须先保护
            return java.net.URLDecoder.decode(mediaId.replace("+", "%2B"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return mediaId;
        }
    }

    private static String extractOutTradeNo(JsonNode orderInfo) {
        if (orderInfo != null && orderInfo.isArray() && !orderInfo.isEmpty()) {
            var outTradeNo = orderInfo.get(0).path("out_trade_no").asString(null);
            return outTradeNo != null ? outTradeNo : "";
        }
        return "";
    }

    /** 投诉关联订单的订单金额（分） */
    private static Long extractOrderAmount(JsonNode orderInfo) {
        if (orderInfo != null && orderInfo.isArray() && !orderInfo.isEmpty()) {
            var amount = orderInfo.get(0).path("amount");
            if (amount.isNumber()) return amount.asLong();
        }
        return null;
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
            return null;
        }
    }

    /** 投诉详情图片：complaint_media_list 为 array[object] */
    private static List<String> extractImages(JsonNode item) {
        var images = new ArrayList<String>();
        var mediaList = item.path("complaint_media_list");
        if (!mediaList.isArray()) return images;
        for (var media : mediaList) images.addAll(textList(media.path("media_url")));
        return images;
    }
}
