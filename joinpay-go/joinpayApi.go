package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"okpay/payment/plugin"
)

const (
	joinpayPayURL      = "https://trade.joinpay.com/tradeRt/uniPay"
	joinpayRefundURL   = "https://trade.joinpay.com/tradeRt/refund"
	joinpayQueryURL    = "https://trade.joinpay.com/tradeRt/queryOrder"
	joinpayTransferURL = "https://www.joinpay.com/payment/pay/singlePay"
	joinpayBalanceURL  = "https://www.joinpay.com/payment/pay/accountBalanceQuery"
)

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

var (
	joinpayPayRequestFields = []string{
		"p0_Version", "p1_MerchantNo", "p2_OrderNo", "p3_Amount", "p4_Cur", "p5_ProductName", "p6_ProductDesc",
		"p7_Mp", "p8_ReturnUrl", "p9_NotifyUrl", "q1_FrpCode", "q2_MerchantBankCode", "q3_SubMerchantNo",
		"q4_IsShowPic", "q5_OpenId", "q6_AuthCode", "q7_AppId", "q8_TerminalNo", "q9_TransactionModel",
		"qa_TradeMerchantNo", "qb_buyerId", "qh_HbFqNum", "qi_FqSellerPercen", "qj_DJPlan", "qk_DisablePayModel",
		"ql_TerminalIp", "qm_ContractId", "qn_SpecialInfo",
	}
	joinpayPayResponseFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_Cur", "r5_Mp", "r6_FrpCode", "r7_TrxNo",
		"r8_MerchantBankCode", "r9_SubMerchantNo", "ra_Code", "rb_CodeMsg", "rc_Result", "rd_Pic",
	}
	joinpayNotifyFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_Cur", "r5_Mp", "r6_Status", "r7_TrxNo",
		"r8_BankOrderNo", "r9_BankTrxNo", "ra_PayTime", "rb_DealTime", "rc_BankCode", "rd_OpenId",
		"re_DiscountAmount", "rh_cardType", "rj_Fee", "rk_FrpCode", "rl_ContractId", "rm_SpecialInfo", "ro_SettleAmount",
	}
	joinpayRefundRequestFields = []string{
		"p0_Version", "p1_MerchantNo", "p2_OrderNo", "p3_RefundOrderNo", "p4_RefundAmount", "p5_RefundReason",
		"p6_NotifyUrl", "pa_FundsAccount",
	}
	joinpayRefundResponseFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_RefundOrderNo", "r4_RefundAmount", "r5_RefundTrxNo",
		"ra_Status", "rb_Code", "rc_CodeMsg", "re_FundsAccount",
	}
	joinpayQueryRequestFields = []string{
		"p0_Version", "p1_MerchantNo", "p2_OrderNo",
	}
	joinpayQueryResponseFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_ProductName", "r5_TrxNo", "r6_BankTrxNo",
		"r7_Fee", "r8_FrpCode", "ra_Status", "rb_Code", "rc_CodeMsg", "rd_OpenId", "re_DiscountAmount",
		"rf_PayTime", "rh_cardType", "rj_BankCode", "rl_ContractId", "rm_SpecialInfo", "ro_SettleAmount",
	}
	joinpayTransferRequestFields = []string{
		"userNo", "tradeMerchantNo", "productCode", "requestTime", "merchantOrderNo", "receiverAccountNoEnc",
		"receiverNameEnc", "receiverAccountType", "receiverBankChannelNo", "paidAmount", "currency", "isChecked",
		"paidDesc", "paidUse", "callbackUrl", "firstProductCode",
	}
	joinpayTransferResponseFields = []string{
		"errorCode", "errorDesc", "userNo", "merchantOrderNo",
	}
	joinpayTransferNotifyFields = []string{
		"status", "errorCode", "errorCodeDesc", "userNo", "tradeMerchantNo", "merchantOrderNo", "platformSerialNo",
		"receiverAccountNoEnc", "receiverNameEnc", "paidAmount", "fee",
	}
	joinpayBalanceRequestFields = []string{
		"userNo",
	}
	joinpayBalanceResponseFields = []string{
		"userNo", "userName", "currency", "useAbleSettAmount", "availableSettAmountFrozen", "errorCode", "errorDesc",
	}
	joinpayBalanceResponseSignFields = []string{
		"statusCode", "message",
		"userNo", "userName", "currency", "useAbleSettAmount", "availableSettAmountFrozen", "errorCode", "errorDesc",
	}
)

type joinpayConfig struct {
	AppID         string
	AppKey        string
	AppMchID      string
	MPAppID       string
	MPAppSecret   string
	MiniAppID     string
	MiniAppSecret string
	Biztypes      []string
}

func decodeJSONAnyMap(raw string) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, err
	}
	if out == nil {
		return nil, fmt.Errorf("empty json object")
	}
	return out, nil
}

func decodeJSONStringMap(raw string) (map[string]string, error) {
	m, err := decodeJSONAnyMap(raw)
	if err != nil {
		return nil, err
	}
	out := make(map[string]string, len(m))
	for k, v := range m {
		switch val := v.(type) {
		case nil:
			out[k] = ""
		case string:
			out[k] = val
		case json.Number:
			out[k] = val.String()
		case bool:
			if val {
				out[k] = "true"
			} else {
				out[k] = "false"
			}
		default:
			out[k] = fmt.Sprint(val)
		}
	}
	return out, nil
}

func valueStringOrNumber(m map[string]any, key string) (string, bool) {
	v, ok := m[key]
	if !ok || v == nil {
		return "", false
	}
	switch val := v.(type) {
	case string:
		return val, true
	case json.Number:
		return val.String(), true
	case float64, float32, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
		return fmt.Sprint(val), true
	default:
		return "", false
	}
}

func requiredStringOrNumber(m map[string]any, key string) (string, error) {
	v, ok := valueStringOrNumber(m, key)
	if !ok || strings.TrimSpace(v) == "" {
		return "", fmt.Errorf("字段 %s 类型错误或为空", key)
	}
	return v, nil
}

func readConfig(req *plugin.InvokeRequestV2) (*joinpayConfig, error) {
	cfg := plugin.ChannelConfig(req)
	mp, _ := cfg["mp"].(map[string]any)
	mini, _ := cfg["mini"].(map[string]any)
	appid := plugin.MapString(cfg, "appid")
	appkey := plugin.MapString(cfg, "appkey")
	appmchid := plugin.MapString(cfg, "appmchid")
	if appid == "" || appkey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &joinpayConfig{
		AppID:         appid,
		AppKey:        appkey,
		AppMchID:      appmchid,
		MPAppID:       plugin.MapString(mp, "appid"),
		MPAppSecret:   plugin.MapString(mp, "appsecret"),
		MiniAppID:     plugin.MapString(mini, "appid"),
		MiniAppSecret: plugin.MapString(mini, "appsecret"),
		Biztypes:      plugin.ReadStringSlice(cfg["biztype"]),
	}, nil
}

func createOrder(
	ctx context.Context,
	req *plugin.InvokeRequestV2,
	cfg *joinpayConfig,
	order *plugin.OrderPayload,
	frpCode string,
	extra map[string]string,
) (map[string]string, plugin.RequestStats, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	siteDomain := strings.TrimRight(plugin.MapString(globalCfg, "sitedomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")

	params := map[string]string{
		"p0_Version":     "2.6",
		"p1_MerchantNo":  cfg.AppID,
		"p2_OrderNo":     order.TradeNo,
		"p3_Amount":      toYuan(order.Real),
		"p4_Cur":         "1",
		"p5_ProductName": limitLength(productName, 30),
		"p6_ProductDesc": limitLength(productName, 300),
		"p7_Mp":          limitLength(order.Param, 100),
		"p8_ReturnUrl":   siteDomain + "/pay/" + order.Type + "/" + order.TradeNo,
		"p9_NotifyUrl":   notifyDomain + "/pay/notify/" + order.TradeNo,
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

func refundOrder(
	ctx context.Context,
	req *plugin.InvokeRequestV2,
	cfg *joinpayConfig,
	order *plugin.OrderPayload,
	refund *plugin.RefundPayload,
) (map[string]string, plugin.RequestStats, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	params := map[string]string{
		"p0_Version":       "2.3",
		"p1_MerchantNo":    cfg.AppID,
		"p2_OrderNo":       order.TradeNo,
		"p3_RefundOrderNo": refund.RefundNo,
		"p4_RefundAmount":  toYuan(refund.Amount),
		"p5_RefundReason":  "申请退款",
		"p6_NotifyUrl":     notifyDomain + "/pay/refundnotify/" + refund.RefundNo,
	}
	params["hmac"] = signJoinpay(params, joinpayRefundRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayRefundURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, err := decodeJSONStringMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if !verifyJoinpay(respStr, joinpayRefundResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	if respStr["ra_Status"] != "100" {
		msg := respStr["rc_CodeMsg"]
		if msg == "" {
			msg = "退款失败"
		}
		return nil, stats, fmt.Errorf("[%s]%s", respStr["rb_Code"], msg)
	}
	return respStr, stats, nil
}

func queryOrder(
	ctx context.Context,
	cfg *joinpayConfig,
	order *plugin.OrderPayload,
) (map[string]string, plugin.RequestStats, error) {
	params := map[string]string{
		"p0_Version":    "2.6",
		"p1_MerchantNo": cfg.AppID,
		"p2_OrderNo":    order.TradeNo,
	}
	params["hmac"] = signJoinpay(params, joinpayQueryRequestFields, cfg.AppKey)

	reqBody := encodeParams(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayQueryURL, reqBody, "application/x-www-form-urlencoded")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respStr, err := decodeJSONStringMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	if !verifyJoinpay(respStr, joinpayQueryResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	return respStr, stats, nil
}

func transferOrder(
	ctx context.Context,
	cfg *joinpayConfig,
	params map[string]string,
) (map[string]string, plugin.RequestStats, error) {
	params["hmac"] = signJoinpay(params, joinpayTransferRequestFields, cfg.AppKey)
	reqBody, err := json.Marshal(params)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayTransferURL, string(reqBody), "application/json")
	stats := plugin.RequestStats{ReqBody: string(reqBody), RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respMap, err := decodeJSONAnyMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	dataAny, ok := respMap["data"]
	if !ok || dataAny == nil {
		return nil, stats, fmt.Errorf("响应解析失败")
	}
	dataObj, ok := dataAny.(map[string]any)
	if !ok || dataObj == nil {
		return nil, stats, fmt.Errorf("响应解析失败")
	}
	dataRaw := map[string]string{}
	for k, v := range dataObj {
		switch val := v.(type) {
		case string:
			dataRaw[k] = val
		case json.Number:
			dataRaw[k] = val.String()
		case nil:
			dataRaw[k] = ""
		default:
			dataRaw[k] = fmt.Sprint(val)
		}
	}
	if !verifyJoinpay(dataRaw, joinpayTransferResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	statusCode, err := requiredStringOrNumber(respMap, "statusCode")
	if err != nil {
		return nil, stats, err
	}
	message, _ := valueStringOrNumber(respMap, "message")
	return map[string]string{
		"statusCode": statusCode,
		"message":    message,
	}, stats, nil
}

func queryBalance(ctx context.Context, cfg *joinpayConfig) (string, plugin.RequestStats, error) {
	params := map[string]string{
		"userNo": cfg.AppID,
	}
	params["hmac"] = signJoinpay(params, joinpayBalanceRequestFields, cfg.AppKey)
	reqBodyBytes, err := json.Marshal(params)
	if err != nil {
		return "", plugin.RequestStats{}, err
	}
	reqBody := string(reqBodyBytes)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayBalanceURL, reqBody, "application/json")
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return "", stats, err
	}
	respMap, err := decodeJSONAnyMap(body)
	if err != nil {
		return "", stats, fmt.Errorf("响应解析失败: %w", err)
	}
	statusCode, err := requiredStringOrNumber(respMap, "statusCode")
	if err != nil {
		return "", stats, err
	}
	if statusCode != "2001" {
		msg, _ := valueStringOrNumber(respMap, "message")
		if msg == "" {
			msg = "查询失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", statusCode, msg)
	}
	dataAny, ok := respMap["data"]
	if !ok || dataAny == nil {
		return "", stats, fmt.Errorf("响应解析失败")
	}
	dataObj, ok := dataAny.(map[string]any)
	if !ok || dataObj == nil {
		return "", stats, fmt.Errorf("响应解析失败")
	}
	dataRaw := map[string]string{}
	for k, v := range dataObj {
		switch val := v.(type) {
		case string:
			dataRaw[k] = val
		case json.Number:
			dataRaw[k] = val.String()
		case nil:
			dataRaw[k] = ""
		default:
			dataRaw[k] = fmt.Sprint(val)
		}
	}
	message, _ := valueStringOrNumber(respMap, "message")
	signData := map[string]string{
		"statusCode": statusCode,
		"message":    message,
		"hmac":       dataRaw["hmac"],
	}
	for _, k := range joinpayBalanceResponseFields {
		signData[k] = dataRaw[k]
	}
	if !verifyJoinpay(signData, joinpayBalanceResponseSignFields, cfg.AppKey) {
		return "", stats, fmt.Errorf("返回验签失败")
	}
	if errCode := strings.TrimSpace(dataRaw["errorCode"]); errCode != "" {
		errDesc := dataRaw["errorDesc"]
		if errDesc == "" {
			errDesc = "查询失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", errCode, errDesc)
	}
	balance := strings.TrimSpace(dataRaw["useAbleSettAmount"])
	if balance == "" {
		return "", stats, fmt.Errorf("余额为空")
	}
	return balance, stats, nil
}
