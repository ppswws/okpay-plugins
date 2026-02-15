package main

import (
	"context"
	"fmt"
	"strings"

	"okpay/payment/plugin"
)

func main() {
	plugin.Serve(map[string]plugin.HandlerFunc{
		"info":     info,
		"create":   create,
		"alipay":   alipay,
		"wxpay":    wxpay,
		"query":    query,
		"refund":   refund,
		"transfer": transfer,
		"notify":   notify,
		"return":   ret,
	})
}

func info(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return map[string]any{
		"id":       "epay",
		"name":     "彩虹易支付",
		"link":     "https://www.epay.com",
		"paytypes": []string{"alipay", "wxpay"},
		"inputs": map[string]plugin.InputField{
			"appurl": {
				Name:     "接口地址",
				Type:     "input",
				Note:     "必须以 http:// 或 https:// 开头，并以 / 结尾",
				Required: true,
			},
			"pid": {
				Name:     "商户ID",
				Type:     "input",
				Required: true,
			},
			"key": {
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

func resolvePayMethod(resp *epayCreateResp) (string, string, error) {
	payURL := strings.TrimSpace(resp.PayURL)
	if payURL != "" {
		return "jump", payURL, nil
	}
	urlScheme := strings.TrimSpace(resp.URLScheme)
	if urlScheme != "" {
		return "scheme", urlScheme, nil
	}
	qr := strings.TrimSpace(resp.QRCode)
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
	if resp.Code == 1 && resp.Status == 1 {
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
		"resp_body":    "易支付接口不支持转账",
		"req_ms":       0,
	}, nil
}

func notify(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}

	params := reqParams(req)
	if !verifyMD5(params, cfg.Key) {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}
	if strings.TrimSpace(params["trade_status"]) != "TRADE_SUCCESS" {
		return map[string]any{"type": "html", "data": "success"}, nil
	}

	if err := plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
		TradeNo:    order.TradeNo,
		APITradeNo: strings.TrimSpace(params["trade_no"]),
		Buyer:      strings.TrimSpace(params["buyer"]),
	}); err != nil {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}
	return map[string]any{"type": "html", "data": "success"}, nil
}

func ret(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return plugin.ReturnOrOK(req), nil
}
