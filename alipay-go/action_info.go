package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	return plugin.BuildInfo(plugin.Manifest{
		ID:         pluginID,
		Name:       pluginName,
		Link:       pluginLink,
		Paytypes:   []string{"alipay"},
		Transtypes: []string{"alipay", "bank"},
		Inputs: map[string]plugin.InSpec{
			"appid":     {Name: "应用APPID", Type: "input", Required: true},
			"appkey":    {Name: "支付宝公钥", Type: "textarea", Note: "建议使用支付宝开放平台中的应用公钥模式公钥内容"},
			"appsecret": {Name: "应用私钥", Type: "textarea", Required: true},
			"appmchid":  {Name: "卖家支付宝用户ID", Type: "input", Note: "可留空，默认使用签约账号收款"},
			"is_prod": {
				Name:    "环境",
				Type:    "select",
				Options: map[string]string{"1": "正式环境", "0": "沙箱环境"},
				Note:    "沙箱联调请选择“沙箱环境”",
			},
			"biztype_alipay": {
				Name:    "支付宝方式",
				Type:    "checkbox",
				Options: map[string]string{"1": "电脑网站支付", "2": "手机网站支付", "3": "扫码支付", "4": "当面付JS", "5": "预授权", "6": "APP支付", "7": "JSAPI支付", "8": "订单码支付"},
			},
		},
		Note: "支付宝官方支付插件（RSA2），支持支付、回调、查询、退款、转账与余额查询。",
	})
}
