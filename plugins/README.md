# okpay-plugins 插件开发指南

本文是通道插件的开发准则（支付流程、S_FAIL / S_WAIT、`lockCreate` 用法）与渠道接口文档索引。
仓库构建、目录结构、发布流程见仓库根 `README.md`。

核心结论：**pay_url 是入口，ext 是“渠道是否已经请求并拿到可缓存支付数据”的唯一判据；渠道成功数据必须经 `Sdk.lockCreate` 写入 ext，失败/本地错误不得伪造 ext。**

## 1. 两个字段

| 字段 | 谁写 | 含义 |
|---|---|---|
| `pay_url` | 宿主 `OrderService` | 商户/用户可打开的支付入口。渠道直跳时是渠道收银台 URL；本地中转/HTML/JSON/QR 时是宿主 `/pay/cashier/{tradeNo}`（固定入口，不按支付方式拼） |
| `ext` | SDK `lockCreate`/`lockOrderExt` | 渠道成功响应后缓存的支付载荷（跳转地址、HTML/JSON 数据、二维码 URL 等） |

- `ext` **只在成功载荷时写入**；failed 载荷、异常、本地/配置/OAuth 失败都不写 ext（只写请求轨迹）。
- `ext` 非空 = 之前已有一次渠道请求成功并缓存了支付数据；收银台直接复用，不再请求渠道。
- `ext` 为空不等于“没有入口”——JSPay/JSAPI 等本地中转页在用户进入特定环境前，ext 本来就是空的。
- 订单已非待支付（如已 `-2`）时，成功回执也不再写 ext：宿主拒绝并记 WARN；`-2` 不可复活。
- 不要用 `pay_url` 反推渠道是否请求过；判断渠道交互看 ext。

## 2. 支付流程形态

| 形态 | 是否请求渠道 | ext | pay_url | create 返回 |
|---|---|---|---|---|
| 渠道收银台直跳（渠道说明可直接跳转） | 是（`lockCreate`） | `jump -> url` 等载荷 | 渠道 URL | `Redirect` |
| 渠道返回 HTML/JSON/二维码 URL，由我们页面展示 | 是（`lockCreate`） | 页面载荷 | 宿主 `/pay/cashier/{tradeNo}` | `Page/Html/Json` |
| 本地中转页，尚未请求渠道（JSPay/JSAPI 需先拿 openid/user_id） | 否 | 空 | 宿主 `/pay/cashier/{tradeNo}` | `Page/Html/Json` |
| 用户在目标环境进入中转页后才请求渠道 | 是（收银台重走 create，内部 `lockCreate`） | 页面载荷 | 已有本地 pay_url | 页面 |
| OAuth 跳转（只拿身份，不请求支付） | 否 | 空 | OAuth URL | `Redirect` 或页面 |
| 渠道有响应且可解析、业务拒绝/缺支付数据 | 是 | 空（失败只写轨迹） | 可能已有本地 pay_url | `S_FAIL`（`Pages.fail`） |
| 渠道超时/网络/不可解析/同步验签失败/配置/OAuth 失败 | 尝试过但无可用数据 | 空 | 可能已有本地 pay_url | `S_WAIT`（抛预期异常） |

### 收银台重入（SDK 固定，不由插件实现）

`PaymentChannel.CASHIER_FUNC`（`/pay/cashier/{tradeNo}`）由 `AbstractPaymentChannel` 固定注册：

```
ext 非空 → Sdk.cachedPage(ctx)          // 渠道数据已固化，直接还原
ext 为空 → create(ctx)                  // 渠道尚未请求，按当前浏览器环境重走建单
```

插件只实现 `create`，**不要注册/实现 cashier**。`create` 必须对同一份上下文做到：

- 不在目标环境（如 PC 打开公众号支付）→ 返回二维码中转页；
- 在目标环境但无身份（微信内无 `code`/`auth_code`）→ 返回 OAuth 跳转；
- OAuth 回跳带上 `code`/`auth_code` → 兑换用户标识 → 请求渠道 → `lockCreate` 写 ext → 返回支付页；
- 渠道请求失败/超时 → `S_WAIT`（`ExpectedFailureException`/`ResultUnknownException`），不伪造页面。

以微信 JSPay 为例的完整链路：

1. 首次建单：`create` 返回二维码中转页，`ext` 为空，`pay_url=/pay/cashier/{tradeNo}`。
2. PC/非微信打开：出二维码，用户手机扫码。
3. 手机微信打开收银台（无 `code`）：`create` 返回 OAuth 授权跳转。
4. OAuth 回跳 `/pay/cashier/{tradeNo}?code=...`：基类看到 `ext` 为空，重走 `create`；插件兑换 openid、请求渠道、写 ext、返回 `wxpay_jspay` 页面。
5. 再次打开收银台：`ext` 非空，基类直接还原页面，不再请求渠道。

**这是中转支付页的主链路，不是兜底分支。** 任何“收银台只重建本地页、不继续建单”的实现都会让 OAuth 回跳停在半路（线上表现：`插件返回空结果` / `渠道处理暂时不可用`，`buyer_id` 已落库但 `ext` 为空、无渠道报文）。

## 3. 什么时候用哪个状态

### S_OK

- 只有渠道明确成功：支付成功、退款成功、付款成功。
- 订单查单/回调：只有明确成功才返回 `S_OK`。

### S_FAIL

只允许以下场景：

1. **建单/`lockCreate` 内**：渠道有响应且可正常解析，但拿不到预期的支付数据/链接，或渠道明确业务拒绝。
2. **退款/付款提交/查单/回调**：渠道业务上明确“退款失败/付款失败”；查单“订单不存在”只有在渠道文档明确其语义为失败时才可映射 `S_FAIL`，否则 `S_WAIT`。
3. **订单创建**：`PageOutcome.Failed`（建单阶段等价 `S_FAIL`）。建单失败看 ext：ext 为空 → `-2`；ext 非空则保持 `0` 并按已有入口返回。create 失败时 pay_url 尚未写入；成功建单必然有 pay_url（本地中转页也有），不能用 pay_url 反推渠道是否请求过。

**解析先行**：HTTP 状态不是业务成败判据。先解析响应体（含 4xx/5xx）；能解析且验签通过 → 按渠道业务码分类；无法解析才按 HTTP 状态归 `S_WAIT`；超时/断连无响应仍 `S_WAIT`。SDK 不提供 `HttpResponse.isSuccess` 包装；仅二进制（图片/证书下载）、空响应（如 204 DELETE）等无业务报文场景在调用点就地按状态码判断。

各渠道建单的 S_FAIL 口径：

| 渠道 | S_FAIL | S_WAIT（同一次响应） |
|---|---|---|
| joinpay | `ra_Code != 100`；成功但缺 `rc_Result`/`rd_Pic`/JSAPI 参数 | 空/坏 JSON、验签失败、超时/网络 |
| alipay | 可解析且 `!ok()`；成功但缺二维码/交易号/调起参数 | 空/坏 JSON、验签失败、超时/网络 |
| epay | 可解析且业务码非成功；成功但缺支付地址 | 空/坏 JSON、无业务码、超时/网络 |
| helipay | `rt2_retCode != 0000`；成功但缺支付地址/调起参数 | 空/坏 JSON、验签失败、超时/网络 |
| sumapay | 下单非 `00000`（含 `00001`/未知/空码）；成功但缺 `payUrl`/`pay_info` | 空/坏 JSON、验签失败、超时/网络 |
| wxpay | 解析出的业务码（如 `PARAM_ERROR`/`ORDER_CLOSED`/`OUT_TRADE_NO_USED`）；2xx 但缺 `code_url`/`h5_url`/`prepay_id` | `SYSTEM_ERROR`/`SYSTEMERROR`/`SERVICE_UNAVAILABLE`/`FREQUENCY_LIMITED`/`RATELIMIT_EXCEEDED`/`SIGN_ERROR`/`NOAUTH`/`OPENID_MISMATCH`/`RULELIMIT`/无 code/验签失败 |

- JSAPI 参数 JSON 能解析即视为拿到预期数据，不校验字段完整性（按现有口径）。
- sumapay 退款/付款的 `00001` 仍按受理中 `S_WAIT`；只有下单不适用该语义。

禁止用 `S_FAIL` 表达：

- 网络超时、连接失败
- 响应无法解析、验签失败
- 通道/系统配置错误
- 公众号/小程序获取 openid 失败、OAuth 失败
- 本地前置校验失败（参数/字段缺失等）
- 订单查单/回调中的未支付、订单不存在、明确关闭/撤销
- 任何“不确定渠道到底有没有成功”的情况

以上一律 `S_WAIT`。

### S_WAIT

- 结果未知/不确定：超时、网络、解析、验签、配置、OAuth、本地错误、未知异常。
- 订单查单/回调：所有非成功情况（未支付、不存在、关闭、撤销、参数/验签失败）。
- 退款/付款：不满足 `S_FAIL` 明确业务失败条件的一切情况。
- `S_WAIT` 不推进任何终态；订单保持 `0`，退款/付款保持 `0`，等待回调/查单/人工。

### 异常与返回值映射

| 语义 | 建单（create/lockCreate） | 退款/付款提交/查单 |
|---|---|---|
| `S_FAIL` | `return Pages.fail(...)`（或抛 `FailureException`） | 返回 `BizResult(state=S_FAIL)`（或抛 `FailureException`） |
| `S_WAIT` | 抛 `ExpectedFailureException` / `ResultUnknownException` | 返回 `BizResult(state=S_WAIT)` / `Responses.wait(...)` |
| `S_OK` | 返回成功页面（`Page/Redirect/Html/Json`） | 返回 `BizResult(state=S_OK)` |

本地/配置/OAuth 失败**不得**构造 `Pages.fail`/`FailureException`；必须走 S_WAIT。

## 4. 宿主如何处理

### 建单（create）

| 插件返回 | 宿主处理 |
|---|---|
| `Redirect(url)` | `pay_url=url`，状态 `0` |
| `Page/Html/Json`（ext 空） | 本地中转页：`pay_url=/pay/cashier/{tradeNo}`，ext 保持空，状态 `0` |
| `Page/Html/Json`（ext 非空） | 本地展示页：`pay_url=/pay/cashier/{tradeNo}`，ext 保留，状态 `0` |
| `PageOutcome.Failed`，ext 为空 | `-2` 创建失败 + result，API 返回创建失败 |
| `PageOutcome.Failed`，ext 非空 | 保持 `0`；API 按已有入口返回，页面返回错误页；记录“明确失败但已有入口”冲突，不 `-2` |
| 异常 / 超时 / 不可解析 / 配置/OAuth 失败 | `S_WAIT`：保持 `0` + result；API 返回“订单正在创建/结果未知，请稍后重试”，页面返回错误/重试页 |

- 失败判定看 ext：ext 非空说明渠道已请求并拿到数据，绝不能折回 `-2`；ext 为空才允许 `-2`。
- 成功建单一定有 `pay_url`；`Page/Html/Json` 的本地 `pay_url` 由宿主写，不需要插件写。不能用 `pay_url` 反推渠道是否请求过。
- **页面路径同样按 ext 判定**：`PageOutcome.Failed` 一律调用 `failOrder`；`status=0 且 ext 为空` → `-2` 并跳结果页，ext 非空或并发已推进 → 保持状态并返回错误页。本地 `pay_url` 不豁免。
- **`-2` 不可复活**：成功建单回执晚于终态推进（并发窗口）时，宿主拒绝写 ext 并记 WARN；插件不要尝试重试/复活。
- 重复下单复用只认 `pay_url`；pay_url 为空就是“订单正在创建”，不能用 ext 补本地支付页。

### 收银台（cashier）

- 入口固定 `/pay/cashier/{tradeNo}`，由 SDK 基类统一处理；插件不实现。
- ext 非空：还原缓存页面，不再请求渠道。
- ext 为空：重走 `create`，由插件按当前 UA/query/配置出二维码、OAuth 跳转，或兑换身份后请求渠道并写 ext。
- 页面 `S_FAIL`：`failOrder`（ext 空 → `-2`，跳结果页）；`S_WAIT`/异常：保持 `0` + result，在当前路由输出错误/重试页，保留刷新重走支付的机会。

### 订单查单 / T_PAY 回调

- 只有 `S_OK → 1`。
- `S_FAIL` 在订单查单/回调中**禁止使用**；即使返回，宿主也按 `S_WAIT` 忽略，不改状态、不写 result。
- 非成功一律 `S_WAIT`；订单保持 `0`，等待 TTL、查单或迟到成功回调。
- `-1` 只由 TTL 超时关闭产生；`-2` 只由建单明确失败产生；`-3` 只由风控产生。

### 退款 / 付款

- 有失败终态：`S_OK → 1`、`S_FAIL → -1`、`S_WAIT → 0`。
- 提交/查单/回调都写 result（最后一次处理结果）。
- `S_FAIL` 只限明确业务失败（退款失败/付款失败，或文档明确“查单不存在=失败”）；解析先行：HTTP 状态不预筛，可解析的非成功业务码按文档语义分类。

### result 写入边界

- 建单 API 与收银台页面路径的 `S_WAIT` 都写 result（最后一次失败/未知原因）；建单成功不写。
- 查单/回调只写 `S_OK` 成功结果；`S_FAIL/S_WAIT` 不写。
- 退款/付款提交/查单/回调都写 result；超时关闭不写。
- 超时关闭、查单重试耗尽、协议异常挂起、商户通知重试耗尽都属于运营性状态，**不写/不覆盖**业务 result。
- 建单 `S_WAIT` 的 result 在 `/pay/result` 等待视图的「说明」处展示。
- 商户 API（`/api/**`）始终返回标准 `ApiResponse` 信封（`code/message/timestamp`），与页面渲染是两套协议。

## 5. 如何正确使用 lockCreate

**所有“可能请求渠道并产出支付数据”的 create 路径都必须走 `Sdk.lockCreate`；不要把渠道成功页直接返回。**

```java
return Sdk.lockCreate(ctx, () -> {
    var resp = 请求渠道(...);
    // 成功：返回页面 + 响应轨迹
    return Sdk.FetchResult.of(Pages.redirect(url), resp);
    // 可解析但业务拒绝/缺支付数据：返回失败页（SDK 只写轨迹，不写 ext）
    // return Sdk.FetchResult.of(Pages.fail("渠道明确拒绝"), resp);
    // 不可解析/超时/验签失败/配置：抛 ExpectedFailureException / ResultUnknownException（S_WAIT）
});
```

`lockCreate` 的行为：

1. **先探测 ext**：已存在则直接还原页面，**不再请求渠道**（复用/幂等）。
2. 执行 fetch；成功与失败均写请求轨迹。
3. 成功载荷 → 写 ext；返回页面。
4. failed 载荷 / `FailureException` → 只写轨迹，不写 ext，还原为 `PageOutcome.Failed`。
5. `ExpectedFailureException` / `ResultUnknownException` / 未预期异常 → 只写轨迹，异常上抛（宿主归 `S_WAIT`；未预期异常记 ERROR）。
6. 并发：失败方若发现另一请求已写入 ext，直接收敛为缓存页（先写者赢）；只有失败先到且订单已终态时才不写并 WARN。

插件禁止：

- 不要直接调用 `Sdk.lockOrderExt`（除非你在实现 SDK 级高级缓存，六插件均不需要）；不要手写 ext、不要手写 pay_url。
- 不要把“渠道请求成功后的页面”绕过 `lockCreate` 直接返回（ext 不会写入，收银台会重复请求渠道）。
- 不要把本地/配置/OAuth 失败包装成 `Pages.fail`/`FailureException`（那是 S_FAIL）；用预期异常走 S_WAIT。
- 不要根据 HTTP 状态判断业务成功；先解析报文并验签（如渠道有验签），按业务码分类。
- 不要在 catch 里吞掉渠道结果后另造页面；渠道成功数据必须原样进入 `FetchResult.of`。
- 渠道返回业务错误/缺支付数据时，不得包装成成功页面；必须按业务码返回 `Pages.fail`（S_FAIL）或抛预期异常（S_WAIT）。
- 应返回中转页的流程必须返回 `Page/Html/Json`；返回 `null`/异常宿主会归 `S_WAIT`，返回 `Redirect` 会被宿主当作支付入口。
- `lockCreate` 的返回值必须原样返回；成功后不要在外层再改写成 `Failed`/异常/另一页面。
- `Redirect` URL 必须 http(s) 且 ≤1024；HTML/JSON 数据必须走 `Html`/`Json`/`Page`，不得塞进 URL。

本地页面（二维码中转页、OAuth 跳转、小程序 scheme 等，**未请求支付渠道**）可以直接返回 `Page/Redirect`，ext 保持空；收银台重入时会重走 create。若本地页面是确定性产物且希望缓存（如部分渠道的本地 HTML），也可以放进 `lockCreate`，但**绝不能**把渠道成功数据放在 `lockCreate` 外面返回。

## 6. 插件实现自检

- [ ] 渠道成功数据只在 `Sdk.lockCreate` 内返回；不手写 ext/pay_url；不直接调用 `lockOrderExt`。
- [ ] failed 载荷用 `Pages.fail`（S_FAIL），并在 `lockCreate` 内返回；不要绕过 lockCreate。
- [ ] 本地/配置/OAuth/网络/解析/验签失败抛 `ExpectedFailureException`/`ResultUnknownException`（S_WAIT），不构造 `Pages.fail`。
- [ ] **不实现 `onCashier`**：收银台由 SDK 基类固定重入 `create`；插件只需保证 `create` 对 PC/微信内/OAuth 回跳三种环境分别产出二维码/OAuth 跳转/渠道下单页。
- [ ] JSPay/JSAPI 中转到渠道请求前：返回 `Page/Html/Json`，ext 保持空；OAuth 回跳后 `create` 必须继续请求渠道并写 ext。
- [ ] 订单查单/回调：只有明确成功 `S_OK`；其余全部 `S_WAIT`，不返回 `S_FAIL`。
- [ ] 退款/付款：只有渠道业务明确失败才 `S_FAIL`；查单“不存在”按渠道文档语义决定，否则 `S_WAIT`；sumapay 下单非 `00000` 一律 `S_FAIL`，退款/付款 `00001` 仍 `S_WAIT`。
- [ ] 先解析响应再分类；HTTP 状态只在无法解析/二进制空响应时就地使用；不使用 `HttpResponse.isSuccess`（已删除）。
- [ ] `lockCreate` 返回值直接透出，不二次改写；`Redirect` URL 为 http(s) 且 ≤1024。
- [ ] JSAPI 参数 JSON 能解析即视为成功，不额外校验字段完整性。
- [ ] 不按 message 文本判断结果；只用 `BizResult.state` / `PageOutcome` 类型。

## 7. 通道接口文档

各插件目录下 `api.txt` 为对应渠道的接口文档抄录，随插件源码一起维护。

| 渠道 | 说明 |
|------|------|
| joinpay | `api.txt` 已按官网现行版校正退款通知字段（见文件头「核对警告」） |
| helipay | `api.txt` 自 okpay-pro 迁入，抄录版本可能落后于线上 |
| alipay | 不在此仓库；官方 llms 文档在 okpay 仓库 `docs/llms文档/支付宝/`（该目录不入版本库） |
| sumapay | 暂无接口文档抄录（okpay-pro 只留 Go 实现源码）；当前实现以代码字段表为准，补齐时向渠道方索取最新文档 |

### 使用注意

**文档只是佐证，实发报文才是真源。** 汇聚文档各节修订不同步，同一件事的字段表可能有三张、字段名互相错位
（退款申请应答 / 退款异步通知 / 退款查询各一张，签名顺序各不相同）。2026-10 的退款通知 `sign_error`
即由此而来：代码照旧版文档抄的字段表，网关按新版报文签名。**接入或修改任何签名相关代码前，先以线上文档
核对字段表，再用真实报文验证。**

字段拼接规则各渠道不同，勿跨渠道套用（详见各自文档的「签名规则」节）：

- **joinpay**：按文档固定字段序拼值（空值跳过），末尾拼密钥做 MD5；各表签名顺序见「签名顺序」列
- **helipay**：`&` 分隔、按参数名排序拼值（**空值也拼 `&`**），末尾拼密钥；部分接口用国密 SM3
- **alipay**：RSA2（SHA256withRSA），非 MD5 摘要；见官方开放平台文档

### 迁移记录

2026-10-17：joinpay / helipay 文档自 okpay-pro `services/plugins/*-go/api.txt` 迁入。
okpay-pro 已停止维护，okpay-plugins 为插件唯一真源；后续文档修正请直接在本仓库进行。
对比：epay-fork 内的 joinpay `api.txt` 为更旧抄录（无代付/余额/附录节），已就地同步了退款通知更正，
但不再作为文档真源。

2026-10-25：6 个插件迁移到 descriptor / S_WAIT 新 SDK（`PageOutcome`/`NotifyResponse`、`onCashier`、
严格 S_FAIL 边界）；`mvn clean test` 475 tests 全绿，`maven-repo` 1.0.0 构件已同步。
