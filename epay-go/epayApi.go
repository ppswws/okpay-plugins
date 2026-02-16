package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"okpay/payment/plugin"
)

const signTypeMD5 = "MD5"

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

type epayConfig struct {
	AppURL string
	PID    string
	Key    string
}

type epayCreateResp struct {
	Code      int    `json:"code"`
	Msg       string `json:"msg"`
	TradeNo   string `json:"trade_no"`
	PayURL    string `json:"payurl"`
	QRCode    string `json:"qrcode"`
	URLScheme string `json:"urlscheme"`
}

type epayQueryResp struct {
	Code       int    `json:"code"`
	Msg        string `json:"msg"`
	TradeNo    string `json:"trade_no"`
	OutTradeNo string `json:"out_trade_no"`
	APITradeNo string `json:"api_trade_no"`
	Status     int    `json:"status"`
}

func readConfig(req *plugin.CallRequest) (*epayConfig, error) {
	cfg := channelConfig(req)
	appurl := strings.TrimSpace(fmt.Sprint(cfg["appurl"]))
	pid := strings.TrimSpace(fmt.Sprint(cfg["pid"]))
	key := strings.TrimSpace(fmt.Sprint(cfg["key"]))
	if appurl == "" || pid == "" || key == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &epayConfig{
		AppURL: appurl,
		PID:    pid,
		Key:    key,
	}, nil
}

func createOrder(ctx context.Context, req *plugin.CallRequest, cfg *epayConfig, order *plugin.OrderPayload) (*epayCreateResp, plugin.RequestStats, error) {
	createUrl := cfg.AppURL + "mapi.php"
	notifyDomain := strings.TrimRight(fmt.Sprint(req.Config["notifydomain"]), "/")
	siteDomain := strings.TrimRight(fmt.Sprint(req.Config["sitedomain"]), "/")

	params := map[string]string{
		"pid":          cfg.PID,
		"type":         order.Type,
		"out_trade_no": order.TradeNo,
		"notify_url":   notifyDomain + "/pay/notify/" + order.TradeNo,
		"return_url":   siteDomain + "/pay/" + order.Type + "/" + order.TradeNo,
		"name":         order.Name,
		"money":        fmtYuan(order.Money),
		"clientip":     order.IPBuyer,
		"device":       reqDevice(req),
		"param":        order.Param,
		"sign_type":    signTypeMD5,
	}
	params["sign"] = signMD5(params, cfg.Key)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, createUrl, reqBody, "application/x-www-form-urlencoded")
	if err != nil {
		return nil, plugin.RequestStats{ReqBody: reqBody, RespBody: "", ReqCount: reqCount, ReqMs: reqMs}, err
	}
	resp := &epayCreateResp{}
	if err := json.Unmarshal([]byte(body), resp); err != nil {
		return nil, plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}, fmt.Errorf("响应解析失败: %w", err)
	}
	if resp.Code != 1 {
		msg := resp.Msg
		if msg == "" {
			msg = "接口通道返回为空"
		}
		return nil, plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}, fmt.Errorf("%s", msg)
	}
	return resp, plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}, nil
}

func epayQuery(ctx context.Context, cfg *epayConfig, order *plugin.OrderPayload) (string, *epayQueryResp, error) {
	queryUrl := cfg.AppURL + "api.php"
	query := url.Values{}
	query.Set("act", "order")
	query.Set("pid", cfg.PID)
	query.Set("key", cfg.Key)
	query.Set("out_trade_no", order.TradeNo)
	body, _, _, err := httpClient.Do(ctx, http.MethodGet, queryUrl+"?"+query.Encode(), "", "")
	if err != nil {
		return "", nil, err
	}
	resp := &epayQueryResp{}
	if err := json.Unmarshal([]byte(body), resp); err != nil {
		return body, nil, fmt.Errorf("响应解析失败: %w", err)
	}
	return body, resp, nil
}
