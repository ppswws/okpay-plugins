package com.okpay.plugin.alipay;

import com.okpay.plugin.ComplaintHandler;
import com.okpay.plugin.alipay.handler.AlipayComplainCore;
import org.pf4j.Extension;

/**
 * 支付宝投诉处理扩展 — 与支付扩展（{@link AlipayPlugin}）同 jar。
 *
 * <p>一个插件 jar 同时提供 PaymentChannel + ComplaintHandler 两种扩展，
 * 宿主按扩展类型分别路由（支付通道 / 投诉应用）。投诉的 kind、配置模式与认证维度
 * 由支付插件 {@code descriptor()} 中的 {@code complaint} 声明，本类只提供操作实现。</p>
 */
@Extension
public class AlipayComplainHandler extends AlipayComplainCore implements ComplaintHandler {
}
