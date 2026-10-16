## Why

`ad_impression` 在 AdMob 全部格式与 TopOn 插屏、激励上由收益回调触发，没有有效收益时不发送；而全屏 `ad_close` 以实际曝光为前提。展示漏斗因此无法闭合，展示率、点击率、关闭率的分子分母口径不一致。同时接入文档和 Banner 规格仍列出已删除的事件，未识别的本地失败原因统一归为 `ad_error`，被契约抑制的事件与被丢弃的无效收益在日志中不可见，AdMob 全屏自动补货不产生加载事件，排查和跨平台对比缺少依据。

## What Changes

分三批交付，均只做新增或文档修正，不改变已有事件的触发时机和字段含义。

批次 1（漏斗口径与文档）：
- 新增业务事件 `ad_show`：平台实际曝光回调时发送，覆盖全屏、Banner、Native，每次展示最多一条，不携带收益字段。
- `ad_impression` 的触发时机、字段和去重保持不变，继续承载展示级收益。
- **BREAKING（仅源码级）**：公共枚举 `AdEventName` 新增 `SHOW`。宿主若对事件名做无 `else` 的穷举 `when`，需要补分支；按事件名分发上报平台的业务方需要为 `ad_show` 配置去向。
- README、`docs/integration-guide.md` 与 Banner 规格的事件表同 `AdEventName` 对齐，删除 `ad_load_request`、`ad_load_result`、`ad_paid`、`ad_reward_earned`；README 增加指标口径、`reason` 取值表、字段变更记录和日志检索说明。

批次 2（排查可见性）：
- `ad_load_fail`、`ad_show_fail` 新增可选 `reason_detail`：当 SDK 内部原因码被归入更宽的 `reason` 类别时保留原码；不含平台错误文本。`reason` 取值不变。
- 开启日志时，业务事件因不满足契约被抑制、平台收益因金额或币种无效被丢弃，均输出诊断日志。

批次 3（加载口径）：
- AdMob 开屏、插屏、激励的持续预加载在首个结果之后的每次自动补货或重试回调，补报 `ad_load` 与对应终态并标记 `load_trigger=sdk_auto`，与 AdMob Native 现有做法一致。

不在本变更内：渲染与可见性事件；准入前失败的事件化；Banner/Native 逐广告标识；竞价未知价 `"unknow"` 的类型调整；版本字段。这些需要另行确认。SDK 继续只通过现有事件回调交付事件，上报到哪些平台由业务方按事件名决定。

## Capabilities

### New Capabilities
- `ad-analytics-events`：跨格式的业务埋点事件契约，包括实际曝光事件、失败原因细分、契约外事件与无效收益的诊断、AdMob 全屏自动加载的事件覆盖。

### Modified Capabilities
- `banner-display`：公共事件列表改为现行事件名，收益出口描述从 `ad_paid` 改为 `ad_impression` 与 `revenueListener`，Banner 每次实际曝光发送 `ad_show`。

## Impact

- 代码：`AdEvent.kt`（事件枚举与业务参数）、`AdEventDispatcher.kt`（全屏展示会话与加载会话）、`BannerDisplaySession.kt`、`NativeAdEvents.kt`、`AdsModuleLogger.kt`、`AppOpenLog.kt`、`AdMobProvider.kt`、`TopOnAds.kt`。
- 测试：`AdEventTest`、`AdShowSessionTest`、`BannerDisplaySessionTest`、`NativeEventIdentityTest`、`ActualLoadContractTest` 等；断言完整事件序列的既有用例需要纳入 `ad_show`。
- 文档：`README.md`、`docs/integration-guide.md`、`docs/banner-integration.md`；`docs/banner-implementation-plan.md` 是历史计划，不修改。
- 宿主：每次展示多一条 `ad_show`；不需要修改回调接入方式。
