## Purpose

定义广告 SDK 通过事件回调交付给宿主的跨格式业务埋点契约，使展示漏斗、收益、失败原因和加载口径可以一致统计与排查。

## ADDED Requirements

### Requirement: 实际曝光事件

系统 SHALL 在平台确认实际曝光的回调到达时发送业务事件 `ad_show`，覆盖 AdMob 开屏、插屏、激励、Native（含全屏 Native）、Banner 以及 TopOn 开屏、插屏、激励、Native（含全屏 Native）。`ad_show` SHALL NOT 依赖收益回调，收益回调 SHALL NOT 产生 `ad_show`。每次展示最多发送一条 `ad_show`，重复的曝光回调 SHALL NOT 重复发送。一次展示已经以 `ad_show_fail` 收口后，迟到的曝光回调 SHALL NOT 再发送 `ad_show`；已发送 `ad_show` 的展示 SHALL NOT 再发送 `ad_show_fail`。

`ad_show` 的业务参数 SHALL 与同一展示的 `ad_click` 一致，包含 `ad_format`、`ad_platform`、`mediation_mode`、`position_id`、`ad_session_id`，以及可用时的 `ad_unit_id`、`ad_source`；Banner 另含该展示的 `refresh_index`。`ad_show` SHALL NOT 携带 `revenue_amount`、`currency`、`precision_type`。

#### Scenario: 实际曝光但没有有效收益
- **WHEN** AdMob 插屏触发实际曝光回调，但收益回调缺失或金额无效
- **THEN** 发送一条 `ad_show`，不发送 `ad_impression`

#### Scenario: 收益先于曝光
- **WHEN** AdMob 激励广告的有效收益回调先于实际曝光回调到达
- **THEN** 收益回调发送 `ad_impression`，实际曝光回调发送 `ad_show`，两者共用同一 `ad_session_id` 且各一条

#### Scenario: TopOn 展示回调同时确认曝光
- **WHEN** TopOn 开屏或 Native 的展示回调到达并携带金额
- **THEN** 同时发送 `ad_show` 与 `ad_impression`；收益字段只出现在 `ad_impression`

#### Scenario: 重复曝光回调
- **WHEN** 同一展示的实际曝光回调到达两次
- **THEN** 只发送一条 `ad_show`

#### Scenario: 失败收口后的迟到曝光
- **WHEN** 全屏展示已发送 `ad_show_fail`，随后平台曝光回调才到达
- **THEN** 不发送 `ad_show`

#### Scenario: Banner 刷新
- **WHEN** 同一 Banner 页面周期内首个广告和两次刷新后的新广告分别触发实际曝光
- **THEN** 发送三条 `ad_show`，`refresh_index` 依次为 1、2、3，与对应 `ad_impression` 一致

### Requirement: 收益曝光口径保持不变

系统 SHALL 保持 `ad_impression` 现有的触发回调、字段与去重：AdMob 各格式及 TopOn 插屏、激励在有效收益回调时发送，TopOn 开屏和 Native 在展示回调时发送。新增 `ad_show` SHALL NOT 改变 `ad_impression` 及 `revenueListener` 的交付次数和金额。

#### Scenario: 新增事件后的收益交付
- **WHEN** AdMob 插屏依次触发实际曝光和有效收益回调
- **THEN** `ad_impression` 与 `revenueListener` 各交付一次，金额和币种与平台回调一致

### Requirement: 失败原因细分

`ad_load_fail` 与 `ad_show_fail` SHALL 保持现有 `reason` 取值。当 SDK 内部原因码与上报的 `reason` 不同，且原因码为小写字母、数字和下划线组成的 SDK 本地代码时，系统 SHALL 附加 `reason_detail` 为该原因码。`reason` 归为 `exception` 的事件 SHALL NOT 附加 `reason_detail`，无论原始原因的格式如何，因为异常路径的原始原因可能是异常消息。平台错误消息等非代码文本 SHALL NOT 进入 `reason_detail`；平台错误文本仍只通过 `error_detail` 交付。

#### Scenario: 未识别的本地原因
- **WHEN** 展示失败的 SDK 原因码不在已归类集合中，`reason` 上报为 `ad_error`
- **THEN** 事件附加 `reason_detail`，值为该原因码

#### Scenario: 已归类的细分原因
- **WHEN** 展示失败原因码为 `wait_timeout`
- **THEN** `reason` 为 `timeout`，`reason_detail` 为 `wait_timeout`

#### Scenario: 原因码与类别相同
- **WHEN** 失败原因码为 `no_fill`
- **THEN** `reason` 为 `no_fill`，不附加 `reason_detail`

#### Scenario: 平台错误文本
- **WHEN** 失败原因是平台返回的自然语言错误消息
- **THEN** 不附加 `reason_detail`

#### Scenario: 异常消息恰为纯小写单词
- **WHEN** Banner 加载调用抛出异常，异常消息为 `disconnected`，`reason` 上报为 `exception`
- **THEN** 不附加 `reason_detail`

### Requirement: 契约外事件与无效收益可诊断

开启 SDK 日志时，系统 SHALL 为以下情况输出诊断日志，且不改变事件回调和收益回调的交付行为：业务事件因不满足业务契约而返回空业务参数；全屏广告平台收益因金额缺失、为负、非有限或币种缺失而被丢弃。`ad_banner_refresh` 与 Banner `ad_close` 属于约定不上报的诊断事件，SHALL NOT 产生契约外诊断。关闭日志时 SHALL NOT 产生这些输出。

#### Scenario: 业务事件缺少必填身份
- **WHEN** 日志开启，一条 `ad_loaded` 缺少合法耗时，业务参数被置为空
- **THEN** 输出包含事件名和广告格式的诊断日志，宿主仍收到该事件且业务参数为空

#### Scenario: 全屏收益金额无效
- **WHEN** 日志开启，AdMob 全屏收益回调的币种为空，或 TopOn 全屏收益缺少合法的 USD 金额
- **THEN** 输出包含平台和广告格式的诊断日志，可确认时包含展示会话；不发送 `ad_impression`，不交付收益回调

#### Scenario: 约定的诊断事件
- **WHEN** 日志开启，产生 `ad_banner_refresh`
- **THEN** 不输出契约外诊断

### Requirement: AdMob 全屏自动加载事件覆盖

AdMob 开屏、插屏、激励的持续预加载，在本次启动的首个加载结果之后，每个预加载成功或失败回调 SHALL 视为一次 SDK 自动加载：使用新的 `request_id` 依次发送 `ad_load` 与 `ad_loaded` 或 `ad_load_fail`，附加 `load_trigger=sdk_auto`，不发送 `latency_ms`。库存耗尽通知 SHALL NOT 产生加载事件。已停止或已被替换的预加载代次的回调 SHALL 被忽略。

#### Scenario: 取用广告后自动补货
- **WHEN** 插屏预加载首个结果已上报，展示取用一条广告后 SDK 自动补货并回调成功
- **THEN** 以新的 `request_id` 发送 `ad_load` 与 `ad_loaded`，二者均带 `load_trigger=sdk_auto`，`ad_loaded` 不含 `latency_ms`

#### Scenario: 自动重试失败
- **WHEN** 首个结果之后 SDK 自动重试并回调失败
- **THEN** 以新的 `request_id` 发送 `ad_load` 与 `ad_load_fail`，带 `load_trigger=sdk_auto`

#### Scenario: 库存耗尽与过期代次
- **WHEN** 收到库存耗尽通知，或已停止的预加载代次仍回调成功
- **THEN** 不发送加载事件
