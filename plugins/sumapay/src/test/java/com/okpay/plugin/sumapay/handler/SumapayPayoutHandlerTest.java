package com.okpay.plugin.sumapay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizState;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.BizRequest;
import com.okpay.plugin.model.BizResult;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.TransferSnapshot;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sumapay.TestKeys;
import com.okpay.plugin.sumapay.util.SumapaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 丰付代付提交/查询/余额查询：RSA 字段序、受理/失败分类、状态映射。
 */
@DisplayName("Sumapay 代付/余额")
class SumapayPayoutHandlerTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final List<String> PAYOUT_REQ_FIELDS = List.of(
            "requestId", "merchantCode", "transferType", "transToMerCode", "transToMerName",
            "sum", "unionBankNum", "openBankName", "openBankProvince", "openBankCity",
            "accountName", "bankCode", "bankAccount", "reason", "noticeUrl",
            "refundNoticeUrl", "transferPayType");
    private static final List<String> PAYOUT_QUERY_REQ_FIELDS = List.of(
            "requestId", "merchantCode", "originalRequestId");
    private static final List<String> PAYOUT_RESP_FIELDS = List.of(
            "requestId", "result", "sum");
    private static final List<String> PAYOUT_QUERY_RESP_FIELDS = List.of(
            "requestId", "result", "status", "dealRemark");
    private static final List<String> BALANCE_REQ_FIELDS = List.of(
            "requestId", "merchantCode");
    private static final List<String> BALANCE_RESP_FIELDS = List.of(
            "result", "merchantCode", "merchantName", "balance", "withdrawAbleBalance",
            "frozenBalance", "prepaidAccountBalance", "unsettledBalance",
            "riskReserveAccountBalance");

    // =========================================================================
    // 代付提交
    // =========================================================================

    @Test
    @DisplayName("对私代付：transferType=1、字段完整、RSA 签名、00000 只算受理")
    void privatePayoutSubmits() throws Exception {
        var body = new AtomicReference<byte[]>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        body.set(inv.getArgument(2));
                        return resp(PAYOUT_RESP_FIELDS, Map.of(
                                "requestId", "T1", "result", "00000", "sum", "1.00"));
                    });

            var result = new SumapaySubmitHandler().handle(ctx(transfer(null)), req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(result.getCode()).isEqualTo("00000");
        }
        var form = gbkForm(body.get());
        assertThat(form).containsEntry("requestId", "T1")
                .containsEntry("merchantCode", "SM1")
                .containsEntry("transferType", "1")
                .containsEntry("sum", "1.00")
                .containsEntry("accountName", "张三")
                .containsEntry("bankAccount", "6222021234567890")
                .containsEntry("reason", "代付")
                .containsEntry("transferPayType", "1")
                .containsEntry("noticeUrl", "https://pay.example.com/pay/transfernotify/T1");
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY,
                SumapaySignUtil.concat(form, PAYOUT_REQ_FIELDS), form.get("signature"))).isTrue();
    }

    @Test
    @DisplayName("对公账户 → PARAM_ERROR（丰付代付仅接入对私）")
    void publicAccountRejected() {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            var result = new SumapaySubmitHandler().handle(ctx(transfer("public")), req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(result.getCode()).isEqualTo("PARAM_ERROR");
            assertThat(result.getMsg()).contains("仅支持对私");
            mocked.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("代付明确失败码 → S_FAIL；未知码 → S_WAIT")
    void payoutResultClassification() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(PAYOUT_RESP_FIELDS, Map.of(
                            "requestId", "T1", "result", "200300162", "sum", "1.00")));
            assertThat(new SumapaySubmitHandler().handle(ctx(transfer(null)), req(BizType.T_XFER)).getState())
                    .isEqualTo(BizState.S_FAIL);
        }
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(PAYOUT_RESP_FIELDS, Map.of(
                            "requestId", "T1", "result", "300101", "sum", "1.00")));
            assertThat(new SumapaySubmitHandler().handle(ctx(transfer(null)), req(BizType.T_XFER)).getState())
                    .isEqualTo(BizState.S_WAIT);
        }
    }

    // =========================================================================
    // 代付查询
    // =========================================================================

    @Test
    @DisplayName("代付查询 status=2 → S_OK，apiNo 退化为平台单号（渠道无上游流水）")
    void payoutQuerySuccess() throws Exception {
        var body = new AtomicReference<byte[]>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        body.set(inv.getArgument(2));
                        return resp(PAYOUT_QUERY_RESP_FIELDS, Map.of(
                                "requestId", "ST1", "result", "00000", "status", "2", "dealRemark", ""));
                    });

            var result = new SumapayQueryHandler().handle(ctx(transfer(null)), req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getApiNo()).isEqualTo("T1");
        }
        var form = gbkForm(body.get());
        assertThat(form).containsEntry("requestId", "ST1")
                .containsEntry("merchantCode", "SM1")
                .containsEntry("originalRequestId", "T1");
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY,
                SumapaySignUtil.concat(form, PAYOUT_QUERY_REQ_FIELDS), form.get("signature"))).isTrue();
    }

    @Test
    @DisplayName("代付查询终态失败状态 → S_FAIL；处理中状态 → S_WAIT")
    void payoutQueryStatusMapping() throws Exception {
        for (var status : List.of("3", "4", "5", "7", "10")) {
            assertThat(queryStatus(status).getState()).isEqualTo(BizState.S_FAIL);
        }
        for (var status : List.of("0", "1", "6", "9")) {
            assertThat(queryStatus(status).getState()).isEqualTo(BizState.S_WAIT);
        }
    }

    @Test
    @DisplayName("代付查询 result!=00000 → S_WAIT（查询失败不判付款失败）")
    void payoutQueryFailureKeepsWait() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(PAYOUT_QUERY_RESP_FIELDS, Map.of(
                            "requestId", "ST1", "result", "2003", "status", "", "dealRemark", "")));

            var result = new SumapayQueryHandler().handle(ctx(transfer(null)), req(BizType.T_XFER));

            assertThat(result.getState()).isEqualTo(BizState.S_WAIT);
            assertThat(result.getCode()).isEqualTo("QUERY_FAIL");
        }
    }

    // =========================================================================
    // 余额查询
    // =========================================================================

    @Test
    @DisplayName("余额查询：result=00000 → S_OK，余额取 withdrawAbleBalance")
    void balanceSuccess() throws Exception {
        var body = new AtomicReference<byte[]>();
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> {
                        body.set(inv.getArgument(2));
                        return resp(BALANCE_RESP_FIELDS, Map.of(
                                "requestId", "B1", "result", "00000", "merchantCode", "SM1",
                                "merchantName", "测试商户", "balance", "12.00",
                                "withdrawAbleBalance", "8.50", "frozenBalance", "0.00",
                                "prepaidAccountBalance", "3.50", "unsettledBalance", "0.00",
                                "riskReserveAccountBalance", "0.00"));
                    });

            var result = new SumapayQueryHandler().handle(ctx(null), req(BizType.T_BAL));

            assertThat(result.getState()).isEqualTo(BizState.S_OK);
            assertThat(result.getBalance()).isEqualTo("8.50");
        }
        var form = gbkForm(body.get());
        assertThat(form.get("requestId")).startsWith("B");
        assertThat(form).containsEntry("merchantCode", "SM1");
        assertThat(SumapaySignUtil.verify(TestKeys.PUBLIC_KEY,
                SumapaySignUtil.concat(form, BALANCE_REQ_FIELDS), form.get("signature"))).isTrue();
    }

    @Test
    @DisplayName("余额查询失败/余额为空 → S_FAIL")
    void balanceFailure() throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(BALANCE_RESP_FIELDS, Map.of(
                            "result", "2003", "merchantCode", "SM1", "merchantName", "",
                            "balance", "", "withdrawAbleBalance", "", "frozenBalance", "",
                            "prepaidAccountBalance", "", "unsettledBalance", "",
                            "riskReserveAccountBalance", "")));
            assertThat(new SumapayQueryHandler().handle(ctx(null), req(BizType.T_BAL)).getState())
                    .isEqualTo(BizState.S_FAIL);
        }
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(BALANCE_RESP_FIELDS, Map.of(
                            "result", "00000", "merchantCode", "SM1", "merchantName", "",
                            "balance", "10.00", "withdrawAbleBalance", "", "frozenBalance", "",
                            "prepaidAccountBalance", "", "unsettledBalance", "",
                            "riskReserveAccountBalance", "")));
            var result = new SumapayQueryHandler().handle(ctx(null), req(BizType.T_BAL));
            assertThat(result.getState()).isEqualTo(BizState.S_FAIL);
            assertThat(result.getCode()).isEqualTo("BAL_ERROR");
        }
    }

    // =========================================================================
    // 内部
    // =========================================================================

    private BizResult queryStatus(String status) throws Exception {
        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            mocked.when(() -> HttpHelper.postBytes(any(), anyString(), any(), anyString(), any()))
                    .thenAnswer(inv -> resp(PAYOUT_QUERY_RESP_FIELDS, Map.of(
                            "requestId", "ST1", "result", "00000", "status", status,
                            "dealRemark", "reason")));
            return new SumapayQueryHandler().handle(ctx(transfer(null)), req(BizType.T_XFER));
        }
    }

    private static TransferSnapshot transfer(String accountType) {
        return TransferSnapshot.builder()
                .tradeNo("T1").amount(100L).accountType(accountType)
                .cardNo("6222021234567890").cardName("张三")
                .build();
    }

    private static BizRequest req(BizType type) {
        return BizRequest.builder().bizType(type).build();
    }

    private InvokeContext ctx(TransferSnapshot transfer) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .transfer(transfer)
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").build())
                .build();
    }

    private static byte[] cfgRaw() {
        try {
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("appid", "SM1");
            cfg.put("appkey", TestKeys.PUBLIC_KEY);
            cfg.put("appsecret", TestKeys.PRIVATE_KEY);
            return HttpHelper.MAPPER.writeValueAsBytes(cfg);
        } catch (Exception e) {
            throw new IllegalStateException("通道配置序列化失败", e);
        }
    }

    private static HttpHelper.HttpResponse resp(List<String> verifyFields,
                                                Map<String, String> fields) throws Exception {
        var m = new LinkedHashMap<String, String>();
        m.putAll(fields);
        m.put("signature", SumapaySignUtil.sign(TestKeys.PRIVATE_KEY,
                SumapaySignUtil.concat(m, verifyFields)));
        return new HttpHelper.HttpResponse(200, Map.of(),
                HttpHelper.MAPPER.writeValueAsBytes(m), "req", 10, 1);
    }

    /** 请求体按 GBK 解出（PaymentUtils.parseForm 固定按 UTF-8，中文会失真） */
    private static Map<String, String> gbkForm(byte[] body) {
        var raw = new String(body, StandardCharsets.ISO_8859_1);
        var out = new LinkedHashMap<String, String>();
        for (var pair : raw.split("&")) {
            var idx = pair.indexOf('=');
            if (idx <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, idx), GBK),
                    URLDecoder.decode(pair.substring(idx + 1), GBK));
        }
        return out;
    }
}
