package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type alipayService struct{}

func (s *alipayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *alipayService) Create(ctx context.Context, req *proto.CreateRequest) (*proto.CreateResponse, error) {
	page, err := create(ctx, req.GetCtx())
	if err != nil {
		return nil, err
	}
	return &proto.CreateResponse{Page: page}, nil
}

func (s *alipayService) Query(ctx context.Context, req *proto.QueryRequest) (*proto.QueryResponse, error) {
	return query(ctx, req.GetCtx())
}

func (s *alipayService) Refund(ctx context.Context, req *proto.RefundRequest) (*proto.RefundResponse, error) {
	return refund(ctx, req.GetCtx())
}

func (s *alipayService) Transfer(ctx context.Context, req *proto.TransferRequest) (*proto.TransferResponse, error) {
	return transfer(ctx, req.GetCtx())
}

func (s *alipayService) Balance(ctx context.Context, req *proto.BalanceRequest) (*proto.BalanceResponse, error) {
	return balance(ctx, req.GetCtx())
}

func (s *alipayService) InvokeFunc(ctx context.Context, req *proto.InvokeFuncRequest) (*proto.InvokeFuncResponse, error) {
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
	case "create", "alipay":
		page, err = alipayHandler(ctx, invoke)
	case "notify":
		page, err = notify(ctx, invoke)
	case "return":
		page, err = pageReturn(ctx, invoke)
	case "ok":
		page = plugin.RespPage("ok")
	default:
		return nil, fmt.Errorf("未知函数: %s", action)
	}
	if err != nil {
		return nil, err
	}
	return &proto.InvokeFuncResponse{Page: page}, nil
}

func main() {
	if err := plugin.Serve(&alipayService{}); err != nil {
		log.Fatal(err)
	}
}
