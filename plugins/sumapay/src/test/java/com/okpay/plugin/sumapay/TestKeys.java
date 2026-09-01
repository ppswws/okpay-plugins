package com.okpay.plugin.sumapay;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

/**
 * 测试 RSA 密钥对：覆盖商户侧可能出现的全部密钥格式
 * （PKCS8/X509 PEM、PKCS1 PEM、裸 Base64），供签名工具与 handler 测试共用。
 */
public final class TestKeys {

    public static final KeyPair PAIR = generate();
    public static final String PRIVATE_KEY = pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
    public static final String PUBLIC_KEY = pem("PUBLIC KEY", PAIR.getPublic().getEncoded());
    public static final String PRIVATE_PKCS1 = pkcs1Pem((RSAPrivateCrtKey) PAIR.getPrivate());
    public static final String PUBLIC_PKCS1 = pkcs1PublicPem();
    public static final String PRIVATE_BARE = strip(PRIVATE_KEY);
    public static final String PUBLIC_BARE = strip(PUBLIC_KEY);

    private TestKeys() {}

    private static KeyPair generate() {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("测试密钥生成失败", e);
        }
    }

    private static String pem(String label, byte[] der) {
        var base64 = Base64.getEncoder().encodeToString(der);
        var sb = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64)
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        return sb.append("-----END ").append(label).append("-----").toString();
    }

    private static String strip(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                  .replaceAll("-----END [A-Z0-9 ]+-----", "")
                  .replaceAll("\\s", "");
    }

    /** PKCS1 私钥：SEQUENCE{version,n,e,d,p,q,dp,dq,qinv}（丰付商户常见格式） */
    private static String pkcs1Pem(RSAPrivateCrtKey key) {
        var seq = der(0x30,
                derInt(BigInteger.ZERO), derInt(key.getModulus()), derInt(key.getPublicExponent()),
                derInt(key.getPrivateExponent()), derInt(key.getPrimeP()), derInt(key.getPrimeQ()),
                derInt(key.getPrimeExponentP()), derInt(key.getPrimeExponentQ()),
                derInt(key.getCrtCoefficient()));
        return pem("RSA PRIVATE KEY", seq);
    }

    /** PKCS1 公钥：SEQUENCE{n,e} */
    private static String pkcs1PublicPem() {
        var rsa = (RSAPrivateCrtKey) PAIR.getPrivate();
        return pem("RSA PUBLIC KEY", der(0x30, derInt(rsa.getModulus()), derInt(rsa.getPublicExponent())));
    }

    private static byte[] der(int tag, byte[]... parts) {
        var len = 0;
        for (var p : parts) len += p.length;
        var header = new byte[4];
        var size = 0;
        header[size++] = (byte) tag;
        if (len < 0x80) {
            header[size++] = (byte) len;
        } else {
            var lenBytes = BigInteger.valueOf(len).toByteArray();
            header[size++] = (byte) (0x80 | lenBytes.length);
            System.arraycopy(lenBytes, 0, header, size, lenBytes.length);
            size += lenBytes.length;
        }
        var out = new byte[size + len];
        System.arraycopy(header, 0, out, 0, size);
        var off = size;
        for (var p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** INTEGER DER：BigInteger.toByteArray 已是补码最小表示（正值首位恒 0），零值单字节 0x00 */
    private static byte[] derInt(BigInteger v) {
        return der(0x02, v.toByteArray());
    }
}
