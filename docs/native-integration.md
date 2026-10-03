# 页面 Native 卡片接入

此入口属于当前工作树，尚未发布到 README 的稳定版 1.0.5。继续使用现有 `Ads.initialize` 和 UMP 配置，Native 广告位 ID 在初始化配置中传入；只接 Native 时可省略全屏 ID，应用 ID 和 TopOn App Key 等所选平台的必需凭据仍需提供。Compose 与 View 入口都包含在 `ads-mediation` 中。

## 双平台比价：业务只持有一个卡片

应用在 `AdMobIds.nativeId` 和 `TopOnIds.nativePlacementId` 中配置 Native ID，并按既有 `BiddingProviderConfig` 初始化 AdMob 和 TopOn；页面不传平台或广告位 ID。View 与 Compose 共用这一请求，不需要业务管理候选缓存、比较价格或再取一次获胜广告：

```kotlin
val request = NativeRequest(
    position = "home_card",
)
val card = AdsNativeView(
    activity = requireActivity(),
    lifecycleOwner = viewLifecycleOwner,
    request = request,
)
container.addView(card)
```

初始化配置两平台 Native ID 才开启比价；竞价模式只配置一个 Native ID 时只使用该来源。单平台模式只使用对应平台配置。未配置 Native ID 返回 `native_not_configured`，不会兜底使用测试 ID。`AdMobIds.TEST` 包含官方 Native 测试 ID；生产配置必须使用自己的 ID。

页面统一使用 `NativeRequest(position = "home_card")`，仅可调整模板比例和竞价等待时间。旧 `platform/adUnitId/admobAdUnitId/topOnPlacementId` 参数已删除，不保留覆盖入口；现有接入需要把 ID 移到初始化配置。业务 `position` 用于位置唯一性和事件，不是实际广告位 ID。

```kotlin
val provider = BiddingProviderConfig(
    admob = AdMobProviderConfig(admobIds.copy(nativeId = admobNativeId)),
    topon = TopOnProviderConfig(topOnIds.copy(nativePlacementId = topOnNativeId)),
)
Ads.initialize(application, AdsConfig(provider = provider))
Ads.preloadNative()
```

`Ads.preloadNative()` 预热初始化配置中的自渲染库存，可在 `initialize` 后立即调用。它独立等待 UMP 允许、应用前台和每个平台 READY，不占用 `observeInitialization` 的单一观察者，也不等待另一平台初始化成功。重复调用合并已有库存，不创建 View、不产生曝光。没有页面需求时沿用五分钟前台闲置及后台关闭；TopOn 模板仍由携带实际尺寸和比例的页面请求准备。

页面命中未渲染保留区时也会建立库存需求：已闲置关闭的 SDK 会话重新启动，仍存活的会话保活；当前缓存对象继续立即交付，页面释放时结束需求。

smoke 来源通过构建参数 `-PnativeSmoke=true -PnativePlatform=admob|topon|bidding` 在 Application 初始化时选择；TopOn ID 来自既有 `nativeTestConfig`。Activity 不再读取 `platform/placement/bidding` 来覆盖广告请求。

每端先独占取出兼容的缓存候选，未命中时在就绪后并行加载。默认最多等待 7 秒（`bidTimeoutMillis` 可调）；另一端初始化未完成也占用这一期限。一端失败、未配置或超时，有可用候选即可展示；两端都无可用对象则 Failed。选择后直接交付同一个获胜对象，将有效、未渲染的落选对象放入跨 Activity 缓存。页面销毁或隐藏取消本轮等待；已取得但尚未渲染的有效候选可保留，提供方取消后无法安全交付的 SDK 迟到对象释放。已进入保留区的对象不归旧页面销毁。

需要优先展示现有库存的位置可设置 `NativeRequest(preferCachedAds = true, position = ...)`：先同步领取两端兼容、有效的缓存（包括 SDK 预加载库存及未渲染保留区），两端都有就立即比价，只有一端有就立即展示；两端均无缓存才沿用限时竞价。未命中端已启动的库存准备继续运行，不阻塞本次展示。默认仍为 `false`，HealthTracker 仅在退出弹窗启用。日志中的“备用库存可领取”描述尚未领取的库存；领取后暂时为空不代表本次展示失败，领取动作会单独记录。

比较口径为 **USD/次展示**。TopOn 展示前使用 `getEcpm(USD) / 1000`，读取失败、负数或非有限值记为未知价格；`getPublisherRevenue()` 不参与比价。AdMob 使用锁定 GMA Next-Gen 1.2.1 的已加载 Native 对象价格路径，按配置读取 micros 和 USD 币种；版本、字段或币种不匹配即未知价格，不猜值。已知有效价格优先于未知；真实 0 仍是有效价格；同价或均未知时固定选 AdMob。无填充不会作为 0 价候选。报价仅用于选择，收入仍由平台 paid 回调上报，不能把报价当作 ILRD。

自定义布局不接收 TopOn 模板；默认布局未给出已确认的 `topOnTemplateAspectRatio` 也不接收模板。这些不兼容模板在比价前释放，另一端可继续获胜。自渲染的素材完整性仍在绑定时校验，渲染失败会明确 Failed。无填充、加载/竞价超时、无候选、一般加载/渲染异常在页面仍合资格时按 2/4/8 秒最多额外重试三次；隐藏、失焦、许可失效或销毁取消定时器。配置、布局及模板兼容错误不自动重试。成功加载或业务页面重新激活恢复自动重试预算，重复布局通知不重置预算；业务也可在修正问题后显式 `retry()`。

缓存由库自动管理：每个平台/广告位最多 1 条，总计最多 4 条；超出时释放最早保留的一条。只有尚未渲染的对象可缓存，已经展示的卡片不会重新出售。AdMob 使用原始加载时刻的一小时期限；TopOn 取用前检查 SDK `isValid`，并从首次入区起设置最长一小时的本层保留上限；跨页、领取后再次入区均不续期。TopOn 领取后仍由原句柄携带该期限，竞价等待期间和首次渲染前继续检查，不能因离区而重新获得一小时。该上限不是 TopOn 广告的虚构加载时间；期限未知的 AdMob 对象不入区。到期及许可撤回主动清理。

加载阶段不捕获业务 Activity：TopOn 使用 Application Context，AdMob Next-Gen 的 loader 本身不接收 Activity。缓存期间解绑旧回调；取出后安装本轮回调，以当前 Activity 新建容器及布局。模板还需匹配加载宽度与已确认比例，不能把 A 页已渲染的 View 移到 B 页。单平台请求也可消费同广告位的落选缓存。页面获取保留独立 requestId/sessionId；缓存命中与恢复不产生 LOAD_REQUEST/LOAD_RESULT。库存准备事件使用 preload_native，不回填业务 position；持续预加载的 SDK 自动补货不伪造本层网络请求，SDK 未提供的逐对象加载身份保持未知。

许可失效先递增本层库存代次；即使后来恢复允许，旧请求迟到的候选也不能重新入区。TopOn 同 placement 的 SDK 缓存在不同 `TUNative` 实例间共享，因此请求同时在 `localExtra` 标记许可代次。准备/领取前用锁定 SDK 的 `clearCache(List<TUAdInfo>)` 选择清理本库标记的旧对象，领取后再核对实际对象标记；不无界清空共享缓存、不声称取消 SDK 在途网络。库存从 `checkValidAdCaches()` 中确认存在许可代次和库存会话匹配的候选，再按 SDK 默认顺序领取并验证实际返回对象；不依赖 `TUShowConfig.Builder.adInfo(candidate)` 指定缓存，该路径在锁定 SDK 的真机验证中仍可能返回其他队首。无标记或其他会话的已领取对象释放后继续尝试，每轮最多领取当前缓存条数且不超过四条，不因后续补货无限清空共享队列；合法对象使用自身价格参与比价。未能取得合法对象时撤销库存就绪状态，沿用 2/4/8 秒最多三次退避；SDK 加载成功不重置失败预算，实际成功领取或显式恢复才重置。旧成功回调没有当前代次候选时，退出 SDK 回调栈后只刷新一次；取消会移除该任务，仍无法关联则明确失败，不套用本次请求的许可。

TopOn 文档的构造参数接受 Context 并建议 Activity；Pangle 明确要求渲染容器使用 Activity。本实现参照 remax 的 Application 加载方式，实际跨 Activity 展示能力仍须逐来源验收，不能由 `isValid` 或编译通过替代。测试入口 `NativeCacheTransferTest` 检查 A 加载→保留对象→A 销毁→B 命中同一个对象→真实曝光，以及旧回调不再收到曝光。详见验证记录。

实现参考 [remax NativeBiddingManager（固定版本）](https://github.com/toukaRemax/remax_sdk/tree/83aecfbd9921b75073f761cf5797b7d076efed30)，复用本库已有比价选择器及事件出口；不引入新的广告 SDK 或图片库。

## View 和页面生命周期

```kotlin
val request = NativeRequest(
    position = "home_card",
)
val card = AdsNativeView(
    activity = requireActivity(),
    lifecycleOwner = viewLifecycleOwner, // Fragment 的 View owner
    request = request,
    active = true,
    visible = true,
    onStateChanged = { state ->
        // Loaded 只表示加载/渲染完成；真实曝光来自 AdEventListener。
    },
)
container.addView(card)

// 业务页面真正离开，即使 Activity 或 Fragment 仍在返回栈中：
card.setActive(false)
// 返回时恢复资格；按策略恢复原对象或开始新获取：
card.setActive(true)
// 失败且当前具备展示资格时才生效：
card.retry()
// 容器最终释放；owner 销毁也会触发此幂等操作：
card.destroy()
```

以上 API 在主线程调用。`update(active, visible, onStateChanged)` 可一次同步资格与最新回调，避免先 active=true、后 visible=false 的中间状态误加载。永久 destroy 不能复活；应创建新 View。

请求须同时满足所选平台已初始化、UMP 允许、owner RESUMED、业务 active/visible、容器附着、有宽度和窗口焦点。单平台请求不会自动换平台；双 ID 请求独立检查两端状态，不把全局 Bidding READY 当成两端均已就绪。未配置、许可拒绝及平台失败有明确 Failed 原因。

Dialog、短时 detach、后台和 visible=false 不代表页面永久结束。`retentionPolicy` 默认 `DESTROY_ON_HIDE`，隐藏释放、返回重新获取。可显式选择 `RETAIN_WHILE_PAGE_ALIVE`：仅在来源已验证能安全暂停/恢复时保留原 SDK 对象、平台 View 与展示会话，恢复不重新注册。**当前仅支持公开素材明确为 IMAGE、无视频且适配器为 `com.google.ads.mediation.admob.AdMobAdapter` 的 Google 图片广告保留**。三星 API35 的生产路径已验证同平台 View 隐藏/恢复、无重新加载及最终销毁；视频、UNKNOWN、其他 AdMob 适配器和 TopOn 仍明确降级释放，不能把选了该策略理解为已经支持真实保留。

`position` 是展示记录的唯一键。同时存在的卡片必须使用不同的 position（即使使用相同广告位）；第二个容器占用相同位置返回 `native_position_occupied`，不会夺走第一个卡片。记录关联原 Activity、owner、request、layout 和策略，不能将已展示对象迁移到另一 Activity。最终 `destroy()`、owner 销毁及 Activity 重建均释放；迟到的旧连接清理不影响新连接。

每实例最多一条本层有效在途请求；取消不保证平台网络或 TU 内部缓存已取消。两个同 ID 卡片也必须各自创建 View，不能共享广告对象。TopOn 同 placement 可能合并底层加载，导致多个成功通知仅有一条可取库存；取不到对象的卡片会明确失败，不保证同时双填充。

## 自定义 View/XML 布局

使用稳定的 `NativeLayout.Custom`，每次工厂调用 inflate 一份全新布局。工厂仅返回素材位置，不接收原广告、注册点击、上报曝光或处理收益。示例中的资源 ID 由宿主自己的 XML 定义：

```kotlin
val cardLayout = NativeLayout.Custom { context ->
    val root = LayoutInflater.from(context).inflate(R.layout.my_native_card, null, false)
    NativeLayoutBinding(
        root = root,
        headline = root.findViewById(R.id.title),
        callToAction = root.findViewById(R.id.cta),
        media = root.findViewById(R.id.media_container),
        adLabel = root.findViewById(R.id.ad_label),
        adChoices = root.findViewById(R.id.ad_choices_container),
        body = root.findViewById(R.id.body),
        advertiser = root.findViewById(R.id.advertiser),
        icon = root.findViewById(R.id.icon_container),
        // 按启用来源提供这些位置；SDK 返回的来源、domain、warning 必须展示。
        adFrom = root.findViewById(R.id.ad_source),
        domain = root.findViewById(R.id.ad_domain),
        warning = root.findViewById(R.id.ad_warning),
        advertiserInfo = root.findViewById(R.id.advertiser_info),
    )
}
```

root 不能已有 parent；引用必须属于 root，素材位置不得重复，媒体、图标及 AdChoices 占位必须为空。广告标识须有清晰可见文字。工厂异常、无效结构或不完整创意明确失败，不展示部分广告。布局由 Activity 主题 Context 创建；不根据 position 选模板，position 同时用于归因与展示所有权，不决定布局。

需要按素材选择样式时使用命名工厂；旧 `Custom { context -> ... }` 构造和 `factory` 继续保留：

```kotlin
val cardLayout = NativeLayout.Custom.withAssets { context, assets ->
    // 返回全新 View 树；素材绑定、注册和披露仍交给库。
    createCardBinding(context, useVideoLayout = assets.mediaType == NativeMediaType.VIDEO)
}
```

`NativeAssets` 不携带 Activity、View 或 SDK 对象，也不授予展示/点击权限。模板广告不调用自定义工厂。缺失文案为 null，未识别媒体为 UNKNOWN，比例仅使用有限正数。

| 快照字段 | AdMob 1.2.1 | TopOn 6.6.22.3 自渲染 |
| --- | --- | --- |
| headline/body/callToAction/advertiser | NativeAd 同名文本 | 素材 title/description/callToAction/advertiserName |
| mediaType | 明确 hasVideoContent 为 VIDEO；有真实 mainImage 为 IMAGE；否则 UNKNOWN | 明确 IMAGE_TYPE/VIDEO_TYPE；其余 UNKNOWN |
| mediaAspectRatio | MediaContent.aspectRatio | 对应图片或视频的有效宽高；未知类型不猜 |
| adFrom/domain/warning | null | 同名素材字段 |

TopOn 模板提供空快照；这些字段只反映 SDK 已提供的数据，不能用零视频时长推断图片。

TopOn 返回的标题、描述、CTA 为空时隐藏对应 View；图标优先使用 SDK View，随后使用 URL。可选 `adFrom`（可退回 advertiser）、domain、warning、advertiserInfo 和 close 用于对应来源素材及 SDK 操作；已有自定义布局启用这些来源时须补齐槽位。媒体 View、图标和 CTA 的 SDK View 由库挂载与注册，业务无需接触它们。

默认卡片和自定义布局使用同一绑定流程。Google 使用 SDK 自动 AdChoices overlay，不依赖 allow-list 专用的自定义披露 API，布局仍须保留右上角披露空间。提供媒体槽位时，媒体必须满足平台真实比例及视频要求，Google 视频至少 120 × 120 dp；不将该下限当成所有媒体的固定尺寸。注册前会测量填充后的 SDK 媒体 View，包含宿主和媒体容器 padding 的影响；不足时返回 `native_video_media_too_small`。后续布局缩小也会隐藏并释放该卡片，修正空间后可显式 retry。实际素材比例由 SDK MediaView 呈现，宿主仍须检查完整展示。宿主 Activity 需开启硬件加速。依据：[Google Native 尺寸要求](https://support.google.com/admob/answer/6329638)、[MediaContent](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/nativead/MediaContent)。

自定义布局禁止给广告内部、外围空白或背景加点击处理器。可选文字缺失时清空隐藏；平台必需素材不完整将失败。布局结构校验无法证明没有视觉遮挡，接入仍需验证大字体、深色、长文案、窄屏、AdChoices 及点击范围。

## TopOn 渲染边界

自渲染布局的 `media` 可为空：库不获取、创建或注册媒体 View，不根据来源或媒体类型阻断其余素材绑定；标题、正文、图标、CTA、广告角标及 render/prepare 继续执行，不创建隐藏媒体。AdMob 同样允许空媒体槽位，视频尺寸检查仅针对实际提供的 MediaView。

这是库的可选槽位行为，不代表所有广告来源允许省略媒体，也不保证 SDK 曝光；接入方仍须核对启用来源的素材展示规范。Google 视频媒体规范要求媒体区域至少 120 × 120 dp，合规的视频布局应提供媒体槽位；有槽位时库继续执行尺寸检查。模板仍由 SDK 排版，不适用自渲染槽位规则。

提供了媒体槽位时使用 SDK MediaView 优先，其次为多图列表，再退回静态主图；均无有效素材仍返回 `native_media_missing`，SDK 返回容器自身仍返回 `native_media_invalid`。省略槽位不用于掩盖已请求展示媒体的素材错误。

历史证据：2026-09-30 三星探针返回 `networkFirmId=50`、`adType=UNKNOWN(0)`、视频时长 0、视频尺寸 -1×-1，当时无媒体限制导致 `native_media_requirement_unknown`。2026-10-01 用户明确修订为无槽位只跳过媒体，该旧限制已移除；仍不以缺少时长/尺寸推断图片，历史失败不作为新行为结论。

初始化使用 `TopOnProviderConfig` 并配置 `nativePlacementId` 后，`NativeRequest(position)` 只使用 TopOn。`NativeLayout.Custom` 要求返回自渲染广告；模板结果返回 `unsupported_native_render_mode` 并释放，不忽略布局、不自动循环请求。

默认入口可接收平台模板；此时排版由平台决定，须在请求中提供已确认的 `topOnTemplateAspectRatio`（宽 / 高）。未知比例返回 `native_template_size_unknown`；不要用猜测尺寸裁切展示。模板的有效内容宽度（外层宽度扣除水平 padding）改变时会结束当前代次并重新请求，相同测量不重载；加载期间改变 inset/padding，也会在挂载前重检，旧尺寸结果不能挂载。

TU 的媒体/图标 View、真实收益监听和来源差异以锁定依赖及来源实测为准，优先使用 SDK 提供的媒体/图标/披露 View；仅提供 URL 或披露 bitmap 时使用 TU 自带图片组件回退，不创建自有图片下载器。提供媒体槽位而平台连必要媒体素材都未提供时明确失败；回退存在也不能代替逐来源视觉验收。每个启用来源分别验收，详见验证记录。

TopOn 自渲染默认卡片在 SDK 返回有效大图宽高时，按媒体区实际内容宽度计算高度，随宿主宽度变化重新测量，支持横图、方图和竖图。未提供有效宽高时保留默认 180dp 媒体区，不将猜测比例当成素材比例；自定义布局继续负责自己的媒体高度。SDK 返回的媒体 View 会统一重新挂载并填满媒体容器，包括 SDK 已自行挂载的情况。依据：[TopOn 素材 API](https://help.toponad.net/cn/docs/yuan-sheng-guang-gao)、[官方自渲染示例](https://github.com/toponteam/TPN-Android-Demo/blob/main/app/src/main/java/com/test/ad/demo/SelfRenderViewUtil.java)。这些尺寸处理不能单独证明某个来源的空白素材已修复。

按 [TopOn Native 素材规则](https://help.toponad.net/docs/Native-Ad-koMu)，返回的描述文字必须展示；缺少 body 占位返回 `native_body_required`。为满足 [Meta 等来源的图标要求](https://help.toponad.net/cn/docs/native_ad_platform_notice)，返回 SDK 图标 View 时必须提供 icon 占位，否则返回 `native_icon_required`。没有相应素材时仍允许省略这两个位置。默认卡片及两套 smoke XML 均提供它们。

## Compose

依赖 `ads-mediation` 后即可使用 Compose 入口：

```kotlin
val layout = remember { NativeLayout.Custom { context -> createCardBinding(context) } }
AdsNative(
    request = request,
    layout = layout,
    lifecycleOwner = backStackEntry, // 当前具体 NavBackStackEntry
    active = isCurrentBusinessPage,
    visible = showCard,
    retryToken = retryCount, // 用户重试时递增，不在普通重组中改变
    onStateChanged = { state -> nativeState = state },
)
```

Activity、owner、等值 request、layout 对象和 retentionPolicy 组成配置身份。普通重组及回调更新不重建；实际身份改变释放旧 View。Preview 只画占位，不执行布局工厂或广告初始化。首版不提供 LazyColumn/RecyclerView 复用或纯 Compose 素材插槽；在页面布局中使用独立卡片。

`onRelease` 在销毁策略下结束展示；保留策略只有在传入持续存活的页级 owner（如具体 backStackEntry）时，才允许分离旧外层 View/业务回调、随后用新外层恢复原平台子 View。仅 Activity owner 的卡片退出组合会销毁，因为 Activity 不能判断业务页面是否仍存活。`if (showAd)` 移出组合在保留策略下表示暂离；永久结束应销毁 pageEntry/Fragment view owner。持续组合中的 Tab 可用 active/visible 控制，onReset 仍为 null，不支持列表复用。当前未知来源的暂停/恢复仍降级释放。

## 事件、收益与全屏

- `AdFormat.NATIVE` 区分广告格式，`slotId` 字段保留但改为原始业务 position（无后缀），不再生成页面 UUID；每次请求有 requestId，同一广告对象有自己的展示 sessionId。加载事件延续 requestId 作为加载 sessionId 的原口径，不能当成曝光会话。
- position 使用业务位置的 `_native` 后缀，页面加载不是全局 preload。每个有效周期一次 ad_position、每次本层加载一对 request/result；取消有独立原因，曝光前失败按尝试去重。
- 曝光/点击以平台回调为准；Loaded、挂载和可见度不伪造曝光。合法多次点击保留，页面释放和覆盖层关闭不伪装成 ad_close。
- 沿用 `AdEventListener` 与 `AdRevenueListener`。单平台请求的 mediationMode 为 ADMOB/TOPON；双 ID 请求为 BIDDING，每端有独立获取 requestId；SDK 库存准备的加载事件与页面获取分开，在最终取得有效胜出对象后产生一次 ad_bid_result，记录候选可用性、价格可用性和胜出平台。后续曝光/点击/收益沿用获胜候选的 requestId、平台和广告位 ID。paid 与 impression 不要求先后；合法零收入保留，旧广告迟到收益仍按原身份归因，平台销毁后是否继续发回调不作保证。
- Native 不占全屏锁、也不参与全屏等待/竞价。`Ads.isReady(NATIVE)` 恒为 false，查询卡片自己的 state。已启用的旧自动开屏路径有 Native 交互抑制；手动页面机会仍通过 `isSceneValid` 表达当前业务是否允许全屏，覆盖层期间不要主动创建机会。
- 自动开屏统一排除锁定广告 SDK 的 Activity，并在最终调度前再次检查；Pangle 内置浏览器从 Chrome 返回不被当作业务展示宿主。普通点击窗口仍为5秒，覆盖层缺少关闭回调的兜底仍为120秒，不靠延长期限屏蔽以后合法机会。此过滤只作用自动路径，不代替手动入口的场景资格。

手动页面机会示例（在页面主线程使用，`owner` 是 Fragment View 或具体导航 entry 的 owner）：

```kotlin
var pageActive = true
var dialogVisible = false
var manualAllowed = false
var pending: AdDisplayOpportunity? = null

fun cancelManualOpportunity() {
    manualAllowed = false
    pending?.cancel()
    pending = null
}

fun sceneValid() = pageActive && !dialogVisible &&
    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
    activity.hasWindowFocus()

// 接到既有 AdsConfig.eventListener 分发中，保留原有分析事件处理。
// 这是 SDK 点击通知，不给广告素材安装宿主 click handler。
val onNativeEvent = AdEventListener { event ->
    if (event.format == AdFormat.NATIVE && event.name == AdEventName.CLICK) {
        cancelManualOpportunity()
    }
}

// 只由下一次独立的业务操作触发，例如用户完成页面任务。
fun onBusinessActionCompleted() {
    cancelManualOpportunity()
    if (!sceneValid()) return
    manualAllowed = true
    pending = Ads.showInterstitialWhenReady(
        activity = activity,
        position = "task_complete",
        isSceneValid = { manualAllowed && sceneValid() },
    )
}
```

页面暂停、离页、打开业务 Dialog 或销毁时调用 `cancelManualOpportunity()`；同时更新 `pageActive` / `dialogVisible`。`onResume` 和 Native 落地页返回时不自动调用 `onBusinessActionCompleted()`，所以本次返回不会紧接手动全屏；下一次合资格的业务操作才建立新机会。宿主在销毁时也须移除对页面的事件转发。已交给 SDK 展示的全屏不能靠取消句柄撤销；`isSceneValid` 在交接前持续检查资格。
- 公共枚举新增项会影响宿主穷举 when；`AdEvent` 尾部新增可选 slotId，不据此宣称所有已编译调用方都二进制兼容。升级时同步编译宿主。

## 失败与验证

可运行的导航示例在 [NativeSmokeActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/NativeSmokeActivity.kt) 的 `NativeSmokeNavigation`：`NavHost` 将当前具体 entry 传给卡片，进入详情使原页面 inactive，返回重新创建 View；Dialog 保持业务周期，仅由 entry 的 STARTED 状态和窗口失焦暂停展示。Navigation 2.8.7 只加入 smoke 宿主，核心及 Compose 库不依赖它。示例遵循 [Android Navigation 的宿主和返回栈契约](https://developer.android.com/guide/navigation)。

构建 Native smoke 后可启动 `NativeSmokeActivity`，传入 `mode=nav`、`platform=admob`，使用官方测试广告操作 Details/Return 和 Dialog/Close。`active=false`、`visible=false` 可分别验证初始资格；`twoCards=true` 验证相同广告位的独立 View。`mode=view` 和 `mode=compose` 保留原接入示例。`retention=destroy|retain` 选择策略；`layout=assets` 演示 withAssets 命名工厂。当前 retain 仅支持上述 Google 图片条件，其余来源降级；不能以新外层再次 Loaded 作为恢复同广告通过。

### TopOn 模板与自渲染分开验证

标准模板入口使用 `platform=topon, layout=default`，通过已确认的 `templateRatio` 或受保护测试配置中的同名值传递宽/高比。核心库按卡片的实际内容宽度计算请求和展示尺寸，不使用屏幕宽度。官方要求请求宽高与模板容器一致，模板比例取自后台配置：[TopOn Native 集成建议](https://help.toponad.net/docs/Native-Ad-koMu)。

HealthTracker 旧提交 `a856c1fe` 使用过固定 4:1。为验证这条历史路径，Native smoke **Debug** 提供显式 `templatePreset=healthtracker-4x1`；它覆盖测试配置中的比例，禁止与 `templateRatio` 同时传入，并且只允许 TopOn 默认布局。页面持续标记 `CANDIDATE (not backend-confirmed)`，布局切换按钮禁用。没有该参数时原有未知比例错误保持不变；Release 拒绝此诊断预设。它不是生产默认值，也不代表当前后台比例已确认。

```sh
# 仅验证历史 4:1 候选；模板实际填充与画面仍须分别确认。
adb -s DEVICE_SERIAL shell am start -S \
  -n com.cashcraft.ads.mediation.smoke.native/com.cashcraft.ads.mediation.smoke.NativeSmokeActivity \
  --es mode view --es platform topon --es layout default \
  --es templatePreset healthtracker-4x1

# 独立自渲染用例，不使用历史模板尺寸；compact 可另开一轮。
adb -s DEVICE_SERIAL shell am start -S \
  -n com.cashcraft.ads.mediation.smoke.native/com.cashcraft.ads.mediation.smoke.NativeSmokeActivity \
  --es mode view --es platform topon --es layout custom
```

两条入口只选择宿主渲染路径，**不会强制后台返回指定渲染类型**。有已确认的独立测试广告位时可分别传 `placement`；现有混合广告位返回其他类型，应记录“目标类型未覆盖”。自定义入口收到模板仍为 `unsupported_native_render_mode`，不改用模板、不自动循环请求。默认入口收到自渲染也不能计为模板成功。

新增 `NativeSmokeSizingTest` 可逐方法运行：`testSizingOptionsKeepHistoricalCandidateExplicit` 检查预设、原配置及不兼容参数；`testLiveTopOnTemplateCandidateDimensions` 才发出一次真实 TopOn 请求，检查模板容器的布局参数/实测宽高、一次加载及隐藏清空。后者返回非模板时明确失败为目标样本未覆盖，保存 `topon-template-candidate-*.png` 到 smoke 的 files 目录，并输出不含广告位或凭据的 `NativeSizing` 日志。尺寸测试通过仍需人工审图，不能替代素材、点击区域、视频或后台音频验收。

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.cashcraft.ads.mediation.smoke.NativeSmokeSizingTest#testSizingOptionsKeepHistoricalCandidateExplicit' \
  com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.cashcraft.ads.mediation.smoke.NativeSmokeSizingTest#testLiveTopOnTemplateCandidateDimensions' \
  com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner
```

真实 AdMob 生命周期检查可单独运行 `NativeLiveLifecycleTest`。它使用官方测试 ID，需要设备联网及 SDK 成功填充；覆盖 visible/父容器隐藏、Dialog、详情返回、同位双卡片、旋转和加载中销毁，不点击广告。旋转测试会恢复宿主原方向设置。构建并安装 Native smoke Debug 与 androidTest 包后执行：

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.cashcraft.ads.mediation.smoke.NativeLiveLifecycleTest \
  com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner
```

断言检查卡片状态、实际子 View 和加载次数；通过不代表视频后台静默、TopOn 缓存、自动开屏或完整视觉矩阵已经验收。

其中 `testGoogleVideoWithoutMediaSlotStillBindsOtherAssets` 使用 [Google 官方 Native Video 测试 ID](https://developers.google.com/admob/android/next-gen/test-ads#demo_ad_units)，通过真实 VIDEO 快照验证无媒体布局仍绑定其他素材，未创建隐藏 MediaView，隐藏后释放。smoke 的 compact XML 本身仍含120dp媒体，此用例在测试工厂中明确移除槽位；不改变业务 XML。当前证据为 Debug/真实曝光及零收益，不代表所有来源无媒体合规或 Release/R8通过；完整截图和边界见当前验证记录。


真实 Fragment View 重建和显式 retry 检查使用官方 AdMob 测试广告，两种策略都覆盖。它先让业务 Custom 工厂明确失败，再恢复工厂并 retry；不将该结果当作 SDK no-fill 或生产来源保留支持。Release 预加载检查把实际 SDK 调用和报价探针保留在被 R8 混淆的目标 APK，覆盖队首查看/取货、自动补货、销毁及 A 销毁后 B 渲染曝光：

```sh
./gradlew :assembleDebugAndroidTest \
  :r8-smoke-app:assembleRelease :r8-smoke-app:assembleReleaseAndroidTest \
  -PnativeSmoke=true -PsmokeTestBuildType=release --offline
adb -s DEVICE_SERIAL install -r build/outputs/apk/androidTest/debug/ads-mediation-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class 'com.cashcraft.ads.mediation.NativeFragmentTest#testRealAdFragmentRecreationAndExplicitRetry' \
  com.cashcraft.ads.mediation.test/android.test.InstrumentationTestRunner
adb -s DEVICE_SERIAL install -r r8-smoke-app/build/outputs/apk/release/r8-smoke-app-release.apk
adb -s DEVICE_SERIAL install -r r8-smoke-app/build/outputs/apk/androidTest/release/r8-smoke-app-release-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.cashcraft.ads.mediation.smoke.NativePreloadR8Test \
  com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner
```

Fragment检查默认仍用官方AdMob测试ID。显式TopOn运行可另传 `-e platform topon`、`applicationId`、`applicationKey`、`nativePlacement`，以及可选的三个全屏placement；测试只开启官方Pangle `Builder(50)` 调试模式。`toponTestDeviceId` 可显式传入，否则在测试线程通过公开Identifier取得，不记录设备ID。该TopOn分支已完成两策略真实布局失败/显式retry/Fragment重建/旧owner销毁，1/1、8.86秒；不同模板宽度与来源保留未通过，不能据该样本声明整项完成。

官方 Google 测试广告仅产生零报价，零价可验证真实库存流程，不阻塞生产接线。真实非零填充仍待后续配置验证，可控非零测试只证明价格算法；Release 构建不替代模板及来源音视频恢复验收。

上述 Release 类覆盖应用内 Activity 切换与真实后台的区分、后台显式关闭、领取触发补货后立即关闭，以及同 key 重建的新队首身份核对；这些历史结果只验证锁定 SDK 的可控机制；当前生产入口已接通共享库存后台清理，新路径设备结果单独记录。后续 HealthTracker 探针通过真实 UMP 调试地域观察请求资格 true→false→true，并驱动已有 Native 就绪通知：旧 TopOn SDK 缓存不能在恢复后交付，新对象带新代次。此为 SDK 资格隔离机制，不是用户点击撤回表单验收，也不证明取消 SDK 网络请求；真实非零报价不再阻塞新预加载生产入口，来源恢复验收仍独立保留。

双平台 smoke 可在上述启动命令加入 `--ez bidding true`；使用已配置的 TopOn 测试广告位及 AdMob 官方测试 ID，日志包含 `winner`、两端可用性和 USD 报价。

常见 reason 还包括 `invalid_native_bid_timeout`、`native_bid_timeout`、`native_no_bid_candidate`、`native_conflicting_platform_selection`；原有 reason 包括 `invalid_ad_unit_id`、`invalid_position`、`invalid_template_ratio`、`sdk_not_initialized`、`native_platform_not_configured`、`native_platform_initialization_failed`、`consent_not_obtained`、`no_fill`、`native_load_failed`、`native_layout_invalid`、`unsupported_native_render_mode` 和 `native_template_size_unknown`。平台错误码在可用时单独保留。

页面失败不自动无限重试；仅当前周期 Failed 且满足资格时 retry 生效。共享库存消费后准备下一条，TopOn 单轮最多额外三次重试；后台取消补货，网络恢复或显式 retry 可重开失败轮次。无填充时可收起区域，不阻塞正文；不定时刷新已经展示的广告。单平台冷库存等待最多30秒，双平台仍遵守请求的比价期限（默认七秒）。

实际完成与未覆盖范围以[当前验证记录](../openspec/changes/refactor-native-ad-loading-and-retention/verification.md)为准；[旧验证记录](../openspec/changes/add-page-native-ads/verification.md)保留历史证据。JUnit、构建/R8、真实 SDK、视觉和来源验收分开记录。测试使用官方 demo ID/明确测试模式，不点击生产广告。


## 本轮实现与能力边界

生产加载入口已接通 AdMob SDK 预加载及 TopOn 共享库存，复用需求合并、消费补货、TopOn 2/4/8 秒退避、五分钟前台闲置与四个闲置 key 上限。两平台先领取实际对象再比价，落选对象进入有界未渲染保留区；不宣称非消费报价后只领取赢家。新 `NativePreloadTransferTest` 只使用官方测试广告，通过公开 API 检查队列变化、取空、自动补货、跨 Activity 展示与图片同对象重新附着；结果及未完成门槛见 [本轮验证记录](../openspec/changes/refactor-native-ad-loading-and-retention/verification.md)。

最新默认业务页已有真实素材/披露、曝光及退出释放证据，但测试直达不是饮水正常导航。独立新对象的后台61分钟观测已通过：本层一小时缓存上限到期阻止旧对象复用，原始Pangle SDK在61分钟后仍有效，两者不是同一TTL结论。Pangle实际多帧和UID播放器补齐局部卸载/重新附着证据，但未静音基线、附着时pause和生产保留链仍未验证；Google视频进度样本未通过。真实默认工厂的局部Material深色/320dp/font1.3，以及Google双卡/旋转/加载取消已补证，完整来源/分屏矩阵仍缺。详见当前验证记录；共享预加载已接通生产入口，来源保留仍降级释放，完整矩阵未通过。

Pangle官方调试Video2新增附着时暂停隔离：调用 `pauseVideo()` / `onPause()` 后实际视频帧仍推进；卸载才移除当前UID播放器，原SDK/View重新附着后虽建立新播放器，恢复截图仍黑屏。仅SDK取消静音未解除样本静音，尚无未静音基线。该结果不外推所有生产Pangle来源，也不作为保留能力通过；当前生产来源继续降级释放。图片与视频必须逐样本检查实际素材、暂停和恢复，不能只看对象有效、曝光一次或公开控制API存在。

模块原生日志沿用 loggingEnabled/logTag，普通日志使用中文业务位置与关键结果，平台真实回调才记曝光/收益。未渲染落选对象保留仅在 DEBUG；DEBUG 保留 requestId/sessionId/responseId 关联，原始 AdEvent 对外字段继续可用。日志关闭不构造诊断消息。`slotId` 语义迁移需宿主适配；本轮仅验证源码接入，不承诺旧已编译二进制兼容。

2026-10-01 最新来源状态：Google 图片 AdMobAdapter 已支持同对象/平台 View 保留并通过 Debug 真机；文中历史“全部降级”只描述旧阶段。视频、其他适配器、TopOn及完整来源矩阵仍未通过。
