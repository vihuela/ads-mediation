## Context

动机与范围见 [proposal.md](proposal.md)，行为契约见 [banner-display spec](specs/banner-display/spec.md)。本设计承接[现有 Banner 方案](../../../../docs/banner-implementation-plan.md)，不重做历史工程调查，也不把其中的拟议 API 当成现有功能。

当前代码给出的约束：

| 当前依据 | 设计影响 |
| --- | --- |
| [Ads.kt](../../../../src/main/java/com/cashcraft/ads/mediation/Ads.kt) 的 `startProviderInitialization` 复用 UMP；`finishBiddingProviderInitialization` 等两端完成后按 OR 汇总 | 保持整体初始化语义，AdMob Banner 另读自身配置及结果；TopOn 选择先返回 unsupported，不以整体 OR 结果代替 AdMob 资格判断 |
| [AdEventDispatcher.kt](../../../../src/main/java/com/cashcraft/ads/mediation/internal/AdEventDispatcher.kt) 的 `beginLoad` 固定 preload position 与 bufferSize，`begin` 创建全屏一次性展示会话 | 复用事件字段、计数、日志及异常隔离，增加最小 Banner 入口；不能直接用 preload 口径或一份全屏会话承载整个 Banner |
| [AdRevenue.kt](../../../../src/main/java/com/cashcraft/ads/mediation/AdRevenue.kt) 要求非空 sessionId，允许合法零金额，保留平台原始信息 | 已确认展示使用现有 payload；身份未知不能靠共享 unknown session、补零或 eCPM 通过校验 |
| [TopOnAds.kt](../../../../src/main/java/com/cashcraft/ads/mediation/internal/topon/TopOnAds.kt) 的现有收益链路关联全屏活跃会话 | Banner 收益需独立捕获刷新后的展示身份，不能接入全屏活跃会话表来猜归属 |
| [settings.gradle.kts](../../../../settings.gradle.kts)、[构建配置](../../../../build.gradle.kts) 当前只有核心库和 R8 smoke 宿主 | 核心维持 View；Compose 为新增可选薄模块，不能假定已有 Compose 插件、版本或发布坐标 |

广告依赖锁定为 GMA Next-Gen 1.2.1、TopOn 6.6.22.3、GMA Next-Gen adapter 1.2.1.1.0、UMP 4.0.0；构建基线为 compileSdk 36、minSdk 26、JVM 17。已取得的最小探针和设备结果及其局限见 [verification.md](verification.md)，不能外推为正式组件验收。

**2026-09-29 范围修订：首版正式 View／Compose 仅接 AdMob。** TopOn 探针继续保留；公开 `BannerRequest` 选择 TopOn 时在 TopOn Banner SDK 加载前返回明确 unsupported，不回退 AdMob。TopOn 正式暂停、尺寸、刷新及收益映射留待反例解决并另行验证，不能把探针成功当作本轮交付。

## Goals / Non-Goals

**Goals:**

- 将页面资格、请求代次、平台实例持有和正常 View 测量集中在一个自有 View，Compose 仅同步参数并负责最终释放。
- 以已验证的公开能力完成 AdMob 暂停与事件映射；TopOn 的公开探针、反例及后续门槛独立保留，测试围绕本轮可观察契约和回调竞态。
- 保留必要身份与不可变回调快照，使旧 UI 隔离与已发生收益交付可以分别判断。

**Non-Goals:**

- 不新增通用广告控制器框架、资源登记表、重复生命周期控制器或可配置的多套暂停策略。
- 不在 Banner 内接管路由、重定义全屏规格，或顺带修复其他全屏变更尚未完成的事项。
- 不为规划完整而假定未经验证的平台能力；不将 TopOn 探针提升为正式组件或完成标记。

## Decisions

### 1. 先建立锁定版本的能力门槛

实施以已有官方测试配置与 [verification.md](verification.md) 的最小 View 路径为起点；以下 AdMob 门槛须在正式路径继续验证。TopOn 记录继续保留实际解析版本、公开 API、测试广告位刷新配置、设备、来源、回调时身份和结论，供后续正式接入使用。

| 必验能力 | 决策结果与未通过处理 |
| --- | --- |
| View 创建、合法尺寸、参数 dp／px、实例何时可取得 | AdMob 确定正式创建与释放路径；TopOn 自适应及标准尺寸反例保留为后续门槛，容器仍须自适应合法高度 |
| 首次展示和连续两次刷新、曝光与收益的顺序和身份 | 固定各平台事件映射及去重键；无法可靠关联时保留原始诊断，先明确降级表达，再冻结接口和验收范围 |
| 前后台、失焦、隐藏、脱离及恢复 | 证明可靠保留才选保留；否则该端选幂等释放重建，不同时实现两套模式 |
| 开启刷新后的首次断网／无填充恢复与关闭刷新对照 | 区分本层请求、平台新请求与旧在途结果；未恢复时记录限制并明确降级或修订契约，不用循环重载掩盖 |
| 初始占位、刷新高度、有效宽度变化 | 占位不能阻断首次请求或恢复；仅高度改变不请求，测量不能形成循环 |
| Banner 本体关闭与落地内容关闭 | 仅前者结束本 slot；不能按回调名称推断，不要求所有来源有关闭按钮 |

这些门槛不因探针观察而自动完成。TopOn 锁定测试源后仍出现请求尺寸偏差、刷新 paid/show 复用 showId，后台刷新开关与下层配置无法核验；详见 [verification.md](verification.md)。因此本轮不冻结 TopOn 正式尺寸、暂停及收益映射。可独立进行 AdMob 正式契约和受控回调实现；不按最新网页或旧工程猜测锁定版本能力，也不为验证而反编译、猜测私有字段或隐含升级广告 SDK。

### 2. 一个自有 View 加一个可选 Compose 包装

拟议公开入口沿用方案：`BannerRequest(platform, adUnitId, position, size)`、`AdsBannerView(activity, lifecycleOwner, request, active)`、`setActive(Boolean)`、`destroy()`，以及可选模块的 `AdsBanner(request, modifier, lifecycleOwner, active, visible, onState)`。公开尺寸为 `Standard320x50` 与 `AnchoredAdaptive`，首版仅 AdMob 正式使用；TopOn 选择返回 unsupported。状态只用于 UI，不替代真实曝光或收益。

`AdsBannerView` 仅持有当前平台实例和自己的广告子 View。Activity、owner、request 在构造时固定；实际改变时由宿主或 Compose 释放旧 View 后创建新 View，不提供重新绑定句柄。同一 View 可在有效尺寸改变时更换内部平台实例。生命周期观察、附着及可见性变化统一进入一次主线程资格评估，状态更新与平台资源管理串行执行。

正式平台操作仅封装 AdMob 实际需要的创建、加载、暂停或释放、尺寸与回调；TopOn 只保留独立探针及公开 unsupported 判定，不建正式加载分支。沿用现有平台目录，不预建全广告格式注册机。宿主正常添加自有 View 即可。

### 3. 复用初始化，补所选平台的观察入口

核心提供内部的配置校验、许可快照、所选平台状态读取及必要的结果通知，基于已有 `Ads` 初始化结果和 provider 状态，避免第二条初始化流程。创建组件先注册可解除的观察，再同步回读当前状态；恢复时重新读取资格，避免读取与订阅之间的间隙错过已完成结果。

整体 Bidding 状态仍服务原调用方；AdMob Banner 在 AdMob 明确失败时失败、仍初始化时等待、成功时继续检查其他资格，不依赖 TopOn 的 OR 汇总结果。TopOn Banner 公开请求先返回 unsupported，即使 TopOn 初始化成功也不进入 SDK 加载。组件结束时移除本层等待和监听。UMP 仅沿用现有初始化及 AdMob 本层请求前检查，不增加运行期隐私变化控制链。

替代方案是只等待 `Ads.state`；由于 OR 汇总会掩盖所选平台失败，不能满足契约。

### 4. 用页面资格和请求代次处理生命周期

主线程评估的必要状态仅包括：是否最终销毁、active、宿主实际可见性及生命周期、当前 slot、有效请求尺寸、当前代次、平台实例、该代次加载结果，以及平台本体关闭后的本 slot 终止标志。沿用简单字段与幂等入口，不新增独立通用状态机或高度状态流。

| 触发 | 资源和身份处理 |
| --- | --- |
| 初次绑定、等待初始化或宽度 | 先应用 active／visible；条件未齐不请求；未加载子 View 的高度不是资格前提 |
| 首次全部就绪 | 为当前有效代次调用一次加载；重复通知只重新评估，不重置失败状态 |
| active=false | 结束当前 slot、使旧代次失效并释放；再次 active=true 才建立新 slot |
| 临时暂停，可保留路径 | 隐藏或公开 API 暂停真实广告；slot、实例及代次保留；在途结果可记录但不挂载 |
| 临时暂停，必要释放路径 | 一次释放使旧代次失效，slot 保留；条件全部恢复后新代次加载一次 |
| 有效请求尺寸改变 | 同一 View 保留 slot，旧代次失效并更换内部实例；仅高度重测不改变代次 |
| destroy、owner 销毁、Compose 最终退出 | 永久结束，移除监听和业务引用；重复调用无副作用 |
| 已确认 Banner 本体关闭 | 上报并释放，保留本 slot 已关闭事实；测量或临时恢复不能重新创建广告 |

平台对象创建后立即保存；如果只能异步交付，先核对身份，有效则接管，过期则按公开接口释放。回调先快照平台身份，再回主线程；任何业务通知返回后，挂载前仍复核资格，覆盖通知中重入销毁。UI 过期检查不能用于一概丢弃旧收益。

页面独立持有是默认示例；公共底部区域是宿主可选布局，不建立跨页面缓存。普通完整页面导航结束归属，Dialog／落地页及预测返回未提交不直接当成永久离开。同窗口遮罩由宿主明确 visible=false；View 宿主可隐藏槽位或父容器。恢复检查宿主槽位而非 SDK 自己隐藏的子 View，避免循环等待。

不采用“所有 ON_PAUSE 都结束 slot”：它会使前后台和 Dialog 恢复无条件重新请求，也混淆业务归属。

### 5. 正常测量负责高度，平台负责刷新

View 使用正常测量和 `WRAP_CONTENT`，Compose 不固定高度。尺寸计算从实际内容宽度出发，仅扣宿主 padding；安全区由宿主处理一次。尺寸输入规范化后只在有效请求尺寸改变时重建；合法广告高度或刷新高度仅触发布局。固定 320 × 50 dp 放不下时明确失败，不裁切或缩放。

AdMob 初次加载先以合法尺寸占位；无法预知高度的路径必须先验证占位及首次失败恢复。规划基线保留首次无填充后的合法占位，不引入自动收起空槽的产品选择。TopOn 自适应请求及标准尺寸偏差留待后续验证，不能据此限制容器自然高度。

首版直连由 AdMob 刷新；未来 TopOn 接入须由 TopOn 刷新并核对下层关闭重复刷新。后台配置由接入方核验记录，SDK 不假设能够读取或修改开关。本层不增加刷新定时器、网络监听或 `retry()`，也不靠重组重新请求；有效旧广告在刷新失败时保留。

固定 50 dp 和本层定时刷新更容易编码，但分别违背已确认的合法高度要求和单一刷新调度责任，不采用。

### 6. Banner 身份独立，复用公共事件与收益出口

扩展 `AdFormat.BANNER`、`AdEventName` 的刷新事件及 `AdEvent.slotId` 可选字段，序列化使用 `slot_id`。扩展现有 dispatcher 的最小 Banner 分发入口，复用日志、position 后缀、计数及异常隔离；本层加载保持一次 request 对应最多一个 result。现有 `beginLoad` 的全屏默认参数和输出保持原样，Banner 增加真实业务 position、slot 关联及无需缓存数量的表达，不伪装为 preload。

| 身份 | 生命周期和用途 |
| --- | --- |
| slot_id | 一次 active 有效周期；进入事件一次，临时暂停／尺寸变化／刷新保持；停用或新 View 后新建 |
| request_id | 本层实际加载一次；平台内部刷新没有本层请求则不新建；不能可靠关联的事件不强行填入上一 request |
| session_id | 可识别的一次广告展示；paid 可以先到，因此按可靠平台身份准备会话而非依赖 impression 到达才建；slot 进入事件可用 slot ID 作 session |
| response ID／show ID | 回调入口取得的实际平台身份，用于展示映射和独立事件去重；不是“平台对象引用” |

slot 进入使用既有按平台、格式的 position 计数口径；加载另用加载计数。一次 slot 内可有多次展示，报表以 session 区分，不能用全屏的一次终态等式校正 Banner 数量。

| 公共事件 | 映射约束 |
| --- | --- |
| ad_position | slot 进入一次；重组、恢复与刷新不重复 |
| ad_load_request／ad_load_result | 本层加载与一次终态；失败后平台重试成功不补第二个终态 |
| ad_banner_refresh | 已确认的刷新结果，成功／失败分开；未分类的内部回调仅诊断 |
| ad_impression | 平台确认的真实曝光；按展示身份去重，连续刷新各有新展示 |
| ad_click | 实际点击归属实际广告，不读取新页面补归属 |
| ad_paid 与全局 revenueListener | 同一真实收益的两个出口分别异常隔离；同一收益去重不使一个出口抛异常阻止另一个 |
| ad_show_fail／ad_close | 仅确知的展示失败／Banner 本体关闭；导航清理或落地内容关闭不冒充 |

收益监听在平台允许的最早时点注册，先于可能触发展示的外部通知及首次可见；平台信息在回调入口捕获为快照。AdMob 加载前先将自有空 AdView 以 INVISIBLE 挂载，给空 View 设置公开 AdSize 对应的最小宽高，dp 转 px 向上取整以覆盖分数像素；首次布局后投递加载并再次复核代次和资格；若创建发生在全局布局回调中，另投递一次仍属当前代次的 requestLayout，保证首轮子布局实际执行。成功回调安装监听后才允许可见，可见性变化时请求一次重新布局。对于只重新测量而跳过未变外层摆放的宿主，测量后仅在仍有原生布局请求且外层尺寸未变时补齐自身布局；不因此请求新广告。不可见预挂载顺序经用户批准，最终组合实现已在锁定版本的 Release/R8 横屏首载对照中验证。收益数值复用现有校验和金额转换，合法零值正常输出，缺失或非法金额只记录诊断。

UI 回调检查当前 View 和代次；收益只检查原展示可确认身份及事件是否已交付。迟到收益使用不包含 Activity、View 或页面回调的必要展示元数据，经全局出口交付。只保存有限的展示映射及去重信息，平台方法允许时由监听捕获对应展示快照；具体身份键和所需保留边界在能力验证中确定，不建设跨页面历史回调队列。不得因映射不存在就改用“最新展示”。

当前 `AdRevenuePayload` 不接受未知 session。身份不足的回调保留平台实际信息用于诊断，不创建假的正常 payload，也不宣称完整收益验收通过。若阶段 1 证明确有无法关联的交付路径，先明确未知结果的表达和降级范围并同步本变更，再冻结公共接口；不擅自新增一套收益模型。

不能给每个 Banner 复用一份 `AdShowSession`，否则丢失刷新曝光；未来 TopOn 正式接入也不能使用全屏活跃会话 fallback，把旧收益归给新广告。

### 7. Compose 只负责身份、参数与退出组合

可选 Compose 模块通过 `AndroidView.factory` 创建 View，按 Activity、owner 与 request 值维持身份；用 `update` 同步 active／visible，首次值在请求前应用。visible=false 映射宿主槽位 `INVISIBLE` 以保留合法占位，不改写 active。`rememberUpdatedState` 读取最新回调，`onRelease` 调用幂等 destroy，不再重复订阅核心生命周期，不开启跨页面 `onReset` View 复用。

临时隐藏时组件仍留在 Composition；`if (visible)` 移除意味着最终释放，alpha=0 或透明遮罩不能当作暂停协议。Preview 用 `LocalInspectionMode` 绘制占位，不初始化平台。

普通 Navigation 示例显式传页面 entry owner 和完整页面归属；Dialog 目的地示例保持底层归属，同窗口遮罩另传 visible。核心不依赖 `NavController`，示例分别覆盖页面独立持有和可选公共底部区域，不把 Tab 下标或路由字符串作为广告身份。

模块沿用核心 minSdk／JVM 基线，按当前 Kotlin 与构建方式验证 Compose 插件及依赖的兼容组合，发布单独坐标并依赖核心。版本选择是实施验证项，不能在未编译前把坐标写为已可用。替代方案是仅提供示例或将 Compose 加入核心依赖；前者不满足正式组件交付，后者给普通 View 宿主增加依赖，均不采用。

### 8. 全屏兼容与有针对性的验证

检查 `AdFormat` 相关配置映射、就绪查询、预加载、竞价和展示分支。全屏专用入口对 BANNER 使用各入口既定失败表达：布尔查询为不就绪，能报告失败的入口给出不支持原因，绝不 fallback 到全屏 ID。Banner 不进入全屏等待、缓存与展示锁；已有全屏会话的终态保护保持不变。

受控回调测试复用现有 JUnit 环境，以公开行为、平台调用次数、事件序列及资源清理结果断言，不为每个私有字段建测试。重点覆盖资格与请求阶段交错、同 ID 多实例、创建后异常及加载中销毁、旧回调、重复/乱序事件、收益监听顺序、合法零金额和宿主异常。真实平台验证再覆盖暂停、首次失败恢复、连续刷新、响应身份及各来源。

沿用 [CI](../../../../.github/workflows/ci.yml) 的核心验证基线：`./gradlew testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease publishToMavenLocal`；按实际变更增加 Debug 构建和 Compose 模块的相关任务。让 AdMob Banner、TopOn unsupported 及 Compose 正式入口在压缩宿主中可达，避免只有编译引用却被 R8 删除。构建、受控回调、设备行为、视觉与报表分开记录，检查具体矩阵见现有方案第 13 节。

## Risks / Trade-offs

- [锁定版本与官方最新指南不同] → 先解析并编译公开 API，记录实际回调；能力未证实的路径保留验证门槛，不混用 legacy GMA 或 AT／TU 类型。
- [AdMob 不能可靠暂停] → 采用释放重建并记录恢复等待及请求变化；未来 TopOn 暂停路径仍须独立验证。
- [首次失败不能靠平台刷新恢复] → 保留合法占位并独立验收，记录限制及降级；本层不偷偷增加无限重试。
- [刷新身份不足、迟到收益映射丢失] → 用实际响应快照和有限元数据，不能确认则诊断并保留未通过状态，不归给最新展示；收益验收是平台交付门槛。
- [Compose 尚无构建基础] → 可选薄模块与现有核心分开发布，先证明插件、依赖及 R8 宿主可用；不升级无关广告依赖。
- [新增枚举或数据类字段影响宿主源码／二进制兼容] → 编译调用样例、核对实际签名与事件解析，发布说明要求适用宿主重新编译；不能仅因字段可选就宣称二进制兼容。
- [原全屏变更仍有未验收项] → 仅验证本次格式扩展和共享出口的回归，分别保留原任务状态，不将原遗留事项算作 Banner 完成证据。

## Migration Plan

1. 沿用当前依赖及探针证据，完成 AdMob 正式能力表和事件映射；记录 TopOn 未通过项，依赖其结论的未来路径继续保持门槛。
2. 实现核心 View、AdMob 资格与接入、TopOn 公开 unsupported，再接通 AdMob Banner 事件及收益；运行相关测试并检查全屏格式分支。
3. 接入可选 Compose 模块与测试宿主，验证 AdMob 导航、布局和压缩后的可达入口；TopOn 探针与来源反例独立保存。
4. 更新 README、接入示例、事件映射、能力限制、兼容说明及原方案状态。使用 `tasks.md` 记录实际完成情况，验证失败或缺环境的项保持未勾选。
5. 宿主按真实发布坐标选择 View 或 Compose 接入，核对测试广告、刷新后台配置和布局归属；远程发布遵循单独授权，本计划不执行发布。回滚时宿主先移除 Banner 接入及 Compose 依赖，再回退兼容的核心版本，不修改广告后台配置或旧全屏行为来模拟回滚。

## Open Questions

以下只影响接入或发布细节，不阻塞既定核心契约和任务拆分：Compose 模块最终名称／坐标、经兼容验证后的最低 Compose 版本，以及具体业务宿主选择页面持有还是公共底部布局。

规划当前保留首次失败合法占位及按平台能力暂停的基线。若产品后续选择收起无填充槽位或所有临时暂停统一释放，需要同步调整规格与恢复验收，不能以本节将其默认为已接受。平台能力验证中的失败则按决策 1 的门槛处理，不作为可无限延期的开放问题。
