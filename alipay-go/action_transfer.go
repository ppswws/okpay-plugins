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

func transfer(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	result, err := transferByChannel(ctx, req)
	if err != nil {
		return nil, err
	}
	return kernelResult(result), nil
}

func transferByChannel(ctx context.Context, req *proto.InvokeContext) (channelBizResult, error) {
	tr := req.GetTransfer()
	if tr == nil || tr.GetTradeNo() == "" {
		return channelBizResult{}, fmt.Errorf("代付单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return channelBizResult{}, err
	}
	client, err := newAliClient(cfg, "", "")
	if err != nil {
		return channelBizResult{}, err
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
		return channelBizResult{
			State: plugin.BizStateFailed,
			Input: plugin.BizOut{Msg: "不支持的转账类型", Stats: plugin.RequestStats{ReqBody: reqBody.JsonBody()}},
		}, nil
	}

	if tr.GetCardNo() == "" {
		return channelBizResult{
			State: plugin.BizStateFailed,
			Input: plugin.BizOut{Msg: "收款账户不能为空", Stats: plugin.RequestStats{ReqBody: reqBody.JsonBody()}},
		}, nil
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
		return channelBizResult{
			State: plugin.BizStateFailed,
			Input: plugin.BizOut{Msg: err.Error(), Stats: plugin.RequestStats{ReqMs: reqMs, ReqBody: body, RespBody: respBody}},
		}, nil
	}
	if resp == nil || resp.Response == nil {
		if respBody == "" {
			respBody = "{}"
		}
		return channelBizResult{
			State: plugin.BizStateProcessing,
			Input: plugin.BizOut{Msg: "代付处理中", Stats: plugin.RequestStats{ReqMs: reqMs, ReqBody: body, RespBody: respBody}},
		}, nil
	}
	state := plugin.BizStateFailed
	if resp.Response.Code == "10000" {
		state = plugin.BizStateSucceeded
	} else if isTransferRetryable(resp.Response.Code, resp.Response.SubCode) {
		// 系统繁忙/处理中：结果不确定，交由上游重试或查单。
		state = plugin.BizStateProcessing
	}
	apiTradeNo := resp.Response.OrderId
	if state != plugin.BizStateSucceeded && apiTradeNo == "" {
		apiTradeNo = resp.Response.PayFundOrderId
	}
	result := resp.Response.SubMsg
	if result == "" {
		result = resp.Response.Msg
	}
	if state != plugin.BizStateSucceeded && resp.Response.SubCode != "" {
		result = resp.Response.SubCode + ":" + result
	}
	stats := plugin.RequestStats{ReqMs: reqMs, ReqBody: body, RespBody: respBody}
	return channelBizResult{
		State: state,
		Input: plugin.BizOut{ApiNo: apiTradeNo, Code: resp.Response.SubCode, Msg: result, Stats: stats},
	}, nil
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
	if strings.Contains(account, "@") {
		return "ALIPAY_LOGON_ID"
	}
	return "ALIPAY_OPEN_ID"
}

func isAlipayUserID(account string) bool {
	if !isDigits(account) {
		return false
	}
	// 支付宝用户号为纯数字、2088 开头、16 位。
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
