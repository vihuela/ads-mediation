# Verification

验证日期：2026-10-15。变更：`optimize-ad-tracking`。

## Source and unit tests

- `ad_show` 复用全屏、Banner、Native 的既有曝光/失败收口，收益回调及去重保持原有契约；公共枚举末尾新增 `SHOW`，保留已有成员顺序。
- 批次 1 先运行新增用例：79 项中 6 项按预期失败；实现后将既有完整序列断言仅在实际曝光处插入 `SHOW`。Banner 回调抛异常矩阵同时覆盖 `SHOW`。
- 批次 2 先运行新增用例：26 项中 3 项按预期失败，分别是细分原因、契约外警告、无效全屏收益诊断；实现后定向用例通过。
- 批次 3 的有效红灯记录为 `batch3-red.log`：11 项中 1 项失败，终态后的 SDK 回调未产生新加载事件。测试夹具构造及控制器初始化问题已先排除。实现后覆盖首个成功/失败终态、后续成功/失败、库存耗尽和旧代次忽略。
- 实现阶段全量 `./gradlew testDebugUnitTest`：581 项，0 失败、0 错误、0 跳过。补充适配层收益诊断与身份用例后的最终全量结果为 583 项，0 失败、0 错误、0 跳过。
- 测试夹具使用 Google 的公开 [ResponseInfo 构造器](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/common/ResponseInfo)。
- AdMob 三种全屏格式额外直接调用真实适配层的支付处理边界，覆盖负金额、空/空白币种，断言有警告且无业务曝光/收益交付；之后的合法零收益仍成功交付。使用公开的 [AdValue 构造器](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/common/AdValue)。
- TopOn 的 SDK 广告信息对象不能由当前夹具合理构造，最初的夹具失败不作为功能红灯。将诊断中的会话选择提取为私有方法后，新增用例实际复现“未知展示 ID 被记到当前会话”的错误（2 项定向测试中 1 项失败，`revenue-identity-red.log`），再修正：有 ID 时只使用已确认的映射；缺失 ID 时才使用捕获会话。用例覆盖已知、未知、缺失身份及无效金额的诊断表示。SDK 取金额/转换的调用分支由源码核对，未将其宣称为已用真实 SDK 回调对象执行。

## Documentation

- README 的 13 个枚举/事件名与 `AdEventName` 一一对应；接入指南事件名集合相同。
- README 的指标段与 design D5 逐字一致，包含 10 项指标及全部 3 个验算样例。
- 使用独立的事件集合核算：Banner 展示率 1/1、每次机会展示数 3、点击率 1/3；Native 先失败再成功的机会失败率 0/1；TopOn 无金额曝光的收益覆盖率 0/1，且不进入 eCPM 分母。
- README 覆盖 `failureReason()` 和 `skipReasons` 的全部 39 个类别/原码，包含异常路径排除及 `reason_detail` 格式/长度边界说明。
- 日志按全屏 `session_id`（开屏调试输出为 `sid`）、Banner/Native 的 `slot_id` 关联；Native 人类可读日志使用 slot 标签。
- 在 README、docs 中排除历史 `banner-implementation-plan.md` 搜索旧事件名，只剩明确的“不再发送”说明。

## Device validation

任务 5.3：通过。此项为 Android 模拟器上的真实 AdMob SDK 请求与曝光证据。

- 设备：`emulator-5582`，Android 13 / API 33，arm64。测试 App：`com.cashcraft.ads.mediation.smoke.native`。
- 使用官方 AdMob 插屏测试广告位 `ca-app-pub-3940256099942544/1033173712`，`AdMobPreloadConfig` 的默认插屏 `bufferSize=2`；测试同时断言各加载记录的 `bufferSize >= 2`。
- 构建：`./gradlew :r8-smoke-app:assembleDebug :r8-smoke-app:assembleDebugAndroidTest -PnativeSmoke=true`。
- 用例：`AdMobAutoLoadTest#testRefillAfterConsumptionUsesAutomaticRequestIdentities`，Instrumentation 结果 `OK (1 test)`，耗时 63.223 秒。
- 执行：`adb -s emulator-5582 shell am instrument -w -e class com.cashcraft.ads.mediation.smoke.AdMobAutoLoadTest#testRefillAfterConsumptionUsesAutomaticRequestIdentities com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner`。
- 初始设备的 Wi-Fi/移动数据均关闭，无默认网络。临时启用 Wi-Fi 后完成填池；结束后停止测试 App，恢复 Wi-Fi 关闭，移动数据保持原状态。
- 取用前基线：4 次终态回调，其中前 2 次是断网失败，后 2 次填池成功；成功数达到 2 后再稳定等待 3 秒才取用。
- 取用时点：`elapsedRealtime=84613229 ms`。随后观察到实际 `ad_show` 和零收益的 `ad_impression`，二者会话均为 `f70a919d-b2ea-4219-8f74-2618a6717c4e`；`ad_show` 不含收益键。
- 补货成功确认：`elapsedRealtime=84624414 ms`，取用后约 11.185 秒；新增一组 `ad_load` / `ad_loaded`，请求 ID 为 `b7e832cc-b5c8-4c16-85db-27c9b7d843e1`。
- 全程 5 组加载请求各有独立 `request_id`，组内加载与终态相同。首组保留可观察耗时；后 4 组（含初始重试、第二次填池和取用后补货）均带 `load_trigger=sdk_auto`，均无 `latency_ms`。初始填池与取用后补货已按取用时点分开。
- 原始完整事件记录：[device-refill.txt](../../../../build/reports/optimize-ad-tracking/device-refill.txt)；[Instrumentation 结果](../../../../build/reports/optimize-ad-tracking/device-instrumentation.log)；[Logcat 尾部记录](../../../../build/reports/optimize-ad-tracking/device-logcat.log)。Logcat 环形缓冲仅保留尾部，完整事件以测试导出的前者为准。
- 导出后使用独立脚本再次解析 JSON 业务参数，核对 5 组请求、各自动组参数、曝光/收益会话关联及合法零收益，全部符合。

| 阶段 | request_id | 终态 | sdk_auto |
|---|---|---|---|
| 初始请求 | `c47c1e79-7ce7-4e65-b843-d16fe6810fe2` | 断网失败，338 ms | 否 |
| 自动重试 | `058c9a52-d47d-4972-a2a3-1d56f520d642` | 断网失败 | 是 |
| 初始填池第 1 条 | `25531b6d-ccab-499a-8663-33855432a6fe` | 成功 | 是 |
| 初始填池第 2 条 | `69ed86fe-8e42-4d09-8861-df5cde38639f` | 成功 | 是 |
| 取用后的补货 | `b7e832cc-b5c8-4c16-85db-27c9b7d843e1` | 成功 | 是 |

## Release gate

- 最终源码的 `./gradlew testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease`：通过，耗时 1 分 13 秒；583 项单测全部通过，Lint 与 R8 Release 构建通过。[完整门禁日志](../../../../build/reports/optimize-ad-tracking/final-release-gate.log)。第一轮同一门禁通过记录（581 项、1 分 39 秒）单独保留。
- Lint 报告：库模块 0 errors / 8 warnings，smoke App 0 errors / 71 warnings；本次新增或修改的生产文件、设备夹具未出现在警告定位中。未扩大范围清理其他警告。
- 两轮构建均保留 R8 的两条 Pangle 缺类警告：`NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE`。本次未改广告依赖，也未将构建成功视为这些第三方路径的设备运行证明。
- `openspec validate optimize-ad-tracking --strict`：通过。
- 本次不执行提交、推送、发布或归档。
- 用户已明确选择“留到正式发布时再更新”版本；任务 6.3 保持未完成，`gradle.properties=1.0.9` 与 README 的现有 `1.0.6` 均不改写。

## Evidence boundary

实际补货的设备验收覆盖 AdMob 插屏；开屏、激励、Banner、Native、TopOn 的新增事件及边界由源码与单元测试验证，本次没有逐一进行真实网络设备复测。R8 Release 构建仅证明发布产物可以生成，不等同于该产物的全部设备行为已验收。

## Execution

文档、部分既有序列断言及 AdMob 支付边界用例由同一原生子代理并行完成，其自身运行元数据为 `provider=openai`、`model=gpt-6.1-sol`、`effort=medium`。主会话核对实际差异并统一执行门禁；TopOn 夹具受限后，由主会话提取私有诊断方法并完成身份回归用例与修正。未切换子代理模型或新增依赖。
