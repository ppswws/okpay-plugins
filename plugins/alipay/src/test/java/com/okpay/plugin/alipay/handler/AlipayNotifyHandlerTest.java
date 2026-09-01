package com.okpay.plugin.alipay.handler;

import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.CompleteBizRequest;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RefundSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.SubOrderSnapshot;
import com.okpay.plugin.model.UpdateSubOrderRequest;
import com.okpay.plugin.sdk.AlipayOpenApiClient;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

/**
 * 支付通知金额校验 fail-closed；
 * 合单退款通知：首个子单成功不终态，全子单退完才推进主退款单，重发幂等，金额缺失不推进。
 *
 * <p>真实密钥 + 真实验签（RSA2 通知签名走真实公钥校验）。</p>
 */
@DisplayName("AlipayNotifyHandler 支付通知/合单退款通知")
class AlipayNotifyHandlerTest {

    private static final String APP_ID = "2021000000000001";
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

    // =========================================================================
    // 证书模式（authMode=cert）：通知验签密钥取自支付宝公钥证书
    // =========================================================================

    private static String loadResource(String name) throws Exception {
        try (var in = AlipayNotifyHandlerTest.class.getResourceAsStream("/certs/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 证书模式配置：认证维度 authMode=cert + 三证书（测试资源，openssl 生成） */
    private static byte[] certCfgRaw() throws Exception {
        return HttpHelper.MAPPER.writeValueAsBytes(Map.of(
                "appid", APP_ID,
                "appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()),
                "authMode", "cert",
                "app_cert", loadResource("alipay_app_cert.pem"),
                "alipay_cert", loadResource("alipay_cert.pem"),
                "root_cert", loadResource("alipay_root_cert.pem")));
    }

    /** 证书模式通知体：以支付宝证书对应私钥签名（验签公钥 = 支付宝公钥证书公钥） */
    private static byte[] certSignedForm(LinkedHashMap<String, String> params) throws Exception {
        var alipayKey = RsaKeys.loadPrivateKey(loadResource("alipay.key"));
        params.put("sign", RsaKeys.sign(
                AlipayOpenApiClient.signContentForVerify(params), alipayKey));
        params.put("sign_type", "RSA2");
        var sb = new StringBuilder();
        for (var e : params.entrySet())
            sb.append(e.getKey()).append("=")
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append("&");
        return sb.substring(0, sb.length() - 1).getBytes(StandardCharsets.UTF_8);
    }

    private InvokeContext certNotifyCtx(long real, byte[] body, HostCallback cb) throws Exception {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(certCfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").real(real).build())
                .request(RequestSnapshot.builder().body(body).build())
                .callback(cb)
                .build();
    }

    @Test
    @DisplayName("证书模式支付通知：支付宝证书私钥签名 → 验签通过走完成链路")
    void payNotifyCertModeCompletesOrder() throws Exception {
        var cb = mock(HostCallback.class);
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", "T1");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1.00");
        params.put("trade_no", "ALI_CERT_NO");
        params.put("buyer_id", "BUYER1");
        var ctx = certNotifyCtx(100L, certSignedForm(params), cb);

        var resp = new AlipayNotifyHandler().payNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getApiNo()).isEqualTo("ALI_CERT_NO");
    }

    @Test
    @DisplayName("证书模式支付通知：篡改金额 → 验签拒绝（sign_error，不推进订单）")
    void payNotifyCertModeRejectsTampered() throws Exception {
        var cb = mock(HostCallback.class);
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "0.01");
        params.put("trade_no", "ALI_CERT_NO");
        // 先按 0.01 真实签名，再改表单体金额为 999.00（不重签）→ 验签必然失败
        var body = new String(certSignedForm(params), StandardCharsets.UTF_8)
                .replace("total_amount=0.01", "total_amount=999.00")
                .getBytes(StandardCharsets.UTF_8);
        var ctx = certNotifyCtx(100L, body, cb);

        var resp = new AlipayNotifyHandler().payNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("sign_error");
        verify(cb, never()).completeBiz(any());
    }

    private InvokeContext notifyCtx(long real, byte[] body, HostCallback cb) throws Exception {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .order(OrderSnapshot.builder().tradeNo("T1").real(real).build())
                .request(RequestSnapshot.builder().body(body).build())
                .callback(cb)
                .build();
    }

    /** 构造 urlencoded 通知体：RSA2 签名走真实私钥（验签按官方规则剔除 sign/sign_type）。
     *  值必须 URL 编码：签名 base64 含 '+'，服务端 URLDecoder 会将其还原为 '+'。 */
    private static byte[] signedForm(LinkedHashMap<String, String> params) throws Exception {
        params.put("sign", RsaKeys.sign(
                AlipayOpenApiClient.signContentForVerify(params), KP.getPrivate()));
        params.put("sign_type", "RSA2");
        var sb = new StringBuilder();
        for (var e : params.entrySet())
            sb.append(e.getKey()).append("=")
                    .append(java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append("&");
        return sb.substring(0, sb.length() - 1).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("支付通知：走完成链路")
    void payNotifyCompletesOrder() throws Exception {
        var cb = mock(HostCallback.class);
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", "T1");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1.00");
        params.put("trade_no", "ALI_NO");
        params.put("buyer_id", "BUYER1");
        var ctx = notifyCtx(100L, signedForm(params), cb);

        var resp = new AlipayNotifyHandler().payNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getApiNo()).isEqualTo("ALI_NO");
        verify(cb, never()).lockOrderExt(any());
    }

    @Test
    @DisplayName("支付通知 out_trade_no 与本地订单不符 → 拒收（验签不保证回调就是本地订单）")
    void payNotifyOutTradeNoMismatchRejected() throws Exception {
        var cb = mock(HostCallback.class);
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", "OTHER_ORDER");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1.00");
        params.put("trade_no", "ALI_NO");
        var ctx = notifyCtx(100L, signedForm(params), cb);

        var resp = new AlipayNotifyHandler().payNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("order_mismatch");
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("buyer_id 缺失回退 buyer_open_id（新版接口返回应用级标识）")
    void payNotifyFallsBackToBuyerOpenId() throws Exception {
        var cb = mock(HostCallback.class);
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", "T1");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "1.00");
        params.put("trade_no", "ALI_NO");
        params.put("buyer_open_id", "OPENID1");
        var ctx = notifyCtx(100L, signedForm(params), cb);

        var resp = new AlipayNotifyHandler().payNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBuyer()).isEqualTo("OPENID1");
    }

    // =========================================================================
    // 合单退款通知（B2：对称终态——全子单退完才推进主退款单，通知重发幂等）
    // =========================================================================

    /** 合单退款通知 ctx：R 前缀退款单 + 有子单的主订单 */
    private InvokeContext refundNotifyCtx(LinkedHashMap<String, String> params, HostCallback cb) throws Exception {
        return InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw()).build())
                .refund(RefundSnapshot.builder().refundNo("R1").tradeNo("T1").amount(30000).build())
                .request(RequestSnapshot.builder().body(signedForm(params)).build())
                .callback(cb)
                .build();
    }

    private static LinkedHashMap<String, String> refundNotifyParams(String subTradeNo, String outRequestNo, String fee) {
        var params = new LinkedHashMap<String, String>();
        params.put("app_id", APP_ID);
        params.put("out_trade_no", subTradeNo);
        params.put("out_request_no", outRequestNo);
        params.put("refund_status", "REFUND_SUCCESS");
        params.put("refund_fee", fee);
        params.put("trade_no", "TX1");
        return params;
    }

    private static List<SubOrderSnapshot> twoSubs(long refunded1, long refunded2) {
        return List.of(
                SubOrderSnapshot.builder().subTradeNo("S1").tradeNo("T1").money(15000).refundMoney(refunded1).build(),
                SubOrderSnapshot.builder().subTradeNo("S2").tradeNo("T1").money(15000).refundMoney(refunded2).build());
    }

    @Test
    @DisplayName("合单退款通知：首个子单成功 → 累计子单但主退款单不终态")
    void combineRefundNotifyFirstSubNotFinal() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.getSubOrders(any())).thenReturn(twoSubs(0, 0)).thenReturn(twoSubs(15000, 0));
        var ctx = refundNotifyCtx(refundNotifyParams("S1", "R1_1", "150.00"), cb);

        var resp = new AlipayNotifyHandler().refundNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        // 子单累计一次
        var captor = ArgumentCaptor.forClass(UpdateSubOrderRequest.class);
        verify(cb).updateSubOrder(captor.capture());
        assertThat(captor.getValue().getSubTradeNo()).isEqualTo("S1");
        assertThat(captor.getValue().getRefundDelta()).isEqualTo(15000);
        // 尚未全退完 → 主退款单不终态
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("合单退款通知：全子单退完 → 推进主退款单终态")
    void combineRefundNotifyAllSubsFinal() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.getSubOrders(any())).thenReturn(twoSubs(15000, 0)).thenReturn(twoSubs(15000, 15000));
        var ctx = refundNotifyCtx(refundNotifyParams("S2", "R1_2", "150.00"), cb);

        var resp = new AlipayNotifyHandler().refundNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        verify(cb).updateSubOrder(any());
        var captor = ArgumentCaptor.forClass(CompleteBizRequest.class);
        verify(cb).completeBiz(captor.capture());
        assertThat(captor.getValue().getBizType()).isEqualTo(com.okpay.plugin.enums.BizType.T_REF);
        assertThat(captor.getValue().getState()).isEqualTo(com.okpay.plugin.enums.BizState.S_OK);
        assertThat(captor.getValue().getApiNo()).isEqualTo("TX1");
    }

    @Test
    @DisplayName("合单退款通知重发：子单已到额 → 超限被拒（幂等）；部分退完不推进终态")
    void combineRefundNotifyResendIdempotent() throws Exception {
        var cb = mock(HostCallback.class);
        // 子单1已退满、子单2未退：重发子单1通知 → 累计超限被拒，且未全退完 → 不推进主退款单
        when(cb.getSubOrders(any())).thenReturn(twoSubs(15000, 0));
        doThrow(new IllegalStateException("子单退款累计超限"))
                .when(cb).updateSubOrder(any());
        var ctx = refundNotifyCtx(refundNotifyParams("S1", "R1_1", "150.00"), cb);

        var resp = new AlipayNotifyHandler().refundNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");
        // 超限被拒且未全退完 → 不触发 completeBiz（等子单2通知/查单续退）
        verify(cb, never()).completeBiz(any());
    }

    @Test
    @DisplayName("合单退款通知金额缺失/非法 → 不累计不推进（靠查单兜底）")
    void combineRefundNotifyMissingFee() throws Exception {
        var cb = mock(HostCallback.class);
        when(cb.getSubOrders(any())).thenReturn(twoSubs(0, 0));
        var params = refundNotifyParams("S1", "R1_1", "");
        var ctx = refundNotifyCtx(params, cb);

        var resp = new AlipayNotifyHandler().refundNotify(ctx);
        assertThat(resp.getDataText()).isEqualTo("success");

        verify(cb, never()).updateSubOrder(any());
        verify(cb, never()).completeBiz(any());
    }
}
