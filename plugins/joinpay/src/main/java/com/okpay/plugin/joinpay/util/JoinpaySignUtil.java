package com.okpay.plugin.joinpay.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 汇聚支付 MD5 签名 — 按业务接口定义的字段顺序（非字典序）。
 */
public final class JoinpaySignUtil {

    private JoinpaySignUtil() {}

    public static String sign(Map<String, String> data, List<String> fields, String key) {
        var sb = new StringBuilder();
        for (var f : fields) {
            var v = data.get(f);
            if (v != null && !v.isEmpty()) sb.append(v);
        }
        return md5(sb.append(key).toString());
    }

    public static boolean verify(Map<String, String> data, List<String> fields, String key) {
        var hmac = data.getOrDefault("hmac", "").toLowerCase();
        return !hmac.isEmpty() && hmac.equals(sign(data, fields, key).toLowerCase());
    }

    public static String md5(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException("MD5 计算失败", e); }
    }
}
