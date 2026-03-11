package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

type epayConfig struct {
	AppURL string
	AppID  string
	AppKey string
}

type globalConfig struct {
	SiteDomain   string
	NotifyDomain string
	GoodsName    string
}

func readConfig(req *proto.InvokeContext) (*epayConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetConfigJsonRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	raw := struct {
		AppURL string `json:"appurl"`
		AppID  string `json:"appid"`
		AppKey string `json:"appkey"`
	}{}
	if err := json.Unmarshal(req.GetChannel().GetConfigJsonRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	if raw.AppURL == "" || raw.AppID == "" || raw.AppKey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	appURL := strings.TrimRight(raw.AppURL, "/")
	if !strings.HasPrefix(appURL, "http://") && !strings.HasPrefix(appURL, "https://") {
		return nil, fmt.Errorf("通道配置 appurl 非法")
	}
	return &epayConfig{AppURL: appURL, AppID: raw.AppID, AppKey: raw.AppKey}, nil
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
	if s == "" {
		return 0
	}
	if strings.HasPrefix(s, "-") {
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
	fracPart := ""
	if len(parts) == 2 {
		fracPart = parts[1]
		if fracPart == "" {
			fracPart = "00"
		}
		if !isDigits(fracPart) {
			return 0
		}
		if len(fracPart) > 2 {
			return 0
		}
		if len(fracPart) == 1 {
			fracPart += "0"
		}
	} else {
		fracPart = "00"
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

func reqDevice(req *proto.InvokeContext) string {
	if req != nil && req.GetRequest() != nil && plugin.IsMobile(req.GetRequest().GetUa()) {
		return "mobile"
	}
	return "pc"
}

func encodeParams(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, v)
	}
	return q.Encode()
}
