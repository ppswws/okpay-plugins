package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

type epayQueryResp struct {
	Code       int    `json:"code"`
	Msg        string `json:"msg"`
	TradeNo    string `json:"trade_no"`
	OutTradeNo string `json:"out_trade_no"`
	APITradeNo string `json:"api_trade_no"`
	Status     string `json:"status"`
}

func epayQuery(ctx context.Context, cfg *epayConfig, order *proto.OrderSnapshot) (*epayQueryResp, error) {
	queryURL := cfg.AppURL + "/api.php"
	q := url.Values{}
	q.Set("act", "order")
	q.Set("pid", cfg.AppID)
	q.Set("key", cfg.AppKey)
	q.Set("out_trade_no", order.GetTradeNo())
	body, _, _, err := httpClient.Do(ctx, http.MethodGet, queryURL+"?"+q.Encode(), "", "")
	if err != nil {
		return nil, err
	}
	resp := &epayQueryResp{}
	if err := json.Unmarshal([]byte(body), resp); err != nil {
		return nil, fmt.Errorf("响应解析失败: %w", err)
	}
	return resp, nil
}

func query(ctx context.Context, req *proto.InvokeContext) (*proto.QueryResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, err := epayQuery(ctx, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	if resp.Code == 1 && resp.Status == "1" {
		state = 1
	}
	return plugin.RespQuery(state, resp.APITradeNo), nil
}
