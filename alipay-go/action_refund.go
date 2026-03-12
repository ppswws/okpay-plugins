package main

import (
	"context"
	"fmt"
	"time"

	"github.com/go-pay/gopay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	result, err := refundByChannel(ctx, req)
	if err != nil {
		return nil, err
	}
	return kernelResult(result), nil
}

func refundByChannel(ctx context.Context, req *proto.InvokeContext) (channelBizResult, error) {
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return channelBizResult{}, fmt.Errorf("退款单为空")
	}
	order := req.GetOrder()
	cfg, err := readConfig(req)
	if err != nil {
		return channelBizResult{}, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return channelBizResult{}, err
	}
	bm := make(gopay.BodyMap)
	if order != nil && order.GetApiTradeNo() != "" {
		bm.Set("trade_no", order.GetApiTradeNo())
	} else if refund.GetTradeNo() != "" {
		bm.Set("out_trade_no", refund.GetTradeNo())
	} else {
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_FAILED,
			Input: plugin.BizResultInput{Msg: "缺少订单号", Stats: plugin.RequestStats{}},
		}, nil
	}
	bm.Set("refund_amount", toYuan(refund.GetAmount()))
	bm.Set("out_request_no", refund.GetRefundNo())
	applyModeBizParams(cfg, bm, "")
	reqBody := bm.JsonBody()
	start := time.Now()
	resp, err := client.TradeRefund(ctx, bm)
	reqMs := int32(time.Since(start).Milliseconds())
	respBody := marshalJSON(resp)
	if err != nil {
		if respBody == "" {
			respBody = err.Error()
		}
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_FAILED,
			Input: plugin.BizResultInput{Msg: err.Error(), Stats: plugin.RequestStats{ReqMs: reqMs, ReqBody: reqBody, RespBody: respBody}},
		}, nil
	}
	if resp == nil || resp.Response == nil {
		if respBody == "" {
			respBody = "{}"
		}
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_PROCESSING,
			Input: plugin.BizResultInput{Msg: "退款处理中", Stats: plugin.RequestStats{ReqMs: reqMs, ReqBody: reqBody, RespBody: respBody}},
		}, nil
	}
	apiRefundNo := resp.Response.TradeNo
	if apiRefundNo == "" {
		apiRefundNo = refund.GetRefundNo()
	}
	state := -1
	if resp.Response.Code == "10000" {
		state = 0
		if resp.Response.FundChange == "Y" {
			state = 1
		}
	}
	result := resp.Response.SubMsg
	if result == "" {
		result = resp.Response.Msg
	}
	if state == -1 && resp.Response.SubCode != "" {
		result = resp.Response.SubCode + ":" + result
	}
	stats := plugin.RequestStats{ReqMs: reqMs, ReqBody: reqBody, RespBody: respBody}
	switch state {
	case 1:
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_SUCCEEDED,
			Input: plugin.BizResultInput{ApiNo: apiRefundNo, Code: resp.Response.SubCode, Msg: result, Stats: stats},
		}, nil
	case -1:
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_FAILED,
			Input: plugin.BizResultInput{Code: resp.Response.SubCode, Msg: result, Stats: stats},
		}, nil
	default:
		return channelBizResult{
			State: proto.BizState_BIZ_STATE_PROCESSING,
			Input: plugin.BizResultInput{ApiNo: apiRefundNo, Code: resp.Response.SubCode, Msg: result, Stats: stats},
		}, nil
	}
}
