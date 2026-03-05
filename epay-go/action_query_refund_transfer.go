package main

import (
	"context"

	"okpay/payment/plugin"
)

func query(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	_, resp, err := epayQuery(ctx, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	if resp.Code == 1 && resp.Status.String() == "1" {
		state = 1
	}
	return plugin.RespQuery(plugin.QueryStateResponse{
		State:      state,
		APITradeNo: resp.APITradeNo,
	}), nil
}

func refund(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return plugin.RespRefund(plugin.RefundStateResponse{
		State:       -1,
		APIRefundNo: "",
		ReqBody:     "",
		RespBody:    "",
		Result:      "易支付接口不支持退款",
		ReqMs:       0,
	}), nil
}

func transfer(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return plugin.RespTransfer(plugin.TransferStateResponse{
		State:      -1,
		APITradeNo: "",
		ReqBody:    "",
		RespBody:   "",
		Result:     "易支付接口不支持代付",
		ReqMs:      0,
	}), nil
}
