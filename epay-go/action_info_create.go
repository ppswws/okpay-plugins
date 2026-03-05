package main

import (
	"context"

	"okpay/payment/plugin"
)

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
