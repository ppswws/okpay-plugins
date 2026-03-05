package main

import (
	"context"

	"okpay/payment/plugin"
)

func notify(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return notifyResult(ctx, req, "config_error")
	}

	params := plugin.ParseRequestParams(req)
	if !verifyMD5(params, cfg.AppKey) {
		return notifyResult(ctx, req, "sign_error")
	}
	if params["trade_status"] != "TRADE_SUCCESS" {
		return notifyResult(ctx, req, "trade_status_invalid")
	}
	if order == nil || params["out_trade_no"] != order.TradeNo {
		return notifyResult(ctx, req, "order_mismatch")
	}
	if order.Real != toCents(params["money"]) {
		return notifyResult(ctx, req, "amount_mismatch")
	}

	_, queryResp, err := epayQuery(ctx, cfg, order)
	if err != nil || queryResp == nil {
		return notifyResult(ctx, req, "query_error")
	}
	if queryResp.Code != 1 || queryResp.Status.String() != "1" {
		return notifyResult(ctx, req, "query_unpaid")
	}

	apiTradeNo := queryResp.APITradeNo
	if apiTradeNo == "" {
		apiTradeNo = queryResp.TradeNo
	}
	if err := plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
		TradeNo:    order.TradeNo,
		APITradeNo: apiTradeNo,
		Buyer:      params["buyer"],
	}); err != nil {
		return notifyResult(ctx, req, "complete_error")
	}
	return notifyResult(ctx, req, "success")
}

func notifyResult(ctx context.Context, req *plugin.InvokeRequestV2, text string) (map[string]any, error) {
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeOrder,
		Result:  plugin.RespHTML(text),
	})
}
