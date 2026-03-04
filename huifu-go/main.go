package main

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"time"

	"okpay/payment/plugin"
	"okpay/payment/plugin/sdk/wechatpay"
)

func main() {
	plugin.Serve(map[string]plugin.HandlerFunc{
		"info":   info,
		"create": create,
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
		"ecny":   ecny,
		"query":  query,
		"notify": notify,
		"refund": refund,
	})
}

func info(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return map[string]any{
		"id":       "huifu",
		"name":     "汇付斗拱平台",
		"link":     "https://paas.huifu.com/",
		"paytypes": []string{"alipay", "wxpay", "bank", "ecny"},
		"bindwxmp": true,
		"bindwxa":  true,
		"inputs": map[string]plugin.InputField{
			"appid": {
				Name:     "汇付系统号",
				Type:     "input",
				Required: true,
			},
			"apppem": {
				Name:     "汇付公钥",
				Type:     "textarea",
				Required: true,
			},
			"appkey": {
				Name:     "商户私钥",
				Type:     "textarea",
				Required: true,
			},
			"appmchid": {
				Name:     "汇付子商户号",
				Type:     "input",
				Note:     "渠道商模式填写",
				Required: true,
			},
			"product_id": {
				Name:     "汇付产品号",
				Type:     "input",
				Required: true,
			},
			"project_id": {
				Name: "半支付托管项目号",
				Type: "input",
				Note: "托管H5/PC支付需要",
			},
			"seq_id": {
				Name: "托管小程序应用ID",
				Type: "input",
				Note: "托管小程序支付可选",
			},
			"biztype_alipay": {
				Name: "支付宝方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "扫码支付",
					"2": "托管H5/PC支付",
					"3": "托管小程序支付",
					"4": "JS支付",
				},
			},
			"biztype_wxpay": {
				Name: "微信方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "自有公众号/小程序支付",
					"2": "托管H5/PC支付",
					"3": "托管小程序支付",
				},
			},
			"biztype_bank": {
				Name: "银联方式",
				Type: "checkbox",
				Options: map[string]string{
					"1": "银联扫码",
					"4": "银联JS支付",
					"2": "快捷支付",
					"3": "网银支付",
				},
			},
		},
	}, nil
}

func create(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.HandlerFunc{
		"alipay": alipay,
		"wxpay":  wxpay,
		"bank":   bank,
		"ecny":   ecny,
	})
}

func alipay(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowScan := plugin.AllowMode(biztypes, "1")
	allowHosting := plugin.AllowMode(biztypes, "2")
	allowMini := plugin.AllowMode(biztypes, "3")
	allowJS := plugin.AllowMode(biztypes, "4")

	if allowMini {
		result, err := aliappHosting(ctx, client, req, cfg, order)
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return plugin.RespPageURL("alipay_h5", result), nil
	}

	if allowHosting {
		requestType := "P"
		if plugin.IsMobile(req.Raw.UserAgent) {
			requestType = "M"
		}
		jumpURL, err := hostingOrder(ctx, client, req, cfg, order, "A_JSAPI", requestType)
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		if plugin.IsAlipay(req.Raw.UserAgent) {
			return plugin.RespJump(jumpURL), nil
		}
		if plugin.IsMobile(req.Raw.UserAgent) {
			return plugin.RespPageURL("alipay_h5", jumpURL), nil
		}
		return plugin.RespPageURL("alipay_qrcode", jumpURL), nil
	}

	if allowJS {
		buyerID := pickBuyerID(order, req)
		if buyerID == "" {
			return plugin.RespError("缺少支付宝用户标识"), nil
		}
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			payInfo, stats, err := addOrder(ctx, client, req, cfg, order, "A_JSAPI", buyerID)
			if err != nil {
				return nil, stats, err
			}
			out, err := decodeJSONAnyMap(payInfo)
			if err != nil {
				return nil, stats, err
			}
			tradeNo := plugin.MapString(out, "tradeNO")
			return plugin.RespPageData("alipay_jspay", map[string]any{"alipay_trade_no": tradeNo, "redirect_url": "data.backurl"}), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowScan {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			codeURL, stats, err := addOrder(ctx, client, req, cfg, order, "A_NATIVE", "")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("alipay_qrcode", codeURL), stats, nil
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
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowSelf := plugin.AllowMode(biztypes, "1")
	allowHosting := plugin.AllowMode(biztypes, "2")
	allowMini := plugin.AllowMode(biztypes, "3")

	if allowMini {
		mini, err := wxappHosting(ctx, client, req, cfg, order, "Y")
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		if scheme, ok := mini["scheme_code"]; ok {
			if s, ok := scheme.(string); ok {
				return plugin.RespPageURL("wxpay_h5", s), nil
			}
			return plugin.RespError("托管小程序返回异常"), nil
		}
		return plugin.RespError("托管小程序返回异常"), nil
	}

	if allowSelf && plugin.IsWeChat(req.Raw.UserAgent) {
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
			payInfo, stats, err := addOrder(ctx, client, req, cfg, order, "T_JSAPI", openID)
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

	if allowSelf {
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
				payInfo, stats, err := addOrder(ctx, client, req, cfg, order, "T_MINIAPP", openID)
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

	if allowHosting {
		requestType := "P"
		if plugin.IsMobile(req.Raw.UserAgent) {
			requestType = "M"
		}
		jumpURL, err := hostingOrder(ctx, client, req, cfg, order, "T_JSAPI", requestType)
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return plugin.RespJump(jumpURL), nil
	}

	result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
		codeURL, stats, err := addOrder(ctx, client, req, cfg, order, "T_NATIVE", "")
		if err != nil {
			return nil, stats, err
		}
		return plugin.RespPageURL("wxpay_qrcode", codeURL), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return result, nil
}

func bank(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	allowUnion := plugin.AllowMode(biztypes, "1")
	allowQuick := plugin.AllowMode(biztypes, "2")
	allowBank := plugin.AllowMode(biztypes, "3")
	allowJS := plugin.AllowMode(biztypes, "4")

	if allowQuick {
		requestType := "P"
		gwType := "01"
		deviceType := "4"
		if plugin.IsMobile(req.Raw.UserAgent) {
			requestType = "M"
			gwType = "02"
			deviceType = "1"
		}
		jumpURL, err := quickpayOrder(ctx, client, req, cfg, order, requestType, gwType, deviceType)
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return plugin.RespJump(jumpURL), nil
	}

	if allowBank {
		gwType := "01"
		deviceType := "4"
		if plugin.IsMobile(req.Raw.UserAgent) {
			gwType = "02"
			deviceType = "1"
		}
		jumpURL, err := bankOrder(ctx, client, req, cfg, order, gwType, deviceType)
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return plugin.RespJump(jumpURL), nil
	}

	if allowJS {
		buyerID := pickBuyerID(order, req)
		if buyerID == "" {
			return plugin.RespError("缺少银联用户标识"), nil
		}
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			codeURL, stats, err := addOrder(ctx, client, req, cfg, order, "U_JSAPI", buyerID)
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespJump(codeURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	if allowUnion {
		result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
			codeURL, stats, err := addOrder(ctx, client, req, cfg, order, "U_NATIVE", "")
			if err != nil {
				return nil, stats, err
			}
			return plugin.RespPageURL("bank_qrcode", codeURL), stats, nil
		})
		if err != nil {
			return plugin.RespError(err.Error()), nil
		}
		return result, nil
	}

	return plugin.RespError("当前通道未开启银联支付方式"), nil
}

func ecny(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	result, err := plugin.LockOrderExt(ctx, req, order.TradeNo, func() (any, plugin.RequestStats, error) {
		codeURL, stats, err := addOrder(ctx, client, req, cfg, order, "D_NATIVE", "")
		if err != nil {
			return nil, stats, err
		}
		return plugin.RespPageURL("bank_qrcode", codeURL), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return result, nil
}

func query(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	resp, err := queryOrder(ctx, client, cfg, order)
	if err != nil {
		return nil, err
	}
	state := 0
	switch plugin.MapString(resp, "trans_stat") {
	case "S":
		state = 1
	case "F":
		state = -1
	}
	apiTradeNo := plugin.MapString(resp, "org_hf_seq_id")
	queryResp := plugin.QueryStateResponse{
		State:      state,
		APITradeNo: apiTradeNo,
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
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("fail"),
		})
	}
	params := plugin.ParseRequestParams(req)
	respData := params["resp_data"]
	sign := params["sign"]
	if respData == "" {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("no data"),
		})
	}
	if !client.checkNotifySign(respData, sign) {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("sign fail"),
		})
	}
	data, err := decodeJSONAnyMap(respData)
	if err != nil {
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML("sign fail"),
		})
	}
	tradeNo := ""
	if order != nil {
		tradeNo = order.TradeNo
	} else {
		tradeNo = plugin.MapString(data, "req_seq_id")
	}
	if plugin.MapString(data, "trans_stat") == "S" {
		if order != nil && plugin.MapString(data, "req_seq_id") == order.TradeNo {
			apiTradeNo := plugin.MapString(data, "hf_seq_id")
			buyer := ""
			if v, ok := data["alipay_response"]; ok {
				buyer = extractBuyerFromResp(v, "buyer_id")
			} else if v, ok := data["wx_response"]; ok {
				buyer = extractBuyerFromResp(v, "sub_openid")
			}
			_ = plugin.CompleteOrder(ctx, req, plugin.CompleteOrderRequest{
				TradeNo:    order.TradeNo,
				APITradeNo: apiTradeNo,
				Buyer:      buyer,
			})
		}
		resp := "RECV_ORD_ID_" + tradeNo
		return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
			BizType: plugin.BizTypeOrder,
			Result:  plugin.RespHTML(resp),
		})
	}
	return plugin.RespNotify(ctx, req, plugin.NotifyResponse{
		BizType: plugin.BizTypeOrder,
		Result:  plugin.RespHTML("resp_code fail"),
	})
}

func refund(ctx context.Context, req *plugin.InvokeRequestV2) (map[string]any, error) {
	order := plugin.Order(req)
	refund := plugin.Refund(req)
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newHuifuClient(*cfg)
	if err != nil {
		return nil, err
	}
	biztypes := plugin.ModeSet(cfg.Biztypes)
	useHostingRefund := plugin.AllowMode(biztypes, "2") || plugin.AllowMode(biztypes, "3")
	var resp map[string]any
	if useHostingRefund {
		resp, err = refundCombine(ctx, client, cfg, order, refund)
	} else {
		resp, err = refundOrder(ctx, client, cfg, order, refund)
	}
	if err != nil {
		refundResp := plugin.RefundStateResponse{
			State:       0,
			APIRefundNo: "",
			ReqBody:     "",
			RespBody:    "",
			Result:      err.Error(),
			ReqMs:       0,
		}
		return plugin.RespRefund(refundResp), nil
	}
	code := plugin.MapString(resp, "resp_code")
	if code != "00000000" && code != "00000100" {
		msg := plugin.MapString(resp, "resp_desc")
		if msg == "" {
			msg = "退款失败"
		}
		refundResp := plugin.RefundStateResponse{
			State:       0,
			APIRefundNo: "",
			ReqBody:     "",
			RespBody:    "",
			Result:      msg,
			ReqMs:       0,
		}
		return plugin.RespRefund(refundResp), nil
	}
	state := 0
	if code == "00000000" {
		state = 1
	}
	refundResp := plugin.RefundStateResponse{
		State:       state,
		APIRefundNo: plugin.MapString(resp, "hf_seq_id"),
		ReqBody:     "",
		RespBody:    "",
		Result:      "",
		ReqMs:       0,
	}
	return plugin.RespRefund(refundResp), nil
}

func extractBuyerFromResp(value any, key string) string {
	switch v := value.(type) {
	case map[string]any:
		return plugin.MapString(v, key)
	case string:
		if out, err := decodeJSONAnyMap(v); err == nil {
			return plugin.MapString(out, key)
		}
	}
	return ""
}
