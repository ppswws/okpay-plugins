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

func buildPayURL(req *plugin.InvokeRequestV2, order *plugin.OrderPayload, query map[string]string) string {
	if order == nil {
		return ""
	}
	globalCfg := plugin.GlobalConfig(req)
	siteDomain := strings.TrimRight(plugin.MapString(globalCfg, "sitedomain"), "/")
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
	return plugin.RespPageURL(page, code), nil
}

func buildDirectResponse(result map[string]string) (map[string]any, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		return plugin.RespHTMLWithSubmit(payload, strings.HasPrefix(lower, "<form")), nil
	}
	return plugin.RespJump(payload), nil
}

func buildH5Response(result map[string]string, page string) (map[string]any, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		return plugin.RespHTMLWithSubmit(payload, strings.HasPrefix(lower, "<form")), nil
	}
	return plugin.RespPageURL(page, payload), nil
}
