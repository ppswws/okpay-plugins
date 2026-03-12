package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"

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
		key := strings.TrimSpace(v)
		if key == "" {
			continue
		}
		out[key] = struct{}{}
	}
	return out
}

func allowMode(set map[string]struct{}, mode string) bool {
	if len(set) == 0 {
		return false
	}
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

func lockOrderPage(
	ctx context.Context,
	tradeNo string,
	fetch func() (*proto.PageResponse, plugin.RequestStats, error),
) (*proto.PageResponse, error) {
	payload, err := plugin.LockOrderExt(ctx, tradeNo, func() (any, plugin.RequestStats, error) {
		page, stats, err := fetch()
		if err != nil {
			return nil, stats, err
		}
		return plugin.BuildReturnMap(page), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return plugin.BuildReturnPage(payload), nil
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
		switch val := v.(type) {
		case nil:
			out[k] = ""
		case string:
			out[k] = val
		case json.Number:
			out[k] = val.String()
		case bool:
			if val {
				out[k] = "true"
			} else {
				out[k] = "false"
			}
		default:
			out[k] = fmt.Sprint(val)
		}
	}
	return out, nil
}

func firstNotEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
