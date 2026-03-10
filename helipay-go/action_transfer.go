package main

import (
	"context"
	"fmt"
	"strings"

	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func transfer(ctx context.Context, req *proto.InvokeContext) (*proto.TransferResponse, error) {
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
		return plugin.RespTransfer(-1, "", "", "", err.Error(), 0), nil
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
		return plugin.RespTransfer(-1, "", stats.ReqBody, stats.RespBody, err.Error(), stats.ReqMs), nil
	}
	if resp["rt2_retCode"] != "0000" && resp["rt2_retCode"] != "0001" {
		msg := resp["rt3_retMsg"]
		if msg == "" {
			msg = resp["rt2_retCode"]
		}
		return plugin.RespTransfer(-1, "", stats.ReqBody, stats.RespBody, msg, stats.ReqMs), nil
	}
	return plugin.RespTransfer(0, resp["rt6_serialNumber"], stats.ReqBody, stats.RespBody, "", stats.ReqMs), nil
}

func transferOrder(ctx context.Context, cfg *helipayConfig, params map[string]string) (map[string]string, plugin.RequestStats, error) {
	resp, stats, err := sendRequestTo(ctx, helipayAPIURL, params, cfg.AppKey)
	if err != nil {
		return nil, stats, err
	}
	return resp, stats, nil
}
