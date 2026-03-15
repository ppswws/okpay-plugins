package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const (
	pluginID   = "alipay"
	pluginName = "支付宝官方支付"
	pluginLink = "https://b.alipay.com/signing/productSetV2.htm"
)

type alipayService struct{}

func (s *alipayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *alipayService) Handle(ctx context.Context, req *proto.HandleRequest) (*proto.HandleResponse, error) {
	invoke := req.GetCtx()
	funcName := ""
	if invoke != nil {
		funcName = strings.TrimSpace(invoke.GetFuncName())
	}
	if funcName == "" {
		return nil, fmt.Errorf("func_name 不能为空")
	}
	var (
		page *proto.PageResponse
		err  error
	)
	switch funcName {
	case "create":
		page, err = create(ctx, invoke)
	case "alipay":
		page, err = alipayHandler(ctx, invoke)
	case "notify":
		page, err = notify(ctx, invoke)
	default:
		err = fmt.Errorf("未知函数: %s", funcName)
	}
	if err != nil {
		return nil, err
	}
	return &proto.HandleResponse{Page: page}, nil
}

func (s *alipayService) Submit(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	var (
		out    *proto.BizResult
		outErr error
	)
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case plugin.BizTypeOrder:
		out = plugin.Result(plugin.BizStateProcessing, plugin.BizOut{
			Msg:   "请使用 Handle(create) 获取支付页面",
			Stats: plugin.RequestStats{},
		})
	case plugin.BizTypeRefund:
		out, outErr = refund(ctx, invoke)
	case plugin.BizTypeTransfer:
		out, outErr = transfer(ctx, invoke)
	default:
		outErr = fmt.Errorf("submit 不支持的业务类型: %s", req.GetBizType().String())
	}
	return out, outErr
}

func (s *alipayService) Query(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	var (
		out    *proto.BizResult
		outErr error
	)
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case plugin.BizTypeOrder:
		out, outErr = queryOrder(ctx, invoke)
	case plugin.BizTypeRefund:
		out, outErr = queryRefund(ctx, invoke)
	case plugin.BizTypeTransfer:
		out, outErr = queryTransfer(ctx, invoke)
	case plugin.BizTypeBalance:
		out, outErr = balance(ctx, invoke)
	default:
		out = &proto.BizResult{}
	}
	return out, outErr
}

func main() {
	if err := plugin.Serve(&alipayService{}); err != nil {
		log.Fatal(err)
	}
}
