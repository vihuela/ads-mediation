# Verification Report

变更：`optimize-ad-tracking`。验证日期：2026-10-15。

## Summary

**实现与本变更规格一致，未发现实现层面的归档阻塞项。** 本次按完整性、正确性、设计一致性核对 7 条需求、27 个场景及 D1–D5。

| 规格一致性问题级别 | 数量 | 结论 |
|---|---|---|
| CRITICAL | 0 | 未发现缺失需求或已确认的行为偏差 |
| WARNING | 0 | 未发现需在本轮归档前处理的一致性问题 |
| SUGGESTION | 1 | 可补充自动加载监听器内更新策略的跨层回归用例 |

任务状态为 **18/19**。唯一未完成项 6.3 是用户明确批准留到正式发布时处理的版本对齐，不作为本轮实现缺失或归档阻塞。实际归档时需保留该发布待办，发布前仍须完成三处版本一致性核对。

## Completeness

| 需求 | 场景数 | 核对结果与主要证据 |
|---|---:|---|
| 实际曝光事件 | 6 | 全屏、Banner、两平台 Native 及全屏 Native 均沿实际曝光入口收口；重复及失败后迟到曝光不新增 SHOW。`AdShowSessionTest`、`BannerDisplaySessionTest`、`NativeEventIdentityTest` 覆盖；`AdEventTest` 核对身份、来源、Banner 序号及无收益键。 |
| 收益曝光口径保持不变 | 1 | AdMob 及 TopOn 插屏/激励仍由有效收益产生 IMPRESSION，TopOn 开屏/Native 保留展示回调的 IMPRESSION；曝光和收益分别去重。会话、Banner/Native 身份及 AdMob 支付边界用例支持。 |
| 失败原因细分 | 5 | 保留既有类别，先排除 exception，再按原码格式、长度和差异输出 reason_detail；自然语言和 disconnected 异常消息不进入该字段。`AdEventTest` 覆盖指定场景，来源路径经源码核对。 |
| 契约外事件与无效收益可诊断 | 3 | 日志开关和两类诊断事件排除正确；共享会话、AdMob 拒绝点及 TopOn 拒绝点均接入诊断。`AdsModuleLoggerTest`、`FullScreenRevenueDiagnosticTest` 支持；TopOn 整条 SDK 回调对象路径的覆盖边界见下文。 |
| AdMob 全屏自动加载事件覆盖 | 3 | 首个成功/失败后每个结果使用新 request_id，组内关联，自动组带 sdk_auto 且无耗时；耗尽通知和旧代次不造加载事件。`ProviderPreloadPolicyTest`、`ActualLoadContractTest` 及插屏设备记录支持。 |
| Banner 完整公共事件与事实边界 | 5 | 新展示各自收口并保留序号；加载成功、刷新、恢复可见及收益均不伪造 SHOW；既有失败、关闭、点击归属契约保留。Banner 展示、事件、回调隔离及 relay 测试支持。 |
| Banner 收益顺序、金额与迟到归属 | 4 | paid 先到、合法零收益、旧页面迟到收益仍按原展示交付；收益与 SHOW 独立。正式 TopOn unsupported 路径无新增曝光/收益出口。相关 Banner 测试及生产路径核对支持。 |

其他任务证据：

- README 与接入指南的事件表均覆盖现行全部 13 个事件；开屏和 Native 日志为 SHOW 使用“已确认实际曝光”。
- README 的指标段与 design D5 全文一致，包含 10 项指标、适用格式、限制及 3 个验算样例；事件集合核算结果符合 D5。
- 原因表覆盖 `failureReason()` 和 `skipReasons`；枚举穷举兼容影响及按事件名配置 `ad_show` 去向均已说明。
- 全屏按 session、Banner/Native 按 slot 检索的说明符合实际日志；文档注明开屏 `sid/req` 和 Native slot 标签的显示方式。
- 搜索 README 和 docs（排除历史 Banner 计划）后，旧事件名仅出现在“不再发送”的说明中。
- 任务 6.3 保持未勾选：`gradle.properties=1.0.9`，README 仍为 `1.0.6`，符合本次明确延期指令。

## Correctness

关键收口与参数核对：

- [全屏收口](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/AdEventDispatcher.kt:521)、[Banner 收口](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/BannerDisplaySession.kt:33)、[Native 收口](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/nativeads/NativeAdEvents.kt:189) 在交付 SHOW 前提交原有终态标志，避免重复及 SHOW 回调重入形成第二条曝光或失败。TopOn 原 IMPRESSION 保留在 SHOW 后，页面结束不会额外抑制该次已确认的原事件；回调异常仍由既有隔离机制处理。
- [业务参数](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/AdEvent.kt:137) 中 SHOW 与 CLICK 共用来源分支，Banner 另带正数 refresh_index；SHOW 不进入收益参数分支。失败原因的现有归类表未改写，新增细分字段受异常来源与格式约束。
- [自动加载会话选择](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/admob/AdMobProvider.kt:533) 在原会话终态后创建并保存新会话，依次发送请求和结果；回调入口保留 started、generation、canLoadAds 保护，库存耗尽不创建新请求。
- [收益诊断](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/AdsModuleLogger.kt:104) 统一受日志开关控制。[TopOn 会话选择](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/topon/TopOnAds.kt:1263) 对有 ID 的回调只使用已确认映射，未知 ID 不猜测当前展示；缺失 ID 才使用捕获会话。

## Coherence

| 设计决定 | 实现对应 | 结论 |
|---|---|---|
| D1 原收口点发 SHOW | 三个原曝光收口新增事件，未新增重复状态；TopOn 原 IMPRESSION 顺序保留 | 一致 |
| D2 原因来源与格式 | exception 先排除，固定正则限制本地代码，无新原因白名单，旧 reason 映射不变 | 一致 |
| D3 日志层与拒绝点诊断 | 契约外事件在日志层判断，无效收益在拒绝路径诊断；TopOn 私有方法承载可独立测试的身份选择，不改变公共接口 | 一致 |
| D4 自动加载新建会话 | 读取既有终态标志，复用现有加载会话与 sdkAutoLoad 参数，保持平台代次及策略保护 | 一致 |
| D5 指标口径锁定 | README 指标段逐字匹配，样例结果一致 | 一致 |

## Findings

### CRITICAL

无。

### WARNING

无规格一致性问题。现有构建告警与执行覆盖边界单独记录在下文。

### SUGGESTION

**S1：补充自动加载监听器更新策略的跨层回归用例。**

当宿主收到第二组及之后的 `ad_load` 时立即调用 `Ads.updatePolicy()`，目前的验证依赖源码时序判断；[自动加载测试](/Users/jiaoyun/od-fz/ads-mediation/src/test/java/com/cashcraft/ads/mediation/ProviderPreloadPolicyTest.kt:215) 主要使用事件收集监听器，[既有重入用例](/Users/jiaoyun/od-fz/ads-mediation/src/test/java/com/cashcraft/ads/mediation/internal/ActualLoadContractTest.kt:78) 针对单个初始会话。

当前 [策略通知](/Users/jiaoyun/od-fz/ads-mediation/src/main/java/com/cashcraft/ads/mediation/Ads.kt:154) 排入主队列，未发现普通策略更新会立即覆盖自动加载终态的证据。补测可防止未来时序调整引入回归；这不是已确认缺陷，也不阻塞本轮结论。本次未为可选建议新增测试或改动实现。

## Validation Evidence

- 本轮重新执行 `openspec validate optimize-ad-tracking --strict`：通过。此命令证明产物结构有效，行为一致性由上述源码与测试核对支持。
- 本轮复核本会话上一轮的最终单测原始 XML：**583 项，0 failures、0 errors、0 skipped**。核对的修改源码/测试文件均早于该轮最终 XML，且本轮未修改源码或测试，复用该有效结果。
- 完整门禁 [final-release-gate.log](/Users/jiaoyun/od-fz/ads-mediation/build/reports/optimize-ad-tracking/final-release-gate.log)：`testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease` 成功，耗时 1 分 13 秒。Lint 库模块 0 errors / 8 warnings，smoke App 0 errors / 71 warnings。
- 已有 [设备原始记录](/Users/jiaoyun/od-fz/ads-mediation/build/reports/optimize-ad-tracking/device-refill.txt) 和 [Instrumentation 结果](/Users/jiaoyun/od-fz/ads-mediation/build/reports/optimize-ad-tracking/device-instrumentation.log) 本轮重新解析：取用前 2 次失败、2 次填池成功，实际曝光后新增第 5 组成功请求；取用到观测约 11.185 秒，自动组字段正确。结果为 `OK (1 test)`，符合任务 5.3。
- 红灯日志中指定功能失败仍可核对：批次 1 为 79 项中 6 项失败，批次 2 为 26 项中 3 项失败，批次 3 为 11 项中 1 项失败，TopOn 身份回归为 2 项中 1 项失败。夹具构造失败不计为功能红灯。
- 本轮记录 35 个主要输入文件的 [核对摘要](/Users/jiaoyun/od-fz/ads-mediation/build/reports/optimize-ad-tracking/verify-inputs.json)，用于确认这些文件在审查期间未变化。摘要不冒充上一轮构建时生成的校验值。

## Evidence Boundary

- 本轮没有重复运行 Gradle 或设备测试，复用了当前代码对应的本会话有效运行证据。
- 真实 SDK 网络设备验证覆盖 AdMob 插屏；其他平台和格式为源码、单测及调用链证据。
- SHOW 回调中同步结束页面、重复曝光或触发失败的判断来自源码推演；异常隔离矩阵已涵盖 SHOW，不能将其当作这些同步重入组合的直接运行证据。
- TopOn SDK 回调对象的取金额、转换与拒绝分支整体为源码验证；共享会话与 TopOn 私有诊断身份选择有单测，不能据此声称该 SDK 对象全链路已执行。
- R8 构建仍有 Pangle `NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE` 两条缺类警告。构建成功不能证明这些第三方运行路径全部正常；本轮未扩大范围处理依赖。

## Execution

主会话负责业务参数、文档、任务完整性和原始运行证据；两名原生子代理分别只读复核曝光/收益链路、自动加载/诊断链路。两者实际运行配置均核对为 `provider=openai`、`model=gpt-6.1-sol`、`effort=medium`；第二名配置由主会话读取其本次 session 元数据确认。主会话统一核对并形成结论。

本次仅新增本验证报告及临时核对输入摘要，源码、测试、任务勾选和版本均保持原状；未执行提交、发布或归档。
