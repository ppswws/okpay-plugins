package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func queryOrder(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, queryErr := queryOrderFromAPI(ctx, cfg, order)
	if queryErr != nil {
		return bizResultByState(plugin.BizStateProcessing, plugin.BizOut{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := plugin.BizStateProcessing
	msg := "交易处理中"
	switch resp["ra_Status"] {
	case "100":
		state = plugin.BizStateSucceeded
		msg = "交易成功"
	case "101":
		state = plugin.BizStateFailed
		msg = "交易失败"
	}
	return bizResultByState(state, plugin.BizOut{
		ApiNo: resp["r5_TrxNo"],
		Buyer: resp["rd_OpenId"],
		Code:  resp["rb_Code"],
		Msg:   msg,
		Stats: stats,
	}), nil
}

func queryRefund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return nil, fmt.Errorf("退款单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, queryErr := queryRefundFromAPI(ctx, cfg, refund)
	if queryErr != nil {
		return bizResultByState(plugin.BizStateProcessing, plugin.BizOut{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := plugin.BizStateProcessing
	msg := "退款处理中"
	switch resp["ra_Status"] {
	case "100":
		state = plugin.BizStateSucceeded
		msg = "退款成功"
	case "101":
		state = plugin.BizStateFailed
		msg = "退款失败"
	default:
		if m := strings.TrimSpace(resp["rc_CodeMsg"]); m != "" {
			msg = m
		}
	}
	return bizResultByState(state, plugin.BizOut{
		ApiNo: resp["r4_RefundTrxNo"],
		Code:  resp["rb_Code"],
		Msg:   msg,
		Stats: stats,
	}), nil
}

func queryTransfer(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	transfer := req.GetTransfer()
	if transfer == nil || transfer.GetTradeNo() == "" {
		return nil, fmt.Errorf("代付单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, queryErr := queryTransferFromAPI(ctx, cfg, transfer)
	if queryErr != nil {
		return bizResultByState(plugin.BizStateProcessing, plugin.BizOut{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := plugin.BizStateProcessing
	msg := "代付处理中"
	switch resp["status"] {
	case "205":
		state = plugin.BizStateSucceeded
		msg = "代付成功"
	case "204", "208", "214":
		state = plugin.BizStateFailed
		msg = "代付失败"
	default:
		if m := strings.TrimSpace(resp["errorDesc"]); m != "" {
			msg = m
		}
	}
	return bizResultByState(state, plugin.BizOut{
		ApiNo: resp["platformSerialNo"],
		Code:  resp["errorCode"],
		Msg:   msg,
		Stats: stats,
	}), nil
}

func queryOrderFromAPI(ctx context.Context, cfg *joinpayConfig, order *proto.OrderSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{"p0_Version": "2.6", "p1_MerchantNo": cfg.AppID, "p2_OrderNo": order.GetTradeNo()}
	params["hmac"] = signJoinpay(params, joinpayQueryRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayQueryURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, decodeErr := decodeJSONStringMap(body)
	switch {
	case decodeErr != nil:
		return nil, stats, fmt.Errorf("响应解析失败: %w", decodeErr)
	case !verifyJoinpay(respStr, joinpayQueryResponseFields, cfg.AppKey):
		return nil, stats, fmt.Errorf("返回验签失败")
	default:
		return respStr, stats, nil
	}
}

func queryRefundFromAPI(ctx context.Context, cfg *joinpayConfig, refund *proto.RefundSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{"p0_Version": "2.3", "p1_MerchantNo": cfg.AppID, "p2_RefundOrderNo": refund.GetRefundNo()}
	params["hmac"] = signJoinpay(params, joinpayRefundQueryRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayRefundQueryURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, decodeErr := decodeJSONStringMap(body)
	switch {
	case decodeErr != nil:
		return nil, stats, fmt.Errorf("响应解析失败: %w", decodeErr)
	case !verifyJoinpay(respStr, joinpayRefundQueryResponseFields, cfg.AppKey):
		return nil, stats, fmt.Errorf("返回验签失败")
	default:
		return respStr, stats, nil
	}
}

func queryTransferFromAPI(ctx context.Context, cfg *joinpayConfig, transfer *proto.TransferSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{
		"userNo":          cfg.AppID,
		"merchantOrderNo": transfer.GetTradeNo(),
	}
	params["hmac"] = signJoinpay(params, joinpayTransferQueryRequestFields, cfg.AppKey)
	reqBodyBytes, err := json.Marshal(params)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	reqBody := string(reqBodyBytes)
	body, reqCount, reqMs, reqErr := httpClient.Do(ctx, http.MethodPost, joinpayTransferQueryURL, reqBody, "application/json")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if reqErr != nil {
		return nil, stats, reqErr
	}

	respMap, decodeErr := decodeJSONAnyMap(body)
	if decodeErr != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", decodeErr)
	}
	statusCode, statusErr := requiredStringOrNumber(respMap, "statusCode")
	if statusErr != nil {
		return nil, stats, statusErr
	}
	if statusCode != "2001" {
		msg, _ := valueStringOrNumber(respMap, "message")
		if msg == "" {
			msg = "查询失败"
		}
		return nil, stats, fmt.Errorf("[%s]%s", statusCode, msg)
	}

	dataObj, ok := respMap["data"].(map[string]any)
	if !ok || dataObj == nil {
		return nil, stats, fmt.Errorf("响应解析失败")
	}
	dataRaw := map[string]string{}
	for k, v := range dataObj {
		dataRaw[k] = toString(v)
	}
	if !verifyJoinpay(dataRaw, joinpayTransferQueryResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	return map[string]string{
		"status":           dataRaw["status"],
		"errorCode":        dataRaw["errorCode"],
		"errorDesc":        dataRaw["errorDesc"],
		"platformSerialNo": dataRaw["platformSerialNo"],
	}, stats, nil
}

func bizResultByState(state proto.BizState, input plugin.BizOut) *proto.BizResult {
	return plugin.Result(state, input)
}
