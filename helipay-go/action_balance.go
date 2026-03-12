package main

import (
	"context"
	"errors"
	"time"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	balanceValue, stats, err := queryBalance(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return plugin.ResultBal(plugin.BizResultInput{
		Balance:    balanceValue,
		ChannelMsg: "余额查询成功",
		Stats:      stats,
	}), nil
}

func queryBalance(ctx context.Context, cfg *helipayConfig) (string, plugin.RequestStats, error) {
	params := map[string]string{
		"P1_bizType":        "MerchantAccountQuery",
		"P2_customerNumber": cfg.AppID,
		"P3_timestamp":      time.Now().Format("20060102150405"),
	}
	resp, stats, err := sendRequestTo(ctx, helipayMerchantAPIURL, params, cfg.AppKey)
	if err != nil {
		return "", stats, err
	}
	if resp["rt2_retCode"] != "0000" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return "", stats, errors.New(msg)
	}
	balance := resp["rt15_amountToBeSettled"]
	if balance == "" {
		return "", stats, errors.New("余额为空")
	}
	return balance, stats, nil
}
