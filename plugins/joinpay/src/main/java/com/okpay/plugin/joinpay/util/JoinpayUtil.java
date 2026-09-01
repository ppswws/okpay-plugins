package com.okpay.plugin.joinpay.util;

import com.okpay.plugin.sdk.HttpHelper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 汇聚支付专属工具（非通用，不放 SDK）。
 */
public final class JoinpayUtil {

    /**
     * 数值保真 JSON 解析：浮点按 BigDecimal 保留原文（165684.50 不会变成 165684.5）。
     * 汇聚回包/通知中存在数值型金额（如 paidAmount: 1.00），验签须按上游 JSON 原文拼接。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper NUM_MAPPER = HttpHelper.MAPPER.copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JoinpayUtil() {}

    public static String limitLength(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : "";
    }

    public static boolean isFormOrHtml(String s) {
        return s != null && (s.contains("<form") || s.contains("<html") || s.contains("<HTML"));
    }

    /** JSON → Map（值转字符串、数值保留原文、保持原始顺序）；非 JSON 返回空 Map */
    public static Map<String, String> parseJsonNum(String json) {
        var map = new LinkedHashMap<String, String>();
        try {
            var raw = parseJsonNumRaw(json);
            raw.forEach((k, v) -> map.put(k, v != null ? v.toString() : ""));
        } catch (Exception e) {
            // 非 JSON：返回空 Map，由调用方决定回退策略
        }
        return map;
    }

    /** JSON → Map&lt;String, Object&gt;（数值保真，调用方自行取嵌套对象）；解析失败抛出 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonNumRaw(String json) throws Exception {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("空 JSON");
        return (Map<String, Object>) NUM_MAPPER.readValue(json, Map.class);
    }
}
