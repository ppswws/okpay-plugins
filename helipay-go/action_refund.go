package main

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return nil, fmt.Errorf("退款单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	result, err := refundOrder(ctx, req, cfg, refund)
	if err != nil {
		return plugin.Result(plugin.BizStateFailed, plugin.BizOut{
			Msg:   err.Error(),
			Stats: plugin.RequestStats{ReqMs: result.ReqMs, ReqBody: result.ReqBody, RespBody: result.RespBody},
		}), nil
	}
	state := 1
	if result.RetCode == "0001" || result.RetCode == "0002" {
		state = 0
	}
	resultText := result.RetMsg
	if resultText == "" {
		resultText = result.RetCode
	}
	stats := plugin.RequestStats{ReqMs: result.ReqMs, ReqBody: result.ReqBody, RespBody: result.RespBody}
	if state == 1 {
		return plugin.Result(plugin.BizStateSucceeded, plugin.BizOut{
			ApiNo: result.APIRefundNo,
			Code:  result.RetCode,
			Msg:   resultText,
			Stats: stats,
		}), nil
	}
	return plugin.Result(plugin.BizStateProcessing, plugin.BizOut{
		ApiNo: result.APIRefundNo,
		Code:  result.RetCode,
		Msg:   resultText,
		Stats: stats,
	}), nil
}

func refundOrder(ctx context.Context, req *proto.InvokeContext, cfg *helipayConfig, refund *proto.RefundSnapshot) (refundResult, error) {
	refundOrderID := refund.GetRefundNo()
	if refundOrderID == "" {
		return refundResult{}, errors.New("refund_no 为空")
	}
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	params := map[string]string{
		"P1_bizType":        "AppPayRefund",
		"P2_orderId":        refund.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
		"P4_refundOrderId":  refundOrderID,
		"P5_amount":         toYuan(refund.GetAmount()),
		"P6_callbackUrl":    notifyDomain + "/pay/refundnotify/" + refundOrderID,
	}
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	if err != nil {
		return refundResult{ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, err
	}
	retCode := resp["rt2_retCode"]
	if retCode != "0000" && retCode != "0001" && retCode != "0002" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = retCode
		}
		return refundResult{ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, errors.New(msg)
	}
	apiRefundNo := resp["rt7_serialNumber"]
	return refundResult{
		APIRefundNo: apiRefundNo,
		RetCode:     retCode,
		RetMsg:      resp["rt3_retMsg"],
		ReqBody:     stats.ReqBody,
		RespBody:    stats.RespBody,
		ReqMs:       stats.ReqMs,
	}, nil
}
