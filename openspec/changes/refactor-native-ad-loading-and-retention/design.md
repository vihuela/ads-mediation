## Context

动机与范围见 [proposal](proposal.md)，行为契约见 [page-native-ads](specs/page-native-ads/spec.md)，详细来源为 [方案第 2–13 节](../../../docs/native-implementation-plan.md)。本稿是实施设计，不代表能力已实现或用户已验收。

目标工作树为 `/Users/jiaoyun/.codex/worktrees/native-loading-retention/ads-mediation`。原 73e5 工作树已归档，现从归档快照 `8d0bb05` 恢复；该快照承接原有修改，本轮增量以它为基线。SDK 保持 GMA Next-Gen 1.2.1、TopOn 6.6.22.3 及现有适配器。

2026-10-01 当前生产入口已接通 AdMob 预加载和 TopOn 共享库存；首版比价先取得两端实际对象，未展示落选对象可有限缓存。Google 图片适配器的同对象/平台 View 恢复已接入并通过 Debug 真机检查；视频、未知素材、第三方 AdMob 适配器及 TopOn 仍降级释放。完整跨组合及来源矩阵尚未通过。已有 controller/provider/handle、选择器、事件和缓存可复用。当前没有已发布 OpenSpec 能力；旧 `add-page-native-ads` 仍在进行中，不能当作已发布规格或已完成基线。

| 已有证据 | 可支持的结论 | 不能替代的验证 |
| --- | --- | --- |
| 旧重写的 109 项 JVM、14 项 instrumentation 及普通加载 R8 样本 | 对应提交状态下的普通加载、绑定、缓存与事件基线 | 新预加载、后来价格修改、逐来源恢复 |
| 1.2.1 / Debug / API 36 的三轮零价预加载探针 | 样本的非消费查询、报价配置与取货身份、自动补货 | 非零报价、异常队列、R8、预加载对象跨 Activity |
| TopOn 转移时同对象及 isValid | 对象转移阶段发生 | 已确认模板比例下的真实渲染与曝光 |
| Pangle TestImage2 曝光及 paid | 对应平台回调已发生 | 主图/图标完整，当前空白问题未关闭 |
| 后续 eCPM 调整的 23 项测试及 Debug 编译 | 原生 `getEcpm(USD) / 1000` 换算、选择和归因的 JVM 证据 | 该修改后的设备、非零样本和 R8 |

以上来自 [历史验证记录](../add-page-native-ads/verification.md:435)及其“原生比价改用 eCPM”小节；本轮不重跑或提升其证据等级。

## Goals / Non-Goals

**Goals:** 让“未消费库存”“已领取未渲染对象”“已绑定展示记录”具有清晰且互斥的所有权，页面信号只影响应清理的资源；让两个平台采用可验证的相同操作语义，保留各自能力边界。

**Non-Goals:** 不建立通用广告引擎、第二套 Manager/Repository 转发链、页面实例模型、导航状态仓库或新 DI/状态机框架；不将平台库存数量、有效期和暂停能力虚构为一致。其余产品范围见 proposal。

## Decisions

### 1. 延用同名能力，明确新旧规划的替代关系

采用同一个 `page-native-ads` 能力路径。因为主规格目录为空，本变更使用 `ADDED Requirements` 汇总完整目标行为。旧变更保留历史任务与证据，本轮不改写；归档前以最终通过的契约合并一次，不能先后无判断同步两份同名 ADDED。

| 旧草案限制 | 新目标 |
| --- | --- |
| 不预取下一条、缓存仅服务当前卡片 | 共享 SDK 库存、消费补货及有界保留 |
| active=false 固定销毁、平台固定隐藏策略 | 公开双策略，未知来源明确降级 |
| 最终组合 View 释放必定销毁广告 | 有持续页级最终销毁信号时分离外层连接并保留展示 |
| 页面实例/slot 编号区分同 position 卡片 | position 唯一，重复占用明确失败 |
| 恢复机械套用未展示的一小时 TTL/readiness | 已展示对象按来源恢复语义检查 |
| 页面同时持有两端广告后比价 | 可靠时先报价只取赢家，否则真实对象比价 |

不另建近似能力名，避免同一组件受两套冲突规格约束。旧实现中未被改变的布局、真实收益、Preview 和全屏边界保留在新规格；旧未通过任务仍由验收矩阵跟踪。

### 2. 三类资源复用已有职责

| 资源 | 归属及负责位置 | 释放边界 |
| --- | --- | --- |
| SDK 共享库存与准备操作 | 现有 `NativeAdCache` 登记；真实平台 provider 管理 | 闲置淘汰、后台、许可、会话期限及显式关闭 |
| 已领取未渲染对象 | 本轮选择或 `NativeCandidateCache` 有界保留 | 原期限、SDK 失效、容量淘汰；领取后转移独占所有权 |
| 已绑定广告与平台 View | 复用 `NativeCardController` / `NativeAdHandle` 的 position 展示记录 | 按策略隐藏释放、最终 owner/Activity 销毁、配置变化或来源失效 |

库存不持有 Activity/View/旧页面 callback。展示记录可持有同 Activity 的平台 View 及必要事件监听，但不持有退出组合的外层 AdsNativeView、业务 lambda 或旧 UI 订阅。索引只用 position；owner 和 Activity 作为记录的生命周期关联及 Context 安全条件，不参与 key。

状态、所有权和 View 操作集中在主线程；销毁先使代次失效，再撤销订阅、移除 View、解绑监听和释放。复用现有请求代次与记录引用核对旧连接，不添加第二套页面 token。清理步骤异常隔离且幂等，不使用大段静默 runCatching。必要的互斥状态沿用已有 sealed 类型或小型类型，SDK handle 不做可 copy 的 data class。

备选的统一全局 showing 对象会串位置；每页面自有池会复制请求和库存，均不采用。

### 3. Provider 统一语义，真实能力由门槛决定

内部以预备、查看、独占领取、关闭表达最小操作；签名以锁定版本能力检查为准。`NativeBiddingProvider` 只协调一次页面获取，不充当第三种平台库存。AdMob 首版每 key 目标一条；TopOn 只在首次需求及消费后请求补货，不能靠循环请求伪造固定 SDK 数量。

- AdMob 预加载回调只发布状态；页面领取安排在回调退出后的主线程调度中，不在回调栈中再 start/poll。SDK 自动补货和重试不叠加本层循环。
- TopOn 使用现有 `TUNative` 的 readiness / `checkAdStatus`、最高优先级候选及 `getNativeAd`；不能将缓存列表第一条当作必然领取对象。同 placement 的参数修改与领取串行，即使本层尺寸 key 不同也要核对真实兼容性。
- TopOn 单轮失败最多额外三次，2/4/8 秒；按库存 key 合并退避，只有前台、许可有效且有预热需求才继续。重复 ensure 不重置上限；网络恢复或有效显式 retry 开新轮。
- 本版本两平台先采用实际领取对象比价，并复用同一个选择器与保留区；不宣称非消费报价后只领取赢家。真实零价验证 SDK 库存流程，可控非零价格验证选择算法，真实非零填充作为后续配置验收，不阻塞生产入口切换。读取失败保持未知，不伪造零价或收益。

### 4. 页面需求与闲置窗口可独立结束

首次合资格页面请求建立库存；共享 key 包含平台/广告位与影响创意兼容性的参数，不包含 position、Activity 或 View。初次未登记位置可能等待加载。

组合退出、active/visible=false、owner 退出展示状态均解除本页需求和等待；最后一份需求结束才启动五分钟前台闲置窗口，无需求库存最多四 key。活跃需求不受此闲置上限驱逐。恢复原展示无需再次领取时不重新订阅；重组及重复通知不累加需求。

后台停止本层补货/退避；AdMob 没有已验证保留库存暂停能力时 destroy 该预加载 key，前台有需求再建。TopOn 关闭只处理本层能控制的监听、任务与对象，不取光共享缓存、不声称取消 SDK 内部在途请求。许可撤回隔离旧代次，恢复后的失效/更新办法必须由阶段 0 验证；无法证明隔离的来源不能继续消费旧库存。应用内 Activity 切换与真正后台通过现有生命周期监测区分。

选择固定内部 1 条、4 key、五分钟作为首版边界，不新增公开调参系统；这些值不是广告 SDK 的硬库存上限或 TTL。

### 5. 比价使用实际对象价格和有界核对

复用 `BidCandidateSelector` 和现有默认七秒期限。TopOn 使用 `getEcpm(TUAdConst.CURRENCY.USD) / 1000`，AdMob 使用 `valueMicros / 1_000_000`，统一 USD/次展示；精度字段只解释来源，不额外决定是否参与。未知、读取失败、负数、非有限值或币种不明均未知，真实零价有效；`getPublisherRevenue()` 不参与展示前比较，paid 单独上报实际收益。

AdMob 公共 ResponseInfo 不提供报价，版本相关读取留在已有反射模块及 JSON 配置，校验版本/路径/金额/币种，失败一次记录原因并降级为未知。不得扩大到二进制逆向工作；能力探针沿用现有已授权源码路径。

首版在主线程独占领取实际对象，再核对价格、有效性和兼容性；不使用领取前队首快照，不循环消耗同平台广告来掩盖错误。实际对象不能读价则未知，有效未渲染落选对象进入有界保留区，一个真实对象仅出现一次；最终有效选择才上报一次 bid_result。后续若实现可靠非消费查询，再加入快照身份/代次核对及最多一次重选、两次 SDK 取货；本版不宣称该优化已实现。

备选的无限重试会耗空 SDK 队列；仅靠主线程宣称原子性也不足，均不采用。

### 6. 原期限保守继承，展示恢复另作判定

使用 `SystemClock.elapsedRealtime()`。普通 AdMob 沿用原加载完成起的一小时；预加载会话 start 作为保守起点，一小时关闭旧队列，所属已领取未渲染对象保持旧期限。SDK 未给原始加载时间时不凭回调顺序构造精确时间，不因自动补货延长已领取对象期限。无法确认会话或期限则释放。

TopOn SDK/已领取未展示对象在访问与首次使用前按真实 readiness/isValid 检查，本层保留区另受首次入区起一小时上限限制。重复入池不续期。已知本层期限由现有任务及时清理；缺少 SDK 精确到期通知则在访问点复核，不假装有逐广告到期定时器。

已绑定对象不因队列轮换或缓存一小时强制刷新。恢复检查使用来源对已展示对象的真实语义，不把已经消费导致的 readiness=false 当成必然失效。选择保守会话寿命可能提前丢弃晚补入广告；实现用中文注释说明触发条件及获得可靠逐对象加载时刻后的替代方案。

### 7. 双策略分离隐藏、恢复和最终销毁

公开增加 `NativeRetentionPolicy`，默认 `DESTROY_ON_HIDE` 兼容旧行为；`RETAIN_WHILE_PAGE_ALIVE` 只保留原 position 已绑定对象。request/layout/policy 实质变化结束旧配置，owner/Activity 变化不迁移旧展示。

隐藏先撤销本轮待交付请求及库存需求。销毁模式释放，保留模式停止媒体/交互并保留平台 View 和广告事件监听。恢复重检页面资格、同 Activity、布局尺寸和来源能力；成功恢复不调用 render/prepare/registerNativeAd，不新增取货/补货/会话。失败记录原因、释放后按资格重新获取。

最终销毁无条件使记录终止、释放对象/容器、删除观察者及引用。保留模式隐藏不标为永久 Destroyed，现有 Loaded 仍只表达已获得广告而非正在曝光。平台未知或不能安全恢复时释放降级，不临时猜测一套媒体 API，也不将降级计为恢复通过。保留来源清单采用阶段 0 实测结论，不新增远程配置系统。

### 8. Compose 只管理当前连接

销毁模式 onRelease 延续销毁语义。保留模式且明确页级 owner 可在组合外持续接收最终销毁时，onRelease 分离当前连接和 UI 回调；返回由新 AndroidView 外层重新附着原平台子 View，旧外层实例不复用。旧 onRelease 或 owner 通知先核对记录/连接引用及请求代次，不能按 position 字符串直接删除新连接。

Navigation 宿主传具体 pageEntry，Fragment 传 viewLifecycleOwner；库不依赖 Navigation 或检查返回栈。Activity owner 无法说明已退出组合的业务页面是否存活，因此该情形销毁；持续组合中的 Tab 使用 active/visible 暂停恢复。`if (showAd)` 在保留模式只代表暂离，接入文档必须说明这一差别。onReset 保持 null，Preview 无副作用；对象/View 不进入 saveable 或跨配置 ViewModel。

### 9. 素材扩展沿用绑定入口

新增 `NativeAssets.kt` 只读快照，拟议 headline/body/callToAction/advertiser/mediaType/mediaAspectRatio/adFrom/domain/warning 以锁定平台能准确映射且业务有价值为准，不能用猜测或空字符串掩盖缺失。旧 `Custom { context -> binding }` 不变，增加命名工厂 `Custom.withAssets { context, assets -> binding }`，避免 lambda 重载破坏旧源码。

业务选择 XML/样式，库填充真实文案并优先使用 SDK 媒体、图标、CTA 和披露 View；快照不包含原始 SDK 对象或点击能力。工厂为当前 Activity 创建独立 View 树；模板不执行该工厂。结构错误明确失败不重选，创意兼容性在可知的最早时点排除。AdMob 监听先于注册；TopOn 保持 renderAdContainer → prepare。模板比例必须确认，媒体/正文/图标及来源披露规则继续沿用现有校验，Pangle 空白不凭曝光通过关闭。

### 10. 事件与中文日志复用已有出口

加载来源、页面获取和展示会话分开关联；预热时没有页面就不回填 position，库存命中不伪造网络 load。保留恢复继续原身份；合法迟到收益只保留轻量身份并走全局出口，与已失效 UI 分离。paid 和事件出口分别去重、隔离异常，不用 eCPM 替 ILRD。

先核对既有 AdEvent.slotId、position 后缀、requestId/sessionId 及宿主消费者，再移除原生内部冗余编号的生成传递；不因日志清理删除公共字段或修改其他格式。兼容结论写入接入说明，确有不兼容变更须明确迁移；默认保持现有原始事件格式。

日志使用 `AdsModuleLogger` / loggingEnabled / logTag，采用 [方案第 9.3 节](../../../docs/native-implementation-plan.md:405)的最终表达。INFO 记录获取、胜出、补货、渲染、平台确认曝光、真实收益和释放；DEBUG 才记录落选保留、缓存细节、内部身份及补货关联。按中文完整字段解释 DEBUG，不新增短编号映射；重复等待只在条件变化时打印，关闭日志不解析大对象。外部错误去换行限长，不打印敏感配置、素材 URL 或完整 ResponseInfo；未确认暂停/曝光不得先报成功。

## Risks / Trade-offs

- 非消费报价与实际对象无法可靠关联 → 阶段 0 用锁定版本验证；不满足则使用实际对象比价，事件只绑定最终对象。R8 和后续 eCPM 修改必须重新取得对应路径证据。
- TopOn placement 共享缓存影响尺寸与来源 → 同 placement 参数/领取串行且取后复核；逐来源记录真实展示，不用不同本层 key 宣称隔离。
- 平台无法安全保留已展示对象 → 明确降级释放，保留 API 仍存在，但来源支持声明只覆盖验证范围；不能以降级完成保留验收。
- position 复用或旧清理误删新记录 → 重复占用失败，旧回调校验实际记录/连接归属；不同卡片的宿主迁移为不同 position。
- 会话 TTL 提前放弃新补广告 → 首版接受保守浪费，保留原期限并记录原因；可靠逐广告时间有证据后再优化，不加复杂追踪系统。
- 库存关闭/许可撤回没有 SDK 完整清理接口 → 只声明本层可控操作，隔离旧代次，无法验证隔离的来源停止消费；不伪装已清空底层库存。
- 文档及任务存在历史缺口 → 普通加载的 109/14、早期 R8 和后续 eCPM 的 23 项测试分开使用；真实宿主、模板比例、Pangle 素材、后台音频、Fragment、retry、交互抑制与来源矩阵持续未完成。

## Migration Plan

1. 本次仅生成 proposal/spec/design/tasks 并执行 OpenSpec 校验，全部实施任务保持未勾选；不触发广告请求、代码切换、提交或归档。
2. 实施开始时记录当前文件级基线、宿主接入及事件兼容情况。阶段 0 先确认新库存/恢复能力与明确降级；未通过门槛的平台路径不能被声明支持或替代生产入口。
3. 按库存与日志 → 领取与生命周期/Compose → 素材渲染顺序完成可审阅小批改动，各批带能失败的最小检查。沿用现有测试和 smoke，不创建新的测试框架。
4. 逐步接上 View、Compose 与示例，迁移同 position 多容器调用；旧 Custom 和单平台入口做源码接入编译，公开二进制兼容单独说明。更新接入文档和已实现状态时只依据实际代码与证据。
5. 新预加载切换前完成对应门槛和 R8 验证；发现回归时只回退本轮明确修改的文件/路径，保留原 dirty tree，不 reset/clean。旧加载过渡路径满足迁移后删除，不引入永久双引擎或远程开关。
6. 用户提供真实工程和 Native ID 后完成最终场景验收。记录源码/构建/JVM、SDK设备、视觉和用户验收的分别结果，缺输入/无填充项保持未通过，既有未覆盖项不由新单测抵消。
7. 实施及验收结束后再同步两个在途变更的任务与同名能力，归档前确认唯一最终规格及历史证据链接；不能由本次规划自动完成或归档旧变更。


## Native 初始化配置统一封装（2026-10-01）

Native ID 由 AdMobIds.nativeId / TopOnIds.nativePlacementId 在 Ads.initialize 时提供。页面 NativeRequest 只保留 position、模板比例和竞价等待时间，删除平台及 ID 参数，不保留覆盖入口。沿用初始化模式，两端 Native ID 齐备才竞价，仅一端配置时只加载该端，均未配置返回 native_not_configured。内部 ResolvedNativeRequest 延用库存和事件链路。初始化前创建 View 等待 readiness。此项取代旧 NativeRequest 源码兼容承诺；全屏及旧 Custom 契约不变。
