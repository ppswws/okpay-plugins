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

// reqDevice 根据 UA 判断设备类型（mobile/pc）。
func reqDevice(req *plugin.InvokeRequestV2) string {
	if plugin.IsMobile(req.Raw.UserAgent) {
		return "mobile"
	}
	return "pc"
}

// encodeParams 将参数编码成 form 表单字符串。
func encodeParams(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	return q.Encode()
}
