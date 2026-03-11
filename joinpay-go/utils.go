package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

type globalConfig struct {
	SiteDomain   string
	NotifyDomain string
	GoodsName    string
}

func readGlobalConfig(req *proto.InvokeContext) globalConfig {
	if req == nil || req.GetConfig() == nil {
		return globalConfig{}
	}
	cfg := req.GetConfig()
	return globalConfig{
		SiteDomain:   cfg.GetSiteDomain(),
		NotifyDomain: cfg.GetNotifyDomain(),
		GoodsName:    cfg.GetGoodsName(),
	}
}

func splitCSV(v string) []string {
	if strings.TrimSpace(v) == "" {
		return nil
	}
	raw := strings.Split(v, ",")
	out := make([]string, 0, len(raw))
	for _, item := range raw {
		s := strings.TrimSpace(item)
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

func modeSet(values []string) map[string]struct{} {
	out := make(map[string]struct{}, len(values))
	for _, v := range values {
		if key := strings.TrimSpace(v); key != "" {
			out[key] = struct{}{}
		}
	}
	return out
}

func allowMode(set map[string]struct{}, mode string) bool {
	_, ok := set[strings.TrimSpace(mode)]
	return ok
}

func queryParam(req *proto.InvokeContext, key string) string {
	if req == nil || req.GetRequest() == nil || key == "" {
		return ""
	}
	values, err := url.ParseQuery(req.GetRequest().GetQuery())
	if err != nil {
		return ""
	}
	return values.Get(key)
}

func lockOrderPage(ctx context.Context, tradeNo string, fetch func() (*proto.PageResponse, plugin.RequestStats, error)) (*proto.PageResponse, error) {
	payload, err := plugin.LockOrderExt(ctx, tradeNo, func() (any, plugin.RequestStats, error) {
		page, stats, err := fetch()
		if err != nil {
			return nil, stats, err
		}
		return pageToMap(page), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return pageFromMap(payload), nil
}

func pageToMap(page *proto.PageResponse) map[string]any {
	if page == nil {
		return map[string]any{"type": "error", "msg": "empty page response"}
	}
	out := map[string]any{"type": page.GetType()}
	if page.GetPage() != "" {
		out["page"] = page.GetPage()
	}
	if page.GetUrl() != "" {
		out["url"] = page.GetUrl()
	}
	if page.GetMsg() != "" {
		out["msg"] = page.GetMsg()
	}
	if len(page.GetDataJsonRaw()) > 0 {
		var data any
		if err := json.Unmarshal(page.GetDataJsonRaw(), &data); err == nil {
			out["data"] = data
		}
	}
	if page.GetDataText() != "" {
		out["data"] = page.GetDataText()
	}
	return out
}

func pageFromMap(m map[string]any) *proto.PageResponse {
	if m == nil {
		return plugin.RespError("empty page payload")
	}
	resp := &proto.PageResponse{Type: mapString(m, "type"), Page: mapString(m, "page"), Url: mapString(m, "url"), Msg: mapString(m, "msg")}
	if data, ok := m["data"]; ok && data != nil {
		switch resp.GetType() {
		case plugin.ResponseTypeHTML:
			resp.DataText = fmt.Sprint(data)
		default:
			raw, _ := json.Marshal(data)
			resp.DataJsonRaw = raw
		}
	}
	if resp.GetType() == "" {
		return plugin.RespError("invalid page payload")
	}
	return resp
}

func mapString(m map[string]any, key string) string {
	if v, ok := m[key]; ok && v != nil {
		return fmt.Sprint(v)
	}
	return ""
}

func toString(v any) string {
	switch val := v.(type) {
	case nil:
		return ""
	case string:
		return val
	case json.Number:
		return val.String()
	default:
		return fmt.Sprint(val)
	}
}

func toYuan(cents int64) string {
	sign := ""
	if cents < 0 {
		sign = "-"
		cents = -cents
	}
	return fmt.Sprintf("%s%d.%02d", sign, cents/100, cents%100)
}

func toCents(raw string) int64 {
	s := raw
	if s == "" || strings.HasPrefix(s, "-") {
		return 0
	}
	parts := strings.SplitN(s, ".", 3)
	if len(parts) > 2 {
		return 0
	}
	intPart := parts[0]
	if intPart == "" {
		intPart = "0"
	}
	if !isDigits(intPart) {
		return 0
	}
	fracPart := "00"
	if len(parts) == 2 {
		fracPart = parts[1]
		if fracPart == "" {
			fracPart = "00"
		}
		if !isDigits(fracPart) || len(fracPart) > 2 {
			return 0
		}
		if len(fracPart) == 1 {
			fracPart += "0"
		}
	}
	units, err := strconv.ParseInt(intPart, 10, 64)
	if err != nil {
		return 0
	}
	frac, err := strconv.ParseInt(fracPart, 10, 64)
	if err != nil {
		return 0
	}
	return units*100 + frac
}

func isDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
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

func buildPayURL(req *proto.InvokeContext, order *proto.OrderSnapshot, query map[string]string) string {
	if order == nil {
		return ""
	}
	globalCfg := readGlobalConfig(req)
	siteDomain := strings.TrimRight(globalCfg.SiteDomain, "/")
	if siteDomain == "" {
		return ""
	}
	payURL := siteDomain + "/pay/" + order.GetType() + "/" + order.GetTradeNo()
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

func decodeJSONStringMap(raw string) (map[string]string, error) {
	m, err := decodeJSONAnyMap(raw)
	if err != nil {
		return nil, err
	}
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = toString(v)
	}
	return out, nil
}

func valueStringOrNumber(m map[string]any, key string) (string, bool) {
	v, ok := m[key]
	if !ok || v == nil {
		return "", false
	}
	s := toString(v)
	if s == "" {
		return "", false
	}
	return s, true
}

func requiredStringOrNumber(m map[string]any, key string) (string, error) {
	v, ok := valueStringOrNumber(m, key)
	if !ok || v == "" {
		return "", fmt.Errorf("字段 %s 类型错误或为空", key)
	}
	return v, nil
}

func buildQRResponse(result map[string]string, page string) (*proto.PageResponse, error) {
	code := result["rc_Result"]
	if code == "" {
		if pic := result["rd_Pic"]; pic != "" {
			code = "data:image/png;base64," + pic
		}
	}
	if code == "" {
		return nil, fmt.Errorf("二维码信息为空")
	}
	return plugin.RespPageURL(page, code), nil
}

func buildDirectResponse(result map[string]string) (*proto.PageResponse, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		return plugin.RespHTML(payload), nil
	}
	return plugin.RespJump(payload), nil
}

func buildH5Response(result map[string]string, page string) (*proto.PageResponse, error) {
	payload := result["rc_Result"]
	if payload == "" {
		return nil, fmt.Errorf("支付数据为空")
	}
	lower := strings.ToLower(payload)
	if strings.Contains(lower, "<form") || strings.Contains(lower, "<html") {
		return plugin.RespHTML(payload), nil
	}
	return plugin.RespPageURL(page, payload), nil
}
