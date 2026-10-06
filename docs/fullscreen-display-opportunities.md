# 全屏展示机会接入说明

本文说明按业务场景等待全屏广告就绪的新接入方式。它适合“这次业务触发在一段时间内可以展示，在期限内等待平台结果并选择可用广告展示”的场景。等待机会、底层加载请求和真实展示会话是三件事：机会结束不会取消加载，也不会让已结束的机会在页面返回后复活。

## API

插页使用 `Ads.showInter(position)`，默认等待 3 秒，通过 `AdsConfig.interTimeoutMillis(position)` 配置，并通过 `nativeFullScreenLayout` 配置原生兜底布局，返回 `AdTask`。position 必填且非空。两平台插页结果全部返回或超时后择优展示；无插页则直接取全屏原生缓存，没有缓存就结束。插页和原生之间不比价，原生保留原始 position。旧插页等待入口已移除。

以下开屏、激励 `WhenReady` 入口保持单类型行为：激励默认 5 秒，开屏默认 12 秒，可传入 `timeoutMillis`（必须为正数）、position（默认 `"manual"`）及 `isSceneValid`。所有期限从调用开始计时，包括主线程排队时间。开屏场景 `showOpen` 的跨类型比价与暂停恢复规则另见 [README](../README.md#开屏场景任务)。

开屏等待只累计宿主可恢复等待期间的时间：宿主 `onPause` 或应用切后台时暂停计时与展示检查，底层加载继续，不返回结束结果。回到同一个 Activity 的 `onResume` 后，已有有效缓存就优先展示（竞价选择当前可用候选）；否则继续剩余等待，不重置整个期限。窗口尚未附着或获得焦点时仍须等待展示条件满足。例如 15 秒预算前台已等 3 秒，后台停留多久，恢复后都只剩 12 秒。广告缓存有效期仍按真实经过时间判断，不随等待计时暂停。

页面销毁、另一个 Activity 恢复、主动取消或场景失效仍会结束开屏机会；结束的机会不会恢复。插屏和激励保留离场即取消的规则。

以下列出调用签名，省略函数实现和内部构造参数；句柄由 `Ads` 返回。

```kotlin
open class AdTask internal constructor(/* 由 SDK 创建 */) {
    val id: String
    fun cancel()
}
// 旧开屏/激励单类型等待入口仍返回 AdDisplayOpportunity（继承 AdTask）。

object Ads {
    fun showAppOpenWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = 12_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdShowResult) -> Unit = {},
    ): AdDisplayOpportunity

    fun showInter(
        position: String,
        onLoadingChanged: (Boolean) -> Unit = {},
        onResult: (AdShowResult) -> Unit = {},
    ): AdTask

    fun showRewardedWhenReady(
        activity: Activity,
        position: String = "manual",
        timeoutMillis: Long = 5_000L,
        isSceneValid: () -> Boolean = { true },
        onResult: (AdRewardResult) -> Unit = {},
    ): AdDisplayOpportunity
}
```

开屏不接收外部容器。TopOn 由 SDK 内部选择 Activity 的 `android.R.id.content`，回退到 `decorView`，创建并清理广告子容器。

`cancel()` 是幂等的。它可取消等待和本库全屏原生；一旦 SDK 已经收到 `show()`，后续取消不会撤回广告，也不会屏蔽关闭、失败、奖励或收益回调。一个机会只交付一次最终结果。

已有的 `showAppOpen`、`showInterstitial` 和 `showRewarded` 仍然是立即尝试入口：没有可用广告时按原语义立即失败，不会因为调用了旧 API 而留下等待资格。

## 结果和事件

单类型等待入口结束时常见的 `AdShowResult.Failed.reason` 包括：

| 原因 | 含义 |
| --- | --- |
| `ad_format_disabled` | 该格式没有配置 ID，或 AdMob 对应全屏缓存数量为零；直接结束，不启动加载 |
| `wait_timeout` | 截止决策时无可用广告或展示条件仍不满足 |
| `ad_load_failed` | 所有参与平台均明确加载失败，提前结束等待 |
| `opportunity_cancelled` | 宿主主动取消 |
| `scene_invalid` | `isSceneValid()` 返回 `false` |
| `scene_validation_failed` | 场景检查抛出异常 |
| `invalid_timeout` | `timeoutMillis <= 0` |
| `request_in_progress` | 已有冲突的等待机会 |
| `another_full_screen_ad_showing` | 已有全屏广告正在展示 |
| `activity_not_available`、`activity_not_resumed`、`app_not_in_foreground` | Activity 或应用当前不具备展示条件 |
| `consent_not_obtained`、初始化失败原因 | 请求许可或 SDK 初始化前置条件未满足 |

`showInter` 在无插页时尝试原生，因此通常以 `no_preloaded_ad` 表示兜底也没有缓存；未配置原生布局则为 `native_layout_not_configured`。取消、许可、初始化、并发、宿主失败不会触发兜底；选定广告的展示失败直接结束。

实际展示交接后，结果按原展示链路处理；此时等待计时停止。激励广告仍只能在 `rewardEarned == true` 时发奖。激励机会通过全部准入检查后即建立业务会话；随后即使等待超时、取消或场景失效，结果仍保留该 `sessionId`。未通过准入检查时可为 `null`。

全部频控、开关和宿主展示资格检查通过后、候选选择和比价前，上报一次 `ad_position`，不传 `ad_platform` 或 `ad_unit_id`。已准入的机会即使最终无填充、等待超时或取消，也保留该事件并用同一 `ad_session_id` 上报失败。候选平台/格式切换和全屏原生兜底不会新增机会；后续事件的 `ad_type` 表示实际素材类型，首条 position 表示原请求类型。加载请求继续使用独立 `request_id`。

示例：

```kotlin
private var interstitialOpportunity: AdTask? = null

fun onLevelCompleted() {
    interstitialOpportunity?.cancel()
    interstitialOpportunity = Ads.showInter(
        position = "level_complete",
        onResult = { result ->
            when (result) {
                AdShowResult.Dismissed -> continueLevelFlow()
                is AdShowResult.Failed -> continueLevelFlow()
            }
        },
    )
}

fun leaveLevel() {
    interstitialOpportunity?.cancel()
    interstitialOpportunity = null
    navigateAway()
}
```

示例中的显式取消应覆盖返回按钮、系统返回手势、Navigation 跳转、Fragment 离开和 Tab 切换等所有离开路径。不要把 `cancel()` 只放在广告回调里；页面保留在返回栈或 Tab 中也不代表它仍是当前广告场景。

## Activity 生命周期和加载复用

`showInter` 绑定调用时 SDK 识别的宿主 Activity，旧单类型等待入口绑定传入的 Activity。原 Activity 销毁、结束或被其他 Activity 实例替换时，结束尚未交接的机会，SDK 不会将机会迁移到新 Activity。开屏在原 Activity 暂停或应用退后台时保留机会并暂停计时，恢复后继续；插屏和激励在暂停或退后台时结束机会。首次 Resume 或窗口尚未 ready 时可以继续等待展示条件。

任务取消或等待阶段结束不会停止 AdMob Preloader、TopOn 的既有加载/重试，也不会清空有效缓存。后续兼容的新机会可以复用在途请求或缓存；新的业务 `position` 不会单独隔离广告缓存。旧单类型等待入口的竞价只比较本次请求广告类型的候选：两家成功立即比价，一家成功另一家明确失败立即展示，两家失败立即结束；仍有平台未完成则继续等待。截止时从当前有效缓存选择，有候选且展示条件满足就展示，否则返回超时。已有缓存视为成功。单平台模式成功即展示、明确失败即结束。新等待入口在一个平台初始化成功后即可开始加载等待，但另一平台仍在初始化时会继续给它参与竞价的机会；`Ads.state` 和 `onInitialized` 仍按两家平台的整体初始化进度报告。

新接入应在 `AdsConfig` 中关闭旧自动开屏，由业务场景显式创建开屏机会：

```kotlin
Ads.initialize(
    application = this,
    config = AdsConfig(
        provider = provider,
        autoShowAppOpen = false,
    ),
)

fun showStartupAd(activity: Activity) {
    val opportunity = Ads.showAppOpenWhenReady(
        activity = activity,
        position = "startup",
        timeoutMillis = 7_000,
        onResult = ::handleAppOpenResult,
    )

    // 在启动页确定离开时调用 opportunity.cancel()。
}
```

需要保留旧自动开屏行为的宿主继续使用 `autoShowAppOpen = true` 和原配置；不要同时让旧自动模式和同一业务场景的新入口各自触发一次开屏。

## 单 Activity、Navigation、Fragment 和保留 Tab

单 Activity 宿主必须把页面 entry、页面实例或当前选中状态纳入场景资格，并在离开时主动取消。只比较路由字符串不够：同一路由可以有多个实例，快速 A → B → A 不能复活 A 的旧机会。回到 A 后必须由新的业务事件创建新机会；它可以复用旧的兼容加载或缓存，但使用新的期限和句柄。

Fragment 可把页面可见性或 View 销毁作为兜底；Navigation 页面 entry 的生命周期、返回和手势离开应直接驱动取消；保留 Tab 必须在选中项变化时取消原 Tab 的机会，即使原页面仍在 Composition 或返回栈中。

### 多 Activity：在原 Activity 保存句柄

```kotlin
private var opportunity: AdTask? = null

fun onBusinessTrigger() {
    opportunity?.cancel()
    opportunity = Ads.showInter(
        position = "activity_a_done",
        onResult = ::handleAdResult,
    )
}

fun openActivityB() {
    opportunity?.cancel() // 明确退出时立即取消；SDK 会处理等待阶段的 onPause。
    opportunity = null
    startActivity(Intent(this, ActivityB::class.java))
}

override fun onDestroy() {
    opportunity?.cancel()
    opportunity = null
    super.onDestroy()
}
```

SDK 自动处理等待阶段的暂停；不要因广告自身导致 Activity 暂停而主动取消任务，否则会关闭本库承载的全屏原生。返回 Activity A 后不要在 `onResume` 自动重发同一业务触发。

### Navigation / Fragment：绑定 entry 和页面 View

```kotlin
// 在 Fragment 的 onViewCreated 中安装；entry 是当前页面自己的 NavBackStackEntry。
val entry = findNavController().currentBackStackEntry ?: return
var opportunity: AdTask? = null
val leaveObserver = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_DESTROY) {
        opportunity?.cancel()
        opportunity = null
    }
}
entry.lifecycle.addObserver(leaveObserver)
viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onDestroy(owner: LifecycleOwner) {
        opportunity?.cancel()
        entry.lifecycle.removeObserver(leaveObserver)
    }
})

button.setOnClickListener {
    if (findNavController().currentBackStackEntry !== entry) return@setOnClickListener
    opportunity?.cancel()
    opportunity = Ads.showInter(
        position = "detail_action",
        onResult = ::handleAdResult,
    )
}
// 主动导航、系统返回和手势完成离场时先 cancel；entry 销毁额外清理。
// 不要无条件在 ON_PAUSE 取消：全屏原生会正常暂停宿主。
```

### 保留 Tab：在切换事件中取消

```kotlin
private var opportunity: AdTask? = null
private var selectedTabId = "home"

fun selectTab(next: String) {
    if (next == selectedTabId) return
    opportunity?.cancel()
    opportunity = null
    selectedTabId = next
}

fun onTabBusinessTrigger() {
    opportunity?.cancel()
    opportunity = Ads.showInter(
        position = "tab_action",
        onResult = ::handleAdResult,
    )
}
```

切换事件必须经过 `selectTab`，即使 A → B → A 全部发生在两次 100ms 检查之间，A 的旧句柄也已经永久取消。

## Compose 接入

展示调用必须来自明确的业务事件，例如按钮点击、一次完成回调或页面 entry 的业务触发。不要在 Composable 函数体直接调用 `showInter` 或 `show...WhenReady`，也不要用每次 `ON_RESUME`、回调更新或 `isCurrentScene` 重新变为 `true` 来自动重发同一次触发。

下面的模式用页面实例作为稳定 key，用 `rememberUpdatedState` 让回调和触发资格检查读取同一页面实例的最新值；这些 State 也通过 `key(hostActivity, pageInstanceId)` 隔离，已交接广告的迟到结果仍交给原页面的回调。机会只在 `triggerInterstitial()` 被明确调用时创建。`DisposableEffect` 负责最终清理，但实际导航/Tab 离开仍应先调用 `leaveScene()`，因为保留页面的 Composition 可能不会立即销毁。

```kotlin
@Composable
fun LevelScreen(
    hostActivity: Activity,
    pageInstanceId: String,
    isCurrentPage: Boolean,
    onAdResult: (AdShowResult) -> Unit,
    navigateAway: () -> Unit,
) {
    val opportunityState = remember(hostActivity, pageInstanceId) {
        mutableStateOf<AdTask?>(null)
    }
    var opportunity by opportunityState
    val latestIsCurrentPage by key(hostActivity, pageInstanceId) {
        rememberUpdatedState(isCurrentPage)
    }
    val latestOnAdResult by key(hostActivity, pageInstanceId) {
        rememberUpdatedState(onAdResult)
    }

    fun leaveScene() {
        opportunity?.cancel()
        opportunity = null
        navigateAway()
    }

    fun triggerInterstitial() {
        if (!latestIsCurrentPage) return
        opportunity?.cancel()
        opportunity = Ads.showInter(
            position = "level_complete",
            onResult = { result -> latestOnAdResult(result) },
        )
    }

    DisposableEffect(hostActivity, pageInstanceId) {
        // Capture this entry's holder, not a rememberUpdatedState pointing to a newer entry.
        onDispose { opportunityState.value?.cancel() }
    }

    Button(onClick = ::triggerInterstitial) {
        Text("展示插屏")
    }

    // 所有返回、手势、Navigation 跳转和 Tab 切换都调用 leaveScene()。
    // 页面返回后再次展示必须由新的业务事件调用 triggerInterstitial()。
}
```

`pageInstanceId` 必须代表页面 entry 或页面实例，而不是一个会在 A → B → A 之间复用的全局路由名。若使用保留 Tab，应把当前选中的 Tab 和对应页面实例作为场景状态；页面不再是当前场景时在离开事件中主动 `cancel()`；重新选中页面不会复活旧任务。

核心库不需要 Compose 或 Navigation 依赖；以上只是宿主侧接入示例。没有 Compose 的宿主使用同样的原则保存句柄，并在页面 entry/Fragment/Activity 的明确离开点调用 `cancel()`。

## 当前 AdMob 复用边界

普通等待取消不会从 SDK 队列取广告，预加载和队列缓存照常复用。只有已经 `pollAd()`、尚未 `show()`、又在最终检查时中止的对象，需要额外判断有效期：

- 开屏按响应 ID 匹配预加载记录，使用对应 Preloader 启动时间作为加载时间的保守下界，并遵守官方四小时上限；可能提前丢弃，不会以取消时间重新计龄。价格无法与该响应可靠关联时按未知价格处理。
- Next-Gen 1.2.1 的插屏和激励没有公开可验证的离队对象有效期；未匹配到加载记录的开屏也无法确认有效性。这些已取出对象解绑回调后销毁，后续机会继续使用 SDK 队列或在途加载。

上述极窄的交接中止窗口不能保证复用同一已取出对象。2026-09-30 已批准此保守策略：任务 2.4 不再要求复用有效性未知的离队对象，真实 SDK 展示验收仍单独跟踪。[官方开屏有效期](https://developers.google.com/admob/android/next-gen/app-open)、[插屏预加载说明](https://developers.google.com/admob/android/next-gen/interstitial)、[激励预加载说明](https://developers.google.com/admob/android/next-gen/rewarded)。

## 业务 loading 与后台恢复

loading 使用当前 Activity 页面内的 View/Compose 覆盖层，避免独立 Activity 或夺取窗口焦点的 Dialog。一次业务触发只创建一个机会，不因重组、重复点击或恢复前台重新计时。

插屏和激励等待期间切后台或离开页面会取消机会，底层加载与缓存保留。返回前台后不恢复已取消的等待、不补弹广告。开屏切后台只暂停等待与计时，回到原 Activity 后继续，不触发结果回调；真正销毁或主动取消仍会终止。业务应在原页面恢复前台且仍有效时清理 loading，并且只继续一次被阻塞的业务；原页面已失效则丢弃继续动作。取消可能同步交付回调，应先标记场景失效再取消，避免取消回调误导航。

插页通过 `onLoadingChanged` 管理页面遮罩：主线程通知 `true` 后可延迟显示，收到 `false` 时立即取消延迟显示并移除。`false` 在插页或原生交接展示前、以及等待失败或取消时发送，不必等广告关闭。未进入等待的请求不通知 `true`；每个请求应持有自己的遮罩，兼容同步回调。

`onResult` 在等待失败或广告最终关闭/失败时交付，用于业务收尾；loading 消失不代表任务完成。交给 SDK 后等待计时停止，不限制广告播放时长。开屏与激励的回调接口不变。

每次机会只主动确保加载一次；明确失败来自平台整体加载失败或初始化失败，而不是聚合平台内部单个广告源失败。本次机会开始前的加载失败记录不会直接导致新机会失败。TopOn 新机会仍可按需发起加载，AdMob 仍依赖持续预加载；本层不增加循环重试。竞价仅等待已启用当前格式的平台，已关闭的参与方不占等待时间。主线程调度可能使截止检查稍晚执行；`showInter` / `showOpen` 仅使用截止前已接收且决策时仍有效的候选，迟到加载保留给后续任务，不再开启新的等待窗口。旧单类型等待入口仍使用检查时有效缓存。


等待期限限制的是等待平台结果的阶段。若在截止前已满足决策条件并选出候选，随后展示准备跨过截止时间，不会仅因此返回超时；截止时选出的兜底候选也遵循同一规则。选定候选不等于交给 SDK：交接前取消、场景失效、许可撤销、宿主不可展示或广告失效仍会阻止展示。
