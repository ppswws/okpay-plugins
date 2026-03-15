package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	plugin "github.com/ppswws/okpay-plugin-sdk"
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

func wxpayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
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

	if allowH5 {
		if plugin.IsMobile(req.GetRequest().GetUa()) {
			return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
				payURL, stats, err := createWapOrder(ctx, req, cfg, order, "wxpay")
				if err != nil {
					return nil, stats, err
				}
				if strings.Contains(payURL, "h5pay.helipay.com") {
					return plugin.RespJump(payURL), stats, nil
				}
				return plugin.RespPageURL("wxpay_h5", payURL), stats, nil
			})
		}
		// 这里不能直接返回手机支付页地址。
		// 安卓端扫描部分链接生成的二维码会白屏，
		// 需要使用中转页地址来规避。
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

	if allowMP {
		if plugin.IsWeChat(req.GetRequest().GetUa()) {
			if cfg.MPAppID == "" || cfg.MPAppSecret == "" {
				return plugin.RespError("支付通道未绑定微信公众号"), nil
			}
			code := queryParam(req, "code")
			redirectURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
			if code == "" {
				authURL := plugin.BuildMPOAuthURL(cfg.MPAppID, redirectURL, order.GetTradeNo())
				if authURL == "" {
					return plugin.RespError("公众号参数缺失"), nil
				}
				return plugin.RespJump(authURL), nil
			}
			openID, err := plugin.GetMPOpenid(ctx, cfg.MPAppID, cfg.MPAppSecret, code)
			if err != nil {
				return plugin.RespError(err.Error()), nil
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

	if allowMini {
		if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
			return plugin.RespError("支付通道未绑定微信小程序"), nil
		}
		code := queryParam(req, "code")
		if code == "" {
			payURL := buildPayURL(req, order, nil)
			values := url.Values{}
			values.Set("real", strconv.FormatInt(order.GetReal(), 10))
			values.Set("url", payURL)
			scheme, err := plugin.GetMiniScheme(ctx, cfg.MiniAppID, cfg.MiniAppSecret, "page/pay", values.Encode())
			if err != nil {
				return plugin.RespError(err.Error()), nil
			}
			return plugin.RespPageURL("wxpay_h5", scheme), nil
		}
		openID, err := plugin.GetMiniOpenid(ctx, cfg.MiniAppID, cfg.MiniAppSecret, code)
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
	return plugin.RespError("当前通道未开启微信支付方式"), nil
}

func bankHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
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
		"successToUrl":      buildPayURL(req, order, nil),
	}

	if cfg.AppMchID != "" {
		params["subMerchantId"] = cfg.AppMchID
	}

	if payType == "wxpay" && cfg.MiniAppID != "" {
		params["appId"] = cfg.MiniAppID
		params["isRaw"] = "0"
	}

	payURL, stats, err := createOrder(ctx, params, cfg.AppKey)
	if err != nil {
		return "", stats, err
	}
	if payType == "wxpay" {
		openlink, err := jsoperatewxdataFromPayURL(ctx, payURL)
		if err == nil && openlink != "" {
			return openlink, stats, nil
		}
	}
	return payURL, stats, nil
}

func jsoperatewxdataFromPayURL(ctx context.Context, payURL string) (string, error) {
	rawPayURL := strings.TrimSpace(payURL)
	if rawPayURL == "" {
		return "", fmt.Errorf("支付链接为空")
	}
	u, err := url.Parse(rawPayURL)
	if err != nil {
		return "", fmt.Errorf("支付链接解析失败: %w", err)
	}
	qs := u.Query()
	appID := strings.TrimSpace(qs.Get("appid"))
	prepayID := strings.TrimSpace(qs.Get("prepayid"))
	sign := strings.TrimSpace(qs.Get("sign"))
	appType := strings.TrimSpace(qs.Get("apptype"))
	if appType == "" {
		appType = "TH5"
	}
	if appID == "" || prepayID == "" || sign == "" {
		return "", fmt.Errorf("支付链接参数不完整")
	}

	query := url.Values{}
	query.Set("appid", appID)
	query.Set("apptype", appType)
	query.Set("prepayid", prepayID)
	query.Set("sign", sign)

	qbaseReq := map[string]any{
		"function_name": "public",
		"data": "",
		"action":       1,
		"scene":        1,
		"call_id":      buildWXRequestID("-"),
		"cloudid_list": []string{},
	}
	qbaseActionData, err := marshalJSONNoEscape(map[string]any{
			"action":  "getUrlScheme",
			"query":   query.Encode(),
			"options": map[string]any{"envVersion": "release"},
	})
	if err != nil {
		return "", fmt.Errorf("序列化 qbase action data 失败: %w", err)
	}
	qbaseReq["data"] = qbaseActionData
	qbaseReqRaw, err := marshalJSONNoEscape(qbaseReq)
	if err != nil {
		return "", fmt.Errorf("序列化 qbase req 失败: %w", err)
	}
	payload := map[string]any{
		"appid": appID,
		"data": map[string]any{
			"qbase_api_name": "tcbapi_slowcallfunction_v2",
			"qbase_req":      qbaseReqRaw,
			"qbase_options": map[string]any{
				"appid": appID,
				"env":   "cloud1-5g6j2un4a478958c",
			},
			"qbase_meta": map[string]any{
				"session_id":       buildWXRequestID(""),
				"sdk_version":      "wx-web-sdk/1.1.0 (1602475903000)",
				"filter_user_info": false,
			},
			"cli_req_id": buildWXRequestID("_"),
		},
	}
	body, err := marshalJSONNoEscape(payload)
	if err != nil {
		return "", fmt.Errorf("序列化 jsoperatewxdata 请求失败: %w", err)
	}
	respBody, _, _, err := httpClient.Do(
		ctx,
		http.MethodPost,
		"https://servicewechat.com/wxa-qbase/jsoperatewxdata",
		body,
		"application/json;charset=UTF-8",
		map[string]string{
			"Referer": "https://h5pay.helipay.com/",
			"Origin":  "https://h5pay.helipay.com",
			"Accept":  "application/json, text/plain, */*",
		},
	)
	if err != nil {
		return "", err
	}

	var layer0 struct {
		BaseResp struct {
			Ret int `json:"ret"`
		} `json:"base_resp"`
		Data string `json:"data"`
	}
	if err := json.Unmarshal([]byte(respBody), &layer0); err != nil {
		return "", err
	}
	if layer0.BaseResp.Ret != 0 {
		return "", fmt.Errorf("jsoperatewxdata请求失败")
	}
	var layer1 struct {
		Data string `json:"data"`
	}
	if err := json.Unmarshal([]byte(layer0.Data), &layer1); err != nil {
		return "", err
	}
	var layer2 struct {
		Openlink string `json:"openlink"`
	}
	if err := json.Unmarshal([]byte(layer1.Data), &layer2); err != nil {
		return "", err
	}
	if strings.TrimSpace(layer2.Openlink) == "" {
		return "", fmt.Errorf("未获取到openlink")
	}
	return layer2.Openlink, nil
}

func buildWXRequestID(sep string) string {
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	if sep == "" {
		return ts
	}
	return ts + sep + randomFractionPart()
}

func randomFractionPart() string {
	// 保持与原实现一致的小数格式：0.后接长数字。
	n, err := rand.Int(rand.Reader, big.NewInt(9_000_000_000_000_000))
	if err != nil {
		return "0.1000000000000000"
	}
	return "0." + strconv.FormatInt(n.Int64()+1_000_000_000_000_000, 10)
}

func marshalJSONNoEscape(v any) (string, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return "", err
	}
	out := strings.TrimSuffix(buf.String(), "\n")
	return out, nil
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
