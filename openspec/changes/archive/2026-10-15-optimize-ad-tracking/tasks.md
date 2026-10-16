## 1. 批次 1：实际曝光事件 `ad_show`

- [x] 1.1 在 `AdEventTest` 先写失败用例：`ad_show` 含基础身份与 `ad_source`，Banner 含 `refresh_index`，不含收益键；再新增 `AdEventName.SHOW("ad_show")` 与业务参数分支，验证 `AdEventTest` 通过
- [x] 1.2 在 `AdShowSessionTest` 先写失败用例：AdMob 曝光无收益只发 `ad_show`；收益先到时两类事件各一条且共用会话；TopOn 开屏 `ad_show` 先于 `ad_impression` 且不带收益；重复曝光只发一条；`ad_show_fail` 之后的迟到曝光不发。再修改全屏收口点，验证用例通过
- [x] 1.3 在 `BannerDisplaySessionTest` 先写失败用例：AdMob 和 TopOn 曝光各发一条带 `refresh_index` 的 `ad_show`，重复回调和失败收口后不再发送。再修改 Banner 收口点，验证用例通过
- [x] 1.4 在 `NativeEventIdentityTest` 先写失败用例：AdMob 和 TopOn Native 曝光各发一条 `ad_show`，失败收口后不再发送。再修改 Native 收口点，验证用例通过
- [x] 1.5 开屏日志与 Native 日志为 `SHOW` 增加“已确认实际曝光”文案，验证 `AppOpenLogTest`、`NativeFlowLogTest` 通过
- [x] 1.6 更新断言完整事件序列的既有测试，只在实际曝光位置插入 `ad_show`；运行 `./gradlew testDebugUnitTest`，日志落文件，验证全部通过

## 2. 批次 1：文档与指标口径

- [x] 2.1 README 第 5 节：事件表加入 `ad_show`；按 design.md D5 写入指标口径表，含分子、分母、统计单位、适用格式与限制，并附三个验算样例；验证事件表与 `AdEventName` 逐项一致，指标表与 D5 一致，Banner 刷新、Native 重试、TopOn 无收益三个样例的计算结果与 D5 相同
- [x] 2.2 README 增加 `reason` 取值表（含 `reason_detail` 说明）和字段变更记录；变更记录写明 `AdEventName` 的源码级兼容影响，以及按事件名分发时需要为 `ad_show` 配置去向；验证与 `failureReason()`、`skipReasons` 的取值一致
- [x] 2.3 README 的日志检索说明改为：全屏按 `session_id`，Banner/Native 按 `slot_id`；验证与 `AdsModuleLogger` 实际输出的字段一致
- [x] 2.4 `docs/integration-guide.md` 的事件表及 `ad_paid` 段落改为现行事件，`docs/banner-integration.md` 补充 `ad_show`；验证在 `README.md`、`docs/`（不含 `banner-implementation-plan.md`）中搜索 `ad_load_request|ad_load_result|ad_paid|ad_reward_earned`，只剩“不再发送”这类说明

## 3. 批次 2：失败原因细分

- [x] 3.1 在 `AdEventTest` 先写失败用例：未识别原因码输出 `reason=ad_error` 及 `reason_detail`；`wait_timeout` 输出 `reason=timeout` 及 `reason_detail=wait_timeout`；`no_fill` 不输出 `reason_detail`；自然语言消息不输出；`errorCode=load_exception` 且原始原因为纯小写单词 `disconnected` 时，`reason=exception` 且不输出 `reason_detail`。再实现，验证用例通过

## 4. 批次 2：诊断日志

- [x] 4.1 先为契约外事件的判定写失败用例：空业务参数的业务事件产生警告文案，`BANNER_REFRESH` 与 Banner `DISMISS` 不产生；再接入 `AdsModuleLogger.event()`，验证用例通过
- [x] 4.2 先为 `AdShowSession.revenue()` 的非法金额和币种写失败用例，断言产生诊断；再在 AdMob 全屏 `paid` 与 TopOn 全屏 `revenuePaid` 的丢弃点接入同一诊断，验证用例通过，且不发送 `ad_impression`、不交付收益回调

## 5. 批次 3：AdMob 全屏自动加载事件

- [x] 5.1 先写失败用例：AdMob 全屏预加载在首个终态之后的成功回调和失败回调，各以新的 `request_id` 产生 `ad_load` 与终态，带 `load_trigger=sdk_auto`，不带 `latency_ms`；库存耗尽和过期代次不产生事件。再实现，验证用例通过
- [x] 5.2 `docs/integration-guide.md` 的加载语义段补充 AdMob 全屏的 `sdk_auto` 说明，验证与 5.1 的行为一致
- [x] 5.3 设备验证：AdMob 插屏配置 `bufferSize ≥ 2`，先记录初始填池的回调次数作为基线，再记录取用广告的时刻。通过条件：
  - 取用后至少实际观察到一次新的补货结果；
  - 每组 `ad_load` 与终态的 `request_id` 相同，不同组之间不同；
  - 业务参数带 `load_trigger=sdk_auto`，不带 `latency_ms`。

  取用后没有出现补货时记录为“未覆盖”，不算通过。结果写入本变更的 `verification.md`

## 6. 收尾

- [x] 6.1 运行发布门禁 `./gradlew testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease`，日志落文件，验证全部通过
- [x] 6.2 运行 `openspec validate optimize-ad-tracking --strict`，验证通过
- [ ] 6.3 发布时把 README 当前版本号、依赖示例与 `gradle.properties` 统一为实际发布的版本号，验证三处一致

## 正式发布前待办

2026-10-15 用户明确批准归档本变更，任务 6.3 延期到正式发布前处理。该任务保持未勾选，当前为 18 项完成、1 项批准延期；归档不代表版本对齐已完成。

当前 `gradle.properties` 为 `1.0.9`，README 当前版本号及依赖示例仍为 `1.0.6`。关闭条件：正式发布时按实际发布版本统一这三处，核对一致后再勾选任务 6.3。
