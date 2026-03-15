package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const sumapayOrderQueryURL = "https://api.sumapay.com/main/SearchOrderAction_merSingleQuery"

func queryOrder(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, qerr := queryByRequestID(ctx, cfg, order.GetTradeNo())
	if qerr != nil {
		return plugin.Result(plugin.BizStateProcessing, plugin.BizOut{Code: "QUERY_ERROR", Msg: qerr.Error(), Stats: stats}), nil
	}
	state := plugin.BizStateProcessing
	msg := "交易处理中"
	switch strings.TrimSpace(resp["status"]) {
	case "2":
		state = plugin.BizStateSucceeded
		msg = "交易成功"
	case "3":
		state = plugin.BizStateFailed
		msg = "交易失败"
	}
	if state != plugin.BizStateSucceeded {
		if em := strings.TrimSpace(resp["errorCode"]); em != "" {
			msg = em
		}
	}
	return plugin.Result(state, plugin.BizOut{
		ApiNo: strings.TrimSpace(resp["tradeId"]),
		Code:  strings.TrimSpace(resp["result"]),
		Msg:   msg,
		Stats: stats,
	}), nil
}

func queryByRequestID(ctx context.Context, cfg *sumapayConfig, requestID string) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{
		"requestId":         "S" + requestID,
		"requestStartTime":  nowTradeTime(),
		"merchantCode":      cfg.MerchantCode,
		"originalRequestId": requestID,
	}
	signature, err := signRSA256(cfg.MerchantPrivateKey, concatByKeys(params, []string{"requestId", "merchantCode", "originalRequestId"}))
	if err != nil {
		return nil, plugin.RequestStats{}, fmt.Errorf("生成签名失败: %w", err)
	}
	params["signature"] = signature

	resp, stats, reqErr := postGBKJSON(ctx, sumapayOrderQueryURL, params)
	if reqErr != nil {
		return nil, stats, reqErr
	}
	verifyText := concatByKeys(resp, []string{"requestId", "result", "merchantCode", "originalRequestId", "tradeId", "tradeSum", "status", "requestTime"})
	if !verifyRSA256(cfg.FengfuPublicKey, verifyText, resp["signature"]) {
		return nil, stats, fmt.Errorf("返回结果验签失败")
	}
	if strings.TrimSpace(resp["result"]) != "00000" {
		msg := strings.TrimSpace(resp["errorCode"])
		if msg == "" {
			msg = "查询处理中"
		}
		return nil, stats, fmt.Errorf("[%s]%s", strings.TrimSpace(resp["result"]), msg)
	}
	return resp, stats, nil
}
