package main

import (
	"crypto/md5"
	"encoding/hex"
	"strings"
)

func signJoinpay(data map[string]string, fields []string, key string) string {
	buf := strings.Builder{}
	for _, field := range fields {
		val := data[field]
		if val == "" {
			continue
		}
		buf.WriteString(val)
	}
	buf.WriteString(key)
	sum := md5.Sum([]byte(buf.String()))
	return hex.EncodeToString(sum[:])
}

func verifyJoinpay(data map[string]string, fields []string, key string) bool {
	sign := strings.ToLower(data["hmac"])
	if sign == "" {
		return false
	}
	return sign == strings.ToLower(signJoinpay(data, fields, key))
}
