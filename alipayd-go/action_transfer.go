package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/go-pay/gopay"
	"okpay/payment/plugin"
	"okpay/payment/plugin/proto"
)

func transfer(ctx context.Context, req *proto.InvokeContext) (*proto.TransferResponse, error) {
	if mode != modeStandard {
		return plugin.RespTransfer(-1, "", "", "", "当前插件模式不支持转账", 0), nil
	}
	tr := req.GetTransfer()
	if tr == nil || tr.GetTradeNo() == "" {
		return nil, fmt.Errorf("代付单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return nil, err
	}
	reqBody := make(gopay.BodyMap)
	reqBody.Set("out_biz_no", tr.GetTradeNo())
	reqBody.Set("trans_amount", toYuan(tr.GetAmount()))
	reqBody.Set("biz_scene", "DIRECT_TRANSFER")
	reqBody.Set("order_title", transferTitle(tr))

	typeVal := strings.ToLower(strings.TrimSpace(tr.GetType()))
	switch typeVal {
	case "", "alipay":
		reqBody.Set("product_code", "TRANS_ACCOUNT_NO_PWD")
		reqBody.SetBodyMap("payee_info", func(m gopay.BodyMap) {
			m.Set("identity", strings.TrimSpace(tr.GetCardNo()))
			m.Set("identity_type", inferAlipayIdentityType(strings.TrimSpace(tr.GetCardNo())))
			if strings.TrimSpace(tr.GetCardName()) != "" {
				m.Set("name", strings.TrimSpace(tr.GetCardName()))
			}
		})
	case "bank":
		reqBody.Set("product_code", "TRANS_BANKCARD_NO_PWD")
		reqBody.SetBodyMap("payee_info", func(m gopay.BodyMap) {
			m.Set("identity_type", "BANKCARD_ACCOUNT")
			m.Set("identity", strings.TrimSpace(tr.GetCardNo()))
			m.Set("name", strings.TrimSpace(tr.GetCardName()))
			m.SetBodyMap("bankcard_ext_info", func(ext gopay.BodyMap) {
				ext.Set("account_type", "2")
			})
		})
	default:
		return plugin.RespTransfer(-1, "", reqBody.JsonBody(), "", "不支持的转账类型", 0), nil
	}

	if strings.TrimSpace(tr.GetCardNo()) == "" {
		return plugin.RespTransfer(-1, "", reqBody.JsonBody(), "", "收款账户不能为空", 0), nil
	}
	resp, err := client.FundTransUniTransfer(ctx, reqBody)
	if err != nil {
		return plugin.RespTransfer(-1, "", reqBody.JsonBody(), "", err.Error(), 0), nil
	}
	if resp == nil || resp.Response == nil {
		return plugin.RespTransfer(0, "", reqBody.JsonBody(), "", "", 0), nil
	}
	state := transferState(strings.TrimSpace(resp.Response.Status))
	apiTradeNo := strings.TrimSpace(resp.Response.OrderId)
	if apiTradeNo == "" {
		apiTradeNo = strings.TrimSpace(resp.Response.PayFundOrderId)
	}
	result := strings.TrimSpace(resp.Response.SubMsg)
	if result == "" {
		result = strings.TrimSpace(resp.Response.Msg)
	}
	return plugin.RespTransfer(state, apiTradeNo, reqBody.JsonBody(), "", result, 0), nil
}

func inferAlipayIdentityType(account string) string {
	account = strings.TrimSpace(account)
	if account == "" {
		return "ALIPAY_LOGON_ID"
	}
	if isDigits(account) && strings.HasPrefix(account, "2088") {
		return "ALIPAY_USER_ID"
	}
	if isDigits(account) {
		return "ALIPAY_LOGON_ID"
	}
	if strings.Contains(account, "@") == true {
		return "ALIPAY_LOGON_ID"
	}
	return "ALIPAY_OPEN_ID"
}

func transferState(status string) int {
	switch strings.ToUpper(strings.TrimSpace(status)) {
	case "SUCCESS":
		return 1
	case "DEALING", "WAIT_PAY":
		return 0
	default:
		return -1
	}
}

func transferTitle(tr *proto.TransferSnapshot) string {
	if tr == nil {
		return "付款"
	}
	if tr.GetCode() > 0 {
		s := fmt.Sprintf("付款%d", tr.GetCode())
		return s
	}
	return "付款"
}
