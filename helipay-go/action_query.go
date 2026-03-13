package main

import (
	"context"
	"errors"
	"fmt"
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
		return bizResultByState(proto.BizState_BIZ_STATE_PROCESSING, plugin.BizResultInput{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := proto.BizState_BIZ_STATE_PROCESSING
	msg := "交易处理中"
	switch strings.ToUpper(resp["rt7_orderStatus"]) {
	case "SUCCESS":
		state = proto.BizState_BIZ_STATE_SUCCEEDED
		msg = "交易成功"
	case "FAIL", "CLOSE", "CANCEL":
		state = proto.BizState_BIZ_STATE_FAILED
		msg = "交易失败"
	}
	return bizResultByState(state, plugin.BizResultInput{
		ApiNo: resp["rt6_serialNumber"],
		Code:  resp["rt2_retCode"],
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
		return bizResultByState(proto.BizState_BIZ_STATE_PROCESSING, plugin.BizResultInput{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := proto.BizState_BIZ_STATE_PROCESSING
	msg := "退款处理中"
	switch strings.ToUpper(resp["rt8_orderStatus"]) {
	case "SUCCESS":
		state = proto.BizState_BIZ_STATE_SUCCEEDED
		msg = "退款成功"
	case "FAIL", "CLOSE":
		state = proto.BizState_BIZ_STATE_FAILED
		msg = "退款失败"
	default:
		if reason := strings.TrimSpace(resp["retReasonDesc"]); reason != "" {
			msg = reason
		}
	}
	return bizResultByState(state, plugin.BizResultInput{
		ApiNo: resp["rt7_serialNumber"],
		Code:  resp["rt2_retCode"],
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
		return bizResultByState(proto.BizState_BIZ_STATE_PROCESSING, plugin.BizResultInput{
			Code:  "QUERY_ERROR",
			Msg:   queryErr.Error(),
			Stats: stats,
		}), nil
	}
	state := proto.BizState_BIZ_STATE_PROCESSING
	msg := "代付处理中"
	switch strings.ToUpper(resp["rt7_orderStatus"]) {
	case "SUCCESS":
		state = proto.BizState_BIZ_STATE_SUCCEEDED
		msg = "代付成功"
	case "FAIL", "REFUND":
		state = proto.BizState_BIZ_STATE_FAILED
		msg = "代付失败"
	default:
		if reason := strings.TrimSpace(resp["rt8_reason"]); reason != "" {
			msg = reason
		}
	}
	return bizResultByState(state, plugin.BizResultInput{
		ApiNo: resp["rt6_serialNumber"],
		Code:  resp["rt2_retCode"],
		Msg:   msg,
		Stats: stats,
	}), nil
}

func queryOrderFromAPI(ctx context.Context, cfg *helipayConfig, order *proto.OrderSnapshot) (map[string]string, plugin.RequestStats, error) {
	switch {
	case order == nil:
		return nil, plugin.RequestStats{}, errors.New("order 为空")
	case order.GetTradeNo() == "" && order.GetApiTradeNo() == "":
		return nil, plugin.RequestStats{}, errors.New("tradeNo/apiTradeNo 不能为空")
	}
	params := map[string]string{
		"P1_bizType":        "AppPayQuery",
		"P2_orderId":        order.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
	}
	if apiTradeNo := order.GetApiTradeNo(); apiTradeNo != "" {
		params["P4_serialNumber"] = apiTradeNo
	}
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	switch {
	case err != nil:
		return nil, stats, err
	case resp["rt2_retCode"] != "0000":
		msg := strings.TrimSpace(resp["rt3_retMsg"])
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return nil, stats, errors.New(msg)
	default:
		return resp, stats, nil
	}
}

func queryRefundFromAPI(ctx context.Context, cfg *helipayConfig, refund *proto.RefundSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{
		"P1_bizType":        "AppPayRefundQuery",
		"P2_refundOrderId":  refund.GetRefundNo(),
		"P3_customerNumber": cfg.AppID,
	}
	if apiNo := strings.TrimSpace(refund.GetApiRefundNo()); apiNo != "" {
		params["P4_serialNumber"] = apiNo
	}
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	switch {
	case err != nil:
		return nil, stats, err
	case resp["rt2_retCode"] != "0000":
		msg := strings.TrimSpace(resp["rt3_retMsg"])
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return nil, stats, errors.New(msg)
	default:
		return resp, stats, nil
	}
}

func queryTransferFromAPI(ctx context.Context, cfg *helipayConfig, transfer *proto.TransferSnapshot) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{
		"P1_bizType":        "TransferQuery",
		"P2_orderId":        transfer.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
	}
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	switch {
	case err != nil:
		return nil, stats, err
	case resp["rt2_retCode"] != "0000":
		msg := strings.TrimSpace(resp["rt3_retMsg"])
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return nil, stats, errors.New(msg)
	default:
		return resp, stats, nil
	}
}

func bizResultByState(state proto.BizState, input plugin.BizResultInput) *proto.BizResult {
	return plugin.Result(state, input)
}
