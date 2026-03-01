package main

import (
	"fmt"
	"net/url"
	"strings"
	"time"

	"okpay/payment/plugin"

	"github.com/shopspring/decimal"
)

// toYuan 将分转换为元字符串（保留 2 位小数）。
func toYuan(cents int64) string {
	return decimal.NewFromInt(cents).Div(decimal.NewFromInt(100)).StringFixed(2)
}

// toCents parses amount in yuan (e.g. "1", "1.00") into cents.
// Invalid input returns 0.
func toCents(raw string) int64 {
	s := strings.TrimSpace(raw)
	if s == "" {
		return 0
	}
	val, err := decimal.NewFromString(s)
	if err != nil || val.IsNegative() {
		return 0
	}
	cents := val.Mul(decimal.NewFromInt(100))
	if !cents.Equal(cents.Truncate(0)) {
		return 0
	}
	return cents.IntPart()
}

func reqDate(tradeNo string) string {
	if len(tradeNo) >= 8 {
		return tradeNo[:8]
	}
	return ""
}

func buildPayURL(req *plugin.CallRequest, order *plugin.OrderPayload, query map[string]string) string {
	if order == nil {
		return ""
	}
	siteDomain := strings.TrimRight(fmt.Sprint(req.Config["sitedomain"]), "/")
	payURL := siteDomain + "/pay/" + order.Type + "/" + order.TradeNo
	if len(query) == 0 {
		return payURL
	}
	q := url.Values{}
	for k, v := range query {
		if k == "" || v == "" {
			continue
		}
		q.Set(k, v)
	}
	if q.Get("t") == "" {
		q.Set("t", fmt.Sprintf("%d", time.Now().Unix()))
	}
	qs := q.Encode()
	if qs == "" {
		return payURL
	}
	return payURL + "?" + qs
}


func reqParams(req *plugin.CallRequest) map[string]string {
	out := map[string]string{}
	for k, v := range req.Request.Query {
		out[k] = fmt.Sprint(v)
	}
	for k, v := range req.Request.Body {
		out[k] = fmt.Sprint(v)
	}
	return out
}

func pickBuyerID(order *plugin.OrderPayload, req *plugin.CallRequest) string {
	if order != nil && strings.TrimSpace(order.Buyer) != "" {
		return strings.TrimSpace(order.Buyer)
	}
	if req != nil {
		if req.Request.Query != nil {
			if v, ok := req.Request.Query["buyer_id"]; ok {
				return strings.TrimSpace(fmt.Sprint(v))
			}
		}
	}
	return ""
}
