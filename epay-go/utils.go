package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"

	"okpay/payment/plugin"
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

// fmtYuan 将分转换为元字符串（保留 2 位小数）。
func fmtYuan(cents int64) string {
	return fmt.Sprintf("%.2f", float64(cents)/100.0)
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
