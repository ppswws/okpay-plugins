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
	buf := strings.Builder{}
	for i, k := range keys {
		if i > 0 {
			buf.WriteByte('&')
		}
		buf.WriteString(k)
		buf.WriteByte('=')
		buf.WriteString(params[k])
	}
	buf.WriteString(key)
	sum := md5.Sum([]byte(buf.String()))
	return hex.EncodeToString(sum[:])
}

func verifyMD5(params map[string]string, key string) bool {
	sign := strings.ToLower(params["sign"])
	if sign == "" {
		return false
	}
	return sign == strings.ToLower(signMD5(params, key))
}
