package com.okpay.plugin.alipay.handler;

import tools.jackson.databind.JsonNode;
import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PaymentUtils;
import com.okpay.plugin.sdk.PluginLog;
import org.slf4j.*;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

import com.okpay.plugin.sdk.ExpectedFailureException;
/**
 * 支付宝投诉处理核心逻辑 — 零第三方 SDK，走 AlipayOpenApiClient。
 *
 * <p>同时对接两套投诉体系：</p>
 * <ul>
 *   <li><b>交易投诉</b>：{@code alipay.merchant.tradecomplain.*} + 通知
 *       {@code alipay.merchant.tradecomplain.changed}（账单详情→交易投诉入口）</li>
 *   <li><b>消费者投诉（RiskGO，可含交易/小程序/代扣等业务类型）</b>：{@code alipay.security.risk.complaint.*} + 通知
 *       {@code alipay.security.risk.complaints.merchants.notify}</li>
 * </ul>
 *
 * <p>两个体系共用一个投诉应用/应用网关（一个 APPID 只能配一个网关），
 * 投诉来源由 {@link ComplaintRecord#getSource()} / {@link ComplaintRef#source()} 标识，
 * 回调按 msg_method 分流、同步合并返回；单号本身不编码来源。</p>
 *
 * <p>负责：构建客户端 → 调用支付宝 API → 转换为标准化的 {@link ComplaintRecord}。
 * 外部调用失败向宿主抛出，由宿主统一转换和记录，避免“返回空结果但接口成功”。</p>
 */
public class AlipayComplainCore {

    private static final Logger log = LoggerFactory.getLogger(AlipayComplainCore.class);

    private static final DateTimeFormatter ALI_DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String MSG_TRADECOMPLAIN = "alipay.merchant.tradecomplain.changed";
    private static final String MSG_RISK = "alipay.security.risk.complaints.merchants.notify";

    /** 是否 RiskGO 来源（来源判定统一在此收口）。 */
    static boolean isRisk(ComplaintRef ref) {
        return ref != null && ComplaintSource.ALIPAY_RISK.equals(ref.source());
    }

    private static boolean isTrade(ComplaintRef ref) {
        return ref != null && ComplaintSource.ALIPAY_TRADE.equals(ref.source());
    }

    // =========================================================================
    // 同步
    // =========================================================================

    /**
     * 拉取一页投诉（两套体系各一页）。游标编码两套体系各自的下一页页码，格式 {@code trade|risk}；
     * 某套体系已拉完或失败时对应段留空，避免对已失败体系反复重试。
     *
     * <p>两个体系独立容错：任一体系失败只记录日志，另一体系结果照常返回；两者都失败且
     * 没有任何数据时才向宿主抛出，避免“某一体系无权限导致整个同步不可用”。</p>
     */
    public SyncListResult syncListPage(ChannelConfig cfg, LocalDate since, String cursor, int limit) {
        int pageSize = Math.max(1, Math.min(limit, ComplaintHandler.SYNC_PAGE_SIZE));
        var start = parseCursor(cursor);
        var systems = List.of(
                new ComplaintSystem("交易投诉", "alipay.merchant.tradecomplain.batchquery",
                        "trade_complain_infos", "page_num", "begin_time", "total_num",
                        false, start.tradePage(), this::toRecord),
                new ComplaintSystem("消费者投诉(RiskGO)", "alipay.security.risk.complaint.info.batchquery",
                        "complaint_list", "current_page_num", "gmt_complaint_start", "total_size",
                        true, start.riskPage(), this::toRiskRecord));

        var client = buildClient(cfg);
        var all = new ArrayList<ComplaintRecord>();
        Exception failure = null;
        int success = 0;
        int nextTrade = 0, nextRisk = 0;
        for (var system : systems) {
            if (system.page() <= 0) continue; // 该体系已拉完
            try {
                var page = syncPage(client, cfg, system, since, pageSize);
                all.addAll(page.records());
                success++;
                if (page.hasMore()) {
                    if (system.risk()) nextRisk = system.page() + 1;
                    else nextTrade = system.page() + 1;
                }
            } catch (Exception e) {
                if (failure == null) failure = e;
                log.warn("支付宝{}同步失败, appId={}, since={}, page={}, error={}",
                        system.label(), cfg.getAppId(), since, system.page(), PluginLog.summary(e));
            }
        }
        // 任一体系成功即返回（另一体系失败只记 WARN）；两个体系都失败才向宿主抛出，
        // 避免只开通单一投诉体系的存量应用因另一体系无权限而整段同步失败。
        if (failure != null && success == 0) {
            if (!PluginLog.isExpected(failure)) {
                if (failure instanceof RuntimeException re) throw re;
                throw new RuntimeException(failure);
            }
            throw new ExpectedFailureException("支付宝投诉列表同步失败", failure);
        }
        return SyncListResult.builder()
                .complaints(all).nextCursor(formatCursor(nextTrade, nextRisk)).build();
    }

    /** 一个投诉体系的分页/字段差异配置（拉取骨架两套共用）。 */
    private record ComplaintSystem(String label, String listMethod, String listField,
                                   String pageParam, String sinceParam, String totalField,
                                   boolean risk, int page,
                                   Function<JsonNode, ComplaintRecord> mapper) {}

    /** 单页拉取结果：记录 + 该体系是否还有下一页。 */
    private record PageResult(List<ComplaintRecord> records, boolean hasMore) {}

    /** 两套投诉体系共用的单页拉取骨架：业务错误透出、分页终止条件和空记录过滤只实现一次。 */
    private PageResult syncPage(AlipayOpenApiClient client, ChannelConfig cfg,
                                ComplaintSystem system, LocalDate since, int pageSize) throws Exception {
        var biz = new LinkedHashMap<String, Object>();
        biz.put(system.pageParam(), (long) system.page());
        biz.put("page_size", (long) pageSize);
        // 开始时间格式 yyyy-MM-dd HH:mm:ss；结束时间不传（官方默认当前时间，
        // 显式传当天 00:00:00 会漏掉当天产生的投诉）；时间跨度最大一年
        biz.put(system.sinceParam(), since + " 00:00:00");

        var resp = client.execute(null, system.listMethod(), biz, authParams(cfg));
        log.debug("支付宝{}同步: appId={}, since={}, page={}, pageSize={}, ok={}, code={}, subCode={}, subMsg={}, total={}",
                system.label(), cfg.getAppId(), since, system.page(), pageSize, resp.ok(), resp.code(),
                resp.subCode(), resp.subMsg(), resp.text(system.totalField()));
        if (!resp.ok())
            throw new ExpectedFailureException("支付宝" + system.label() + "同步失败: " + alipayErrMsg(resp));
        var list = resp.node(system.listField());
        if (list == null || !list.isArray() || list.isEmpty())
            return new PageResult(List.of(), false);

        var records = new ArrayList<ComplaintRecord>();
        for (var item : list) {
            var rec = system.mapper().apply(item);
            // 上游缺投诉单号的脏记录不落库（宿主以单号做唯一键）
            if (rec != null && rec.getComplaintId() != null && !rec.getComplaintId().isBlank())
                records.add(rec);
        }
        int total = parseTotal(resp.text(system.totalField()));
        boolean hasMore = list.size() == pageSize
                && (total < 0 || (long) system.page() * pageSize < total);
        return new PageResult(records, hasMore);
    }

    private record Cursor(int tradePage, int riskPage) {}

    /** 首页（cursor 空）从两套体系的第 1 页开始；空段表示该体系已拉完，非法游标视为契约破坏。 */
    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Cursor(1, 1);
        var parts = cursor.split("\\|", -1);
        if (parts.length != 2)
            throw new ExpectedFailureException("支付宝投诉同步游标无效: " + cursor);
        return new Cursor(parsePage(parts[0], cursor), parsePage(parts[1], cursor));
    }

    private static int parsePage(String value, String cursor) {
        if (value == null || value.isBlank()) return 0;
        try {
            int page = Integer.parseInt(value.trim());
            return Math.max(0, page);
        } catch (NumberFormatException e) {
            throw new ExpectedFailureException("支付宝投诉同步游标无效: " + cursor, e);
        }
    }

    /** 两套体系都无后续页返回 null；否则编码 {@code trade|risk}，已完成的一段留空。 */
    private static String formatCursor(int tradePage, int riskPage) {
        if (tradePage <= 0 && riskPage <= 0) return null;
        return (tradePage > 0 ? String.valueOf(tradePage) : "") + "|"
                + (riskPage > 0 ? String.valueOf(riskPage) : "");
    }

    private static int parseTotal(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 按投诉引用查询/刷新单条投诉详情。
     * RiskGO 来源走消费者投诉 query，交易投诉来源走 tradecomplain query。
     */
    public ComplaintRecord syncDetail(ChannelConfig cfg, ComplaintRef ref) {
        boolean risk = isRisk(ref);
        var biz = new LinkedHashMap<String, Object>();
        if (risk) {
            // 官方新接入建议使用 record_id（内容与 complain_id/complaint_id 一致）
            biz.put("record_id", ref.externalId());
        } else {
            biz.put("complain_event_id", ref.externalId());
        }
        return queryDetail(cfg, risk ? "消费者投诉(RiskGO)" : "交易投诉",
                risk ? "alipay.security.risk.complaint.info.query" : "alipay.merchant.tradecomplain.query",
                biz, ref.externalId(), risk ? this::toRiskRecord : this::toRecord);
    }

    /** 两套投诉体系共用的详情查询骨架（响应判断、日志、错误包装只实现一次）。 */
    private ComplaintRecord queryDetail(ChannelConfig cfg, String label, String method,
                                        Map<String, Object> biz, String complaintId,
                                        Function<JsonNode, ComplaintRecord> mapper) {
        try {
            var resp = buildClient(cfg).execute(null, method, biz, authParams(cfg));
            log.debug("支付宝{}详情: appId={}, complaintId={}, ok={}, code={}, subCode={}, subMsg={}",
                    label, cfg.getAppId(), complaintId, resp.ok(), resp.code(), resp.subCode(), resp.subMsg());
            return resp.ok() ? mapper.apply(resp.root()) : null;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("支付宝" + label + "详情查询失败, complaintId=" + complaintId, e);
        }
    }

    // =========================================================================
    // 回调
    // =========================================================================

    /**
     * 解析支付宝应用网关推送的投诉通知：
     * <ul>
     *   <li>{@code alipay.merchant.tradecomplain.changed} → biz_content.complain_event_id（交易投诉）</li>
     *   <li>{@code alipay.security.risk.complaints.merchants.notify} → biz_content.complaint_id
     *       （RiskGO 主表主键，携带 source=alipay_risk 交由宿主查详情）</li>
     * </ul>
     * 验签失败不给出投诉单号（防伪造），仅 ack fail 等上游重发。
     */
    public CallbackResult parseCallback(ChannelConfig cfg, Map<String, String> headers, byte[] body) {
        try {
            var params = PaymentUtils.parseForm(
                    new String(body, StandardCharsets.UTF_8));
            var ok = buildClient(cfg).verifyNotify(params);
            var msgMethod = params.get("msg_method");
            var appId = params.get("app_id");

            // 应用网关共用地址会收到其他 msg_method 的消息，非本产品消息不做处理
            if (!MSG_TRADECOMPLAIN.equals(msgMethod) && !MSG_RISK.equals(msgMethod)) {
                log.debug("支付宝投诉回调忽略: msgMethod={}, appId={}, signOk={}",
                        msgMethod, appId, ok);
                return CallbackResult.builder().ackStatus(ok ? 200 : 500)
                        .ackContent(ok ? "success" : "fail")
                        .contentType("text/plain").build();
            }

            if (!ok) {
                log.info("支付宝投诉回调验签失败: msgMethod={}, appId={}", msgMethod, appId);
                return CallbackResult.builder().ackStatus(500).ackContent("fail")
                        .contentType("text/plain").build();
            }
            if (!cfg.getAppId().equals(appId)) {
                log.warn("支付宝投诉回调 app_id 不匹配: expect={}, actual={}, msgMethod={}",
                        cfg.getAppId(), appId, msgMethod);
                return CallbackResult.builder().ackStatus(500).ackContent("fail")
                        .contentType("text/plain").build();
            }

            var biz = HttpHelper.MAPPER.readTree(
                    params.getOrDefault("biz_content", "{}"));

            if (MSG_RISK.equals(msgMethod)) {
                var id = biz.path("complaint_id").asString(null);
                var messageType = biz.path("message_type").asString("");
                log.debug("支付宝消费者投诉(RiskGO)回调: appId={}, complaintId={}, messageType={}",
                        appId, id, messageType);
                if (id == null || id.isBlank()) {
                    return CallbackResult.builder().ackStatus(500).ackContent("fail")
                            .contentType("text/plain").build();
                }
                return CallbackResult.builder()
                        .source(ComplaintSource.ALIPAY_RISK)
                        .complaintId(id)
                        .notifyId(params.get("notify_id"))
                        .actionType(messageType)
                        .message(riskNotifyMessage(messageType, biz))
                        .ackStatus(200)
                        .ackContent("success")
                        .contentType("text/plain")
                        .build();
            }

            // biz_content 为消息报文 JSON：complain_event_id（支付宝侧投诉单号）
            var id = biz.path("complain_event_id").asString(null);
            log.debug("支付宝交易投诉回调: appId={}, complaintId={}", appId, id);
            return CallbackResult.builder()
                    .source(ComplaintSource.ALIPAY_TRADE)
                    .complaintId(id)
                    .notifyId(params.get("notify_id"))
                    .ackStatus(200)
                    .ackContent("success")
                    .contentType("text/plain")
                    .build();
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                log.error("投诉回调解析失败", e);
            }
            return CallbackResult.builder().ackStatus(500).ackContent("fail")
                    .contentType("text/plain").build();
        }
    }

    // =========================================================================
    // 媒体文件
    // =========================================================================

    /**
     * 上传图片到支付宝：交易投诉走 {@code alipay.merchant.image.upload}（image_type + image_content，
     * 返回 image_id）；RiskGO 走 {@code alipay.security.risk.complaint.file.upload}
     * （file_content，返回 file_name/file_key/file_url，供 process.finish 的 img_file_list 使用）。
     */
    public ComplaintMediaRef uploadMedia(ChannelConfig cfg, ComplaintRef ref,
                                         byte[] fileData, String filename) {
        return isRisk(ref) ? uploadRiskFile(cfg, fileData, filename)
                : uploadTradeImage(cfg, fileData, filename);
    }

    private ComplaintMediaRef uploadTradeImage(ChannelConfig cfg, byte[] fileData, String filename) {
        String imageId;
        try {
            var biz = new LinkedHashMap<String, Object>();
            biz.put("image_type", ext(filename));
            var resp = buildClient(cfg).executeMultipart(null,
                    "alipay.merchant.image.upload", biz, "image_content",
                    filename, "image/" + ext(filename).replace("jpg", "jpeg"),
                    fileData, authParams(cfg));
            if (!resp.ok()) {
                // 透传支付宝 sub_msg（最具体）→ msg → sub_code → code
                throw new ExpectedFailureException("上传图片失败：" + alipayErrMsg(resp));
            }
            imageId = resp.text("image_id");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("上传图片失败, filename=" + filename, e);
        }
        if (imageId == null || imageId.isBlank()) {
            throw new ExpectedFailureException("上传图片失败：支付宝未返回 image_id");
        }
        return ComplaintMediaRef.ofId(imageId);
    }

    private ComplaintMediaRef uploadRiskFile(ChannelConfig cfg, byte[] fileData, String filename) {
        try {
            var resp = buildClient(cfg).executeMultipart(null,
                    "alipay.security.risk.complaint.file.upload", Map.of(), "file_content",
                    filename, "application/octet-stream", fileData, authParams(cfg));
            if (!resp.ok()) {
                throw new ExpectedFailureException("上传投诉凭证失败：" + alipayErrMsg(resp));
            }
            var url = resp.text("file_url");
            var key = resp.text("file_key");
            if (url == null || url.isBlank() || key == null || key.isBlank()) {
                throw new ExpectedFailureException("上传投诉凭证失败：支付宝未返回 file_url/file_key");
            }
            return ComplaintMediaRef.ofFile(url, key);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("上传投诉凭证失败, filename=" + filename, e);
        }
    }

    /** 渠道业务失败文案：sub_msg → msg → sub_code → code 逐级兜底，全缺 → 统一文案。 */
    private static String alipayErrMsg(AlipayOpenApiClient.ApiResponse resp) {
        for (var v : List.of(resp.subMsg(), resp.msg(), resp.subCode(), resp.code())) {
            if (v != null && !v.isBlank()) return v;
        }
        return "支付宝返回未知错误";
    }

    /**
     * 从支付宝下载图片（投诉详情里的 images 为可直接访问的图片 URL）。
     * 来源可信不做域名白名单强校验；非 200 视为失败抛异常（不静默返回空）。
     */
    public byte[] downloadMedia(ChannelConfig cfg, String mediaUrl) {
        try {
            var resp = HttpHelper.get(null, mediaUrl);
            if (resp.statusCode() != 200) {
                throw new ExpectedFailureException("下载图片失败, status=" + resp.statusCode());
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("下载图片失败", e);
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
        var ref = params.getRef();
        boolean risk = isRisk(ref);
        var biz = new LinkedHashMap<String, Object>();
        biz.put("reply_content", params.getContent());
        if (risk) {
            // RiskGO 回复接口无图片字段；成交投诉图片上传得到的 image_id 也不能复用。
            // 明确拒绝而不是静默丢图（前端已对 RiskGO 隐藏图片上传入口）。
            if (params.getImages() != null && !params.getImages().isEmpty())
                throw new ExpectedFailureException("支付宝消费者投诉(RiskGO)暂不支持图片回复");
            biz.put("record_id", ref.externalId());
        } else {
            biz.put("complain_event_id", ref.externalId());
            var images = joinMediaIds(params.getImages());
            if (images != null) biz.put("reply_images", images);
        }
        executeAction(cfg, risk ? "回复消费者投诉(RiskGO)" : "回复投诉",
                risk ? "alipay.security.risk.complaint.reply.send"
                        : "alipay.merchant.tradecomplain.reply.submit",
                biz, ref.externalId());
    }

    /** 两套投诉体系共用的操作骨架；业务失败原样透出文案，不再包一层“异常”。 */
    private void executeAction(ChannelConfig cfg, String label, String method,
                               Map<String, Object> biz, String complaintId) {
        try {
            var resp = buildClient(cfg).execute(null, method, biz, authParams(cfg));
            if (!resp.ok()) throw new ExpectedFailureException(label + "失败: " + alipayErrMsg(resp));
        } catch (ExpectedFailureException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException(label + "异常, complaintId=" + complaintId, e);
        }
    }

    /**
     * 更新退款审批进度 — 交易投诉体系无此 API（微信专有），留空。
     */
    public void refundProgress(ChannelConfig cfg, ComplaintRefundParams params) {
        log.debug("支付宝无退款审批接口，source={}, complaintId={}",
                params.getRef().source(), params.getRef().externalId());
    }

    /**
     * 标记投诉处理完成 — 交易投诉走 {@code feedback.submit}（feedback_code + feedback_content），
     * RiskGO 走 {@code process.finish}（id_list + process_code + reply_content + img_file_list）。
     */
    public void finish(ChannelConfig cfg, ComplaintFinishParams params) {
        if (isRisk(params.getRef())) finishRisk(cfg, params);
        else finishTrade(cfg, params);
    }

    private void finishTrade(ChannelConfig cfg, ComplaintFinishParams params) {
        if (params.getResultCode() == null || params.getResultCode().isBlank())
            throw new ExpectedFailureException("支付宝交易投诉完成需选择反馈类目");
        if (params.getContent() == null || params.getContent().isBlank())
            throw new ExpectedFailureException("支付宝交易投诉完成需填写处理说明");
        var biz = new LinkedHashMap<String, Object>();
        biz.put("complain_event_id", params.getRef().externalId());
        biz.put("feedback_code", params.getResultCode());
        biz.put("feedback_content", params.getContent());
        var images = joinMediaIds(params.getImages());
        if (images != null) biz.put("feedback_images", images);
        executeAction(cfg, "完成交易投诉", "alipay.merchant.tradecomplain.feedback.submit",
                biz, params.getRef().externalId());
    }

    private void finishRisk(ChannelConfig cfg, ComplaintFinishParams params) {
        if (params.getResultCode() == null || params.getResultCode().isBlank())
            throw new ExpectedFailureException("支付宝消费者投诉完成需选择处理结果码");
        if (params.getContent() == null || params.getContent().isBlank())
            throw new ExpectedFailureException("支付宝消费者投诉完成需填写回复内容");
        final long id;
        try {
            id = Long.parseLong(params.getRef().externalId());
        } catch (NumberFormatException e) {
            throw new ExpectedFailureException("支付宝消费者投诉单号非法: "
                    + params.getRef().externalId(), e);
        }
        var biz = new LinkedHashMap<String, Object>();
        biz.put("id_list", List.of(id));
        biz.put("process_code", params.getResultCode());
        biz.put("reply_content", params.getContent());
        if (params.getRemark() != null && !params.getRemark().isBlank())
            biz.put("remark", params.getRemark());
        var imgFiles = riskImageFiles(params.getImages());
        if (imgFiles != null) biz.put("img_file_list", imgFiles);
        try {
            var resp = buildClient(cfg).execute(null,
                    "alipay.security.risk.complaint.process.finish", biz, authParams(cfg));
            if (!resp.ok())
                throw new ExpectedFailureException("完成消费者投诉失败: " + alipayErrMsg(resp));
            if (!"true".equalsIgnoreCase(resp.text("complaint_process_success")))
                throw new ExpectedFailureException("完成消费者投诉未确认成功: " + alipayErrMsg(resp));
        } catch (ExpectedFailureException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("完成消费者投诉异常, complaintId="
                    + params.getRef().externalId(), e);
        }
    }

    /**
     * 商家补充凭证（{@code alipay.merchant.tradecomplain.supplement.submit}）：
     * supplement_content + supplement_images（image_id 逗号分隔）。
     */
    public void supplementSubmit(ChannelConfig cfg, ComplaintRef ref,
                                 String content, List<ComplaintMediaRef> images) {
        if (!isTrade(ref))
            throw new ExpectedFailureException("支付宝消费者投诉无补充凭证接口");
        var biz = new LinkedHashMap<String, Object>();
        biz.put("complain_event_id", ref.externalId());
        biz.put("supplement_content", content);
        var imageIds = joinMediaIds(images);
        if (imageIds != null) biz.put("supplement_images", imageIds);
        executeAction(cfg, "补充凭证", "alipay.merchant.tradecomplain.supplement.submit",
                biz, ref.externalId());
    }

    // =========================================================================
    // 通知地址管理
    // =========================================================================

    /**
     * 设置投诉回调通知 URL — 支付宝无此接口，需在开放平台控制台（应用网关）手动配置。
     */
    public void setNotifyUrl(ChannelConfig cfg, String url) {
        log.debug("支付宝投诉通知地址需在开放平台控制台手动配置");
    }

    /**
     * 删除投诉回调通知 URL — 支付宝无此接口，需在开放平台控制台手动操作。
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
     * 构建 AlipayOpenApiClient。
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
            throw new ExpectedFailureException("支付宝投诉客户端初始化失败（请检查 "
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
     * RiskGO 消费者投诉响应 → ComplaintRecord。
     *
     * <p>RiskGO 是 API 家族（消费者投诉），业务类型可能是交易投诉、小程序/代扣等：
     * 有交易信息/交易号按「交易投诉」展示，否则按「消费者投诉」展示。
     * 单号取主表主键 {@code id}（source=alipay_risk）：RiskGO 的详情/回复/完结
     * 接口都以主表主键为准，回调 biz_content 的 {@code complaint_id} 也是该值。</p>
     *
     * <p>状态映射：WAIT_PROCESS → 待办；PROCESSING/OVERDUE → 处理中；
     * FINISHED/PROCESSED/DROP_* /CLOSED → 终态。</p>
     */
    ComplaintRecord toRiskRecord(JsonNode item) {
        if (item == null) return null;
        var id = item.path("id").asString("");
        if (id.isBlank()) return null;

        var status = item.path("status").asString(null);
        var state = status != null ? switch (status) {
            case "WAIT_PROCESS" -> 0;
            case "PROCESSING", "OVERDUE" -> 1;
            case "FINISHED", "PROCESSED", "DROP_COMPLAIN", "DROP_OVERDUE_COMPLAIN",
                 "DROP_OVERDUE_PROCESSED", "CLOSED" -> 2;
            default -> 1;
        } : 0;

        JsonNode tradeInfo = null;
        var trades = item.path("complaint_trade_info_list");
        if (trades != null && trades.isArray() && trades.size() > 0) tradeInfo = trades.get(0);
        // 业务类型：有交易信息/交易号即为交易投诉；RiskGO 是 API 家族，不等于“小程序投诉”
        var outNo = tradeInfo != null ? tradeInfo.path("out_no").asString(null) : null;
        var apiNo = tradeInfo != null ? tradeInfo.path("trade_no").asString(null)
                : item.path("trade_no").asString(null);
        var businessType = (outNo != null && !outNo.isBlank()) || (apiNo != null && !apiNo.isBlank())
                ? "交易投诉" : "消费者投诉";

        var content = item.path("complain_content").asString(null);
        // label_tag_list 只在详情接口出现：列表未带回时保持 null，宿主按「非 null 才覆盖」保留已有标签
        var labelsNode = item.path("label_tag_list");
        var labels = labelsNode.isArray() ? new ArrayList<>(textList(labelsNode)) : null;
        var highRisk = item.path("high_risk_tag").asBoolean(false);
        if (highRisk) {
            if (labels == null) labels = new ArrayList<>();
            if (!labels.contains("高风险")) labels.add("高风险");
        }
        var upgradeContent = item.path("upgrade_content").asString(null);
        var upgradedAt = parseTime(item.path("gmt_upgrade").asString(null));
        var upgraded = (upgradeContent != null && !upgradeContent.isBlank())
                || upgradedAt != null || highRisk
                || (labels != null && (labels.contains("升级投诉") || labels.contains("高风险")));
        Boolean escalated = upgraded ? Boolean.TRUE : (labels != null ? Boolean.FALSE : null);
        return ComplaintRecord.builder()
                .source(ComplaintSource.ALIPAY_RISK)
                .complaintId(id)
                .escalated(escalated)
                .escalationContent(upgradeContent)
                .escalatedAt(upgradedAt)
                .labels(labels)
                .merchantNo(item.path("opposite_pid").asString(null))
                // 无交易信息时回退交易号：宿主按 trade_no/api_trade_no 仍可关联
                .tradeNo(outNo != null && !outNo.isBlank() ? outNo : apiNo)
                .outTradeNo(outNo != null && !outNo.isBlank() ? outNo : apiNo)
                .apiTradeNo(apiNo)
                .state(state)
                .rawState(status)
                // RiskGO 无独立标题/原因字段：标题按业务类型展示，全文入 content
                .title(businessType)
                .complaintType(businessType)
                .content(content)
                .phone(item.path("contact").asString(null))
                .amount(toFen(item.path("complain_amount").asString(null)))
                .occurredAt(parseTime(item.path("gmt_complain").asString(null)))
                .deadlineAt(parseTime(item.path("gmt_overdue").asString(null)))
                .complainUrl(item.path("complain_url").asString(null))
                .messages(riskMessages(content,
                        item.path("process_message").asString(null),
                        item.path("process_remark").asString(null),
                        textList(item.path("process_img_url_list")),
                        parseTime(item.path("gmt_complain").asString(null)),
                        parseTime(item.path("gmt_process").asString(null))))
                .build();
    }

    /**
     * RiskGO 消费者投诉没有协商历史查询接口，但有商家处理结果字段：
     * 用 complain_content 作为用户首条投诉，用 process_* 作为商家处理记录；
     * 用户后续回复只能从 complaints.merchants.notify 的 reply_content 获取（暂无本地留存）。
     */
    private static List<ComplaintRecord.MessageRecord> riskMessages(
            String content, String processMessage, String processRemark, List<String> processImages,
            LocalDateTime complaintAt, LocalDateTime processAt) {
        var messages = new ArrayList<ComplaintRecord.MessageRecord>();
        if (content != null && !content.isBlank()) {
            messages.add(ComplaintRecord.MessageRecord.builder()
                    .role(ComplaintRecord.MessageRecord.ROLE_USER)
                    .name("用户")
                    .content(content)
                    .createdAt(complaintAt)
                    .build());
        }
        var processContent = joinDistinct(processMessage, processRemark);
        if (processContent != null || (processImages != null && !processImages.isEmpty())) {
            messages.add(ComplaintRecord.MessageRecord.builder()
                    .role(ComplaintRecord.MessageRecord.ROLE_MERCHANT)
                    .name("商家处理记录")
                    .content(processContent)
                    .createdAt(processAt)
                    .images(processImages == null ? List.of() : processImages)
                    .build());
        }
        return messages;
    }

    /** 交易投诉协商记录出现审核小二/政府单位 → 视为平台介入。 */
    private static boolean hasAuditorReply(JsonNode item) {
        var replies = item != null ? item.path("reply_detail_infos") : null;
        if (replies == null || !replies.isArray()) return false;
        for (var reply : replies) {
            var role = reply.path("replier_role").asString("");
            if ("AUDITOR".equals(role) || "GOVERNMENT".equals(role)) return true;
        }
        return false;
    }

    /** 去空、去重后按中文分号拼接；全空返回 null */
    private static String joinDistinct(String a, String b) {
        var parts = new LinkedHashSet<String>();
        if (a != null && !a.isBlank()) parts.add(a.trim());
        if (b != null && !b.isBlank()) parts.add(b.trim());
        return parts.isEmpty() ? null : String.join("；", parts);
    }

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
            case "FINISHED", "CANCELLED", "PLATFORM_FINISH", "CLOSED", "REPORT_SUCCEED" -> 2;
            // 官方新增/未知状态不擅自判终态；rawState 保留原值，列表仍可见可处理
            default -> 1;
        } : 0;

        // 投诉对象（官方 target_id/target_type）：target_type=PID 时 target_id 即被诉商家 PID
        var targetType = item != null ? item.path("target_type").asString(null) : null;
        var merchantNo = "PID".equals(targetType)
                ? item.path("target_id").asString(null) : null;

        // 平台介入/小二/政府角色是交易投诉唯一的「升级」信号（官方无 upgrade 字段）；
        // batchquery 不带协商记录，未检测到平台信号时返回 null，宿主保留详情已写入的升级状态
        var platformEscalated = "PLATFORM_PROCESSING".equals(status)
                || "PLATFORM_FINISH".equals(status) || "REPORT_SUCCEED".equals(status)
                || hasAuditorReply(item);
        return ComplaintRecord.builder()
                .source(ComplaintSource.ALIPAY_TRADE)
                .complaintId(item != null ? item.path("complain_event_id").asString(null) : null) // 支付宝侧投诉单号
                .escalated(platformEscalated ? Boolean.TRUE : null)
                .labels(platformEscalated ? List.of("平台介入") : null)
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
                .messages(item != null ? extractMessages(item) : List.of())
                .build();
    }

    /**
     * 协商记录 reply_detail_infos → messages（replier_role 枚举：USER/MERCHANT/SYSTEM/
     * AUDITOR/GOVERNMENT，后三者归「系统」角色，审核小二/政府单位在名称中保留）。
     */
    private static List<ComplaintRecord.MessageRecord> extractMessages(JsonNode item) {
        var replies = item.path("reply_detail_infos");
        if (replies == null || !replies.isArray()) return List.of();
        var list = new ArrayList<ComplaintRecord.MessageRecord>();
        for (var r : replies) {
            var replyRole = r.path("replier_role").asString(null);
            var role = switch (replyRole) {
                case "USER" -> ComplaintRecord.MessageRecord.ROLE_USER;
                case "MERCHANT" -> ComplaintRecord.MessageRecord.ROLE_MERCHANT;
                default -> ComplaintRecord.MessageRecord.ROLE_SYSTEM;
            };
            var roleLabel = switch (replyRole) {
                case "USER" -> "用户";
                case "MERCHANT" -> "商户";
                case "AUDITOR" -> "审核小二";
                case "GOVERNMENT" -> "政府单位";
                default -> "系统";
            };
            var replierName = r.path("replier_name").asString(null);
            var name = replierName == null || replierName.isBlank() ? roleLabel : replierName;
            if (("AUDITOR".equals(replyRole) || "GOVERNMENT".equals(replyRole))
                    && !name.equals(roleLabel)) {
                name = name + "（" + roleLabel + "）";
            }
            list.add(ComplaintRecord.MessageRecord.builder()
                    .role(role)
                    .name(name)
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

    /** 图片引用 id 列表 → 逗号分隔字符串（交易投诉 images 参数语义），空列表返回 null 不带该字段 */
    private static String joinMediaIds(List<ComplaintMediaRef> images) {
        if (images == null || images.isEmpty()) return null;
        var ids = new ArrayList<String>(images.size());
        for (var image : images) {
            if (image == null || image.id() == null || image.id().isBlank())
                throw new ExpectedFailureException("图片引用缺少 image_id");
            ids.add(image.id());
        }
        return String.join(",", ids);
    }

    /** RiskGO process.finish 的 img_file_list：每项必须同时具备 url 与 key。 */
    private static List<Map<String, String>> riskImageFiles(List<ComplaintMediaRef> images) {
        if (images == null || images.isEmpty()) return null;
        var files = new ArrayList<Map<String, String>>(images.size());
        for (var image : images) {
            if (image == null || image.url() == null || image.url().isBlank()
                    || image.key() == null || image.key().isBlank())
                throw new ExpectedFailureException("消费者投诉完成图片缺少 url/key");
            var file = new LinkedHashMap<String, String>();
            file.put("img_url", image.url());
            file.put("img_url_key", image.key());
            files.add(file);
        }
        return files;
    }

    /** RiskGO 用户回复通知 → 本地消息（详情接口不返回用户后续回复，只能从通知留痕）。 */
    private static ComplaintRecord.MessageRecord riskNotifyMessage(String messageType, JsonNode biz) {
        if (!"USER_REPLY".equals(messageType)) return null;
        var content = biz.path("reply_content").asString(null);
        if (content == null || content.isBlank()) return null;
        return ComplaintRecord.MessageRecord.builder()
                .role(ComplaintRecord.MessageRecord.ROLE_USER)
                .name("用户")
                .content(content)
                .createdAt(parseTime(biz.path("reply_time").asString(null)))
                .build();
    }

    // =========================================================================
    // 内部 — 工具方法
    // =========================================================================

    /**
     * 金额字符串（元）转分（Long）；支付宝返回的金额单位为元。
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
