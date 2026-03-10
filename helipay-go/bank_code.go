package main

import (
	"fmt"
	"strings"
)

type helipayBankRule struct {
	code       string
	display    string
	exactNames []string
}

var helipayBankRules = []helipayBankRule{
	{code: "CCB", display: "建设银行", exactNames: []string{"建设银行"}},
	{code: "CMBCHINA", display: "招商银行", exactNames: []string{"招商银行"}},
	{code: "ABC", display: "农业银行", exactNames: []string{"农业银行"}},
	{code: "BOC", display: "中国银行", exactNames: []string{"中国银行"}},
	{code: "BOCO", display: "交通银行", exactNames: []string{"交通银行"}},
	{code: "POST", display: "邮政储蓄银行", exactNames: []string{"邮政储蓄银行"}},
	{code: "SPDB", display: "浦发银行", exactNames: []string{"浦发银行"}},
	{code: "CIB", display: "兴业银行", exactNames: []string{"兴业银行"}},
	{code: "ECITIC", display: "中信银行", exactNames: []string{"中信银行"}},
	{code: "CEB", display: "光大银行", exactNames: []string{"光大银行"}},
	{code: "PINGAN", display: "平安银行", exactNames: []string{"平安银行"}},
	{code: "CMBC", display: "民生银行", exactNames: []string{"民生银行"}},
	{code: "CGB", display: "广发银行", exactNames: []string{"广发银行"}},
	{code: "HXB", display: "华夏银行", exactNames: []string{"华夏银行"}},

	{code: "BCCB", display: "北京银行", exactNames: []string{"北京银行"}},
	{code: "SHB", display: "上海银行", exactNames: []string{"上海银行"}},
	{code: "SRCB", display: "上海农商银行", exactNames: []string{"上海农商银行"}},
	{code: "JSB", display: "江苏银行", exactNames: []string{"江苏银行"}},
	{code: "CZB", display: "浙商银行", exactNames: []string{"浙商银行"}},
	{code: "BON", display: "南京银行", exactNames: []string{"南京银行"}},
	{code: "NBCB", display: "宁波银行", exactNames: []string{"宁波银行"}},
	{code: "TCCB", display: "天津银行", exactNames: []string{"天津银行"}},
	{code: "HSBANK", display: "徽商银行", exactNames: []string{"徽商银行"}},
	{code: "CBHB", display: "渤海银行", exactNames: []string{"渤海银行"}},
	{code: "HFBANK", display: "恒丰银行", exactNames: []string{"恒丰银行"}},

	{code: "BEA", display: "东亚银行", exactNames: []string{"东亚银行"}},
	{code: "HANGSENGBANK", display: "恒生银行", exactNames: []string{"恒生银行"}},
	{code: "CITI", display: "花旗银行", exactNames: []string{"花旗银行"}},

	{code: "THX", display: "贵阳银行", exactNames: []string{"贵阳银行"}},
	{code: "GDNYBANK", display: "南粤银行", exactNames: []string{"南粤银行"}},
	{code: "LZBANK", display: "兰州银行", exactNames: []string{"兰州银行"}},
}

func inferHelipayBankCode(bankName string) (string, error) {
	raw := strings.TrimSpace(bankName)
	if raw == "" {
		return "", fmt.Errorf("银行名称不能为空")
	}
	norm := strings.ToUpper(strings.TrimSpace(raw))
	if norm == "" {
		return "", fmt.Errorf("银行名称不能为空")
	}
	// 其次按固定名称精确匹配（四字银行不使用简称，避免误判）。
	for _, rule := range helipayBankRules {
		for _, name := range rule.exactNames {
			if name == "" {
				continue
			}
			if norm == strings.ToUpper(strings.TrimSpace(name)) {
				return rule.code, nil
			}
		}
	}
	return "", fmt.Errorf("不支持的银行名称: %s；支持银行: %s", raw, strings.Join(supportedHelipayBankNames(), "、"))
}

func supportedHelipayBankNames() []string {
	out := make([]string, 0, len(helipayBankRules))
	for _, rule := range helipayBankRules {
		if strings.TrimSpace(rule.display) == "" {
			continue
		}
		out = append(out, rule.display)
	}
	return out
}
