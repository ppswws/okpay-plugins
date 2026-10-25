# 通道接口文档

各插件目录下 `api.txt` 为对应渠道的接口文档抄录，随插件源码一起维护。

| 插件 | 文档 | 说明 |
|------|------|------|
| joinpay | `joinpay/api.txt` | 已按官网现行版校正退款通知字段（见文件头「核对警告」） |
| helipay | `helipay/api.txt` | 自 okpay-pro 迁入，抄录版本可能落后于线上 |
| alipay | 不在此仓库 | 官方 llms 文档全量在 okpay 仓库 `docs/llms文档/支付宝/`（3.8M，该目录不入版本库） |

## 使用注意

**文档只是佐证，实发报文才是真源。** 汇聚文档各节修订不同步，同一件事的字段表可能有三张、字段名互相错位
（退款申请应答 / 退款异步通知 / 退款查询各一张，签名顺序各不相同）。2026-10 的退款通知 `sign_error`
即由此而来：代码照旧版文档抄的字段表，网关按新版报文签名。**接入或修改任何签名相关代码前，先以线上文档
核对字段表，再用真实报文验证。**

字段拼接规则各渠道不同，勿跨渠道套用（详见各自文档的「签名规则」节）：

- **joinpay**：按文档固定字段序拼值（空值跳过），末尾拼密钥做 MD5；各表签名顺序见「签名顺序」列
- **helipay**：`&` 分隔、按参数名排序拼值（**空值也拼 `&`**），末尾拼密钥；部分接口用国密 SM3
- **alipay**：RSA2（SHA256withRSA），非 MD5 摘要；见官方开放平台文档

## 未收录

- **sumapay**：三个仓库中均无接口文档抄录（okpay-pro 只留了 Go 实现源码）。当前实现以代码字段表为准；
  如需补齐，向渠道方索取最新接口文档后放至 `sumapay/api.txt`。

## 迁移记录

2026-10-17：joinpay / helipay 文档自 okpay-pro `services/plugins/*-go/api.txt` 迁入。
okpay-pro 已停止维护，okpay-plugins 为插件唯一真源；后续文档修正请直接在本仓库进行。
对比：epay-fork 内的 joinpay `api.txt` 为更旧抄录（无代付/余额/附录节），已就地同步了退款通知更正，
但不再作为文档真源。

2026-10-25：6 个插件迁移到 descriptor / S_WAIT 新 SDK（`PageOutcome`/`NotifyResponse`、`onCashier`、
严格 S_FAIL 边界）；`mvn clean test` 475 tests 全绿，`maven-repo` 1.0.0 构件已同步。
