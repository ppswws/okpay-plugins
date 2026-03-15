package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const (
	helipayAPIURL         = "https://pay.trx.helipay.com/trx/app/interface.action"
	helipayMerchantAPIURL = "https://pay.trx.helipay.com/trx/merchant/interface.action"
)

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

type helipayConfig struct {
	AppID         string
	AppKey        string
	SM4Key        string
	AppMchID      string
	MPAppID       string
	MPAppSecret   string
	MiniAppID     string
	MiniAppSecret string
	Biztypes      []string
}

type refundResult struct {
	APIRefundNo string
	RetCode     string
	RetMsg      string
	ReqBody     string
	RespBody    string
	ReqMs       int32
}

func readConfig(req *proto.InvokeContext) (*helipayConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetCfgRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	raw := struct {
		AppID   string `json:"appid"`
		AppKey  string `json:"appkey"`
		SM4Key  string `json:"sm4_key"`
		AppMch  string `json:"appmchid"`
		Biztype string `json:"biztype"`
		MP      struct {
			AppID     string `json:"appid"`
			AppSecret string `json:"appsecret"`
		} `json:"mp"`
		Mini struct {
			AppID     string `json:"appid"`
			AppSecret string `json:"appsecret"`
		} `json:"mini"`
	}{}
	if err := json.Unmarshal(req.GetChannel().GetCfgRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	if raw.AppID == "" || raw.AppKey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &helipayConfig{
		AppID:         raw.AppID,
		AppKey:        raw.AppKey,
		SM4Key:        raw.SM4Key,
		AppMchID:      raw.AppMch,
		MPAppID:       raw.MP.AppID,
		MPAppSecret:   raw.MP.AppSecret,
		MiniAppID:     raw.Mini.AppID,
		MiniAppSecret: raw.Mini.AppSecret,
		Biztypes:      splitCSV(raw.Biztype),
	}, nil
}

func sendRequestTo(ctx context.Context, requestURL string, params map[string]string, apiKey string) (map[string]string, plugin.RequestStats, error) {
	params["signatureType"] = "MD5"
	params["sign"] = signRequest(params, apiKey)
	payload := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, requestURL, payload, "application/x-www-form-urlencoded;charset=UTF-8")
	stats := plugin.RequestStats{ReqBody: payload, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	resp := parseResponse(body)
	if resp["raw"] != "" {
		return nil, stats, fmt.Errorf("响应解析失败")
	}
	if !verifyResponse(resp, apiKey) {
		return nil, stats, fmt.Errorf("返回数据验签失败")
	}
	return resp, stats, nil
}

func parseResponse(body string) map[string]string {
	if body == "" {
		return map[string]string{}
	}
	if m, err := decodeJSONStringMap(body); err == nil && len(m) > 0 {
		return m
	}
	values, err := url.ParseQuery(body)
	if err == nil && len(values) > 0 {
		out := map[string]string{}
		for k, v := range values {
			if len(v) > 0 {
				out[k] = v[0]
			}
		}
		return out
	}
	return map[string]string{"raw": body}
}
