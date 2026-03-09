package main

import (
	"crypto/md5"
	"fmt"
	"strings"
)

func signRequest(params map[string]string, apiKey string) string {
	plain := buildRequestPlain(params) + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return fmt.Sprintf("%x", sum)
}

func verifyResponse(resp map[string]string, apiKey string) bool {
	sign := resp["sign"]
	if sign == "" {
		return true
	}
	plain := buildResponsePlain(resp) + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return strings.EqualFold(sign, fmt.Sprintf("%x", sum))
}

func verifyNotify(payload map[string]string, apiKey string) bool {
	sign := payload["sign"]
	if sign == "" {
		return false
	}
	plain := buildNotifyPlain(payload) + "&" + apiKey
	sum := md5.Sum([]byte(plain))
	return strings.EqualFold(sign, fmt.Sprintf("%x", sum))
}

func buildRequestPlain(params map[string]string) string {
	return buildPlainByOrder(params, requestOrderByBizType(params["P1_bizType"]))
}

func buildResponsePlain(resp map[string]string) string {
	return buildPlainByOrder(resp, responseOrderByBizType(resp["rt1_bizType"]))
}

func buildNotifyPlain(payload map[string]string) string {
	return buildPlainByOrder(payload, notifyOrder(payload))
}

func requestOrderByBizType(bizType string) []string {
	switch bizType {
	case "AppPay":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_orderAmount", "P6_currency", "P7_authcode", "P8_appType", "P9_notifyUrl", "P10_successToUrl", "P11_orderIp", "P12_goodsName", "P13_goodsDetail", "P14_desc"}
	case "AppPayPublic", "AppPayApplet":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_payType", "P5_appid", "P6_deviceInfo", "P7_isRaw", "P8_openid", "P9_orderAmount", "P10_currency", "P11_appType", "P12_notifyUrl", "P13_successToUrl", "P14_orderIp", "P15_goodsName", "P16_goodsDetail", "P17_limitCreditPay", "P18_desc"}
	case "AppPayH5WFT":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_orderAmount", "P5_currency", "P6_orderIp", "P7_notifyUrl", "P8_appPayType", "P9_payType", "P10_appName", "P11_deviceInfo", "P12_applicationId", "P13_goodsName", "P14_goodsDetail", "P15_desc"}
	case "AppPayRefund":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_refundOrderId", "P5_amount", "P6_callbackUrl"}
	case "Transfer":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber", "P4_amount", "P5_bankCode", "P6_bankAccountNo", "P7_bankAccountName", "P8_biz", "P9_bankUnionCode", "P10_feeType", "P11_urgency", "P12_summary", "notifyUrl", "payerName", "payerShowName", "payerAccountNo"}
	case "MerchantAccountQuery":
		return []string{"P1_bizType", "P2_customerNumber", "P3_timestamp"}
	case "AppPayQuery":
		return []string{"P1_bizType", "P2_orderId", "P3_customerNumber"}
	default:
		return []string{}
	}
}

func responseOrderByBizType(bizType string) []string {
	switch bizType {
	case "AppPay":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_qrcode", "rt9_wapurl", "rt10_orderAmount", "rt11_currency"}
	case "AppPayPublic":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_appid", "rt9_tokenId", "rt10_payInfo", "rt11_orderAmount", "rt12_currency"}
	case "AppPayH5WFT":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_appName", "rt8_payInfo", "rt9_orderAmount", "rt10_currency", "rt11_payType"}
	case "AppPayApplet":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_payType", "rt8_appid", "rt9_tokenId", "rt10_payInfo", "rt11_orderAmount", "rt12_currency"}
	case "AppPayRefund":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_refundOrderNum", "rt7_serialNumber", "rt8_amount", "rt9_currency"}
	case "Transfer":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber"}
	case "MerchantAccountQuery":
		return []string{"rt1_bizType", "rt2_retCode", "rt3_retMsg", "rt4_customerNumber", "rt5_accountStatus", "rt6_balance", "rt7_frozenBalance", "rt8_d0Balance", "rt9_T1Balance", "rt10_currency", "rt11_createDate", "rt12_desc"}
	case "AppPayQuery":
		return []string{"rt1_bizType", "rt2_retCode", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_orderStatus", "rt8_orderAmount", "rt9_currency"}
	default:
		return []string{}
	}
}

func notifyOrder(payload map[string]string) []string {
	if payload["rt7_orderStatus"] != "" && payload["rt1_bizType"] == "Transfer" {
		return []string{"rt1_bizType", "rt2_retCode", "rt3_retMsg", "rt4_customerNumber", "rt5_orderId", "rt6_serialNumber", "rt7_orderStatus", "rt8_notifyType", "rt9_reason", "rt10_createDate", "rt11_completeDate"}
	}
	if payload["rt3_refundOrderId"] != "" {
		return []string{"rt1_customerNumber", "rt2_orderId", "rt3_refundOrderId", "rt4_systemSerial", "rt5_status", "rt6_amount", "rt7_currency", "rt8_timestamp"}
	}
	return []string{"rt1_customerNumber", "rt2_orderId", "rt3_systemSerial", "rt4_status", "rt5_orderAmount", "rt6_currency", "rt7_timestamp", "rt8_desc"}
}

func buildPlainByOrder(params map[string]string, order []string) string {
	plain := ""
	for _, key := range order {
		plain += "&" + params[key]
	}
	return plain
}
