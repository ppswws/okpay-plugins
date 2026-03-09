package main

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
	"okpay/payment/plugin/sdk/wechatpay"
)

func create(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.CreateHandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
	})
}

func alipay(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	modes := modeSet(cfg.Biztypes)
	allowPublic := allowMode(modes, "1")
	allowMini := allowMode(modes, "2")
	allowH5 := allowMode(modes, "3")
	allowScan := allowMode(modes, "4")

	if allowH5 && plugin.IsMobile(req.GetRequest().GetUa()) {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createWapOrder(ctx, req, cfg, order, "alipay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespJump(payURL), stats, nil
		})
	}
	if allowScan || allowH5 {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createScanOrder(ctx, req, cfg, order, "alipay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
		})
	}
	if allowMini {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createAppletOrder(ctx, req, cfg, order, "alipay", "1", "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
		})
	}
	if allowPublic {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createPublicOrder(ctx, req, cfg, order, "alipay", "1", "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
		})
	}
	return plugin.RespError("当前通道未开启支付宝支付方式"), nil
}

func wxpay(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	modes := modeSet(cfg.Biztypes)
	allowMP := allowMode(modes, "1")
	allowMini := allowMode(modes, "2")
	allowH5 := allowMode(modes, "3")
	allowScan := allowMode(modes, "4")

	if allowMini {
		if cfg.MiniAppID == "" {
			return plugin.RespError("支付通道未绑定微信小程序"), nil
		}
		// 按是否配置小程序密钥决定原生/非原生模式：
		// 原生：有密钥，需通过 code 换取 openid（P7_isRaw=1, P8_openid=真实openid）
		// 非原生：无密钥（P7_isRaw=0, P8_openid=1）
		if cfg.MiniAppSecret != "" {
			code := queryParam(req, "code")
			if code == "" {
				payURL := buildPayURL(req, order, nil)
				values := url.Values{}
				values.Set("real", strconv.FormatInt(order.GetReal(), 10))
				values.Set("url", payURL)
				scheme, err := wechatpay.GenerateScheme(ctx, cfg.MiniAppID, cfg.MiniAppSecret, "page/pay", values.Encode())
				if err != nil {
					return plugin.RespError(err.Error()), nil
				}
				return plugin.RespPageURL("wxpay_h5", scheme), nil
			}
			openID, err := wechatpay.AppGetOpenid(ctx, wechatpay.MiniAuthParams{
				AppID:     cfg.MiniAppID,
				AppSecret: cfg.MiniAppSecret,
				Code:      code,
			})
			if err != nil {
				return plugin.RespJSON(map[string]any{"code": 1, "message": err.Error()}), nil
			}
			result, err := lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
				payInfo, stats, err := createAppletOrder(ctx, req, cfg, order, "wxpay", cfg.MiniAppID, "1", openID)
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(payInfo)
				if err != nil {
					return nil, stats, err
				}
				return plugin.RespJSON(map[string]any{"code": 0, "js_api_parameters": jsParams}), stats, nil
			})
			if err != nil {
				return plugin.RespJSON(map[string]any{"code": 1, "message": err.Error()}), nil
			}
			return result, nil
		}
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			codeURL, stats, err := createAppletOrder(ctx, req, cfg, order, "wxpay", cfg.MiniAppID, "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("wxpay_h5", codeURL), stats, nil
		})
	}
	if allowH5 && plugin.IsMobile(req.GetRequest().GetUa()) {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createWapOrder(ctx, req, cfg, order, "wxpay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespJump(payURL), stats, nil
		})
	}
	if allowMP {
		if plugin.IsWeChat(req.GetRequest().GetUa()) {
			if cfg.MPAppID == "" || cfg.MPAppSecret == "" {
				return plugin.RespError("支付通道未绑定微信公众号"), nil
			}
			code := queryParam(req, "code")
			redirectURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
			openID, authURL, err := wechatpay.GetOpenid(ctx, wechatpay.MPAuthParams{AppID: cfg.MPAppID, AppSecret: cfg.MPAppSecret, Code: code, RedirectURL: redirectURL, State: order.GetTradeNo()})
			if err != nil {
				return plugin.RespError(err.Error()), nil
			}
			if authURL != "" {
				return plugin.RespJump(authURL), nil
			}
			return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
				payInfo, stats, err := createPublicOrder(ctx, req, cfg, order, "wxpay", cfg.MPAppID, "1", openID)
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(payInfo)
				if err != nil {
					return nil, stats, err
				}
				return plugin.RespPageData("wxpay_jspay", map[string]any{"js_api_parameters": jsParams}), stats, nil
			})
		}
		qrURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
		return plugin.RespPageURL("wxpay_qrcode", qrURL), nil
	}
	if allowScan {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createScanOrder(ctx, req, cfg, order, "wxpay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("wxpay_qrcode", payURL), stats, nil
		})
	}
	return plugin.RespError("当前通道未开启微信支付方式"), nil
}

func bank(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	modes := modeSet(cfg.Biztypes)
	if allowMode(modes, "1") {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createPublicOrder(ctx, req, cfg, order, "bank", "1", "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("bank_qrcode", payURL), stats, nil
		})
	}
	return plugin.RespError("当前通道未开启银联支付方式"), nil
}

func createPublicOrder(ctx context.Context, req *proto.InvokeContext, cfg *helipayConfig, order *proto.OrderSnapshot, payType, appid, isRaw, openid string) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := strings.TrimRight(globalCfg.NotifyDomain, "/") + "/pay/notify/" + order.GetTradeNo()
	productName := globalCfg.GoodsName
	params := map[string]string{
		"P1_bizType":         "AppPayPublic",
		"P2_orderId":         order.GetTradeNo(),
		"P3_customerNumber":  cfg.AppID,
		"P4_payType":         "PUBLIC",
		"P5_appid":           appid,
		"P6_deviceInfo":      "",
		"P7_isRaw":           isRaw,
		"P8_openid":          openid,
		"P9_orderAmount":     toYuan(order.GetReal()),
		"P10_currency":       "CNY",
		"P11_appType":        mapAppPayType(payType),
		"P12_notifyUrl":      notifyURL,
		"P13_successToUrl":   buildPayURL(req, order, nil),
		"P14_orderIp":        order.GetIpBuyer(),
		"P15_goodsName":      productName,
		"P16_goodsDetail":    "",
		"P17_limitCreditPay": "",
		"P18_desc":           "",
	}
	if cfg.AppMchID != "" {
		params["P20_subMerchantId"] = cfg.AppMchID
	}
	return createOrder(ctx, params, cfg.AppKey)
}

func createAppletOrder(ctx context.Context, req *proto.InvokeContext, cfg *helipayConfig, order *proto.OrderSnapshot, payType, appid, isRaw, openid string) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := strings.TrimRight(globalCfg.NotifyDomain, "/") + "/pay/notify/" + order.GetTradeNo()
	productName := globalCfg.GoodsName
	params := map[string]string{
		"P1_bizType":         "AppPayApplet",
		"P2_orderId":         order.GetTradeNo(),
		"P3_customerNumber":  cfg.AppID,
		"P4_payType":         "APPLET",
		"P5_appid":           appid,
		"P6_deviceInfo":      "",
		"P7_isRaw":           isRaw,
		"P8_openid":          openid,
		"P9_orderAmount":     toYuan(order.GetReal()),
		"P10_currency":       "CNY",
		"P11_appType":        mapAppPayType(payType),
		"P12_notifyUrl":      notifyURL,
		"P13_successToUrl":   buildPayURL(req, order, nil),
		"P14_orderIp":        order.GetIpBuyer(),
		"P15_goodsName":      productName,
		"P16_goodsDetail":    "",
		"P17_limitCreditPay": "",
		"P18_desc":           "",
	}
	if cfg.AppMchID != "" {
		params["P20_subMerchantId"] = cfg.AppMchID
	}
	return createOrder(ctx, params, cfg.AppKey)
}

func createWapOrder(ctx context.Context, req *proto.InvokeContext, cfg *helipayConfig, order *proto.OrderSnapshot, payType string) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := strings.TrimRight(globalCfg.NotifyDomain, "/") + "/pay/notify/" + order.GetTradeNo()
	productName := globalCfg.GoodsName
	params := map[string]string{
		"P1_bizType":        "AppPayH5WFT",
		"P2_orderId":        order.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
		"P4_orderAmount":    toYuan(order.GetReal()),
		"P5_currency":       "CNY",
		"P6_orderIp":        order.GetIpBuyer(),
		"P7_notifyUrl":      notifyURL,
		"P8_appPayType":     mapAppPayType(payType),
		"P9_payType":        "WAP",
		"P10_appName":       "短剧剧场",
		"P11_deviceInfo":    "iOS_WAP",
		"P12_applicationId": strings.TrimRight(globalCfg.SiteDomain, "/"),
		"P13_goodsName":     productName,
		"P14_goodsDetail":   "",
		"P15_desc":          "",
		"nonRawMode":        "0",
		"isRaw":             "0",
		"successToUrl":      buildPayURL(req, order, nil),
	}
	if cfg.AppMchID != "" {
		params["subMerchantId"] = cfg.AppMchID
	}
	return createOrder(ctx, params, cfg.AppKey)
}

func createScanOrder(ctx context.Context, req *proto.InvokeContext, cfg *helipayConfig, order *proto.OrderSnapshot, payType string) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := strings.TrimRight(globalCfg.NotifyDomain, "/") + "/pay/notify/" + order.GetTradeNo()
	productName := globalCfg.GoodsName
	params := map[string]string{
		"P1_bizType":        "AppPay",
		"P2_orderId":        order.GetTradeNo(),
		"P3_customerNumber": cfg.AppID,
		"P4_payType":        "SCAN",
		"P5_orderAmount":    toYuan(order.GetReal()),
		"P6_currency":       "CNY",
		"P7_authcode":       "1",
		"P8_appType":        mapAppPayType(payType),
		"P9_notifyUrl":      notifyURL,
		"P10_successToUrl":  buildPayURL(req, order, nil),
		"P11_orderIp":       order.GetIpBuyer(),
		"P12_goodsName":     productName,
		"P13_goodsDetail":   "",
		"P14_desc":          "",
	}
	if cfg.AppMchID != "" {
		params["P15_subMerchantId"] = cfg.AppMchID
	}
	return createOrder(ctx, params, cfg.AppKey)
}

func createOrder(ctx context.Context, params map[string]string, apiKey string) (string, plugin.RequestStats, error) {
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, apiKey)
	if err != nil {
		return "", stats, err
	}
	if resp["rt2_retCode"] != "0000" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return "", stats, errors.New(msg)
	}
	payInfo := firstNotEmpty(resp["rt8_qrcode"], resp["rt8_payInfo"], resp["rt10_payInfo"], resp["rt9_wapurl"])
	if payInfo == "" {
		return "", stats, fmt.Errorf("下单成功但未返回支付地址")
	}
	return payInfo, stats, nil
}

func mapAppPayType(payType string) string {
	switch payType {
	case "alipay":
		return "ALIPAY"
	case "bank":
		return "UNIONPAY"
	case "wxpay":
		return "WXPAY"
	default:
		return ""
	}
}
