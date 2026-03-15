package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	n, err := parseHelipayOrderNotify(req)
	if err != nil || !verifyNotify(n.toSignMap(), cfg.AppKey) || n.Rt4Status != "SUCCESS" {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	order := req.GetOrder()
	if order == nil {
		return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
	}
	if n.Rt2OrderID != order.GetTradeNo() {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	if order.GetReal() != toCents(n.Rt5OrderAmount) {
		return plugin.RecordNotify(req, plugin.RespHTML("amount_mismatch")), nil
	}
	if err := plugin.CompleteBiz(ctx, plugin.BizDoneIn{
		BizType: plugin.BizTypeOrder,
		BizNo:   order.GetTradeNo(),
		State:   plugin.BizStateSucceeded,
		ApiNo:   n.Rt3SystemSerial,
		Buyer:   n.Rt10OpenID,
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
	n, err := parseHelipayRefundNotify(req)
	if err != nil || !verifyNotify(n.toSignMap(), cfg.AppKey) {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	status := strings.ToUpper(n.Rt5Status)
	refund := req.GetRefund()
	if refund == nil || refund.GetRefundNo() == "" {
		return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
	}
	if n.Rt3RefundOrderID != refund.GetRefundNo() {
		return plugin.RecordNotify(req, plugin.RespHTML("refund_mismatch")), nil
	}
	if n.Rt6Amount != "" && refund.GetAmount() != toCents(n.Rt6Amount) {
		return plugin.RecordNotify(req, plugin.RespHTML("amount_mismatch")), nil
	}
	respBody := "status=" + status
	if raw, marshalErr := json.Marshal(n); marshalErr == nil {
		respBody = string(raw)
	}
	if err := plugin.CompleteBiz(ctx, plugin.BizDoneIn{
		BizType:  plugin.BizTypeRefund,
		BizNo:    refund.GetRefundNo(),
		State:    helipayRefundState(status),
		ApiNo:    n.Rt4SystemSerial,
		Code:     n.Rt5Status,
		Msg:      status,
		RespBody: respBody,
	}); err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
}

func transferNotify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	n, err := parseHelipayTransferNotify(req)
	if err != nil || !verifyNotify(n.toSignMap(), cfg.AppKey) {
		return plugin.RecordNotify(req, plugin.RespHTML("fail")), nil
	}
	transfer := req.GetTransfer()
	if transfer == nil || transfer.GetTradeNo() == "" {
		return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
	}
	status := strings.ToUpper(n.Rt7OrderStatus)
	state, ok := helipayTransferState(status)
	if ok {
		_ = plugin.CompleteBiz(ctx, plugin.BizDoneIn{
			BizType: plugin.BizTypeTransfer,
			BizNo:   transfer.GetTradeNo(),
			State:   state,
			ApiNo:   n.Rt6SerialNumber,
			Code:    n.Rt2RetCode,
			Msg:     n.Rt9Reason,
		})
	}
	return plugin.RecordNotify(req, plugin.RespHTML("success")), nil
}

func helipayRefundState(status string) proto.BizState {
	switch status {
	case "SUCCESS":
		return plugin.BizStateSucceeded
	case "FAIL", "CLOSE":
		return plugin.BizStateFailed
	default:
		return plugin.BizStateProcessing
	}
}

func helipayTransferState(status string) (proto.BizState, bool) {
	switch status {
	case "SUCCESS":
		return plugin.BizStateSucceeded, true
	case "FAIL", "REFUND":
		return plugin.BizStateFailed, true
	case "RECEIVE", "INIT", "DOING":
		return plugin.BizStateProcessing, true
	default:
		return plugin.BizStateProcessing, false
	}
}

type helipayOrderNotify struct {
	Rt1CustomerNumber string
	Rt2OrderID        string
	Rt3SystemSerial   string
	Rt4Status         string
	Rt5OrderAmount    string
	Rt6Currency       string
	Rt7Timestamp      string
	Rt8Desc           string
	Rt10OpenID        string
	Sign              string
}

func (n helipayOrderNotify) toSignMap() map[string]string {
	return map[string]string{
		"rt1_customerNumber": n.Rt1CustomerNumber,
		"rt2_orderId":        n.Rt2OrderID,
		"rt3_systemSerial":   n.Rt3SystemSerial,
		"rt4_status":         n.Rt4Status,
		"rt5_orderAmount":    n.Rt5OrderAmount,
		"rt6_currency":       n.Rt6Currency,
		"rt7_timestamp":      n.Rt7Timestamp,
		"rt8_desc":           n.Rt8Desc,
		"rt10_openId":        n.Rt10OpenID,
		"sign":               n.Sign,
	}
}

func parseHelipayOrderNotify(req *proto.InvokeContext) (*helipayOrderNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &helipayOrderNotify{
		Rt1CustomerNumber: raw["rt1_customerNumber"],
		Rt2OrderID:        raw["rt2_orderId"],
		Rt3SystemSerial:   raw["rt3_systemSerial"],
		Rt4Status:         raw["rt4_status"],
		Rt5OrderAmount:    raw["rt5_orderAmount"],
		Rt6Currency:       raw["rt6_currency"],
		Rt7Timestamp:      raw["rt7_timestamp"],
		Rt8Desc:           raw["rt8_desc"],
		Rt10OpenID:        raw["rt10_openId"],
		Sign:              raw["sign"],
	}
	if n.Rt1CustomerNumber == "" || n.Rt2OrderID == "" || n.Rt3SystemSerial == "" || n.Rt4Status == "" || n.Rt5OrderAmount == "" || n.Rt6Currency == "" || n.Rt7Timestamp == "" || n.Sign == "" {
		return nil, fmt.Errorf("missing required order notify fields")
	}
	return n, nil
}

type helipayRefundNotify struct {
	Rt1CustomerNumber         string
	Rt2OrderID                string
	Rt3RefundOrderID          string
	Rt4SystemSerial           string
	Rt5Status                 string
	Rt6Amount                 string
	Rt7Currency               string
	Rt8Timestamp              string
	Rt10RefundChannelOrderNum string
	Sign                      string
}

func (n helipayRefundNotify) toSignMap() map[string]string {
	return map[string]string{
		"rt1_customerNumber": n.Rt1CustomerNumber,
		"rt2_orderId":        n.Rt2OrderID,
		"rt3_refundOrderId":  n.Rt3RefundOrderID,
		"rt4_systemSerial":   n.Rt4SystemSerial,
		"rt5_status":         n.Rt5Status,
		"rt6_amount":         n.Rt6Amount,
		"rt7_currency":       n.Rt7Currency,
		"rt8_timestamp":      n.Rt8Timestamp,
		"sign":               n.Sign,
	}
}

func parseHelipayRefundNotify(req *proto.InvokeContext) (*helipayRefundNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &helipayRefundNotify{
		Rt1CustomerNumber:         raw["rt1_customerNumber"],
		Rt2OrderID:                raw["rt2_orderId"],
		Rt3RefundOrderID:          raw["rt3_refundOrderId"],
		Rt4SystemSerial:           raw["rt4_systemSerial"],
		Rt5Status:                 raw["rt5_status"],
		Rt6Amount:                 raw["rt6_amount"],
		Rt7Currency:               raw["rt7_currency"],
		Rt8Timestamp:              raw["rt8_timestamp"],
		Rt10RefundChannelOrderNum: raw["rt10_refundChannelOrderNum"],
		Sign:                      raw["sign"],
	}
	if n.Rt1CustomerNumber == "" || n.Rt2OrderID == "" || n.Rt3RefundOrderID == "" || n.Rt4SystemSerial == "" || n.Rt5Status == "" || n.Rt6Amount == "" || n.Rt7Currency == "" || n.Rt8Timestamp == "" || n.Sign == "" {
		return nil, fmt.Errorf("missing required refund notify fields")
	}
	return n, nil
}

type helipayTransferNotify struct {
	Rt1BizType        string
	Rt2RetCode        string
	Rt3RetMsg         string
	Rt4CustomerNumber string
	Rt5OrderID        string
	Rt6SerialNumber   string
	Rt7OrderStatus    string
	Rt8NotifyType     string
	Rt9Reason         string
	Rt10CreateDate    string
	Rt11CompleteDate  string
	Sign              string
}

func (n helipayTransferNotify) toSignMap() map[string]string {
	return map[string]string{
		"rt1_bizType":        n.Rt1BizType,
		"rt2_retCode":        n.Rt2RetCode,
		"rt3_retMsg":         n.Rt3RetMsg,
		"rt4_customerNumber": n.Rt4CustomerNumber,
		"rt5_orderId":        n.Rt5OrderID,
		"rt6_serialNumber":   n.Rt6SerialNumber,
		"rt7_orderStatus":    n.Rt7OrderStatus,
		"rt8_notifyType":     n.Rt8NotifyType,
		"rt9_reason":         n.Rt9Reason,
		"rt10_createDate":    n.Rt10CreateDate,
		"rt11_completeDate":  n.Rt11CompleteDate,
		"sign":               n.Sign,
	}
}

func parseHelipayTransferNotify(req *proto.InvokeContext) (*helipayTransferNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &helipayTransferNotify{
		Rt1BizType:        raw["rt1_bizType"],
		Rt2RetCode:        raw["rt2_retCode"],
		Rt3RetMsg:         raw["rt3_retMsg"],
		Rt4CustomerNumber: raw["rt4_customerNumber"],
		Rt5OrderID:        raw["rt5_orderId"],
		Rt6SerialNumber:   raw["rt6_serialNumber"],
		Rt7OrderStatus:    raw["rt7_orderStatus"],
		Rt8NotifyType:     raw["rt8_notifyType"],
		Rt9Reason:         raw["rt9_reason"],
		Rt10CreateDate:    raw["rt10_createDate"],
		Rt11CompleteDate:  raw["rt11_completeDate"],
		Sign:              raw["sign"],
	}
	if n.Rt1BizType == "" || n.Rt5OrderID == "" || n.Rt6SerialNumber == "" || n.Rt7OrderStatus == "" || n.Sign == "" {
		return nil, fmt.Errorf("missing required transfer notify fields")
	}
	return n, nil
}

func parseNotifyJSONMap(req *proto.InvokeContext) (map[string]string, error) {
	if req == nil || req.GetRequest() == nil {
		return nil, fmt.Errorf("request is nil")
	}
	payload := req.GetRequest().GetBody()
	if len(payload) == 0 {
		return nil, fmt.Errorf("notify body is empty")
	}
	src := map[string]any{}
	if err := json.Unmarshal(payload, &src); err != nil {
		values, parseErr := url.ParseQuery(string(payload))
		if parseErr != nil || len(values) == 0 {
			return nil, fmt.Errorf("notify body parse failed: %w", err)
		}
		out := make(map[string]string, len(values))
		for k, v := range values {
			if len(v) > 0 {
				out[k] = v[0]
			} else {
				out[k] = ""
			}
		}
		return out, nil
	}
	out := make(map[string]string, len(src))
	for k, v := range src {
		switch val := v.(type) {
		case nil:
			out[k] = ""
		case string:
			out[k] = val
		case float64, float32, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, bool:
			out[k] = fmt.Sprint(val)
		default:
			b, _ := json.Marshal(val)
			out[k] = string(b)
		}
	}
	return out, nil
}
