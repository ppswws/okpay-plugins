package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func create(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.CreateHandlerFunc{
		"alipay": alipayHandler,
		"wxpay":  wxpayHandler,
		"bank":   bankHandler,
	})
}

func alipayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return handleCreatePay(ctx, req, func(method, url string) *proto.PageResponse {
		page := plugin.RespError("渠道未返回可用支付地址")
		switch method {
		case "jump":
			page = plugin.RespJump(url)
		case "qrcode":
			page = plugin.RespPageURL("alipay_qrcode", url)
		}
		return page
	})
}

func wxpayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return handleCreatePay(ctx, req, func(method, url string) *proto.PageResponse {
		page := plugin.RespError("渠道未返回可用支付地址")
		switch method {
		case "jump":
			page = plugin.RespJump(url)
		case "scheme":
			page = plugin.RespPageURL("wxpay_h5", url)
		case "qrcode":
			page = plugin.RespPageURL("wxpay_qrcode", url)
		}
		return page
	})
}

func bankHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return handleCreatePay(ctx, req, func(method, url string) *proto.PageResponse {
		page := plugin.RespError("渠道未返回可用支付地址")
		switch method {
		case "jump":
			page = plugin.RespJump(url)
		case "scheme", "qrcode":
			page = plugin.RespPageURL("bank_qrcode", url)
		}
		return page
	})
}

func handleCreatePay(
	ctx context.Context,
	req *proto.InvokeContext,
	mapper func(method, url string) *proto.PageResponse,
) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	payload, err := plugin.LockOrderExt(ctx, order.GetTradeNo(), func() (any, plugin.RequestStats, error) {
		cfg, err := readConfig(req)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		resp, stats, err := createOrder(ctx, req, cfg, order)
		if err != nil {
			return nil, stats, err
		}
		method, url, err := resolvePayMethod(resp)
		if err != nil {
			return plugin.BuildReturnMap(plugin.RespError(err.Error())), stats, nil
		}
		return plugin.BuildReturnMap(mapper(method, url)), stats, nil
	})
	if err != nil {
		return nil, err
	}
	return plugin.BuildReturnPage(payload), nil
}

func resolvePayMethod(resp *epayCreateResp) (string, string, error) {
	if resp.PayURL != "" {
		return "jump", resp.PayURL, nil
	}
	if resp.URLScheme != "" {
		return "scheme", resp.URLScheme, nil
	}
	if resp.QRCode == "" {
		return "", "", fmt.Errorf("渠道未返回支付地址")
	}
	return "qrcode", resp.QRCode, nil
}

type epayCreateResp struct {
	Code      int    `json:"code"`
	Msg       string `json:"msg"`
	TradeNo   string `json:"trade_no"`
	PayURL    string `json:"payurl"`
	QRCode    string `json:"qrcode"`
	URLScheme string `json:"urlscheme"`
}

func createOrder(ctx context.Context, req *proto.InvokeContext, cfg *epayConfig, order *proto.OrderSnapshot) (*epayCreateResp, plugin.RequestStats, error) {
	createURL := cfg.AppURL + "/mapi.php"
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	siteDomain := strings.TrimRight(globalCfg.SiteDomain, "/")

	params := map[string]string{
		"pid":          cfg.AppID,
		"type":         order.GetType(),
		"out_trade_no": order.GetTradeNo(),
		"notify_url":   notifyDomain + "/pay/notify/" + order.GetTradeNo(),
		"return_url":   siteDomain + "/pay/" + order.GetType() + "/" + order.GetTradeNo(),
		"name":         globalCfg.GoodsName,
		"money":        toYuan(order.GetReal()),
		"clientip":     order.GetIpBuyer(),
		"device":       reqDevice(req),
		"param":        order.GetParam(),
		"sign_type":    "MD5",
	}
	params["sign"] = signMD5(params, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, createURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}

	resp := &epayCreateResp{}
	if err := json.Unmarshal([]byte(body), resp); err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if resp.Code != 1 {
		msg := resp.Msg
		if msg == "" {
			msg = "接口通道返回为空"
		}
		return nil, stats, fmt.Errorf("%s", msg)
	}
	return resp, stats, nil
}
