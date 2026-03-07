package main

import (
	"context"
	"fmt"

	"okpay/payment/plugin/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BalanceResponse, error) {
	return nil, fmt.Errorf("epay 不支持余额查询")
}
