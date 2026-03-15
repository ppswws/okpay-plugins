package main

import (
	"context"
	"fmt"
	"net/url"
	"time"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	plugin "github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const (
	payModePage      = "1" // 电脑网站支付
	payModeWap       = "2" // 手机网站支付
	payModeScan      = "3" // 扫码支付
	payModeJSPay     = "4" // 当面付网页支付
	payModePreauth   = "5" // 预授权支付
	payModeApp       = "6" // 应用支付
	payModeJSAPI     = "7" // 小程序支付
	payModeOrderCode = "8" // 订单码支付
)

func create(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.CreateHandlerFunc{
		"alipay": alipayHandler,
	})
}

func alipayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	biztypes := modeSet(cfg.Biztypes)
	ua := req.GetRequest().GetUa()
	isAlipay := plugin.IsAlipay(ua)
	isMobile := plugin.IsMobile(ua)

	allowPage := allowMode(biztypes, payModePage)
	allowWap := allowMode(biztypes, payModeWap)
	allowScan := allowMode(biztypes, payModeScan)
	allowJSPay := allowMode(biztypes, payModeJSPay)
	allowPreauth := allowMode(biztypes, payModePreauth)
	allowApp := allowMode(biztypes, payModeApp)
	allowJSAPI := allowMode(biztypes, payModeJSAPI)
	allowOrderCode := allowMode(biztypes, payModeOrderCode)

	// 与旧版项目的自动判定顺序保持一致（含扫码兜底逻辑）。
	if isAlipay && allowJSPay && !allowWap {
		return dispatchAlipayMode(ctx, req, cfg, payModeJSPay, true)
	}
	if (isMobile && (allowScan || allowJSPay) && !allowWap) || (!isMobile && !allowPage) {
		if allowScan {
			return dispatchAlipayMode(ctx, req, cfg, payModeScan, isAlipay)
		}
		if allowWap {
			return dispatchAlipayMode(ctx, req, cfg, payModeWap, isAlipay)
		}
		if allowJSPay {
			return dispatchAlipayMode(ctx, req, cfg, payModeJSPay, isAlipay)
		}
		if allowApp {
			return dispatchAlipayMode(ctx, req, cfg, payModeApp, isAlipay)
		}
		if allowJSAPI {
			return dispatchAlipayMode(ctx, req, cfg, payModeJSAPI, isAlipay)
		}
		if allowPreauth {
			return dispatchAlipayMode(ctx, req, cfg, payModePreauth, isAlipay)
		}
		if allowOrderCode {
			return dispatchAlipayMode(ctx, req, cfg, payModeOrderCode, isAlipay)
		}
	}

	if isMobile && allowWap {
		return dispatchAlipayMode(ctx, req, cfg, payModeWap, isAlipay)
	}
	if allowPage {
		return dispatchAlipayMode(ctx, req, cfg, payModePage, isAlipay)
	}
	if allowApp {
		return dispatchAlipayMode(ctx, req, cfg, payModeApp, isAlipay)
	}
	if allowJSAPI {
		return dispatchAlipayMode(ctx, req, cfg, payModeJSAPI, isAlipay)
	}
	if allowPreauth {
		return dispatchAlipayMode(ctx, req, cfg, payModePreauth, isAlipay)
	}
	if allowScan {
		return dispatchAlipayMode(ctx, req, cfg, payModeScan, isAlipay)
	}
	if allowOrderCode {
		return dispatchAlipayMode(ctx, req, cfg, payModeOrderCode, isAlipay)
	}
	if allowJSPay {
		return dispatchAlipayMode(ctx, req, cfg, payModeJSPay, isAlipay)
	}
	return nil, fmt.Errorf("当前通道未开启可用的支付方式")
}

func dispatchAlipayMode(ctx context.Context, req *proto.InvokeContext, cfg *aliConfig, mode string, isAlipay bool) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil {
		return nil, fmt.Errorf("订单为空")
	}
	buyerID := ""

	if mode == payModeJSPay || mode == payModeJSAPI {
		authCode := queryValue(req, "auth_code")
		if authCode == "" {
			oauthURL := plugin.BuildAliOAuthURL(cfg.AppID, req.GetRequest().GetUrl(), order.GetTradeNo(), cfg.IsProd)
			if oauthURL == "" {
				return nil, fmt.Errorf("生成支付宝 OAuth 地址失败")
			}
			if isAlipay {
				return plugin.RespJump(oauthURL), nil
			}
			return plugin.RespPageURL("alipay_qrcode", oauthURL), nil
		}
		if authCode != "" {
			identity, identityErr := plugin.GetAliIdentity(ctx, cfg.AppID, cfg.AppSecret, authCode, cfg.IsProd)
			if identityErr == nil {
				buyerID = identity.UserID
			}
		}
	}

	// 4/5/6/7 分支依赖实时上下文，不做锁缓存。
	if mode == payModeJSPay || mode == payModePreauth || mode == payModeApp || mode == payModeJSAPI {
		page, _, err := doAlipayMode(ctx, req, cfg, mode, buyerID)
		return page, err
	}
	return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
		return doAlipayMode(ctx, req, cfg, mode, buyerID)
	})
}

// 电脑网站支付。
func pagePayAsHTML(ctx context.Context, client *alipay.Client, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	payURL, err := client.TradePagePay(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: payURL,
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	page, pageErr := buildAutoPostPage(payURL)
	if pageErr != nil {
		return nil, stats, pageErr
	}
	return page, stats, nil
}

// 扫码支付与订单码支付共用的预下单逻辑。
// 普通扫码使用默认产品码；订单码会在调用侧改写为离线订单码产品。
func precreateAsQR(ctx context.Context, trade tradePrecreateClient, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	resp, err := trade.TradePrecreate(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: marshalJSON(resp),
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	if resp == nil || resp.Response == nil || resp.Response.QrCode == "" {
		if stats.RespBody == "" {
			stats.RespBody = "{}"
		}
		return nil, stats, fmt.Errorf("支付宝未返回二维码")
	}
	return plugin.RespPageURL("alipay_qrcode", resp.Response.QrCode), stats, nil
}

// 手机网站支付。
// 与旧版项目的手机支付语义保持一致，实际返回表单页面。
func wapPay(ctx context.Context, client *alipay.Client, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	payURL, err := client.TradeWapPay(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: payURL,
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	page, pageErr := buildAutoPostPage(payURL)
	if pageErr != nil {
		return nil, stats, pageErr
	}
	return page, stats, nil
}

// 将支付宝返回的 gateway URL 转为自动 POST 提交页面，统一给 page/wap 共用。
func buildAutoPostPage(payURL string) (*proto.PageResponse, error) {
	if payURL == "" {
		return nil, fmt.Errorf("支付宝未返回支付地址")
	}
	u, parseErr := url.Parse(payURL)
	if parseErr != nil {
		return nil, fmt.Errorf("解析支付地址失败: %w", parseErr)
	}
	action := (&url.URL{
		Scheme: u.Scheme,
		Host:   u.Host,
		Path:   u.Path,
	}).String()
	values := u.Query()
	if charset := values.Get("charset"); charset != "" {
		action = action + "?charset=" + url.QueryEscape(charset)
	}
	fields := make(map[string][]string, len(values))
	for key, vals := range values {
		if len(vals) == 0 {
			continue
		}
		cp := make([]string, 0, len(vals))
		cp = append(cp, vals...)
		fields[key] = cp
	}
	// 表单编码由 SDK 统一输出为 utf-8（meta + accept-charset）。
	html, htmlErr := plugin.BuildPostHTML(plugin.PostForm{
		ActionURL: action,
		Fields:    fields,
	})
	if htmlErr != nil {
		return nil, htmlErr
	}
	return plugin.RespHTML(html), nil
}

// 应用内支付。
func appPayAsPage(ctx context.Context, client *alipay.Client, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	orderStr, err := client.TradeAppPay(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: orderStr,
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	if orderStr == "" {
		return nil, stats, fmt.Errorf("支付宝未返回 APP 支付参数")
	}
	return plugin.RespPageData("alipay_h5", map[string]any{
		"url":          baseScheme(orderStr),
		"redirect_url": "/pay/ok",
	}), stats, nil
}

// 预授权支付。
func preauthAsPage(ctx context.Context, client *alipay.Client, req *proto.InvokeContext, bm gopay.BodyMap) (*proto.PageResponse, plugin.RequestStats, error) {
	order := req.GetOrder()
	if order == nil {
		return nil, plugin.RequestStats{}, fmt.Errorf("订单为空")
	}
	authBody := make(gopay.BodyMap)
	authBody.Set("out_order_no", order.GetTradeNo())
	authBody.Set("out_request_no", order.GetTradeNo())
	authBody.Set("order_title", bm.GetString("subject"))
	authBody.Set("amount", bm.GetString("total_amount"))
	authBody.Set("product_code", "PREAUTH_PAY")
	if val := bm.GetAny("business_params"); val != nil {
		authBody.Set("business_params", val)
	}
	reqBody := authBody.JsonBody()
	start := time.Now()
	orderStr, err := client.FundAuthOrderAppFreeze(ctx, authBody)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: orderStr,
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	if orderStr == "" {
		return nil, stats, fmt.Errorf("支付宝未返回预授权参数")
	}
	return plugin.RespPageData("alipay_h5", map[string]any{
		"url":          baseScheme(orderStr),
		"redirect_url": "/pay/ok",
	}), stats, nil
}

// 当面付与小程序支付共用的下单逻辑。
// 当面付仅注入买家标识；小程序支付会额外注入产品码与应用编号。
func jsTradeAsPage(ctx context.Context, client *alipay.Client, bm gopay.BodyMap, buyerKey, buyerVal string, jsapi bool, cfg *aliConfig) (*proto.PageResponse, plugin.RequestStats, error) {
	prepare := cloneBodyMap(bm)
	if buyerKey != "" && buyerVal != "" {
		prepare.Set(buyerKey, buyerVal)
	}
	if jsapi {
		prepare.Set("product_code", "JSAPI_PAY")
		prepare.Set("op_app_id", cfg.AppID)
	}
	reqBody := prepare.JsonBody()
	start := time.Now()
	resp, err := client.TradeCreate(ctx, prepare)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: marshalJSON(resp),
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	if resp == nil || resp.Response == nil || resp.Response.TradeNo == "" {
		return nil, stats, fmt.Errorf("支付宝未返回交易号")
	}
	return plugin.RespPageData("alipay_jspay", map[string]any{
		"alipay_trade_no": resp.Response.TradeNo,
		"redirect_url":    "/pay/ok",
	}), stats, nil
}

// 根据支付方式执行实际渠道调用。
func doAlipayMode(ctx context.Context, req *proto.InvokeContext, cfg *aliConfig, mode, buyerID string) (*proto.PageResponse, plugin.RequestStats, error) {
	order := req.GetOrder()
	if order == nil {
		return nil, plugin.RequestStats{}, fmt.Errorf("订单为空")
	}
	notifyURL, returnURL := buildOrderURLs(req, order)
	client, err := newAliClient(cfg, notifyURL, returnURL)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	bm := basePayBody(req, order, cfg)
	reqBody := bm.JsonBody()
	switch mode {
	case payModePage:
		return pagePayAsHTML(ctx, client, bm, reqBody)
	case payModeWap:
		return wapPay(ctx, client, bm, reqBody)
	case payModeScan:
		return precreateAsQR(ctx, client, bm, reqBody)
	case payModeJSPay:
		return jsTradeAsPage(ctx, client, bm, "buyer_id", buyerID, false, cfg)
	case payModePreauth:
		return preauthAsPage(ctx, client, req, bm)
	case payModeApp:
		return appPayAsPage(ctx, client, bm, reqBody)
	case payModeJSAPI:
		return jsTradeAsPage(ctx, client, bm, "buyer_id", buyerID, true, cfg)
	case payModeOrderCode:
		orderCodeBody := cloneBodyMap(bm)
		orderCodeBody.Set("product_code", "QR_CODE_OFFLINE")
		return precreateAsQR(ctx, client, orderCodeBody, orderCodeBody.JsonBody())
	default:
		return nil, plugin.RequestStats{}, fmt.Errorf("不支持的支付方式: %s", mode)
	}
}

type tradePrecreateClient interface {
	TradePrecreate(ctx context.Context, bm gopay.BodyMap) (*alipay.TradePrecreateResponse, error)
}

func cloneBodyMap(src gopay.BodyMap) gopay.BodyMap {
	dst := make(gopay.BodyMap, len(src))
	for k, v := range src {
		dst[k] = v
	}
	return dst
}

func basePayBody(req *proto.InvokeContext, order *proto.OrderSnapshot, cfg *aliConfig) gopay.BodyMap {
	bm := make(gopay.BodyMap)
	globalCfg := readGlobalConfig(req)
	bm.Set("out_trade_no", order.GetTradeNo())
	bm.Set("total_amount", toYuan(order.GetReal()))
	bm.Set("subject", globalCfg.GoodsName)
	if order.GetIpBuyer() != "" {
		bm.SetBodyMap("business_params", func(m gopay.BodyMap) {
			m.Set("mc_create_trade_ip", order.GetIpBuyer())
		})
	}
	applyModeBizParams(cfg, bm, toYuan(order.GetReal()))
	return bm
}
