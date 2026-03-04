package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"okpay/payment/plugin"
)

type huifuConfig struct {
	AppID         string
	ProductID     string
	AppKey        string
	AppPem        string
	AppMchID      string
	ProjectID     string
	SeqID         string
	MPAppID       string
	MPAppSecret   string
	MiniAppID     string
	MiniAppSecret string
	Biztypes      []string
}

func readConfig(req *plugin.InvokeRequestV2) (*huifuConfig, error) {
	cfg := plugin.ChannelConfig(req)
	mp, _ := cfg["mp"].(map[string]any)
	mini, _ := cfg["mini"].(map[string]any)
	out := &huifuConfig{
		AppID:         plugin.MapString(cfg, "appid"),
		ProductID:     plugin.MapString(cfg, "product_id"),
		AppKey:        plugin.MapString(cfg, "appkey"),
		AppPem:        plugin.MapString(cfg, "apppem"),
		AppMchID:      plugin.MapString(cfg, "appmchid"),
		ProjectID:     plugin.MapString(cfg, "project_id"),
		SeqID:         plugin.MapString(cfg, "seq_id"),
		MPAppID:       plugin.MapString(mp, "appid"),
		MPAppSecret:   plugin.MapString(mp, "appsecret"),
		MiniAppID:     plugin.MapString(mini, "appid"),
		MiniAppSecret: plugin.MapString(mini, "appsecret"),
		Biztypes:      plugin.ReadStringSlice(cfg["biztype"]),
	}
	if out.AppID == "" || out.ProductID == "" || out.AppKey == "" || out.AppPem == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return out, nil
}

func addOrder(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload, tradeType, subOpenID string) (string, plugin.RequestStats, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	siteDomain := strings.TrimRight(plugin.MapString(globalCfg, "sitedomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")
	ip := order.IPBuyer
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_date":   reqDate(order.TradeNo),
		"req_seq_id": order.TradeNo,
		"huifu_id":   huifuID,
		"trade_type": tradeType,
		"trans_amt":  toYuan(order.Real),
		"goods_desc": productName,
		"notify_url": notifyDomain + "/pay/notify/" + order.TradeNo,
		"risk_check_data": toJSONString(map[string]any{
			"ip_addr": ip,
		}),
	}
	switch tradeType {
	case "T_JSAPI", "T_MINIAPP":
		params["wx_data"] = toJSONString(map[string]any{
			"sub_openid":       subOpenID,
			"openid":           subOpenID,
			"device_info":      "4",
			"spbill_create_ip": ip,
		})
	case "A_JSAPI":
		params["alipay_data"] = toJSONString(map[string]any{
			"subject":  productName,
			"buyer_id": subOpenID,
		})
	case "A_NATIVE":
		params["alipay_data"] = toJSONString(map[string]any{
			"subject": productName,
		})
	case "T_NATIVE":
		params["wx_data"] = toJSONString(map[string]any{
			"product_id":       "01001",
			"spbill_create_ip": ip,
		})
	case "U_JSAPI":
		params["unionpay_data"] = toJSONString(map[string]any{
			"qr_code":     siteDomain,
			"customer_ip": ip,
			"user_id":     subOpenID,
		})
	}

	resp, stats, err := client.requestAPI(ctx, "/v3/trade/payment/jspay", params)
	if err != nil {
		return "", stats, err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000100" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return "", stats, fmt.Errorf("%s", msg)
	}
	if tradeType == "T_JSAPI" || tradeType == "T_MINIAPP" || tradeType == "A_JSAPI" || tradeType == "U_JSAPI" {
		return plugin.MapString(resp, "pay_info"), stats, nil
	}
	return plugin.MapString(resp, "qr_code"), stats, nil
}

func hostingOrder(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload, transType, requestType string) (string, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	siteName := plugin.MapString(globalCfg, "sitename")
	productName := plugin.MapString(globalCfg, "goodsname")
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_date":       reqDate(order.TradeNo),
		"req_seq_id":     order.TradeNo,
		"huifu_id":       huifuID,
		"trans_amt":      toYuan(order.Real),
		"goods_desc":     productName,
		"pre_order_type": "1",
		"hosting_data": toJSONString(map[string]any{
			"project_title": siteName,
			"project_id":    cfg.ProjectID,
			"callback_url":  buildPayURL(req, order, nil),
			"request_type":  requestType,
		}),
		"notify_url": notifyDomain + "/pay/notify/" + order.TradeNo,
		"trans_type": transType,
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/hosting/payment/preorder", params)
	if err != nil {
		return "", err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return "", fmt.Errorf("%s", msg)
	}
	return plugin.MapString(resp, "jump_url"), nil
}

func wxappHosting(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload, needScheme string) (map[string]any, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	miniData := map[string]any{"need_scheme": needScheme}
	if cfg.SeqID != "" {
		miniData["seq_id"] = cfg.SeqID
	}
	params := map[string]any{
		"pre_order_type": "3",
		"req_date":       reqDate(order.TradeNo),
		"req_seq_id":     order.TradeNo,
		"huifu_id":       huifuID,
		"trans_amt":      toYuan(order.Real),
		"goods_desc":     productName,
		"miniapp_data":   toJSONString(miniData),
		"notify_url":     notifyDomain + "/pay/notify/" + order.TradeNo,
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/hosting/payment/preorder", params)
	if err != nil {
		return nil, err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return nil, fmt.Errorf("%s", msg)
	}
	raw := plugin.MapString(resp, "miniapp_data")
	out, err := decodeJSONAnyMap(raw)
	if err != nil {
		return nil, fmt.Errorf("返回数据解析失败")
	}
	return out, nil
}

func aliappHosting(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload) (string, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"pre_order_type": "2",
		"req_date":       reqDate(order.TradeNo),
		"req_seq_id":     order.TradeNo,
		"huifu_id":       huifuID,
		"trans_amt":      toYuan(order.Real),
		"goods_desc":     productName,
		"app_data": toJSONString(map[string]any{
			"app_schema": buildPayURL(req, order, nil),
		}),
		"notify_url": notifyDomain + "/pay/notify/" + order.TradeNo,
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/hosting/payment/preorder", params)
	if err != nil {
		return "", err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return "", fmt.Errorf("%s", msg)
	}
	return plugin.MapString(resp, "jump_url"), nil
}

func quickpayOrder(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload, requestType string, gwType string, deviceType string) (string, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_seq_id":   order.TradeNo,
		"req_date":     reqDate(order.TradeNo),
		"huifu_id":     huifuID,
		"trans_amt":    toYuan(order.Real),
		"goods_desc":   productName,
		"request_type": requestType,
		"extend_pay_data": toJSONString(map[string]any{
			"goods_short_name": productName,
			"gw_chnnl_tp":      gwType,
			"biz_tp":           "100099",
		}),
		"terminal_device_data": toJSONString(map[string]any{
			"device_type": deviceType,
			"device_ip":   order.IPBuyer,
		}),
		"risk_check_data": toJSONString(map[string]any{
			"ip_addr": order.IPBuyer,
		}),
		"notify_url": notifyDomain + "/pay/notify/" + order.TradeNo,
		"front_url":  buildPayURL(req, order, nil),
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/onlinepayment/quickpay/frontpay", params)
	if err != nil {
		return "", err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" && code != "00000100" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return "", fmt.Errorf("%s", msg)
	}
	return plugin.MapString(resp, "form_url"), nil
}

func bankOrder(ctx context.Context, client *huifuClient, req *plugin.InvokeRequestV2, cfg *huifuConfig, order *plugin.OrderPayload, gwType string, deviceType string) (string, error) {
	globalCfg := plugin.GlobalConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	productName := plugin.MapString(globalCfg, "goodsname")
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_seq_id": order.TradeNo,
		"req_date":   reqDate(order.TradeNo),
		"huifu_id":   huifuID,
		"trans_amt":  toYuan(order.Real),
		"goods_desc": productName,
		"extend_pay_data": toJSONString(map[string]any{
			"goods_short_name": productName,
			"gw_chnnl_tp":      gwType,
			"biz_tp":           "100099",
		}),
		"terminal_device_data": toJSONString(map[string]any{
			"device_type": deviceType,
			"device_ip":   order.IPBuyer,
		}),
		"risk_check_data": toJSONString(map[string]any{
			"ip_addr": order.IPBuyer,
		}),
		"notify_url": notifyDomain + "/pay/notify/" + order.TradeNo,
		"front_url":  buildPayURL(req, order, nil),
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/onlinepayment/banking/frontpay", params)
	if err != nil {
		return "", err
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" && code != "00000100" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = plugin.MapString(resp, "bank_message")
		}
		if msg == "" {
			msg = "接口返回失败"
		}
		return "", fmt.Errorf("%s", msg)
	}
	return plugin.MapString(resp, "form_url"), nil
}

func refundOrder(ctx context.Context, client *huifuClient, cfg *huifuConfig, order *plugin.OrderPayload, refund *plugin.RefundPayload) (map[string]any, error) {
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_date":       time.Now().Format("20060102"),
		"req_seq_id":     refund.RefundNo,
		"huifu_id":       huifuID,
		"ord_amt":        toYuan(refund.Amount),
		"org_req_date":   reqDate(order.TradeNo),
		"org_req_seq_id": order.TradeNo,
	}
	resp, _, err := client.requestAPI(ctx, "/v3/trade/payment/scanpay/refund", params)
	return resp, err
}

func refundCombine(ctx context.Context, client *huifuClient, cfg *huifuConfig, order *plugin.OrderPayload, refund *plugin.RefundPayload) (map[string]any, error) {
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"req_date":       time.Now().Format("20060102"),
		"req_seq_id":     refund.RefundNo,
		"huifu_id":       huifuID,
		"ord_amt":        toYuan(refund.Amount),
		"org_req_date":   reqDate(order.TradeNo),
		"org_req_seq_id": order.TradeNo,
	}
	resp, _, err := client.requestAPI(ctx, "/v2/trade/hosting/payment/htRefund", params)
	return resp, err
}

func queryOrder(ctx context.Context, client *huifuClient, cfg *huifuConfig, order *plugin.OrderPayload) (map[string]any, error) {
	huifuID := cfg.AppID
	if cfg.AppMchID != "" {
		huifuID = cfg.AppMchID
	}
	params := map[string]any{
		"org_req_seq_id": order.TradeNo,
		"org_req_date":   reqDate(order.TradeNo),
		"huifu_id":       huifuID,
	}
	resp, _, err := client.requestAPI(ctx, "/v3/trade/payment/scanpay/query", params)
	return resp, err
}

func toJSONString(value any) string {
	raw, _ := json.Marshal(value)
	return string(raw)
}
