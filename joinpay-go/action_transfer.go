package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

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
	globalCfg := readGlobalConfig(req)
	notifyDomain := strings.TrimRight(globalCfg.NotifyDomain, "/")
	params := map[string]string{
		"userNo":               cfg.AppID,
		"productCode":          "BANK_PAY_DAILY_ORDER",
		"requestTime":          time.Now().Format("2006-01-02 15:04:05"),
		"merchantOrderNo":      transfer.GetTradeNo(),
		"receiverAccountNoEnc": transfer.GetCardNo(),
		"receiverNameEnc":      transfer.GetCardName(),
		"receiverAccountType":  "201",
		"paidAmount":           toYuan(transfer.GetAmount()),
		"currency":             "201",
		"isChecked":            "202",
		"paidDesc":             "工资发放",
		"paidUse":              "201",
		"callbackUrl":          buildNotifyURL(notifyDomain, "transfernotify/"+transfer.GetTradeNo()),
	}
	if receiverBankChannelNo := strings.TrimSpace(transfer.GetBranchName()); receiverBankChannelNo != "" {
		params["receiverBankChannelNo"] = receiverBankChannelNo
	}
	if cfg.AppMchID != "" {
		params["tradeMerchantNo"] = cfg.AppMchID
	}
	resp, stats, err := transferOrder(ctx, cfg, params)
	if err != nil {
		return plugin.Result(plugin.BizStateFailed, plugin.BizOut{
			Msg:   err.Error(),
			Stats: stats,
		}), nil
	}
	statusCode := resp["statusCode"]
	message := resp["message"]
	if statusCode == "2002" || (statusCode != "2001" && statusCode != "2003") {
		if message == "" {
			message = "代付受理失败"
		}
		return plugin.Result(plugin.BizStateFailed, plugin.BizOut{
			Code:  statusCode,
			Msg:   message,
			Stats: stats,
		}), nil
	}
	result := message
	if result == "" {
		result = statusCode
	}
	return plugin.Result(plugin.BizStateProcessing, plugin.BizOut{
		Code:  statusCode,
		Msg:   result,
		Stats: stats,
	}), nil
}

func transferOrder(ctx context.Context, cfg *joinpayConfig, params map[string]string) (map[string]string, plugin.RequestStats, error) {
	params["hmac"] = signJoinpay(params, joinpayTransferRequestFields, cfg.AppKey)
	reqBody, err := json.Marshal(params)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, joinpayTransferURL, string(reqBody), "application/json")
	stats := plugin.RequestStats{ReqBody: string(reqBody), RespBody: body, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	respMap, err := decodeJSONAnyMap(body)
	if err != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", err)
	}
	dataObj, ok := respMap["data"].(map[string]any)
	if !ok || dataObj == nil {
		return nil, stats, fmt.Errorf("响应解析失败")
	}
	dataRaw := map[string]string{}
	for k, v := range dataObj {
		dataRaw[k] = toString(v)
	}
	if !verifyJoinpay(dataRaw, joinpayTransferResponseFields, cfg.AppKey) {
		return nil, stats, fmt.Errorf("返回验签失败")
	}
	statusCode, err := requiredStringOrNumber(respMap, "statusCode")
	if err != nil {
		return nil, stats, err
	}
	message, _ := valueStringOrNumber(respMap, "message")
	return map[string]string{"statusCode": statusCode, "message": message}, stats, nil
}
