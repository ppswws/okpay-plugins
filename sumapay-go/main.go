package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type sumapayService struct{}

func (s *sumapayService) Info(ctx context.Context, _ *proto.PluginInfoRequest) (*proto.PluginInfoResponse, error) {
	return info(ctx)
}

func (s *sumapayService) Handle(ctx context.Context, req *proto.HandleRequest) (*proto.HandleResponse, error) {
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
	case "notify":
		page, err = notify(ctx, invoke)
	case "refundnotify":
		page, err = refundNotify(ctx, invoke)
	case "paymerchantnotify":
		page, err = payMerchantNotify(ctx, invoke)
	default:
		err = fmt.Errorf("未知函数: %s", funcName)
	}
	if err != nil {
		return nil, err
	}
	return &proto.HandleResponse{Page: page}, nil
}

func (s *sumapayService) Submit(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case plugin.BizTypeOrder:
		return plugin.Result(plugin.BizStateProcessing, plugin.BizOut{
			Msg:   "请使用 Handle(create) 获取支付页面",
			Stats: plugin.RequestStats{},
		}), nil
	case plugin.BizTypeRefund:
		return refund(ctx, invoke)
	case plugin.BizTypeTransfer:
		return plugin.Result(plugin.BizStateFailed, plugin.BizOut{
			Msg:   "sumapay 不支持转账",
			Stats: plugin.RequestStats{},
		}), nil
	default:
		return nil, fmt.Errorf("submit 不支持的业务类型: %s", req.GetBizType().String())
	}
}

func (s *sumapayService) Query(ctx context.Context, req *proto.BizRequest) (*proto.BizResult, error) {
	invoke := req.GetCtx()
	switch req.GetBizType() {
	case plugin.BizTypeOrder:
		return queryOrder(ctx, invoke)
	default:
		return &proto.BizResult{}, nil
	}
}

func main() {
	if err := plugin.Serve(&sumapayService{}); err != nil {
		log.Fatal(err)
	}
}
