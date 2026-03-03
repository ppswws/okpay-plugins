package main

import (
	"net/url"
	"strings"

	"okpay/payment/plugin"

	"github.com/shopspring/decimal"
)

// toYuan 将分转换为元字符串（保留 2 位小数）。
func toYuan(cents int64) string {
	return decimal.NewFromInt(cents).Div(decimal.NewFromInt(100)).StringFixed(2)
}

// toCents 函数将元金额（例如“1”、“1.00”）解析为分，无效输入返回 0。
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

func encodeParams(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	return q.Encode()
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

func buildPayURL(req *plugin.CallRequest, order *plugin.OrderPayload, query map[string]string) string {
	if order == nil {
		return ""
	}
	siteDomain := strings.TrimRight(plugin.String(req.Config["sitedomain"]), "/")
	if siteDomain == "" {
		return ""
	}
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
	qs := q.Encode()
	if qs == "" {
		return payURL
	}
	return payURL + "?" + qs
}

func toStringMap(input map[string]any) map[string]string {
	out := map[string]string{}
	for k, v := range input {
		out[k] = plugin.String(v)
	}
	return out
}
