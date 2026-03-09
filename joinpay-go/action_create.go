package main

import (
	"context"
	"fmt"
	"net/http"
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
	biztypes := modeSet(cfg.Biztypes)
	allowQR := allowMode(biztypes, "1")
	allowH5 := allowMode(biztypes, "2")

	if allowH5 {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "ALIPAY_H5", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildH5Response(resp, "alipay_h5")
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
		})
	}
	if allowQR {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "ALIPAY_NATIVE", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildQRResponse(resp, "alipay_qrcode")
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
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
	biztypes := modeSet(cfg.Biztypes)
	allowQR := allowMode(biztypes, "1")
	allowH5 := allowMode(biztypes, "2")
	allowMP := allowMode(biztypes, "3")
	allowMini := allowMode(biztypes, "4")

	if allowMini {
		code := queryParam(req, "code")
		if code != "" {
			if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
				return plugin.RespJSON(map[string]any{"code": 1, "message": "支付通道未配置微信小程序"}), nil
			}
			openID, err := wechatpay.AppGetOpenid(ctx, wechatpay.MiniAuthParams{AppID: cfg.MiniAppID, AppSecret: cfg.MiniAppSecret, Code: code})
			if err != nil {
				return plugin.RespJSON(map[string]any{"code": 1, "message": err.Error()}), nil
			}
			return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_XCX", map[string]string{"q5_OpenId": openID, "q7_AppId": cfg.MiniAppID})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(resp["rc_Result"])
				if err != nil {
					return nil, stats, err
				}
				return plugin.RespJSON(map[string]any{"code": 0, "js_api_parameters": jsParams}), stats, nil
			})
		}
		if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
			return plugin.RespError("支付通道未配置微信小程序"), nil
		}
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

	if allowH5 {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_H5_PLUS", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildH5Response(resp, "wxpay_h5")
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
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
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_GZH", map[string]string{"q5_OpenId": openID, "q7_AppId": cfg.MPAppID})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(resp["rc_Result"])
				if err != nil {
					return nil, stats, err
				}
				return plugin.RespPageData("wxpay_jspay", map[string]any{"js_api_parameters": jsParams}), stats, nil
			})
		}
		qrURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
		return plugin.RespPageURL("wxpay_qrcode", qrURL), nil
	}

	if allowQR {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_NATIVE", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildQRResponse(resp, "wxpay_qrcode")
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
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
	biztypes := modeSet(cfg.Biztypes)
	allowQR := allowMode(biztypes, "1")
	allowH5 := allowMode(biztypes, "2")

	if plugin.IsMobile(req.GetRequest().GetUa()) && allowH5 {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "UNIONPAY_H5", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildDirectResponse(resp)
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
		})
	}
	if allowQR {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			resp, stats, err := createOrder(ctx, req, cfg, order, "UNIONPAY_NATIVE", nil)
			if err != nil {
				return nil, stats, err
			}
			result, err := buildQRResponse(resp, "bank_qrcode")
			if err != nil {
				return nil, stats, err
			}
			return result, stats, nil
		})
	}
	return plugin.RespError("当前通道未开启云闪付支付方式"), nil
}

func createOrder(ctx context.Context, req *proto.InvokeContext, cfg *joinpayConfig, order *proto.OrderSnapshot, frpCode string, extra map[string]string) (map[string]string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	siteDomain := strings.TrimRight(globalCfg.SiteDomain, "/")
	productName := globalCfg.GoodsName

	params := map[string]string{
		"p0_Version":     "2.6",
		"p1_MerchantNo":  cfg.AppID,
		"p2_OrderNo":     order.GetTradeNo(),
		"p3_Amount":      toYuan(order.GetReal()),
		"p4_Cur":         "1",
		"p5_ProductName": limitLength(productName, 30),
		"p6_ProductDesc": limitLength(productName, 300),
		"p7_Mp":          limitLength(order.GetParam(), 100),
		"p8_ReturnUrl":   siteDomain + "/pay/" + order.GetType() + "/" + order.GetTradeNo(),
		"p9_NotifyUrl":   notifyDomain + "/pay/notify/" + order.GetTradeNo(),
		"q1_FrpCode":     frpCode,
	}
	params["qa_TradeMerchantNo"] = cfg.AppMchID
	for k, v := range extra {
		params[k] = v
	}
	params["hmac"] = signJoinpay(params, joinpayPayRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayPayURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, err := decodeJSONStringMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if !verifyJoinpay(respStr, joinpayPayResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	if respStr["ra_Code"] != "100" {
		msg := respStr["rb_CodeMsg"]
		if msg == "" {
			msg = "接口通道返回为空"
		}
		return nil, stats, fmt.Errorf("[%s]%s", respStr["ra_Code"], msg)
	}
	return respStr, stats, nil
}
