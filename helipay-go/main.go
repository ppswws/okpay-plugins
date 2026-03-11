package main

import (
	"context"
	"fmt"
	"log"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

type helipayService struct{}

func (s *helipayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *helipayService) Create(ctx context.Context, req *proto.CreateRequest) (*proto.CreateResponse, error) {
	page, err := create(ctx, req.GetCtx())
	if err != nil {
		return nil, err
	}
	return &proto.CreateResponse{Page: page}, nil
}

func (s *helipayService) Query(ctx context.Context, req *proto.QueryRequest) (*proto.QueryResponse, error) {
	return query(ctx, req.GetCtx())
}

func (s *helipayService) Refund(ctx context.Context, req *proto.RefundRequest) (*proto.RefundResponse, error) {
	return refund(ctx, req.GetCtx())
}

func (s *helipayService) Transfer(ctx context.Context, req *proto.TransferRequest) (*proto.TransferResponse, error) {
	return transfer(ctx, req.GetCtx())
}

func (s *helipayService) Balance(ctx context.Context, req *proto.BalanceRequest) (*proto.BalanceResponse, error) {
	return balance(ctx, req.GetCtx())
}

func (s *helipayService) InvokeFunc(ctx context.Context, req *proto.InvokeFuncRequest) (*proto.InvokeFuncResponse, error) {
	invoke := req.GetCtx()
	action := invoke.GetFuncName()
	if action == "" {
		action = invoke.GetAction()
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
		page, err = alipayHandler(ctx, invoke)
	case "wxpay":
		page, err = wxpayHandler(ctx, invoke)
	case "bank":
		page, err = bankHandler(ctx, invoke)
	case "notify":
		page, err = notify(ctx, invoke)
	case "refundnotify":
		page, err = refundNotify(ctx, invoke)
	case "transfernotify":
		page, err = transferNotify(ctx, invoke)
	default:
		return nil, fmt.Errorf("未知函数: %s", action)
	}
	if err != nil {
		return nil, err
	}
	return &proto.InvokeFuncResponse{Page: page}, nil
}

func main() {
	if err := plugin.Serve(&helipayService{}); err != nil {
		log.Fatal(err)
	}
}
