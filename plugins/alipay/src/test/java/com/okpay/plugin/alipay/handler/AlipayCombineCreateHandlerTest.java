package com.okpay.plugin.alipay.handler;

import tools.jackson.core.type.TypeReference;
import com.okpay.plugin.HostCallback;
import com.okpay.plugin.model.ChannelSnapshot;
import com.okpay.plugin.model.ConfigSnapshot;
import com.okpay.plugin.model.InvokeContext;
import com.okpay.plugin.model.LockExtResult;
import com.okpay.plugin.model.OrderSnapshot;
import com.okpay.plugin.model.RequestSnapshot;
import com.okpay.plugin.model.SaveSubOrdersRequest;
import com.okpay.plugin.sdk.HttpHelper;
import com.okpay.plugin.sdk.RsaKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.net.URLDecoder;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlipayCreateHandler 合单拆单（直付通 merge）：达阈值 → merge.precreate（锁内落库）
 * → wap/app.merge.pay 两段式；未达阈值/未开启 → 单笔路径不落库。
 */
@DisplayName("AlipayCreateHandler 合单拆单")
class AlipayCombineCreateHandlerTest {

    private static final String APP_ID = "2021000000000001";
    private static final String TRADE_NO = "P202608181200000000001";
    private static final String PRE_ORDER_NO = "PRE_ORDER_202608180001";
    private static final String UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

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

    /** 通道配置：合单开关 + 支付方式 + 直付通 SMID（可空=直连）；金额参数走全局 ConfigSnapshot 注入 */
    private static byte[] cfgRaw(String biztype, boolean combineOn, String smids) throws Exception {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("appid", APP_ID);
        cfg.put("appsecret", pem("PRIVATE KEY", KP.getPrivate().getEncoded()));
        cfg.put("appkey", pem("PUBLIC KEY", KP.getPublic().getEncoded()));
        cfg.put("biztype", biztype);
        if (combineOn) cfg.put("alicombine_open", true);
        if (smids != null) cfg.put("appmchid", smids);
        return HttpHelper.MAPPER.writeValueAsBytes(cfg);
    }

    private InvokeContext ctx(long real, byte[] cfgRaw, String ua) throws Exception {
        var b = InvokeContext.builder()
                .requestId("r1")
                .channel(ChannelSnapshot.builder().cfgRaw(cfgRaw).build())
                .order(OrderSnapshot.builder().tradeNo(TRADE_NO).real(real).build())
                .callback(mock(HostCallback.class))
                .config(ConfigSnapshot.builder().siteDomain("https://pay.example.com")
                        .notifyDomain("https://pay.example.com").goodsName("合单测试商品")
                        // 全局合单金额：起拆 100 元、单笔上限 200 元
                        .combineAlipayMinMoneyCents(10000).combineAlipaySubMoneyCents(20000).build());
        if (ua != null) b.request(RequestSnapshot.builder().ua(ua).build());
        return b.build();
    }

    private static void letRealUaChecks(MockedStatic<HttpHelper> mocked) {
        mocked.when(() -> HttpHelper.isAlipay(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isMobile(any())).thenCallRealMethod();
        mocked.when(() -> HttpHelper.isWeChat(any())).thenCallRealMethod();
    }

    /** 构造带响应签名的支付宝响应 body（响应验签走真实公钥） */
    private static byte[] signedResponse(String nodeName, String nodeJson) throws Exception {
        var sign = RsaKeys.sign(nodeJson, KP.getPrivate());
        return ("{\"" + nodeName + "\":" + nodeJson + ",\"sign\":\"" + sign + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 从请求表单体解析 biz_content */
    private static Map<String, Object> parseBizContent(String formBody) throws Exception {
        var params = new LinkedHashMap<String, String>();
        for (var pair : formBody.split("&")) {
            var idx = pair.indexOf("=");
            if (idx > 0) params.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return HttpHelper.MAPPER.readValue(params.get("biz_content"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** 模拟支付宝 App 侧：orderSuffix 是双层 urlencode，解码一层还原唤起 orderStr */
    private static String orderStrFrom(String schemeUrl) throws Exception {
        var decoded = URLDecoder.decode(schemeUrl, StandardCharsets.UTF_8);
        var suffix = decoded.substring(decoded.indexOf("orderSuffix=") + 12);
        suffix = suffix.substring(0, suffix.indexOf("#Intent"));
        return URLDecoder.decode(suffix, StandardCharsets.UTF_8);
    }

    private static String subNo(int idx) {
        return com.okpay.plugin.sdk.Sdk.subTradeNo(TRADE_NO, idx);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> orderDetails(Map<String, Object> biz) {
        return (List<Map<String, Object>>) biz.get("order_details");
    }

    // =========================================================================
    // WAP 合单（两段式）
    // =========================================================================

    @Test
    @DisplayName("达阈值 WAP：merge.precreate 落库 → wap.merge.pay 表单（仅 1 次 HTTP）")
    void wapCombineSplitsAndSaves() throws Exception {
        // real=30000 分（300 元）≥ minmoney=100 元；SMID=2 → 起 2 单各 15000 ≤ submoney=200 元
        var ctx = ctx(30000, cfgRaw("2", true, "SMID1,SMID2"), UA_IPHONE);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"pre_order_no\":\"" + PRE_ORDER_NO + "\"}"),
                            "req", 10, 1));

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("html");
            assertThat(resp.getDataText()).contains("alipay.trade.wap.merge.pay");
            // 两段式第二段为纯 HTML 构建：全流程只有 merge.precreate 一次 HTTP
            assertThat(bodyCaptor.getAllValues()).hasSize(1);
            var saved = ArgumentCaptor.forClass(SaveSubOrdersRequest.class);
            verify(cb).saveSubOrders(saved.capture());
            assertThat(saved.getValue().getTradeNo()).isEqualTo(TRADE_NO);
            assertThat(saved.getValue().getItems()).hasSize(2);
            assertThat(saved.getValue().getItems().get(0).getSubTradeNo()).isEqualTo(subNo(1));
            assertThat(saved.getValue().getItems().get(0).getMoney()).isEqualTo(15000);
            assertThat(saved.getValue().getItems().get(1).getSubTradeNo()).isEqualTo(subNo(2));
            assertThat(saved.getValue().getItems().get(1).getMoney()).isEqualTo(15000);
        }
    }

    @Test
    @DisplayName("合单报文结构：子单号/金额（元）/商品 + notify_url")
    void wapMergeBodyStructure() throws Exception {
        var ctx = ctx(30000, cfgRaw("2", true, "SMID1,SMID2"), UA_IPHONE);
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"pre_order_no\":\"" + PRE_ORDER_NO + "\"}"),
                            "req", 10, 1));

            new AlipayCreateHandler().alipay(ctx);

            var form = bodyCaptor.getValue();
            assertThat(form).contains("notify_url=https%3A%2F%2Fpay.example.com%2Fpay%2Fnotify%2F" + TRADE_NO);
            var biz = parseBizContent(form);
            assertThat(biz).containsEntry("out_merge_no", TRADE_NO)
                    .doesNotContainKey("out_trade_no");
            var details = orderDetails(biz);
            assertThat(details).hasSize(2);
            // 金额是元字符串：toCents 换算后合计=主单实付（与落库子单金额一致）
            long total = 0;
            for (int i = 0; i < 2; i++) {
                var d = details.get(i);
                assertThat(d.get("app_id")).isEqualTo(APP_ID);
                assertThat(d.get("out_trade_no")).isEqualTo(subNo(i + 1));
                assertThat(d.get("product_code")).isEqualTo("QUICK_WAP_WAY");
                assertThat(d.get("total_amount")).isEqualTo("150.00");
                assertThat(d.get("subject")).isEqualTo("合单测试商品");
                total += com.okpay.plugin.sdk.PaymentUtils.toCents((String) d.get("total_amount"));
            }
            assertThat(total).isEqualTo(30000);
        }
    }

    @Test
    @DisplayName("多 SMID 轮询：子单带 sub_merchant.merchant_id + settle_info（defaultSettle 结算进件默认账号）")
    void smidRoundRobin() throws Exception {
        var ctx = ctx(30000, cfgRaw("2", true, "SMID1,SMID2"), UA_IPHONE);
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"pre_order_no\":\"" + PRE_ORDER_NO + "\"}"),
                            "req", 10, 1));

            new AlipayCreateHandler().alipay(ctx);

            var details = orderDetails(parseBizContent(bodyCaptor.getValue()));
            @SuppressWarnings("unchecked")
            var sub1 = (Map<String, Object>) details.get(0).get("sub_merchant");
            @SuppressWarnings("unchecked")
            var sub2 = (Map<String, Object>) details.get(1).get("sub_merchant");
            assertThat(sub1).containsEntry("merchant_id", "SMID1");
            assertThat(sub2).containsEntry("merchant_id", "SMID2");
            @SuppressWarnings("unchecked")
            var settle1 = (Map<String, Object>) details.get(0).get("settle_info");
            assertThat(settle1).containsEntry("settle_period_time", "1d");
            @SuppressWarnings("unchecked")
            var infos1 = (List<Map<String, Object>>) settle1.get("settle_detail_infos");
            assertThat(infos1).hasSize(1);
            // 结算到商户进件默认账号：trans_in_type=defaultSettle，trans_in 留空（SMID 仅作 sub_merchant.merchant_id）
            assertThat(infos1.get(0)).containsEntry("trans_in_type", "defaultSettle")
                    .containsEntry("amount", "150.00")
                    .doesNotContainKey("trans_in")
                    .doesNotContainKey("summary_dimension");
        }
    }

    @Test
    @DisplayName("未配置 SMID（非直付通）→ 不拆单，单笔 wap.pay（无 merge HTTP + 不落库）")
    void noSmidStaysSingle() throws Exception {
        // real=30000 分（300 元）≥ minmoney=100 元，但无 SMID（直连/ISV）→ 合单不可用，走单笔
        var ctx = ctx(30000, cfgRaw("2", true, null), UA_IPHONE);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("html");
            assertThat(resp.getDataText()).contains("alipay.trade.wap.pay");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("直付通单笔（未达合单阈值）：sub_merchant + settle_info 注入单笔支付（defaultSettle/账期1d）")
    void directPaySingleCarriesSettleInfo() throws Exception {
        // real=5000 分（50 元）< minmoney=100 元，SMID 已配 → 走单笔 app.pay，但直付通结算参数仍须注入
        var ctx = ctx(5000, cfgRaw("5", true, "SMID1"), UA_PC);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getPage()).isEqualTo("alipay_h5");
            var data = HttpHelper.MAPPER.readValue(resp.getDataRaw(), LinkedHashMap.class);
            var orderStr = orderStrFrom(data.get("url").toString());
            assertThat(orderStr).contains("method=alipay.trade.app.pay");
            var biz = parseBizContent(orderStr);
            assertThat(biz.get("sub_merchant")).isEqualTo(Map.of("merchant_id", "SMID1"));
            @SuppressWarnings("unchecked")
            var settle = (Map<String, Object>) biz.get("settle_info");
            assertThat(settle).containsEntry("settle_period_time", "1d");
            @SuppressWarnings("unchecked")
            var infos = (List<Map<String, Object>>) settle.get("settle_detail_infos");
            assertThat(infos.get(0)).containsEntry("trans_in_type", "defaultSettle")
                    .containsEntry("amount", "50.00");
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("未达阈值 → 单笔 wap.pay（pageExecute 无 HTTP）+ 不落库")
    void belowThresholdStaysSingle() throws Exception {
        // real=5000 分（50 元）< minmoney=100 元
        var ctx = ctx(5000, cfgRaw("2", true, "SMID1"), UA_IPHONE);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("html");
            assertThat(resp.getDataText()).contains("alipay.trade.wap.pay");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("合单未开启 → 单笔路径")
    void combineOffStaysSingle() throws Exception {
        var ctx = ctx(30000, cfgRaw("2", false, "SMID1"), UA_IPHONE);
        when(ctx.getCallback().lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getDataText()).contains("alipay.trade.wap.pay");
            verify(ctx.getCallback(), never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("merge.precreate 被拒 → 错误响应且不落库")
    void precreateRejectedFails() throws Exception {
        var ctx = ctx(30000, cfgRaw("2", true, "SMID1"), UA_IPHONE);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"40004\",\"msg\":\"Business Failed\","
                                            + "\"sub_code\":\"INVALID_PARAMETER\",\"sub_msg\":\"订单信息不存在\"}"),
                            "req", 10, 1));

            // 锁内渠道拒绝：error 载荷只记轨迹不写 ext，lockCreate 还原为 error 页（业务失败不外抛）
            var resp = new AlipayCreateHandler().alipay(ctx);
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).contains("订单信息不存在");
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("merge.precreate 成功但缺 pre_order_no → 失败且不落库（拒绝静默空预订单号）")
    void precreateMissingPreOrderNoFails() throws Exception {
        var ctx = ctx(30000, cfgRaw("2", true, "SMID1"), UA_IPHONE);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            mocked.when(() -> HttpHelper.post(any(), any(), any(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\"}"),
                            "req", 10, 1));

            // 无 pre_order_no 无法进入第二段（wap/app.merge.pay 依赖它）：失败且不落库
            var resp = new AlipayCreateHandler().alipay(ctx);
            assertThat(resp.getType()).isEqualTo("error");
            assertThat(resp.getMsg()).contains("pre_order_no");
            verify(cb, never()).saveSubOrders(any());
        }
    }

    // =========================================================================
    // APP 合单（两段式，锁内执行）
    // =========================================================================

    @Test
    @DisplayName("APP 合单：merge.precreate 落库 → app.merge.pay 唤起串 → alipay_h5（进锁）")
    void appCombineRendersScheme() throws Exception {
        var ctx = ctx(30000, cfgRaw("5", true, "SMID1,SMID2"), UA_PC);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpHelper.post(any(), any(), bodyCaptor.capture(), any()))
                    .thenAnswer(inv -> new HttpHelper.HttpResponse(200, Map.of(),
                            signedResponse("alipay_trade_merge_precreate_response",
                                    "{\"code\":\"10000\",\"msg\":\"Success\",\"pre_order_no\":\"" + PRE_ORDER_NO + "\"}"),
                            "req", 10, 1));

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getType()).isEqualTo("page");
            assertThat(resp.getPage()).isEqualTo("alipay_h5");
            var data = HttpHelper.MAPPER.readValue(resp.getDataRaw(), LinkedHashMap.class);
            var orderStr = orderStrFrom(data.get("url").toString());
            assertThat(orderStr).contains("method=alipay.trade.app.merge.pay");
            // 第二段只凭 pre_order_no 唤起：biz_content 无 order_details（明细在 precreate 已提交）
            var biz = parseBizContent(orderStr);
            assertThat(biz).containsOnlyKeys("pre_order_no")
                    .containsEntry("pre_order_no", PRE_ORDER_NO);
            // 仅 precreate 一次 HTTP；落库子单与报文一致
            assertThat(bodyCaptor.getAllValues()).hasSize(1);
            verify(cb).saveSubOrders(any());
            // 合单 APP 与单笔 APP 契约不同：precreate 有 HTTP 轨迹，须进锁防重入
            // （lockCreate 内部 probe 读 + 成功写 = 2 次 lockOrderExt）
            verify(cb, org.mockito.Mockito.atLeastOnce()).lockOrderExt(any());
        }
    }

    @Test
    @DisplayName("APP 合单：ext 已存在（锁内缓存命中）→ 不重发渠道、不重复落库")
    void appExtShortCircuitSkipsNetwork() throws Exception {
        var ctx = ctx(30000, cfgRaw("5", true, "SMID1"), UA_PC);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder()
                .extData(Map.of("type", "page", "page", "alipay_h5", "url", "alipays://cached")).build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getPage()).isEqualTo("alipay_h5");
            assertThat(resp.getUrl()).isEqualTo("alipays://cached");
            mocked.verify(() -> HttpHelper.post(any(), any(), any(), any()), never());
            verify(cb, never()).saveSubOrders(any());
        }
    }

    @Test
    @DisplayName("APP 未达阈值 → 单笔 app.pay（lockCreate 之外，不写 ext）")
    void appBelowThresholdStaysSingle() throws Exception {
        var ctx = ctx(5000, cfgRaw("5", true, "SMID1"), UA_PC);
        var cb = ctx.getCallback();
        when(cb.lockOrderExt(any())).thenReturn(LockExtResult.builder().build());

        try (MockedStatic<HttpHelper> mocked = mockStatic(HttpHelper.class)) {
            letRealUaChecks(mocked);

            var resp = new AlipayCreateHandler().alipay(ctx);

            assertThat(resp.getPage()).isEqualTo("alipay_h5");
            var data = HttpHelper.MAPPER.readValue(resp.getDataRaw(), LinkedHashMap.class);
            assertThat(orderStrFrom(data.get("url").toString())).contains("method=alipay.trade.app.pay");
            verify(cb, never()).saveSubOrders(any());
            verify(cb, never()).lockOrderExt(any());
        }
    }
}
