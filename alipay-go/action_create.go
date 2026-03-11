package main

import (
	"context"
	"fmt"
	"time"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func create(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	return plugin.CreateWithHandlers(ctx, req, map[string]plugin.CreateHandlerFunc{
		"alipay": alipayHandler,
	})
}

func alipayHandler(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	order := req.GetOrder()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	return lockOrderPage(ctx, order.GetTradeNo(), func() (*proto.PageResponse, plugin.RequestStats, error) {
		cfg, err := readConfig(req)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		notifyURL, returnURL := buildOrderURLs(req, order)
		client, err := newAliClient(cfg, notifyURL, returnURL)
		if err != nil {
			return nil, plugin.RequestStats{}, err
		}
		biztypes := modeSet(cfg.Biztypes)
		allowPage := allowMode(biztypes, "1")
		allowWap := allowMode(biztypes, "2")
		allowScan := allowMode(biztypes, "3")
		allowJSPay := allowMode(biztypes, "4")
		allowPreauth := allowMode(biztypes, "5")
		allowApp := allowMode(biztypes, "6")
		allowJSAPI := allowMode(biztypes, "7")
		allowOrderCode := allowMode(biztypes, "8")
		isMobile := plugin.IsMobile(req.GetRequest().GetUa())

		bm := basePayBody(req, order, cfg)
		reqBody := bm.JsonBody()

		// 仅在「电脑网站支付 + 非移动端」时走 pagePay，其它情况统一扫码支付。
		if allowPage && !isMobile {
			return pagePayAsHTML(ctx, client, bm, reqBody)
		}
		if allowOrderCode {
			orderCodeBody := cloneBodyMap(bm)
			orderCodeBody.Set("product_code", "QR_CODE_OFFLINE")
			return precreateAsQR(ctx, client, orderCodeBody, orderCodeBody.JsonBody())
		}
		if allowWap || allowScan || allowJSPay || allowPreauth || allowApp || allowJSAPI || allowOrderCode || allowPage {
			return precreateAsQR(ctx, client, bm, reqBody)
		}
		return nil, plugin.RequestStats{}, fmt.Errorf("当前通道未开启可用的支付方式")
	})
}

func pagePayAsHTML(ctx context.Context, client *alipay.Client, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	payURL, err := client.TradePagePay(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: payURL,
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	return plugin.RespJump(payURL), stats, nil
}

func precreateAsQR(ctx context.Context, trade tradePrecreateClient, bm gopay.BodyMap, reqBody string) (*proto.PageResponse, plugin.RequestStats, error) {
	start := time.Now()
	resp, err := trade.TradePrecreate(ctx, bm)
	stats := plugin.RequestStats{
		ReqBody:  reqBody,
		RespBody: marshalJSON(resp),
		ReqCount: 1,
		ReqMs:    int32(time.Since(start).Milliseconds()),
	}
	if err != nil {
		if stats.RespBody == "" {
			stats.RespBody = err.Error()
		}
		return nil, stats, err
	}
	if resp == nil || resp.Response == nil || resp.Response.QrCode == "" {
		if stats.RespBody == "" {
			stats.RespBody = "{}"
		}
		return nil, stats, fmt.Errorf("支付宝未返回二维码")
	}
	return plugin.RespPageURL("alipay_qrcode", resp.Response.QrCode), stats, nil
}

type tradePrecreateClient interface {
	TradePrecreate(ctx context.Context, bm gopay.BodyMap) (*alipay.TradePrecreateResponse, error)
}

func cloneBodyMap(src gopay.BodyMap) gopay.BodyMap {
	dst := make(gopay.BodyMap, len(src))
	for k, v := range src {
		dst[k] = v
	}
	return dst
}

func basePayBody(req *proto.InvokeContext, order *proto.OrderSnapshot, cfg *aliConfig) gopay.BodyMap {
	bm := make(gopay.BodyMap)
	globalCfg := readGlobalConfig(req)
	bm.Set("out_trade_no", order.GetTradeNo())
	bm.Set("total_amount", toYuan(order.GetReal()))
	bm.Set("subject", globalCfg.GoodsName)
	if order.GetIpBuyer() != "" {
		bm.SetBodyMap("business_params", func(m gopay.BodyMap) {
			m.Set("mc_create_trade_ip", order.GetIpBuyer())
		})
	}
	applyModeBizParams(cfg, bm, toYuan(order.GetReal()))
	return bm
}
