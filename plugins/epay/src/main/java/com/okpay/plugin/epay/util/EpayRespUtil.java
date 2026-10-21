package com.okpay.plugin.epay.util;

import java.util.Map;

/**
 * 易支付响应工具。
 */
public final class EpayRespUtil {

    private EpayRespUtil() {}

    /**
     * 判断响应体 code 是否为成功态。
     *
     * <p>标准易支付以 {@code code=1} 表示成功；部分兼容渠道沿用 HTTP 语义返回 {@code code=200}，同样视为成功。</p>
     */
    public static boolean isSuccess(Map<String, String> resp) {
        var code = resp.get("code");
        return "1".equals(code) || "200".equals(code);
    }
}
