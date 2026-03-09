package main

import (
	"context"
	"fmt"
	"net/url"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
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
	result := "success"
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		result = "order_mismatch"
	} else if cfg, err := readConfig(req); err != nil {
		result = "config_error"
	} else if notifyParams, err := parseEpayNotify(req); err != nil {
		result = "invalid_notify_params"
	} else if !verifyMD5(notifyParams.toSignMap(), cfg.AppKey) {
		result = "sign_error"
	} else if notifyParams.TradeStatus != "TRADE_SUCCESS" {
		result = "trade_status_invalid"
	} else if notifyParams.OutTradeNo != order.GetTradeNo() {
		result = "order_mismatch"
	} else if order.GetReal() != toCents(notifyParams.Money) {
		result = "amount_mismatch"
	} else if queryResp, err := epayQuery(ctx, cfg, order); err != nil {
		result = "query_error"
	} else if queryResp.Code != 1 || queryResp.Status != "1" {
		result = "query_unpaid"
	} else if err := plugin.CompleteOrder(ctx, plugin.CompleteOrderInput{
		TradeNo:    order.GetTradeNo(),
		APITradeNo: queryResp.APITradeNo,
		Buyer:      notifyParams.Buyer,
	}); err != nil {
		result = "complete_error"
	}
	return plugin.RecordNotify(ctx, req, plugin.BizTypeOrder, plugin.RespHTML(result)), nil
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
