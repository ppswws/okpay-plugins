package main

import (
	"bytes"
	"encoding/json"
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

func buildPayURL(req *plugin.InvokeRequestV2, order *plugin.OrderPayload, query map[string]string) string {
	if order == nil {
		return ""
	}
	globalCfg := plugin.GlobalConfig(req)
	siteDomain := strings.TrimRight(plugin.MapString(globalCfg, "sitedomain"), "/")
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

func pickBuyerID(order *plugin.OrderPayload, req *plugin.InvokeRequestV2) string {
	if order != nil && strings.TrimSpace(order.Buyer) != "" {
		return strings.TrimSpace(order.Buyer)
	}
	if req != nil {
		return plugin.QueryParam(req, "buyer_id")
	}
	return ""
}

func decodeJSONAnyMap(raw string) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, err
	}
	if out == nil {
		return nil, fmt.Errorf("empty json object")
	}
	return out, nil
}
