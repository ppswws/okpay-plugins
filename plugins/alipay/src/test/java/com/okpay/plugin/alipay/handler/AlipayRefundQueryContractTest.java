package com.okpay.plugin.alipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 单笔退款成功判定与查单不存在语义（官方口径）：
 * alipay.trade.refund code=10000 仅代表受理成功，fund_change=Y 才是资金变动成功；
 * N/缺失（幂等重放等场景）保持 S_ING 交查单确认；查单 ACQ.TRADE_NOT_EXIST = 等待买家付款（S_ING）。
 */
@DisplayName("AlipaySubmitHandler/QueryHandler 退款成功判定与查单不存在")
class AlipayRefundQueryContractTest {

    private static final String APP_ID = "2021000000000001";
    private static final String TRADE_NO = "T202608281200000000001";
    private static final String REFUND_NO = "R202608281200000000001";
    private static KeyPair KP;

    @BeforeAll
    static void setUp() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KP = kpg.generateKeyPair();
    }

    private static String pem(String header, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(der);
        return "-----BEGIN " + header + "-----\n" + b64 + "\n-----END " + header + "-----";
    }

    private static byte[] cfgRaw() throws Exception {
        return HttpHelper.MAPPER.writeValueAsBytes(Map.of(
                "appid", APP_ID,
                "appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()),
                "appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded())));
    }

    private static byte[] cfgRawOrNull() {
        try {
            return cfgRaw();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 非合单订单 ctx：getSubOrders 恒空（单笔路径） */
    private InvokeContext ctx(boolean combine) throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.getSubOrders(any())).thenReturn(List.of());
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(10000).combine(combine).build())
                .refund(RefundSnapshot.builder().refundNo(REFUND_NO).tradeNo(TRADE_NO).amount(5000).build())
                .callback(cb)
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static MockedStatic<HttpHelper> responding(byte[] body) {
        var mocked = mockStatic(HttpHelper.class);
        mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(), body, "req", 10, 1));
        return mocked;
    }

    // =========================================================================
    // 单笔退款：fund_change 复合判定
    // =========================================================================

    @Test
    @DisplayName("退款 fund_change=Y → S_OK（资金变动成功）")
    void refundFundChangeYOk() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_refund_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"Y\",\"trade_no\":\"TX1\"}"))) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("TX1");
        }
    }

    @Test
    @DisplayName("退款 10000 但 fund_change 缺失 → S_ING（受理≠资金变动，交查单确认）")
    void refundWithoutFundChangeIng() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_refund_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"TX1\"}"))) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getMsg()).contains("待查询确认");
        }
    }

    @Test
    @DisplayName("退款 10000 但 fund_change=N → S_ING（官方口径：N 需查询确认）")
    void refundFundChangeNIng() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_refund_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"fund_change\":\"N\",\"trade_no\":\"TX1\"}"))) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
        }
    }

    @Test
    @DisplayName("退款 40004+SYSTEM_ERROR → S_ING（系统超时状态未知，重试/查单兜底）")
    void refundSystemErrorIng() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_refund_response",
                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                        + "\"sub_code\":\"SYSTEM_ERROR\",\"sub_msg\":\"系统超时\"}"))) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getCode()).isEqualTo("40004");
        }
    }

    @Test
    @DisplayName("退款 40004 其他业务错误 → S_FAIL（确定性拒绝）")
    void refundBusinessFail() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_refund_response",
                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                        + "\"sub_code\":\"REFUND_AMT_RESTRICTION\",\"sub_msg\":\"退款金额超限\"}"))) {
            var result = new AlipaySubmitHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_REF).bizNo(REFUND_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("REFUND_AMT_RESTRICTION");
        }
    }

    // =========================================================================
    // 单笔查单：ACQ.TRADE_NOT_EXIST = 未支付（S_ING），其余业务错误 S_FAIL
    // =========================================================================

    @Test
    @DisplayName("查单 ACQ.TRADE_NOT_EXIST → S_ING（交易不存在=等待买家付款，不作失败终态）")
    void queryTradeNotExistIng() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_query_response",
                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                        + "\"sub_code\":\"ACQ.TRADE_NOT_EXIST\",\"sub_msg\":\"交易不存在\"}"))) {
            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_ING);
            assertThat(result.getMsg()).contains("等待买家付款");
        }
    }

    @Test
    @DisplayName("查单其他业务错误 → S_FAIL")
    void queryBusinessFail() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_query_response",
                "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                        + "\"sub_code\":\"ACQ.INVALID_PARAMETER\",\"sub_msg\":\"参数无效\"}"))) {
            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("ACQ.INVALID_PARAMETER");
        }
    }

    @Test
    @DisplayName("查单 TRADE_SUCCESS → S_OK（buyer=buyer_user_id）")
    void querySuccessOk() throws Exception {
        var ctx = ctx(false);
        try (var mocked = responding(signedResponse("alipay_trade_query_response",
                "{\"code\":\"10000\",\"msg\":\"Success\",\"trade_no\":\"ALI_TX1\","
                        + "\"trade_status\":\"TRADE_SUCCESS\",\"buyer_user_id\":\"2088123456789012\","
                        + "\"total_amount\":\"100.00\"}"))) {
            var result = new AlipayQueryHandler().handle(ctx,
                    BizRequest.builder().bizType(BizType.T_PAY).bizNo(TRADE_NO).build());

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("ALI_TX1");
            assertThat(result.getBuyer()).isEqualTo("2088123456789012");
        }
    }
}
