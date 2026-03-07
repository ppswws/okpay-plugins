package main

import (
	"context"
	"fmt"

	"okpay/payment/plugin/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.RefundResponse, error) {
	return nil, fmt.Errorf("epay 不支持退款")
}
