package main

import (
	"context"
	"errors"
	"fmt"
	"strings"

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
	resp, err := queryOrder(ctx, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	switch strings.ToUpper(resp["rt7_orderStatus"]) {
	case "SUCCESS":
		state = 1
	case "FAIL", "CLOSE", "CANCEL":
		state = -1
	}
	return plugin.RespQuery(state, resp["rt6_serialNumber"]), nil
}

func queryOrder(ctx context.Context, cfg *helipayConfig, order *proto.OrderSnapshot) (map[string]string, error) {
	if order == nil {
		return nil, errors.New("order 为空")
	}
	if order.GetTradeNo() == "" && order.GetApiTradeNo() == "" {
		return nil, errors.New("tradeNo/apiTradeNo 不能为空")
	}
	params := map[string]string{
		"P1_bizType":        "AppPayQuery",
		"P2_orderId":        order.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
	}
	if apiTradeNo := order.GetApiTradeNo(); apiTradeNo != "" {
		params["P4_serialNumber"] = apiTradeNo
	}
	resp, _, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	if err != nil {
		return nil, err
	}
	if resp["rt2_retCode"] != "0000" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return nil, errors.New(msg)
	}
	return resp, nil
}
