package com.okpay.plugin.wxpay.handler;

import com.okpay.plugin.model.ChannelConfig;
import com.okpay.plugin.model.ComplaintRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("微信投诉被诉商户号")
class WxpayComplaintedMchidTest {

    private ChannelConfig cfg(String mode, String appMchId) {
        var extras = new HashMap<String, String>();
        if (mode != null) extras.put("mode", mode);
        return ChannelConfig.builder().appMchId(appMchId).extras(extras).build();
    }

    @Test
    @DisplayName("入库：直连商户上游未返回时按自身商户号补齐，服务商保持为空")
    void fillDirectMchidOnlyForDirect() {
        var direct = ComplaintRecord.builder().complaintId("C1").build();
        assertThat(WxpayComplainCore.fillDirectComplaintedMchid(direct, cfg("direct", "190001")).getMerchantNo())
                .isEqualTo("190001");

        var service = ComplaintRecord.builder().complaintId("C2").build();
        assertThat(WxpayComplainCore.fillDirectComplaintedMchid(service, cfg("service", "190001")).getMerchantNo())
                .isNull();

        var upstream = ComplaintRecord.builder().complaintId("C3").merchantNo("190002").build();
        assertThat(WxpayComplainCore.fillDirectComplaintedMchid(upstream, cfg("direct", "190001")).getMerchantNo())
                .isEqualTo("190002");
    }

    @Test
    @DisplayName("回复/完成：被诉商户号非空直接使用，空值拒绝")
    void requireComplaintedMchidRejectsBlank() {
        assertThat(WxpayComplainCore.requireComplaintedMchid("190002")).isEqualTo("190002");
        assertThatThrownBy(() -> WxpayComplainCore.requireComplaintedMchid(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少被诉商户号");
        assertThatThrownBy(() -> WxpayComplainCore.requireComplaintedMchid(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少被诉商户号");
    }
}
