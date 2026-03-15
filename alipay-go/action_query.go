package main

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/go-pay/gopay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type channelBizResult struct {
	State proto.BizState
	Input plugin.BizOut
}

func kernelResult(result channelBizResult) *proto.BizResult {
	return plugin.Result(result.State, result.Input)
}

func queryOrder(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	result, err := queryOrderByChannel(ctx, req)
	if err != nil {
		return nil, err
	}
	return kernelResult(result), nil
}

func queryRefund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	result, err := queryRefundByChannel(ctx, req)
	if err != nil {
		return nil, err
	}
	return kernelResult(result), nil
}

func queryTransfer(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	result, err := queryTransferByChannel(ctx, req)
	if err != nil {
		return nil, err
	}
	return kernelResult(result), nil
}

func queryOrderByChannel(ctx context.Context, req *proto.InvokeContext) (channelBizResult, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return channelBizResult{}, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return channelBizResult{}, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return channelBizResult{}, err
	}

	bm := make(gopay.BodyMap)
	if order.GetApiTradeNo() != "" {
		bm.Set("trade_no", order.GetApiTradeNo())
	} else {
		bm.Set("out_trade_no", order.GetTradeNo())
	}
	applyModeBizParams(cfg, bm, "")

	start := time.Now()
	resp, queryErr := client.TradeQuery(ctx, bm)
	stats := plugin.RequestStats{
		ReqMs:    int32(time.Since(start).Milliseconds()),
		ReqBody:  bm.JsonBody(),
		RespBody: marshalJSON(resp),
	}
	switch {
	case queryErr != nil:
		if stats.RespBody == "" {
			stats.RespBody = queryErr.Error()
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: queryErr.Error(), Stats: stats},
		}, nil
	case resp == nil || resp.Response == nil:
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: "交易处理中", Stats: stats},
		}, nil
	}

	state := plugin.BizStateProcessing
	msg := "交易处理中"
	switch resp.Response.TradeStatus {
	case "TRADE_SUCCESS", "TRADE_FINISHED":
		state = plugin.BizStateSucceeded
		msg = "交易成功"
	case "TRADE_CLOSED":
		state = plugin.BizStateFailed
		msg = "交易关闭"
	}
	return channelBizResult{
		State: state,
		Input: plugin.BizOut{
			ApiNo: resp.Response.TradeNo,
			Buyer: resp.Response.BuyerOpenId,
			Code:  resp.Response.Code,
			Msg:   msg,
			Stats: stats,
		},
	}, nil
}

func queryRefundByChannel(ctx context.Context, req *proto.InvokeContext) (channelBizResult, error) {
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return channelBizResult{}, fmt.Errorf("退款单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return channelBizResult{}, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return channelBizResult{}, err
	}

	bm := make(gopay.BodyMap)
	order := req.GetOrder()
	if order != nil && order.GetApiTradeNo() != "" {
		bm.Set("trade_no", order.GetApiTradeNo())
	} else if refund.GetTradeNo() != "" {
		bm.Set("out_trade_no", refund.GetTradeNo())
	} else {
		return channelBizResult{
			State: plugin.BizStateFailed,
			Input: plugin.BizOut{Msg: "缺少订单号", Stats: plugin.RequestStats{}},
		}, nil
	}

	bm.Set("out_request_no", refund.GetRefundNo())
	start := time.Now()
	resp, queryErr := client.TradeFastPayRefundQuery(ctx, bm)
	stats := plugin.RequestStats{
		ReqMs:    int32(time.Since(start).Milliseconds()),
		ReqBody:  bm.JsonBody(),
		RespBody: marshalJSON(resp),
	}
	switch {
	case queryErr != nil:
		if stats.RespBody == "" {
			stats.RespBody = queryErr.Error()
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: queryErr.Error(), Stats: stats},
		}, nil
	case resp == nil || resp.Response == nil:
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: "退款处理中", Stats: stats},
		}, nil
	case resp.Response.Code != "10000":
		msg := resp.Response.SubMsg
		switch msg {
		case "":
			msg = resp.Response.Msg
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Code: resp.Response.SubCode, Msg: msg, Stats: stats},
		}, nil
	}

	state := plugin.BizStateProcessing
	msg := "退款处理中"
	switch strings.ToUpper(resp.Response.RefundStatus) {
	case "REFUND_SUCCESS":
		state = plugin.BizStateSucceeded
		msg = "退款成功"
	}
	apiNo := resp.Response.TradeNo
	switch apiNo {
	case "":
		apiNo = refund.GetApiRefundNo()
	}
	return channelBizResult{
		State: state,
		Input: plugin.BizOut{
			ApiNo: apiNo,
			Code:  resp.Response.SubCode,
			Msg:   msg,
			Stats: stats,
		},
	}, nil
}

func queryTransferByChannel(ctx context.Context, req *proto.InvokeContext) (channelBizResult, error) {
	transfer := req.GetTransfer()
	if transfer == nil || transfer.GetTradeNo() == "" {
		return channelBizResult{}, fmt.Errorf("代付单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return channelBizResult{}, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return channelBizResult{}, err
	}

	bm := make(gopay.BodyMap)
	bm.Set("biz_scene", "DIRECT_TRANSFER")
	bm.Set("out_biz_no", transfer.GetTradeNo())
	if transfer.GetApiTradeNo() != "" {
		bm.Set("order_id", transfer.GetApiTradeNo())
	}
	switch strings.ToLower(transfer.GetType()) {
	case "bank":
		bm.Set("product_code", "TRANS_BANKCARD_NO_PWD")
	default:
		bm.Set("product_code", "TRANS_ACCOUNT_NO_PWD")
	}

	start := time.Now()
	resp, queryErr := client.FundTransCommonQuery(ctx, bm)
	stats := plugin.RequestStats{
		ReqMs:    int32(time.Since(start).Milliseconds()),
		ReqBody:  bm.JsonBody(),
		RespBody: marshalJSON(resp),
	}
	switch {
	case queryErr != nil:
		if stats.RespBody == "" {
			stats.RespBody = queryErr.Error()
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: queryErr.Error(), Stats: stats},
		}, nil
	case resp == nil || resp.Response == nil:
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: "代付处理中", Stats: stats},
		}, nil
	case resp.Response.Code != "10000":
		msg := resp.Response.SubMsg
		switch msg {
		case "":
			msg = resp.Response.Msg
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Code: resp.Response.SubCode, Msg: msg, Stats: stats},
		}, nil
	}

	state := plugin.BizStateProcessing
	msg := "代付处理中"
	switch strings.ToUpper(resp.Response.Status) {
	case "SUCCESS":
		state = plugin.BizStateSucceeded
		msg = "代付成功"
	case "FAIL", "CLOSED", "REFUND":
		state = plugin.BizStateFailed
		msg = "代付失败"
	}
	if state == plugin.BizStateFailed {
		switch {
		case resp.Response.FailReason != "":
			msg = resp.Response.FailReason
		case resp.Response.SubOrderFailReason != "":
			msg = resp.Response.SubOrderFailReason
		}
	}
	apiNo := resp.Response.OrderId
	if apiNo == "" {
		apiNo = resp.Response.PayFundOrderId
	}
	code := resp.Response.ErrorCode
	if code == "" {
		code = resp.Response.SubOrderErrorCode
	}
	return channelBizResult{
		State: state,
		Input: plugin.BizOut{
			ApiNo: apiNo,
			Code:  code,
			Msg:   msg,
			Stats: stats,
		},
	}, nil
}
