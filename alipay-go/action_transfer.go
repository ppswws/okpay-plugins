package main

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/go-pay/gopay"
	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
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

	typeVal := strings.ToLower(tr.GetType())
	switch typeVal {
	case "", "alipay":
		reqBody.Set("product_code", "TRANS_ACCOUNT_NO_PWD")
		reqBody.SetBodyMap("payee_info", func(m gopay.BodyMap) {
			m.Set("identity", tr.GetCardNo())
			m.Set("identity_type", inferAlipayIdentityType(tr.GetCardNo()))
			if tr.GetCardName() != "" {
				m.Set("name", tr.GetCardName())
			}
		})
	case "bank":
		reqBody.Set("product_code", "TRANS_BANKCARD_NO_PWD")
		reqBody.SetBodyMap("payee_info", func(m gopay.BodyMap) {
			m.Set("identity_type", "BANKCARD_ACCOUNT")
			m.Set("identity", tr.GetCardNo())
			m.Set("name", tr.GetCardName())
			m.SetBodyMap("bankcard_ext_info", func(ext gopay.BodyMap) {
				ext.Set("account_type", "2")
			})
		})
	default:
		return plugin.RespTransfer(-1, "", reqBody.JsonBody(), "", "不支持的转账类型", 0), nil
	}

	if tr.GetCardNo() == "" {
		return plugin.RespTransfer(-1, "", reqBody.JsonBody(), "", "收款账户不能为空", 0), nil
	}
	body := reqBody.JsonBody()
	start := time.Now()
	resp, err := client.FundTransUniTransfer(ctx, reqBody)
	reqMs := int32(time.Since(start).Milliseconds())
	respBody := marshalJSON(resp)
	if err != nil {
		if respBody == "" {
			respBody = err.Error()
		}
		return plugin.RespTransfer(-1, "", body, respBody, err.Error(), reqMs), nil
	}
	if resp == nil || resp.Response == nil {
		if respBody == "" {
			respBody = "{}"
		}
		return plugin.RespTransfer(0, "", body, respBody, "", reqMs), nil
	}
	state := -1
	if resp.Response.Code == "10000" {
		state = 1
	} else if isTransferRetryable(resp.Response.Code, resp.Response.SubCode) {
		// 系统繁忙/处理中：结果不确定，交由上游重试或查单。
		state = 0
	}
	apiTradeNo := resp.Response.OrderId
	if state != 1 && apiTradeNo == "" {
		apiTradeNo = resp.Response.PayFundOrderId
	}
	result := resp.Response.SubMsg
	if result == "" {
		result = resp.Response.Msg
	}
	if state != 1 && resp.Response.SubCode != "" {
		result = resp.Response.SubCode + ":" + result
	}
	return plugin.RespTransfer(state, apiTradeNo, body, respBody, result, reqMs), nil
}

func isTransferRetryable(code, subCode string) bool {
	_ = code
	switch strings.ToUpper(subCode) {
	case "SYSTEM_ERROR", "REQUEST_PROCESSING":
		return true
	default:
		return false
	}
}

func inferAlipayIdentityType(account string) string {
	if account == "" {
		return "ALIPAY_LOGON_ID"
	}
	if isAlipayUserID(account) {
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

func isAlipayUserID(account string) bool {
	if !isDigits(account) {
		return false
	}
	// 支付宝 uid：纯数字、2088 开头、16 位。
	if !strings.HasPrefix(account, "2088") {
		return false
	}
	return len(account) == 16
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
