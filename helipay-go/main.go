package main

import (
	"context"
	"fmt"
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
		"notify":         notify,
		"refund":         refund,
		"balance":        balance,
		"transfer":       transfer,
		"transfernotify": transferNotify,
	})
}

func info(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return map[string]any{
		"id":         "helipay",
		"name":       "合利宝",
		"link":       "https://www.helipay.com/",
		"paytypes":   []string{"wxpay", "alipay", "bank"},
		"transtypes": []string{"bank"},
		"bindwxmp":   true,
		"bindwxa":    true,
		"inputs": map[string]plugin.InputField{
			"appid": {
				Name:     "商户号 customerNumber",
				Type:     "input",
				Required: true,
			},
			"appmchid": {
				Name:     "报备编号",
				Type:     "input",
				Required: true,
			},
			"appkey": {
				Name:     "商户密钥(MD5签名密钥)",
				Type:     "input",
				Note:     "合利宝提供的签名密钥",
				Required: true,
			},
			"sm4_key": {
				Name:     "加密密钥(SM4)",
				Type:     "input",
				Note:     "合利宝提供的加密密钥",
				Required: true,
			},
			"biztype_alipay": {
				Name: "支付宝方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "公众号/JS/服务窗",
					"2": "小程序",
					"3": "WAP(H5)",
					"4": "扫码",
				},
			},
			"biztype_wxpay": {
				Name: "微信方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "公众号/JS",
					"2": "小程序",
					"3": "WAP(H5)",
					"4": "扫码",
				},
			},
			"biztype_bank": {
				Name: "银联方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "云闪付扫码",
				},
			},
		},
		"note": "配置说明：商户号/报备编号/签名密钥/加密密钥由合利宝提供",
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
	modes := plugin.ModeSet(cfg.Biztypes)
	allowPublic := plugin.AllowMode(modes, "1")
	allowMini := plugin.AllowMode(modes, "2")
	allowH5 := plugin.AllowMode(modes, "3")
	allowScan := plugin.AllowMode(modes, "4")

	if allowH5 && plugin.IsMobile(req.Raw.UserAgent) {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createWapOrder(ctx, req, cfg, order, "alipay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespJump(payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowScan || allowH5 {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createScanOrder(ctx, req, cfg, order, "alipay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowMini {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createAppletOrder(ctx, req, cfg, order, "alipay", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowPublic {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createPublicOrder(ctx, req, cfg, order, "alipay", "1", "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
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
	modes := plugin.ModeSet(cfg.Biztypes)
	allowPublic := plugin.AllowMode(modes, "1")
	allowMini := plugin.AllowMode(modes, "2")
	allowH5 := plugin.AllowMode(modes, "3")
	allowScan := plugin.AllowMode(modes, "4")

	if allowPublic && plugin.IsWeChat(req.Raw.UserAgent) {
		if cfg.MPAppID == "" || cfg.MPAppSecret == "" {
			return plugin.RespError("支付通道未绑定微信公众号"), nil
		}
		code := plugin.QueryParam(req, "code")
		redirectURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
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
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowMini {
		if cfg.MiniAppID == "" {
			return plugin.RespError("支付通道未绑定微信小程序"), nil
		}
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			codeURL, stats, err := createAppletOrder(ctx, req, cfg, order, "wxpay", cfg.MiniAppID)
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("wxpay_h5", codeURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowH5 && plugin.IsMobile(req.Raw.UserAgent) {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createWapOrder(ctx, req, cfg, order, "wxpay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespJump(payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowScan {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createScanOrder(ctx, req, cfg, order, "wxpay")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("wxpay_qrcode", payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowPublic {
		qrURL := buildPayURL(req, order, map[string]string{"t": fmt.Sprintf("%d", time.Now().Unix())})
		return plugin.RespPageURL("wxpay_qrcode", qrURL), nil
	}

	return plugin.RespError("当前通道未开启微信支付方式"), nil
}

func bank(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	modes := plugin.ModeSet(cfg.Biztypes)
	allowScan := plugin.AllowMode(modes, "1")
	if allowScan {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payURL, stats, err := createPublicOrder(ctx, req, cfg, order, "bank", "1", "0", "1")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("bank_qrcode", payURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}
	return plugin.RespError("当前通道未开启银联支付方式"), nil
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
	if !verifyNotify(params, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("fail"),
		})
	}
	if params["rt4_status"] != "SUCCESS" {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("fail"),
		})
	}
	if order != nil {
		if params["rt2_orderId"] != order.TradeNo {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeOrder,
				Result:  plugin.RespHTML("fail"),
			})
		}
		if order.Real != toCents(params["rt5_orderAmount"]) {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeOrder,
				Result:  plugin.RespHTML("amount_mismatch"),
			})
		}
	}
	if order != nil {
		if err := plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
			TradeNo:    order.TradeNo,
			APITradeNo: params["rt3_systemSerial"],
			Buyer:      params["rt10_openId"],
		}); err != nil {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeOrder,
				Result:  plugin.RespHTML("fail"),
			})
		}
	}
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeOrder,
		Result:  plugin.RespHTML("success"),
	})
}

func refund(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	refund := plugin.Refund(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	result, err := refundOrder(ctx, cfg, refund)
	if err != nil {
		refundResp := plugin.RefundStateResponse{
			State:       -1,
			APIRefundNo: "",
			ReqBody:     result.ReqBody,
			RespBody:    result.RespBody,
			Result:      err.Error(),
			ReqMs:       result.ReqMs,
		}
		return plugin.RespRefund(refundResp), nil
	}
	state := 1
	if result.RetCode == "0001" || result.RetCode == "0002" {
		state = 0
	}
	refundResp := plugin.RefundStateResponse{
		State:       state,
		APIRefundNo: result.APIRefundNo,
		ReqBody:     result.ReqBody,
		RespBody:    result.RespBody,
		Result:      "",
		ReqMs:       result.ReqMs,
	}
	return plugin.RespRefund(refundResp), nil
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
	notifyDomain := strings.TrimRight(plugin.MapString(globalCfg, "notifydomain"), "/")
	params := map[string]string{
		"P1_bizType":         "Transfer",
		"P2_orderId":         transfer.TradeNo,
		"P3_customerNumber":  cfg.AppID,
		"P4_amount":          toYuan(transfer.Amount),
		"P5_bankCode":        transfer.BankName,
		"P6_bankAccountNo":   transfer.CardNo,
		"P7_bankAccountName": transfer.CardName,
		"P8_biz":             "B2C",
		"P9_bankUnionCode":   transfer.BranchName,
		"P10_feeType":        "PAYER",
		"P11_urgency":        "true",
		"P12_summary":        "",
		"notifyUrl":          notifyDomain + "/pay/transfernotify/" + transfer.TradeNo,
		"payerName":          "",
		"payerShowName":      "",
		"payerAccountNo":     "",
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
	if resp["rt2_retCode"] != "0000" && resp["rt2_retCode"] != "0001" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		transferResp := plugin.TransferStateResponse{
			State:      -1,
			APITradeNo: "",
			ReqBody:    stats.ReqBody,
			RespBody:   stats.RespBody,
			Result:     msg,
			ReqMs:      stats.ReqMs,
		}
		return plugin.RespTransfer(transferResp), nil
	}
	// 0000/0001 为受理/已存在（按处理中处理）
	transferResp := plugin.TransferStateResponse{
		State:      0,
		APITradeNo: resp["rt6_serialNumber"],
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
	if !verifyNotify(params, cfg.AppKey) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("fail"),
		})
	}
	status := strings.ToUpper(params["rt7_orderStatus"])
	apiTradeNo := params["rt6_serialNumber"]
	result := params["rt9_reason"]
	if transfer == nil {
		if status == "SUCCESS" {
			return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
				BizType: plugin.BizTypeTransfer,
				Result:  plugin.RespHTML("success"),
			})
		}
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("success"),
		})
	}
	if status == "SUCCESS" {
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
	if status == "FAIL" || status == "REFUND" {
		_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
			TradeNo:    transfer.TradeNo,
			Status:     -1,
			APITradeNo: apiTradeNo,
			Result:     result,
		})
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeTransfer,
			Result:  plugin.RespHTML("success"),
		})
	}
	if status == "RECEIVE" || status == "INIT" || status == "DOING" {
		_ = plugin.CompleteTransfer(ctx, req, plugin.CompleteTransferRequest{
			TradeNo:    transfer.TradeNo,
			Status:     0,
			APITradeNo: apiTradeNo,
			Result:     result,
		})
	}
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeTransfer,
		Result:  plugin.RespHTML("success"),
	})
}
