package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
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
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "qrcode":
			return plugin.RespPageURL("alipay_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
	})
}

func wxpayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return handleCreatePay(ctx, req, func(method, url string) *proto.PageResponse {
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "scheme":
			return plugin.RespPageURL("wxpay_h5", url)
		case "qrcode":
			return plugin.RespPageURL("wxpay_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
	})
}

func bankHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return handleCreatePay(ctx, req, func(method, url string) *proto.PageResponse {
		switch method {
		case "jump":
			return plugin.RespJump(url)
		case "scheme", "qrcode":
			return plugin.RespPageURL("bank_qrcode", url)
		default:
			return plugin.RespError("渠道未返回可用支付地址")
		}
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
			return pageToMap(plugin.RespError(err.Error())), stats, nil
		}
		return pageToMap(mapper(method, url)), stats, nil
	})
	if err != nil {
		return nil, err
	}
	return pageFromMap(payload), nil
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

func pageToMap(page *proto.PageResponse) map[string]any {
	if page == nil {
		return map[string]any{"type": "error", "msg": "empty page response"}
	}
	out := map[string]any{
		"type": page.GetType(),
		"page": page.GetPage(),
		"url":  page.GetUrl(),
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
	resp := &proto.PageResponse{Type: mapString(m, "type"), Page: mapString(m, "page"), Url: mapString(m, "url")}
	if data, ok := m["data"]; ok && data != nil {
		switch resp.GetType() {
		case plugin.ResponseTypeHTML:
			resp.DataText = strings.TrimSpace(fmt.Sprint(data))
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
	if len(m) == 0 {
		return ""
	}
	v, ok := m[key]
	if !ok || v == nil {
		return ""
	}
	return strings.TrimSpace(fmt.Sprint(v))
}
