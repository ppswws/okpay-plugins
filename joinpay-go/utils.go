package main

import (
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
func encodeParams(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	return q.Encode()
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


func limitLength(value string, length int) string {
	if value == "" || length <= 0 {
		return ""
	}
	runes := []rune(value)
	if len(runes) <= length {
		return value
	}
	return string(runes[:length])
}

func buildPayURL(req *plugin.CallRequest, order *plugin.OrderPayload, query map[string]string) string {
	if order == nil {
		return ""
	}
	siteDomain := strings.TrimRight(fmt.Sprint(req.Config["sitedomain"]), "/")
	if siteDomain == "" {
		return ""
	}
	payURL := siteDomain + "/pay/" + order.Type + "/" + order.TradeNo
	if len(query) == 0 {
		return payURL
	}
	q := url.Values{}
	for k, v := range query {
		if k == "" {
			continue
		}
		if v == "" {
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

func buildQRResponse(result map[string]string, page string) (map[string]any, error) {
	code := result["rc_Result"]
	if code == "" {
		pic := result["rd_Pic"]
		if pic != "" {
			code = "data:image/png;base64," + pic
		}
	}
	if code == "" {
		return nil, fmt.Errorf("二维码信息为空")
	}
	return map[string]any{"type": "page", "page": page, "url": code}, nil
}

func buildDirectResponse(result map[string]string) (map[string]any, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		out := map[string]any{"type": "html", "data": payload}
		if strings.HasPrefix(lower, "<form") {
			out["submit"] = true
		}
		return out, nil
	}
	return map[string]any{"type": "jump", "url": payload}, nil
}

func buildH5Response(result map[string]string, page string) (map[string]any, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		out := map[string]any{"type": "html", "data": payload}
		if strings.HasPrefix(lower, "<form") {
			out["submit"] = true
		}
		return out, nil
	}
	return map[string]any{"type": "page", "page": page, "url": payload}, nil
}

func toStringMap(input map[string]any) map[string]string {
	out := map[string]string{}
	for k, v := range input {
		out[k] = fmt.Sprint(v)
	}
	return out
}

func parseJSONAny(raw string) (any, error) {
	if raw == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	var out any
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		return nil, fmt.Errorf("支付参数解析失败: %w", err)
	}
	return out, nil
}
