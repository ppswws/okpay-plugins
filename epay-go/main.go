package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type epayService struct{}

func (s *epayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *epayService) Handle(ctx context.Context, req *proto.HandleRequest) (*proto.HandleResponse, error) {
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
	case "wxpay":
		page, err = wxpayHandler(ctx, invoke)
	case "bank":
		page, err = bankHandler(ctx, invoke)
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

func (s *epayService) Submit(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	var (
		out    *proto.BizResult
		outErr error
	)
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case proto.BizType_BIZ_TYPE_ORDER:
		out = plugin.Result(plugin.BizStateProcessing, plugin.BizResultInput{
			Msg:   "请使用 Handle(create) 获取支付页面",
			Stats: plugin.RequestStats{},
		})
	case proto.BizType_BIZ_TYPE_REFUND:
		out, outErr = refund(ctx, invoke)
	case proto.BizType_BIZ_TYPE_TRANSFER:
		out, outErr = transfer(ctx, invoke)
	default:
		outErr = fmt.Errorf("submit 不支持的业务类型: %s", req.GetBizType().String())
	}
	return out, outErr
}

func (s *epayService) Query(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	var (
		out    *proto.BizResult
		outErr error
	)
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case proto.BizType_BIZ_TYPE_ORDER:
		out, outErr = query(ctx, invoke)
	case proto.BizType_BIZ_TYPE_BALANCE:
		out, outErr = balance(ctx, invoke)
	default:
		out = plugin.Result(plugin.BizStateProcessing, plugin.BizResultInput{
			Msg:   "渠道未实现该业务查询",
			Stats: plugin.RequestStats{},
		})
	}
	return out, outErr
}

func main() {
	if err := plugin.Serve(&epayService{}); err != nil {
		log.Fatal(err)
	}
}
