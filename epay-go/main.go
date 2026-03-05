package main

import "okpay/payment/plugin"

func main() {
	plugin.Serve(map[string]plugin.HandlerFunc{
		"info":     info,
		"create":   create,
		"alipay":   alipay,
		"wxpay":    wxpay,
		"bank":     bank,
		"query":    query,
		"refund":   refund,
		"transfer": transfer,
		"notify":   notify,
	})
}
