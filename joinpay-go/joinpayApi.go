package main

import (
	"encoding/json"
	"fmt"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const (
	joinpayPayURL      = "https://trade.joinpay.com/tradeRt/uniPay"
	joinpayRefundURL   = "https://trade.joinpay.com/tradeRt/refund"
	joinpayQueryURL    = "https://trade.joinpay.com/tradeRt/queryOrder"
	joinpayTransferURL = "https://www.joinpay.com/payment/pay/singlePay"
	joinpayBalanceURL  = "https://www.joinpay.com/payment/pay/accountBalanceQuery"
)

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

var (
	joinpayPayRequestFields = []string{
		"p0_Version", "p1_MerchantNo", "p2_OrderNo", "p3_Amount", "p4_Cur", "p5_ProductName", "p6_ProductDesc",
		"p7_Mp", "p8_ReturnUrl", "p9_NotifyUrl", "q1_FrpCode", "q2_MerchantBankCode", "q3_SubMerchantNo",
		"q4_IsShowPic", "q5_OpenId", "q6_AuthCode", "q7_AppId", "q8_TerminalNo", "q9_TransactionModel",
		"qa_TradeMerchantNo", "qb_buyerId", "qh_HbFqNum", "qi_FqSellerPercen", "qj_DJPlan", "qk_DisablePayModel",
		"ql_TerminalIp", "qm_ContractId", "qn_SpecialInfo",
	}
	joinpayPayResponseFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_Cur", "r5_Mp", "r6_FrpCode", "r7_TrxNo",
		"r8_MerchantBankCode", "r9_SubMerchantNo", "ra_Code", "rb_CodeMsg", "rc_Result", "rd_Pic",
	}
	joinpayNotifyFields = []string{
		"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_Cur", "r5_Mp", "r6_Status", "r7_TrxNo",
		"r8_BankOrderNo", "r9_BankTrxNo", "ra_PayTime", "rb_DealTime", "rc_BankCode", "rd_OpenId",
		"re_DiscountAmount", "rh_cardType", "rj_Fee", "rk_FrpCode", "rl_ContractId", "rm_SpecialInfo", "ro_SettleAmount",
	}
	joinpayRefundRequestFields       = []string{"p0_Version", "p1_MerchantNo", "p2_OrderNo", "p3_RefundOrderNo", "p4_RefundAmount", "p5_RefundReason", "p6_NotifyUrl", "pa_FundsAccount"}
	joinpayRefundResponseFields      = []string{"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_RefundOrderNo", "r4_RefundAmount", "r5_RefundTrxNo", "ra_Status", "rb_Code", "rc_CodeMsg", "re_FundsAccount"}
	joinpayQueryRequestFields        = []string{"p0_Version", "p1_MerchantNo", "p2_OrderNo"}
	joinpayQueryResponseFields       = []string{"r0_Version", "r1_MerchantNo", "r2_OrderNo", "r3_Amount", "r4_ProductName", "r5_TrxNo", "r6_BankTrxNo", "r7_Fee", "r8_FrpCode", "ra_Status", "rb_Code", "rc_CodeMsg", "rd_OpenId", "re_DiscountAmount", "rf_PayTime", "rh_cardType", "rj_BankCode", "rl_ContractId", "rm_SpecialInfo", "ro_SettleAmount"}
	joinpayTransferRequestFields     = []string{"userNo", "tradeMerchantNo", "productCode", "requestTime", "merchantOrderNo", "receiverAccountNoEnc", "receiverNameEnc", "receiverAccountType", "receiverBankChannelNo", "paidAmount", "currency", "isChecked", "paidDesc", "paidUse", "callbackUrl", "firstProductCode"}
	joinpayTransferResponseFields    = []string{"errorCode", "errorDesc", "userNo", "merchantOrderNo"}
	joinpayTransferNotifyFields      = []string{"status", "errorCode", "errorCodeDesc", "userNo", "tradeMerchantNo", "merchantOrderNo", "platformSerialNo", "receiverAccountNoEnc", "receiverNameEnc", "paidAmount", "fee"}
	joinpayBalanceRequestFields      = []string{"userNo"}
	joinpayBalanceResponseFields     = []string{"userNo", "userName", "currency", "useAbleSettAmount", "availableSettAmountFrozen", "errorCode", "errorDesc"}
	joinpayBalanceResponseSignFields = []string{"statusCode", "message", "userNo", "userName", "currency", "useAbleSettAmount", "availableSettAmountFrozen", "errorCode", "errorDesc"}
)

type joinpayConfig struct {
	AppID                 string
	AppKey                string
	AppMchID              string
	MPAppID               string
	MPAppSecret           string
	MiniAppID             string
	MiniAppSecret         string
	Biztypes              []string
	ReceiverBankChannelNo string
}

func readConfig(req *proto.InvokeContext) (*joinpayConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetConfigJsonRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	raw := struct {
		AppID                 string `json:"appid"`
		AppKey                string `json:"appkey"`
		AppMchID              string `json:"appmchid"`
		Biztype               string `json:"biztype"`
		ReceiverBankChannelNo string `json:"receiverBankChannelNo"`
		MP                    struct {
			AppID     string `json:"appid"`
			AppSecret string `json:"appsecret"`
		} `json:"mp"`
		Mini struct {
			AppID     string `json:"appid"`
			AppSecret string `json:"appsecret"`
		} `json:"mini"`
	}{}
	if err := json.Unmarshal(req.GetChannel().GetConfigJsonRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	if raw.AppID == "" || raw.AppKey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	return &joinpayConfig{
		AppID:                 raw.AppID,
		AppKey:                raw.AppKey,
		AppMchID:              raw.AppMchID,
		MPAppID:               raw.MP.AppID,
		MPAppSecret:           raw.MP.AppSecret,
		MiniAppID:             raw.Mini.AppID,
		MiniAppSecret:         raw.Mini.AppSecret,
		Biztypes:              splitCSV(raw.Biztype),
		ReceiverBankChannelNo: raw.ReceiverBankChannelNo,
	}, nil
}
