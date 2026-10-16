# ad-analytics-events Specification

## Purpose
定义广告 SDK 通过事件回调交付给宿主的跨格式业务埋点契约，使展示漏斗、收益、失败原因和加载口径可以一致统计与排查。

## Requirements

### Requirement: 实际曝光事件

系统 SHALL 在平台确认实际曝光的回调到达时发送业务事件 `ad_show`，覆盖 AdMob 开屏、插屏、激励、Native（含全屏 Native）、Banner 以及 TopOn 开屏、插屏、激励、Native（含全屏 Native）。`ad_show` SHALL NOT 依赖收益回调，收益回调 SHALL NOT 产生 `ad_show`。每次展示最多发送一条 `ad_show`，重复的曝光回调 SHALL NOT 重复发送。一次展示已经以 `ad_show_fail` 收口后，迟到的曝光回调 SHALL NOT 再发送 `ad_show`；已发送 `ad_show` 的展示 SHALL NOT 再发送 `ad_show_fail`。

TopOn 开屏 `onAdShow`、插屏 `onInterstitialAdShow`、激励 `onRewardedVideoAdPlayStart` SHALL 在登记 showId/收益关联前，通过现有 `revenueTracker.confirm(revenueIdentity(info, session.format), session)` 确认归属。无 token 但已有可靠 showId → 当前 session 关联时 SHALL 接受；无 token 且 showId 未知、缺失或空白时 SHALL 拒绝，SHALL NOT 以 active 会话兜底。被拒绝的回调 SHALL NOT 登记身份、发送 `ad_show`、设置 `hasImpression`、输出开屏会话业务 `ad_impression`、触发展示频控或该回调的 `afterShow` 补量。此约束仅收紧这三个正向展示回调，点击、关闭、奖励和失败回调 SHALL 保持既有校验，不声明其他回调已完全隔离。

`ad_show` 的业务参数 SHALL 与同一展示的 `ad_click` 一致，包含 `ad_format`、`ad_platform`、`mediation_mode`、`position_id`、`ad_session_id`，以及可用时的 `ad_unit_id`、`ad_source`；Banner 另含该展示的 `refresh_index`。`ad_show` SHALL NOT 携带 `revenue_amount`、`currency`、`precision_type`。

#### Scenario: 实际曝光但没有有效收益
- **WHEN** AdMob 插屏触发实际曝光回调，但收益回调缺失或金额无效
- **THEN** 发送一条 `ad_show`，不发送 `ad_impression`

#### Scenario: 收益先于曝光
- **WHEN** AdMob 激励广告的有效收益回调先于实际曝光回调到达
- **THEN** 收益回调发送 `ad_impression`，实际曝光回调发送 `ad_show`，两者共用同一 `ad_session_id` 且各一条

#### Scenario: TopOn 展示回调同时确认曝光
- **WHEN** TopOn 开屏的展示回调确认归属，或 Native 按原有规则确认展示，且回调携带金额
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

#### Scenario: 迟到 A 回调不占用 B 的曝光
- **WHEN** A 已结束且 B 为当前会话，A 的上述正向展示回调迟到，tracker 无法确认该身份属于 B
- **THEN** 不为 B 登记该身份、不消耗 B 的曝光去重状态，也不触发 B 的频控或该回调的补量
- **AND** B 后续收到可确认归属的展示回调时，仍按原契约发送一次 `ad_show`

#### Scenario: 已有关联的 showId 不携带 token
- **WHEN** 上述正向展示回调无 token，但 showId 已可靠关联到当前 session 且 tracker 确认通过
- **THEN** 仍接受该展示，按原契约处理曝光与去重；开屏仍在该回调内先发送 `ad_show` 再发送 `ad_impression`

#### Scenario: 整次展示均无可信身份
- **WHEN** 上述正向展示回调全程无 token，且 showId 未知、缺失或空白，始终无法确认归属
- **THEN** 不登记身份、不发送 `ad_show`、不设置 `hasImpression`、不输出开屏会话业务 `ad_impression`，也不触发展示频控或该回调的 `afterShow` 补量
- **AND** 未确认曝光即关闭时沿用 `Failed(dismissed_before_impression)`；开屏现有 2 秒 `show_callback_timeout` 仍生效，合法收益按原有策略处理

#### Scenario: 晚到收益不补展示
- **WHEN** 展示回调因身份不可信被拒绝，随后收到合法收益回调
- **THEN** 收益按既有归属、去重和交付策略处理，SHALL NOT 补发 `ad_show`，也不以收益代替展示确认或消耗 `ad_show` 去重状态
- **AND** 开屏 SHALL NOT 因该收益回调补发会话业务 `ad_impression`

#### Scenario: 未确认曝光的关闭与奖励收尾
- **WHEN** 上述正向展示回调无法确认归属，之后关闭回调按既有规则被接受，且此前仍无可信曝光
- **THEN** 返回 `Failed(dismissed_before_impression)`，释放全屏门，仅交付一次结果
- **AND** 激励此前若已按既有规则收到奖励，`rewardEarned` 仍为 true，展示失败 SHALL NOT 清除该奖励资格

#### Scenario: 开屏身份确认与既有超时
- **WHEN** 开屏开始展示后，在既有 2 秒展示回调超时之前仍无可信展示确认，且未先由关闭或失败回调收尾
- **THEN** 按 `show_callback_timeout` 失败收尾，移除容器并释放全屏门，不影响后续按原规则交付合法收益
- **AND** 超时前已收到可信展示确认的开屏 SHALL NOT 被该超时结束；已关闭的展示 SHALL NOT 被该超时重复收尾

### Requirement: 收益曝光口径保持不变

系统 SHALL 保持 `ad_impression` 现有的触发回调、字段与去重：AdMob 各格式及 TopOn 插屏、激励在有效收益回调时发送，TopOn 开屏在 tracker 确认展示归属后仍于 `onAdShow` 发送，Native 仍在原展示回调时发送。开屏身份无法确认时 SHALL NOT 输出会话业务 `ad_impression`；已确认展示 SHALL 保持原触发时机及可无金额的规则。新增 `ad_show` 本身 SHALL NOT 改变 `ad_impression` 及 `revenueListener` 的交付次数和金额；本次展示身份校验 SHALL NOT 改变收益 tracker、`revenuePaid` / `deliverRevenue` 的归属、去重与交付策略，收益回调 SHALL NOT 补发 `ad_show`。

#### Scenario: 新增事件后的收益交付
- **WHEN** AdMob 插屏依次触发实际曝光和有效收益回调
- **THEN** `ad_impression` 与 `revenueListener` 各交付一次，金额和币种与平台回调一致

### Requirement: 展示失败素材来源

所有格式（开屏、插屏、激励、Banner、Native，含全屏 Native）与平台的有效 `ad_show_fail` 业务参数 SHALL 包含 `ad_source`。失败素材来源可确认且 `AdEvent.adSource` 非空白时 SHALL 使用原值；为 null 或空白时 SHALL 仅在 `analyticsParameters()` 中兜底为字面量 `"unknow"`，SHALL NOT 更改该拼写或强制填充原始属性。该字段 SHALL 表示失败素材来源，SHALL NOT 表示故障归因。没有实际素材的失败 SHALL 使用 `"unknow"`，SHALL NOT 从缓存候选猜测来源。

此契约 SHALL 保持其他字段及事件输出、非法事件返回空 Map 的门禁和已曝光后的终态不变；已曝光的展示 SHALL NOT 因本契约补发 `ad_show_fail`。`ad_load_fail` 业务输出 SHALL 完全不变且 SHALL NOT 增加 `ad_source`。

#### Scenario: 已确认素材展示失败
- **WHEN** 失败前已取得实际素材，且其来源为非空白的 `Pangle`
- **THEN** 有效 `ad_show_fail` 的 `ad_source` 为 `Pangle`，失败原因等其他字段保持原有规则

#### Scenario: 素材来源不可确认
- **WHEN** 有效 `ad_show_fail` 的原始 `adSource` 为 null、空字符串或纯空白
- **THEN** 业务参数包含 `ad_source=unknow`，原始 `adSource` 保持原值

#### Scenario: 没有实际素材或尚未选平台
- **WHEN** 无填充、等待超时、取消或未选平台导致失败，尚无实际失败素材
- **THEN** 有效 `ad_show_fail` 使用 `ad_source=unknow`，不从缓存候选取来源；未选平台时仍为 `ad_platform=unknown` 且无 `ad_unit_id`

#### Scenario: 展示身份非法
- **WHEN** 展示失败事件缺少合法业务场景或会话
- **THEN** `analyticsParameters()` 返回空 Map，不因来源兜底而变为有效事件

#### Scenario: TopOn 旧广告回调在新广告开始后迟到
- **WHEN** 广告 A 已结束，广告 B 成为当前活跃会话，随后收到 A 的点击、关闭、奖励、播放失败或收益回调
- **THEN** 仅凭当前活跃会话或回调被放行 SHALL NOT 将 A 的来源写入 B；来源写入 SHALL 要求非空 `showId` 已映射到对应的具体会话实例
- **AND** 缺失、空白或未登记的 ID SHALL NOT 更新失败来源，收益处理 SHALL NOT 隐式更新失败来源；B 此前无已确认来源且随后展示失败时，`ad_source` 为 `"unknow"`

#### Scenario: 加载失败携带原始来源
- **WHEN** `ad_load_fail` 的原始 `adSource` 有值、为 null 或空白
- **THEN** 业务参数均不含 `ad_source`，其余加载失败业务输出保持原有规则

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
