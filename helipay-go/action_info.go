package main

import (
	"context"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	return plugin.BuildInfoResponse(plugin.Manifest{
		ID:         "helipay",
		Name:       "合利宝",
		Link:       "https://www.helipay.com/",
		Paytypes:   []string{"wxpay", "alipay", "bank"},
		Transtypes: []string{"bank"},
		Inputs: map[string]plugin.InputSpec{
			"appid":    {Name: "商户号 customerNumber", Type: "input", Required: true},
			"appmchid": {Name: "报备编号", Type: "input", Required: true},
			"appkey":   {Name: "商户密钥(MD5签名密钥)", Type: "input", Note: "合利宝提供的签名密钥", Required: true},
			"sm4_key":  {Name: "加密密钥(SM4)", Type: "input", Note: "合利宝提供的加密密钥", Required: true},
			"biztype_alipay": {
				Name:    "支付宝方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "公众号/JS/服务窗", "2": "小程序", "3": "WAP(H5)", "4": "扫码"},
			},
			"biztype_wxpay": {
				Name:    "微信方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "公众号/JS", "2": "小程序", "3": "WAP(H5)", "4": "扫码"},
			},
			"biztype_bank": {
				Name:    "银联方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "云闪付扫码"},
			},
		},
		Note: "配置说明：商户号/报备编号/签名密钥/加密密钥由合利宝提供",
	})
}
