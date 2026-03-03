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
	"sort"
	"strings"

	"okpay/payment/plugin"
)

const huifuBaseURL = "https://api.huifu.com"

type huifuClient struct {
	sysID      string
	productID  string
	privateKey *rsa.PrivateKey
	publicKey  *rsa.PublicKey
	httpClient *plugin.HTTPClient
}

func newHuifuClient(cfg huifuConfig) (*huifuClient, error) {
	if cfg.AppID == "" || cfg.ProductID == "" || cfg.AppKey == "" || cfg.AppPem == "" {
		return nil, fmt.Errorf("通道配置不完整")
	}
	privateKey, err := parsePrivateKey(cfg.AppKey)
	if err != nil {
		return nil, err
	}
	publicKey, err := parsePublicKey(cfg.AppPem)
	if err != nil {
		return nil, err
	}
	return &huifuClient{
		sysID:      cfg.AppID,
		productID:  cfg.ProductID,
		privateKey: privateKey,
		publicKey:  publicKey,
		httpClient: plugin.NewHTTPClient(plugin.HTTPClientConfig{}),
	}, nil
}

func (c *huifuClient) requestAPI(ctx context.Context, path string, data map[string]any) (map[string]any, plugin.RequestStats, error) {
	sign, err := c.signData(data)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	payload := map[string]any{
		"sys_id":     c.sysID,
		"product_id": c.productID,
		"data":       data,
		"sign":       sign,
	}
	body, err := encodeJSON(payload)
	if err != nil {
		return nil, plugin.RequestStats{}, err
	}
	respBody, reqCount, reqMs, err := c.httpClient.Do(ctx, "POST", huifuBaseURL+path, body, "application/json")
	stats := plugin.RequestStats{ReqBody: body, RespBody: respBody, ReqCount: reqCount, ReqMs: reqMs}
	if err != nil {
		return nil, stats, err
	}
	var resp struct {
		Data map[string]any `json:"data"`
		Sign string         `json:"sign"`
	}
	respMap, err := plugin.DecodeJSONMap(respBody)
	if err != nil {
		return nil, stats, fmt.Errorf("接口返回数据解析失败")
	}
	if v, ok := respMap["data"].(map[string]any); ok {
		resp.Data = v
	}
	resp.Sign = plugin.String(respMap["sign"])
	if len(resp.Data) == 0 || strings.TrimSpace(resp.Sign) == "" {
		return nil, stats, fmt.Errorf("接口返回数据解析失败")
	}
	if !c.verifyResponse(resp.Data, resp.Sign) {
		return nil, stats, fmt.Errorf("接口返回数据验签失败")
	}
	return resp.Data, stats, nil
}

func (c *huifuClient) checkNotifySign(data string, sign string) bool {
	if strings.TrimSpace(sign) == "" {
		return false
	}
	return verifyWithKey(c.publicKey, []byte(data), sign)
}

func (c *huifuClient) verifyResponse(data map[string]any, sign string) bool {
	plain, err := encodeJSONSorted(data)
	if err != nil {
		return false
	}
	return verifyWithKey(c.publicKey, []byte(plain), sign)
}

func (c *huifuClient) signData(data map[string]any) (string, error) {
	plain, err := encodeJSONSorted(data)
	if err != nil {
		return "", err
	}
	return signWithKey(c.privateKey, []byte(plain))
}

func parsePrivateKey(raw string) (*rsa.PrivateKey, error) {
	pemText := "-----BEGIN PRIVATE KEY-----\n" + wordwrap(raw, 64) + "\n-----END PRIVATE KEY-----"
	block, _ := pem.Decode([]byte(pemText))
	if block == nil {
		return nil, fmt.Errorf("商户私钥不正确")
	}
	if key, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		if rsaKey, ok := key.(*rsa.PrivateKey); ok {
			return rsaKey, nil
		}
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	return nil, fmt.Errorf("商户私钥不正确")
}

func parsePublicKey(raw string) (*rsa.PublicKey, error) {
	pemText := "-----BEGIN PUBLIC KEY-----\n" + wordwrap(raw, 64) + "\n-----END PUBLIC KEY-----"
	block, _ := pem.Decode([]byte(pemText))
	if block == nil {
		return nil, fmt.Errorf("汇付公钥不正确")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("汇付公钥不正确")
	}
	rsaKey, ok := pub.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("汇付公钥不正确")
	}
	return rsaKey, nil
}

func signWithKey(key *rsa.PrivateKey, data []byte) (string, error) {
	hashed := sha256.Sum256(data)
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hashed[:])
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(sig), nil
}

func verifyWithKey(key *rsa.PublicKey, data []byte, sign string) bool {
	sig, err := base64.StdEncoding.DecodeString(sign)
	if err != nil {
		return false
	}
	hashed := sha256.Sum256(data)
	return rsa.VerifyPKCS1v15(key, crypto.SHA256, hashed[:], sig) == nil
}

func encodeJSON(value any) (string, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(value); err != nil {
		return "", err
	}
	out := strings.TrimRight(buf.String(), "\n")
	return out, nil
}

func encodeJSONSorted(data map[string]any) (string, error) {
	keys := make([]string, 0, len(data))
	for k, v := range data {
		if v != nil {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	var buf bytes.Buffer
	buf.WriteByte('{')
	for i, k := range keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		keyJSON, _ := json.Marshal(k)
		buf.Write(keyJSON)
		buf.WriteByte(':')
		valJSON, err := encodeJSON(data[k])
		if err != nil {
			return "", err
		}
		buf.WriteString(valJSON)
	}
	buf.WriteByte('}')
	return buf.String(), nil
}

func wordwrap(text string, width int) string {
	text = strings.TrimSpace(text)
	if text == "" || width <= 0 {
		return text
	}
	var parts []string
	for len(text) > width {
		parts = append(parts, text[:width])
		text = text[width:]
	}
	if text != "" {
		parts = append(parts, text)
	}
	return strings.Join(parts, "\n")
}
