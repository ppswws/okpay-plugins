package main

import (
	"context"
	"fmt"
	"net/http"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func query(ctx context.Context, req *proto.InvokeContext) (*proto.QueryResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, _, err := queryOrder(ctx, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	switch resp["ra_Status"] {
	case "100":
		state = 1
	case "101":
		state = -1
	}
	return plugin.RespQuery(state, resp["r5_TrxNo"]), nil
}

func queryOrder(ctx context.Context, cfg *joinpayConfig, order *proto.OrderSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{"p0_Version": "2.6", "p1_MerchantNo": cfg.AppID, "p2_OrderNo": order.GetTradeNo()}
	params["hmac"] = signJoinpay(params, joinpayQueryRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayQueryURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, err := decodeJSONStringMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if !verifyJoinpay(respStr, joinpayQueryResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	return respStr, stats, nil
}
