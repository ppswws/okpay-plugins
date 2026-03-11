package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

type pluginMode string

const (
	modeStandard pluginMode = "standard"
	modeDirect   pluginMode = "direct"
	modeService  pluginMode = "service"
)

type aliConfig struct {
	AppID     string
	AppKey    string
	AppSecret string
	AppMchID  string
	Biztypes  []string
	IsProd    bool
}

func readConfig(req *proto.InvokeContext) (*aliConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetConfigJsonRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	raw := map[string]any{}
	if err := json.Unmarshal(req.GetChannel().GetConfigJsonRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	cfg := &aliConfig{
		AppID:     strings.TrimSpace(toString(raw["appid"])),
		AppKey:    strings.TrimSpace(toString(raw["appkey"])),
		AppSecret: strings.TrimSpace(toString(raw["appsecret"])),
		AppMchID:  strings.TrimSpace(toString(raw["appmchid"])),
		Biztypes:  readStringSlice(raw["biztype_alipay"]),
		IsProd:    true,
	}
	if cfg.AppID == "" || cfg.AppSecret == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	if mode == modeService && cfg.AppMchID == "" {
		return nil, fmt.Errorf("商户授权token不能为空")
	}
	if mode == modeDirect && cfg.AppMchID == "" {
		return nil, fmt.Errorf("子商户SMID不能为空")
	}
	if len(cfg.Biztypes) == 0 {
		cfg.Biztypes = []string{"1", "2", "3"}
	}
	if v := strings.ToLower(strings.TrimSpace(toString(raw["is_prod"]))); v != "" {
		cfg.IsProd = v != "0" && v != "false" && v != "no"
	}
	if gw := strings.ToLower(strings.TrimSpace(toString(raw["gateway"]))); strings.Contains(gw, "sandbox") {
		cfg.IsProd = false
	}
	return cfg, nil
}

func newAliClient(cfg *aliConfig, notifyURL, returnURL string) (*alipay.Client, error) {
	if cfg == nil {
		return nil, fmt.Errorf("配置为空")
	}
	client, err := alipay.NewClient(cfg.AppID, cfg.AppSecret, cfg.IsProd)
	if err != nil {
		return nil, err
	}
	client.SetCharset(alipay.UTF8).SetSignType(alipay.RSA2)
	if strings.TrimSpace(notifyURL) != "" {
		client.SetNotifyUrl(strings.TrimSpace(notifyURL))
	}
	if strings.TrimSpace(returnURL) != "" {
		client.SetReturnUrl(strings.TrimSpace(returnURL))
	}
	if strings.TrimSpace(cfg.AppKey) != "" {
		client.AutoVerifySign([]byte(strings.TrimSpace(cfg.AppKey)))
	}
	if mode == modeService {
		client.SetAppAuthToken(cfg.AppMchID)
	}
	return client, nil
}

func allowModes(values []string) map[string]struct{} {
	out := make(map[string]struct{}, len(values))
	for _, v := range values {
		if s := strings.TrimSpace(v); s != "" {
			out[s] = struct{}{}
		}
	}
	return out
}

func allowMode(set map[string]struct{}, mode string) bool {
	_, ok := set[strings.TrimSpace(mode)]
	return ok
}

func applyModeBizParams(req *proto.InvokeContext, bm gopay.BodyMap, totalAmount string) {
	if bm == nil {
		return
	}
	channel := req.GetChannel()
	if mode == modeStandard {
		if channel != nil && strings.TrimSpace(queryChannelConfig(channel, "appmchid")) != "" {
			bm.Set("seller_id", strings.TrimSpace(queryChannelConfig(channel, "appmchid")))
		}
		return
	}
	if mode == modeDirect {
		smid := strings.TrimSpace(queryChannelConfig(channel, "appmchid"))
		if smid == "" {
			return
		}
		bm.SetBodyMap("sub_merchant", func(m gopay.BodyMap) {
			m.Set("merchant_id", smid)
		})
		if totalAmount != "" {
			bm.SetBodyMap("settle_info", func(m gopay.BodyMap) {
				m.Set("settle_period_time", "1d")
				m.Set("settle_detail_infos", []map[string]any{{"trans_in_type": "defaultSettle", "amount": totalAmount}})
			})
		}
	}
}

func queryChannelConfig(channel *proto.ChannelSnapshot, key string) string {
	if channel == nil || len(channel.GetConfigJsonRaw()) == 0 {
		return ""
	}
	obj := map[string]any{}
	if err := json.Unmarshal(channel.GetConfigJsonRaw(), &obj); err != nil {
		return ""
	}
	return toString(obj[key])
}

func orderSubject(req *proto.InvokeContext, order *proto.OrderSnapshot) string {
	if order != nil && strings.TrimSpace(order.GetSubject()) != "" {
		return limitLength(strings.TrimSpace(order.GetSubject()), 128)
	}
	if req != nil && req.GetConfig() != nil && strings.TrimSpace(req.GetConfig().GetGoodsName()) != "" {
		return limitLength(strings.TrimSpace(req.GetConfig().GetGoodsName()), 128)
	}
	if order != nil && strings.TrimSpace(order.GetTradeNo()) != "" {
		return "订单 " + strings.TrimSpace(order.GetTradeNo())
	}
	return "订单支付"
}

func buildOrderURLs(req *proto.InvokeContext, order *proto.OrderSnapshot) (string, string) {
	notifyDomain := ""
	siteDomain := ""
	if req != nil && req.GetConfig() != nil {
		notifyDomain = strings.TrimRight(strings.TrimSpace(req.GetConfig().GetNotifyDomain()), "/")
		siteDomain = strings.TrimRight(strings.TrimSpace(req.GetConfig().GetSiteDomain()), "/")
	}
	if order == nil {
		return "", ""
	}
	notifyURL := ""
	if notifyDomain != "" {
		notifyURL = notifyDomain + "/pay/notify/" + order.GetTradeNo()
	}
	returnURL := ""
	if siteDomain != "" {
		returnURL = siteDomain + "/pay/return/" + order.GetTradeNo()
	}
	return notifyURL, returnURL
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
	s := strings.TrimSpace(raw)
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

func readStringSlice(v any) []string {
	if v == nil {
		return nil
	}
	switch vv := v.(type) {
	case []string:
		out := make([]string, 0, len(vv))
		for _, s := range vv {
			s = strings.TrimSpace(s)
			if s != "" {
				out = append(out, s)
			}
		}
		return out
	case []any:
		out := make([]string, 0, len(vv))
		for _, item := range vv {
			s := strings.TrimSpace(fmt.Sprint(item))
			if s != "" {
				out = append(out, s)
			}
		}
		return out
	case string:
		s := strings.TrimSpace(vv)
		if s == "" {
			return nil
		}
		if strings.Contains(s, ",") {
			parts := strings.Split(s, ",")
			out := make([]string, 0, len(parts))
			for _, p := range parts {
				p = strings.TrimSpace(p)
				if p != "" {
					out = append(out, p)
				}
			}
			return out
		}
		return []string{s}
	default:
		return nil
	}
}

func queryParam(req *proto.InvokeContext, key string) string {
	if req == nil || req.GetRequest() == nil || key == "" {
		return ""
	}
	values, err := url.ParseQuery(req.GetRequest().GetQuery())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(values.Get(key))
}

func parseQueryString(raw string) map[string]string {
	values, err := url.ParseQuery(strings.TrimSpace(raw))
	if err != nil {
		return map[string]string{}
	}
	out := make(map[string]string, len(values))
	for k := range values {
		out[k] = values.Get(k)
	}
	return out
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

func modeNote() string {
	switch mode {
	case modeDirect:
		return "支付宝直付通插件，提交支付时自动注入 sub_merchant/settle_info 参数。"
	case modeService:
		return "支付宝服务商插件，使用 app_auth_token 代表商户发起交易。"
	default:
		return "支付宝官方支付插件（RSA2），支持支付、回调、查询、退款、转账与余额查询。"
	}
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
	out := map[string]any{"type": page.GetType(), "page": page.GetPage(), "url": page.GetUrl(), "msg": page.GetMsg()}
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

func buildPayURL(req *proto.InvokeContext, order *proto.OrderSnapshot, query map[string]string) string {
	if order == nil {
		return ""
	}
	siteDomain := ""
	if req != nil && req.GetConfig() != nil {
		siteDomain = strings.TrimRight(req.GetConfig().GetSiteDomain(), "/")
	}
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
