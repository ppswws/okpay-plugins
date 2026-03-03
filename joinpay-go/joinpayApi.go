package main

import (
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

func readConfig(req *plugin.CallRequest) (*joinpayConfig, error) {
	cfg := plugin.DecodeConfig(req)
	appid := plugin.String(cfg["appid"])
	appkey := plugin.String(cfg["appkey"])
	appmchid := plugin.String(cfg["appmchid"])
	if appid == "" || appkey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &joinpayConfig{
		AppID:         appid,
		AppKey:        appkey,
		AppMchID:      appmchid,
		MPAppID:       plugin.String(cfg["mp_appid"]),
		MPAppSecret:   plugin.String(cfg["mp_appsecret"]),
		MiniAppID:     plugin.String(cfg["mini_appid"]),
		MiniAppSecret: plugin.String(cfg["mini_appsecret"]),
		Biztypes:      plugin.ReadStringSlice(cfg["biztype"]),
	}, nil
}

func createOrder(
	ctx context.Context,
	req *plugin.CallRequest,
	cfg *joinpayConfig,
	order *plugin.OrderPayload,
	frpCode string,
	extra map[string]string,
) (map[string]string, plugin.RequestStats, error) {
	notifyDomain := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/")
	siteDomain := strings.TrimRight(plugin.String(req.Config["sitedomain"]), "/")
	productName := plugin.String(req.Config["goodsname"])

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
	resp, err := plugin.DecodeJSONMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	respStr := toStringMap(resp)
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
	req *plugin.CallRequest,
	cfg *joinpayConfig,
	order *plugin.OrderPayload,
	refund *plugin.RefundPayload,
) (map[string]string, plugin.RequestStats, error) {
	notifyDomain := strings.TrimRight(plugin.String(req.Config["notifydomain"]), "/")
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
	resp, err := plugin.DecodeJSONMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	respStr := toStringMap(resp)
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
	resp, err := plugin.DecodeJSONMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	respStr := toStringMap(resp)
	if !verifyJoinpay(respStr, joinpayQueryResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	return respStr, stats, nil
}

func transferOrder(
	ctx context.Context,
	cfg *joinpayConfig,
	params map[string]string,
) (map[string]any, plugin.RequestStats, error) {
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
	resp, err := plugin.DecodeJSONMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	dataAny, ok := resp["data"]
	if !ok {
		return resp, stats, fmt.Errorf("响应解析失败")
	}
	dataMap := map[string]any{}
	switch v := dataAny.(type) {
	case map[string]any:
		dataMap = v
	case string:
		dataMap, _ = plugin.DecodeJSONMap(v)
	default:
		dataMap, _ = plugin.DecodeJSONMap(plugin.String(v))
	}
	dataStr := toStringMap(dataMap)
	if !verifyJoinpay(dataStr, joinpayTransferResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	return resp, stats, nil
}
