package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.PluginLog;
import com.okpay.plugin.sdk.WechatPayException;
import com.okpay.plugin.sdk.WechatPayV3Client;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

import com.okpay.plugin.sdk.ExpectedFailureException;
/**
 * 微信支付投诉处理核心逻辑 — 零第三方 SDK，走 WechatPayV3Client（商户服务原始 JSON 接口）。
 */
public class WxpayComplainCore {

    private static final Logger log = LoggerFactory.getLogger(WxpayComplainCore.class);

    /**
     * 拉取一页投诉。游标即 offset；total_count 仅首页返回，据此判断是否还有下一页。
     * 单页固定 {@link ComplaintHandler#SYNC_PAGE_SIZE}：微信 limit 过大时会返回
     * INVALID_REQUEST「当前查询结果数据量过大」。
     */
    public SyncListResult syncListPage(ChannelConfig cfg, LocalDate since, String cursor, int limit) {
        int pageSize = Math.max(1, Math.min(limit, ComplaintHandler.SYNC_PAGE_SIZE));
        int offset = parseOffset(cursor);
        var client = buildClient(cfg);
        var all = new ArrayList<ComplaintRecord>();
        try {
            // 不传 complainted_mchid：直连查本商户，服务商/收付通查名下全部子商户
            var query = "?limit=" + pageSize + "&offset=" + offset
                    + "&begin_date=" + since + "&end_date=" + LocalDate.now();
            var resp = client.get("/v3/merchant-service/complaints-v2" + query, null);
            var body = resp.body();
            var data = body.path("data");
            boolean fullPage = false;
            if (data != null && data.isArray()) {
                fullPage = data.size() == pageSize;
                for (var item : data) {
                    all.add(decryptPhone(fillDirectComplaintedMchid(WxpayConvert.toRecord(item), cfg), client));
                }
            }
            // total_count 只在 offset=0 返回；拿不到时以“本页是否满页”判断，最多多拉一次空页
            int total = body.path("total_count").asInt(-1);
            boolean hasMore = fullPage && (total < 0 || offset + data.size() < total);
            String nextCursor = hasMore ? String.valueOf(offset + pageSize) : null;
            log.debug("微信投诉列表同步: appId={}, since={}, offset={}, pageSize={}, total={}, fetched={}, hasMore={}",
                    cfg.getAppId(), since, offset, pageSize, total, all.size(), hasMore);
            return SyncListResult.builder().complaints(all).nextCursor(nextCursor).build();
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("微信投诉列表同步失败", e);
        }
    }

    /** 游标即 offset；非法游标说明宿主/插件契约被破坏。 */
    private static int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            throw new ExpectedFailureException("微信投诉同步游标无效: " + cursor, e);
        }
    }

    public ComplaintRecord syncDetail(ChannelConfig cfg, ComplaintRef ref) {
        try {
            var client = buildClient(cfg);
            var record = fetchDetail(client, cfg, ref.externalId());
            // 协商历史独立接口（详情应答已无 query_historys 字段）；limit 上限 300，默认单页足够
            var enc = java.net.URLEncoder.encode(ref.externalId(), StandardCharsets.UTF_8);
            var history = client.get("/v3/merchant-service/complaints-v2/" + enc
                    + "/negotiation-historys?limit=300&offset=0", null);
            record.setMessages(WxpayConvert.toMessages(history.body().path("data")));
            return decryptPhone(record, client);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("微信投诉详情查询失败", e);
        }
    }

    /** 回调落库只取投诉字段，跳过协商历史接口 */
    public ComplaintRecord syncDetailWithoutHistory(ChannelConfig cfg, ComplaintRef ref) {
        try {
            var client = buildClient(cfg);
            return decryptPhone(fetchDetail(client, cfg, ref.externalId()), client);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("微信投诉详情查询失败", e);
        }
    }

    private ComplaintRecord fetchDetail(WechatPayV3Client client, ChannelConfig cfg, String complaintId) throws Exception {
        var enc = java.net.URLEncoder.encode(complaintId, StandardCharsets.UTF_8);
        return fillDirectComplaintedMchid(WxpayConvert.toRecord(client.get(
                "/v3/merchant-service/complaints-v2/" + enc, null).body()), cfg);
    }

    public CallbackResult parseCallback(ChannelConfig cfg, Map<String, String> headers, byte[] body) {
        try {
            var headerList = new LinkedHashMap<String, List<String>>();
            headers.forEach((k, v) -> headerList.put(k, List.of(v)));
            var parsed = buildClient(cfg).parseNotify(
                    new String(body, StandardCharsets.UTF_8), headerList);
            return CallbackResult.builder()
                    .source(ComplaintSource.WXPAY)
                    .complaintId(parsed.path("complaint_id").asString(null))
                    .actionType(parsed.path("action_type").asString(null))
                    .ackStatus(200).ackContent("{\"code\":\"SUCCESS\"}").contentType("application/json")
                    .build(); // 应答体为微信 v3 通知标准成功格式
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                log.error("投诉回调解析失败", e);
            }
            return CallbackResult.builder().ackStatus(500)
                    .ackContent("{\"code\":\"FAIL\",\"message\":\"callback error\"}")
                    .contentType("application/json").build();
        }
    }

    public ComplaintMediaRef uploadMedia(ChannelConfig cfg, ComplaintRef ref,
                                         byte[] fileData, String filename) {
        try {
            // 微信支持 jpg/jpeg/png/bmp；仅校验，保留原文件名
            var mediaFilename = WxpayConvert.validateMediaFilename(filename);
            // meta 用 JSON 序列化而非字符串拼接：文件名含引号/反斜杠时拼出的 JSON 会损坏
            var metaNode = HttpHelper.MAPPER.createObjectNode();
            metaNode.put("filename", mediaFilename);
            metaNode.put("sha256", sha256Hex(fileData));
            var resp = buildClient(cfg).postMultipart(
                    "/v3/merchant-service/images/upload",
                    HttpHelper.MAPPER.writeValueAsString(metaNode), mediaFilename,
                    "image/" + ext(mediaFilename).replace("jpg", "jpeg"),
                    fileData, null);
            var mediaId = resp.body().path("media_id").asString(null);
            if (mediaId == null || mediaId.isBlank()) throw new ExpectedFailureException("上传图片未返回 media_id");
            return ComplaintMediaRef.ofId(mediaId);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("上传图片失败", e);
        }
    }

    public byte[] downloadMedia(ChannelConfig cfg, String mediaId) {
        try {
            // 兼容两种入参：完整 media_url（如 .../v3/merchant-service/images/{media_id}）或裸 media_id
            if (mediaId != null && mediaId.startsWith("http")) {
                mediaId = WxpayConvert.extractMediaId(mediaId);
            }
            mediaId = WxpayConvert.decodeMediaId(mediaId);
            if (mediaId == null || mediaId.isBlank()) {
                throw new ExpectedFailureException("mediaRef 为空，无法提取 media_id");
            }
            // 图片下载须走商户签名 GET（不带签名的裸 GET 上游拒绝）
            return buildClient(cfg).download("/v3/merchant-service/images/"
                    + java.net.URLEncoder.encode(mediaId, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("下载图片失败", e);
        }
    }

    public void reply(ChannelConfig cfg, ReplyParams params) {
        try {
            var mchId = requireComplaintedMchid(params.getMerchantNo());
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("complainted_mchid", mchId);
            body.put("response_content", params.getContent());
            var images = body.putArray("response_images");
            if (params.getImages() != null) {
                for (var image : params.getImages()) {
                    if (image == null || image.id() == null || image.id().isBlank())
                        throw new ExpectedFailureException("图片引用缺少 media_id");
                    images.add(image.id());
                }
            }
            buildClient(cfg).post("/v3/merchant-service/complaints-v2/"
                            + java.net.URLEncoder.encode(params.getRef().externalId(), StandardCharsets.UTF_8)
                            + "/response", body, null);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("回复投诉失败", e);
        }
    }

    /**
     * 更新退款审批结果（消费者投诉 2.0）：
     * 同意/拒绝由微信推进投诉单状态；同意退款不等于资金退回，仍需另行发起退款。
     */
    public void refundProgress(ChannelConfig cfg, ComplaintRefundParams params) {
        try {
            var mchId = requireComplaintedMchid(params.getMerchantNo());
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("complainted_mchid", mchId);
            if (params.getDecision() == RefundDecision.AGREE) {
                body.put("action", "APPROVE");
                // 官方/易支付一致：同意退款发起日 0=立即；备注只属于拒绝分支
                body.put("launch_refund_day", 0);
            } else {
                if (params.getReason() == null || params.getReason().isBlank())
                    throw new ExpectedFailureException("拒绝退款必须填写原因");
                body.put("action", "REJECT");
                body.put("reject_reason", params.getReason());
                var images = params.getImages();
                if (images != null && !images.isEmpty()) {
                    var arr = body.putArray("reject_media_list");
                    for (var image : images) {
                        if (image == null || image.id() == null || image.id().isBlank())
                            throw new ExpectedFailureException("举证图片缺少 media_id");
                        arr.add(image.id());
                    }
                }
            }
            buildClient(cfg).post("/v3/merchant-service/complaints-v2/"
                            + java.net.URLEncoder.encode(params.getRef().externalId(), StandardCharsets.UTF_8)
                            + "/update-refund-progress", body, null);
        } catch (ExpectedFailureException e) {
            throw e;
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("更新退款审批失败", e);
        }
    }

    /** 微信无结果码/内容：统一 finish 入口只做「反馈处理完成」。 */
    public void finish(ChannelConfig cfg, ComplaintFinishParams params) {
        try {
            var mchId = requireComplaintedMchid(params.getMerchantNo());
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("complainted_mchid", mchId);
            buildClient(cfg).post("/v3/merchant-service/complaints-v2/"
                            + java.net.URLEncoder.encode(params.getRef().externalId(), StandardCharsets.UTF_8)
                            + "/complete", body, null);
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("完成投诉失败", e);
        }
    }

    /** 投诉通知回调地址路径（微信：查询 GET / 创建 POST / 更新 PUT / 删除 DELETE 同路径）。 */
    private static final String NOTIFY_PATH = "/v3/merchant-service/complaint-notifications";

    /** 设置投诉通知回调地址：先查上游已配置地址——一致则跳过，有则更新（PUT）、无则创建（POST）。 */
    public void setNotifyUrl(ChannelConfig cfg, String url) {
        try {
            var client = buildClient(cfg);
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("url", url);
            var existing = queryNotifyUrl(client);
            if (url.equals(existing)) return;
            if (existing == null) {
                client.post(NOTIFY_PATH, body, null);
            } else {
                client.put(NOTIFY_PATH, body, null);
            }
        } catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("设置通知地址失败", e);
        }
    }

    /** 查询上游已配置的回调地址；未配置（微信按 RESOURCE_NOT_EXISTS 报错）返回 null，其余查询错误原样上抛。 */
    private static String queryNotifyUrl(WechatPayV3Client client) throws IOException, InterruptedException {
        try {
            var resp = client.get(NOTIFY_PATH, null);
            var url = resp.body() == null ? null : resp.body().path("url").asString(null);
            return url == null || url.isBlank() ? null : url;
        } catch (WechatPayException e) {
            if ("RESOURCE_NOT_EXISTS".equals(e.code())) return null;
            throw e;
        }
    }

    public void deleteNotifyUrl(ChannelConfig cfg, String url) {
        try { buildClient(cfg).delete(NOTIFY_PATH, null); }
        catch (Exception e) {
            if (!PluginLog.isExpected(e)) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            throw new ExpectedFailureException("删除通知地址失败", e);
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private WechatPayV3Client buildClient(ChannelConfig cfg) {
        var extras = cfg.getExtras();
        try {
            // 字段语义：appmchid=商户号（服务商为服务商商户号，签名主体）
            var pubKeyMode = "public_key".equals(extras.get("authMode"));
            return new WechatPayV3Client(
                    cfg.getAppMchId(),
                    require(cfg, "privateKey"),
                    cfg.getAppKey(),
                    cfg.getAppSecret(),
                    pubKeyMode ? extras.get("publickeyid") : null,
                    pubKeyMode ? extras.get("wxpay_public_key") : null);
        } catch (IOException e) {
            throw new ExpectedFailureException("微信投诉客户端初始化失败（请检查密钥/微信支付公钥格式）", e);
        }
    }

    private static String require(ChannelConfig cfg, String key) {
        var v = cfg.getExtras().get(key);
        if (v == null || v.isBlank()) throw new ExpectedFailureException("缺少配置: " + key);
        return v;
    }

    /** 回复/完成接口 complainted_mchid 必填：入库时已补齐（直连自身/服务商上游），空值说明解析链路异常 */
    static String requireComplaintedMchid(String merchantNo) {
        if (nullish(merchantNo)) throw new ExpectedFailureException("缺少被诉商户号");
        return merchantNo;
    }

    /**
     * 直连商户上游不返回被诉商户号：被诉方即商户自身，按配置商户号补齐。
     * 服务商/收付通不补——被诉子商户号必须由上游逐单返回，缺失时留痕。
     */
    static ComplaintRecord fillDirectComplaintedMchid(ComplaintRecord record, ChannelConfig cfg) {
        if (record == null || !nullish(record.getMerchantNo())) return record;
        if (isDirect(cfg)) {
            if (!nullish(cfg.getAppMchId())) record.setMerchantNo(cfg.getAppMchId());
            // 缺商户号时保持上游原值：投诉记录已落库，不落日志。
        }
        // 服务商未返回被诉商户号同样保留上游原值：投诉记录已落库，不落日志。
        return record;
    }

    /** 直连模式判定：只认显式 mode=direct（不存在未声明 mode 的存量数据） */
    private static boolean isDirect(ChannelConfig cfg) {
        return "direct".equals(modeOf(cfg));
    }

    private static String modeOf(ChannelConfig cfg) {
        if (cfg.getExtras() == null) return null;
        return cfg.getExtras().get("mode");
    }

    private static boolean nullish(String s) { return s == null || s.isBlank(); }

    /**
     * payer_phone 为商户 API 证书公钥加密的 RSA-OAEP 密文，用商户私钥解密为明文供运营查看；
     * 解密失败保留密文兜底（不阻断投诉同步），phone 为空原样返回。
     */
    private static ComplaintRecord decryptPhone(ComplaintRecord record, WechatPayV3Client client) {
        var phone = record.getPhone();
        if (nullish(phone)) return record;
        var plain = client.rsaDecryptOaep(phone);
        if (plain != null && !plain.isBlank()) record.setPhone(plain);
        return record;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 计算失败", e);
        }
    }

    /** 从文件名提取扩展名，默认 jpg */
    private static String ext(String filename) {
        var i = filename != null ? filename.lastIndexOf('.') : -1;
        return i > 0 ? filename.substring(i + 1) : "jpg";
    }
}
