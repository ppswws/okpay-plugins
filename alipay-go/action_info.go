package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	switch mode {
	case modeDirect:
		return plugin.BuildInfoResponse(plugin.Manifest{
			ID:       pluginID,
			Name:     pluginName,
			Link:     pluginLink,
			Paytypes: []string{"alipay"},
			Inputs: map[string]plugin.InputSpec{
				"appid":     {Name: "应用APPID", Type: "input", Required: true},
				"appkey":    {Name: "支付宝公钥", Type: "textarea", Note: "建议使用支付宝开放平台中的应用公钥模式公钥内容"},
				"appsecret": {Name: "应用私钥", Type: "textarea", Required: true},
				"appmchid":  {Name: "子商户SMID", Type: "input", Required: true},
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
			Note: modeNote(),
		})
	case modeService:
		return plugin.BuildInfoResponse(plugin.Manifest{
			ID:       pluginID,
			Name:     pluginName,
			Link:     pluginLink,
			Paytypes: []string{"alipay"},
			Inputs: map[string]plugin.InputSpec{
				"appid":     {Name: "应用APPID", Type: "input", Required: true},
				"appkey":    {Name: "支付宝公钥", Type: "textarea", Note: "建议使用支付宝开放平台中的应用公钥模式公钥内容"},
				"appsecret": {Name: "应用私钥", Type: "textarea", Required: true},
				"appmchid":  {Name: "商户授权token", Type: "input", Required: true},
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
			Note: modeNote(),
		})
	default:
		return plugin.BuildInfoResponse(plugin.Manifest{
			ID:         pluginID,
			Name:       pluginName,
			Link:       pluginLink,
			Paytypes:   []string{"alipay"},
			Transtypes: []string{"alipay", "bank"},
			Inputs: map[string]plugin.InputSpec{
				"appid":     {Name: "应用APPID", Type: "input", Required: true},
				"appkey":    {Name: "支付宝公钥", Type: "textarea", Note: "建议使用支付宝开放平台中的应用公钥模式公钥内容"},
				"appsecret": {Name: "应用私钥", Type: "textarea", Required: true},
				"appmchid":  {Name: "卖家支付宝用户ID", Type: "input", Note: "普通模式可留空；直付通/服务商模式按插件说明填写"},
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
			Note: modeNote(),
		})
	}
}
