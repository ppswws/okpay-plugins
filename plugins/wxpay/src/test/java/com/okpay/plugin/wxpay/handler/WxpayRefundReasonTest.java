package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.model.RefundSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款 reason 出口契约：官方 reason 上限 80 字节（超长被拒），空备注兜底「退款」。
 */
@DisplayName("WxpaySubmitHandler.refundReason")
class WxpayRefundReasonTest {

    private static RefundSnapshot refund(String remark) {
        return RefundSnapshot.builder().refundNo("R1").remark(remark).build();
    }

    @Test
    @DisplayName("空备注兜底「退款」")
    void blankFallback() {
        assertThat(WxpaySubmitHandler.refundReason(refund(null))).isEqualTo("退款");
        assertThat(WxpaySubmitHandler.refundReason(refund("  "))).isEqualTo("退款");
    }

    @Test
    @DisplayName("80 字节内原样透传")
    void withinLimit() {
        var remark = "用户申请退款"; // 6 汉字 = 18 字节
        assertThat(WxpaySubmitHandler.refundReason(refund(remark))).isEqualTo(remark);
    }

    @Test
    @DisplayName("超 80 字节按 UTF-8 截断且不产生残缺字节")
    void overLimitTruncated() {
        // 30 汉字 = 90 字节 > 80 → 截 26 汉字 = 78 字节
        var remark = "退".repeat(30);
        var result = WxpaySubmitHandler.refundReason(refund(remark));
        assertThat(result.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(80);
        assertThat(result).isEqualTo("退".repeat(26));
        assertThat(result).doesNotContain("�");
    }

    @Test
    @DisplayName("截断边界恰在多字节字符中间不崩")
    void boundaryMidChar() {
        // 27 汉字 = 81 字节 → 截 80 字节含残缺尾字节
        var remark = "款".repeat(27);
        var result = WxpaySubmitHandler.refundReason(refund(remark));
        assertThat(result.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(78);
        assertThat(result).doesNotContain("�");
    }
}
