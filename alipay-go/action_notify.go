package main

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type alipayNotifyParams struct {
	OutTradeNo  string
	TradeNo     string
	TotalAmount string
	TradeStatus string
	BuyerID     string
	BuyerOpenID string
}

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	notifyParams, payload, err := parseAlipayNotify(req)
	if err != nil || notifyParams == nil || len(payload) == 0 {
		return plugin.RespHTML("fail"), nil
	}
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	ok, err := alipay.VerifySign(cfg.AppKey, payload)
	if err != nil || !ok {
		return plugin.RespHTML("fail"), nil
	}
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return plugin.RespHTML("fail"), nil
	}
	if notifyParams.OutTradeNo != order.GetTradeNo() {
		return plugin.RespHTML("fail"), nil
	}
	if toCents(notifyParams.TotalAmount) != order.GetReal() {
		return plugin.RespHTML("fail"), nil
	}
	status := strings.ToUpper(notifyParams.TradeStatus)
	if status != "TRADE_SUCCESS" && status != "TRADE_FINISHED" {
		return plugin.RespHTML("success"), nil
	}
	buyer := notifyParams.BuyerID
	if buyer == "" {
		buyer = notifyParams.BuyerOpenID
	}
	if err := plugin.CompleteBiz(ctx, plugin.CompleteBizInput{
		BizType: proto.BizType_BIZ_TYPE_ORDER,
		BizNo:   order.GetTradeNo(),
		State:   proto.BizState_BIZ_STATE_SUCCEEDED,
		ApiNo:   notifyParams.TradeNo,
		Buyer:   buyer,
	}); err != nil {
		return plugin.RespHTML("fail"), nil
	}
	return plugin.RespHTML("success"), nil
}

func parseAlipayNotify(req *proto.InvokeContext) (*alipayNotifyParams, gopay.BodyMap, error) {
	if req == nil || req.GetRequest() == nil {
		return nil, nil, fmt.Errorf("request is nil")
	}
	values := url.Values{}
	appendQueryValues(values, req.GetRequest().GetQuery())
	appendQueryValues(values, string(req.GetRequest().GetBody()))
	if len(values) == 0 {
		return nil, nil, fmt.Errorf("notify payload is empty")
	}
	payload := make(gopay.BodyMap, len(values))
	for k := range values {
		payload.Set(k, values.Get(k))
	}
	out := &alipayNotifyParams{
		OutTradeNo:  values.Get("out_trade_no"),
		TradeNo:     values.Get("trade_no"),
		TotalAmount: values.Get("total_amount"),
		TradeStatus: values.Get("trade_status"),
		BuyerID:     values.Get("buyer_id"),
		BuyerOpenID: values.Get("buyer_open_id"),
	}
	if out.OutTradeNo == "" || out.TotalAmount == "" || out.TradeStatus == "" || values.Get("sign") == "" {
		return nil, nil, fmt.Errorf("missing required notify fields")
	}
	return out, payload, nil
}

func appendQueryValues(dst url.Values, raw string) {
	if raw == "" {
		return
	}
	parsed, err := url.ParseQuery(raw)
	if err != nil {
		return
	}
	for k, vals := range parsed {
		if len(vals) == 0 {
			continue
		}
		dst.Set(k, vals[len(vals)-1])
	}
}
