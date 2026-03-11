package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	payload := notifyBodyMap(req)
	if len(payload) == 0 {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	ok, err := alipay.VerifySign(strings.TrimSpace(cfg.AppKey), payload)
	if err != nil || !ok {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	if strings.TrimSpace(payload.GetString("out_trade_no")) != order.GetTradeNo() {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	if toCents(payload.GetString("total_amount")) != order.GetReal() {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	status := strings.ToUpper(strings.TrimSpace(payload.GetString("trade_status")))
	if status != "TRADE_SUCCESS" && status != "TRADE_FINISHED" {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("success")), nil
	}
	buyer := strings.TrimSpace(payload.GetString("buyer_id"))
	if buyer == "" {
		buyer = strings.TrimSpace(payload.GetString("buyer_open_id"))
	}
	if err := plugin.CompleteOrder(ctx, plugin.CompleteOrderInput{
		TradeNo:    order.GetTradeNo(),
		APITradeNo: strings.TrimSpace(payload.GetString("trade_no")),
		Buyer:      buyer,
	}); err != nil {
		return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("fail")), nil
	}
	return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML("success")), nil
}

func pageReturn(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	payload := notifyBodyMap(req)
	if len(payload) == 0 {
		return plugin.RespError("回跳参数为空"), nil
	}
	ok, err := alipay.VerifySign(strings.TrimSpace(cfg.AppKey), payload)
	if err != nil || !ok {
		return plugin.RespError("支付宝返回验签失败"), nil
	}
	order := req.GetOrder()
	if order == nil {
		return plugin.RespError("订单不存在"), nil
	}
	if strings.TrimSpace(payload.GetString("out_trade_no")) != order.GetTradeNo() {
		return plugin.RespError("订单号校验失败"), nil
	}
	if toCents(payload.GetString("total_amount")) != order.GetReal() {
		return plugin.RespError("订单金额校验失败"), nil
	}
	site := strings.TrimRight(req.GetConfig().GetSiteDomain(), "/")
	if site == "" {
		return plugin.RespPage("ok"), nil
	}
	return plugin.RespJump(fmt.Sprintf("%s/pay/ok/%s", site, order.GetTradeNo())), nil
}

func notifyBodyMap(req *proto.InvokeContext) gopay.BodyMap {
	out := make(gopay.BodyMap)
	if req == nil || req.GetRequest() == nil {
		return out
	}
	for k, v := range parseQueryString(req.GetRequest().GetQuery()) {
		out.Set(k, v)
	}
	body := strings.TrimSpace(string(req.GetRequest().GetBody()))
	if body != "" {
		for k, v := range parseQueryString(body) {
			out.Set(k, v)
		}
	}
	return out
}
