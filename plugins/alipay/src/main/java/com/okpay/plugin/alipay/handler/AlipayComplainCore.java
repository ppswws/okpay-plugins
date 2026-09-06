package com.okpay.plugin.alipay.handler;

import tools.jackson.databind.JsonNode;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import org.slf4j.*;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 支付宝投诉处理核心逻辑 — 零第三方 SDK，走 AlipayOpenApiClient。
 *
 * <p>对接 <b>交易投诉（tradecomplain）体系</b>：{@code alipay.merchant.tradecomplain.*}
 * 六接口 + {@code alipay.merchant.image.upload}，与通知 {@code alipay.merchant.tradecomplain.changed}
 * （官方文档已存档 {@code llms文档/支付宝/投诉处理/}）。</p>
 *
 * <p>负责：构建客户端 → 调用支付宝 API → 转换为标准化的 {@link ComplaintRecord}。
 * 外部调用失败向宿主抛出，由宿主统一转换和记录，避免“返回空结果但接口成功”。</p>
 */
public class AlipayComplainCore {

    private static final Logger log = LoggerFactory.getLogger(AlipayComplainCore.class);

    private static final DateTimeFormatter ALI_DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // =========================================================================
    // 同步
    // =========================================================================

    /**
     * 从支付宝批量同步投诉列表（分页拉取，直到没有更多或达到 limit）。
     * batchquery 的 {@code page_size} 上限 20。
     */
    public SyncListResult syncList(ChannelConfig cfg, LocalDate since, int limit) {
        var client = buildClient(cfg);
        int pageSize = Math.min(limit, 20);
        var all = new ArrayList<ComplaintRecord>();
        try {
            for (int page = 1; ; page++) {
                var biz = new LinkedHashMap<String, Object>();
                biz.put("page_num", (long) page);
                biz.put("page_size", (long) pageSize);
                // begin_time 格式 yyyy-MM-dd HH:mm:ss；end_time 不传（官方默认当前时间，
                // 显式传当天 00:00:00 会漏掉当天产生的投诉）；时间跨度最大一年
                biz.put("begin_time", since.toString() + " 00:00:00");

                var resp = client.execute(null, "alipay.merchant.tradecomplain.batchquery",
                        biz, authParams(cfg));
                var list = resp.ok() ? resp.node("trade_complain_infos") : null;
                if (list == null || !list.isArray() || list.isEmpty())
                    break;

                for (var item : list)
                    all.add(toRecord(item));

                if (list.size() < pageSize || all.size() >= limit)
                    break;
            }
        } catch (Exception e) {
            throw new IllegalStateException("支付宝投诉列表同步失败", e);
        }
        return SyncListResult.builder().complaints(all).hasMore(all.size() >= limit).build();
    }

    /**
     * 按支付宝侧投诉单号查询/刷新单条投诉详情（query 的 complain_event_id 参数）。
     */
    public ComplaintRecord syncDetail(ChannelConfig cfg, String complaintId) {
        try {
            var biz = new LinkedHashMap<String, Object>();
            biz.put("complain_event_id", complaintId);

            var resp = buildClient(cfg).execute(null,
                    "alipay.merchant.tradecomplain.query", biz, authParams(cfg));
            return resp.ok() ? toRecord(resp.root()) : null;
        } catch (Exception e) {
            throw new IllegalStateException("支付宝投诉详情查询失败, complaintId=" + complaintId, e);
        }
    }

    // =========================================================================
    // 回调
    // =========================================================================

    /**
     * 解析支付宝应用网关推送的交易投诉通知（{@code alipay.merchant.tradecomplain.changed}），
     * 验签后从 biz_content 消息报文中提取投诉单号 complain_event_id，随后由宿主查详情刷新。
     */
    public CallbackResult parseCallback(ChannelConfig cfg, Map<String, String> headers, byte[] body) {
        try {
            var params = PaymentUtils.parseForm(
                    new String(body, StandardCharsets.UTF_8));
            var ok = buildClient(cfg).verifyNotify(params);

            // 应用网关共用地址会收到其他 msg_method 的消息，非本产品消息不做处理
            var msgMethod = params.get("msg_method");
            if (!"alipay.merchant.tradecomplain.changed".equals(msgMethod)) {
                log.debug("支付宝投诉通知消息类型不匹配, msgMethod={}", msgMethod);
                return CallbackResult.builder().ackStatus(ok ? 200 : 500)
                        .ackContent(ok ? "success" : "fail")
                        .contentType("text/plain").build();
            }

            // 验签失败不给出投诉单号（防伪造），仅 ack fail 等上游重发
            if (!ok) {
                return CallbackResult.builder().ackStatus(500).ackContent("fail")
                        .contentType("text/plain").build();
            }

            // biz_content 为消息报文 JSON：complain_event_id（支付宝侧投诉单号）
            var biz = HttpHelper.MAPPER.readTree(
                    params.getOrDefault("biz_content", "{}"));

            return CallbackResult.builder()
                    .complaintId(biz.path("complain_event_id").asString(null))
                    .ackStatus(200)
                    .ackContent("success")
                    .contentType("text/plain")
                    .build();
        } catch (Exception e) {
            log.error("投诉回调解析失败", e);
            return CallbackResult.builder().ackStatus(500).ackContent("fail")
                    .contentType("text/plain").build();
        }
    }

    // =========================================================================
    // 媒体文件
    // =========================================================================

    /**
     * 上传图片到支付宝（{@code alipay.merchant.image.upload}：image_type + image_content），
     * 返回 image_id（回复/反馈/补充凭证的 images 参数均消费该 id 列表）。
     */
    public String uploadMedia(ChannelConfig cfg, byte[] fileData, String filename) {
        try {
            var biz = new LinkedHashMap<String, Object>();
            biz.put("image_type", ext(filename));
            var resp = buildClient(cfg).executeMultipart(null,
                    "alipay.merchant.image.upload", biz,
                    filename, "image/" + ext(filename).replace("jpg", "jpeg"),
                    fileData, authParams(cfg));
            return resp.ok() ? resp.text("image_id") : null;
        } catch (Exception e) {
            throw new IllegalStateException("上传图片失败, filename=" + filename, e);
        }
    }

    /**
     * 从支付宝下载图片（投诉详情里的 images 为可直接访问的图片 URL）。
     * 来源可信不做域名白名单强校验；非 200 视为失败抛异常（不静默返回空）。
     */
    public byte[] downloadMedia(ChannelConfig cfg, String mediaUrl) {
        try {
            var resp = HttpHelper.get(null, mediaUrl);
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("下载图片失败, status=" + resp.statusCode());
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下载图片失败", e);
        }
    }

    // =========================================================================
    // 操作
    // =========================================================================

    /**
     * 商家留言回复（{@code alipay.merchant.tradecomplain.reply.submit}）：
     * reply_content + reply_images（{@code uploadMedia} 返回的 image_id，逗号分隔）。
     */
    public void reply(ChannelConfig cfg, ReplyParams params) {
        try {
            var biz = new LinkedHashMap<String, Object>();
            biz.put("complain_event_id", params.getComplaintId());
            biz.put("reply_content", params.getContent());
            var images = joinImageIds(params.getImages());
            if (images != null) biz.put("reply_images", images);

            var resp = buildClient(cfg).execute(null,
                    "alipay.merchant.tradecomplain.reply.submit", biz, authParams(cfg));
            if (!resp.ok()) throw new IllegalStateException("回复投诉失败: " + resp.subMsg());
        } catch (Exception e) {
            throw new IllegalStateException("回复投诉异常, complaintId=" + params.getComplaintId(), e);
        }
    }

    /**
     * 更新退款审批进度 — 交易投诉体系无此 API（微信专有），留空。
     */
    public void refundProgress(ChannelConfig cfg, String complaintId,
                               RefundDecision decision, String reason, List<String> images) {
        log.debug("支付宝无退款审批接口，complaintId={}", complaintId);
    }

    /**
     * 标记投诉处理完成 — 老体系的「商家处理投诉」走 {@code feedback.submit}，需要反馈类目码
     * （feedback_code：02 已退款/03 已发货/05 已完成售后/06 非我方责任/04 其他）+ 处理说明，
     * 宿主 complete 链路未携带这些参数，故不做调用；投诉完结由运营在处理时经 reply/feedback 达成。
     */
    public void complete(ChannelConfig cfg, String complaintId, String merchantNo) {
        log.debug("支付宝投诉完结需反馈类目码（feedback.submit），宿主 complete 链路未携带, complaintId={}", complaintId);
    }

    /**
     * 商家补充凭证（{@code alipay.merchant.tradecomplain.supplement.submit}）：
     * supplement_content + supplement_images（image_id 逗号分隔）。
     */
    public void supplementSubmit(ChannelConfig cfg, String complaintId,
                                 String content, List<String> images) {
        try {
            var biz = new LinkedHashMap<String, Object>();
            biz.put("complain_event_id", complaintId);
            biz.put("supplement_content", content);
            var imageIds = joinImageIds(images);
            if (imageIds != null) biz.put("supplement_images", imageIds);

            var resp = buildClient(cfg).execute(null,
                    "alipay.merchant.tradecomplain.supplement.submit", biz, authParams(cfg));
            if (!resp.ok()) throw new IllegalStateException("补充凭证失败: " + resp.subMsg());
        } catch (Exception e) {
            throw new IllegalStateException("补充凭证异常, complaintId=" + complaintId, e);
        }
    }

    // =========================================================================
    // 通知地址管理
    // =========================================================================

    /**
     * 设置投诉回调通知 URL。
     * 支付宝无 SDK 接口，需前往开放平台控制台（应用网关）手动配置。
     */
    public void setNotifyUrl(ChannelConfig cfg, String url) {
        log.debug("支付宝投诉通知地址需在开放平台控制台手动配置");
    }

    /**
     * 删除投诉回调通知 URL。
     * 支付宝无 SDK 接口，需前往开放平台控制台手动操作。
     */
    public void deleteNotifyUrl(ChannelConfig cfg, String url) {
        log.debug("支付宝投诉通知地址需在开放平台控制台手动删除");
    }

    // =========================================================================
    // 内部 — 客户端构建
    // =========================================================================

    /**
     * 子商户授权令牌注入：投诉按应用归属，auth_token 留空 = 应用身份，处理名下所有
     * （已授权）子商户的投诉；填写 = 只处理该授权子商户（第三方代理调用）。配置来自
     * 投诉应用表单的 auth_token 字段（extras 原样透传）。
     */
    private Map<String, String> authParams(ChannelConfig cfg) {
        var token = cfg.getExtras().get("auth_token");
        return token != null && !token.isBlank()
                ? Map.of("app_auth_token", token)
                : Map.of();
    }

    /**
     * 根据 ChannelConfig 构建 AlipayOpenApiClient。
     * extras 中的 is_prod 控制是否使用正式环境；认证凭据维度与支付插件一致：
     * 三证书（app_cert/alipay_cert/root_cert）齐全 → 证书模式（请求带 SN、验签取支付宝证书），
     * 否则公钥/私钥模式（appkey/appsecret）。证书模式仍需应用私钥（appsecret）签名。
     */
    private AlipayOpenApiClient buildClient(ChannelConfig cfg) {
        var extras = cfg.getExtras();
        var prod = "true".equals(extras.getOrDefault("is_prod", "true"));

        var gateway = prod
                ? "https://openapi.alipay.com/gateway.do"
                : "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

        try {
            return new AlipayOpenApiClient(gateway, cfg.getAppId(),
                    cfg.getAppSecret(), cfg.getAppKey(),
                    extras.get("app_cert"), extras.get("alipay_cert"), extras.get("root_cert"));
        } catch (IOException e) {
            var cert = notBlank(extras.get("app_cert")) && notBlank(extras.get("alipay_cert"))
                    && notBlank(extras.get("root_cert"));
            throw new IllegalStateException("支付宝投诉客户端初始化失败（请检查 "
                    + (cert ? "appsecret/app_cert/alipay_cert/root_cert 证书格式" : "appsecret/appkey 密钥格式")
                    + "）", e);
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    // =========================================================================
    // 内部 — 响应 → ComplaintRecord 映射
    // =========================================================================

    /**
     * 批量查询/单条查询响应 → ComplaintRecord（query 与 batchquery.trade_complain_infos[]
     * 元素字段一致，统一按 JSON 路径映射）。
     *
     * <p>状态映射（官方枚举）：MERCHANT_PROCESSING → 待办；
     * MERCHANT_FEEDBACKED / PLATFORM_PROCESSING → 平台介入中；MERCHANT_FEEDBACK_TIMEOUT
     * （商家处理超时，官方 reply/feedback 仍允许商户处理）→ 待办；
     * FINISHED / CANCELLED / PLATFORM_FINISH / CLOSED / REPORT_SUCCEED → 终态。</p>
     */
    ComplaintRecord toRecord(JsonNode item) {
        var status = item != null ? item.path("status").asString(null) : null;
        var state = status != null ? switch (status) {
            case "MERCHANT_PROCESSING", "MERCHANT_FEEDBACK_TIMEOUT" -> 0;
            case "MERCHANT_FEEDBACKED", "PLATFORM_PROCESSING" -> 1;
            default -> 2;   // FINISHED / CANCELLED / PLATFORM_FINISH / CLOSED / REPORT_SUCCEED
        } : 0;

        // 投诉对象（官方 target_id/target_type）：target_type=PID 时 target_id 即被诉商家 PID
        var targetType = item != null ? item.path("target_type").asString(null) : null;
        var merchantNo = "PID".equals(targetType)
                ? item.path("target_id").asString(null) : null;

        return ComplaintRecord.builder()
                .complaintId(item != null ? item.path("complain_event_id").asString(null) : null) // 支付宝侧投诉单号
                .merchantNo(merchantNo)                                                        // 被诉方（target_type=PID 时的商家 PID）
                .tradeNo(item != null ? item.path("merchant_order_no").asString(null) : null)     // 商家订单号 = 本地交易号
                .outTradeNo(item != null ? item.path("merchant_order_no").asString(null) : null)
                .apiTradeNo(item != null ? item.path("trade_no").asString(null) : null)           // 支付宝交易号
                .state(state)
                .rawState(status)
                .complaintType(item != null ? item.path("leaf_category_name").asString(null) : null) // 用户投诉诉求
                .title(item != null ? item.path("complain_reason").asString(null) : null)            // 投诉原因
                .content(item != null ? item.path("content").asString(null) : null)                  // 投诉内容
                .phone(item != null ? item.path("phone_no").asString(null) : null)                   // 投诉人电话
                .amount(item != null ? toFen(item.path("trade_amount").asString(null)) : null)       // 交易金额（元 → 分）
                .occurredAt(item != null ? parseTime(item.path("gmt_create").asString(null)) : null)
                .images(item != null ? textList(item.path("images")) : List.of())
                .messages(item != null ? extractMessages(item) : List.of())
                .build();
    }

    /**
     * 协商记录 reply_detail_infos → messages（replier_role 枚举：USER/MERCHANT/SYSTEM/
     * AUDITOR/GOVERNMENT，后三者归「系统」角色显示）。
     */
    private static List<ComplaintRecord.MessageRecord> extractMessages(JsonNode item) {
        var replies = item.path("reply_detail_infos");
        if (replies == null || !replies.isArray()) return List.of();
        var list = new ArrayList<ComplaintRecord.MessageRecord>();
        for (var r : replies) {
            var replyRole = r.path("replier_role").asString(null);
            var role = switch (replyRole) {
                case "USER" -> 1;       // 用户
                case "MERCHANT" -> 2;   // 商户
                default -> 4;           // SYSTEM / AUDITOR（审核小二）/ GOVERNMENT（政府单位）→ 系统
            };
            list.add(ComplaintRecord.MessageRecord.builder()
                    .role(role)
                    .name(role == 1 ? "用户" : role == 2 ? "商户" : "系统")
                    .content(r.path("content").asString(null))
                    .createdAt(parseTime(r.path("gmt_create").asString(null)))
                    .images(textList(r.path("images")))
                    .build());
        }
        return list;
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text, ALI_DTF);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        var list = new ArrayList<String>();
        for (var v : node) list.add(v.asString());
        return list;
    }

    /** 图片 id 列表 → 逗号分隔字符串（官方 images 参数语义），空列表返回 null 不带该字段 */
    private static String joinImageIds(List<String> images) {
        if (images == null || images.isEmpty()) return null;
        return String.join(",", images);
    }

    // =========================================================================
    // 内部 — 工具方法
    // =========================================================================

    /**
     * 金额字符串（元）转分（Long）。
     * 支付宝返回的金额单位是"人民币元"，需转换为分存储。
     */
    private static Long toFen(String amountYuan) {
        if (amountYuan == null || amountYuan.isBlank()) return null;
        try {
            return new BigDecimal(amountYuan.trim())
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从文件名提取小写扩展名，默认 jpg（官方 image_type 仅收 jpg/jpeg/png） */
    private static String ext(String filename) {
        var i = filename != null ? filename.lastIndexOf('.') : -1;
        return i > 0 ? filename.substring(i + 1).toLowerCase(Locale.ROOT) : "jpg";
    }
}
