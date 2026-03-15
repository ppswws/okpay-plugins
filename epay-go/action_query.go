package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type epayQueryResp struct {
	Code       int    `json:"code"`
	Msg        string `json:"msg"`
	TradeNo    string `json:"trade_no"`
	OutTradeNo string `json:"out_trade_no"`
	APITradeNo string `json:"api_trade_no"`
	Buyer      string `json:"buyer"`
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
	if err = json.Unmarshal([]byte(body), resp); err != nil {
		return nil, fmt.Errorf("响应解析失败: %w", err)
	}
	return resp, nil
}

func query(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
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
	state := plugin.BizStateProcessing
	msg := "交易处理中"
	if resp.Code == 1 && resp.Status == "1" {
		state = plugin.BizStateSucceeded
		msg = "交易成功"
	}
	return &proto.BizResult{
		State: state,
		ApiNo: resp.APITradeNo,
		Buyer: resp.Buyer,
		Code:  fmt.Sprintf("%d", resp.Code),
		Msg:   msg,
	}, nil
}
