package main

import (
	"context"

	plugin "github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	return plugin.BuildInfo(plugin.Manifest{
		ID:       "sumapay",
		Name:     "丰付支付",
		Link:     "https://www.sumapay.com/",
		Paytypes: []string{"alipay", "wxpay"},
		Inputs: map[string]plugin.InSpec{
			"appid": {
				Name:     "商户编号",
				Type:     "input",
				Note:     "merchantCode",
				Required: true,
			},
			"appuserid": {
				Name:     "二级户标识",
				Type:     "input",
				Note:     "userIdIdentity",
				Required: true,
			},
			"appkey": {
				Name:     "丰付公钥",
				Type:     "textarea",
				Note:     "支持 PEM 或 Base64",
				Required: true,
			},
			"appsecret": {
				Name:     "商户私钥",
				Type:     "textarea",
				Note:     "支持 PEM 或 Base64",
				Required: true,
			},
			"appmchid": {
				Name:     "子商户编码",
				Type:     "input",
				Note:     "支付宝/微信子商户号",
				Required: false,
			},
		},
		Note: "sumapay RSA2（SHA256）版；支持支付宝H5和微信H5下单、订单回调、退款、退款回调、订单查询。",
	})
}
