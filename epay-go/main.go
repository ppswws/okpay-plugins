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

func info(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
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

func create(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.HandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
	})
}

func alipay(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
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
			return map[string]any{"type": "error", "msg": err.Error()}, stats, nil
		}
		if method == "jump" {
			return map[string]any{"type": "jump", "url": url}, stats, nil
		}
		if method == "qrcode" {
			return map[string]any{"type": "page", "page": "alipay_qrcode", "url": url}, stats, nil
		}
		return map[string]any{"type": "error", "msg": "渠道未返回可用支付地址"}, stats, nil
	})
}

func wxpay(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
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
			return map[string]any{"type": "error", "msg": err.Error()}, stats, nil
		}
		if method == "jump" {
			return map[string]any{"type": "jump", "url": url}, stats, nil
		}
		if method == "scheme" {
			return map[string]any{"type": "page", "page": "wxpay_h5", "url": url}, stats, nil
		}
		if method == "qrcode" {
			return map[string]any{"type": "page", "page": "wxpay_qrcode", "url": url}, stats, nil
		}
		return map[string]any{"type": "error", "msg": "渠道未返回可用支付地址"}, stats, nil
	})
}

func bank(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
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
			return map[string]any{"type": "error", "msg": err.Error()}, stats, nil
		}
		if method == "jump" {
			return map[string]any{"type": "jump", "url": url}, stats, nil
		}
		if method == "qrcode" || method == "scheme" {
			return map[string]any{"type": "page", "page": "bank_qrcode", "url": url}, stats, nil
		}
		return map[string]any{"type": "error", "msg": "渠道未返回可用支付地址"}, stats, nil
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

func query(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
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
	return map[string]any{"state": state, "api_trade_no": resp.APITradeNo}, nil
}

func refund(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return map[string]any{
		"state":         2,
		"api_refund_no": "",
		"req_body":      "",
		"resp_body":     "易支付接口不支持退款",
		"req_ms":        0,
	}, nil
}

func transfer(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return map[string]any{
		"state":        2,
		"api_trade_no": "",
		"req_body":     "",
		"resp_body":    "易支付接口不支持代付",
		"req_ms":       0,
	}, nil
}

func notify(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return map[string]any{"type": "html", "data": "config_error"}, nil
	}

	params := reqParams(req)
	if !verifyMD5(params, cfg.AppKey) {
		return map[string]any{"type": "html", "data": "sign_error"}, nil
	}
	if params["trade_status"] != "TRADE_SUCCESS" {
		return map[string]any{"type": "html", "data": "trade_status_invalid"}, nil
	}
	if order == nil || params["out_trade_no"] != order.TradeNo {
		return map[string]any{"type": "html", "data": "order_mismatch"}, nil
	}
	if order.Real != toCents(params["money"]) {
		return map[string]any{"type": "html", "data": "amount_mismatch"}, nil
	}
	_, queryResp, err := epayQuery(ctx, cfg, order)
	if err != nil || queryResp == nil {
		return map[string]any{"type": "html", "data": "query_error"}, nil
	}
	if queryResp.Code != 1 || queryResp.Status.String() != "1" {
		return map[string]any{"type": "html", "data": "query_unpaid"}, nil
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
		return map[string]any{"type": "html", "data": "complete_error"}, nil
	}
	return map[string]any{"type": "html", "data": "success"}, nil
}
