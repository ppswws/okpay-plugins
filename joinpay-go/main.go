package main

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"okpay/payment/plugin"
	"okpay/payment/plugin/sdk/wechatpay"
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
		"balance":        balance,
		"transfer":       transfer,
		"transfernotify": transferNotify,
	})
}

func info(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
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

func create(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.HandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
	})
}

func alipay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
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
			return plugin.RespError(err.Error()), nil
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
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}
	return plugin.RespError("当前通道未开启支付宝支付方式"), nil
}

func wxpay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
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
		code := plugin.QueryParam(req, "code")
		if code != "" {
			if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
				return plugin.RespJSON(map[string]any{"code": 1, "message": "支付通道未配置微信小程序"}), nil
			}
			openID, err := wechatpay.AppGetOpenid(ctx, wechatpay.MiniAuthParams{
				AppID:     cfg.MiniAppID,
				AppSecret: cfg.MiniAppSecret,
				Code:      code,
			})
			if err != nil {
				return plugin.RespJSON(map[string]any{"code": 1, "message": err.Error()}), nil
			}
			result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_XCX", map[string]string{
					"q5_OpenId": openID,
					"q7_AppId":  cfg.MiniAppID,
				})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(resp["rc_Result"])
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
		if cfg.MiniAppID == "" || cfg.MiniAppSecret == "" {
			return plugin.RespError("支付通道未配置微信小程序"), nil
		}
		payURL := buildPayURL(req, order, nil)
		values := url.Values{}
		values.Set("real", strconv.FormatInt(order.Real, 10))
		values.Set("url", payURL)
		scheme, err := wechatpay.GenerateScheme(ctx, cfg.MiniAppID, cfg.MiniAppSecret, "page/pay", values.Encode())
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return plugin.RespPageURL("wxpay_h5", scheme), nil
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
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowMP {
		if plugin.IsWeChat(req.Raw.UserAgent) {
			if cfg.MPAppID == "" || cfg.MPAppSecret == "" {
				return plugin.RespError("支付通道未绑定微信公众号"), nil
			}
			code := plugin.QueryParam(req, "code")
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
				return plugin.RespError(err.Error()), nil
			}
			if authURL != "" {
				return plugin.RespJump(authURL), nil
			}
			result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
				resp, stats, err := createOrder(ctx, req, cfg, order, "WEIXIN_GZH", map[string]string{
					"q5_OpenId": openID,
					"q7_AppId":  cfg.MPAppID,
				})
				if err != nil {
					return nil, stats, err
				}
				jsParams, err := decodeJSONAnyMap(resp["rc_Result"])
				if err != nil {
					return nil, stats, err
				}
				return plugin.RespPageData("wxpay_jspay", map[string]any{"js_api_parameters": jsParams}), stats, nil
			})
			if err != nil {
				return plugin.RespError(err.Error()), nil
			}
			return result, nil
		}

		qrURL := buildPayURL(req, order, map[string]string{
			"t": fmt.Sprintf("%d", time.Now().Unix()),
		})
		return plugin.RespPageURL("wxpay_qrcode", qrURL), nil
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
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}
	return plugin.RespError("当前通道未开启微信支付方式"), nil
}

func bank(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowQR := plugin.AllowMode(biztypes, "1")
	allowH5 := plugin.AllowMode(biztypes, "2")

	if plugin.IsMobile(req.Raw.UserAgent) && allowH5 {
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
			return plugin.RespError(err.Error()), nil
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
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}
	return plugin.RespError("当前通道未开启云闪付支付方式"), nil
}

func query(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, _, err := queryOrder(ctx, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	switch resp["ra_Status"] {
	case "100":
		state = 1
	case "101":
		state = -1
	}
	queryResp := plugin.QueryStateResponse{
		State:      state,
		APITradeNo: resp["r5_TrxNo"],
	}
	return plugin.RespQuery(queryResp), nil
}

func notify(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("fail"),
		})
	}
	params := plugin.ParseRequestParams(req)
	if !verifyJoinpay(params, joinpayNotifyFields, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("sign_error"),
		})
	}
	if params["r6_Status"] != "100" {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("status=" + params["r6_Status"]),
		})
	}

	if order != nil {
		if params["r2_OrderNo"] != order.TradeNo {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeOrder,
				Result:  plugin.RespHTML("order_mismatch"),
			})
		}
		if order.Real != toCents(params["r3_Amount"]) {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeOrder,
				Result:  plugin.RespHTML("amount_mismatch"),
			})
		}
		_ = plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
			TradeNo:    order.TradeNo,
			APITradeNo: params["r7_TrxNo"],
			Buyer:      params["rd_OpenId"],
		})
	}
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeOrder,
		Result:  plugin.RespHTML("success"),
	})
}

func refund(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	refund := plugin.Refund(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	resp, stats, err := refundOrder(ctx, req, cfg, order, refund)
	if err != nil {
		refundResp := plugin.RefundStateResponse{
			State:       -1,
			APIRefundNo: "",
			ReqBody:     stats.ReqBody,
			RespBody:    stats.RespBody,
			Result:      err.Error(),
			ReqMs:       stats.ReqMs,
		}
		return plugin.RespRefund(refundResp), nil
	}
	state := 0
	if resp["ra_Status"] == "100" {
		state = 1
	}
	if resp["ra_Status"] == "101" {
		state = -1
	}
	refundResp := plugin.RefundStateResponse{
		State:       state,
		APIRefundNo: resp["r5_RefundTrxNo"],
		ReqBody:     stats.ReqBody,
		RespBody:    stats.RespBody,
		Result:      "",
		ReqMs:       stats.ReqMs,
	}
	return plugin.RespRefund(refundResp), nil
}

func refundNotify(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	refund := plugin.Refund(req)
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("fail"),
		})
	}
	params := plugin.ParseRequestParams(req)
	if !verifyJoinpay(params, joinpayRefundResponseFields, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("sign_error"),
		})
	}
	status := params["ra_Status"]
	if refund == nil {
		if status == "100" {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeRefund,
				Result:  plugin.RespHTML("success"),
			})
		}
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("status=" + status),
		})
	}
	if params["r3_RefundOrderNo"] != refund.RefundNo {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("refund_mismatch"),
		})
	}
	if refund.Amount != toCents(params["r4_RefundAmount"]) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("amount_mismatch"),
		})
	}
	if status == "100" {
		_ = plugin.CompleteRefund(ctx, req, plugin.CompleteRefundRequest{
			RefundNo:    refund.RefundNo,
			Status:      1,
			APIRefundNo: params["r5_RefundTrxNo"],
			RespBody:    params["rc_CodeMsg"],
		})
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("success"),
		})
	}
	if status == "101" {
		_ = plugin.CompleteRefund(ctx, req, plugin.CompleteRefundRequest{
			RefundNo:    refund.RefundNo,
			Status:      -1,
			APIRefundNo: params["r5_RefundTrxNo"],
			RespBody:    params["rc_CodeMsg"],
		})
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeRefund,
			Result:  plugin.RespHTML("status=" + status),
		})
	}
	_ = plugin.CompleteRefund(ctx, req, plugin.CompleteRefundRequest{
		RefundNo:    refund.RefundNo,
		Status:      0,
		APIRefundNo: params["r5_RefundTrxNo"],
		RespBody:    params["rc_CodeMsg"],
	})
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeRefund,
		Result:  plugin.RespHTML("status=" + status),
	})
}

func balance(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	balanceValue, _, err := queryBalance(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return plugin.RespBalance(balanceValue), nil
}

func transfer(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	transfer := plugin.Transfer(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	globalCfg := plugin.GlobalConfig(req)
	cfgMap := plugin.ChannelConfig(req)
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	params := map[string]string{
		"userNo":                cfg.AppID,
		"productCode":           "BANK_PAY_DAILY_ORDER",
		"requestTime":           time.Now().Format("2006-01-02 15:04:05"),
		"merchantOrderNo":       transfer.TradeNo,
		"receiverAccountNoEnc":  transfer.CardNo,
		"receiverNameEnc":       transfer.CardName,
		"receiverAccountType":   "201",
		"receiverBankChannelNo": plugin.MapString(cfgMap, "receiverBankChannelNo"),
		"paidAmount":            toYuan(transfer.Amount),
		"currency":              "201",
		"isChecked":             "202",
		"paidDesc":              "工资发放",
		"paidUse":               "201",
		"callbackUrl":           notifyDomain + "/pay/transfernotify/" + transfer.TradeNo,
		"firstProductCode":      "",
	}
	if cfg.AppMchID != "" {
		params["tradeMerchantNo"] = cfg.AppMchID
	}

	resp, stats, err := transferOrder(ctx, cfg, params)
	if err != nil {
		transferResp := plugin.TransferStateResponse{
			State:      -1,
			APITradeNo: "",
			ReqBody:    stats.ReqBody,
			RespBody:   stats.RespBody,
			Result:     err.Error(),
			ReqMs:      stats.ReqMs,
		}
		return plugin.RespTransfer(transferResp), nil
	}
	statusCode := resp["statusCode"]
	message := resp["message"]
	if statusCode == "2002" {
		if message == "" {
			message = "代付受理失败"
		}
		transferResp := plugin.TransferStateResponse{
			State:      -1,
			APITradeNo: "",
			ReqBody:    stats.ReqBody,
			RespBody:   stats.RespBody,
			Result:     message,
			ReqMs:      stats.ReqMs,
		}
		return plugin.RespTransfer(transferResp), nil
	}
	if statusCode != "2001" && statusCode != "2003" {
		if message == "" {
			message = "代付受理失败"
		}
		transferResp := plugin.TransferStateResponse{
			State:      -1,
			APITradeNo: "",
			ReqBody:    stats.ReqBody,
			RespBody:   stats.RespBody,
			Result:     message,
			ReqMs:      stats.ReqMs,
		}
		return plugin.RespTransfer(transferResp), nil
	}
	transferResp := plugin.TransferStateResponse{
		State:      0,
		APITradeNo: "",
		ReqBody:    stats.ReqBody,
		RespBody:   stats.RespBody,
		Result:     "",
		ReqMs:      stats.ReqMs,
	}
	return plugin.RespTransfer(transferResp), nil
}

func transferNotify(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	transfer := plugin.Transfer(req)
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("fail"),
		})
	}
	params := plugin.ParseRequestParams(req)
	if !verifyJoinpay(params, joinpayTransferNotifyFields, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("sign_error"),
		})
	}
	status := params["status"]
	apiTradeNo := params["platformSerialNo"]
	result := params["errorCodeDesc"]
	successStatus := map[string]bool{
		"205": true,
	}
	failStatus := map[string]bool{
		"204": true,
		"208": true,
		"214": true,
	}
	if transfer == nil {
		if successStatus[status] {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeTransfer,
				Result:  plugin.RespHTML("success"),
			})
		}
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("status=" + status),
		})
	}
	if successStatus[status] {
		_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
			TradeNo:    transfer.TradeNo,
			Status:     1,
			APITradeNo: apiTradeNo,
			Result:     result,
		})
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("success"),
		})
	}
	if failStatus[status] {
		_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
			TradeNo:    transfer.TradeNo,
			Status:     -1,
			APITradeNo: apiTradeNo,
			Result:     result,
		})
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("status=" + status),
		})
	}
	_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
		TradeNo:    transfer.TradeNo,
		Status:     0,
		APITradeNo: apiTradeNo,
		Result:     result,
	})
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeTransfer,
		Result:  plugin.RespHTML("status=" + status),
	})
}
