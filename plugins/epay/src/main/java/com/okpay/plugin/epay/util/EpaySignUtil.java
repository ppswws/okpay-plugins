package com.okpay.plugin.epay.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 易支付 MD5 签名工具。
 *
 * <p>按键排序拼接 → 追加密钥 → MD5 十六进制小写。</p>
 */
public final class EpaySignUtil {

    private EpaySignUtil() {}

    /** 签名 */
    public static String sign(Map<String, String> params, String key) {
        var keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        var sb = new StringBuilder();
        for (var k : keys) {
            if ("sign".equals(k) || "sign_type".equals(k)) continue;
            var v = params.get(k);
            if (v == null || v.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("&");
            sb.append(k).append("=").append(v);
        }
        sb.append(key);
        return md5(sb.toString());
    }

    /** 验签（大小写不敏感） */
    public static boolean verify(Map<String, String> params, String key) {
        var sig = params.getOrDefault("sign", "").toLowerCase();
        return !sig.isEmpty() && sig.equals(sign(params, key).toLowerCase());
    }

    private static String md5(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("MD5 计算失败", e);
        }
    }
}
