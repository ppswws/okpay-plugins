package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"okpay/payment/plugin"

	"github.com/shopspring/decimal"
)

func channelConfig(req *plugin.CallRequest) map[string]any {
	raw := req.Channel["config"]
	switch v := raw.(type) {
	case map[string]any:
		return v
	case string:
		cfg := map[string]any{}
		if err := json.Unmarshal([]byte(v), &cfg); err == nil {
			return cfg
		}
	case []byte:
		cfg := map[string]any{}
		if err := json.Unmarshal(v, &cfg); err == nil {
			return cfg
		}
	}
	return map[string]any{}
}

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

// reqDevice 根据 UA 判断设备类型（mobile/pc）。
func reqDevice(req *plugin.CallRequest) string {
	if plugin.IsMobile(req.Request.UA) {
		return "mobile"
	}
	return "pc"
}

// reqParams 合并 query/body 并转成 string map。
func reqParams(req *plugin.CallRequest) map[string]string {
	out := map[string]string{}
	for k, v := range req.Request.Query {
		out[k] = strings.TrimSpace(fmt.Sprint(v))
	}
	for k, v := range req.Request.Body {
		out[k] = strings.TrimSpace(fmt.Sprint(v))
	}
	return out
}

// encodeParams 将参数编码成 form 表单字符串。
func encodeParams(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	return q.Encode()
}
