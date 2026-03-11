package main

import (
	"context"
	"fmt"

	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func transfer(ctx context.Context, req *proto.InvokeContext) (*proto.TransferResponse, error) {
	return nil, fmt.Errorf("epay 不支持代付")
}
