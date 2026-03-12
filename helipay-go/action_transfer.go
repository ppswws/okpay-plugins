package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func transfer(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	transfer := req.GetTransfer()
	if transfer == nil || transfer.GetTradeNo() == "" {
		return nil, fmt.Errorf("代付单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	bankCode, err := inferHelipayBankCode(transfer.GetBankName())
	if err != nil {
		return plugin.ResultFail(plugin.BizResultInput{
			ChannelMsg: err.Error(),
			Stats:      plugin.RequestStats{},
		}), nil
	}
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	params := map[string]string{
		"P1_bizType":         "Transfer",
		"P2_orderId":         transfer.GetTradeNo(),
		"P3_customerNumber":  cfg.AppID,
		"P4_amount":          toYuan(transfer.GetAmount()),
		"P5_bankCode":        bankCode,
		"P6_bankAccountNo":   transfer.GetCardNo(),
		"P7_bankAccountName": transfer.GetCardName(),
		"P8_biz":             "B2C",
		"P10_feeType":        "PAYER",
		"P11_urgency":        "true",
		"notifyUrl":          notifyDomain + "/pay/transfernotify/" + transfer.GetTradeNo(),
	}
	if bankUnionCode := strings.TrimSpace(transfer.GetBranchName()); bankUnionCode != "" {
		params["P9_bankUnionCode"] = bankUnionCode
	}
	resp, stats, err := transferOrder(ctx, cfg, params)
	if err != nil {
		return plugin.ResultFail(plugin.BizResultInput{
			ChannelMsg: err.Error(),
			Stats:      stats,
		}), nil
	}
	if resp["rt2_retCode"] != "0000" && resp["rt2_retCode"] != "0001" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return plugin.ResultFail(plugin.BizResultInput{
			ChannelCode: resp["rt2_retCode"],
			ChannelMsg:  msg,
			Stats:       stats,
		}), nil
	}
	result := resp["rt3_retMsg"]
	if result == "" {
		result = resp["rt2_retCode"]
	}
	return plugin.ResultPending(plugin.BizResultInput{
		APIBizNo:    resp["rt6_serialNumber"],
		ChannelCode: resp["rt2_retCode"],
		ChannelMsg:  result,
		Stats:       stats,
	}), nil
}

func transferOrder(ctx context.Context, cfg *helipayConfig, params map[string]string) (map[string]string, plugin.RequestStats, error) {
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	if err != nil {
		return nil, stats, err
	}
	return resp, stats, nil
}
