package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/go-pay/gopay"
	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func balance(ctx context.Context, req *proto.InvokeContext) (*proto.BalanceResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	userID := strings.TrimSpace(queryParam(req, "user_id"))
	if userID == "" {
		userID = strings.TrimSpace(queryParam(req, "alipay_user_id"))
	}
	if userID == "" {
		userID = strings.TrimSpace(queryParam(req, "alipay_open_id"))
	}
	if userID == "" {
		return nil, fmt.Errorf("缺少 user_id/alipay_user_id/alipay_open_id")
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return nil, err
	}
	bm := make(gopay.BodyMap)
	if isDigits(userID) && strings.HasPrefix(userID, "2088") {
		bm.Set("alipay_user_id", userID)
	} else {
		bm.Set("alipay_open_id", userID)
	}
	bm.Set("account_type", "ACCTRANS_ACCOUNT")
	applyModeBizParams(req, bm, "")
	resp, err := client.FundAccountQuery(ctx, bm)
	if err != nil {
		return nil, err
	}
	if resp == nil || resp.Response == nil {
		return plugin.RespBalance("0"), nil
	}
	if strings.TrimSpace(resp.Response.AvailableAmount) == "" {
		return plugin.RespBalance("0"), nil
	}
	return plugin.RespBalance(strings.TrimSpace(resp.Response.AvailableAmount)), nil
}
