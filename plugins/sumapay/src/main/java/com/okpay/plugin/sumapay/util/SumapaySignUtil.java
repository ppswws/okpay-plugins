package com.okpay.plugin.sumapay.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 丰付支付签名工具 — RSA-SHA256（PKCS1 v1.5，Base64 输出）。
 *
 * <p>签名内容为接口约定字段的<b>值</b>按顺序直接拼接（无分隔符），空值字段不参与；
 * 签名字节按 UTF-8 计算，传输层才转 GBK。密钥支持 PEM 或裸 Base64
 * （自动补 PEM 头尾），私钥 PKCS8/PKCS1、公钥 X509/PKCS1 均兼容。</p>
 */
public final class SumapaySignUtil {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    private SumapaySignUtil() {}

    /** 拼接签名串：按字段顺序取非空值（渠道约定空字段不参与拼接） */
    public static String concat(Map<String, String> params, List<String> keys) {
        var sb = new StringBuilder();
        for (var k : keys) {
            var v = params.get(k);
            if (v != null && !v.isBlank()) sb.append(v);
        }
        return sb.toString();
    }

    /** 使用商户私钥生成签名（UTF-8 签名字节，Base64 输出） */
    public static String sign(String privateKey, String plain) {
        try {
            var sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initSign(parsePrivateKey(privateKey));
            sig.update(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalArgumentException("商户私钥签名失败", e);
        }
    }

    /** 使用丰付公钥验签；签名缺失或非法返回 false */
    public static boolean verify(String publicKey, String plain, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            var sig = Signature.getInstance(SIGN_ALGORITHM);
            sig.initVerify(parsePublicKey(publicKey));
            sig.update(plain.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // 密钥解析：PEM / 裸 Base64；PKCS8 / X509 优先，PKCS1 兜底
    // =========================================================================

    private static PrivateKey parsePrivateKey(String raw) throws Exception {
        var data = pemDecode(raw);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));
        } catch (InvalidKeySpecException e) {
            // PKCS1 私钥：RSAPrivateKey ::= SEQUENCE { version, n, e, d, p, q, dp, dq, qinv }
            var ints = Der.parse(data).children;
            if (ints.length < 9) throw e;
            var spec = new RSAPrivateCrtKeySpec(
                    ints[1].intValue(), ints[2].intValue(), ints[3].intValue(),
                    ints[4].intValue(), ints[5].intValue(), ints[6].intValue(),
                    ints[7].intValue(), ints[8].intValue());
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        }
    }

    private static PublicKey parsePublicKey(String raw) throws Exception {
        var data = pemDecode(raw);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(data));
        } catch (InvalidKeySpecException e) {
            // PKCS1 公钥：RSAPublicKey ::= SEQUENCE { n, e }
            var ints = Der.parse(data).children;
            if (ints.length < 2) throw e;
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(ints[0].intValue(), ints[1].intValue()));
        }
    }

    /** 去除 PEM 头尾（或裸 Base64）取 DER 字节 */
    private static byte[] pemDecode(String raw) {
        var key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("密钥为空");
        var base64 = key.contains("-----BEGIN")
                ? key.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                      .replaceAll("-----END [A-Z0-9 ]+-----", "")
                      .replaceAll("\\s", "")
                : key.replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    /** 极简 DER 解析（仅 PKCS1 密钥结构需要）：SEQUENCE 展开子节点，其余只取内容。 */
    private static final class Der {
        private final int tag;
        private final byte[] content;
        private final Der[] children;
        private final int end;

        private Der(int tag, byte[] content, Der[] children, int end) {
            this.tag = tag;
            this.content = content;
            this.children = children;
            this.end = end;
        }

        static Der parse(byte[] data) { return parse(data, 0); }

        private static Der parse(byte[] data, int off) {
            int tag = data[off] & 0xFF;
            int p = off + 1;
            int len = data[p] & 0xFF;
            if ((len & 0x80) != 0) {
                int n = len & 0x7F;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (data[++p] & 0xFF);
            }
            p++;
            int contentEnd = p + len;
            var content = Arrays.copyOfRange(data, p, contentEnd);
            Der[] children = null;
            if (tag == 0x30) {
                var list = new ArrayList<Der>();
                int cp = 0;
                while (cp < content.length) {
                    var child = parse(content, cp);
                    list.add(child);
                    cp = child.end;
                }
                children = list.toArray(Der[]::new);
            }
            return new Der(tag, content, children, contentEnd);
        }

        BigInteger intValue() {
            if (tag != 0x02) throw new IllegalArgumentException("非 INTEGER 节点");
            return new BigInteger(content);
        }
    }
}
