package main

import (
	"context"
	"crypto/md5"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"okpay/payment/plugin"
)

const helipayAPIURL = "https://pay.trx.helipay.com/trx/app/interface.action"

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
	ReqBody     string
	RespBody    string
	ReqMs       int32
}

func readConfig(req *plugin.CallRequest) (*helipayConfig, error) {
	cfg := plugin.DecodeConfig(req)
	appid := plugin.String(cfg["appid"])
	appkey := plugin.String(cfg["appkey"])
	if appid == "" || appkey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &helipayConfig{
		AppID:         appid,
		AppKey:        appkey,
		SM4Key:        plugin.String(cfg["sm4_key"]),
		AppMchID:      plugin.String(cfg["appmchid"]),
		MPAppID:       plugin.String(cfg["mp_appid"]),
		MPAppSecret:   plugin.String(cfg["mp_appsecret"]),
		MiniAppID:     plugin.String(cfg["mini_appid"]),
		MiniAppSecret: plugin.String(cfg["mini_appsecret"]),
		Biztypes:      plugin.ReadStringSlice(cfg["biztype"]),
	}, nil
}

func createPublicOrder(ctx context.Context, req *plugin.CallRequest, cfg *helipayConfig, order *plugin.OrderPayload, payType, appid, isRaw, openid string) (string, plugin.RequestStats, error) {
	notifyURL := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/") + "/pay/notify/" + order.TradeNo
	productName := plugin.String(req.Config["goodsname"])
	params := map[string]string{
		"P1_bizType":         "AppPayPublic",
		"P2_orderId":         order.TradeNo,
		"P3_customerNumber":  cfg.AppID,
		"P4_payType":         "PUBLIC",
		"P5_appid":           appid,
		"P6_deviceInfo":      "",
		"P7_isRaw":           isRaw,
		"P8_openid":          openid,
		"P9_orderAmount":     toYuan(order.Real),
		"P10_currency":       "CNY",
		"P11_appType":        mapAppPayType(payType),
		"P12_notifyUrl":      notifyURL,
		"P13_successToUrl":   buildPayURL(req, order, nil),
		"P14_orderIp":        order.IPBuyer,
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

func createAppletOrder(ctx context.Context, req *plugin.CallRequest, cfg *helipayConfig, order *plugin.OrderPayload, payType, appid string) (string, plugin.RequestStats, error) {
	notifyURL := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/") + "/pay/notify/" + order.TradeNo
	productName := plugin.String(req.Config["goodsname"])
	params := map[string]string{
		"P1_bizType":         "AppPayApplet",
		"P2_orderId":         order.TradeNo,
		"P3_customerNumber":  cfg.AppID,
		"P4_payType":         "APPLET",
		"P5_appid":           appid,
		"P6_deviceInfo":      "",
		"P7_isRaw":           "0",
		"P8_openid":          "1",
		"P9_orderAmount":     toYuan(order.Real),
		"P10_currency":       "CNY",
		"P11_appType":        mapAppPayType(payType),
		"P12_notifyUrl":      notifyURL,
		"P13_successToUrl":   buildPayURL(req, order, nil),
		"P14_orderIp":        order.IPBuyer,
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

func createWapOrder(ctx context.Context, req *plugin.CallRequest, cfg *helipayConfig, order *plugin.OrderPayload, payType string) (string, plugin.RequestStats, error) {
	notifyURL := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/") + "/pay/notify/" + order.TradeNo
	productName := plugin.String(req.Config["goodsname"])
	params := map[string]string{
		"P1_bizType":        "AppPayH5WFT",
		"P2_orderId":        order.TradeNo,
		"P3_customerNumber": cfg.AppID,
		"P4_orderAmount":    toYuan(order.Real),
		"P5_currency":       "CNY",
		"P6_orderIp":        order.IPBuyer,
		"P7_notifyUrl":      notifyURL,
		"P8_appPayType":     mapAppPayType(payType),
		"P9_payType":        "WAP",
		"P10_appName":       "短剧剧场",
		"P11_deviceInfo":    "iOS_WAP",
		"P12_applicationId": strings.TrimRight(plugin.String(req.Config["sitedomain"]), "/"),
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

func createScanOrder(ctx context.Context, req *plugin.CallRequest, cfg *helipayConfig, order *plugin.OrderPayload, payType string) (string, plugin.RequestStats, error) {
	notifyURL := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/") + "/pay/notify/" + order.TradeNo
	productName := plugin.String(req.Config["goodsname"])
	params := map[string]string{
		"P1_bizType":        "AppPay",
		"P2_orderId":        order.TradeNo,
		"P3_customerNumber": cfg.AppID,
		"P4_payType":        "SCAN",
		"P5_orderAmount":    toYuan(order.Real),
		"P6_currency":       "CNY",
		"P7_authcode":       "1",
		"P8_appType":        mapAppPayType(payType),
		"P9_notifyUrl":      notifyURL,
		"P10_successToUrl":  buildPayURL(req, order, nil),
		"P11_orderIp":       order.IPBuyer,
		"P12_goodsName":     productName,
		"P13_goodsDetail":   "",
		"P14_desc":          "",
	}
	if cfg.AppMchID != "" {
		params["P15_subMerchantId"] = cfg.AppMchID
	}
	return createOrder(ctx, params, cfg.AppKey)
}

func refundOrder(ctx context.Context, cfg *helipayConfig, refund *plugin.RefundPayload) (refundResult, error) {
	refundOrderID := refund.RefundNo
	if refundOrderID == "" {
		return refundResult{}, errors.New("refund_no 为空")
	}
	params := map[string]string{
		"P1_bizType":        "AppPayRefund",
		"P2_orderId":        refund.TradeNo,
		"P3_customerNumber": cfg.AppID,
		"P4_refundOrderId":  refundOrderID,
		"P5_amount":         toYuan(refund.Amount),
		"P6_callbackUrl":    "",
	}
	resp, stats, err := sendRequest(ctx, params, cfg.AppKey)
	if err != nil {
		return refundResult{ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, err
	}
	retCode := resp["rt2_retCode"]
	if retCode != "0000" && retCode != "0001" && retCode != "0002" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = retCode
		}
		return refundResult{ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, errors.New(msg)
	}
	apiRefundNo := resp["rt7_serialNumber"]
	if apiRefundNo == "" {
		apiRefundNo = resp["rt6_refundOrderNum"]
	}
	if apiRefundNo == "" {
		apiRefundNo = refundOrderID
	}
	return refundResult{APIRefundNo: apiRefundNo, RetCode: retCode, ReqBody: stats.ReqBody, RespBody: stats.RespBody, ReqMs: stats.ReqMs}, nil
}

func transferOrder(ctx context.Context, cfg *helipayConfig, params map[string]string) (map[string]string, plugin.RequestStats, error) {
	resp, stats, err := sendRequest(ctx, params, cfg.AppKey)
	if err != nil {
		return nil, stats, err
	}
	return resp, stats, nil
}

func createOrder(ctx context.Context, params map[string]string, apiKey string) (string, plugin.RequestStats, error) {
	resp, stats, err := sendRequest(ctx, params, apiKey)
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

func sendRequest(ctx context.Context, params map[string]string, apiKey string) (map[string]string, plugin.RequestStats, error) {
	params["signatureType"] = "MD5"
	params["sign"] = signRequest(params, apiKey)
	payload := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, helipayAPIURL, payload, "application/x-www-form-urlencoded;charset=UTF-8")
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
	if jsonMap, err := plugin.DecodeJSONMap(body); err == nil && len(jsonMap) > 0 {
		return toStringMap(jsonMap)
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

func signRequest(params map[string]string, apiKey string) string {
	plain := buildRequestPlain(params)
	plain = plain + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return fmt.Sprintf("%x", sum)
}

func verifyResponse(resp map[string]string, apiKey string) bool {
	sign := resp["sign"]
	if sign == "" {
		return true
	}
	plain := buildResponsePlain(resp)
	plain = plain + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return strings.EqualFold(sign, fmt.Sprintf("%x", sum))
}

func verifyNotify(payload map[string]string, apiKey string) bool {
	sign := payload["sign"]
	if sign == "" {
		return false
	}
	plain := buildNotifyPlain(payload)
	plain = plain + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return strings.EqualFold(sign, fmt.Sprintf("%x", sum))
}

func buildRequestPlain(params map[string]string) string {
	bizType := params["P1_bizType"]
	order := requestOrderByBizType(bizType)
	return buildPlainByOrder(params, order)
}

func buildResponsePlain(resp map[string]string) string {
	bizType := resp["rt1_bizType"]
	order := responseOrderByBizType(bizType)
	return buildPlainByOrder(resp, order)
}

func buildNotifyPlain(payload map[string]string) string {
	order := notifyOrder(payload)
	return buildPlainByOrder(payload, order)
}

func requestOrderByBizType(bizType string) []string {
	switch bizType {
	case "AppPay":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_orderAmount", "P6_currency", "P7_authcode", "P8_appType", "P9_notifyUrl", "P10_successToUrl", "P11_orderIp", "P12_goodsName", "P13_goodsDetail", "P14_desc"}
	case "AppPayPublic", "AppPayApplet":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_appid", "P6_deviceInfo", "P7_isRaw", "P8_openid", "P9_orderAmount", "P10_currency", "P11_appType", "P12_notifyUrl", "P13_successToUrl", "P14_orderIp", "P15_goodsName", "P16_goodsDetail", "P17_limitCreditPay", "P18_desc"}
	case "AppPayH5WFT":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_orderAmount", "P5_currency", "P6_orderIp", "P7_notifyUrl", "P8_appPayType", "P9_payType", "P10_appName", "P11_deviceInfo", "P12_applicationId", "P13_goodsName", "P14_goodsDetail", "P15_desc"}
	case "AppPayRefund":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_refundOrderId", "P5_amount", "P6_callbackUrl"}
	case "Transfer":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_amount", "P5_bankCode", "P6_bankAccountNo", "P7_bankAccountName", "P8_biz", "P9_bankUnionCode", "P10_feeType", "P11_urgency", "P12_summary", "notifyUrl", "payerName", "payerShowName", "payerAccountNo"}
	default:
		return []string{}
	}
}

func responseOrderByBizType(bizType string) []string {
	switch bizType {
	case "AppPay":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_qrcode", "rt9_wapurl", "rt10_orderAmount", "rt11_currency"}
	case "AppPayPublic":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_appid", "rt9_tokenId", "rt10_payInfo", "rt11_orderAmount", "rt12_currency"}
	case "AppPayH5WFT":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_appName", "rt8_payInfo", "rt9_orderAmount", "rt10_currency", "rt11_payType"}
	case "AppPayApplet":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_appid", "rt9_tokenId", "rt10_payInfo", "rt11_orderAmount", "rt12_currency"}
	case "AppPayRefund":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_refundOrderNum", "rt7_serialNumber", "rt8_amount", "rt9_currency"}
	case "Transfer":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber"}
	default:
		return []string{}
	}
}

func notifyOrder(payload map[string]string) []string {
	if payload["rt7_orderStatus"] != "" && payload["rt1_bizType"] == "Transfer" {
		return []string{"rt1_bizType", "rt2_retCode", "rt3_retMsg", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_orderStatus", "rt8_notifyType", "rt9_reason", "rt10_createDate", "rt11_completeDate"}
	}
	if payload["rt3_refundOrderId"] != "" {
		return []string{"rt1_customerNumber", "rt2_orderId", "rt3_refundOrderId", "rt4_systemSerial", "rt5_status", "rt6_amount", "rt7_currency", "rt8_timestamp"}
	}
	return []string{"rt1_customerNumber", "rt2_orderId", "rt3_systemSerial", "rt4_status", "rt5_orderAmount", "rt6_currency", "rt7_timestamp", "rt8_desc"}
}

func buildPlainByOrder(params map[string]string, order []string) string {
	plain := ""
	for _, key := range order {
		plain += "&" + params[key]
	}
	return plain
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

func firstNotEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
