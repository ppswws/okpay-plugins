package main

import (
	"context"
	"fmt"
	"time"

	"github.com/go-pay/gopay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.RefundResponse, error) {
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return nil, fmt.Errorf("退款单为空")
	}
	order := req.GetOrder()
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return nil, err
	}
	bm := make(gopay.BodyMap)
	if order != nil && order.GetApiTradeNo() != "" {
		bm.Set("trade_no", order.GetApiTradeNo())
	} else if refund.GetTradeNo() != "" {
		bm.Set("out_trade_no", refund.GetTradeNo())
	} else {
		return plugin.RespRefund(-1, "", "", "", "缺少订单号", 0), nil
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
		return plugin.RespRefund(-1, "", reqBody, respBody, err.Error(), reqMs), nil
	}
	if resp == nil || resp.Response == nil {
		if respBody == "" {
			respBody = "{}"
		}
		return plugin.RespRefund(0, "", reqBody, respBody, "", reqMs), nil
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
	return plugin.RespRefund(state, apiRefundNo, reqBody, respBody, result, reqMs), nil
}
