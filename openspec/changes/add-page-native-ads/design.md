## Context

动机和范围见 [proposal.md](proposal.md)，行为契约见 [page-native-ads/spec.md](specs/page-native-ads/spec.md)。需求依据为 [2026-09-29 Native 实现方案](../../../docs/native-implementation-plan.md)，最初规划已进入实现；2026-09-30 按用户追加要求加入页面级原生比价，以下以当前接口为准。官方网页示例的 API 仍须通过锁定依赖编译和设备验证。

当前工作树包含根 Android library、可选 `:ads-compose` 及 `:r8-smoke-app`。声明版本为 GMA Next-Gen 1.2.1、TopOn core 6.6.22.3、GMA adapter 1.2.1.1.0、UMP 4.0.0，minSdk 26；构建排除 legacy `play-services-ads` / `play-services-ads-lite`。本次不重新解析或升级这些依赖。

当前源码的约束：

- `Ads` 在 UMP 允许后初始化平台；Bidding 的聚合 READY 不能证明请求选择的平台成功，需读取各平台状态。现有初始化回调不适合页面通过重复 initialize 注册长期等待。
- `AdEventDispatcher.beginLoad` 使用 `preload_<format>`，展示会话默认携带 `FullScreenShowAttempt`；两处都需最小扩展才能准确表达页面加载和 Native 展示。
- AdMob、TopOn 及 Bidding 已有自动开屏路径；Native 交互抑制必须覆盖这些路径，不能只改某一个 provider。
- `add-fullscreen-display-opportunities` 仍是未完成的在途变更，不能将其剩余宿主验收视为本次已完成依据；Banner 仅为方案，不作为可调用组件。

## Goals / Non-Goals

**Goals:**

- 将对象所有权、资格判断和清理收口到一个页面 View，使两个平台及 Compose 共享同一行为。
- 将平台差异限制在加载、渲染、媒体/图标和回调转换处；为默认与自定义布局保留同一注册路径。
- 复用已有初始化、日志、计数、事件与收益验证能力，保留可测试的请求代次及轻量事件身份。

**Non-Goals:**

- 不重写全屏广告架构，不抽象通用广告引擎或提前建设 EmbeddedAdManager。
- 不让 Native 渲染类型或页面状态改变全局平台选择，不将 Native 对象放入已有全屏缓存。
- 不为后续信息流创建队列、池、布局 DSL 或扩展接口；完整产品范围以 proposal 为准。

## Decisions

### 1. 先完成锁定版本能力验证，再实现平台适配

阶段 1 在当前依赖上写最小可编译调用并记录真实 SDK 证据：AdMob 单条 Native 加载、事件/paid 共用监听、素材注册和释放；TU 自渲染/模板、布局嵌套、媒体/图标、Native paid setter 的所属对象及签名、销毁拼写、回调线程和取缓存行为。接口示例只能作为待验证候选，不能照搬 legacy GMA 或全屏 TU 收益监听。

固定能力门槛为双平台 Native 加载、正确渲染、资源释放和真实 ILRD。TU paid 无法接通时，该平台适配任务保持未完成，不用 eCPM 替代，也不将变更标为已完整交付。可以独立推进 View/AdMob 等不依赖部分；若必须升级依赖或缩减平台支持，另行调整方案，不能偷偷改变本次契约。

隐藏/后台视频的固定决策是：只有当前 SDK 与启用来源通过暂停/隐藏/恢复验证，才保留对象和容器；否则该平台固定采用释放重建。TopOn 待恢复对象有效性无法确认时也采用释放后按资格重新获取。备选的直接假定所有网络可暂停或统一一小时有效期缺少依据，故不采用。

### 2. 页面 View 持有最小状态，Compose 仅包装

公共请求支持 `NativeRequest(platform, adUnitId, position)` 及 `NativeRequest(position = ..., admobAdUnitId = ..., topOnPlacementId = ...)`；其余公共面为`AdsNativeView`、`NativeLayout.Default / Custom(factory)`、`NativeLayoutBinding` 和可选模块的 `AdsNative()`。模板比例约束仅在实际使用 TopOn 模板的入口传入，具体参数放置由阶段 1 的接口验证收敛，不给所有请求强制添加无效宽高。

Activity、owner、等值 request 和 layout 对象组成实例身份；新身份通过释放旧 View、创建新 View 生效，不提供已展示对象任意热换布局。状态、事件与 View 操作在主线程串行；SDK 回调跨线程时先捕获请求/广告身份，再排入主线程，不能执行时再读取可被替换的当前广告。

| 内部信息 | 生命周期及用途 |
| --- | --- |
| slotId | 一个 active 页面周期，区分相同 placement 的同时实例；原周期结束后不复用 |
| generation | 每次加载/替换的失效标记；销毁、停用及重新请求前先更新，阻止旧 UI 回调 |
| requestId | 每次真实本层加载，沿用现有加载事件标识 |
| sessionId 与平台 ID | 一条广告的展示归因；安全重挂同一对象不更换 |
| 当前广告、平台容器和 loader | 只由该 View 持有，释放后清空；不进入全局配置、ViewModel 或可保存状态 |

`slotId` 仅在现有公开字段不足以关联周期时作为尾部可选字段添加，默认 null，审查兼容影响；内部无论是否增加公开字段都必须能够区分周期。轻量事件记录不得持有 Activity、View 或广告对象。

不为单一实现新增工厂/服务层。可以将 View 的资格与代次判断提取为可由现有 JUnit 直接驱动的小型内部逻辑；只有两端确需的窄适配边界才使用共同类型。

### 3. 资格与终态先确定，再通知外部

`Ads` 提供内部的所选平台配置/状态/许可快照及可取消订阅；复用现有初始化结果与共享监听，页面销毁时移除订阅。初始化等待保持 Idle，明确未配置、初始化失败或 UMP 拒绝进入带原因的 Failed；不以聚合 READY 代替所选平台成功，也不靠再次 initialize 增加不可取消回调。

| 触发 | 处理 |
| --- | --- |
| 初次创建 | 先保存 active/visible，再装观察者与计算资格；合法 active 周期只发一次 ad_position |
| Idle 且资格齐全 | 创建 requestId / generation，先进入 Loading，再调用平台；同一有效请求只启动一次 |
| 加载成功 | 先取得对象所有权及安装监听，检查身份/创意/绑定；失效则回收，暂不可显示则按平台策略保留或释放，允许时才挂载 |
| Loaded | 只代表有效对象已取得，不伪造曝光；渲染失败仍可转 Failed |
| 加载或渲染失败 | 本次加载结果及展示失败分别按语义收口，记录稳定 reason；不在失败回调内递归加载 |
| retry | 仅 Failed 且资格齐全时启动新代次；其余调用无副作用，不预留恢复后的自动重试 |
| active=false | 先使代次失效并结束 slot，再移除自身广告及释放；可回 Idle，后续 false→true 创建新周期 |
| 临时暂停/隐藏 | 不新请求、不新挂载；安全保留或按平台固定策略释放，恢复时重新检查有效性与资格 |
| 模板确认关闭 | 结束当前 slot 并释放，公开状态可回 Idle，但该 slot 不再自动加载；须明确新 active 周期或新实例才可出现 |
| owner destroy / 最终释放 / destroy | 先置永久终态并失效代次，再解绑、移除及释放；Destroyed 不能复活 |

所有对宿主的回调均异常隔离。外部通知可能同步重入 destroy、retry 或导航，因此每次平台调用及最终挂载前仍检查代次和资格，不能将一次校验当成整个调用链的保证。

取消本层请求必须产生一次带取消原因的加载终态，但不承诺底层网络取消。AdMob 迟到对象直接释放；TU loader 从创建时即归页面持有，清理已装监听及可确认归属的广告，禁止为“清理迟到广告”盲目消费 placement 全局缓存。旧收益通道与旧 UI 回调分开，合法 paid 仍使用原广告的轻量身份。

### 4. 默认卡片与业务布局共享平台绑定

布局工厂通过 Activity 主题 Context 创建普通 View/XML，返回 root、文本素材 View、媒体/图标空占位及广告披露位置。SDK 创建内部平台 Native 容器并放入 root；平台媒体、图标和必需披露元素由 SDK 提供或按已验证的图片绑定能力处理。业务不接触原始广告或平台媒体类型。

默认卡片也创建同样的 binding；不另写一条仅默认布局可用的展示链。校验 root 未挂载、引用归属、必需项及空占位，再根据实际创意验证兼容性；可选素材缺失则清空/隐藏，必需素材缺失失败。失败时只删除 SDK 自有 View，不清空宿主其他布局。若平台图片填充确需支持代码，先复用已有平台能力，不默认增加图片加载依赖。

宽度取宿主内容宽度，高度由布局、媒体比例及约束测量；默认布局提供清晰广告标识、AdChoices 留白及 CTA，覆盖长文案、深色、大字体和窄屏。Google 视频至少 120 × 120 dp 是下限，不能代替实际素材比例、硬件加速及完整展示要求。业务布局仍必须完成真实视觉和点击区域验收，结构校验无法证明没有遮挡。

备选的纯 Compose 素材插槽需要另行保证平台注册与点击语义，不纳入首版。把业务场景映射成 SDK 内部 XML 表会扩大耦合，故 layout 与统计 position 分离。

### 5. 双平台保留各自真实渲染顺序

AdMob 候选流程：Next-Gen `NativeAdLoader` / `NativeAdRequest` 请求单条 → 接收广告 → 安装同一个 `NativeAdEventCallback` 处理曝光、点击、覆盖层及 paid → 绑定素材及媒体 → 使用该版本支持的 `registerNativeAd` 完成注册 → 再检查资格并挂载。精确 import、签名和释放入口由阶段 1 编译确认，不依据网页推断 1.2.1 的能力。

TopOn 候选流程：页面拥有 `TUNative` loader → 按版本允许的方式获取并拥有 `NativeAd` → 在展示前安装展示/点击/收益监听 → 判断 `isNativeExpress()` → 自渲染按 binding 与 `TUNativePrepareInfo` 注册，模板使用平台布局 → 在 `TUNativeAdView` 中先 `renderAdContainer()`、后 `prepare()`。`destory()` 等实际拼写以及 Native 收益监听所属对象必须以锁定依赖为准。

自定义布局收到 TU 模板立即 `unsupported_native_render_mode` 并释放；默认入口可接受模板，但其排版由平台决定，不承诺使用默认卡片外观。模板比例必须由已确认配置提供，宽度计算同时用于请求和展示。相同测量不重载；真实尺寸变化且确需重取时，结束当前代次、只发一个必要请求。不得循环请求以碰到自渲染结果。

### 6. 缓存和恢复不引入刷新系统

每个 View 独占当前卡片对象；跨 Activity 缓存只持有已落选且从未渲染的候选，不保留业务 View。AdMob 在对象交付时记录单调时钟，用于待展示或待恢复的一小时期限；不对持续显示中的卡片设置刷新任务。TopOn 以实际缓存/就绪能力判断，不能把从缓存取出时刻当成加载时刻；验证无法确认时采用释放/重新获取策略，不能套用 AdMob TTL。

不同卡片不能同时共用对象；缓存取出即转移所有权，关闭或 destroy 的对象不得复活。所有替换统一走代次失效与清理入口，不创建后台预取、下一条缓存或自动退避任务。缓存到期属于有效性处理，不生成 `ad_native_refresh`。

### 7. 扩展现有分发器，保留独立收益身份

`AdEventDispatcher.beginLoad` 增加可选业务 position/周期关联入口，旧全屏调用继续使用原默认预加载位置。Native 创建与全屏锁无关的展示归因记录；可共享位置计数、日志及安全 dispatch，但不能为了复用而构造有实际占用语义的 `FullScreenShowAttempt`，也不重构全屏结束规则。

`ad_position` 每 slot 一次，`ad_load_request/result` 每真实本层请求一对；加载成功与后续渲染失败分别记录，取消的迟到回调不能补第二个加载结果。`ad_show_fail` 针对当前未曝光尝试去重，已曝光后正常清理不补报。不同 retry 可形成独立尝试，不能用整个 View 的一次性失败标记吞掉后续真实失败。

曝光按广告去重，合法多次点击保留。卡片关闭只接受已确认的卡片关闭通知；覆盖层关闭和页面清理不发 ad_close。单平台 Native mediationMode 使用实际平台，不产生 bid 事件；双 ID 请求使用 BIDDING，每候选单独记录 load 身份并发出一次真实 ad_bid_result，选定后展示与收益身份切换到获胜候选。

收益沿用 `AdRevenuePayload`、平台原始对象与现有校验/收益 ID 规则：AdMob 用 valueMicros，TU 用 Native 实际回调收入；只有字段确实不足才扩展 `AdRevenue.kt`。`ad_paid` 与 revenue payload 各自交付及去重，不能因为事件监听失败而漏掉 payload；合法零金额保留，非法金额及归因缺失记日志，不用 eCPM 或当前 session 填空。

广告 A 的收益回调捕获 A 的不可变身份，在 UI 销毁后仍可交付全局收益出口；不保留页面引用来等待收益，也不保证平台 destroy 后还会产生回调。阶段 1 核对该版本回调可用信息，不能擅自假设 response ID 等于唯一 paid ID。

### 8. Native 与全屏仅在交互资格处连接

`AdFormat.NATIVE` 在全屏映射中显式拒绝，`Ads.isReady(NATIVE)` 返回 false，通用展示机会入口返回 `unsupported_ad_format`。检查 `AdsConfig`、provider、compatibility、竞价及枚举遍历，防止新增格式落入某种全屏 ID 或被全屏预加载循环处理。原三种格式的默认值、等待、取消、计时和奖励语义不随 Native 改变。

宿主使用页面级机会时，通过已有场景有效性控制覆盖层期间的展示资格，并在示例中明确接入方式。仍启用旧 `autoShowAppOpen` 的路径共享一个最小内部交互抑制判断，接入各 provider 及 Bidding 自动控制器；Native 卡片不会申请全屏展示锁。

交互抑制只保留不含页面引用的短期身份：可靠 overlay-open/close 信号控制覆盖层期间抑制；外部跳转识别本次离开与首次返回，消费该次恢复的自动开屏机会后结束；只有点击、没有打开/离开证据时，使用有界经过时间窗口清除票据。旧广告的迟到关闭不能清除另一广告当前覆盖层资格。所有缺失回调的兜底都必须有可验证的结束条件；具体窗口值由阶段 1 设备证据确定并作为内部常量记录，不公开额外模式。

仅忽略广告 Activity 无法覆盖浏览器/商店返回，持锁到卡片销毁又会阻塞无关全屏机会，两者均不采用。实际抑制与重返行为是必验项，不能凭 click 回调存在就宣称完成。

### 9. Compose 为可选模块，接入示例可独立编译

新增独立 `:ads-compose` Android library，依赖核心库，采用与当前 Kotlin/AGP 兼容且实施时确认的 Compose/Lifecycle 依赖；核心仅引入其实际需要的 Lifecycle 类型，不引入 Compose/Navigation。若届时已存在由 Banner 实施创建的等价模块，直接复用，禁止建立第二套包装模块。

`AdsNative` 先检查 Preview；真实路径以 Activity、owner、等值 request、稳定 layout 作为重建 key，在 `AndroidView.factory` 创建 View，`update` 同步 active/visible/最新回调，`onRelease` 调用同一 destroy。自定义工厂由业务 remember 持有；owner 变化不能继续复用旧 Activity View，普通回调更新不能成为重建 key。

文档分别给默认、自定义 XML、Fragment `viewLifecycleOwner`、具体 `NavBackStackEntry` 和同 ID 双卡片示例。保留页面的 active 必须由业务离页明确更新，不能仅绑定 Activity，也不能把 Dialog 引起的暂停当永久离开。首版不为 LazyColumn/RecyclerView 开启 onReset 复用契约。

## Risks / Trade-offs

- [锁定版本 Native API 或 TU paid 不完整] → 阶段 1 编译和真实收益验证是平台适配门槛；失败时保留未完成项，不升级依赖或伪造收入绕过。
- [TU 内部 placement 缓存与每实例 loader 的关系不透明] → 验证同 ID 双实例、清理与迟到结果归属；只清理可确认对象，无法隔离时不得通过双实例验收。
- [部分广告源不能安全保留隐藏视频] → 平台固定释放重建，并公开恢复请求次数差异；每个实际启用 Native 来源独立验证。
- [业务布局结构合法但视觉违规] → 结构测试与图片/视频、字体缩放、深色及点击区域设备验收分别记录，不以 XML 编译替代视觉结论。
- [Native 点击误触发开屏或永久禁用] → 覆盖各自动开屏路径，测试无跳转点击、长时间外跳、返回、漏关闭及多卡片交错；有限票据不持有页面。
- [公开枚举/数据类影响宿主] → 记录 Kotlin 穷举迁移及二进制兼容影响；新增数据字段用尾部默认参数，但不将其视为二进制兼容保证。
- [与在途全屏或未来 Banner 修改共享文件] → 实施前检查工作树并按文件分配写入责任；只复用已经存在且语义一致的小段逻辑，保留他人修改及未完成验收。

## Migration Plan

1. 在当前依赖完成能力证据与固定策略记录，随后实现 View/布局、两平台、事件和自动开屏接点，再增加 Compose 模块；按 `tasks.md` 分批验证，所有实现/设备任务初始未完成。
2. 先跑现有 JUnit、核心/Compose Debug 构建与 lint，再扩展 `:r8-smoke-app` 的实际 Native 可达入口和 release 构建；必要的设备、视觉、收益与各网络来源验收独立执行。仅有 R8 构建不等于真实初始化/渲染成功。
3. 宿主继续使用既有 Ads 初始化，只在目标页面添加 Native ID、生命周期/active 绑定和可选 layout。核对宿主穷举 when；不把此次接入顺带改为 Native-only 初始化或迁移业务广告栈。
4. 将源码/JVM、构建/R8、设备、视觉及平台来源结果记录到本变更的 `verification.md`，写明版本、设备、命令、广告测试配置和未覆盖项；不记录凭据或用生产广告点击验证。
5. 上线或发布另按实际发布授权执行。本次规划不发布 SDK。宿主回退时先移除 Native 页面调用及可选 Compose 依赖；SDK 回退须同步回退引用 NATIVE 的宿主代码，不能以运行时关闭替代枚举兼容检查。

## Open Questions

以下为已固定契约之下的实施细节，由阶段 1 解答并记录，不阻止规划，也不能成为静默削减规格的理由：

- 锁定 TU Native paid/销毁及素材注册的精确签名、回调线程和可用收益身份字段；若能力不满足，按决策 1 保持平台实现未完成。
- 各平台实际启用来源可否安全保留隐藏广告，TU 可确认的对象有效性依据；按决策 1 和 6 选择已定义的保留或释放重建分支。
- 测试 placement 的模板比例、实际填充来源，以及自动开屏交互兜底窗口的实测值；它们只影响测试配置和内部参数，不改变失败语义或抑制边界。
- 可选模块的 Compose/Lifecycle 具体兼容版本及最终公开参数命名；不改变核心依赖隔离、实例身份或布局绑定责任。

### 9. 页面内比价与对象所有权

双 ID 请求创建两个页面候选，复用现有 `BidCandidateSelector`，不经过全屏缓存。已就绪端立即加载；另一端可在默认 7 秒总期限内等待初始化。只比较实际取得且仍有效的对象，已知 USD 单次价格优先于未知，同价/均未知选 AdMob；零价可用、无填充不可用。无合格对象明确 Failed，业务仍只使用同一个状态与 retry 接口。

TopOn 展示前使用 getEcpm(USD)/1000，统一为 USD/次展示；getPublisherRevenue 不参与比价，实际收益仍使用 Native paid 回调。读取失败、负数或非有限值记为未知价格，真实零价保留。AdMob 仅在 1.2.1 配置匹配时读取 regular Native 路径 a→b→m，按价格对象 b(micros)、d(currency) 提取；不遍历猜测字段、不使用商品价格或展示后的 paid 做预展示竞价。配置或反射失效返回未知，不阻断可用广告展示。参考 remax_sdk 固定提交 83aecfbd9921b75073f761cf5797b7d076efed30；不复制它的未填充零价候选或混合 eCPM/单次收益口径。

不兼容自定义布局或未知模板比例的 TopOn 模板在选择前释放。选择直接交付持有的获胜 handle，缓存有效且从未渲染的未胜者，停止计时和订阅。取消只清理本卡片拥有的对象，不盲取 TU placement 缓存；迟到结果释放，合法已交付广告的迟到收益仍归原身份。每次业务回调后重新检查代次，防止回调导航/销毁后继续发请求或挂载。

### 10. 跨 Activity 的落选缓存（用户追加）

保留一个共享有界映射，键为平台/广告位，每键一条、总计最多四条。候选先独占取缓存，再按需请求；普通单平台入口也复用此路径。模板要求原请求宽度/比例匹配。只缓存从未渲染的有效对象，落选时清空 SDK 回调目标，取出时改为新候选监听，避免保留上一页/竞价图和串收益归因。展示后的对象只销毁，不回池。

TopOn loader 使用 Application Context，不以弱引用或 MutableContextWrapper 伪装 Activity；渲染仍使用当前真实 Activity。GMA Next-Gen loader 无 Activity 入参。AdMob 期限沿用加载时刻，TopOn 实际 `isValid` 在 6.6.22.3 编译验证；最长一小时保留、容量淘汰和许可撤回均销毁对象。共享缓存不等待下一次请求才清理到期条目。不由缓存可用性推导 SDK 任意来源的跨 Activity 展示可靠性，具体来源能力按设备验证记录。
