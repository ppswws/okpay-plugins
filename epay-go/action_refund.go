package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	return plugin.ResultFail(plugin.BizResultInput{
		ChannelMsg: "epay 不支持退款",
		Stats:      plugin.RequestStats{},
	}), nil
}
