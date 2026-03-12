package main

import (
	"context"
	"strings"

	"github.com/go-pay/gopay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return nil, err
	}
	bm := make(gopay.BodyMap)
	if isDigits(cfg.AppMchID) && strings.HasPrefix(cfg.AppMchID, "2088") {
		bm.Set("alipay_user_id", cfg.AppMchID)
	} else {
		bm.Set("alipay_open_id", cfg.AppMchID)
	}
	bm.Set("account_type", "ACCTRANS_ACCOUNT")
	applyModeBizParams(cfg, bm, "")
	resp, err := client.FundAccountQuery(ctx, bm)
	if err != nil {
		return nil, err
	}
	balance := "0"
	if resp != nil && resp.Response != nil && resp.Response.AvailableAmount != "" {
		balance = resp.Response.AvailableAmount
	}
	return plugin.ResultBal(plugin.BizResultInput{
		Balance:    balance,
		ChannelMsg: "余额查询成功",
		Stats:      plugin.RequestStats{},
	}), nil
}
