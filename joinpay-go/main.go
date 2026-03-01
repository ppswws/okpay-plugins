package main

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"okpay/payment/plugin"
	"okpay/payment/plugin/wechatpay"
)

func main() {
	plugin.Serve(map[string]plugin.HandlerFunc{
		"info":           info,
		"create":         create,
		"alipay":         alipay,
		"wxpay":          wxpay,
		"bank":           bank,
		"query":          query,
		"notify":         notify,
		"refund":         refund,
		"refundnotify":   refundNotify,
		"transfer":       transfer,
		"transfernotify": transferNotify,
	})
}

func info(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return map[string]any{
		"id":         "joinpay",
		"name":       "汇聚支付",
		"link":       "https://www.joinpay.com/",
		"paytypes":   []string{"alipay", "wxpay", "bank"},
		"transtypes": []string{"bank"},
		"bindwxmp":   true,
		"bindwxa":    true,
		"inputs": map[string]plugin.InputField{
			"appid": {
				Name:     "商户编号",
				Type:     "input",
				Note:     "对应 p1_MerchantNo",
				Required: true,
			},
			"appkey": {
				Name:     "商户密钥",
				Type:     "input",
				Note:     "MD5 密钥",
				Required: true,
			},
			"appmchid": {
				Name:     "报备商户号",
				Type:     "input",
				Note:     "对应 qa_TradeMerchantNo",
				Required: true,
			},
			"biztype_alipay": {
				Name: "支付宝方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "支付宝扫码",
					"2": "支付宝H5",
				},
			},
			"biztype_wxpay": {
				Name: "微信方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "微信扫码",
					"2": "微信H5",
					"3": "微信公众号",
					"4": "微信小程序",
				},
			},
			"biztype_bank": {
				Name: "云闪付方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "云闪付扫码",
					"2": "云闪付H5",
				},
			},
		},
		"note": "请确认已完成汇聚支付商户报备并获取商户密钥（公众号/小程序支付需配置 AppID/AppSecret）。",
	}, nil
}

func create(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.HandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
	})
}

func alipay(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowQR := plugin.AllowMode(biztypes, "1")
	allowH5 := plugin.AllowMode(biztypes, "2")

	if allowH5 {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}
	if allowQR {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}
	return map[string]any{"type": "error", "msg": "当前通道未开启支付宝支付方式"}, nil
}

func wxpay(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)

	allowQR := plugin.AllowMode(biztypes, "1")
	allowH5 := plugin.AllowMode(biztypes, "2")
	allowMP := plugin.AllowMode(biztypes, "3")
	allowMini := plugin.AllowMode(biztypes, "4")

	if allowMini {
		code := plugin.GetQuery(req, "code")
		if code != "" {
			if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
				return map[string]any{"type": "json", "data": map[string]any{"code": 1, "message": "支付通道未配置微信小程序"}}, nil
			}
			openID, err := wechatpay.AppGetOpenid(ctx, wechatpay.MiniAuthParams{
				AppID:     cfg.MiniAppID,
				AppSecret: cfg.MiniAppSecret,
				Code:      code,
			})
			if err != nil {
				return map[string]any{"type": "json", "data": map[string]any{"code": 1, "message": err.Error()}}, nil
			}
			result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_XCX", map[string]string{
					"q5_OpenId": openID,
					"q7_AppId":  cfg.MiniAppID,
				})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := parseJSONAny(resp["rc_Result"])
				if err != nil {
					return nil, stats, err
				}
				return map[string]any{"type": "json", "data": map[string]any{"code": 0, "js_api_parameters": jsParams}}, stats, nil
			})
			if err != nil {
				return map[string]any{"type": "json", "data": map[string]any{"code": 1, "message": err.Error()}}, nil
			}
			return result, nil
		}
		if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
			return map[string]any{"type": "error", "msg": "支付通道未配置微信小程序"}, nil
		}
		payURL := buildPayURL(req, order, nil)
		values := url.Values{}
		values.Set("real", strconv.FormatInt(order.Real, 10))
		values.Set("url", payURL)
		scheme, err := wechatpay.GenerateScheme(ctx, cfg.MiniAppID, cfg.MiniAppSecret, "page/pay", values.Encode())
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return map[string]any{"type": "page", "page": "wxpay_h5", "url": scheme}, nil
	}

	if allowH5 {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}

	if allowMP {
		if plugin.IsWeChat(req.Request.UA) {
			if cfg.MPAppID == "" || cfg.MPAppSecret == "" {
				return map[string]any{"type": "error", "msg": "支付通道未绑定微信公众号"}, nil
			}
			code := plugin.GetQuery(req, "code")
			redirectURL := buildPayURL(req, order, map[string]string{
				"t": fmt.Sprintf("%d", time.Now().Unix()),
			})
			openID, authURL, err := wechatpay.GetOpenid(ctx, wechatpay.MPAuthParams{
				AppID:       cfg.MPAppID,
				AppSecret:   cfg.MPAppSecret,
				Code:        code,
				RedirectURL: redirectURL,
				State:       order.TradeNo,
			})
			if err != nil {
				return map[string]any{"type": "error", "msg": err.Error()}, nil
			}
			if authURL != "" {
				return map[string]any{"type": "jump", "url": authURL}, nil
			}
			result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_GZH", map[string]string{
					"q5_OpenId": openID,
					"q7_AppId":  cfg.MPAppID,
				})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := parseJSONAny(resp["rc_Result"])
				if err != nil {
					return nil, stats, err
				}
				return map[string]any{"type": "page", "page": "wxpay_jspay", "data": map[string]any{"js_api_parameters": jsParams}}, stats, nil
			})
			if err != nil {
				return map[string]any{"type": "error", "msg": err.Error()}, nil
			}
			return result, nil
		}

		qrURL := buildPayURL(req, order, map[string]string{
			"t": fmt.Sprintf("%d", time.Now().Unix()),
		})
		return map[string]any{"type": "page", "page": "wxpay_qrcode", "url": qrURL}, nil
	}

	if allowQR {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}
	return map[string]any{"type": "error", "msg": "当前通道未开启微信支付方式"}, nil
}

func bank(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowQR := plugin.AllowMode(biztypes, "1")
	allowH5 := plugin.AllowMode(biztypes, "2")

	if plugin.IsMobile(req.Request.UA) && allowH5 {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}
	if allowQR {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
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
		if err != nil {
			return map[string]any{"type": "error", "msg": err.Error()}, nil
		}
		return result, nil
	}
	return map[string]any{"type": "error", "msg": "当前通道未开启云闪付支付方式"}, nil
}

func query(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, err := queryOrder(ctx, cfg, order)
	if err != nil {
		return map[string]any{
			"state":        2,
			"api_trade_no": "",
			"req_body":     stats.ReqBody,
			"resp_body":    stats.RespBody,
			"req_ms":       stats.ReqMs,
		}, nil
	}
	state := 0
	switch resp["ra_Status"] {
	case "100":
		state = 1
	case "101":
		state = 2
	}
	return map[string]any{
		"state":        state,
		"api_trade_no": resp["r5_TrxNo"],
		"req_body":     stats.ReqBody,
		"resp_body":    stats.RespBody,
		"req_ms":       stats.ReqMs,
	}, nil
}

func notify(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	cfg, err := readConfig(req)
	if err != nil {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}
	params := reqParams(req)
	if !verifyJoinpay(params, joinpayNotifyFields, cfg.AppKey) {
		return map[string]any{"type": "html", "data": "sign_error"}, nil
	}
	if params["r6_Status"] != "100" {
		return map[string]any{"type": "html", "data": "status=" + params["r6_Status"]}, nil
	}

	if order != nil {
		if params["r2_OrderNo"] != order.TradeNo {
			return map[string]any{"type": "html", "data": "order_mismatch"}, nil
		}
		if order.Real != toCents(params["r3_Amount"]) {
			return map[string]any{"type": "html", "data": "amount_mismatch"}, nil
		}
		_ = plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
			TradeNo:    order.TradeNo,
			APITradeNo: params["r7_TrxNo"],
			Buyer:      params["rd_OpenId"],
		})
	}
	return map[string]any{"type": "html", "data": "success"}, nil
}

func refund(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	order := plugin.DecodeOrder(req.Order)
	refund := plugin.DecodeRefund(req.Refund)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, err := refundOrder(ctx, req, cfg, order, refund)
	if err != nil {
		return map[string]any{
			"state":         2,
			"api_refund_no": "",
			"req_body":      stats.ReqBody,
			"resp_body":     stats.RespBody,
			"req_ms":        stats.ReqMs,
		}, nil
	}
	return map[string]any{
		"state":         0,
		"api_refund_no": resp["r5_RefundTrxNo"],
		"req_body":      stats.ReqBody,
		"resp_body":     stats.RespBody,
		"req_ms":        stats.ReqMs,
	}, nil
}

func refundNotify(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	refund := plugin.DecodeRefund(req.Refund)
	cfg, err := readConfig(req)
	if err != nil {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}
	params := reqParams(req)
	if !verifyJoinpay(params, joinpayRefundResponseFields, cfg.AppKey) {
		return map[string]any{"type": "html", "data": "sign_error"}, nil
	}
	status := params["ra_Status"]
	if refund == nil {
		if status == "100" {
			return map[string]any{"type": "html", "data": "success"}, nil
		}
		return map[string]any{"type": "html", "data": "status=" + status}, nil
	}
	if params["r3_RefundOrderNo"] != refund.RefundNo {
		return map[string]any{"type": "html", "data": "refund_mismatch"}, nil
	}
	if refund.Amount != toCents(params["r4_RefundAmount"]) {
		return map[string]any{"type": "html", "data": "amount_mismatch"}, nil
	}
	if status == "100" {
		_ = plugin.CompleteRefund(ctx, req, plugin.CompleteRefundRequest{
			RefundNo:    refund.RefundNo,
			Status:      1,
			APIRefundNo: params["r5_RefundTrxNo"],
			RespBody:    params["rc_CodeMsg"],
		})
		return map[string]any{"type": "html", "data": "success"}, nil
	}
	if status != "100" {
		_ = plugin.CompleteRefund(ctx, req, plugin.CompleteRefundRequest{
			RefundNo:    refund.RefundNo,
			Status:      -1,
			APIRefundNo: params["r5_RefundTrxNo"],
			RespBody:    params["rc_CodeMsg"],
		})
	}
	return map[string]any{"type": "html", "data": "status=" + status}, nil
}

func transfer(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	transfer := plugin.DecodeTransfer(req.Transfer)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	cfgMap := plugin.DecodeConfig(req)
	notifyDomain := strings.TrimRight(fmt.Sprint(req.Config["notifydomain"]), "/")
	productCode := fmt.Sprint(cfgMap["productCode"])
	if productCode == "" {
		productCode = "BANK_PAY_ORDINARY_ORDER"
	}
	params := map[string]string{
		"userNo":                cfg.AppID,
		"productCode":           productCode,
		"requestTime":           time.Now().Format("2006-01-02 15:04:05"),
		"merchantOrderNo":       transfer.TradeNo,
		"receiverAccountNoEnc":  transfer.CardNo,
		"receiverNameEnc":       transfer.CardName,
		"receiverAccountType":   fmt.Sprint(cfgMap["receiverAccountType"]),
		"receiverBankChannelNo": fmt.Sprint(cfgMap["receiverBankChannelNo"]),
		"paidAmount":            toYuan(transfer.Amount),
		"currency":              fmt.Sprint(cfgMap["currency"]),
		"isChecked":             fmt.Sprint(cfgMap["isChecked"]),
		"paidDesc":              fmt.Sprint(cfgMap["paidDesc"]),
		"paidUse":               fmt.Sprint(cfgMap["paidUse"]),
		"callbackUrl":           notifyDomain + "/pay/transfernotify/" + transfer.TradeNo,
		"firstProductCode":      fmt.Sprint(cfgMap["firstProductCode"]),
	}
	if params["receiverAccountType"] == "" {
		params["receiverAccountType"] = "201"
	}
	if params["currency"] == "" {
		params["currency"] = "201"
	}
	if params["isChecked"] == "" {
		params["isChecked"] = "202"
	}
	if params["paidDesc"] == "" {
		params["paidDesc"] = "代付"
	}
	if params["paidUse"] == "" {
		params["paidUse"] = "201"
	}
	if cfg.AppMchID != "" {
		params["tradeMerchantNo"] = cfg.AppMchID
	}

	resp, stats, err := transferOrder(ctx, cfg, params)
	if err != nil {
		return map[string]any{"state": 2, "api_trade_no": "", "req_body": stats.ReqBody, "resp_body": err.Error(), "req_ms": stats.ReqMs}, nil
	}
	statusCode := fmt.Sprint(resp["statusCode"])
	message := fmt.Sprint(resp["message"])
	if statusCode != "2001" {
		if message == "" {
			message = "代付受理失败"
		}
		return map[string]any{"state": 2, "api_trade_no": "", "req_body": stats.ReqBody, "resp_body": message, "req_ms": stats.ReqMs}, nil
	}
	return map[string]any{"state": 0, "api_trade_no": "", "req_body": stats.ReqBody, "resp_body": stats.RespBody, "req_ms": stats.ReqMs}, nil
}

func transferNotify(ctx context.Context, req *plugin.CallRequest) (map[string]any, error) {
	transfer := plugin.DecodeTransfer(req.Transfer)
	cfg, err := readConfig(req)
	if err != nil {
		return map[string]any{"type": "html", "data": "fail"}, nil
	}
	params := reqParams(req)
	if !verifyJoinpay(params, joinpayTransferNotifyFields, cfg.AppKey) {
		return map[string]any{"type": "html", "data": "sign_error"}, nil
	}
	status := params["status"]
	apiTradeNo := params["platformSerialNo"]
	result := params["errorCodeDesc"]
	if transfer == nil {
		if status == "1" || status == "100" {
			return map[string]any{"type": "html", "data": "success"}, nil
		}
		return map[string]any{"type": "html", "data": "status=" + status}, nil
	}
	if status == "1" || status == "100" {
		_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
			TradeNo:    transfer.TradeNo,
			Status:     1,
			APITradeNo: apiTradeNo,
			Result:     result,
		})
		return map[string]any{"type": "html", "data": "success"}, nil
	}
	_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
		TradeNo:    transfer.TradeNo,
		Status:     -1,
		APITradeNo: apiTradeNo,
		Result:     result,
	})
	return map[string]any{"type": "html", "data": "status=" + status}, nil
}
