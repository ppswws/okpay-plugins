package main

import (
	"context"
	"fmt"

	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.RefundResponse, error) {
	return nil, fmt.Errorf("epay 不支持退款")
}
