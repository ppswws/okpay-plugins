package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/go-pay/gopay"
	"github.com/go-pay/gopay/alipay"
	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
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
		modes := allowModes(cfg.Biztypes)
		allowPage := allowMode(modes, "1")
		allowWap := allowMode(modes, "2")
		allowScan := allowMode(modes, "3")
		allowJSPay := allowMode(modes, "4")
		allowPreauth := allowMode(modes, "5")
		allowApp := allowMode(modes, "6")
		allowJSAPI := allowMode(modes, "7")
		allowOrderCode := allowMode(modes, "8")

		isMobile := plugin.IsMobile(req.GetRequest().GetUa())
		isAlipay := plugin.IsAlipay(req.GetRequest().GetUa())
		bm := basePayBody(req, order)

		if allowWap && isMobile {
			payURL, err := client.TradeWapPay(ctx, bm)
			if err != nil {
				return nil, plugin.RequestStats{}, err
			}
			return plugin.RespJump(payURL), plugin.RequestStats{}, nil
		}
		if allowPage && !isMobile {
			payURL, err := client.TradePagePay(ctx, bm)
			if err != nil {
				return nil, plugin.RequestStats{}, err
			}
			return plugin.RespJump(payURL), plugin.RequestStats{}, nil
		}

		// 4/5/7/8 在当前上下文信息不足时先收敛为二维码模式，后续再细化成独立流程。
		if allowJSPay {
			if isAlipay && allowWap {
				payURL, err := client.TradeWapPay(ctx, bm)
				if err != nil {
					return nil, plugin.RequestStats{}, err
				}
				return plugin.RespJump(payURL), plugin.RequestStats{}, nil
			}
			return precreateAsQR(ctx, client, bm)
		}
		if allowScan {
			return precreateAsQR(ctx, client, bm)
		}
		if allowOrderCode {
			return precreateAsQR(ctx, client, bm)
		}
		if allowJSAPI {
			return precreateAsQR(ctx, client, bm)
		}
		if allowPreauth {
			return precreateAsQR(ctx, client, bm)
		}
		if allowApp {
			orderStr, err := client.TradeAppPay(ctx, bm)
			if err != nil {
				return nil, plugin.RequestStats{}, err
			}
			return plugin.RespJSON(map[string]any{"order_string": orderStr, "mode": "app"}), plugin.RequestStats{}, nil
		}
		if allowWap {
			payURL, err := client.TradeWapPay(ctx, bm)
			if err != nil {
				return nil, plugin.RequestStats{}, err
			}
			return plugin.RespJump(payURL), plugin.RequestStats{}, nil
		}
		if allowPage {
			payURL, err := client.TradePagePay(ctx, bm)
			if err != nil {
				return nil, plugin.RequestStats{}, err
			}
			return plugin.RespJump(payURL), plugin.RequestStats{}, nil
		}
		return nil, plugin.RequestStats{}, fmt.Errorf("当前通道未开启可用的支付宝支付方式")
	})
}

func precreateAsQR(ctx context.Context, trade tradePrecreateClient, bm gopay.BodyMap) (*proto.PageResponse, plugin.RequestStats, error) {
	resp, err := trade.TradePrecreate(ctx, bm)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	if resp == nil || resp.Response == nil || strings.TrimSpace(resp.Response.QrCode) == "" {
		return nil, plugin.RequestStats{}, fmt.Errorf("支付宝未返回二维码")
	}
	return plugin.RespPageURL("alipay_qrcode", strings.TrimSpace(resp.Response.QrCode)), plugin.RequestStats{}, nil
}

type tradePrecreateClient interface {
	TradePrecreate(ctx context.Context, bm gopay.BodyMap) (*alipay.TradePrecreateResponse, error)
}

func basePayBody(req *proto.InvokeContext, order *proto.OrderSnapshot) gopay.BodyMap {
	bm := make(gopay.BodyMap)
	bm.Set("out_trade_no", order.GetTradeNo())
	bm.Set("total_amount", toYuan(order.GetReal()))
	bm.Set("subject", orderSubject(req, order))
	if order.GetIpBuyer() != "" {
		bm.SetBodyMap("business_params", func(m gopay.BodyMap) {
			m.Set("mc_create_trade_ip", order.GetIpBuyer())
		})
	}
	applyModeBizParams(req, bm, toYuan(order.GetReal()))
	return bm
}
