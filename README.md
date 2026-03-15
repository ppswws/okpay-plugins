# OKPay Plugins 开发说明

本目录用于存放插件源码（`*-go`），供插件开发者参考与二次开发。

## 1. 目录约定

- 每个插件一个目录：`<name>-go/`
- 插件建议文件结构：

```text
plugin-name-go/
  main.go
  action_info.go
  action_create.go
  action_query.go
  action_notify.go
  action_refund.go
  action_transfer.go
  action_balance.go
  utils.go
```

- 仅维护源码与文档，不提交编译产物。

## 2. 依赖 SDK

插件开发统一依赖：
- `github.com/ppswws/okpay-plugin-sdk`

常见导入：

```go
import (
  "github.com/ppswws/okpay-plugin-sdk"
  "github.com/ppswws/okpay-plugin-sdk/proto"
)
```

SDK 协议与 API 详见：
- `payment/plugin/README.md`
- 公开仓库：`https://github.com/ppswws/okpay-plugin-sdk`

## 3. 实现要求

- 直接实现 `plugin.PluginService`（`Info/Create/Query/Refund/Transfer/Balance/InvokeFunc`）。
- 返回 typed proto message，不使用动态协议。
- 金额使用分（`int64`）或十进制字符串，禁止 float。
- 验签优先使用原始载荷（`raw_http.body_raw`、`raw_http.query_raw`）。

## 4. 常用 SDK 能力

- 页面返回：`plugin.RespJump/RespHTML/RespJSON/RespPage/...`
- 通知与回写：`plugin.RecordNotify`、`plugin.CompleteOrder/Refund/Transfer/CNotify`
- 锁单：`plugin.LockOrderExt`
- Create 分发：`plugin.CreateWithHandlers`
- UA 判断：`plugin.IsWeChat/IsAlipay/IsMobileQQ/IsMobile`
- 微信辅助：`plugin.BuildMPOAuthURL/GetMPOpenid/GetMiniOpenid/GetMiniScheme`
- 支付宝 OAuth 辅助：`plugin.BuildAliOAuthURL/GetAliIdentity`

## 5. 构建

在 `payment` 目录批量构建所有插件：

```bash
for d in plugins/*-go; do n="$(basename "$d" -go)"; go build -ldflags="-s -w" -o "plugins/$n" "./$d"; done
```

## 6. 迁移差异记录

当前支付宝插件迁移差异与后续补齐计划见：
- `payment/plugins/_notes.md`
