package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	return plugin.BuildInfoResponse(plugin.Manifest{
		ID:         "joinpay",
		Name:       "汇聚支付",
		Link:       "https://www.joinpay.com/",
		Paytypes:   []string{"alipay", "wxpay", "bank"},
		Transtypes: []string{"bank"},
		Inputs: map[string]plugin.InputSpec{
			"appid":    {Name: "商户编号", Type: "input", Note: "对应 p1_MerchantNo", Required: true},
			"appkey":   {Name: "商户密钥", Type: "input", Note: "MD5 密钥", Required: true},
			"appmchid": {Name: "报备商户号", Type: "input", Note: "对应 qa_TradeMerchantNo", Required: true},
			"biztype_alipay": {
				Name:    "支付宝方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "支付宝扫码", "2": "支付宝H5"},
			},
			"biztype_wxpay": {
				Name:    "微信方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "微信扫码", "2": "微信H5", "3": "微信公众号", "4": "微信小程序"},
			},
			"biztype_bank": {
				Name:    "云闪付方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "云闪付扫码", "2": "云闪付H5"},
			},
		},
		Note: "请确认已完成汇聚支付商户报备并获取商户密钥（公众号/小程序支付需配置 AppID/AppSecret）。",
	})
}
