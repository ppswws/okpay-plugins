package main

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
)

const (
	sumapayRefundURL      = "https://api.sumapay.com/main/Refund_do"
	sumapayFundSharingURL = "https://api.sumapay.com/fundSharing/merchant.do"
)

func refund(ctx context.Context, req *proto.InvokeContext) (*proto.BizResult, error) {
	order := req.GetOrder()
	ref := req.GetRefund()
	if order == nil || order.GetTradeNo() == "" {
		return nil, fmt.Errorf("订单为空")
	}
	if ref == nil || ref.GetRefundNo() == "" {
		return nil, fmt.Errorf("退款单为空")
	}
	cfg, err := readConfig(req)
	if err != nil {
		return nil, err
	}
	result, stats, err := refundByChannel(ctx, req, cfg, order, ref)
	if err != nil {
		return plugin.Result(plugin.BizStateFailed, plugin.BizOut{Msg: err.Error(), Stats: stats}), nil
	}
	state := plugin.BizStateProcessing
	if result.success {
		state = plugin.BizStateSucceeded
	}
	return plugin.Result(state, plugin.BizOut{
		ApiNo: result.apiNo,
		Code:  result.code,
		Msg:   result.msg,
		Stats: stats,
	}), nil
}

type refundChannelResult struct {
	success bool
	apiNo   string
	code    string
	msg     string
}

func refundByChannel(ctx context.Context, req *proto.InvokeContext, cfg *sumapayConfig, order *proto.OrderSnapshot, refund *proto.RefundSnapshot) (refundChannelResult, plugin.RequestStats, error) {
	globalCfg := readGlobalConfig(req)
	today := time.Now().Format("20060102")
	orderDate := ""
	if len(order.GetTradeNo()) >= 8 {
		orderDate = order.GetTradeNo()[:8]
	}

	if orderDate == today {
		notifyURL := orderNotifyURL(globalCfg, "refundnotify/"+refund.GetRefundNo())
		params := map[string]string{
			"requestType":       "IFSU0045",
			"requestId":         "O" + refund.GetRefundNo(),
			"merchantCode":      cfg.MerchantCode,
			"originalRequestId": order.GetTradeNo(),
			"reason":            "协商退款",
			"noticeUrl":         notifyURL,
		}
		resp, stats, err := callFundSharingAPI(ctx, cfg, params, []string{"requestType", "requestId", "merchantCode", "originalRequestId", "reason", "noticeUrl"})
		if err != nil {
			return refundChannelResult{}, stats, err
		}
		if !verifyRSA256(cfg.FengfuPublicKey, concatByKeys(resp, []string{"requestId", "result", "merchantCode"}), resp["signature"]) {
			return refundChannelResult{}, stats, fmt.Errorf("返回结果验签失败")
		}
		state, msg, ferr := mapRefundAccepted(resp["result"], "交易撤销")
		if ferr != nil {
			return refundChannelResult{}, stats, ferr
		}
		return refundChannelResult{success: state, apiNo: strings.TrimSpace(resp["requestId"]), code: strings.TrimSpace(resp["result"]), msg: msg}, stats, nil
	}

	queryParams := map[string]string{
		"requestType":    "IFSU0043",
		"requestId":      "Q" + refund.GetRefundNo(),
		"merchantCode":   cfg.MerchantCode,
		"userIdIdentity": cfg.UserIDIdentity,
	}
	queryResp, queryStats, err := callFundSharingAPI(ctx, cfg, queryParams, []string{"requestType", "requestId", "merchantCode", "userIdIdentity"})
	if err != nil {
		return refundChannelResult{}, queryStats, err
	}
	if !verifyRSA256(cfg.FengfuPublicKey, concatByKeys(queryResp, []string{"requestId", "result", "userIdIdentity", "availSum"}), queryResp["signature"]) {
		return refundChannelResult{}, queryStats, fmt.Errorf("返回结果验签失败")
	}
	if queryResp["result"] != "00000" {
		return refundChannelResult{}, queryStats, fmt.Errorf("二级户余额查询失败[%s]", queryResp["result"])
	}

	avail := toCents(queryResp["availSum"])
	if avail < refund.GetAmount() {
		notifyURL := orderNotifyURL(globalCfg, "paymerchantnotify/"+refund.GetRefundNo())
		payParams := map[string]string{
			"requestType":    "IFSU0040",
			"requestId":      "F" + refund.GetRefundNo(),
			"merchantCode":   cfg.MerchantCode,
			"userIdIdentity": cfg.UserIDIdentity,
			"sum":            toYuan(refund.GetAmount()),
			"reason":         "退款所需金额",
			"noticeUrl":      notifyURL,
		}
		payResp, payStats, payErr := callFundSharingAPI(ctx, cfg, payParams, []string{"requestType", "requestId", "merchantCode", "userIdIdentity", "sum", "reason", "noticeUrl"})
		if payErr != nil {
			return refundChannelResult{}, payStats, payErr
		}
		if !verifyRSA256(cfg.FengfuPublicKey, concatByKeys(payResp, []string{"requestId", "result", "merchantCode", "userIdIdentity"}), payResp["signature"]) {
			return refundChannelResult{}, payStats, fmt.Errorf("返回结果验签失败")
		}
		if _, _, ferr := mapRefundAccepted(payResp["result"], "付款至二级户"); ferr != nil {
			return refundChannelResult{}, payStats, ferr
		}
	}

	notifyURL := orderNotifyURL(globalCfg, "refundnotify/"+refund.GetRefundNo())
	refundParams := map[string]string{
		"requestId":         refund.GetRefundNo(),
		"originalRequestId": order.GetTradeNo(),
		"tradeProcess":      cfg.MerchantCode,
		"fund":              toYuan(refund.GetAmount()),
		"noticeUrl":         notifyURL,
		"reason":            "协商退款",
		"refundMothed":      "1",
		"remark":            refund.GetRefundNo(),
	}
	signText := concatByKeys(refundParams, []string{"requestId", "originalRequestId", "tradeProcess", "fund", "noticeUrl", "remark"})
	signature, signErr := signRSA256(cfg.MerchantPrivateKey, signText)
	if signErr != nil {
		return refundChannelResult{}, plugin.RequestStats{}, fmt.Errorf("生成签名失败: %w", signErr)
	}
	refundParams["mersignature"] = signature

	resp, stats, err := postGBKJSON(ctx, sumapayRefundURL, refundParams)
	if err != nil {
		return refundChannelResult{}, stats, err
	}
	if !verifyRSA256(cfg.FengfuPublicKey, concatByKeys(resp, []string{"requestId", "result", "remark"}), resp["resultSignature"]) {
		return refundChannelResult{}, stats, fmt.Errorf("返回结果验签失败")
	}
	success, msg, ferr := mapRefundAccepted(resp["result"], "退款")
	if ferr != nil {
		return refundChannelResult{}, stats, ferr
	}
	return refundChannelResult{success: success, apiNo: strings.TrimSpace(resp["requestId"]), code: strings.TrimSpace(resp["result"]), msg: msg}, stats, nil
}

func callFundSharingAPI(ctx context.Context, cfg *sumapayConfig, params map[string]string, signFields []string) (map[string]string, plugin.RequestStats, error) {
	signature, err := signRSA256(cfg.MerchantPrivateKey, concatByKeys(params, signFields))
	if err != nil {
		return nil, plugin.RequestStats{}, fmt.Errorf("生成签名失败: %w", err)
	}
	params["signature"] = signature
	resp, stats, reqErr := postGBKJSON(ctx, sumapayFundSharingURL, params)
	if reqErr != nil {
		return nil, stats, reqErr
	}
	return resp, stats, nil
}

func mapRefundAccepted(resultCode string, action string) (bool, string, error) {
	code := strings.TrimSpace(resultCode)
	switch code {
	case "00000":
		return false, action + "请求成功，处理中", nil
	case "00001", "200300305", "200300304":
		return false, action + "请求已受理，处理中", nil
	case "200300162":
		return false, "账号余额不足[200300162]", fmt.Errorf("账号余额不足[200300162]")
	default:
		if code == "" {
			code = "UNKNOWN"
		}
		return false, "错误代码[" + code + "]", fmt.Errorf("错误代码[%s]", code)
	}
}
