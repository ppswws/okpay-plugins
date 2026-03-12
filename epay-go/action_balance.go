package main

import (
	"context"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	return plugin.ResultFail(plugin.BizResultInput{
		ChannelMsg: "epay 不支持余额查询",
		Stats:      plugin.RequestStats{},
	}), nil
}
