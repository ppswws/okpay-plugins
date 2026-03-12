package main

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	order := req.GetOrder()
	refund := req.GetRefund()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	if refund == nil || refund.GetRefundNo() == "" {
		return nil, fmt.Errorf("退款单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, err := refundOrder(ctx, req, cfg, order, refund)
	if err != nil {
		return plugin.ResultFail(plugin.BizResultInput{
			ChannelMsg: err.Error(),
			Stats:      stats,
		}), nil
	}
	state := 0
	if resp["ra_Status"] == "100" {
		state = 1
	}
	if resp["ra_Status"] == "101" {
		state = -1
	}
	result := resp["rc_CodeMsg"]
	if result == "" {
		result = resp["ra_Status"]
	}
	switch state {
	case 1:
		return plugin.ResultOK(plugin.BizResultInput{
			APIBizNo:    resp["r5_RefundTrxNo"],
			ChannelCode: resp["rb_Code"],
			ChannelMsg:  result,
			Stats:       stats,
		}), nil
	case -1:
		return plugin.ResultFail(plugin.BizResultInput{
			ChannelCode: resp["rb_Code"],
			ChannelMsg:  result,
			Stats:       stats,
		}), nil
	default:
		return plugin.ResultPending(plugin.BizResultInput{
			APIBizNo:    resp["r5_RefundTrxNo"],
			ChannelCode: resp["rb_Code"],
			ChannelMsg:  result,
			Stats:       stats,
		}), nil
	}
}

func refundOrder(ctx context.Context, req *proto.InvokeContext, cfg *joinpayConfig, order *proto.OrderSnapshot, refund *proto.RefundSnapshot) (map[string]string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	params := map[string]string{
		"p0_Version":       "2.3",
		"p1_MerchantNo":    cfg.AppID,
		"p2_OrderNo":       order.GetTradeNo(),
		"p3_RefundOrderNo": refund.GetRefundNo(),
		"p4_RefundAmount":  toYuan(refund.GetAmount()),
		"p5_RefundReason":  "申请退款",
		"p6_NotifyUrl":     notifyDomain + "/pay/refundnotify/" + refund.GetRefundNo(),
	}
	params["hmac"] = signJoinpay(params, joinpayRefundRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayRefundURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, err := decodeJSONStringMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if !verifyJoinpay(respStr, joinpayRefundResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	if respStr["ra_Status"] != "100" {
		msg := respStr["rc_CodeMsg"]
		if msg == "" {
			msg = "退款失败"
		}
		return nil, stats, fmt.Errorf("[%s]%s", respStr["rb_Code"], msg)
	}
	return respStr, stats, nil
}
