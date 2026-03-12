package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
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

type aliChannelConfig struct {
	AppID     string `json:"appid"`
	AppKey    string `json:"appkey"`
	AppSecret string `json:"appsecret"`
	AppMchID  string `json:"appmchid"`
	Biztype   string `json:"biztype"`
	IsProd    string `json:"is_prod"`
	Gateway   string `json:"gateway"`
}

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

func readConfig(req *proto.InvokeContext) (*aliConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetConfigJsonRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	raw := aliChannelConfig{}
	if err := json.Unmarshal(req.GetChannel().GetConfigJsonRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	cfg := &aliConfig{
		AppID:     raw.AppID,
		AppKey:    raw.AppKey,
		AppSecret: raw.AppSecret,
		AppMchID:  raw.AppMchID,
		Biztypes:  splitCSV(raw.Biztype),
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
	cfg.AppSecret = normalizeKeyBase64(cfg.AppSecret)
	cfg.AppKey = normalizeKeyBase64(cfg.AppKey)
	if v := strings.ToLower(raw.IsProd); v != "" {
		cfg.IsProd = v != "0" && v != "false" && v != "no"
	}
	if gw := strings.ToLower(raw.Gateway); strings.Contains(gw, "sandbox") {
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
	if notifyURL != "" {
		client.SetNotifyUrl(notifyURL)
	}
	if returnURL != "" {
		client.SetReturnUrl(returnURL)
	}
	// Keep key format consistent with channel config (raw base64):
	// do not enable AutoVerifySign here, because it requires PEM/cert content.
	if mode == modeService {
		client.SetAppAuthToken(cfg.AppMchID)
	}
	return client, nil
}

func applyModeBizParams(cfg *aliConfig, bm gopay.BodyMap, totalAmount string) {
	if bm == nil {
		return
	}
	if cfg == nil {
		return
	}
	if mode == modeStandard {
		if cfg.AppMchID != "" {
			bm.Set("seller_id", cfg.AppMchID)
		}
		return
	}
	if mode == modeDirect {
		if cfg.AppMchID == "" {
			return
		}
		bm.SetBodyMap("sub_merchant", func(m gopay.BodyMap) {
			m.Set("merchant_id", cfg.AppMchID)
		})
		if totalAmount != "" {
			bm.SetBodyMap("settle_info", func(m gopay.BodyMap) {
				m.Set("settle_period_time", "1d")
				m.Set("settle_detail_infos", []map[string]any{{"trans_in_type": "defaultSettle", "amount": totalAmount}})
			})
		}
	}
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

func buildOrderURLs(req *proto.InvokeContext, order *proto.OrderSnapshot) (string, string) {
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	siteDomain := strings.TrimRight(globalCfg.SiteDomain, "/")
	if order == nil {
		return "", ""
	}
	notifyURL := ""
	if notifyDomain != "" {
		notifyURL = notifyDomain + "/pay/notify/" + order.GetTradeNo()
	}
	returnURL := ""
	if siteDomain != "" {
		returnURL = siteDomain + "/pay/" + order.GetType() + "/" + order.GetTradeNo()
	}
	return notifyURL, returnURL
}

func buildRefundNotifyURL(req *proto.InvokeContext, refund *proto.RefundSnapshot) string {
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	if notifyDomain == "" || refund == nil || refund.GetRefundNo() == "" {
		return ""
	}
	return notifyDomain + "/pay/refundnotify/" + refund.GetRefundNo()
}

func buildTransferNotifyURL(req *proto.InvokeContext, transfer *proto.TransferSnapshot) string {
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	if notifyDomain == "" || transfer == nil || transfer.GetTradeNo() == "" {
		return ""
	}
	return notifyDomain + "/pay/transfernotify/" + transfer.GetTradeNo()
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

func normalizeKeyBase64(raw string) string {
	key := strings.TrimSpace(raw)
	if key == "" {
		return ""
	}
	return strings.Map(func(r rune) rune {
		switch r {
		case '\r', '\n', '\t', ' ':
			return -1
		default:
			return r
		}
	}, key)
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

func marshalJSON(v any) string {
	if v == nil {
		return ""
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return ""
	}
	return string(raw)
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
		return plugin.BuildReturnMap(page), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return plugin.BuildReturnPage(payload), nil
}
