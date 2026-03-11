package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/go-pay/gopay"
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
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return nil, err
	}
	bm := make(gopay.BodyMap)
	if strings.TrimSpace(order.GetApiTradeNo()) != "" {
		bm.Set("trade_no", strings.TrimSpace(order.GetApiTradeNo()))
	} else {
		bm.Set("out_trade_no", order.GetTradeNo())
	}
	applyModeBizParams(req, bm, "")
	resp, err := client.TradeQuery(ctx, bm)
	if err != nil {
		return nil, err
	}
	if resp == nil || resp.Response == nil {
		return plugin.RespQuery(0, ""), nil
	}
	state := 0
	switch strings.ToUpper(strings.TrimSpace(resp.Response.TradeStatus)) {
	case "TRADE_SUCCESS", "TRADE_FINISHED":
		state = 1
	case "TRADE_CLOSED":
		state = -1
	default:
		state = 0
	}
	return plugin.RespQuery(state, strings.TrimSpace(resp.Response.TradeNo)), nil
}
