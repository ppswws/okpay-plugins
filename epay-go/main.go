package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

type epayService struct{}

func (s *epayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *epayService) Create(ctx context.Context, req *proto.CreateRequest) (*proto.CreateResponse, error) {
	page, err := create(ctx, req.GetCtx())
	if err != nil {
		return nil, err
	}
	return &proto.CreateResponse{Page: page}, nil
}

func (s *epayService) Query(ctx context.Context, req *proto.QueryRequest) (*proto.QueryResponse, error) {
	return query(ctx, req.GetCtx())
}

func (s *epayService) Refund(ctx context.Context, req *proto.RefundRequest) (*proto.RefundResponse, error) {
	return refund(ctx, req.GetCtx())
}

func (s *epayService) Transfer(ctx context.Context, req *proto.TransferRequest) (*proto.TransferResponse, error) {
	return transfer(ctx, req.GetCtx())
}

func (s *epayService) Balance(ctx context.Context, req *proto.BalanceRequest) (*proto.BalanceResponse, error) {
	return balance(ctx, req.GetCtx())
}

func (s *epayService) InvokeFunc(ctx context.Context, req *proto.InvokeFuncRequest) (*proto.InvokeFuncResponse, error) {
	invoke := req.GetCtx()
	action := strings.TrimSpace(invoke.GetFuncName())
	if action == "" {
		action = strings.TrimSpace(invoke.GetAction())
	}
	if action == "" {
		return nil, fmt.Errorf("func_name 不能为空")
	}
	var (
		page *proto.PageResponse
		err  error
	)
	switch action {
	case "create":
		page, err = create(ctx, invoke)
	case "alipay":
		page, err = alipay(ctx, invoke)
	case "wxpay":
		page, err = wxpay(ctx, invoke)
	case "bank":
		page, err = bank(ctx, invoke)
	case "notify":
		page, err = notify(ctx, invoke)
	default:
		return nil, fmt.Errorf("未知函数: %s", action)
	}
	if err != nil {
		return nil, err
	}
	return &proto.InvokeFuncResponse{Page: page}, nil
}

func main() {
	if err := plugin.Serve(&epayService{}); err != nil {
		log.Fatal(err)
	}
}
