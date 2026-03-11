# Alipay 插件迁移差异与补齐清单

更新时间：2026-03-12
适用范围：
- `payment/plugins/alipay-go`
- `payment/plugins/alipayd-go`
- `payment/plugins/alipaysl-go`

## 1. 当前结论（业务视角）

这三个插件的 `biztype_alipay(1~8)` 中，当前**完整独立实现**的是：
- `1` 电脑网站支付（PagePay）
- `2` 手机网站支付（WapPay）
- `3` 扫码支付（Precreate）
- `6` APP支付（AppPay）

当前被“收敛为二维码预下单（Precreate）”的是：
- `4` 当面付JS
- `5` 预授权支付
- `7` JSAPI支付
- `8` 订单码支付

收敛入口（3个插件同构）：
- `payment/plugins/alipay-go/action_create.go`
- `payment/plugins/alipayd-go/action_create.go`
- `payment/plugins/alipaysl-go/action_create.go`

关键代码点（以 `alipay-go` 为例）：
- allow 分支定义：`payment/plugins/alipay-go/action_create.go:35`
- 收敛注释：`payment/plugins/alipay-go/action_create.go:64`
- 4/5/7/8 收敛到 `precreateAsQR`：`payment/plugins/alipay-go/action_create.go:65`

## 2. 为什么收敛（不是 gopay 能力不足）

当前收敛主因是：OKPay 插件上下文里尚未打通“模式专属输入 + 专属流程 + 专属回调”的整链路，而不是 gopay 无法调用。

### 2.1 `4` 当面付JS 缺失项
- 需要买家身份参数：`buyer_id` 或 `buyer_open_id`
- 需要 OAuth / 用户标识获取链路（或明确从内核透传）
- 需要独立页面/回跳处理（类似 epay 的 `jspay`）

### 2.2 `5` 预授权支付 缺失项
- 需要独立下单接口（冻结）与独立异步回调（预授权通知）
- 需要冻结后确认/失败处理策略

### 2.3 `7` JSAPI支付 缺失项
- 需要 `product_code=JSAPI_PAY`
- 需要 `op_app_id`（子应用/小程序 appid）与买家标识
- 需要专门的客户端拉起参数返回结构

### 2.4 `8` 订单码支付 缺失项
- 需要明确 `product_code=QR_CODE_OFFLINE` 的下单语义
- 当前统一 Precreate 只能覆盖“通用扫码”，不能表达“订单码”语义差异

## 3. 相对 epay 的核心差异（参考实现思路）

epay 不是统一 `allowX -> 一个下单 API`，而是：
- `allowX -> 独立函数 -> 独立参数模型 -> 独立通知处理`

在参考项目中可重点看：
- `参考项目/epay/plugins/alipay/alipay_plugin.php`
- `参考项目/epay/plugins/alipayd/alipayd_plugin.php`
- `参考项目/epay/plugins/alipaysl/alipaysl_plugin.php`

典型能力点（epay 已有，当前 OKPay 未全量对齐）：
- `alipay_wappaylogin`（WAP 登录态控制）
- `alipay_paymode`（PC 走页付/扫码策略）
- `ext_user_info` 注入（`cert_no/cert_name/min_age`）
- 预授权独立回调流程（`preauthnotify`）
- JSAPI/当面付JS 的买家标识流程
- 订单码 `QR_CODE_OFFLINE` 语义

## 4. alipayd（直付通）额外缺口

当前 `alipayd-go` 已做基础直付通参数注入：
- `sub_merchant`
- `settle_info`（默认 1d + defaultSettle）

相关代码：
- `payment/plugins/alipayd-go/utils.go:122`

但与 epay 相比，仍缺少：
- 合单支付（combine）拆单与合并单通知
- `direct_settle_time` 结算策略
- `profits/settle` 业务状态流转
- 回调后 `settle_confirm` 与失败补偿

当前 `notify` 只做验签 + `CompleteOrder`，未进入 settle/combine 流程：
- `payment/plugins/alipayd-go/action_notify.go:45`

### 4.1 关于“合单（combine）”范围的明确说明

参考项目里，合单能力是 `alipayd`（直付通版）特有链路，不是三插件都具备：
- `参考项目/epay/plugins/alipayd/alipayd_plugin.php` 有：
- `isCombinePay`
- `combineOrderParams`
- `mergePrecreatePay / wapMergePay / appMergePay / mergeCreate`
- `combine_notify / combine_return`
- `refund_combine / close_combine`

而 `参考项目/epay/plugins/alipay/alipay_plugin.php`、`参考项目/epay/plugins/alipaysl/alipaysl_plugin.php` 没有等价的合单实现。

因此当前 OKPay 的“合单能力缺失”应优先归类为：
- `alipayd-go` 与 epay `alipayd` 的差异
- 不是 `alipay-go/alipaysl-go` 的核心对齐项

## 5. 配置与模型侧缺口

### 5.1 插件输入项尚未覆盖的配置
目前 `info` 里只暴露了 `appid/appkey/appsecret/appmchid/biztype_alipay`，尚未暴露：
- `alipay_wappaylogin`
- `alipay_paymode`
- `direct_settle_time`（至少 `alipayd-go` 需要）

参考：
- `payment/plugins/alipay-go/action_info.go:11`

### 5.2 订单上下文字段缺口
`OrderSnapshot` 没有显式字段：
- `cert_no/cert_name/min_age`
- `sub_openid/sub_appid`
- `combine/profits/settle`

目前仅有 `ext` 可承载扩展：
- `payment/plugin/proto/plugin.proto:42`

## 6. 后续补齐建议（按优先级）

1. 先补 `8` 订单码：低耦合，直接做 `product_code=QR_CODE_OFFLINE` 分支。  
2. 再补 `7` JSAPI：先定义 `ext` 约定（`sub_openid/sub_appid`），打通下单参数。  
3. 再补 `4` 当面付JS：确定用户标识来源（内核透传优先，插件 OAuth 兜底）。  
4. 再补 `5` 预授权：新增独立 notify action 与状态落库策略。  
5. 最后补 `alipayd` 的 combine + settle 策略（涉及内核状态模型协同，变更面最大）。  

## 7. 本文档用途

后续会话可先读本文件，再进入具体改造。建议在每次补齐后更新：
- “已完成项”
- “未完成项”
- “代码入口与验证方式”
