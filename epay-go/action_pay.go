package main

import (
	"context"
	"fmt"

	"okpay/payment/plugin"
)

func alipay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return handleCreatePay(ctx, req, func(method, url string) map[string]any {
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "qrcode":
			return plugin.RespPageURL("alipay_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
	})
}

func wxpay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return handleCreatePay(ctx, req, func(method, url string) map[string]any {
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "scheme":
			return plugin.RespPageURL("wxpay_h5", url)
		case "qrcode":
			return plugin.RespPageURL("wxpay_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
	})
}

func bank(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return handleCreatePay(ctx, req, func(method, url string) map[string]any {
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "scheme", "qrcode":
			return plugin.RespPageURL("bank_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
	})
}

func handleCreatePay(
	ctx context.Context,
	req *plugin.InvokeRequestV2,
	mapper func(method, url string) map[string]any,
) (map[string]any, error) {
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
		return mapper(method, url), stats, nil
	})
}

func resolvePayMethod(resp *epayCreateResp) (string, string, error) {
	if resp.PayURL != "" {
		return "jump", resp.PayURL, nil
	}
	if resp.URLScheme != "" {
		return "scheme", resp.URLScheme, nil
	}
	if resp.QRCode == "" {
		return "", "", fmt.Errorf("渠道未返回支付地址")
	}
	return "qrcode", resp.QRCode, nil
}
