package main

import (
	"context"
	"fmt"
	"net/url"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type epayNotifyParams struct {
	PID         string
	TradeNo     string
	OutTradeNo  string
	Type        string
	Name        string
	Money       string
	TradeStatus string
	Param       string
	Sign        string
	SignType    string
	Buyer       string
}

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return plugin.RespHTML("order_mismatch"), nil
	}
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespHTML("config_error"), nil
	}
	n, err := parseEpayNotify(req)
	if err != nil {
		return plugin.RespHTML("invalid_notify_params"), nil
	}
	if !verifyMD5(n.toSignMap(), cfg.AppKey) {
		return plugin.RespHTML("sign_error"), nil
	}
	if n.TradeStatus != "TRADE_SUCCESS" {
		return plugin.RespHTML("trade_status_invalid"), nil
	}
	if n.OutTradeNo != order.GetTradeNo() {
		return plugin.RespHTML("order_mismatch"), nil
	}
	if order.GetReal() != toCents(n.Money) {
		return plugin.RespHTML("amount_mismatch"), nil
	}
	queryResp, err := epayQuery(ctx, cfg, order)
	if err != nil {
		return plugin.RespHTML("query_error"), nil
	}
	if queryResp.Code != 1 || queryResp.Status != "1" {
		return plugin.RespHTML("query_unpaid"), nil
	}
	if err := plugin.CompleteBiz(ctx, plugin.CompleteBizInput{
		BizType: proto.BizType_BIZ_TYPE_ORDER,
		BizNo:   order.GetTradeNo(),
		State:   proto.BizState_BIZ_STATE_SUCCEEDED,
		ApiNo:   n.TradeNo,
		Buyer:   n.Buyer,
	}); err != nil {
		return plugin.RespHTML("complete_error"), nil
	}
	return plugin.RespHTML("success"), nil
}

func parseEpayNotify(req *proto.InvokeContext) (*epayNotifyParams, error) {
	if req == nil || req.GetRequest() == nil {
		return nil, fmt.Errorf("request is nil")
	}
	values, err := url.ParseQuery(req.GetRequest().GetQuery())
	if err != nil || len(values) == 0 {
		return nil, fmt.Errorf("query is empty")
	}
	out := &epayNotifyParams{
		PID:         values.Get("pid"),
		TradeNo:     values.Get("trade_no"),
		OutTradeNo:  values.Get("out_trade_no"),
		Type:        values.Get("type"),
		Name:        values.Get("name"),
		Money:       values.Get("money"),
		TradeStatus: values.Get("trade_status"),
		Param:       values.Get("param"),
		Sign:        values.Get("sign"),
		SignType:    values.Get("sign_type"),
		Buyer:       values.Get("buyer"),
	}
	if out.PID == "" || out.TradeNo == "" || out.OutTradeNo == "" || out.Type == "" || out.Name == "" ||
		out.Money == "" || out.TradeStatus == "" || out.Sign == "" || out.SignType == "" {
		return nil, fmt.Errorf("missing required fields")
	}
	return out, nil
}

func (p *epayNotifyParams) toSignMap() map[string]string {
	if p == nil {
		return map[string]string{}
	}
	return map[string]string{
		"pid":          p.PID,
		"trade_no":     p.TradeNo,
		"out_trade_no": p.OutTradeNo,
		"type":         p.Type,
		"name":         p.Name,
		"money":        p.Money,
		"trade_status": p.TradeStatus,
		"param":        p.Param,
		"sign":         p.Sign,
		"sign_type":    p.SignType,
	}
}
