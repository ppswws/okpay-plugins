package main

import (
	"crypto/md5"
	"encoding/hex"
	"sort"
	"strings"
)

func signMD5(params map[string]string, key string) string {
	keys := make([]string, 0, len(params))
	for k, v := range params {
		if v == "" || k == "sign" || k == "sign_type" {
			continue
		}
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var b strings.Builder
	for i, k := range keys {
		if i > 0 {
			b.WriteByte('&')
		}
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(params[k])
	}
	b.WriteString(key)

	sum := md5.Sum([]byte(b.String()))
	return hex.EncodeToString(sum[:])
}

func verifyMD5(params map[string]string, key string) bool {
	sign := strings.ToLower(params["sign"])
	if sign == "" {
		return false
	}
	return sign == strings.ToLower(signMD5(params, key))
}
