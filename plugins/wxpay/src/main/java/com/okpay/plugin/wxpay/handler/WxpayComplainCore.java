package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.model.*;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.WechatPayV3Client;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/**
 * 微信支付投诉处理核心逻辑 — 零第三方 SDK，走 WechatPayV3Client（商户服务原始 JSON 接口）。
 */
public class WxpayComplainCore {

    private static final Logger log = LoggerFactory.getLogger(WxpayComplainCore.class);

    public SyncListResult syncList(ChannelConfig cfg, LocalDate since, int limit) {
        var client = buildClient(cfg);
        int pageSize = Math.min(limit, 50);
        var all = new ArrayList<ComplaintRecord>();
        try {
            for (int offset = 0; ; offset += pageSize) {
                var query = "?limit=" + pageSize + "&offset=" + offset
                        + "&begin_date=" + since + "&end_date=" + LocalDate.now();
                // complainted_mchid=被诉商户号（取 sub_mchid；留空不传=直连查本商户/服务商名下全部子商户）。
                // appmchid 是签名主体，服务商模式下传它会查空，恒不传
                var complainted = complaintedMchid(cfg);
                if (complainted != null) query += "&complainted_mchid="
                        + java.net.URLEncoder.encode(complainted, StandardCharsets.UTF_8);
                var resp = client.get("/v3/merchant-service/complaints-v2" + query, null);
                var data = resp.body().path("data");
                if (data == null || !data.isArray() || data.isEmpty()) break;
                for (var item : data) all.add(decryptPhone(WxpayConvert.toRecord(item), client));
                if (data.size() < pageSize || all.size() >= limit) break;
            }
        } catch (Exception e) { throw new IllegalStateException("微信投诉列表同步失败", e); }
        return SyncListResult.builder().complaints(all).hasMore(all.size() >= limit).build();
    }

    public ComplaintRecord syncDetail(ChannelConfig cfg, String complaintId) {
        try {
            var client = buildClient(cfg);
            var enc = java.net.URLEncoder.encode(complaintId, StandardCharsets.UTF_8);
            var record = WxpayConvert.toRecord(client.get(
                    "/v3/merchant-service/complaints-v2/" + enc, null).body());
            // 协商历史独立接口（详情应答已无 query_historys 字段）；limit 上限 300，默认单页足够
            var history = client.get("/v3/merchant-service/complaints-v2/" + enc
                    + "/negotiation-historys?limit=300&offset=0", null);
            record.setMessages(WxpayConvert.toMessages(history.body().path("data")));
            return decryptPhone(record, client);
        } catch (Exception e) { throw new IllegalStateException("微信投诉详情查询失败", e); }
    }

    public CallbackResult parseCallback(ChannelConfig cfg, Map<String, String> headers, byte[] body) {
        try {
            var headerList = new LinkedHashMap<String, List<String>>();
            headers.forEach((k, v) -> headerList.put(k, List.of(v)));
            var parsed = buildClient(cfg).parseNotify(
                    new String(body, StandardCharsets.UTF_8), headerList);
            return CallbackResult.builder()
                    .complaintId(parsed.path("complaint_id").asString(null))
                    .actionType(parsed.path("action_type").asString(null))
                    .ackStatus(200).ackContent("{\"code\":\"SUCCESS\"}").contentType("application/json")
                    .build(); // 应答体为微信 v3 通知标准成功格式
        } catch (Exception e) { log.error("投诉回调解析失败", e);
            return CallbackResult.builder().ackStatus(500)
                    .ackContent("{\"code\":\"FAIL\",\"message\":\"callback error\"}")
                    .contentType("application/json").build(); }
    }

    public String uploadMedia(ChannelConfig cfg, byte[] fileData, String filename) {
        try {
            // meta 用 JSON 序列化而非字符串拼接：文件名含引号/反斜杠时拼出的 JSON 会损坏
            var metaNode = HttpHelper.MAPPER.createObjectNode();
            metaNode.put("filename", filename);
            metaNode.put("sha256", sha256Hex(fileData));
            var resp = buildClient(cfg).postMultipart(
                    "/v3/merchant-service/images/upload",
                    HttpHelper.MAPPER.writeValueAsString(metaNode), filename,
                    "image/" + ext(filename).replace("jpg", "jpeg"),
                    fileData, null);
            var mediaId = resp.body().path("media_id").asString(null);
            if (mediaId == null) throw new IllegalStateException("上传图片未返回 media_id");
            return mediaId;
        } catch (Exception e) { throw new IllegalStateException("上传图片失败", e); }
    }

    public byte[] downloadMedia(ChannelConfig cfg, String mediaId) {
        try {
            // 兼容两种入参：完整 media_url（如 .../v3/merchant-service/images/{media_id}）或裸 media_id
            if (mediaId != null && mediaId.startsWith("http")) {
                mediaId = WxpayConvert.extractMediaId(mediaId);
            }
            if (mediaId == null || mediaId.isBlank()) {
                throw new IllegalArgumentException("mediaRef 为空，无法提取 media_id");
            }
            // 修复：从无鉴权裸 GET 改为商户签名的 GET（原实现未签名，上游拒绝后无法下载）
            return buildClient(cfg).download("/v3/merchant-service/images/"
                    + java.net.URLEncoder.encode(mediaId, StandardCharsets.UTF_8), null);
        } catch (Exception e) { throw new IllegalStateException("下载图片失败", e); }
    }

    public void reply(ChannelConfig cfg, ReplyParams params) {
        try {
            var mchId = resolveComplaintedMchid(cfg, params.getMerchantNo());
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("complainted_mchid", mchId);
            body.put("response_content", params.getContent());
            var images = body.putArray("response_images");
            if (params.getImages() != null) params.getImages().forEach(images::add);
            buildClient(cfg).post("/v3/merchant-service/complaints-v2/"
                            + java.net.URLEncoder.encode(params.getComplaintId(), StandardCharsets.UTF_8)
                            + "/response", body, null);
        } catch (Exception e) { throw new IllegalStateException("回复投诉失败", e); }
    }

    public void refundProgress(ChannelConfig cfg, String complaintId, RefundDecision decision,
                                String reason, java.util.List<String> images) {
        log.debug("微信 v3 投诉接口不支持退款审批");
    }

    public void complete(ChannelConfig cfg, String complaintId, String merchantNo) {
        try {
            var mchId = resolveComplaintedMchid(cfg, merchantNo);
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("complainted_mchid", mchId);
            buildClient(cfg).post("/v3/merchant-service/complaints-v2/"
                            + java.net.URLEncoder.encode(complaintId, StandardCharsets.UTF_8)
                            + "/complete", body, null);
        } catch (Exception e) { throw new IllegalStateException("完成投诉失败", e); }
    }

    public void setNotifyUrl(ChannelConfig cfg, String url) {
        try {
            var body = HttpHelper.MAPPER.createObjectNode();
            body.put("url", url);
            buildClient(cfg).post("/v3/merchant-service/complaint-notifications", body, null);
        } catch (Exception e) { throw new IllegalStateException("设置通知地址失败", e); }
    }

    public void deleteNotifyUrl(ChannelConfig cfg, String url) {
        try { buildClient(cfg).delete("/v3/merchant-service/complaint-notifications", null); }
        catch (Exception e) { throw new IllegalStateException("删除通知地址失败", e); }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private WechatPayV3Client buildClient(ChannelConfig cfg) {
        var extras = cfg.getExtras();
        try {
            // 字段语义：appmchid=商户号（签名）、appid=AppID；子商户号走 sub_mchid
            var pubKeyMode = "public_key".equals(extras.get("authMode"));
            return new WechatPayV3Client(
                    cfg.getAppMchId(),
                    require(cfg, "privateKey"),
                    cfg.getAppKey(),
                    cfg.getAppSecret(),
                    pubKeyMode ? extras.get("publickeyid") : null,
                    pubKeyMode ? extras.get("wxpay_public_key") : null);
        } catch (IOException e) {
            throw new IllegalStateException("微信投诉客户端初始化失败（请检查密钥/微信支付公钥格式）", e);
        }
    }

    private static String require(ChannelConfig cfg, String key) {
        var v = cfg.getExtras().get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return v;
    }

    /**
     * 解析回复/完成接口必填的 complainted_mchid：
     * 优先投诉记录自己的被诉商户号（服务商/收付通模式 sub_mchid 留空时，每条投诉来自
     * 不同子商户，必须带各自 complained_mchid），其次配置的子商户号（sub_mchid），最后回退商户号（appmchid）。
     */
    private String resolveComplaintedMchid(ChannelConfig cfg, String merchantNo) {
        if (!nullish(merchantNo)) return merchantNo;
        var subMchId = cfg.getExtras().get("sub_mchid");
        return nullish(subMchId) ? cfg.getAppMchId() : subMchId;
    }

    private static boolean nullish(String s) { return s == null || s.isBlank(); }

    /**
     * 投诉列表 complainted_mchid（被诉商户号）取 sub_mchid：
     * 有值 → 按该子商户过滤；留空 → 不传（官方语义：直连=查本商户，
     * 服务商/收付通=名下所有子商户的投诉）。appmchid 是签名主体，服务商模式下传它会查空，恒不传。
     */
    private static String complaintedMchid(ChannelConfig cfg) {
        var subMchId = cfg.getExtras().get("sub_mchid");
        return nullish(subMchId) ? null : subMchId;
    }

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
