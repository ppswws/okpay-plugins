package main

import (
	"context"
	"fmt"
	"strings"

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
	if order != nil && strings.TrimSpace(order.GetApiTradeNo()) != "" {
		bm.Set("trade_no", strings.TrimSpace(order.GetApiTradeNo()))
	} else if refund.GetTradeNo() != "" {
		bm.Set("out_trade_no", refund.GetTradeNo())
	} else {
		return plugin.RespRefund(-1, "", "", "", "缺少订单号", 0), nil
	}
	bm.Set("refund_amount", toYuan(refund.GetAmount()))
	bm.Set("out_request_no", refund.GetRefundNo())
	applyModeBizParams(req, bm, "")
	resp, err := client.TradeRefund(ctx, bm)
	if err != nil {
		return plugin.RespRefund(-1, "", bm.JsonBody(), "", err.Error(), 0), nil
	}
	if resp == nil || resp.Response == nil {
		return plugin.RespRefund(0, "", bm.JsonBody(), "", "", 0), nil
	}
	apiRefundNo := strings.TrimSpace(resp.Response.TradeNo)
	if apiRefundNo == "" {
		apiRefundNo = refund.GetRefundNo()
	}
	state := 0
	if strings.EqualFold(strings.TrimSpace(resp.Response.FundChange), "Y") {
		state = 1
	}
	return plugin.RespRefund(state, apiRefundNo, bm.JsonBody(), "", strings.TrimSpace(resp.Response.Msg), 0), nil
}
