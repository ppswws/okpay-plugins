package main

import (
	"context"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	n := parseFormMap(req)
	if !verifyOrderNotify(cfg, n) {
		return plugin.RecordNotify(req, plugin.RespHTML("sign_error")), nil
	}
	if strings.TrimSpace(n["status"]) != "2" {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	if strings.TrimSpace(n["requestId"]) != order.GetTradeNo() {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	if cents := toCents(n["totalPrice"]); cents > 0 && cents != order.GetReal() {
		return plugin.RecordNotify(req, plugin.RespHTML("amount_mismatch")), nil
	}
	buyer := strings.TrimSpace(n["openId"])
	if buyer == "" {
		buyer = strings.TrimSpace(n["alipayUserId"])
	}
	if err := plugin.CompleteBiz(ctx, plugin.BizDoneIn{
		BizType: plugin.BizTypeOrder,
		BizNo:   order.GetTradeNo(),
		State:   plugin.BizStateSucceeded,
		ApiNo:   strings.TrimSpace(n["channelSn"]),
		Buyer:   buyer,
	}); err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
}

func refundNotify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	n := parseFormMap(req)
	if !verifyRefundNotify(cfg, n) {
		return plugin.RecordNotify(req, plugin.RespHTML("sign_error")), nil
	}
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
	}
	if requestID := strings.TrimSpace(n["requestId"]); requestID != "" && requestID != refund.GetRefundNo() {
		return plugin.RecordNotify(req, plugin.RespHTML("refund_mismatch")), nil
	}
	state := plugin.BizStateProcessing
	msg := "退款处理中"
	if strings.TrimSpace(n["refundResult"]) == "0" {
		state = plugin.BizStateSucceeded
		msg = "退款成功"
	}
	if strings.TrimSpace(n["refundResult"]) == "1" {
		state = plugin.BizStateFailed
		msg = "退款失败"
	}
	if err := plugin.CompleteBiz(ctx, plugin.BizDoneIn{
		BizType: plugin.BizTypeRefund,
		BizNo:   refund.GetRefundNo(),
		State:   state,
		ApiNo:   strings.TrimSpace(n["requestId"]),
		Code:    strings.TrimSpace(n["refundResult"]),
		Msg:     msg,
	}); err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
}

func payMerchantNotify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	n := parseFormMap(req)
	if !verifyPayMerchantNotify(cfg, n) {
		return plugin.RecordNotify(req, plugin.RespHTML("sign_error")), nil
	}
	return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
}

func verifyOrderNotify(cfg *sumapayConfig, raw map[string]string) bool {
	if cfg == nil {
		return false
	}
	sign := strings.TrimSpace(raw["resultSignature"])
	if sign == "" {
		return false
	}
	plain := concatByKeys(raw, []string{"requestId", "payId", "fiscalDate", "description", "totalPrice", "tradeAmount", "tradeFee"})
	return verifyRSA256(cfg.FengfuPublicKey, plain, sign)
}

func verifyRefundNotify(cfg *sumapayConfig, raw map[string]string) bool {
	if cfg == nil {
		return false
	}
	sign := strings.TrimSpace(raw["resultSignature"])
	if sign == "" {
		return false
	}
	plain := ""
	if strings.TrimSpace(raw["result"]) != "" {
		plain = concatByKeys(raw, []string{"result", "requestId", "merchantCode"})
	} else {
		plain = concatByKeys(raw, []string{"requestId", "originalRequestId", "refundResult", "refundTime"})
	}
	if plain == "" {
		return false
	}
	return verifyRSA256(cfg.FengfuPublicKey, plain, sign)
}

func verifyPayMerchantNotify(cfg *sumapayConfig, raw map[string]string) bool {
	if cfg == nil {
		return false
	}
	sign := strings.TrimSpace(raw["signature"])
	if sign == "" {
		return false
	}
	plain := concatByKeys(raw, []string{"requestId", "merchantCode", "result"})
	if plain == "" {
		return false
	}
	return verifyRSA256(cfg.FengfuPublicKey, plain, sign)
}
