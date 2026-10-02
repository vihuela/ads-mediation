## Purpose

为 Android 页面提供独立持有的 Native 原生广告卡片，允许宿主选择默认卡片或业务 View/XML 布局，并通过 View 或 Compose 接入。规范请求资格、双平台渲染、页面与广告对象的生命周期、事件收益归因及全屏兼容行为，使页面离开、重组和迟到回调不会导致额外请求、错误挂载或资源串用。

## ADDED Requirements

### Requirement: 页面卡片显式选择已配置的平台

系统 SHALL 为页面内独立 Native 卡片提供请求入口，由请求指定单平台或同时指定 AdMob/TopOn Native ID，以及业务 position。系统 SHALL 校验请求、全局配置及所选平台状态；未配置或明确初始化失败时返回可辨识的失败原因，不无限等待另一平台、不静默切换平台。Native ID SHALL 随请求传入，不成为现有全屏 ID 配置的新必填字段；首版 SHALL 保留当前全局初始化约束。

#### Scenario: 竞价模式下所选平台失败

- **WHEN** 全局配置为 Bidding，AdMob 初始化成功但请求选择的 TopOn 已明确初始化失败
- **THEN** Native 卡片报告所选平台失败，不以整体 READY 请求 TopOn，也不改用 AdMob

#### Scenario: 所选平台成功而另一平台仍未成功

- **WHEN** 请求选择的平台已初始化成功、UMP 允许请求且页面条件满足，另一竞价平台仍在初始化或已经失败
- **THEN** Native 卡片可以请求所选平台，不等待另一平台

#### Scenario: 请求缺少有效字段或选择未配置平台

- **WHEN** 请求缺少有效广告位 ID 或业务 position，或者所选平台不在当前配置中
- **THEN** 系统返回明确的输入或配置失败，不调用平台 Native 加载

### Requirement: 请求和挂载遵守页面资格

系统 SHALL 仅在请求合法、所选平台初始化成功、UMP 允许请求、页面 active、业务 visible、owner 至少 RESUMED、绑定 Activity 可用、容器已附着且有有效内容宽度时发起加载；临时遮挡、失去焦点或宿主不可展示时 SHALL 阻止新请求和新挂载。首次判断 SHALL 使用传入的初始 active/visible。挂载前 SHALL 再次检查资格及请求身份；等待中的初始化不能绕过 UMP。

#### Scenario: 初始页面不可用

- **WHEN** 卡片以 active=false 或 visible=false 创建，或者 owner 未恢复、容器未附着或宽度为零
- **THEN** 系统不先发起加载再停用，待资格满足后才允许加载

#### Scenario: 等待初始化和许可

- **WHEN** 所选平台仍在初始化或 UMP 门禁尚未完成
- **THEN** 卡片不发起请求；条件变为允许后可继续，明确初始化失败或许可拒绝时报告对应原因

#### Scenario: 加载完成前展示资格改变

- **WHEN** 加载开始时有资格，但加载完成前页面隐藏、失去焦点、owner 暂停或 UMP 不再允许请求
- **THEN** 系统不挂载新广告，也不因为加载成功绕过当前门禁

### Requirement: 身份稳定时不重复创建广告

系统 SHALL 以 Activity、页面 owner、请求值和布局对象共同确定卡片身份。等值请求、普通 UI 更新和回调更新 SHALL 不重建广告；身份改变时 SHALL 释放旧实例并创建独立实例，旧回调不污染新实例。业务自定义布局对象 SHALL 稳定持有，不由普通重组反复创建。

#### Scenario: 请求等值及回调更新

- **WHEN** 同页传入与旧值相等的请求，并更新状态回调或非广告 UI
- **THEN** 原广告对象和有效加载保持不变，不新增请求

#### Scenario: 更换 owner 或布局

- **WHEN** Activity、owner、请求内容或布局对象身份发生变化
- **THEN** 旧实例结束并释放，新实例独立建立生命周期和加载身份，旧广告不能迁移过来

### Requirement: 页面实例独立拥有资源且销毁不可逆

每个页面广告位 SHALL 独立持有广告对象、平台容器和回调身份。active=false SHALL 结束当前周期并释放资源，再次 active=true 可开始新周期；永久 destroy、owner 销毁及最终 View 释放 SHALL 幂等结束实例，使其不可复活。Fragment SHALL 以 View 生命周期为边界，导航页面 SHALL 以具体页面 owner 和业务 active 表达归属；页面资源不得进入全局池或跨页面保存状态。

#### Scenario: 同广告位的两个卡片

- **WHEN** 两个页面实例同时使用同一 Native 广告位 ID，其中一个离开并销毁
- **THEN** 仅该实例的对象、子 View 和观察者被释放，另一实例继续使用自己的广告，不共享同一广告对象或 View 树

#### Scenario: 页面离开后返回

- **WHEN** 单 Activity 页面进入详情时将卡片 active=false，之后返回并设置 active=true
- **THEN** 原周期不恢复；新周期在资格满足时重新请求，不依赖 Activity 销毁判断离页

#### Scenario: Fragment View 或导航 owner 销毁

- **WHEN** Fragment 的 View 生命周期或具体导航页面 owner 结束，即使 Activity 仍存活
- **THEN** 卡片立即完成幂等清理，重复 destroy、最终释放或再次设置 active 不复活已销毁实例

### Requirement: 临时隐藏保留页面身份并安全处理媒体

系统 SHALL 区分临时隐藏与永久离页。Dialog、短时 detach、失焦、后台或 visible=false SHALL 阻止新请求及新挂载，隐藏广告不得继续产生不正确的交互或媒体播放。平台具备经验证的安全保留能力时 SHALL 保留当前广告及容器；不具备时 SHALL 使用该平台固定的释放重建策略，并在文档中说明恢复可能新增请求，不对宿主暴露多套暂停模式。

#### Scenario: Dialog 使底层页面暂停

- **WHEN** Dialog 改变底层页面生命周期但页面未永久离开
- **THEN** 系统不把该暂停直接等同 active=false，按平台既定隐藏策略处理，恢复后重新检查资格

#### Scenario: 视频卡片进入后台

- **WHEN** 卡片含视频且页面隐藏或应用进入后台
- **THEN** 媒体及交互符合已验证的平台暂停或释放策略；恢复时不无条件重挂已释放或已过期对象

### Requirement: 在途加载和迟到结果按周期隔离

同一实例 SHALL 最多保留一个本层有效在途请求，重复布局、恢复或初始化通知不得增加并行请求。周期结束 SHALL 使旧请求身份失效；这不等于底层网络已经取消。旧回调直接交付的广告对象 SHALL 被释放，不挂载、不更新新 UI；TopOn SHALL 仅回收可以确认归属的资源，不从 placement 共享缓存盲取对象作为迟到结果，也不声称清空了不可访问的底层缓存。

#### Scenario: 加载中重复收到就绪通知

- **WHEN** 同一有效周期正在加载，随后发生多次 onResume、测量或平台就绪通知
- **THEN** 本层加载调用次数不增加

#### Scenario: 销毁后平台交付广告

- **WHEN** 原实例已销毁，旧加载回调随后直接交付广告对象
- **THEN** 系统释放该对象，不通知新实例 Loaded，也不向旧页面挂载

#### Scenario: 旧回调与新周期交错

- **WHEN** 原周期结束后新周期已开始，旧周期的加载、曝光或点击回调才被处理
- **THEN** 旧通知不能改变新卡片状态或改写新周期事件身份；合法旧收益按收益契约单独处理

### Requirement: 状态和重试具有明确边界

系统 SHALL 向宿主提供 Idle、Loading、Loaded、Failed、Destroyed 状态；Loaded SHALL 仅表示有效广告已加载，不等同曝光。失败 SHALL 包含稳定 reason 和可选平台错误码，区分无填充、加载错误、布局/渲染错误及取消。显式 retry SHALL 仅在当前周期失败且请求资格满足时发起一次新加载；加载中、已加载、已销毁或暂不满足资格时不生效，不排队自动重试。状态、事件和收益回调的异常 SHALL 被隔离，不阻断其他合法通知和资源清理。

#### Scenario: 失败后显式重试

- **WHEN** 卡片加载失败且仍有资格，宿主调用 retry
- **THEN** 开始一次具有新 requestId 的加载；该加载进行中再次 retry 不发起第二个请求

#### Scenario: 不满足重试条件

- **WHEN** 卡片已加载、已销毁、隐藏或 owner 未恢复时调用 retry
- **THEN** 调用不新增请求，也不留下恢复时自动执行的重试任务

#### Scenario: 业务回调抛异常

- **WHEN** 状态、事件或收益监听抛出异常
- **THEN** 其他合法通知仍可交付，已确定的终态和广告释放不被跳过

### Requirement: 默认及自定义布局使用同一素材绑定契约

系统 SHALL 提供一套含媒体区域的默认卡片和业务 View/XML 布局工厂入口，两者使用同一素材绑定流程。业务 SHALL 使用当前 Activity 的主题 Context 创建独立内容 root，提供文本素材和广告披露位置；媒体与图标占位随业务布局提供。无媒体区域的 AdMob 自定义布局仅可承载无需媒体区域的素材，视频素材 SHALL 明确失败；TopOn 自渲染无媒体布局 SHALL 仅接受可靠识别为 Pangle 图片且具有可绑定图标的素材，其余要求媒体或要求未确认的素材 SHALL 明确失败。无媒体时 SHALL 仅跳过媒体绑定，继续其余素材及平台注册。工厂不接收原始广告、不自行注册点击、填充广告或上报曝光收益。position SHALL 只用于业务归因，不能隐式选择布局。

#### Scenario: 默认布局直接使用

- **WHEN** 宿主未指定自定义布局
- **THEN** AdMob 或 TopOn 自渲染结果使用 SDK 默认卡片，经同一绑定和注册流程展示

#### Scenario: 同工厂服务不同卡片

- **WHEN** 两个卡片使用同一稳定的业务布局工厂
- **THEN** 工厂为各实例创建独立 View 树，平台媒体和素材在各自容器内绑定，不跨实例共享 binding

#### Scenario: TopOn 紧凑图片布局省略媒体槽位

- **WHEN** 自渲染绑定没有媒体槽位，且当前广告来源为 Pangle、素材类型可靠识别为图片、图标可绑定
- **THEN** 系统仅跳过主媒体绑定，继续标题、描述、CTA、图标、广告披露和点击视图注册，按原顺序完成 render/prepare

#### Scenario: TopOn 无媒体布局遇到视频或未知要求

- **WHEN** 无媒体布局收到 Pangle 视频、Meta/Vungle 普通 Native 或来源/素材要求尚未确认的广告
- **THEN** 分别明确失败 native_video_media_missing、native_media_required 或 native_media_requirement_unknown，不用缺少槽位作为所有来源的统一失败依据，不以测试广告文案推断素材类型

### Requirement: 无效绑定不得产生部分广告

系统 SHALL 在可检查范围内校验 root 未挂载、素材引用均属于 root、必需绑定存在且已提供的媒体/图标占位为空，并在素材到达后检查与实际创意的兼容性。工厂异常或绑定无效 SHALL 返回明确布局错误，清理已取得资源，不挂载部分广告，不清空宿主其他内容。

#### Scenario: 工厂或结构无效

- **WHEN** 工厂抛异常、root 已有父容器、素材引用不属于 root、缺少必需绑定或占位非空
- **THEN** 卡片以布局错误失败，已取得广告及自身创建的 View 被释放，宿主其他子 View 保持不变

#### Scenario: 创意缺少素材

- **WHEN** 创意缺少可选字段或无法满足必需素材要求
- **THEN** 可选素材对应元素被清空并隐藏；必需素材不完整则渲染失败，不能通过简单隐藏绕过要求

### Requirement: 素材注册和真实交互由平台负责

系统 SHALL 将素材放入对应平台 Native 容器并完成平台要求的素材、媒体、图标和披露元素注册，在可能触发展示或业务通知之前安装曝光、点击及收益监听。宿主 SHALL 不向广告内部增加点击处理器，不将空白区域扩展为点击区，也不以 View 挂载或可见比例伪造曝光。平台提供媒体/图标 View 时 SHALL 避免再叠加重复图片。

#### Scenario: 广告已加载但尚无曝光回调

- **WHEN** 对象已经加载甚至已经挂载，但平台尚未回调曝光
- **THEN** 卡片可报告 Loaded，但不产生 ad_impression

#### Scenario: 首次挂载立即产生收益或曝光

- **WHEN** 平台在绑定、挂载或业务收到 Loaded 后立即产生回调
- **THEN** 已安装的监听按该广告身份接收事件，后续设置监听不覆盖或丢失收益回调

### Requirement: TopOn 渲染类型和模板尺寸明确匹配

系统 SHALL 支持 TopOn 自渲染及默认入口的平台模板，按当前版本要求完成渲染与素材准备。自定义布局 SHALL 只接受自渲染广告；收到模板时以 `unsupported_native_render_mode` 失败并释放结果，不忽略布局、不静默改用默认模板、不循环请求。默认入口使用模板时 SHALL 要求已确认的比例/尺寸约束，请求和显示尺寸保持一致；未知尺寸明确失败，不裁切后强行展示。

#### Scenario: 自定义布局收到模板广告

- **WHEN** 请求指定自定义布局而 TopOn 返回模板结果
- **THEN** 返回 unsupported_native_render_mode 并释放该结果，当前失败等待宿主显式处理

#### Scenario: 默认入口使用已知模板

- **WHEN** 默认布局请求收到模板广告，且已提供与后台模板一致的尺寸约束
- **THEN** 系统使用平台模板及对应 Native 容器，按内容宽度计算一致的请求和展示尺寸，不拆解模板素材

#### Scenario: 模板宽度改变或比例未知

- **WHEN** 模板缺少确认的比例，或容器宽度改变导致当前请求尺寸不再适用
- **THEN** 前者以明确尺寸错误失败；后者在确需重新请求时仅建立一个新加载代次，重复测量不连续重载

### Requirement: 卡片尺寸和披露元素保持可用

卡片 SHALL 使用宿主可用宽度、素材和媒体约束确定高度，不套用固定 Banner 高度，不把媒体偏好比例当成实际素材比例。默认及自定义路径 SHALL 保持广告标识、AdChoices 和 CTA 清晰可用，不被内容遮盖，不扩大可点击区域；Google 视频媒体区域 SHALL 至少为 120 × 120 dp，同时满足实际视频比例及必需字段要求。包含视频的宿主 SHALL 满足平台硬件加速要求。

#### Scenario: 窄屏及辅助显示设置

- **WHEN** 页面使用窄屏、分屏、大字体、长文案或深色模式
- **THEN** 广告标识、披露元素、媒体和 CTA 不被裁切或遮盖，文本对比度及可读性满足接入验收

#### Scenario: 无填充和加载占位

- **WHEN** 卡片仍在加载或收到无填充
- **THEN** 宿主可以显示占位或收起广告区域，正文仍可正常使用；失败不触发无限自动请求

### Requirement: 缓存只服务当前卡片且按平台验证有效性

系统 SHALL 仅持有当前独立卡片所需广告，不建立全局池、不预取下一条、不定时替换已显示广告。同一对象 SHALL 不同时挂载或注册到两个容器，释放后不得再用。AdMob 待首次展示或等待恢复的缓存 SHALL 使用单调经过时间计龄，达到一小时后丢弃并仅在资格满足时重载；该规则 SHALL 不成为显示中卡片的每小时刷新。TopOn SHALL 使用实际版本支持的有效性/就绪依据，不将取出时间等同网络加载时间，也不套用 AdMob 寿命。

#### Scenario: AdMob 待显示对象过期

- **WHEN** AdMob 对象等待展示或恢复已达一小时，即使系统日期被调整
- **THEN** 系统不挂载该对象，释放后按当前资格加载新对象

#### Scenario: 显示中卡片达到一小时

- **WHEN** 卡片保持显示而经过时间达到一小时
- **THEN** 系统不因缓存建议强制定时刷新，也不生成 ad_native_refresh

#### Scenario: TopOn 缓存来源或有效性不明

- **WHEN** TopOn 返回缓存结果或当前版本不足以确认待恢复对象有效性
- **THEN** 系统不伪造加载时间或寿命，只使用可确认有效的对象，并记录该平台采用的释放/重新获取策略

### Requirement: Compose 包装沿用同一页面资源管理

系统 SHALL 通过可选模块提供 Compose 卡片入口，核心 AAR 不强制依赖 Compose 或 Navigation。默认及自定义布局 SHALL 复用 View 核心；普通重组仅同步 active、visible 和最新回调，身份变化才重建，最终释放和 owner 销毁使用相同幂等清理。Preview SHALL 只显示占位，不初始化广告、不发请求、不执行自定义布局工厂。首版 SHALL 不提供任意纯 Compose 素材插槽或列表复用契约。

#### Scenario: 稳定布局下多次重组

- **WHEN** Compose 页面保持相同 Activity、owner、等值 request 和稳定 layout，多次重组并更新回调
- **THEN** 使用同一广告实例，无额外加载，后续状态交付给最新回调

#### Scenario: Preview 和最终释放

- **WHEN** 页面在 Preview 中渲染，或真实页面的 Compose View 最终被释放
- **THEN** Preview 不触发广告及布局工厂副作用；真实页面的最终释放幂等销毁资源，后续回调不再挂载 UI

### Requirement: Native 不进入全屏展示及竞价流程

Native 卡片 SHALL 不占用全屏展示锁、不进入全屏展示机会等待或缓存竞价。单平台 Native SHALL 记录 ADMOB/TOPON mediationMode；显式双 ID 请求 SHALL 执行页面内比价并记录 BIDDING mediationMode 及一次 ad_bid_result，独立于全屏竞价。全局 `Ads.isReady(NATIVE)` SHALL 返回 false；公共全屏展示机会入口收到 NATIVE SHALL 稳定失败 `unsupported_ad_format`，不得映射到任一种全屏广告位。

#### Scenario: Native 卡片存活时创建全屏机会

- **WHEN** Native 卡片已加载或显示，宿主创建合法全屏展示机会
- **THEN** 卡片本身不持有全屏锁，全屏机会按已有契约及当前页面/交互资格判断

#### Scenario: Native 被传入全屏查询或入口

- **WHEN** 宿主将 NATIVE 传给全局缓存就绪查询或通用全屏展示机会入口
- **THEN** 前者返回 false，后者返回 unsupported_ad_format，均不使用全屏 ID 请求或展示广告

### Requirement: Native 交互不得意外触发自动开屏

系统 SHALL 在 Native 打开覆盖层期间抑制新的自动开屏机会，并对浏览器或商店返回所对应的恢复过程提供有界、可结束的交互抑制。覆盖层关闭 SHALL 不等同卡片关闭；卡片整个存活期不能持有全屏锁，单次 click 也不能永久屏蔽以后合法开屏。该行为 SHALL 覆盖已配置的单平台及 Bidding 自动开屏路径。

#### Scenario: 覆盖层打开后关闭

- **WHEN** Native 打开落地覆盖层或 AdChoices，然后收到覆盖层关闭通知
- **THEN** 打开期间不新增自动开屏展示，关闭通知不产生卡片 ad_close，也不直接释放仍有效的卡片

#### Scenario: 从外部页面返回

- **WHEN** 用户点击 Native 后打开浏览器或商店，再返回广告页面
- **THEN** 本次返回不紧接意外自动开屏；交互抑制按已定义的返回/超时边界结束，不永久影响后续独立的合法开屏机会

### Requirement: 事件身份独立且页面加载可追溯

系统 SHALL 复用公共事件出口并区分页面周期、加载请求和广告展示身份：同 ID 的不同实例或新周期具有可区分的周期身份，每次本层加载拥有新的 requestId，同一广告对象恢复挂载保留 sessionId。平台 response/show ID 在可用时 SHALL 被保留；加载阶段延续 requestId 作为 sessionId 的既有口径时，不宣称加载时已产生真实曝光会话。Native position SHALL 使用业务位置及 `_native` 后缀，不能伪装成 `preload_native`。

#### Scenario: 重试与同对象恢复

- **WHEN** 同周期失败后显式重试，或者已加载对象临时隐藏后安全恢复
- **THEN** 重试生成新 requestId，安全恢复保留该广告的 sessionId，两者均可关联原页面周期

#### Scenario: 两个相同 placement 同时上报

- **WHEN** 两个卡片使用相同 placement 和 position 并交错收到事件
- **THEN** 事件可区分各自周期、请求及广告，不以当前全局卡片或相同 placement 猜测归属

### Requirement: 加载及未曝光终态只收口一次

系统 SHALL 在合法广告位进入有效页面周期时上报一次 ad_position；每次真实本层加载 SHALL 对应一次 ad_load_request 和一次终态 ad_load_result。取消在途请求 SHALL 按取消原因收口，不伪装成无填充；曝光前加载/渲染失败或有效周期结束仍未曝光 SHALL 对对应尝试最多上报一次 ad_show_fail，已曝光后正常离开不报失败。

#### Scenario: 在途请求取消后又成功

- **WHEN** 页面结束时当前请求尚未完成，平台随后回调成功
- **THEN** 该请求只保留一次取消终态，迟到成功不补发第二个加载结果、不伪造曝光；旧对象按归属释放

#### Scenario: 曝光前失败及曝光后离页

- **WHEN** 某广告加载/渲染失败或周期结束仍无曝光，另一广告已曝光后正常离页
- **THEN** 前者按对应尝试只报告一次 ad_show_fail，后者只清理资源，不因离页补发展示失败

### Requirement: 曝光点击和关闭保持平台真实语义

ad_impression SHALL 以平台明确回调为准，对同一对象按平台语义去重；ad_click SHALL 保留合法多次点击，不一律只保留首次。ad_close SHALL 仅来自可确认的卡片关闭动作，页面释放、暂停及广告覆盖层关闭不能冒充卡片关闭。首版 SHALL 不新增关闭按钮；TopOn 模板存在可靠关闭回调时，关闭 SHALL 释放卡片且当前周期不自动重新出现。

#### Scenario: 重复曝光与多次合法点击

- **WHEN** 同一广告重复收到相同曝光通知，并先后收到不同的合法点击
- **THEN** 重复曝光按对象去重，合法点击分别上报，不用一次性点击标记吞掉后续点击

#### Scenario: 模板卡片被关闭

- **WHEN** TopOn 模板回调确认用户关闭卡片
- **THEN** 上报一次卡片关闭并释放，当前周期不因恢复、测量或 retry 自动重新出现

### Requirement: 收益使用真实平台数值并与原广告绑定

系统 SHALL 通过现有 ad_paid 和收益 payload 出口分别交付并去重，保留金额、币种、精度、来源、平台标识及原始收益信息。AdMob SHALL 使用 valueMicros，TopOn SHALL 使用 Native 实际收益监听交付的 impression revenue，不以 eCPM 补实际收入。收益与曝光 SHALL 独立处理且不假定顺序；合法零金额可交付，非法金额不得伪造修正后上报。已清理卡片后到达的有效旧收益 SHALL 仍按原轻量身份交付全局出口，不更新旧 UI，不读取新广告 session；缺少可靠身份时 SHALL 记录归因缺失而不猜测。

#### Scenario: 收益先于曝光且金额为零

- **WHEN** 平台先发送合法零金额 paid，再发送曝光，或重复发送同一 paid
- **THEN** 合法收益不等待曝光，事件和 payload 各按收益身份只交付一次，曝光单独处理

#### Scenario: 旧广告收益迟到

- **WHEN** 广告 A 已释放、广告 B 已成为当前对象，A 的有效收益回调随后到达
- **THEN** 收益仍归 A，不读 B 的当前会话、不恢复 A 的 UI；系统不承诺平台在 destroy 后一定继续发送回调

#### Scenario: 收益监听缺失或数值无效

- **WHEN** TopOn Native 未接通真实 paid 监听、仅有 eCPM，或回调金额/身份无法合法使用
- **THEN** 不捏造收益或归给当前卡片；记录缺失或无效原因，未接通 TU paid 时不能宣称 TopOn Native 完整支持

### Requirement: 新格式不改变既有全屏配置和依赖契约

系统 SHALL 保留既有全屏初始化 ID 要求、立即展示和等待展示行为及收益出口，不要求宿主为旧全屏用法配置 Native ID。核心 SHALL 保持当前广告 SDK 版本及 legacy GMA 排除规则，不因 Native 混入旧 GMA SDK。接入说明 SHALL 提示新增公共枚举及公开数据结构变化的兼容影响，并分别报告编译、R8、设备、视觉及广告来源验证范围。

#### Scenario: 旧全屏宿主升级

- **WHEN** 宿主保留原有全屏配置且不创建 Native 卡片
- **THEN** 原初始化和三种全屏格式继续按既有契约运行，不因缺少 Native ID 失败，也不强制引入 Compose

#### Scenario: 仅完成编译或某一广告来源验证

- **WHEN** Native 仅通过编译/R8 构建，或者仅一部分 TopOn Native 来源取得真实填充
- **THEN** 验证记录分别列明已覆盖与未覆盖项，不把构建成功或全屏填充成功宣称为全部 Native 来源、生命周期和视觉验收通过

### Requirement: 一个页面请求完成原生比价和渲染

系统 SHALL 保留单平台调用，并支持同一 NativeRequest 提供两个命名 ID。系统 SHALL 在有界期限内并行加载，比较实际持有且有效的候选；USD 单次价格统一后复用已有选择器。已知非负价格优先于未知，同价或均未知固定选 AdMob；无广告 SHALL NOT 作为零价候选。系统 SHALL 交付同一获胜对象，并缓存仍有效且未渲染的落选候选，其余对象释放，业务无需再次取缓存或手动切换容器。

#### Scenario: 一端无填充或超时

- **WHEN** 一端已取得有效对象，另一端失败、不可用或达到请求期限
- **THEN** 已取得的对象可胜出，未知价格不阻止展示，迟到失败端对象必须释放

#### Scenario: 页面在比价期间销毁

- **WHEN** 加载事件回调同步导航或销毁卡片，或比价尚未结束时页面失去资格
- **THEN** 停止后续请求、计时及订阅，释放本次仍持有的候选，不挂载迟到对象；已经交给共享缓存的落选对象保留

#### Scenario: 模板与业务布局不兼容

- **WHEN** TopOn 返回模板，而业务指定 Custom 或未提供模板比例
- **THEN** 该候选失败并释放，其他兼容候选仍可参与比价

#### Scenario: 比价事件和收益归因

- **WHEN** 双 ID 请求完成选择并由获胜对象触发平台回调
- **THEN** 每端保留独立加载 requestId，一次 bid_result 包含候选可用性和价格可用性，后续曝光/点击/收益使用获胜候选身份；ILRD 只来自 paid 回调

### Requirement: 未展示的落选对象可跨 Activity 复用

系统 SHALL 在不增加业务缓存 API 的前提下，按平台/广告位保留有限数量的未渲染落选对象。缓存 SHALL NOT 持有旧页面回调或业务 View；取出 SHALL 立即转移独占所有权，重绑当前候选回调，并在当前 Activity 中创建渲染容器。已展示对象 SHALL NOT 重新入池。缓存到期、SDK 判无效、容量淘汰或许可撤回时 SHALL 释放资源。

#### Scenario: 原加载 Activity 已销毁

- **WHEN** Activity A 的候选落选并入缓存，随后 A 销毁，Activity B 请求兼容的相同平台/广告位
- **THEN** B 可独占取用同一有效对象，广告展示/点击/收益归本轮身份，旧页面不再收到 UI 事件

#### Scenario: 模板尺寸变化或同时双卡片

- **WHEN** B 的模板请求尺寸不兼容，或另一个卡片已经取出了缓存对象
- **THEN** 本卡片按需加载新对象，不共用一个广告或强行重挂旧 View
