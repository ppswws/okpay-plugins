package main

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/ppswws/okpay-plugin-sdk"
	"github.com/ppswws/okpay-plugin-sdk/proto"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

var httpClient = plugin.NewHTTPClient(plugin.HTTPClientConfig{})

type sumapayConfig struct {
	MerchantCode       string
	UserIDIdentity     string
	SubMerchantID      string
	TotalBizType       string
	FengfuPublicKey    string
	MerchantPrivateKey string
}

type globalConfig struct {
	SiteDomain   string
	NotifyDomain string
	GoodsName    string
}

func readGlobalConfig(req *proto.InvokeContext) globalConfig {
	if req == nil || req.GetConfig() == nil {
		return globalConfig{}
	}
	cfg := req.GetConfig()
	return globalConfig{
		SiteDomain:   strings.TrimRight(cfg.GetSiteDomain(), "/"),
		NotifyDomain: strings.TrimRight(cfg.GetNotifyDomain(), "/"),
		GoodsName:    cfg.GetGoodsName(),
	}
}

func readConfig(req *proto.InvokeContext) (*sumapayConfig, error) {
	if req == nil || req.GetChannel() == nil || len(req.GetChannel().GetCfgRaw()) == 0 {
		return nil, fmt.Errorf("通道配置不完整")
	}
	var raw map[string]any
	if err := json.Unmarshal(req.GetChannel().GetCfgRaw(), &raw); err != nil {
		return nil, fmt.Errorf("通道配置解析失败: %w", err)
	}
	appid := strings.TrimSpace(toString(raw["appid"]))
	if appid == "" {
		return nil, fmt.Errorf("通道配置 appid 不能为空")
	}
	merchantCode := appid
	userIDIdentity := strings.TrimSpace(toString(raw["appuserid"]))
	if userIDIdentity == "" {
		userIDIdentity = strings.TrimSpace(toString(raw["useridentity"]))
	}
	subMerchantID := strings.TrimSpace(toString(raw["appmchid"]))
	if userIDIdentity == "" {
		userIDIdentity = subMerchantID
	}
	cfg := &sumapayConfig{
		MerchantCode:       merchantCode,
		UserIDIdentity:     userIDIdentity,
		SubMerchantID:      subMerchantID,
		TotalBizType:       "BIZ01104",
		FengfuPublicKey:    strings.TrimSpace(toString(raw["appkey"])),
		MerchantPrivateKey: strings.TrimSpace(toString(raw["appsecret"])),
	}
	if cfg.MerchantCode == "" || cfg.FengfuPublicKey == "" || cfg.MerchantPrivateKey == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	if cfg.UserIDIdentity == "" {
		return nil, fmt.Errorf("缺少 userIdIdentity，请配置 appuserid")
	}
	return cfg, nil
}

func toString(v any) string {
	switch val := v.(type) {
	case nil:
		return ""
	case string:
		return val
	case json.Number:
		return val.String()
	case float64:
		if val == float64(int64(val)) {
			return strconv.FormatInt(int64(val), 10)
		}
		return strconv.FormatFloat(val, 'f', -1, 64)
	case bool:
		if val {
			return "true"
		}
		return "false"
	default:
		return strings.TrimSpace(fmt.Sprint(val))
	}
}

func toYuan(cents int64) string {
	sign := ""
	if cents < 0 {
		sign = "-"
		cents = -cents
	}
	return fmt.Sprintf("%s%d.%02d", sign, cents/100, cents%100)
}

func toCents(raw string) int64 {
	s := strings.TrimSpace(raw)
	if s == "" || strings.HasPrefix(s, "-") {
		return 0
	}
	parts := strings.SplitN(s, ".", 3)
	if len(parts) > 2 {
		return 0
	}
	intPart := parts[0]
	if intPart == "" {
		intPart = "0"
	}
	if !isDigits(intPart) {
		return 0
	}
	fracPart := "00"
	if len(parts) == 2 {
		fracPart = parts[1]
		if fracPart == "" {
			fracPart = "00"
		}
		if !isDigits(fracPart) || len(fracPart) > 2 {
			return 0
		}
		if len(fracPart) == 1 {
			fracPart += "0"
		}
	}
	units, err := strconv.ParseInt(intPart, 10, 64)
	if err != nil {
		return 0
	}
	frac, err := strconv.ParseInt(fracPart, 10, 64)
	if err != nil {
		return 0
	}
	return units*100 + frac
}

func isDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func concatByKeys(source map[string]string, keys []string) string {
	buf := strings.Builder{}
	for _, k := range keys {
		if v := source[k]; v != "" {
			buf.WriteString(v)
		}
	}
	return buf.String()
}

func normalizeKey(raw string, isPrivate bool) string {
	key := strings.TrimSpace(raw)
	if key == "" {
		return ""
	}
	if strings.Contains(key, "-----BEGIN") {
		return key
	}
	clean := strings.Map(func(r rune) rune {
		switch r {
		case '\n', '\r', '\t', ' ':
			return -1
		default:
			return r
		}
	}, key)
	body := wrap64(clean)
	if isPrivate {
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----"
	}
	return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----"
}

func wrap64(s string) string {
	if s == "" {
		return ""
	}
	buf := strings.Builder{}
	for i := 0; i < len(s); i += 64 {
		end := i + 64
		if end > len(s) {
			end = len(s)
		}
		if i > 0 {
			buf.WriteByte('\n')
		}
		buf.WriteString(s[i:end])
	}
	return buf.String()
}

func parseRSAPrivateKey(raw string) (*rsa.PrivateKey, error) {
	pemData := normalizeKey(raw, true)
	if pemData == "" {
		return nil, fmt.Errorf("商户私钥未配置")
	}
	blk, _ := pem.Decode([]byte(pemData))
	if blk == nil {
		return nil, fmt.Errorf("商户私钥解析失败")
	}
	if k, err := x509.ParsePKCS8PrivateKey(blk.Bytes); err == nil {
		if rk, ok := k.(*rsa.PrivateKey); ok {
			return rk, nil
		}
		return nil, fmt.Errorf("商户私钥类型错误")
	}
	k, err := x509.ParsePKCS1PrivateKey(blk.Bytes)
	if err != nil {
		return nil, fmt.Errorf("商户私钥解析失败: %w", err)
	}
	return k, nil
}

func parseRSAPublicKey(raw string) (*rsa.PublicKey, error) {
	pemData := normalizeKey(raw, false)
	if pemData == "" {
		return nil, fmt.Errorf("丰付公钥未配置")
	}
	blk, _ := pem.Decode([]byte(pemData))
	if blk == nil {
		return nil, fmt.Errorf("丰付公钥解析失败")
	}
	if pub, err := x509.ParsePKIXPublicKey(blk.Bytes); err == nil {
		rsaPub, ok := pub.(*rsa.PublicKey)
		if !ok {
			return nil, fmt.Errorf("丰付公钥类型错误")
		}
		return rsaPub, nil
	}
	pub, err := x509.ParsePKCS1PublicKey(blk.Bytes)
	if err != nil {
		return nil, fmt.Errorf("丰付公钥解析失败: %w", err)
	}
	return pub, nil
}

func signRSA256(privateKey string, plain string) (string, error) {
	priv, err := parseRSAPrivateKey(privateKey)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(plain))
	sig, err := rsa.SignPKCS1v15(rand.Reader, priv, crypto.SHA256, hash[:])
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(sig), nil
}

func verifyRSA256(publicKey string, plain string, signature string) bool {
	sig := strings.TrimSpace(signature)
	if sig == "" {
		return false
	}
	pub, err := parseRSAPublicKey(publicKey)
	if err != nil {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(sig)
	if err != nil {
		return false
	}
	hash := sha256.Sum256([]byte(plain))
	return rsa.VerifyPKCS1v15(pub, crypto.SHA256, hash[:], raw) == nil
}

func toGBK(raw string) string {
	if raw == "" {
		return ""
	}
	out, _, err := transform.String(simplifiedchinese.GBK.NewEncoder(), raw)
	if err != nil {
		return raw
	}
	return out
}

func fromGBK(raw string) string {
	if raw == "" {
		return ""
	}
	out, _, err := transform.String(simplifiedchinese.GBK.NewDecoder(), raw)
	if err != nil {
		return raw
	}
	return out
}

func encodeParamsGBK(params map[string]string) string {
	q := url.Values{}
	for k, v := range params {
		q.Set(k, toGBK(v))
	}
	return q.Encode()
}

func decodeJSONAnyMap(raw string) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, err
	}
	if out == nil {
		return nil, fmt.Errorf("empty json object")
	}
	return out, nil
}

func decodeJSONStringMap(raw string) (map[string]string, error) {
	m, err := decodeJSONAnyMap(raw)
	if err != nil {
		return nil, err
	}
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = toString(v)
	}
	return out, nil
}

func postGBKJSON(ctx context.Context, endpoint string, params map[string]string) (map[string]string, plugin.RequestStats, error) {
	reqBody := encodeParamsGBK(params)
	body, reqCount, reqMs, err := httpClient.Do(ctx, http.MethodPost, endpoint, reqBody, "application/x-www-form-urlencoded")
	decoded := fromGBK(body)
	stats := plugin.RequestStats{ReqBody: reqBody, RespBody: decoded, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	resp, decodeErr := decodeJSONStringMap(decoded)
	if decodeErr != nil {
		return nil, stats, fmt.Errorf("响应解析失败: %w", decodeErr)
	}
	return resp, stats, nil
}

func parseFormMap(req *proto.InvokeContext) map[string]string {
	out := map[string]string{}
	if req == nil || req.GetRequest() == nil {
		return out
	}
	appendFormMap(out, req.GetRequest().GetQuery())
	appendFormMap(out, string(req.GetRequest().GetBody()))
	return out
}

func appendFormMap(dst map[string]string, raw string) {
	if strings.TrimSpace(raw) == "" {
		return
	}
	vals, err := url.ParseQuery(raw)
	if err != nil {
		return
	}
	for k, arr := range vals {
		if len(arr) == 0 {
			continue
		}
		dst[k] = arr[len(arr)-1]
	}
}

func lockOrderPage(ctx context.Context, tradeNo string, fetch func() (*proto.PageResponse, plugin.RequestStats, error)) (*proto.PageResponse, error) {
	payload, err := plugin.LockOrderExt(ctx, tradeNo, func() (any, plugin.RequestStats, error) {
		page, stats, err := fetch()
		if err != nil {
			return nil, stats, err
		}
		return plugin.BuildReturnMap(page), stats, nil
	})
	if err != nil {
		return plugin.RespError(err.Error()), nil
	}
	return plugin.BuildReturnPage(payload), nil
}

func nowTradeTime() string {
	return time.Now().Format("20060102150405")
}

func shortTradeTime(tradeNo string) string {
	if len(tradeNo) >= 14 && isDigits(tradeNo[:14]) {
		return tradeNo[:14]
	}
	return nowTradeTime()
}

func orderReturnURL(cfg globalConfig, order *proto.OrderSnapshot) string {
	if cfg.SiteDomain == "" || order == nil {
		return ""
	}
	return cfg.SiteDomain + "/pay/" + order.GetType() + "/" + order.GetTradeNo()
}

func buildPayURL(req *proto.InvokeContext, order *proto.OrderSnapshot, query map[string]string) string {
	if order == nil {
		return ""
	}
	payURL := orderReturnURL(readGlobalConfig(req), order)
	if payURL == "" || len(query) == 0 {
		return payURL
	}
	q := url.Values{}
	for k, v := range query {
		if k == "" || v == "" {
			continue
		}
		q.Set(k, v)
	}
	if q.Get("t") == "" {
		q.Set("t", fmt.Sprintf("%d", time.Now().Unix()))
	}
	qs := q.Encode()
	if qs == "" {
		return payURL
	}
	return payURL + "?" + qs
}

func orderNotifyURL(cfg globalConfig, biz string) string {
	if cfg.NotifyDomain == "" || biz == "" {
		return ""
	}
	return cfg.NotifyDomain + "/pay/" + biz
}
