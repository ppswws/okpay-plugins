package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func info(ctx context.Context) (*proto.PluginInfoResponse, error) {
	return plugin.BuildInfoResponse(plugin.Manifest{
		ID:       "epay",
		Name:     "彩虹易支付",
		Link:     "https://pay.cccyun.cc/",
		Paytypes: []string{"alipay", "wxpay", "bank"},
		Inputs: map[string]plugin.InputSpec{
			"appurl": {
				Name:     "接口地址",
				Type:     "input",
				Note:     "必须以 http:// 或 https:// 开头",
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
		Note: "易支付标准接口插件（MD5 签名）",
	})
}
