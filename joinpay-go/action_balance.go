package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BalanceResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	balanceValue, _, err := queryBalance(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return plugin.RespBalance(balanceValue), nil
}

func queryBalance(ctx context.Context, cfg *joinpayConfig) (string, plugin.RequestStats, error) {
	params := map[string]string{"userNo": cfg.AppID}
	params["hmac"] = signJoinpay(params, joinpayBalanceRequestFields, cfg.AppKey)
	reqBodyBytes, err := json.Marshal(params)
	if err != nil {
		return "", plugin.RequestStats{}, err
	}
	reqBody := string(reqBodyBytes)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayBalanceURL, reqBody, "application/json")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return "", stats, err
	}
	respMap, err := decodeJSONAnyMap(body)
	if err != nil {
		return "", stats, fmt.Errorf("响应解析失败: %w", err)
	}
	statusCode, err := requiredStringOrNumber(respMap, "statusCode")
	if err != nil {
		return "", stats, err
	}
	if statusCode != "2001" {
		msg, _ := valueStringOrNumber(respMap, "message")
		if msg == "" {
			msg = "查询失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", statusCode, msg)
	}
	dataObj, ok := respMap["data"].(map[string]any)
	if !ok || dataObj == nil {
		return "", stats, fmt.Errorf("响应解析失败")
	}
	dataRaw := map[string]string{}
	for k, v := range dataObj {
		dataRaw[k] = toString(v)
	}
	message, _ := valueStringOrNumber(respMap, "message")
	signData := map[string]string{"statusCode": statusCode, "message": message, "hmac": dataRaw["hmac"]}
	for _, k := range joinpayBalanceResponseFields {
		signData[k] = dataRaw[k]
	}
	if !verifyJoinpay(signData, joinpayBalanceResponseSignFields, cfg.AppKey) {
		return "", stats, fmt.Errorf("返回验签失败")
	}
	if errCode := dataRaw["errorCode"]; errCode != "" {
		errDesc := dataRaw["errorDesc"]
		if errDesc == "" {
			errDesc = "查询失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", errCode, errDesc)
	}
	balance := dataRaw["useAbleSettAmount"]
	if balance == "" {
		return "", stats, fmt.Errorf("余额为空")
	}
	return balance, stats, nil
}
