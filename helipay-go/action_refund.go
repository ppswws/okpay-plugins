package main

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.RefundResponse, error) {
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
		return plugin.RespRefund(-1, "", result.ReqBody, result.RespBody, err.Error(), result.ReqMs), nil
	}
	state := 1
	if result.RetCode == "0001" || result.RetCode == "0002" {
		state = 0
	}
	return plugin.RespRefund(state, result.APIRefundNo, result.ReqBody, result.RespBody, "", result.ReqMs), nil
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
	if apiRefundNo == "" {
		apiRefundNo = resp["rt6_refundOrderNum"]
	}
	if apiRefundNo == "" {
		apiRefundNo = refundOrderID
	}
	return refundResult{APIRefundNo: apiRefundNo, RetCode: retCode, ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, nil
}
