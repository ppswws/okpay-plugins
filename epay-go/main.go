package main

import (
	"context"
	"fmt"

	"okpay/payment/plugin"
)

func main() {
	plugin.Serve(map[string]plugin.HandlerFunc{
		"info":     info,
		"create":   create,
		"alipay":   alipay,
		"wxpay":    wxpay,
		"bank":     bank,
		"query":    query,
		"refund":   refund,
		"transfer": transfer,
		"notify":   notify,
	})
}

func info(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return map[string]any{
		"id":       "epay",
		"name":     "彩虹易支付",
		"link":     "https://www.epay.com",
		"paytypes": []string{"alipay", "wxpay", "bank"},
		"inputs": map[string]plugin.InputField{
			"appurl": {
				Name:     "接口地址",
				Type:     "input",
				Note:     "必须以 http:// 或 https:// 开头，并以 / 结尾",
				Required: true,
			},
			"appid": {
				Name:     "商户ID",
				Type:     "input",
				Required: true,
			},
			"appkey": {
				Name:     "商户密钥",
				Type:     "input",
				Required: true,
			},
		},
		"note": "易支付标准接口插件（MD5 签名）",
	}, nil
}

func create(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.HandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
	})
}

func alipay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	return plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
		cfg, err := readConfig(req)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		resp, stats, err := createOrder(ctx, req, cfg, order)
		if err != nil {
			return nil, stats, err
		}
		method, url, err := resolvePayMethod(resp)
		if err != nil {
			return plugin.RespError(err.Error()), stats, nil
		}
		if method == "jump" {
			return plugin.RespJump(url), stats, nil
		}
		if method == "qrcode" {
			return plugin.RespPageURL("alipay_qrcode", url), stats, nil
		}
		return plugin.RespError("渠道未返回可用支付地址"), stats, nil
	})
}

func wxpay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	return plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
		cfg, err := readConfig(req)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		resp, stats, err := createOrder(ctx, req, cfg, order)
		if err != nil {
			return nil, stats, err
		}
		method, url, err := resolvePayMethod(resp)
		if err != nil {
			return plugin.RespError(err.Error()), stats, nil
		}
		if method == "jump" {
			return plugin.RespJump(url), stats, nil
		}
		if method == "scheme" {
			return plugin.RespPageURL("wxpay_h5", url), stats, nil
		}
		if method == "qrcode" {
			return plugin.RespPageURL("wxpay_qrcode", url), stats, nil
		}
		return plugin.RespError("渠道未返回可用支付地址"), stats, nil
	})
}

func bank(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	return plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
		cfg, err := readConfig(req)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		resp, stats, err := createOrder(ctx, req, cfg, order)
		if err != nil {
			return nil, stats, err
		}
		method, url, err := resolvePayMethod(resp)
		if err != nil {
			return plugin.RespError(err.Error()), stats, nil
		}
		if method == "jump" {
			return plugin.RespJump(url), stats, nil
		}
		if method == "qrcode" || method == "scheme" {
			return plugin.RespPageURL("bank_qrcode", url), stats, nil
		}
		return plugin.RespError("渠道未返回可用支付地址"), stats, nil
	})
}

func resolvePayMethod(resp *epayCreateResp) (string, string, error) {
	payURL := resp.PayURL
	if payURL != "" {
		return "jump", payURL, nil
	}
	urlScheme := resp.URLScheme
	if urlScheme != "" {
		return "scheme", urlScheme, nil
	}
	qr := resp.QRCode
	if qr == "" {
		return "", "", fmt.Errorf("渠道未返回支付地址")
	}
	return "qrcode", qr, nil
}

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
	queryResp := plugin.QueryStateResponse{
		State:      state,
		APITradeNo: resp.APITradeNo,
	}
	return plugin.RespQuery(queryResp), nil
}

func refund(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	refundResp := plugin.RefundStateResponse{
		State:       -1,
		APIRefundNo: "",
		ReqBody:     "",
		RespBody:    "",
		Result:      "易支付接口不支持退款",
		ReqMs:       0,
	}
	return plugin.RespRefund(refundResp), nil
}

func transfer(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	transferResp := plugin.TransferStateResponse{
		State:      -1,
		APITradeNo: "",
		ReqBody:    "",
		RespBody:   "",
		Result:     "易支付接口不支持代付",
		ReqMs:      0,
	}
	return plugin.RespTransfer(transferResp), nil
}

func notify(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("config_error"),
		})
	}

	params := plugin.ParseRequestParams(req)
	if !verifyMD5(params, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("sign_error"),
		})
	}
	if params["trade_status"] != "TRADE_SUCCESS" {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("trade_status_invalid"),
		})
	}
	if order == nil || params["out_trade_no"] != order.TradeNo {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("order_mismatch"),
		})
	}
	if order.Real != toCents(params["money"]) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("amount_mismatch"),
		})
	}
	_, queryResp, err := epayQuery(ctx, cfg, order)
	if err != nil || queryResp == nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("query_error"),
		})
	}
	if queryResp.Code != 1 || queryResp.Status.String() != "1" {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("query_unpaid"),
		})
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
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("complete_error"),
		})
	}
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeOrder,
		Result:  plugin.RespHTML("success"),
	})
}
