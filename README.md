# Ads Mediation Android SDK

这是一个 Android 全屏广告聚合 SDK，通过统一 API 支持开屏广告、插屏广告和激励广告。
宿主可以选择直连 AdMob GMA Next-Gen、TopOn 海外版，或者同时初始化两者并按缓存广告的
单次展示收益进行端内缓存竞价。

当前稳定版本：`1.0.5`

从当前源码接入五种广告的完整步骤见 [广告接入文档](docs/integration-guide.md)，包含依赖、初始化、全屏展示、Banner/Native 的 View 与 Compose 示例，以及事件、收益和常见问题。

工作树新增的 AdMob Banner 与 Compose 接口尚未远程发布，参见 [Banner 接入与当前验证边界](docs/banner-integration.md)；页面 Native 卡片尚未发布，View 核心、默认/业务 XML 布局及 Compose 接入见 [Native 接入说明](docs/native-integration.md)。Native 支持单平台或双 ID 实际对象比价，与全屏竞价独立；新增只读素材工厂与默认销毁的双展示策略。真实来源尚未通过恢复验收时明确降级释放。实施状态见 [Native 验证记录](openspec/changes/refactor-native-ad-loading-and-retention/verification.md)。稳定版 `1.0.5` 不包含这些新增接口。

[![打开 AI 接入提示词](https://img.shields.io/badge/AI-%E6%89%93%E5%BC%80%E5%B9%B6%E5%A4%8D%E5%88%B6%E6%8E%A5%E5%85%A5%E6%8F%90%E7%A4%BA%E8%AF%8D-2ea44f)](#10-复制给-ai完整接入提示词)

依赖坐标：

```kotlin
implementation("com.cashcraft:ads-mediation:1.0.5")
```

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
    implementation("com.cashcraft:ads-mediation:1.0.5")
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

// 接入模式：未初始化时为 null；初始化后为 ADMOB、TOPON 或 BIDDING。
val mode: AdMediationMode? = Ads.mediationMode

// 固定 provider 时返回 ADMOB/TOPON；未初始化或竞价模式返回 null，不代表本次广告来源。
val fixedPlatform: AdPlatform? = Ads.platform
```

初始化会固定本进程的配置。使用同一个 Application 和相等的 `AdsConfig` 重复调用，只订阅原来的
初始化结果，不会重启或重新配置 SDK。建议复用首次创建的配置对象；事件、收益监听器也是配置的一部分，
新建的监听器实例视为配置变化。`onInitialized` 不属于固定配置；广告库只保留一个等待中的初始化回调，
传入新回调会替换旧回调，省略回调不会清除已有等待者。通知前先清空引用，回调在主线程执行。

启动页无需轮询状态，可在主线程登记一次性回调：

```kotlin
val stopObserving = Ads.observeInitialization { success ->
    // 已完成时立即通知；success 表示至少一个平台可用，不代表广告已加载。
}
// 启动页等待超时或销毁时调用；仅解除本次监听，不取消 SDK 初始化。
stopObserving()
```

返回的取消函数不会清除后来登记的新回调。协程等待可用 `suspendCancellableCoroutine` 桥接，
通过 `invokeOnCancellation { stopObserving() }` 在超时或生命周期取消时解绑。

更换接入模式、广告 ID、许可设置、监听器或其他配置，以及更换 Application，都会同步抛出
`IllegalArgumentException`，不会返回成功或应用部分新配置。SDK 自身的初始化失败仍通过回调报告。

已创建的 Provider 配置可通过 `provider.mediationMode` 查询模式。`provider.platform` 保留旧事件
归属语义，竞价配置仍以 AdMob 作为回退值，不用于判断接入模式或竞价胜出方；`BiddingProviderConfig.platform`
已标记弃用。某次实际广告的平台应读取对应的 `AdEvent.platform` / `AdRevenuePayload.platform`，竞价结果
读取 `winnerPlatform`。

新接入统一使用 `com.cashcraft.ads.mediation.Ads` 与 `AdsConfig`。旧的 `admob.AdMobAds` 和
`admob.AdMobConfig` 已标记为 WARNING 级弃用，仅为已有宿主保留签名与行为。旧入口不经过 `Ads` 的统一
UMP 门禁，不要在同一进程混用两套初始化入口；弃用提示不会自动迁移或改变既有接入。

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
| `autoShowAppOpen` | `Boolean` | `true` | 已启用开屏格式时，App 进入前台自动尝试展示 |
| `appOpenPosition` | `String` | `app_foreground` | 自动开屏对应的业务场景名 |

开屏日志沿用原生广告的两层结构，由现有 `loggingEnabled` 控制：

- INFO/WARN：`[开屏广告][业务位置] 中文业务结果`，说明加载结果、双方报价及胜出平台、实际广告来源、曝光/关闭和失败原因，不混入完整会话或广告位 ID。
- DEBUG：`[开屏广告][调试][业务位置] 阶段 | key=value`，保留 `event/platform/sid/pos`；请求标识、竞价候选与错误详情分行，每行均有完整 `sid`。
- `platform` 是外层聚合渠道，`source` 是实际广告网络，例如 TopOn 渠道的 `source=AdMob`；未知来源不会推断为聚合渠道本身。
- `ready` 表示候选可用，`priced` 表示取得报价；`usd/winUsd` 是美元/次展示，未知不等于 0。收益回调优先用 `valueMicros` 显示准确金额，不表示已经到账。
- `unit/req/resp` 是广告位/请求/响应标识，`load=150ms` 是加载耗时，`buffer` 是加载事件携带的预加载容量。加载与展示的 `sid` 独立，SDK 的加载响应 ID 与展示 ID 也不保证相同。

`adb logcat -v threadtime AdsMediation:I '*:S'` 可只看业务结果；改为 `AdsMediation:D` 查看完整明细。
宿主可复用纯格式化函数 `AdEvent.appOpenLogLines()`：首行为业务说明，其余为调试明细，函数本身不打印。发布宿主应将 `loggingEnabled` 设为 `BuildConfig.DEBUG`。

### 2.2 Provider 参数

AdMob 直连：

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

`AdMobPreloadConfig` 表示 GMA Next-Gen 每种广告持续补充的缓存上限，取值范围为 `0..15`，
三种全屏格式默认各 `2`，Banner 默认 `1`。全屏格式设为 `0` 会关闭该格式；`banner = 0`
只关闭显式 Banner 预加载，Banner View 仍可按自己的请求加载。开发和自动化测试可使用
`AdMobIds.TEST`，生产包必须注入真实 ID。

**按格式启用（本工作树新增，尚未远程发布）：** 全屏广告 ID 可省略，默认空字符串表示关闭；
应用 ID／TopOn App Key 仍必填，纯空白字符串仍会被拒绝。已有全量 ID 和默认缓存配置的行为保持不变。

```kotlin
// 只用 AdMob 激励：不需要开屏、插屏 ID，也不会预加载这两种广告。
val rewardedOnly = AdMobProviderConfig(
    ids = AdMobIds(applicationId = "AdMob App ID", rewardedId = "Rewarded Ad Unit ID"),
)

// 只用 Banner：初始化配置默认广告位，页面只传 position。
val bannerOnly = AdMobProviderConfig(
    ids = AdMobIds(applicationId = "AdMob App ID", bannerId = "Banner Ad Unit ID"),
)

// 只用 TopOn 激励：同样省略其他 placement ID。
val topOnRewardedOnly = TopOnProviderConfig(
    ids = TopOnIds("TopOn App ID", "TopOn App Key", rewardedPlacementId = "Rewarded Placement ID"),
)

val enabled = rewardedOnly.isFormatEnabled(AdFormat.REWARDED) // true
```

选择需要的 provider 传给 `AdsConfig`。未启用的格式不创建 SDK 广告对象、不预加载或补充加载，
`Ads.isReady(format)` 返回 `false`；调用立即展示或等待展示都会直接得到
`AdShowResult.Failed("ad_format_disabled")`。激励结果中的 `rewardEarned` 为 `false`。
未启用开屏时，即使 `autoShowAppOpen = true` 也不会启动自动开屏机会。
Banner 页面可直接使用 `AdsBanner(position = "home_bottom")`，Fragment 在 `onViewCreated`
使用 `bindBanner(bannerContainer, position = "home_bottom")`，自动随 View owner 释放。
便捷入口要求先调用 `Ads.initialize()` 并配置 `bannerId`，无需等待 SDK 初始化完成。
Activity 公共底栏可用 `Ads.bannerRequest(position, size)` 配合 `AdsBannerView`；
HealthTracker 的实际接入、紧凑尺寸及预加载约定见 [宿主接入记录](docs/banner-integration.md#healthtracker-宿主接入)。
需要独立广告位时仍可显式传 `BannerRequest`；高级业务启停由 `active` 控制，TopOn Banner 仍不支持。

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
| `admob` | `AdMobProviderConfig` | 必填 | AdMob App ID、按需填写的 ad unit ID，以及各格式缓存数量 |
| `topon` | `TopOnProviderConfig` | 必填 | TopOn App ID、App Key，以及按需填写的 placement ID |

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

其中 `admob.preload` 控制 AdMob GMA Next-Gen 的持续缓存数量，零值关闭对应全屏格式。TopOn 的加载和缓存策略由
TopOn SDK 管理，目前没有在 `BiddingProviderConfig` 暴露缓存数量。手动插屏和激励展示不会等待
新广告加载，只比较调用瞬间已经缓存的候选；自动开屏最多等待 7 秒，这个时长当前也不是公开
配置项。

## 3. 展示广告

需要等待广告就绪、并让展示资格绑定到页面或业务场景时，请使用[全屏展示机会接入说明](docs/fullscreen-display-opportunities.md)。其中包含开屏、插屏、激励的新 API、取消和超时结果、Activity/Navigation/Compose 生命周期接入，以及关闭旧自动开屏的迁移方式。

`position` 保留接入方传入的业务场景 ID（去除首尾空白，空值使用 `unknown`），不追加广告类型后缀。
展示相关日志和事件上报使用同一个 ID，广告类型由独立的 `ad_type` 字段表示。
加载事件（`ad_load_request` / `ad_load_result`）不输出或上报 `position`，以 `request_id` / `session_id` 关联加载过程。

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

```

`AdShowResult.Dismissed` 表示全屏广告产生展示并最终关闭。全屏与 Banner 失败共用
`AdShowResult.Failed`，业务统一通过 `is AdShowResult.Failed` 处理 `reason`；Banner 的
`onState` 仍接收 `BannerState`，其失败不代表此前没有曝光或收益。Banner 接入与迁移见
[Banner 接入说明](docs/banner-integration.md)。

手动展示只接受当前位于前台、处于 Resumed 状态且 Window 已附着并获得焦点的 Activity。
不满足条件时不会消费预加载广告，而是通过 `AdShowResult.Failed` 和 `ad_show_fail` 收口；稳定
失败原因包括 `app_not_in_foreground`、`activity_not_resumed`、
`activity_window_not_attached` 和 `activity_window_not_focused`。

AdMob、TopOn 与竞价模式共用同一套宿主生命周期状态和自动开屏前台窗口：每次进入前台后
最多等待 7 秒，Activity 可交互且广告可用时展示；进入后台或等待超时则以 `ad_show_fail`
结束本次机会。各平台的初始化、加载、缓存补充、收益和 SDK 回调仍分别由各自实现负责。

TopOn 开屏由库内部依次尝试 Activity 的
`android.R.id.content` 和 Window DecorView。容器不存在、未附着、不可见、挂载失败或
`show()` 抛异常时不会导致宿主崩溃，而是发送 `ad_show_fail`；开启 `loggingEnabled` 时同一失败
会写入 Logcat，异常路径还会保留 throwable 堆栈。

`AdRewardResult` 包含：

| 属性 | 说明 |
| --- | --- |
| `rewardEarned` | 广告平台是否明确触发奖励回调，业务只能据此发奖 |
| `showResult` | 本次展示是关闭还是失败 |
| `sessionId` | 将广告奖励与业务发奖事件关联起来的会话 ID |

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
| `position` | 非加载事件的原始业务场景 ID；`ad_load_request` / `ad_load_result` 不携带此字段 |
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

端内缓存竞价只创建和预加载各平台已启用的格式，两家可以启用不同格式；等待展示不会等待已关闭格式的平台。
每次手动展示只比较调用当下已缓存的候选，
不会为了等待网络加载而阻塞业务：

1. 只有一个平台有缓存时，直接选择该平台，即使价格为零。
2. 两个平台都有缓存时，已知价格优先于未知价格。
3. 两边价格都已知时选择 USD 单次展示收益更高者。
4. 价格相同或都未知时，使用 AdMob 作为确定性兜底。
5. 两边都没有缓存时，产生 `ad_bid_result(result=no_candidate)` 和
   `ad_show_fail(reason=no_preloaded_ad)`。

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

本地完整验证：

```shell
./gradlew clean testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease publishToMavenLocal \
  -PVERSION_NAME=1.0.5
```

R8 smoke app 会让 AdMob、TopOn 和竞价路径都保持可达，然后构建开启压缩和资源收缩的 Release
APK，用于验证 AAR 的 consumer rules 和 GMA 反射字段。

GitHub Actions 中的 `CI` 和 `Publish GitHub Package` 都只支持在 Actions 页面手动触发，不会因
push、PR 或 tag 自动运行。

本地或手动发布 CI 都会先读取 GitHub Packages 的 `maven-metadata.xml`，找到最高的稳定
`major.minor.patch` 版本并自动递增 patch。当前为 `1.0.5` 时，下一次会发布 `1.0.6`。

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
VERSION_NAME=2.0.0 ./scripts/publish-next-version.sh
```

GitHub Package 版本不可覆盖；脚本默认自动递增可避免重复版本上传。

## 10. 复制给 AI：完整接入提示词

点击下面代码块右上角的复制按钮，把整段内容发送给能访问你 Android 工程的 AI。AI 应根据
现有项目结构完成接入、编译和测试，而不是只生成示例代码。

```text
请在当前 Android 工程中完整接入 Ads Mediation SDK，并直接修改、编译和验证项目。

固定信息：
- Maven 坐标：com.cashcraft:ads-mediation:1.0.5
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
2. 在 App 模块加入 implementation("com.cashcraft:ads-mediation:1.0.5")。
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
