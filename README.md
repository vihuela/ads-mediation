# Ads Mediation Android SDK

这是一个 Android 广告聚合 SDK，通过统一 API 支持开屏、插屏、激励和宿主渲染的原生广告。
宿主可以选择直连 AdMob GMA Next-Gen、TopOn 海外版，或者同时初始化两者并按缓存广告的
单次展示收益进行端内缓存竞价。

当前稳定版本：`main_1.0.11`

[![打开 AI 接入提示词](https://img.shields.io/badge/AI-%E6%89%93%E5%BC%80%E5%B9%B6%E5%A4%8D%E5%88%B6%E6%8E%A5%E5%85%A5%E6%8F%90%E7%A4%BA%E8%AF%8D-2ea44f)](#10-复制给-ai完整接入提示词)

依赖坐标：

```kotlin
implementation("com.cashcraft:ads-mediation:main_1.0.11")
```

### main_1.0.11 更新

- 新增统一原生广告 `AdFormat.NATIVE`、`Ads.showNative` 和外部渲染器；列表卡片、Dialog 和
  全屏 Activity 共用加载、竞价及收益链路，布局和关闭交互由宿主实现。
- AdMob 原生通过版本化反射配置读取价格并参与竞价，反射失败保留 `null`；内置对应 R8 保留规则。
  简化反射配置，移除 `regular` 包装层。
- AdMob 配置要求 Application ID 和至少一个广告位 ID；TopOn 还要求 App Key。
  其余广告位可留空，未配置的类型不预加载、不参与竞价。这里只校验非空，实际有效性由平台校验。
- Demo 增加原生卡片、全屏 Dialog、各广告类型及竞价入口，并提供埋点审计脚本，核对
  `ad_position = ad_impression + ad_show_fail`。
- 依赖按 AdMob、TopOn 及各网络适配器分组注释。原生接入详见下文“原生广告：外部渲染器”。

### 1.0.5 更新

- 覆盖 GMA Next-Gen 1.2.1 传递引入的旧 WorkManager/Room，升级到 WorkManager 2.11.2。
- AAR 内置 Room 反射构造函数的 consumer ProGuard 规则，修复宿主开启 R8 后可能在
  `WorkDatabase_Impl` 初始化阶段崩溃的问题。
- 修复后的 Release 包支持 Android 16 的 16 KB 页面设备启动；广告 API 和行为保持兼容。

### 1.0.3 更新

- AdMob、TopOn 和竞价模式共用宿主生命周期监控及自动开屏前台窗口。
- 手动展示统一校验前台、Resumed、Window 附着和焦点状态，不满足时通过
  `AdShowResult.Failed` 与 `ad_show_fail` 返回稳定失败原因，且不会消费预加载广告。
- 统一全屏广告展示互斥和 `dismissed_before_impression` 结果处理；各平台原有初始化、
  加载、缓存补充、收益和 SDK 回调策略保持独立。

## 1. 接入依赖

### 1.1 配置仓库和凭据

GitHub Packages 的私有包在下载时也需要具备 `read:packages` 权限的 GitHub 用户名和 token。
本地开发建议把凭据放进不提交 Git 的 `local.properties`：

```properties
github.packages.username=YOUR_GITHUB_USERNAME
github.packages.token=YOUR_GITHUB_TOKEN
```

在宿主工程的 `settings.gradle.kts` 中读取凭据，并加入 GitHub Packages 以及广告网络仓库：

```kotlin
import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties().apply {
    settingsDir.resolve("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        maven("https://maven.pkg.github.com/vihuela/ads-mediation") {
            credentials {
                username = providers.gradleProperty("github.packages.username").orNull
                    ?: providers.environmentVariable("GITHUB_PACKAGES_USERNAME").orNull
                    ?: localProperties.getProperty("github.packages.username")
                password = providers.gradleProperty("github.packages.token").orNull
                    ?: providers.environmentVariable("GITHUB_PACKAGES_TOKEN").orNull
                    ?: localProperties.getProperty("github.packages.token")
            }
            content {
                includeGroup("com.cashcraft")
            }
        }

        maven("https://artifact.bytedance.com/repository/pangle/")
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
        maven("https://jfrog.anythinktech.com/artifactory/overseas_sdk") {
            content {
                includeGroup("com.thinkup.sdk")
                includeGroup("com.smartdigimkttech.sdk")
                includeGroup("com.verbto.tools")
                includeGroup("com.hyperbid.tools")
            }
        }
    }
}
```

### 1.2 添加 SDK

在 App 模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation("com.cashcraft:ads-mediation:main_1.0.11")
}
```

SDK 的 Manifest 内包含 AdMob App ID 占位符。宿主必须为每个 flavor 提供真实值或 Google
官方测试值：

```kotlin
android {
    defaultConfig {
        manifestPlaceholders["admobApplicationId"] = "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"
    }
}
```

如果使用 product flavor，应在各 flavor 内分别设置。SDK 已通过 AAR 自动携带必要的 consumer
ProGuard 规则，宿主不需要复制本仓库的混淆规则。

## 2. 初始化

建议在自定义 `Application.onCreate()` 中调用一次 `Ads.initialize()`。初始化是异步的：默认先
等待第一个已恢复的 Activity 完成 UMP 同意流程，再初始化广告平台并开始预加载。

下面是包含事件回调、收益回调和初始化结果的完整示例：

```kotlin
import android.app.Application
import com.cashcraft.ads.mediation.*

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        Ads.initialize(
            application = this,
            config = AdsConfig(
                provider = BiddingProviderConfig(
                    admob = AdMobProviderConfig(
                        ids = AdMobIds(
                            applicationId = "ca-app-pub-...~...",
                            appOpenId = "ca-app-pub-.../...",
                            interstitialId = "ca-app-pub-.../...",
                            rewardedId = "ca-app-pub-.../...",
                        ),
                        preload = AdMobPreloadConfig(
                            appOpen = 2,
                            interstitial = 2,
                            rewarded = 2,
                        ),
                    ),
                    topon = TopOnProviderConfig(
                        ids = TopOnIds(
                            applicationId = "TopOn App ID",
                            applicationKey = "TopOn App Key",
                            appOpenPlacementId = "TopOn Splash Placement ID",
                            interstitialPlacementId = "TopOn Interstitial Placement ID",
                            rewardedPlacementId = "TopOn Rewarded Placement ID",
                        ),
                    ),
                ),
                umpConsent = UmpConsentConfig(
                    enabled = true,
                    tagForUnderAgeOfConsent = false,
                ),
                eventListener = { event ->
                    analytics.logEvent(
                        event.name.analyticsName,
                        event.analyticsParameters(),
                    )
                },
                revenueListener = { payload ->
                    when (payload) {
                        is AdMobRevenuePayload -> reportAdMobRevenue(payload)
                        is TopOnRevenuePayload -> reportTopOnRevenue(payload)
                    }
                },
                loggingEnabled = BuildConfig.DEBUG,
                logTag = "AdsMediation",
                autoShowAppOpen = true,
                appOpenPosition = "app_foreground",
            ),
            onInitialized = { success ->
                // success=false 不会崩溃；Ads.state 会变为 FAILED。
                // 初始化后调用展示仍会通过回调和 ad_show_fail 正常收口。
            },
        )
    }
}
```

初始化状态可以随时读取：

```kotlin
when (Ads.state) {
    AdsState.NOT_INITIALIZED -> Unit
    AdsState.INITIALIZING -> Unit
    AdsState.READY -> Unit
    AdsState.FAILED -> Unit
}

// 固定 provider 时返回 ADMOB/TOPON；竞价模式没有固定平台，因此返回 null。
val fixedPlatform: AdPlatform? = Ads.platform
```

竞价模式只要 AdMob 或 TopOn 任意一方初始化成功，整体初始化就视为成功。初始化失败不会抛出
SDK 崩溃；展示回调会返回 `Failed("sdk_initialization_failed")`。如果在 `Ads.initialize()` 之前
调用展示，也不会崩溃，但由于事件分发器尚未建立，只能收到 `Failed("sdk_not_initialized")`
回调，无法上报完整的 `ad_position/ad_show_fail` 事件对。

### 2.1 `AdsConfig` 参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `provider` | `AdProviderConfig` | 必填 | 选择 AdMob、TopOn 或端内缓存竞价模式；竞价参数见下方 `BiddingProviderConfig` |
| `umpConsent` | `UmpConsentConfig` | 根据 provider 推导 | 所有 provider 共用的 UMP 请求门禁，必须先允许请求广告才初始化平台 |
| `eventListener` | `AdEventListener` | 空实现 | 接收加载、展示、竞价、点击、收益等稳定事件 |
| `revenueListener` | `AdRevenueListener` | 空实现 | 接收 AdMob/TopOn 原生展示级收益对象，专门用于 Tenjin 等 ILRD 接口 |
| `loggingEnabled` | `Boolean` | SDK Debug 包为 `true` | 控制模块 Logcat 和平台调试日志 |
| `logTag` | `String` | `AdsMediation` | 模块 Logcat tag，不允许为空 |
| `autoShowAppOpen` | `Boolean` | `true` | App 进入前台时自动尝试展示开屏广告 |
| `autoAppOpenMode` | `AutoAppOpenMode` | `APP_OPEN_ONLY` | 自动开屏只用开屏，或通过 `APP_OPEN_OR_INTERSTITIAL` 同时比较插屏 |
| `appOpenPosition` | `String` | `app_foreground` | 自动开屏对应的业务场景名 |

### 2.2 Provider 参数

AdMob 直连：

配置校验在构造 `AdMobIds` / `TopOnIds` 时执行，不满足则抛 `IllegalArgumentException`：

- AdMob：`applicationId` 非空，且开屏、插屏、激励、原生四种 ID 至少一个非空。
- TopOn：`applicationId`、`applicationKey` 非空，且四种广告位 ID 至少一个非空。
- 广告 ID 可省略；空字符串、纯空白以及原生的 `null` 均表示未配置。未配置的类型不创建广告、
  不预加载、不参与竞价。两平台竞价时各自满足上述规则即可，不要求配置相同类型。
- 未配置开屏时不触发默认自动开屏；混合自动开屏只在开屏或插屏至少配置一项时启用。
  手动请求未配置的全屏类型返回 `ad_not_configured`，原生沿用 `native_not_configured`。

例如仅接入原生可写 `AdMobIds(applicationId = "...", nativeId = "...")`，或
`TopOnIds(applicationId = "...", applicationKey = "...", nativePlacementId = "...")`。

```kotlin
val provider = AdMobProviderConfig(
    ids = AdMobIds(
        applicationId = "AdMob App ID",
        appOpenId = "App Open Ad Unit ID",
        interstitialId = "Interstitial Ad Unit ID",
        rewardedId = "Rewarded Ad Unit ID",
    ),
    preload = AdMobPreloadConfig(
        appOpen = 2,
        interstitial = 2,
        rewarded = 2,
    ),
)
```

`AdMobPreloadConfig` 表示 GMA Next-Gen 每种广告持续补充的缓存上限，取值范围为 `1..15`，
默认都是 `2`。开发和自动化测试可使用 `AdMobIds.TEST`，生产包必须注入真实 ID。

TopOn：

```kotlin
val provider = TopOnProviderConfig(
    ids = TopOnIds(
        applicationId = "TopOn App ID",
        applicationKey = "TopOn App Key",
        appOpenPlacementId = "Splash Placement ID",
        interstitialPlacementId = "Interstitial Placement ID",
        rewardedPlacementId = "Rewarded Placement ID",
    ),
)
```

端内缓存竞价：

```kotlin
val provider = BiddingProviderConfig(
    admob = AdMobProviderConfig(
        ids = AdMobIds(
            applicationId = "AdMob App ID",
            appOpenId = "AdMob App Open Ad Unit ID",
            interstitialId = "AdMob Interstitial Ad Unit ID",
            rewardedId = "AdMob Rewarded Ad Unit ID",
        ),
        preload = AdMobPreloadConfig(
            appOpen = 2,
            interstitial = 2,
            rewarded = 2,
        ),
    ),
    topon = TopOnProviderConfig(
        ids = TopOnIds(
            applicationId = "TopOn App ID",
            applicationKey = "TopOn App Key",
            appOpenPlacementId = "TopOn Splash Placement ID",
            interstitialPlacementId = "TopOn Interstitial Placement ID",
            rewardedPlacementId = "TopOn Rewarded Placement ID",
        ),
    ),
)
```

`BiddingProviderConfig` 入参：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `admob` | `AdMobProviderConfig` | 必填 | AdMob App ID、三种 ad unit ID，以及三种格式各自的预加载缓存数量 |
| `topon` | `TopOnProviderConfig` | 必填 | TopOn App ID、App Key，以及三种格式各自的 placement ID |

竞价模式没有额外的价格系数、底价或手动超时参数。价格统一换算为 USD 单次展示收益，候选选择
规则由 SDK 固定实现。UMP、事件回调、收益回调、日志、自动开屏开关和自动开屏 `position` 仍然
配置在外层 `AdsConfig`：

```kotlin
AdsConfig(
    provider = provider,
    umpConsent = UmpConsentConfig(
        enabled = true,
        tagForUnderAgeOfConsent = false,
    ),
    eventListener = eventListener,
    revenueListener = revenueListener,
    loggingEnabled = BuildConfig.DEBUG,
    logTag = "AdsMediation",
    autoShowAppOpen = true,
    appOpenPosition = "app_foreground",
)
```

其中 `admob.preload` 只控制 AdMob GMA Next-Gen 的持续缓存数量。TopOn 的加载和缓存策略由
TopOn SDK 管理，目前没有在 `BiddingProviderConfig` 暴露缓存数量。手动插屏和激励展示不会等待
新广告加载，只比较调用瞬间已经缓存的候选；自动开屏最多等待 7 秒，这个时长当前也不是公开
配置项。

## 3. 展示广告

`position` 是业务场景，例如关卡结束、刷新道具或手动开屏。SDK 会自动拼接广告类型后缀，
形成 `<业务场景>_<广告类型>`；已存在相同后缀时不会重复添加。

```kotlin
val ready = Ads.isReady(AdFormat.INTERSTITIAL)

Ads.showInterstitial(
    activity = this,
    position = "game_level_complete",
) { result ->
    when (result) {
        AdShowResult.Dismissed -> continueGame()
        is AdShowResult.Failed -> continueGame()
    }
}

Ads.showRewarded(
    activity = this,
    position = "game_tool_refresh",
) { result ->
    // 只根据 rewardEarned 发奖，不能根据广告关闭或展示成功发奖。
    if (result.rewardEarned) {
        grantReward(result.sessionId)
    }
}

Ads.showAppOpen(
    activity = this,
    position = "manual",
) { result ->
    // Dismissed 或 Failed 都表示本次调用已经结束。
}

// Launcher、Compose 宿主等非标准 Activity 可显式提供已附着且可见的全屏宿主容器。
Ads.showAppOpen(
    activity = this,
    hostContainer = splashHost,
    position = "launcher_minus_one",
) { result ->
    // SDK 会创建并清理自己的广告子容器，不会移除宿主传入的 splashHost。
}
```

`AdShowResult.Dismissed` 表示广告产生展示并最终关闭；`AdShowResult.Failed.reason` 是适合日志和
埋点的稳定失败原因。`AdRewardResult` 包含：

手动展示只接受当前位于前台、处于 Resumed 状态且 Window 已附着并获得焦点的 Activity。
不满足条件时不会消费预加载广告，而是通过 `AdShowResult.Failed` 和 `ad_show_fail` 收口；稳定
失败原因包括 `app_not_in_foreground`、`activity_not_resumed`、
`activity_window_not_attached` 和 `activity_window_not_focused`。

AdMob、TopOn 与竞价模式共用同一套宿主生命周期状态和自动开屏前台窗口：每次进入前台后
最多等待 7 秒，Activity 可交互且广告可用时展示；进入后台或等待超时则以 `ad_show_fail`
结束本次机会。各平台的初始化、加载、缓存补充、收益和 SDK 回调仍分别由各自实现负责。

TopOn 开屏会优先使用调用方传入的 `hostContainer`，否则依次尝试 Activity 的
`android.R.id.content` 和 Window DecorView。容器不存在、未附着、不可见、挂载失败或
`show()` 抛异常时不会导致宿主崩溃，而是发送 `ad_show_fail`；开启 `loggingEnabled` 时同一失败
会写入 Logcat，异常路径还会保留 throwable 堆栈。

| 属性 | 说明 |
| --- | --- |
| `rewardEarned` | 广告平台是否明确触发奖励回调，业务只能据此发奖 |
| `showResult` | 本次展示是关闭还是失败 |
| `sessionId` | 将广告奖励与业务发奖事件关联起来的会话 ID |

### 跨格式缓存竞价

原来的 `showAppOpen`、`showInterstitial`、`showRewarded` 仍然只展示对应格式。
新增两个跨格式入口，单平台和 `BiddingProviderConfig` 均可使用：

```kotlin
// 开屏与插屏比价。hostContainer 可省略，TopOn 开屏沿用自动查找容器的逻辑。
Ads.showAppOpenOrInterstitial(
    activity = this,
    position = "manual_open",
) { result ->
    // result.platform / result.format 是本次选中的平台和格式。
    // result.showResult 为 Dismissed 或 Failed；未选中候选时平台和格式为 null。
}

// 激励与插屏比价，默认插屏不会产生业务奖励。
Ads.showRewardedOrInterstitial(
    activity = this,
    position = "game_tool_refresh",
) { result ->
    if (result.rewardEarned) grantReward(result.sessionId)
}

// 只有业务明确允许“插屏曝光后关闭也发奖”时才启用该策略。
Ads.showRewardedOrInterstitial(
    activity = this,
    position = "game_tool_refresh",
    interstitialRewardPolicy = InterstitialRewardPolicy.ON_DISMISSED,
) { result ->
    if (result.rewardEarned) grantReward(result.sessionId)
}

val openReady = Ads.isAppOpenOrInterstitialReady()
val rewardReady = Ads.isRewardedOrInterstitialReady()
```

`AdMixedShowResult` 返回选中的 `platform`、`format`、`showResult`、`sessionId`，以及：

- `sdkRewardEarned`：仅表示激励 SDK 是否触发奖励回调，普通插屏始终为 `false`。
- `rewardEarned`：本次业务是否可以发奖。默认与 SDK 激励奖励一致；显式选择
  `ON_DISMISSED` 时，插屏产生曝光并关闭也可为 `true`。这不代表完整观看，不会伪造
  `ad_reward_earned` 事件。展示失败或未曝光就关闭的插屏不会发奖。

激励与插屏混合的调用方需要接受“插屏胜出但默认不发奖”的结果。需要保证奖励机会仅由
激励广告承接的场景，继续使用 `showRewarded`。

自动开屏默认保持 `APP_OPEN_ONLY`。需要自动开屏也参与跨格式竞价时，在初始化配置中设置：

```kotlin
val config = AdsConfig(
    provider = provider,
    autoShowAppOpen = true,
    autoAppOpenMode = AutoAppOpenMode.APP_OPEN_OR_INTERSTITIAL,
)
```

混合自动开屏会同时检查开屏和插屏缓存，在现有 7 秒前台窗口内有任一种可用即可进入选择。
手动混合展示仍不等待新广告加载。每次只展示一个胜出广告，胜出广告展示失败时不连播另一候选。

## 4. UMP 与隐私选项

UMP 是 AdMob、TopOn 和竞价模式共用的全局门禁，因为 TopOn 也可能加载 Google 适配器。

```kotlin
val consent = Ads.consentSnapshot
val canRequestAds = consent.canRequestAds

if (Ads.isPrivacyOptionsRequired) {
    Ads.showPrivacyOptions(this) { errorMessage ->
        // null 表示隐私选项页面正常关闭。
    }
}
```

`AdConsentSnapshot` 属性：

| 属性 | 说明 |
| --- | --- |
| `status` | `UNKNOWN`、`REQUIRED`、`NOT_REQUIRED`、`OBTAINED` 或 `DISABLED` |
| `canRequestAds` | 当前是否允许初始化广告平台并请求广告 |
| `privacyOptionsRequired` | 宿主是否需要提供隐私选项入口 |

`UmpConsentConfig.enabled=false` 会跳过 UMP 并立即允许初始化，通常只适用于明确不需要 UMP 的
环境。`tagForUnderAgeOfConsent=true` 会把未达同意年龄标记传给 UMP。

## 5. 事件回调

```kotlin
eventListener = AdEventListener { event ->
    analytics.logEvent(
        name = event.name.analyticsName,
        parameters = event.analyticsParameters(),
    )
}
```

事件名：

| 枚举 | 上报名称 | 说明 |
| --- | --- | --- |
| `LOAD_REQUEST` | `ad_load_request` | 开始加载或补充缓存 |
| `LOAD_RESULT` | `ad_load_result` | 加载成功或失败 |
| `POSITION` | `ad_position` | 一次被允许进入广告模块的展示机会 |
| `BID_RESULT` | `ad_bid_result` | 竞价候选、价格和获胜方 |
| `IMPRESSION` | `ad_impression` | 广告平台确认产生展示 |
| `SHOW_FAIL` | `ad_show_fail` | 本次展示机会未产生展示并已失败收口 |
| `CLICK` | `ad_click` | 用户点击广告 |
| `DISMISS` | `ad_close` | 全屏广告关闭 |
| `PAID` | `ad_paid` | 平台返回展示级收益 |
| `REWARD_EARNED` | `ad_reward_earned` | 激励广告平台确认奖励 |

在完成初始化后，每个被广告模块接收的展示会话都满足：

```text
ad_position = ad_impression + ad_show_fail
```

同一个会话不会同时产生 `ad_impression` 和 `ad_show_fail`。竞价会话的顺序是
`ad_position -> ad_bid_result -> ad_impression/ad_show_fail`；初始化或同意流程尚未完成等
竞价前失败没有 `ad_bid_result`。

`AdEvent` 公共属性：

| 属性 / analytics key | 说明 |
| --- | --- |
| `name` | 当前事件枚举；`analyticsName` 是稳定上报名 |
| `platform` / `ad_platform` | 事件所属平台：`admob` 或 `topon` |
| `mediationMode` / `mediation_mode` | 初始化模式：`admob`、`topon` 或 `bidding` |
| `format` / `ad_type` | `app_open`、`interstitial` 或 `rewarded` |
| `position` | 已追加广告类型的业务场景 |
| `sessionId` / `session_id` | 串联同一次展示机会内的全部事件 |
| `adUnitId` / `ad_unit_id` | AdMob ad unit ID 或 TopOn placement ID |
| `number` | 当前进程内同类事件的递增序号 |

按事件出现的可选属性：

| 属性 / analytics key | 说明 |
| --- | --- |
| `reason` | 失败或结果原因，最长 64 字符 |
| `errorCode` / `error_code` | 平台错误码 |
| `adSource` / `ad_source` | 实际填充广告源 |
| `responseId` / `response_id` | 平台响应 ID |
| `value` | 单次展示收益，或竞价价格，单位由事件的 `currency` 确定 |
| `valueMicros` / `value_micros` | AdMob 收益微单位原值 |
| `currency` | ISO 货币代码；竞价事件统一为 USD |
| `mediationAdapterClassName` | AdMob 实际 mediation adapter 类名 |
| `precisionType` | 收益精度类型 |
| `requestId` | 加载请求 ID |
| `result` | 加载或竞价结果 |
| `latencyMillis` / `latency_ms` | 加载耗时 |
| `bufferSize` / `buffer_size` | AdMob 预加载缓存大小 |

`ad_bid_result` 还包含 `winner_platform`、`admob_available`、`topon_available`、
`admob_price_available`、`topon_price_available`、`admob_value`、`topon_value`、
`winning_value`、`admob_ad_unit_id` 和 `topon_ad_unit_id`。无有效价格时对应 value 字段不出现，
不会用 `0` 冒充未知价格；真实零价格仍上报 `0.0`。

## 6. 收益回调

`ad_paid` 用于通用可观测性；展示级收益应使用独立的 `revenueListener`。AdMob 和 TopOn
保留各自的 payload 类型，但所有后端上报所需字段都位于公共 `AdRevenuePayload` 接口上，宿主
无需按平台分支。只有 Tenjin 等依赖 Provider 原生字段的集成才需要判断具体子类型。

```kotlin
revenueListener = AdRevenueListener { payload ->
    reportRevenueToBackend(
        eventId = payload.eventId,
        occurredAtMillis = payload.occurredAtMillis,
        platform = payload.platform,
        format = payload.format,
        sessionId = payload.sessionId,
        position = payload.position,
        placementId = payload.placementId,
        valueMicros = payload.valueMicros,
        currencyCode = payload.currencyCode,
        adNetwork = payload.adNetwork,
        impressionId = payload.impressionId,
        precisionType = payload.precisionType,
    )

    // Only native ILRD integrations need provider-specific fields.
    when (payload) {
        is AdMobRevenuePayload -> {
            reportAdMobNativeRevenue(payload.mediationAdapterClassName)
        }

        is TopOnRevenuePayload -> {
            reportTopOnNativeRevenue(payload.adInfo)
        }
    }
}
```

公共 `AdRevenuePayload` 属性：

| 属性 | 说明 |
| --- | --- |
| `eventId` | 幂等收益事件 ID；优先由 Provider impression ID 生成，否则使用展示 session ID |
| `occurredAtMillis` | 收益回调到达的 Unix 毫秒时间 |
| `platform` / `mediationMode` | 实际收益平台和宿主选择的聚合模式 |
| `format` | `APP_OPEN` / `INTERSTITIAL` / `REWARDED` |
| `sessionId` / `position` | 展示会话及稳定业务场景 |
| `placementId` | AdMob ad unit ID 或 TopOn placement ID |
| `valueMicros` | `currencyCode` 对应货币的微单位，非负且非空 |
| `currencyCode` | 非空 ISO 货币代码；TopOn 显式归一为 USD |
| `adNetwork` | 实际填充广告网络；Provider 未提供时为 null |
| `impressionId` | AdMob response ID 或 TopOn show ID；Provider 未提供时为 null |
| `precisionType` | Provider 收益精度；未提供时为 null |

Provider 特有属性：

| 属性 | 说明 |
| --- | --- |
| `AdMobRevenuePayload.mediationAdapterClassName` | 实际填充 adapter 类名 |
| `TopOnRevenuePayload.adInfo` | 原始 `TUAdInfo`；只供即时原生集成使用，不得持久化 |

TopOn 收益使用 `getPublisherRevenue(USD)`，没有合法 USD publisher revenue 时不发出收益 payload。
AdMob 保留官方回调的币种；只接受美元的宿主接口必须检查 `currencyCode == "USD"` 后再上报。

## 7. 竞价逻辑和兼容范围

端内缓存竞价模式会同时预加载 AdMob 和 TopOn 的同种广告。每次手动展示只比较调用当下已缓存的候选，
不会为了等待网络加载而阻塞业务：

1. 只有一个平台有缓存时，直接选择该平台，即使价格为零。
2. 两个平台都有缓存时，已知价格优先于未知价格。
3. 两边价格都已知时选择 USD 单次展示收益更高者。
4. 价格相同或都未知时，使用 AdMob 作为确定性兜底。
5. 两边都没有缓存时，产生 `ad_bid_result(result=no_candidate)` 和
   `ad_show_fail(reason=no_preloaded_ad)`。

跨格式入口将两个格式的候选放在同一次比较中；双平台配置最多有四个候选，单平台配置有两个。
仍然只使用当前缓存，已知价格优先，高价优先；同价或都未知时先选场景原本的格式
（开屏或激励），再优先 AdMob。未启用的平台和其他格式不会参与，TopOn 对本次两个格式分别
尝试补充缓存，但本次选择不会等待加载完成。

跨格式 `ad_bid_result` 的 `analyticsParameters()` 保留汇总信息：场景、会话、请求格式、
参与格式、胜出平台和格式、收益价格等。旧的 `admob_value` / `topon_value` 等平台字段继续
表示请求的主格式；新增 `requested_ad_type`、`eligible_ad_types`、`winner_format`。
SDK 生成的竞价事件最多 22 个属性，不再展开每个候选的属性，避免超过 Firebase 每事件 25 个
属性的限制。宿主额外追加属性（包括默认事件属性）时仍需控制总数。

完整候选列表通过 `AdEvent.bidCandidates` 获取，每项包含 `platform`、`format`、`adUnitId`、
`available` 和 `priceUsd`；价格未知时 `priceUsd` 为 null，真实零价格保持为 0。
跨格式的逐候选分析应读取这个列表，按需发送到业务后端；候选明细仍可在 SDK 调试日志中查看。

曝光、收益和关闭事件的 `ad_type` / `ad_unit_id` 使用实际展示格式及其广告位，`position` 使用
业务场景加实际格式后缀；可用同一 `session_id` 关联竞价事件中的原始请求格式。

TopOn 通过 `checkAdStatus().getTUTopAdInfo()` 读取当前最高优先级缓存广告，优先使用
`getPublisherRevenue(USD)`；缺失时使用 `getEcpm(USD) / 1000`。这可以保证和 AdMob 使用同一种
货币、同一种单次展示收益口径，并让询价对象与 TopOn 随后 `show()` 选择的广告保持一致。询价
只读取缓存元数据，不会清理或消费 TopOn 广告。AdMob 通过版本化反射路径读取 GMA Next-Gen
预加载队列头部的 `AdValue.valueMicros`，同样只读取、不消费广告。

自动竞价开屏会在进入前台后最多等待 7 秒，直到 Activity window 可用且至少有一个缓存；超时或
退到后台都会用 `ad_bid_result + ad_show_fail` 收口，不会留下只有 `ad_position` 的会话。

重要兼容边界：当前竞价价格反射路径只支持 AdMob GMA Next-Gen `1.2.1`。升级 AdMob 后必须同时
更新 `google_next_gen_preload_reflection_paths.json`、consumer ProGuard 规则和 R8 smoke 测试，
不能只修改依赖版本。

## 8. 当前 SDK 和适配器版本

| 类型 | Maven 组件 | 当前版本 |
| --- | --- | --- |
| AdMob 直连 SDK | `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk` | `1.2.1` |
| AndroidX WorkManager | `androidx.work:work-runtime` | `2.11.2` |
| Google UMP | `com.google.android.ump:user-messaging-platform` | `4.0.0` |
| TopOn 核心 | `com.thinkup.sdk:core-tpn` | `6.6.22.3` |
| TopOn ADX adapter | `com.thinkup.sdk:adapter-tpn-sdm` | `6.5.77.1.1` |
| TopOn ADX SDK | `com.smartdigimkttech.sdk:smartdigimkttech-sdk` | `6.5.77` |
| TopOn GMA Next-Gen adapter | `com.thinkup.sdk:adapter-tpn-gma-next-gen` | `1.2.1.1.0` |
| TopOn Pangle adapter | `com.thinkup.sdk:adapter-tpn-pangle` | `8.1.0.3.1.0` |
| Pangle SDK | `com.pangle.global:pag-sdk` | `8.1.0.3` |
| TopOn Meta adapter | `com.thinkup.sdk:adapter-tpn-facebook` | `6.22.0.1.0` |
| Meta Audience Network | `com.facebook.android:audience-network-sdk` | `6.22.0` |
| TopOn Mintegral adapter | `com.thinkup.sdk:adapter-tpn-mintegral` | `17.1.71.1.0` |
| Mintegral SDK | `com.mbridge.msdk.oversea:mbridge_android_sdk` | `17.1.71` |
| TopOn Tramini 插件 | `com.thinkup.sdk:tramini-plugin-tpn` | `6.6.22.3` |

AdMob 直连路径本身不需要 adapter；表中的 GMA adapter 是 TopOn 调用 GMA Next-Gen 时使用的
适配器。工程明确排除了 legacy `play-services-ads` 和 `play-services-ads-lite`，禁止与
GMA Next-Gen 同时打包。

## 9. 验证、混淆和发布

1.0.11 发布检查（main_1.0.11 仅调整版本名）：84 项 JVM 单元测试通过；SDK Lint 无问题，demo Lint 为 0 errors、43 warnings；
Release R8 构建及本地 Maven 发布通过。Demo Lint 和 R8 仍有告警，见下方真机验证记录。
本次配置校验调整经过单测和构建检查，未重新进行真机回归；下方真机记录对应各轮测试时的构建。

本地完整验证：

```shell
./gradlew clean testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease publishToMavenLocal \
  -PVERSION_NAME=main_1.0.11
```

R8 smoke app 会让 AdMob、TopOn 和竞价路径都保持可达，然后构建开启压缩和资源收缩的 Release
APK，用于验证 AAR 的 consumer rules 和 GMA 反射字段。

GitHub Actions 中的 `CI` 和 `Publish GitHub Package` 都只支持在 Actions 页面手动触发，不会因
push、PR 或 tag 自动运行。

本地或手动发布 CI 都会先读取 GitHub Packages 的 `maven-metadata.xml`，找到最高的稳定
`major.minor.patch` 版本并自动递增 patch；默认逻辑只识别纯数字版本，不自动拼接分支名。
当前发布版本采用 `<分支>_<版本号>` 格式：`main_1.0.11`，需通过 `VERSION_NAME` 显式指定。
此前的 `1.0.11` 保留在 Packages，新接入使用 `main_1.0.11`。

查看下一版本但不发布：

```shell
./scripts/publish-next-version.sh --print-next-version
```

本地验证并发布下一版本：

```shell
./scripts/publish-next-version.sh
```

如确实需要指定版本，可显式覆盖：

```shell
VERSION_NAME=main_1.0.12 ./scripts/publish-next-version.sh
```

GitHub Package 版本不可覆盖；脚本默认自动递增可避免重复版本上传。

## 10. 复制给 AI：完整接入提示词

点击下面代码块右上角的复制按钮，把整段内容发送给能访问你 Android 工程的 AI。AI 应根据
现有项目结构完成接入、编译和测试，而不是只生成示例代码。

```text
请在当前 Android 工程中完整接入 Ads Mediation SDK，并直接修改、编译和验证项目。

固定信息：
- Maven 坐标：com.cashcraft:ads-mediation:main_1.0.11
- GitHub Packages 仓库：https://maven.pkg.github.com/vihuela/ads-mediation
- 公共包名：com.cashcraft.ads.mediation
- 支持 APP_OPEN、INTERSTITIAL、REWARDED
- 支持 AdMobProviderConfig、TopOnProviderConfig、BiddingProviderConfig 三种模式
- 当前直连 AdMob GMA Next-Gen 是 1.2.1；竞价价格反射只兼容 1.2.1，不要擅自升级
- 不要引入 legacy play-services-ads 或 play-services-ads-lite

请完成以下工作：
1. 检查 settings.gradle.kts，在 dependencyResolutionManagement 中加入 GitHub Packages、
   Pangle、Mintegral 和 TopOn 仓库。GitHub 用户名/token 优先从 Gradle property 或环境变量读取，
   本地再从已被 Git 忽略的 local.properties 读取；绝对不能把 token 写进可提交文件。
2. 在 App 模块加入 implementation("com.cashcraft:ads-mediation:main_1.0.11")。
3. 为每个 product flavor 设置 manifestPlaceholders["admobApplicationId"]。不要使用生产 ID
   进行测试；开发环境可使用 AdMobIds.TEST 对应的官方测试 App ID。
4. 检查现有配置来源，选择 AdMob、TopOn 或 Bidding。不要把 App ID、App Key、ad unit ID、
   placement ID 硬编码进通用业务类；沿用项目现有 BuildConfig、properties 或 secret 体系。
5. 在自定义 Application.onCreate 中调用 Ads.initialize。默认启用 UMP；记录 onInitialized，
   但初始化失败不能导致 App 崩溃或阻塞业务流程。
6. AdsConfig 必须接入 eventListener：使用 event.name.analyticsName 作为事件名，使用
   event.analyticsParameters() 作为完整参数。保持同一 session_id，并确保初始化后的每个展示
   会话满足 ad_position = ad_impression + ad_show_fail。
7. AdsConfig 必须接入 revenueListener。通用后端直接消费 AdRevenuePayload 的 eventId、时间、
   平台、格式、展示上下文、微单位金额、币种、广告网络和 impressionId；AdMob 子类型保留
   mediationAdapterClassName，TopOn 子类型保留原始 adInfo。不要从 ad_paid 反推原生收益对象。
8. 把插屏、激励和手动开屏调用改为 Ads.showInterstitial、Ads.showRewarded、
   Ads.showAppOpen。position 使用稳定业务场景名。激励业务只能在 rewardEarned=true 时发奖，
   并把 sessionId 带到业务发奖埋点。
9. 如果隐私入口需要展示，使用 Ads.isPrivacyOptionsRequired 和 Ads.showPrivacyOptions。
10. 保留 SDK AAR 自带的 consumer ProGuard 规则。构建开启 R8 的 Release 变体，确认没有由于
    com.cashcraft.ads.mediation 或 ads_mobile_sdk 反射路径造成的缺类/缺成员错误。
11. 至少运行相关单测、Lint、Debug 编译和一个启用 minify 的 Release 构建。最后汇报选择的
    provider、配置来源、事件/收益接线位置、验证命令及结果；不要输出任何 token 或生产密钥。

公共初始化结构：
Ads.initialize(
    application = this,
    config = AdsConfig(
        provider = BiddingProviderConfig(
            admob = AdMobProviderConfig(ids = admobIds),
            topon = TopOnProviderConfig(ids = topOnIds),
        ),
        umpConsent = UmpConsentConfig(),
        eventListener = { event ->
            analytics.logEvent(event.name.analyticsName, event.analyticsParameters())
        },
        revenueListener = { payload ->
            when (payload) {
                is AdMobRevenuePayload -> reportAdMobRevenue(payload)
                is TopOnRevenuePayload -> reportTopOnRevenue(payload)
            }
        },
        loggingEnabled = BuildConfig.DEBUG,
        autoShowAppOpen = true,
    ),
    onInitialized = { success -> /* 记录状态，不要崩溃 */ },
)

公共展示结构：
Ads.showInterstitial(activity, "game_level_complete") { result -> /* 无论结果都继续业务 */ }
Ads.showRewarded(activity, "game_tool_refresh") { result ->
    if (result.rewardEarned) grantReward(result.sessionId)
}
Ads.showAppOpen(activity, "manual") { result -> /* 收口 */ }
```

## 原生广告：外部渲染器

### 配置与加载

在现有配置中追加 `AdMobIds.nativeId`、`TopOnIds.nativePlacementId` 即可开启对应平台。
两项默认为 `null`，已有生产接入不会自动新增原生请求。`AdMobIds.TEST` 已包含官方原生测试 ID。
`BiddingProviderConfig` 下两边各缓存一条原生广告；未配置原生 ID 的平台不参与原生请求。

```kotlin
val admob = AdMobProviderConfig(
    ids = existingAdMobIds.copy(nativeId = "YOUR_NATIVE_AD_UNIT_ID"),
)
val topon = TopOnProviderConfig(
    ids = existingTopOnIds.copy(nativePlacementId = "YOUR_NATIVE_PLACEMENT_ID"),
)
// AdsConfig(provider = admob / topon / BiddingProviderConfig(admob, topon))
```

UMP 同意并完成初始化后自动预加载；消费后补充一条缓存，缓存有效期最长一小时。
加载失败不无限重试：下一次 `showNative` 或 `preloadNative` 会重试，60 秒未回调会解除加载锁；
迟到的结果会被销毁。`preloadNative` 是异步请求，通过现有 `LOAD_RESULT` 事件观察结果。
`isReady(AdFormat.NATIVE)` 只表示存在缓存，最终还需检查本次渲染器是否支持该平台。

`showNative` 只消费当前缓存，不等待网络；空缓存返回 `no_preloaded_ad` 并触发加载。
Bidding 复用现有选择规则：有有效价格优先，其次价格高者优先，均未知时 AdMob 优先。
反射配置按 SDK 版本直接定义 `preloading`（队列路径）、`nativeAdPaths`（原生路径）和
共用的 `priceFields`（价格字段），不再使用 `regular` 分类。
AdMob 原生沿用 remax 的 GMA Next-Gen 1.2.1 反射路径 `a → b → m`，在加载完成时读取
该广告对象的 `valueMicros` 和币种，将 USD 金额除以 1,000,000 后参与竞价。字段查找包含父类，
AAR 的 consumer R8 规则保护实际读取的字段。未知 SDK 版本、字段缺失、非 USD 或负值均保留
`null`，有效的 0 保留为 0；取价不会消费缓存或触发曝光。TopOn 读取缓存广告自身的 USD 价格。
展示后的 ILRD 仍单独来自 SDK 收益回调，不使用反射价格代替真实收益。

### 渲染接口

`NativeAdRenderers` 按每次展示传入，因此宿主可以用不同实例或闭包控制布局、大小和样式。
SDK 的素材类型通过 `api` 依赖公开，宿主无需另行添加不同版本的 Google/TopOn SDK。

- `AdMobNativeAdRenderer.createView` 返回未附着的 `NativeAdView`；`bind` 填充素材、设置
  `headlineView`、`callToActionView` 等，返回可选的 `MediaView`。库随后调用 `registerNativeAd`。
- `TopOnNativeAdRenderer.createView` 返回未附着的内容 `ViewGroup`；`bind` 填充 `TUNativeMaterial`，
  返回包含标题、CTA 和可点击视图等绑定信息的 `TUNativePrepareInfo`。库创建 `TUNativeAdView`，
  完成 `renderAdContainer` 和 `prepare`。模板广告走 SDK 原生模板，不调用自渲染器。
- 缺少渲染器的自渲染广告不参与本次选择、不消耗缓存；TopOn 模板广告仍可参与。
- 渲染器需展示广告标识及网络要求的素材，保留 SDK 的 AdChoices/隐私交互。不要替换广告回调、
  自行注册/销毁广告、模拟点击，或把同一个 View/广告绑定到多个容器。

AdMob 注册规则参考 [Google Next-Gen 原生广告文档](https://developers.google.com/admob/android/next-gen/native/advanced)。
可编译的两平台渲染器见 [SmokeNativeRenderers.kt](r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/SmokeNativeRenderers.kt)。

### 展示与释放

```kotlin
private var nativeHandle: NativeAdHandle? = null

fun renderNative(activity: Activity, container: ViewGroup) {
    nativeHandle?.destroy()
    nativeHandle = null
    Ads.showNative(
        activity = activity,
        container = container,
        position = "detail_bottom",
        renderers = NativeAdRenderers(admob = myAdMobRenderer, topon = myTopOnRenderer),
    ) { result ->
        when (result) {
            is NativeAdShowResult.Rendered -> nativeHandle = result.handle
            is NativeAdShowResult.Failed -> { /* 隐藏占位；通过 LOAD_RESULT 决定下次尝试 */ }
        }
    }
}

// Fragment.onDestroyView、RecyclerView 回收或 Compose DisposableEffect.onDispose
fun releaseNative() {
    nativeHandle?.destroy()
    nativeHandle = null
}
```

`Rendered` 表示完成绑定和挂载，真实曝光仅由 SDK 回调报告；点击和收益继续走全局 listener。
每条展示有自己的 session 和幂等 `destroy()`，销毁只移除本条广告的 View，不清空宿主容器。
Activity 销毁时会兜底释放；同一 Activity 下多条原生可并存。同一容器重复展示返回
`native_container_in_use`，先销毁旧句柄再复用。容器临时 detach 不自动销毁，避免 RecyclerView
短暂离屏或 Compose 重组误销毁，宿主需在真正移除时释放。

`showNative`、`preloadNative`、`destroy` 可以从任意线程调用；渲染、注册、事件和展示结果在主线程执行。
曝光前销毁会报告 `destroyed_before_impression`；曝光后释放报告 `DISMISS`。
其他失败包括 `native_not_configured`、`native_renderer_missing`、`native_render_failed`，以及通用
初始化/同意状态错误。渲染器异常会释放已消费的广告并返回失败，不会影响其他容器。

### 宿主自行构建全屏原生

在宿主的 Activity 或 Dialog 中准备一个容器，照常调用 `showNative`，使用适合大屏布局的渲染器。
关闭按钮、返回键、倒计时、窗口尺寸和页面跳转均归宿主；关闭时销毁句柄。原生不会锁住全屏广告
通道、不参与开屏/插屏/激励混合选择，也不会产生激励奖励。宿主如自行编排全屏原生及开屏广告，
可配置 `autoShowAppOpen = false` 并自行调度开屏，避免恢复页面时自动开屏覆盖原生页面。
不需要 `FULL_SCREEN_NATIVE` 类型、独立全屏原生 ID 或 SDK 内置 Activity。

[R8 示例应用](r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/SmokeActivity.kt) 提供
卡片和宿主全屏 Dialog 两种入口，复用同一个渲染器工厂。AdMob 使用 `AdMobIds.TEST`，TopOn 使用
`short-drama/config/app.properties` 的 `dev` 应用及 `dev.toponNativeId=n1h7t76th560qm`，
未使用 `dev.toponFullNativeId`。这些开发 ID 仅用于测试，生产接入请替换。

本地运行示例（连接测试设备后）：

```bash
./gradlew :r8-smoke-app:installDebug
adb shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --es provider admob
# 切换平台前停止进程，避免同一进程重复初始化不同平台。
adb shell am force-stop com.cashcraft.ads.mediation.smoke
adb shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --es provider topon
# provider=bidding 或不传时使用双平台竞价。
```

示例关闭了 UMP 和自动开屏，只用于开发测试；生产应用保留默认 UMP 流程。

原生功能初次验证：78 项 JVM 单元测试通过；原生展示的 Debug APK 和 Release R8 构建通过。
真机验证了 AdMob 卡片/全屏 Dialog 的曝光、收益和关闭，Google 原生校验器报告 0 个实现问题；
TopOn（Mintegral 填充）的卡片曝光和收益通过。该开发广告位后续请求返回 4001 空填充，
因此 TopOn 全屏 Dialog 的实际填充展示尚未验证。Release 构建仍有 Pangle/TikTok 可选依赖缺类告警。


原生反射取价的混淆真机验证（GMA Next-Gen 1.2.1）：

- 使用无宿主额外 keep 规则的 Release APK，通过开发签名安装；`ad_bid_result` 中出现
  `admob_price_available=true`、`admob_native_value=0`，随后有同 session 的曝光和收益回调。
  官方测试广告本次返回有效的 0 价格。
- 故障验证仅修改混淆 APK 的 JSON 反射路径为不存在的字段，其余文件（包括 DEX）保持一致。
  运行结果为 `admob_price_available=false`、`admob_native_value=unknown`；广告仍能展示和回调收益，
  没有将反射失败替换成 0。验证后设备已恢复正常 Release APK。
- 这两轮 TopOn 返回 4001 空填充；两平台均有价格时的胜者比较、未知价格回退由单元测试覆盖。
- 本地验证日志保存在 `build/verification/native-price-r8/normal.log` 和 `fault.log`（不纳入版本控制）。

### 真机 demo 与埋点核对

2026-10-13 TopOn 与短剧同机对照（三星 SM-S931U1 / Android 16）：

- 短剧 `devDebug` 使用发布的 remax `1.0.52_preloading.6`，首次运行成功加载并曝光两条原生，
  分别来自 Mintegral 和 Topon Adx；恢复短剧原包后再请求也出现 `4001`，底层为
  Mintegral `3002211:mtg bid response empty`、Adx `50105:rpm filter`。
  成功后的补缓存还出现过 `4005` 广告源过滤及 `2008` 加载过于频繁。
- 短剧存在 `adRuntimeIdConfigJson` 动态覆盖：宿主配置 → 缓存非空字段 → 远程非空字段 →
  SDK 初始化。两次启动的实际远程 JSON 均确认 TopOn App ID、App Key、Native ID 与 demo 一致，
  原生请求实际使用 `n1h7t76th560qm`。这里只记录 Key 相等性，不复制 Key 到验证报告。
- 初始化及普通原生请求一致：Application Context、空网络初始化列表、等待初始化回调、
  `TUNative(...).makeAdRequest()`，普通原生均未设置 `localExtra`。
  短剧独立全屏原生入口才传宽高，并使用另一个广告位。
- 版本差异：短剧 / demo 的 TopOn core 为 `6.6.40` / `6.6.22.3`，Mintegral SDK 为
  `17.1.61` / `17.1.71`，Adx SDK 为 `6.5.79` / `6.5.77`，对应适配器也不同。
  demo 原版 Release、原版 Debug、对齐现有网络依赖版本、使用短剧包名，以及版本和包名同时
  对齐的对照包，本轮均返回 `4001`，没有证据表明改版本或包名即可解决。
- 结论边界：短剧自身的填充结果也会变化；已确认动态 TopOn 凭据没有差异，但尚未定位唯一根因。
  顺序发起的线上请求受实时填充/过滤状态影响，不能将这些结果当作确定性的服务端规则证明。
  包名对照保留了短剧现有数据，未清数据；版本对齐未额外引入短剧所有其他网络适配器。
- 对照脚本、APK、日志和 `comparison.json`、`runtime-id-comparison.json` 位于
  `build/verification/topon-short-drama/`。未修改短剧源码或正式依赖；设备已恢复短剧原开发包
  和 demo 原混淆包，并核对安装 APK 的 SHA-256 一致。

2026-10-13 反射配置简化后的 R8 真机复测：移除 `regular`、改用 `nativeAdPaths`，
并将 `priceFields` 提升为共用字段后，重新构建 Release，在三星 SM-S931U1 / Android 16 验证。

- 正常包：原生卡片、全屏 Dialog 均读到有效价格 `0`，`admob_price_available=true`，
  竞价选中 AdMob，曝光、收益、关闭齐全；Google 原生校验器报告无实现问题。
- 故障包：仅将 APK 内 `nativeAdPaths` 改为不存在的字段，三个 DEX 与正常包完全一致。
  卡片、全屏竞价候选均为 `price_usd=null`、`admob_price_available=false`，没有伪造零价格；
  未知价格的可用广告仍可兜底展示，曝光、收益、关闭正常。
- 混合竞价：AdMob 激励/插屏价格均为 `0`，TopOn 激励价格为 `0.0004113` USD，
  正确选中 TopOn，完成曝光、收益、奖励和关闭。首次在原生 Dialog 未关闭时触发，
  按预期返回 `activity_window_not_focused`；关闭 Dialog 后重试成功。
- 恢复正常包后再次展示原生，价格恢复为有效 `0`。共 7 个 session：`ad_position=7`、
  曝光 6、失败 1，竞价与埋点审计无错误，三个测试进程均无应用崩溃记录。
- TopOn 原生本轮仍返回 4001 空填充，未覆盖两平台原生同时有价的真机比较。
- 证据保存在 `build/verification/native-config-r8/`：`normal.log`、`fault.log`、`restored.log`、
  `verification.json`、`audit.json` 和截图。正常包 `release.apk` SHA-256 为
  `cac045284bf108d07a2f19bffa6c782150b3b04ceb9873789a701cbf23538209`，设备已恢复该包。

`r8-smoke-app` 同时是可交互 demo 和 R8 消费端。首次打开可选择 AdMob、TopOn 或 Bidding；
SDK 每个进程只初始化一次，切换模式需强制停止后重新打开。界面包含全部四种已支持广告类型
（开屏、插屏、激励、原生）、两种混合竞价，以及插屏关闭发业务奖励的策略示例。
卡片和宿主全屏原生参考 short-drama 的素材排版，XML 位于 `r8-smoke-app/src/main/res/layout/demo_native_*`，
两平台共用素材布局，`SmokeNativeRenderers` 分别绑定 SDK 资产。全屏窗口和关闭按钮由 demo 管理。

界面显示缓存状态、最近一次竞价的所有候选价格与赢家，以及当前进程的 session 统计。
未知价格显示 `null`。`AdsDemoAudit` 日志记录公开 `AdEventListener` 实际收到的 JSON 事件，
`AdsDemoResult` 记录业务回调。现有事件名是 **`ad_position`**（不是 `ad_postion`）。

对已结束的展示 session，应满足 `ad_position = ad_impression + ad_show_fail`，且每个
`session_id` 恰好 1 次 position、1 次曝光或失败。渲染完成不等于曝光；尚未曝光又未销毁的原生
会显示 pending，销毁后补 `destroyed_before_impression`。曝光后的关闭只产生 close，不再补失败。
加载失败 `ad_load_result` 不计入展示失败；混合竞价只为一次展示机会记一个 position，按实际展示类型统计。
进程被系统直接杀死时无法补发回调，因此核对前应通过 Close / Destroy / Finish 正常结束展示。

可通过 intent 重放操作，不需要依赖按钮坐标（需在广告关闭回到 demo 后再执行下一个 action）：

```bash
adb shell am force-stop com.cashcraft.ads.mediation.smoke
adb shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --es provider bidding
adb shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --es action native_card
# 其他 action：app_open、interstitial、rewarded、mixed_open、mixed_reward、mixed_reward_policy、
# native_fullscreen、preload_native、destroy_native、native_destroy_early、native_missing_renderer、native_render_error、finish
# 首次启动附加 --es auto app_open 或 --es auto mixed 可验证前后台自动开屏；默认 off。
adb logcat -d -s AdsDemoAudit:I AdsDemoResult:I > events.log
python3 scripts/audit-demo-events.py events.log
```

核对脚本按 session 检查等式、重复、顺序、维度一致性，并根据公开候选信息重新计算竞价赢家。
存在 pending、重复、缺失或错误赢家时退出码非 0。一次进程的日志只采集一次，避免重叠日志造成重复计数。

#### 2026-10-13 真机回归结果

设备：Samsung SM-S931U1 / Android 16。使用开启 R8 与资源压缩的 release 包，以本地 debug
证书签名安装；运行包无 DEBUGGABLE 标志。单元测试 **79 项通过**，release 构建与 lintVital 通过。
构建仍有前文提到的第三方 Pangle/TikTok 可选类警告；本轮应用进程未发现 FATAL EXCEPTION。

| 真机轮次 | position | impression | show_fail | 结果 |
| --- | ---: | ---: | ---: | --- |
| AdMob 手动 | 12 | 8 | 4 | 四种格式、卡片/全屏原生、两种混合与奖励策略入口；包含原生三个异常路径 |
| TopOn 手动 | 11 | 6 | 5 | 开屏、插屏、激励及混合展示成功；原生无填充；重复全屏请求被正确拒绝 |
| Bidding 手动 | 11 | 8 | 3 | 四种格式和两种混合竞价；包含原生三个异常路径 |
| AdMob 自动开屏 | 2 | 2 | 0 | 冷启动及 Home 后回前台 |
| Bidding 自动混合开屏 | 2 | 2 | 0 | 冷启动插屏胜出，回前台开屏胜出 |
| **合计** | **38** | **26** | **12** | **38 = 26 + 12，逐 session 无错误、无 pending** |

另外收到 26 次 paid、26 次 close、19 次 bid_result、9 次 reward_earned。
这些是 SDK 公开 listener 的日志核对结果，不代表下游数据平台已经完成上传和入库。

- AdMob 卡片与宿主全屏布局均真实曝光，原生校验器两者都显示 `No implementation issues found`。
- 双平台激励都可用且有价格时：AdMob 测试价 0、TopOn 0.0005737 USD，TopOn 胜出；曝光后
  paid 同为 0.0005737 USD。混合激励的四候选场景中，TopOn 0.0005637 USD 胜出。
- 最终混淆包中 AdMob 原生仍读到有效 0，TopOn 未就绪候选价格为 null。
- `native_renderer_missing`、`destroyed_before_impression`、`native_render_failed` 在 AdMob 与
  Bidding 模式各验证一次，均只有一次 position 和一次 show_fail。
- **覆盖边界**：TopOn 原生测试位在本轮多次返回 4001 `Return Ad is empty.`，卡片与全屏入口
  都已请求并核对失败事件，但新布局的 TopOn 实际曝光仍待有填充时验证。本轮奖励策略入口
  胜出的都是激励，插屏胜出后的业务奖励分支由现有单元测试覆盖，不能当作已完成真机验证。

本地证据目录（build 目录不提交）：`build/verification/demo-matrix/`。包含各轮 `.log`、汇总
`audit.json`、布局截图 `native-card.png` / `native-fullscreen.png`、最终可安装 `release.apk`。
最终 APK SHA-256：`fe57d52535bb1b50dd30a0e669740566f417f89a65b9e239d471659c5175e932`。
单平台轮次使用首个混淆包；最终包随后修正了 demo 的模式选择初始化时机与系统栏滚动裁剪，
并完成 Bidding 全量与自动开屏回归，广告库实现未在这些轮次间变更。

```bash
python3 scripts/audit-demo-events.py \
  build/verification/demo-matrix/admob.log \
  build/verification/demo-matrix/topon.log \
  build/verification/demo-matrix/bidding.log \
  build/verification/demo-matrix/auto-admob.log \
  build/verification/demo-matrix/auto-bidding.log
```
