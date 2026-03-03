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
	siteDomain := strings.TrimRight(plugin.String(req.Config["sitedomain"]), "/")
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
	if req == nil {
		return out
	}
	if raw := req.Request.Query; raw != "" {
		if values, err := url.ParseQuery(raw); err == nil && len(values) > 0 {
			for k, vals := range values {
				if len(vals) > 0 {
					out[k] = vals[0]
				}
			}
		}
	}
	if raw := req.Request.Body; raw != "" {
		if values, err := url.ParseQuery(raw); err == nil && len(values) > 0 {
			for k, vals := range values {
				if len(vals) > 0 {
					out[k] = vals[0]
				}
			}
		} else if jsonMap, err := plugin.DecodeJSONMap(raw); err == nil {
			for k, v := range jsonMap {
				out[k] = plugin.String(v)
			}
		}
	}
	return out
}

func reqQueryValue(req *plugin.CallRequest, key string) string {
	if req == nil || key == "" {
		return ""
	}
	if raw := req.Request.Query; raw != "" {
		if values, err := url.ParseQuery(raw); err == nil && len(values) > 0 {
			if vals, ok := values[key]; ok && len(vals) > 0 {
				return vals[0]
			}
		}
	}
	return ""
}

func pickBuyerID(order *plugin.OrderPayload, req *plugin.CallRequest) string {
	if order != nil && strings.TrimSpace(order.Buyer) != "" {
		return strings.TrimSpace(order.Buyer)
	}
	if req != nil {
		return reqQueryValue(req, "buyer_id")
	}
	return ""
}
