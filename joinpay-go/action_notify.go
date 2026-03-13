package main

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

func notify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	n, err := parseJoinpayOrderNotify(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	if !verifyJoinpay(n.toSignMap(), joinpayNotifyFields, cfg.AppKey) {
		return plugin.RespHTML("sign_error"), nil
	}
	if n.R6Status != "100" {
		return plugin.RespHTML("status=" + n.R6Status), nil
	}
	order := req.GetOrder()
	if order == nil {
		return plugin.RespHTML("success"), nil
	}
	if n.R2OrderNo != order.GetTradeNo() {
		return plugin.RespHTML("order_mismatch"), nil
	}
	if order.GetReal() != toCents(n.R3Amount) {
		return plugin.RespHTML("amount_mismatch"), nil
	}
	if err := plugin.CompleteBiz(ctx, plugin.CompleteBizInput{
		BizType: proto.BizType_BIZ_TYPE_ORDER,
		BizNo:   order.GetTradeNo(),
		State:   proto.BizState_BIZ_STATE_SUCCEEDED,
		ApiNo:   n.R7TrxNo,
		Buyer:   n.RdOpenId,
	}); err != nil {
		return plugin.RespHTML("fail"), nil
	}
	return plugin.RespHTML("success"), nil
}

func refundNotify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	n, err := parseJoinpayRefundNotify(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	if !verifyJoinpay(n.toSignMap(), joinpayRefundResponseFields, cfg.AppKey) {
		return plugin.RespHTML("sign_error"), nil
	}
	status := n.RaStatus
	refund := req.GetRefund()
	if refund == nil {
		if status == "100" {
			return plugin.RespHTML("success"), nil
		}
		return plugin.RespHTML("status=" + status), nil
	}
	if n.R3RefundOrderNo != refund.GetRefundNo() {
		return plugin.RespHTML("refund_mismatch"), nil
	}
	if refund.GetAmount() != toCents(n.R4RefundAmount) {
		return plugin.RespHTML("amount_mismatch"), nil
	}
	if err := plugin.CompleteBiz(ctx, plugin.CompleteBizInput{
		BizType:  proto.BizType_BIZ_TYPE_REFUND,
		BizNo:    refund.GetRefundNo(),
		State:    joinpayRefundState(status),
		ApiNo:    n.R5RefundTrxNo,
		Code:     status,
		Msg:      n.RcCodeMsg,
		RespBody: n.RcCodeMsg,
	}); err != nil {
		return plugin.RespHTML("fail"), nil
	}
	if status == "100" {
		return plugin.RespHTML("success"), nil
	}
	return plugin.RespHTML("status=" + status), nil
}

func transferNotify(ctx context.Context, req *proto.InvokeContext) (*proto.PageResponse, error) {
	cfg, err := readConfig(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	n, err := parseJoinpayTransferNotify(req)
	if err != nil {
		return plugin.RespHTML("fail"), nil
	}
	if !verifyJoinpay(n.toSignMap(), joinpayTransferNotifyFields, cfg.AppKey) {
		return plugin.RespHTML("sign_error"), nil
	}
	status := n.Status
	state := joinpayTransferState(status)
	transfer := req.GetTransfer()
	if transfer == nil {
		if state == proto.BizState_BIZ_STATE_SUCCEEDED {
			return plugin.RespHTML("success"), nil
		}
		return plugin.RespHTML("status=" + status), nil
	}
	if err := plugin.CompleteBiz(ctx, plugin.CompleteBizInput{
		BizType: proto.BizType_BIZ_TYPE_TRANSFER,
		BizNo:   transfer.GetTradeNo(),
		State:   state,
		ApiNo:   n.PlatformSerialNo,
		Code:    n.ErrorCode,
		Msg:     n.ErrorCodeDesc,
	}); err != nil {
		return plugin.RespHTML("fail"), nil
	}
	if state == proto.BizState_BIZ_STATE_SUCCEEDED {
		return plugin.RespHTML("success"), nil
	}
	return plugin.RespHTML("status=" + status), nil
}

func joinpayRefundState(status string) proto.BizState {
	switch status {
	case "100":
		return proto.BizState_BIZ_STATE_SUCCEEDED
	case "101":
		return proto.BizState_BIZ_STATE_FAILED
	default:
		return proto.BizState_BIZ_STATE_PROCESSING
	}
}

func joinpayTransferState(status string) proto.BizState {
	switch status {
	case "205":
		return proto.BizState_BIZ_STATE_SUCCEEDED
	case "204", "208", "214":
		return proto.BizState_BIZ_STATE_FAILED
	default:
		return proto.BizState_BIZ_STATE_PROCESSING
	}
}

type joinpayOrderNotify struct {
	R0Version        string
	R1MerchantNo     string
	R2OrderNo        string
	R3Amount         string
	R4Cur            string
	R5Mp             string
	R6Status         string
	R7TrxNo          string
	R8BankOrderNo    string
	R9BankTrxNo      string
	RaPayTime        string
	RbDealTime       string
	RcBankCode       string
	RdOpenId         string
	ReDiscountAmount string
	RhCardType       string
	RjFee            string
	RkFrpCode        string
	RlContractId     string
	RmSpecialInfo    string
	RoSettleAmount   string
	Hmac             string
}

func (n joinpayOrderNotify) toSignMap() map[string]string {
	return map[string]string{
		"r0_Version": n.R0Version, "r1_MerchantNo": n.R1MerchantNo, "r2_OrderNo": n.R2OrderNo, "r3_Amount": n.R3Amount,
		"r4_Cur": n.R4Cur, "r5_Mp": n.R5Mp, "r6_Status": n.R6Status, "r7_TrxNo": n.R7TrxNo,
		"r8_BankOrderNo": n.R8BankOrderNo, "r9_BankTrxNo": n.R9BankTrxNo, "ra_PayTime": n.RaPayTime, "rb_DealTime": n.RbDealTime,
		"rc_BankCode": n.RcBankCode, "rd_OpenId": n.RdOpenId, "re_DiscountAmount": n.ReDiscountAmount, "rh_cardType": n.RhCardType,
		"rj_Fee": n.RjFee, "rk_FrpCode": n.RkFrpCode, "rl_ContractId": n.RlContractId, "rm_SpecialInfo": n.RmSpecialInfo,
		"ro_SettleAmount": n.RoSettleAmount, "hmac": n.Hmac,
	}
}

func parseJoinpayOrderNotify(req *proto.InvokeContext) (*joinpayOrderNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &joinpayOrderNotify{
		R0Version: raw["r0_Version"], R1MerchantNo: raw["r1_MerchantNo"], R2OrderNo: raw["r2_OrderNo"],
		R3Amount: raw["r3_Amount"], R4Cur: raw["r4_Cur"], R5Mp: raw["r5_Mp"], R6Status: raw["r6_Status"],
		R7TrxNo: raw["r7_TrxNo"], R8BankOrderNo: raw["r8_BankOrderNo"], R9BankTrxNo: raw["r9_BankTrxNo"],
		RaPayTime: raw["ra_PayTime"], RbDealTime: raw["rb_DealTime"], RcBankCode: raw["rc_BankCode"],
		RdOpenId: raw["rd_OpenId"], ReDiscountAmount: raw["re_DiscountAmount"], RhCardType: raw["rh_cardType"],
		RjFee: raw["rj_Fee"], RkFrpCode: raw["rk_FrpCode"], RlContractId: raw["rl_ContractId"],
		RmSpecialInfo: raw["rm_SpecialInfo"], RoSettleAmount: raw["ro_SettleAmount"], Hmac: raw["hmac"],
	}
	if n.R2OrderNo == "" || n.R3Amount == "" || n.R6Status == "" || n.Hmac == "" {
		return nil, fmt.Errorf("missing required order notify fields")
	}
	return n, nil
}

type joinpayRefundNotify struct {
	R0Version       string
	R1MerchantNo    string
	R2OrderNo       string
	R3RefundOrderNo string
	R4RefundAmount  string
	R5RefundTrxNo   string
	RaStatus        string
	RbCode          string
	RcCodeMsg       string
	ReFundsAccount  string
	Hmac            string
}

func (n joinpayRefundNotify) toSignMap() map[string]string {
	return map[string]string{
		"r0_Version": n.R0Version, "r1_MerchantNo": n.R1MerchantNo, "r2_OrderNo": n.R2OrderNo, "r3_RefundOrderNo": n.R3RefundOrderNo,
		"r4_RefundAmount": n.R4RefundAmount, "r5_RefundTrxNo": n.R5RefundTrxNo, "ra_Status": n.RaStatus, "rb_Code": n.RbCode,
		"rc_CodeMsg": n.RcCodeMsg, "re_FundsAccount": n.ReFundsAccount, "hmac": n.Hmac,
	}
}

func parseJoinpayRefundNotify(req *proto.InvokeContext) (*joinpayRefundNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &joinpayRefundNotify{
		R0Version: raw["r0_Version"], R1MerchantNo: raw["r1_MerchantNo"], R2OrderNo: raw["r2_OrderNo"],
		R3RefundOrderNo: raw["r3_RefundOrderNo"], R4RefundAmount: raw["r4_RefundAmount"], R5RefundTrxNo: raw["r5_RefundTrxNo"],
		RaStatus: raw["ra_Status"], RbCode: raw["rb_Code"], RcCodeMsg: raw["rc_CodeMsg"],
		ReFundsAccount: raw["re_FundsAccount"], Hmac: raw["hmac"],
	}
	if n.R3RefundOrderNo == "" || n.R4RefundAmount == "" || n.RaStatus == "" || n.Hmac == "" {
		return nil, fmt.Errorf("missing required refund notify fields")
	}
	return n, nil
}

type joinpayTransferNotify struct {
	Status               string
	ErrorCode            string
	ErrorCodeDesc        string
	UserNo               string
	TradeMerchantNo      string
	MerchantOrderNo      string
	PlatformSerialNo     string
	ReceiverAccountNoEnc string
	ReceiverNameEnc      string
	PaidAmount           string
	Fee                  string
	Hmac                 string
}

func (n joinpayTransferNotify) toSignMap() map[string]string {
	return map[string]string{
		"status": n.Status, "errorCode": n.ErrorCode, "errorCodeDesc": n.ErrorCodeDesc, "userNo": n.UserNo,
		"tradeMerchantNo": n.TradeMerchantNo, "merchantOrderNo": n.MerchantOrderNo, "platformSerialNo": n.PlatformSerialNo,
		"receiverAccountNoEnc": n.ReceiverAccountNoEnc, "receiverNameEnc": n.ReceiverNameEnc, "paidAmount": n.PaidAmount,
		"fee": n.Fee, "hmac": n.Hmac,
	}
}

func parseJoinpayTransferNotify(req *proto.InvokeContext) (*joinpayTransferNotify, error) {
	raw, err := parseNotifyJSONMap(req)
	if err != nil {
		return nil, err
	}
	n := &joinpayTransferNotify{
		Status: raw["status"], ErrorCode: raw["errorCode"], ErrorCodeDesc: raw["errorCodeDesc"],
		UserNo: raw["userNo"], TradeMerchantNo: raw["tradeMerchantNo"], MerchantOrderNo: raw["merchantOrderNo"],
		PlatformSerialNo: raw["platformSerialNo"], ReceiverAccountNoEnc: raw["receiverAccountNoEnc"], ReceiverNameEnc: raw["receiverNameEnc"],
		PaidAmount: raw["paidAmount"], Fee: raw["fee"], Hmac: raw["hmac"],
	}
	if n.Status == "" || n.MerchantOrderNo == "" || n.Hmac == "" {
		return nil, fmt.Errorf("missing required transfer notify fields")
	}
	return n, nil
}

func parseNotifyJSONMap(req *proto.InvokeContext) (map[string]string, error) {
	if req == nil || req.GetRequest() == nil {
		return nil, fmt.Errorf("request is nil")
	}
	payload := req.GetRequest().GetBody()
	if len(payload) == 0 {
		return nil, fmt.Errorf("notify body is empty")
	}
	src := map[string]any{}
	if err := json.Unmarshal(payload, &src); err != nil {
		return nil, fmt.Errorf("notify body json invalid: %w", err)
	}
	out := make(map[string]string, len(src))
	for k, v := range src {
		switch val := v.(type) {
		case nil:
			out[k] = ""
		case string:
			out[k] = val
		case float64, float32, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, bool:
			out[k] = fmt.Sprint(val)
		default:
			b, _ := json.Marshal(val)
			out[k] = string(b)
		}
	}
	return out, nil
}
