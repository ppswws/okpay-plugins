package main

import (
	"context"
	"fmt"
	"math/rand/v2"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const sumapayCreateURL = "https://www.sumapay.com/wechatTransitGateway/merchant.do"

func create(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.CreateHandlerFunc{
		"alipay": alipayHandler,
		"wxpay":  wxpayHandler,
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
	return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
		payURL, stats, err := createAlipayOrder(ctx, req, cfg, order)
		if err != nil {
			return nil, stats, err
		}
		if strings.Contains(payURL, "sumapay.com") {
			return plugin.RespJump(payURL), stats, nil
		}
		return plugin.RespPageURL("alipay_qrcode", payURL), stats, nil
	})
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
	if plugin.IsMobile(req.GetRequest().GetUa()) {
		return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
			payURL, stats, err := createWxpayOrder(ctx, req, cfg, order)
			if err != nil {
				return nil, stats, err
			}
			if strings.Contains(payURL, "sumapay.com") {
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

func createAlipayOrder(ctx context.Context, req *proto.InvokeContext, cfg *sumapayConfig, order *proto.OrderSnapshot) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := orderNotifyURL(globalCfg, "notify/"+order.GetTradeNo())
	backURL := orderReturnURL(globalCfg, order)
	goodsName := strings.TrimSpace(globalCfg.GoodsName)
	params := map[string]string{
		"requestType":      "IOZ2010",
		"requestId":        order.GetTradeNo(),
		"requestStartTime": shortTradeTime(order.GetTradeNo()),
		"merchantCode":     cfg.MerchantCode,
		"totalBizType":     cfg.TotalBizType,
		"totalPrice":       toYuan(order.GetReal()),
		"goodsDesc":        goodsName,
		"noticeUrl":        notifyURL,
		"backUrl":          backURL,
		"terminalIp":       randomTerminalIP(),
		"userType":         "2",
		"userIdIdentity":   cfg.UserIDIdentity,
		"subMerchantId":    cfg.SubMerchantID,
		"productId":        "CQSJWS",
		"productName":      "元宝充值",
		"fund":             toYuan(order.GetReal()),
		"merAcct":          "CSSH",
		"bizType":          cfg.TotalBizType,
		"productNumber":    "1",
	}
	signText := concatByKeys(params, []string{
		"requestType", "requestId", "merchantCode", "totalBizType", "totalPrice",
		"goodsDesc", "noticeUrl", "backUrl", "userType", "userIdIdentity", "subMerchantId",
	})
	signature, err := signRSA256(cfg.MerchantPrivateKey, signText)
	if err != nil {
		return "", plugin.RequestStats{}, fmt.Errorf("生成签名失败: %w", err)
	}
	params["signature"] = signature

	resp, stats, err := postGBKJSON(ctx, sumapayCreateURL, params)
	if err != nil {
		return "", stats, err
	}
	if resp["result"] != "00000" {
		msg := strings.TrimSpace(resp["errorMsg"])
		if msg == "" {
			msg = "返回数据解析失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", resp["result"], msg)
	}
	verifyText := concatByKeys(resp, []string{"requestId", "result", "payUrl", "errorMsg"})
	if !verifyRSA256(cfg.FengfuPublicKey, verifyText, resp["signature"]) {
		return "", stats, fmt.Errorf("返回结果验签失败")
	}
	payURL := resolveSchemeURL(resp["payUrl"])
	if payURL == "" {
		return "", stats, fmt.Errorf("渠道未返回支付地址")
	}
	return payURL, stats, nil
}

func createWxpayOrder(ctx context.Context, req *proto.InvokeContext, cfg *sumapayConfig, order *proto.OrderSnapshot) (string, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	notifyURL := orderNotifyURL(globalCfg, "notify/"+order.GetTradeNo())
	backURL := orderReturnURL(globalCfg, order)
	goodsName := strings.TrimSpace(globalCfg.GoodsName)
	params := map[string]string{
		"requestType":      "IOZ1017",
		"requestId":        order.GetTradeNo(),
		"requestStartTime": shortTradeTime(order.GetTradeNo()),
		"merchantCode":     cfg.MerchantCode,
		"totalBizType":     cfg.TotalBizType,
		"totalPrice":       toYuan(order.GetReal()),
		"goodsDesc":        goodsName,
		"envFlag":          "3",
		"noticeUrl":        notifyURL,
		"backUrl":          backURL,
		"terminalIp":       randomTerminalIP(),
		"userType":         "2",
		"userIdIdentity":   cfg.UserIDIdentity,
		"subMerchantId":    cfg.SubMerchantID,
		"productId":        "CQSJWS",
		"productName":      "元宝充值",
		"fund":             toYuan(order.GetReal()),
		"merAcct":          "CSSH",
		"bizType":          cfg.TotalBizType,
		"productNumber":    "1",
	}
	signText := concatByKeys(params, []string{
		"requestType", "requestId", "merchantCode", "totalBizType", "totalPrice", "goodsDesc",
		"envFlag", "noticeUrl", "backUrl", "userType", "userIdIdentity", "subMerchantId",
	})
	signature, err := signRSA256(cfg.MerchantPrivateKey, signText)
	if err != nil {
		return "", plugin.RequestStats{}, fmt.Errorf("生成签名失败: %w", err)
	}
	params["signature"] = signature

	resp, stats, err := postGBKJSON(ctx, sumapayCreateURL, params)
	if err != nil {
		return "", stats, err
	}
	if resp["result"] != "00000" {
		msg := strings.TrimSpace(resp["errorMsg"])
		if msg == "" {
			msg = "返回数据解析失败"
		}
		return "", stats, fmt.Errorf("[%s]%s", resp["result"], msg)
	}
	verifyText := concatByKeys(resp, []string{"requestId", "result", "payUrl", "mpAppId", "mpPath", "errorMsg"})
	if !verifyRSA256(cfg.FengfuPublicKey, verifyText, resp["signature"]) {
		return "", stats, fmt.Errorf("返回结果验签失败")
	}
	payURL := resolveSchemeURL(resp["payUrl"])
	if payURL == "" {
		return "", stats, fmt.Errorf("渠道未返回支付地址")
	}
	return payURL, stats, nil
}

func resolveSchemeURL(payURL string) string {
	raw := strings.TrimSpace(payURL)
	if raw == "" {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	schemeRaw := strings.TrimSpace(u.Query().Get("scheme"))
	if schemeRaw == "" {
		return raw
	}
	decoded, err := url.QueryUnescape(schemeRaw)
	if err != nil {
		return schemeRaw
	}
	return decoded
}

func randomTerminalIP() string {
	prefixes := [...]string{
		"101.18.83.",
		"123.9.128.",
		"39.144.97.",
		"36.184.20.",
		"223.160.138.",
		"115.206.243.",
		"111.27.59.",
		"60.27.159.",
		"220.177.57.",
		"123.180.43.",
		"223.101.198.",
	}
	prefix := prefixes[rand.IntN(len(prefixes))]
	lastOctet := rand.IntN(255) + 1
	return prefix + strconv.Itoa(lastOctet)
}
