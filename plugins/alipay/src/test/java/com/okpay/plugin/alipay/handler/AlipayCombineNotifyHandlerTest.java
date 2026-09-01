package com.okpay.plugin.alipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.enums.BizType;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.SubOrderSnapshot;
import com.okpay.plugin.model.UpdateSubOrderRequest;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import com.okpay.plugin.sdk.Sdk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlipayNotifyHandler 合单支付通知：merge_pay_status=='FINISHED' 才推进；
 * order_detail_results（参与 RSA2 签名，先验签后解析）逐项校验与库内子单一一对应；
 * 全通过 → 逐子单已付 + 主单完成；任一不符 → 拒收等重发。
 */
@DisplayName("AlipayNotifyHandler 合单支付通知")
class AlipayCombineNotifyHandlerTest {

    private static final String APP_ID = "2021000000000001";
    private static final String TRADE_NO = "P202608181200000000001";
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

    private InvokeContext notifyCtx(byte[] body) {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRawOrNull()).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(30000).build())
                .request(RequestSnapshot.builder().body(body).build())
                .callback(mock(HostCallback.class))
                .build();
    }

    /** 构造 urlencoded 通知体：RSA2 签名走真实私钥（验签按官方规则剔除 sign/sign_type） */
    private static byte[] signedForm(LinkedHashMap<String, String> params) throws Exception {
        params.put("sign", RsaKeys.sign(
                AlipayOpenApiClient.signContentForVerify(params), KP.getPrivate()));
        params.put("sign_type", "RSA2");
        var sb = new StringBuilder();
        for (var e : params.entrySet())
            sb.append(e.getKey()).append("=")
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append("&");
        return sb.substring(0, sb.length() - 1).getBytes(StandardCharsets.UTF_8);
    }

    private static String subNo(int idx) {
        return Sdk.subTradeNo(TRADE_NO, idx);
    }

    private static List<SubOrderSnapshot> subs(long money) {
        return List.of(
                SubOrderSnapshot.builder().subTradeNo(subNo(1)).tradeNo(TRADE_NO).money(money).build(),
                SubOrderSnapshot.builder().subTradeNo(subNo(2)).tradeNo(TRADE_NO).money(money).build());
    }

    /** 构造合单通知参数：order_detail_results 是 JSON 数组字符串（参与 RSA2 签名） */
    private static LinkedHashMap<String, String> mergeParams(String mergeStatus, String resultsJson) {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", TRADE_NO);
        params.put("merge_pay_status", mergeStatus);
        params.put("order_detail_results", resultsJson);
        return params;
    }

    private static String result(String subNo, String txId, String code) {
        return "{\"out_trade_no\":\"" + subNo + "\",\"trade_no\":\"" + txId
                + "\",\"result_code\":\"" + code + "\",\"result_msg\":\"" + code + "\""
                + ",\"total_amount\":\"150.00\"}";
    }

    @Test
    @DisplayName("FINISHED + 子单全 SUCCESS 且一一对应 → 逐子单已付 + 主单完成")
    void allFinishedCompletes() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "," + result(subNo(2), "TX2", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("success");
        var updates = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
        verify(cb, times(2)).updateSubOrder(updates.capture());
        assertThat(updates.getAllValues()).extracting(u -> u.getSubTradeNo())
                .containsExactly(subNo(1), subNo(2));
        assertThat(updates.getAllValues()).allMatch(u -> u.getStatus() == SubOrderSnapshot.STATUS_PAID);
        assertThat(updates.getAllValues()).extracting(u -> u.getApiTradeNo())
                .containsExactly("TX1", "TX2");
        var complete = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(complete.capture());
        assertThat(complete.getValue().getBizType()).isEqualTo(BizType.T_PAY);
        assertThat(complete.getValue().getState().name()).isEqualTo("S_OK");
        assertThat(complete.getValue().getBizNo()).isEqualTo(TRADE_NO);
        assertThat(complete.getValue().getApiNo()).isEqualTo("TX1");
    }

    @Test
    @DisplayName("merge_pay_status 非 FINISHED → ack success 不推进（等查询兜底）")
    void notFinishedAcked() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("WAIT_PAY",
                "[" + result(subNo(1), "", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("success");
        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("子单含非 SUCCESS → 拒收（amount_mismatch）")
    void subNotSuccessRejected() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "," + result(subNo(2), "", "FAIL") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("子单数量不符 → 拒收")
    void subCountMismatchRejected() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("未知子单号（不在库内）→ 拒收")
    void unknownSubRejected() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result("S2026081800000000000001", "TX1", "SUCCESS") + ","
                        + result(subNo(2), "TX2", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).updateSubOrder(any());
    }

    @Test
    @DisplayName("子单号重复 → 拒收")
    void duplicateSubRejected() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "," + result(subNo(1), "TX1", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("amount_mismatch");
        verify(cb, never()).updateSubOrder(any());
    }

    @Test
    @DisplayName("库内无子单记录 → 拒收（order_mismatch）")
    void noSubsRejected() throws Exception {
        var ctx = notifyCtx(signedForm(mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "]")));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(List.of());

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("order_mismatch");
        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("签名不符 → sign_error（order_detail_results 已参与签名，篡改即拒收）")
    void signErrorRejected() throws Exception {
        // 篡改：先正常签名再改 result_code，验签必然失败
        var params = mergeParams("FINISHED",
                "[" + result(subNo(1), "TX1", "SUCCESS") + "," + result(subNo(2), "TX2", "SUCCESS") + "]");
        params.put("sign", "forged-signature");
        params.put("sign_type", "RSA2");
        var sb = new StringBuilder();
        for (var e : params.entrySet())
            sb.append(e.getKey()).append("=")
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append("&");
        var ctx = notifyCtx(sb.substring(0, sb.length() - 1).getBytes(StandardCharsets.UTF_8));
        var cb = ctx.getCallback();
        when(cb.getSubOrders(any())).thenReturn(subs(15000));

        var resp = new AlipayNotifyHandler().payNotify(ctx);

        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }
}
