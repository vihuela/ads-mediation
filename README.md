# Ads Mediation Android SDK

这是一个 Android 全屏广告聚合 SDK，通过统一 API 支持开屏广告、插屏广告和激励广告。
宿主可以选择直连 AdMob GMA Next-Gen、TopOn 海外版，或者同时初始化两者并按缓存广告的
单次展示收益进行端内缓存竞价。

当前稳定版本：`1.0.2`

[![打开 AI 接入提示词](https://img.shields.io/badge/AI-%E6%89%93%E5%BC%80%E5%B9%B6%E5%A4%8D%E5%88%B6%E6%8E%A5%E5%85%A5%E6%8F%90%E7%A4%BA%E8%AF%8D-2ea44f)](#10-复制给-ai完整接入提示词)

依赖坐标：

```kotlin
implementation("com.cashcraft:ads-mediation:1.0.2")
```

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
    implementation("com.cashcraft:ads-mediation:1.0.2")
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
| `appOpenPosition` | `String` | `app_foreground` | 自动开屏对应的业务场景名 |

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

TopOn 开屏会优先使用调用方传入的 `hostContainer`，否则依次尝试 Activity 的
`android.R.id.content` 和 Window DecorView。容器不存在、未附着、不可见、挂载失败或
`show()` 抛异常时不会导致宿主崩溃，而是发送 `ad_show_fail`；开启 `loggingEnabled` 时同一失败
会写入 Logcat，异常路径还会保留 throwable 堆栈。

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

`ad_paid` 用于通用可观测性；向 Tenjin 等平台传递展示级收益时应使用独立的
`revenueListener`，避免从通用事件中反推或丢失原生对象。

```kotlin
revenueListener = AdRevenueListener { payload ->
    when (payload) {
        is AdMobRevenuePayload -> {
            reportAdMobRevenue(
                valueMicros = payload.valueMicros,
                currencyCode = payload.currencyCode,
                adUnitId = payload.adUnitId,
                responseId = payload.responseId,
                mediationAdapterClassName = payload.mediationAdapterClassName,
                precisionType = payload.precisionType,
            )
        }

        is TopOnRevenuePayload -> {
            reportTopOnRevenue(
                adInfo = payload.adInfo,
                valueMicros = payload.valueMicros,
                currencyCode = payload.currencyCode,
            )
        }
    }
}
```

`AdMobRevenuePayload` 属性：

| 属性 | 说明 |
| --- | --- |
| `valueMicros` | 收益微单位原值，Tenjin AdMob ILRD 必需 |
| `currencyCode` | ISO 货币代码，平台未提供时可能为 null |
| `adUnitId` | 产生收益的 AdMob ad unit ID |
| `responseId` | GMA response ID |
| `mediationAdapterClassName` | 实际填充 adapter 类名 |
| `precisionType` | GMA 收益精度 |

`TopOnRevenuePayload` 属性：

| 属性 | 说明 |
| --- | --- |
| `adInfo` | 原始 `TUAdInfo`，对外声明为 `Any` 以避免公共 API 强绑定 TopOn 类型 |
| `valueMicros` | TopOn publisher revenue 精确换算后的微单位，缺失时为 null |
| `currencyCode` | TopOn 收益货币代码，缺失时为 null |

## 7. 竞价逻辑和兼容范围

端内缓存竞价模式会同时预加载 AdMob 和 TopOn 的同种广告。每次手动展示只比较调用当下已缓存的候选，
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
  -PVERSION_NAME=1.0.2
```

R8 smoke app 会让 AdMob、TopOn 和竞价路径都保持可达，然后构建开启压缩和资源收缩的 Release
APK，用于验证 AAR 的 consumer rules 和 GMA 反射字段。

GitHub Actions 中的 `CI` 和 `Publish GitHub Package` 都只支持在 Actions 页面手动触发，不会因
push、PR 或 tag 自动运行。

本地或手动发布 CI 都会先读取 GitHub Packages 的 `maven-metadata.xml`，找到最高的稳定
`major.minor.patch` 版本并自动递增 patch。当前为 `1.0.2` 时，下一次会发布 `1.0.3`。

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
- Maven 坐标：com.cashcraft:ads-mediation:1.0.2
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
2. 在 App 模块加入 implementation("com.cashcraft:ads-mediation:1.0.2")。
3. 为每个 product flavor 设置 manifestPlaceholders["admobApplicationId"]。不要使用生产 ID
   进行测试；开发环境可使用 AdMobIds.TEST 对应的官方测试 App ID。
4. 检查现有配置来源，选择 AdMob、TopOn 或 Bidding。不要把 App ID、App Key、ad unit ID、
   placement ID 硬编码进通用业务类；沿用项目现有 BuildConfig、properties 或 secret 体系。
5. 在自定义 Application.onCreate 中调用 Ads.initialize。默认启用 UMP；记录 onInitialized，
   但初始化失败不能导致 App 崩溃或阻塞业务流程。
6. AdsConfig 必须接入 eventListener：使用 event.name.analyticsName 作为事件名，使用
   event.analyticsParameters() 作为完整参数。保持同一 session_id，并确保初始化后的每个展示
   会话满足 ad_position = ad_impression + ad_show_fail。
7. AdsConfig 必须接入 revenueListener。AdMobRevenuePayload 原样传递 valueMicros、
   currencyCode、adUnitId、responseId、mediationAdapterClassName、precisionType 给宿主 ILRD；
   TopOnRevenuePayload 必须保留原始 adInfo 并交给 TopOn/Tenjin 收益接口，不能从 ad_paid
   通用事件反推原生收益对象。
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
