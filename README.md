# Ads Mediation Android SDK

这是一个 Android 全屏广告聚合 SDK，通过统一 API 支持开屏广告、插屏广告和激励广告。
宿主可以选择直连 AdMob GMA Next-Gen、TopOn 海外版，或者同时初始化两者并按缓存广告的
单次展示收益进行端内缓存竞价。

当前版本：`1.0.11`（Maven group：`com.cashcraft.sdk`）

从当前源码接入五种广告的完整步骤见 [广告接入文档](docs/integration-guide.md)，包含依赖、初始化、全屏展示、Banner/Native 的 View 与 Compose 示例，以及事件、收益和常见问题。

1.0.6 包含 AdMob Banner 与 Compose 接口，参见 [Banner 接入与当前验证边界](docs/banner-integration.md)；1.0.6 同时包含页面 Native 卡片，View 核心、默认/业务 XML 布局及 Compose 接入见 [Native 接入说明](docs/native-integration.md)。Native 支持单平台或双 ID 实际对象比价，与全屏竞价独立；新增只读素材工厂与默认销毁的双展示策略。真实来源尚未通过恢复验收时明确降级释放。实施状态见 [Native 验证记录](openspec/changes/refactor-native-ad-loading-and-retention/verification.md)。`1.0.5` 不包含这些新增接口。

[![打开 AI 接入提示词](https://img.shields.io/badge/AI-%E6%89%93%E5%BC%80%E5%B9%B6%E5%A4%8D%E5%88%B6%E6%8E%A5%E5%85%A5%E6%8F%90%E7%A4%BA%E8%AF%8D-2ea44f)](#10-复制给-ai完整接入提示词)

依赖坐标：

```kotlin
implementation("com.cashcraft.sdk:ads-mediation:1.0.11")
```

### 1.0.11 更新

- 修复 `ad_bid_result` 金额类型：未知或无效报价省略字段，已知报价保持数值，真实零价保留。
- 包含新增的零价、无胜者、负数及非有限金额回归覆盖；统计后台既有字段类型需由业务方核对。
- 新 group 的原 `1.0.10` 保留，本版使用新版本号正常发布。
- 业务更新步骤及验证边界见 [1.0.11 发布说明](docs/sdk-1.0.11-release.md)。

### 1.0.10 更新

- 使用独立 Maven group `com.cashcraft.sdk`，artifactId、GitHub Packages 仓库及源码包名保持不变。
- 包含当前开发线的 Banner、Native View/Compose、场景任务、频控及 v2 事件与收益契约。
- 包含 TopOn 回调归属、策略通知、后台补库存及 Banner 展示配额修复。
- 从旧 group 迁移时必须移除旧坐标，重新编译宿主及依赖模块；新旧包包含同名类，不能同时引入。
- 业务更新步骤及验证边界见 [1.0.10 发布说明](docs/sdk-1.0.10-release.md)。

### 1.0.6 更新

- 包含 Banner、Native 的 View/Compose 接入和预加载、缓存管理。
- 新增开屏/插页场景任务，统一场景配额和 v2 广告事件、展示收益契约。
- 含公共 API 与事件契约变更，升级时请按当前接入文档迁移并验证宿主。

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


## 开屏场景任务

业务使用 `Ads.showOpen(position) { result -> ... }`，返回可取消的 `AdTask`（`id` 用于关联日志）。
初始化时通过 `AdsConfig.openTimeoutMillis` 配置各 position 的等待预算，通过
`AdsConfig.nativeFullScreenLayout` 一次配置原生兜底布局。现有 `showAppOpenWhenReady`、
`showRewardedWhenReady` 仍保留原有行为。

旧自动开屏配置 `autoShowAppOpen`、`appOpenPosition` 已标记为弃用（`WARNING`），兼容行为和默认值保持不变。
新接入或迁移时显式设置 `autoShowAppOpen = false`，在业务入口调用 `Ads.showOpen(position)`，
原 `appOpenPosition` 通过该调用的 `position` 传入。已有任务回前台后会继续；任务结束后如需再次展示，需重新调用。

`showOpen` 同时等开屏/插页 × AdMob/TopOn：全部候选返回或预算用完后，先做同类型平台比价，
再比较两个类型胜者；有效 USD/次展示报价越高越优先，已知价优先未知价，同价优先开屏、AdMob。
没有候选时直接领取全屏原生缓存，原生也没有则结束，不追加网络等待。
宿主暂停时冻结预算，恢复后继续等待；取消、销毁或选定广告后不会因迟到结果重新展示。
底层加载与缓存保留给后续任务。选定广告的 SDK 展示失败直接结束，不继续追加广告。

日志遵循 `loggingEnabled` / `logTag`，使用中文描述完整流程：候选状态 → 等待结束 →
确定本次候选 → 一条多行候选表格 → 紧接一条最终比价结果 → 展示或全屏原生兜底 → 任务结束。
表格列出四个候选的类型、平台、状态、报价及类型内胜者；两条日志使用同一个任务 ID。
统一前缀为 `[开屏任务][任务=完整ID][位置=业务位置][总耗时=123毫秒]`。
“展示记录”关联实际广告 SDK 会话，“交接记录”关联原生选中、曝光及结束；
“已提交展示”不代表真实曝光。失败先说明中文原因，再附原始原因码。
“总耗时”包含后台时间，“已等待”不含后台暂停；迟到加载继续产出全局加载日志，不再参与本次决策。


## 插页场景任务

业务调用 `Ads.showInter(position) { result -> ... }`，返回 `AdTask`。旧插页等待入口已删除，调用方需同步迁移。

需要等待提示时传入 `onLoadingChanged`：主线程通知 `true` 表示进入等待，`false` 表示展示交接前或等待结束，宿主应立即清理自己的 loading。回调可能同步执行；未进入等待的请求不会通知 `true`，最终业务收尾仍使用 `onResult`。
通过 `AdsConfig.interTimeoutMillis(position)` 一次配置等待预算（默认 3 秒），原生布局复用 `nativeFullScreenLayout`。
只等待已启用的 AdMob / TopOn 插页：全部返回或超时后在有效插页中择优；没有插页（包括均未启用）才取全屏原生缓存。
原生与插页不跨类型比价，不追加加载等待，沿用原始 position，不加后缀。无缓存则结束，选定广告的展示失败不追加兜底。
等待期间暂停宿主、进入后台、切换/销毁页面或主动取消会结束任务；不会在恢复前台后补弹，底层加载仍可继续。
整个流程共用一次全屏占用，最多展示一条、最终回调一次；已交给广告 SDK 的展示不能强制关闭，本库原生可以取消关闭。
日志前缀为 `[插页任务]`，一条两平台候选表后立即输出一条结果，保留完整任务 ID 与原始 position。
场景失效由宿主保存的 `AdTask.cancel()` 取消；取消可能同步回调，业务应先标记失效再取消，避免误导航。

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
    implementation("com.cashcraft.sdk:ads-mediation:1.0.11")
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

如果宿主把首次初始化延后到网络恢复，而 Activity 已经完成 `onResume`，在主线程调用
`Ads.initialize(activity, config)`。该重载同步绑定当前宿主后开始 UMP，无需额外一次恢复事件；
它要求 Activity 未关闭或销毁，且 `LifecycleOwner` 状态为 `RESUMED`。普通 Activity 只接受
已被本库监听为当前恢复宿主的实例。原 Application 重载及其配置一致性、单一回调规则保持兼容。
此重载属于当前源码新增接口，稳定版 `1.0.5` 尚不包含。

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
                    val parameters = event.analyticsParameters()
                    if (parameters.isNotEmpty()) analytics.logEvent(event.name.analyticsName, parameters)
                },
                revenueListener = { payload ->
                    when (payload) {
                        is AdMobRevenuePayload -> reportAdMobRevenue(payload)
                        is TopOnRevenuePayload -> reportTopOnRevenue(payload)
                    }
                },
                loggingEnabled = BuildConfig.DEBUG,
                logTag = "AdsMediation",
                autoShowAppOpen = false, // 关闭已弃用的自动开屏，由业务调用 Ads.showOpen(position)。
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
| `autoShowAppOpen` | `Boolean` | `true` | 已弃用；兼容旧前台自动展示。新接入设为 `false`，由业务调用 `Ads.showOpen(position)` |
| `appOpenPosition` | `String` | `app_foreground` | 已弃用；仅供旧自动开屏使用。迁移后通过 `showOpen(position)` 传入 |

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

**按格式启用（1.0.10）：** 全屏广告 ID 可省略，默认空字符串表示关闭；
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
    autoShowAppOpen = false, // 开屏由业务调用 Ads.showOpen(position)。
)
```

其中 `admob.preload` 控制 AdMob GMA Next-Gen 的持续缓存数量，零值关闭对应全屏格式。TopOn 的加载和缓存策略由
TopOn SDK 管理，目前没有在 `BiddingProviderConfig` 暴露缓存数量。手动插屏和激励展示不会等待
新广告加载，只比较调用瞬间已经缓存的候选；自动开屏最多等待 7 秒，这个时长当前也不是公开
配置项。

## 3. 展示广告

需要等待广告就绪、并让展示资格绑定到页面或业务场景时，请使用[全屏展示机会接入说明](docs/fullscreen-display-opportunities.md)。其中包含开屏、插屏、激励的新 API、取消和超时结果、Activity/Navigation/Compose 生命周期接入，以及关闭旧自动开屏的迁移方式。

`position` 保留接入方传入的业务场景 ID（去除首尾空白，空值使用 `unknown`），不追加广告类型后缀。
展示相关日志和事件上报使用同一个 ID，广告类型由独立的 `ad_format` 字段表示。
加载事件（`ad_load` / `ad_loaded` / `ad_load_fail`）以独立 `request_id` 关联加载过程，不携带业务场景或展示会话。

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
    val parameters = event.analyticsParameters()
    if (parameters.isNotEmpty()) analytics.logEvent(event.name.analyticsName, parameters)
}
```

事件名：

| 枚举 | 上报名称 | 说明 |
| --- | --- | --- |
| `LOAD` | `ad_load` | 本层真实 SDK 加载开始；SDK 自动加载在结果回调时补报并标记 `load_trigger=sdk_auto` |
| `LOADED` | `ad_loaded` | 加载成功 |
| `LOAD_FAIL` | `ad_load_fail` | 加载失败、超时或取消 |
| `SCENE_SKIP` | `ad_scene_skip` | 展示机会建立前被策略拦截 |
| `POSITION` | `ad_position` | 全部频控、开关与展示资格通过后、比价前的展示需求；同一机会不因重试或兜底重复 |
| `BID_RESULT` | `ad_bid_result` | 比价结果，包含各平台候选与报价、胜出平台和胜出价 |
| `SHOW` | `ad_show` | 平台确认实际曝光，每次展示最多一条，不依赖收益 |
| `IMPRESSION` | `ad_impression` | 按下表的平台回调发送，可携带展示级收益 |
| `SHOW_FAIL` | `ad_show_fail` | 本次展示机会未产生展示并已失败收口 |
| `CLICK` | `ad_click` | 用户点击广告 |
| `DISMISS` | `ad_close` | 全屏实际曝光后的关闭或 Native 卡片关闭；Banner 不进入业务参数 |
| `REWARD` | `ad_reward` | 激励广告平台确认奖励 |
| `BANNER_REFRESH` | `ad_banner_refresh` | SDK 刷新诊断，不作为业务事件上报 |

`ad_impression` 的时机与 EasyLoanCalc 一致，不再发送业务 `ad_paid`：

| 平台 / 形式 | 业务事件触发回调 |
| --- | --- |
| AdMob 开屏、插屏、激励、Native（含全屏 Native）、Banner | `onAdPaid` |
| TopOn 插屏、激励 | `onAdRevenuePaid` |
| TopOn 开屏 | 通过展示身份确认的 `onAdShow` |
| TopOn Native（含全屏 Native） | `onAdImpressed` |

TopOn Banner 当前未提供生产实现。`ad_show` 覆盖全屏、Banner 和 Native（含全屏 Native），只由平台实际曝光回调触发；加载成功、可见、恢复显示或收到收益都不能代替实际曝光。每次展示最多一条，已以 `ad_show_fail` 收口后的迟到曝光不发送；已曝光的展示不再发送 `ad_show_fail`。Banner 页面刷新后的新广告独立计数。
`ad_show` 业务参数同 `ad_click`：`ad_format`、`ad_platform`、`mediation_mode`、`position_id`、`ad_session_id`，以及可用时的 `ad_unit_id`、`ad_source`；Banner 另有 `refresh_index`。它不含 `revenue_amount`、`currency`、`precision_type`。
同一展示的 `ad_show`、`ad_impression` 与专用收益交付各自去重，顺序不限；TopOn 开屏在展示身份确认后、Native 按原有规则，在同一展示回调内先发 `ad_show`，再发 `ad_impression`。收益晚于页面关闭时，使用原场景及原会话。
收益回调先到时不伪造实际曝光通知。收益回调驱动的业务事件在没有有效收益时不会发出，
因此不再保证 `ad_position = ad_impression + ad_show_fail`；失败后的有效迟到收益也不会被丢弃。
TopOn 开屏在展示身份确认后、Native 按原有规则，在展示回调缺少金额时仍发送 `ad_show` 和不带金额的 `ad_impression`，不把未知收益填成零。

TopOn 开屏 `onAdShow`、插屏 `onInterstitialAdShow`、激励 `onRewardedVideoAdPlayStart` 在登记 showId/收益关联前，必须通过现有 `revenueTracker.confirm(revenueIdentity(info, session.format), session)` 确认属于本次展示。无 token 但已有可靠 showId → 当前 session 关联仍接受；无 token 且 showId 未知、缺失或空白时拒绝，不凭 active 会话兜底。被拒绝的回调不登记身份、不发送 `ad_show`、不设置 `hasImpression`，也不触发开屏业务 `ad_impression`、展示频控或该回调的 `afterShow` 补量。

若整次展示始终没有可信展示身份，即使收到合法收益也不补发 `ad_show`；未确认曝光即关闭沿用 `Failed(dismissed_before_impression)`，开屏现有 2 秒 `show_callback_timeout` 仍生效。收益 tracker、`revenuePaid` / `deliverRevenue` 的归属、去重与交付策略不变。此次仅收紧上述三个正向展示回调，点击、关闭、奖励、失败回调仍沿用原有校验，不能据此认为其他回调已完全隔离。

关闭回调按既有规则被接受后仍释放全屏门；开屏超时仍失败收尾、移除容器并释放全屏门。激励奖励资格与展示确认独立：无可信曝光但按原规则收到奖励后关闭时，可能返回 `rewardEarned=true` 且 `showResult=Failed(dismissed_before_impression)`。

竞价前失败没有 `ad_bid_result`。

`AdEvent.analyticsParameters()` 直接提供 v2 业务字段，宿主无需转换事件名或字段。
刷新诊断事件（`BANNER_REFRESH`）、Banner 的 `DISMISS` 以及不满足业务契约的事件返回空 Map；`BID_RESULT` 作为业务事件输出，宿主只转发非空业务参数。
`AdEvent` 的原始类型化属性仍用于 SDK 日志、回调诊断和专用收益通道，不会全部进入业务埋点。

| v2 analytics key | 说明 |
| --- | --- |
| `ad_format` | `app_open`、`interstitial`、`rewarded`、`native` 或 `banner` |
| `ad_platform` | `admob`、`topon`；选定平台前的失败为 `unknown`；`ad_position` 不传 |
| `mediation_mode` | `admob`、`topon` 或 `bidding` |
| `position_id` / `ad_session_id` | 非加载事件的业务场景和展示会话；Banner 和 Native 同一页面周期使用 `slot_id` 作为业务会话，刷新与重试共用；场景拦截只含 `position_id` |
| `request_id` | 加载事件独立请求 ID，不携带展示会话 |
| `latency_ms` | 本层可观测加载成功/失败的非负耗时；`sdk_auto` 不传 |
| `load_trigger` | SDK 自动加载补报时为 `sdk_auto`；适用于 AdMob 全屏及 Native，开始时刻不可观测 |
| `ad_unit_id` | 选定平台后可用的广告单元；`ad_position` 不传 |
| `ad_source` | `ad_loaded`、`ad_show`、`ad_impression`、`ad_click` 及有胜者的竞价事件上可用的实际广告源；有效 `ad_show_fail` 必传失败素材来源，不可确认为 `"unknow"`；`ad_load_fail` 不传 |
| `reason` / `error_code` | 受控失败/拦截类别与可用的 SDK 主错误码；`exception` 不传 `error_code` |
| `reason_detail` | 失败的 SDK 内部细分原因码，按下文规则输出；不是错误文本 |
| `error_detail` / `error_detail_truncated` | 平台错误文本；去除首尾空白后最多 8192 字符，截断时标记 `true` |
| `revenue_amount` / `currency` | `ad_impression` 上可用的收益主货币单位金额和 ISO 币种；SDK 返回零金额时保留零 |
| `precision_type` | 收益精度类型 |
| `refresh_index` | 仅 Banner 的 `ad_show` / `ad_impression`：同一页面周期内广告序号，首条为 1；新广告递增，重复回调和刷新失败不递增，迟到收益保留原序号 |

业务参数不包含 `position`、`session_id`、`slot_id`、`number`、`response_id`、
`value_micros`。Firebase 保留事件的过滤由宿主上报器处理；独立收益回调继续使用原始收益 payload。

分析平台的属性列表按事件累积，旧版本发过的属性会一直保留；`position` 列显示 `-` 表示该事件来自 v2 及之后的版本，并非重复上报。已废弃属性与替代关系：

| 已废弃属性 | 替代 | 变更版本 |
| --- | --- | --- |
| `position` | `position_id` | v2 契约（2026-10-05） |
| `session_id` | `ad_session_id` | v2 契约（2026-10-05） |
| `ad_type` | `ad_format` | 2026-10-11 |
| `number` | 无替代，不再上报 | v2 契约（2026-10-05） |

筛选或分组时同时覆盖新旧属性才能对比历史数据；两者不会在同一条事件里并存。

`ad_bid_result` 复用 `position_id`、`ad_session_id` 和 `mediation_mode`，并提供：

| 字段 | 说明 |
| --- | --- |
| `result` | `won` 或 `no_candidate`，统一全屏与 Native 比价结果 |
| `ad_platform` / `winner_platform` | 胜出平台；无胜者为 `unknown`，此时不传 `ad_unit_id` |
| `admob_available` / `topon_available` | 比价时该平台是否有可用素材；不是加载请求是否发起 |
| `admob_price_available` / `topon_price_available` | 是否有有效报价；未知、负数、非有限值不作为有效价格 |
| `admob_value` / `topon_value` / `winning_value` | USD 每次展示报价，不是 eCPM；仅传合法数值，未知或无效金额省略字段，真实零价保留；无胜者时省略 `winning_value` |
| `currency` | `USD` |
| `admob_ad_unit_id` / `topon_ad_unit_id` | 可用时记录候选平台广告单元；配置了广告单元不代表已有素材 |

有效 `ad_show_fail` 在所有格式和平台下均包含 `ad_source`：使用失败素材可确认的非空 `AdEvent.adSource`，为 null 或空白时由 `analyticsParameters()` 输出字面量 `"unknow"`（固定拼写），原始属性仍可为空。该字段描述失败素材的来源，不表示故障归因；没有实际素材的失败（如无填充、等待超时、取消或未选定平台）使用 `"unknow"`，不从缓存候选猜测。未选平台仍为 `ad_platform=unknown`，不输出配置广告单元。非法事件仍返回空 Map；本契约不改变其他字段、已曝光后的终态或 `ad_load_fail` 的业务输出。

AdMob 全屏在取出广告后保存其 `ResponseInfo` 来源，展示调用抛异常或 SDK 回调失败时沿用；TopOn 全屏仅在非空 `showId` 已映射到具体会话实例时保存回调来源，不能仅凭当前活跃会话或回调被放行来确认归属。缺失、空白或未登记的 `showId` 不更新失败来源，收益回调也不隐式更新；此前无已确认来源时仍为 `"unknow"`，即使失败回调带有 `networkName`。Native 和 Banner 使用当前素材已经保存的来源，重试或刷新不能沿用上一条素材的来源。按广告源排查失败时需保留 `"unknow"` 分组；计算失败率还需要同口径的展示尝试分母，`ad_position` 不提供广告源。

### 失败与拦截原因

以下左列是业务参数 `reason`，右列是原始 `AdEvent.reason` 的归类。不要将原始日志原因码直接当成业务类别。

| 业务 `reason` | 原始原因码或判定条件 |
|---|---|
| `no_fill` | `no_fill`、`no_preloaded_ad`、`no_candidate` |
| `timeout` | `timeout`、`wait_timeout`、`load_timeout`、`native_load_timeout`、`native_bid_timeout` |
| `cancelled` | `cancelled`、`opportunity_cancelled`、`native_cancelled` |
| `ad_busy` | `ad_busy`、`already_showing`、`fullscreen_busy`、`another_full_screen_ad_showing`、`request_in_progress` |
| `platform_disabled` | 失败原因为 `ad_platform_disabled`，或 skip 原因为 `platform_disabled` |
| `scene_inactive` | `scene_inactive`、`scene_invalid`、`activity_not_available`、`activity_not_resumed`、`app_not_in_foreground`、`native_inactive`、`native_destroyed`、`native_temporarily_unavailable` |
| `exception` | 优先判定 `errorCode=load_exception`；或原因为 `exception`、`load_exception`、`banner_configuration_failed`、`banner_callback_configuration_failed` |
| `global_disabled` | `global_disabled` |
| `position_disabled` | `position_disabled` |
| `new_user_protection` | `new_user_protection` |
| `fullscreen_gap` | `fullscreen_gap` |
| `show_rate_limited` | `daily_show_limit`、`show_rate_limited` |
| `click_rate_limited` | `daily_click_limit`、`click_rate_limited` |
| `ad_error` | 未命中上述映射，且 `result` 不是 `no_fill`、`timeout`、`cancelled` 的其他失败 |

失败事件依次判定 `errorCode=load_exception`、原始原因映射、skip 映射，最后按 `result=no_fill/timeout/cancelled` 回退到同名类别，否则为 `ad_error`。
`ad_scene_skip` 只接受 `skipReasons` 的九个原始值：`global_disabled`、`platform_disabled`、`position_disabled`、`new_user_protection`、`fullscreen_gap`、`daily_show_limit`、`daily_click_limit`、`show_rate_limited`、`click_rate_limited`；其他值返回空业务参数。部分准入前拦截没有事件，不能据此覆盖全部拦截量。

`reason_detail` 仅用于 `ad_load_fail` / `ad_show_fail`，按顺序判定：先排除业务 `reason=exception`；再要求原始原因匹配 `^[a-z][a-z0-9_]{0,63}$` 且不同于上报的 `reason`，才输出原始原因码。例如 `wait_timeout` → `reason=timeout, reason_detail=wait_timeout`；`no_fill` 不附加；未归类的 SDK 本地代码归为 `ad_error` 并保留该代码。自然语言不输出，异常消息即使是 `disconnected` 这样的纯小写单词也不输出。
新增失败路径必须将平台或异常文本放在 `errorDetail` 或异常分支，不得作为非异常原因码传入；平台错误文本通过 `error_detail` 交付，不能写入 `reason_detail`。新增原因码时同步更新此表。

### 指标口径

README 的指标表以本节为准。“会话”指业务参数 `ad_session_id`；Banner 与 Native 的 `ad_session_id` 是页面周期 `slot_id`，周期内的刷新和重试共用。逐广告身份不在本次范围内，所以 Banner/Native 的点击率按条数统计，精度受这一点限制。

| 指标 | 分子 / 分母 | 统计单位与限制 | 适用格式 |
|---|---|---|---|
| 展示率 | 有 `ad_show` 的会话数 / 有 `ad_position` 的会话数 | 按会话去重，不超过 100% | 全部 |
| 每次机会展示数 | `ad_show` 条数 / `ad_position` 条数 | 按条数，可以大于 1，不叫“率” | Banner、Native |
| 机会失败率 | 有 `ad_show_fail` 且没有 `ad_show` 的会话数 / 有 `ad_position` 的会话数 | 按会话；不能用 `ad_show_fail / ad_position` | 全部 |
| 拦截率 | `ad_scene_skip` 条数 / (`ad_scene_skip` + `ad_position`) 条数 | 按条数；部分准入前拦截不发事件，结果偏低 | 全部 |
| 点击率 | 全屏：有 `ad_click` 的会话数 / 有 `ad_show` 的会话数。Banner、Native：`ad_click` 条数 / `ad_show` 条数 | 后者不按单条广告去重 | 全部 |
| 关闭率 | 有 `ad_close` 的会话数 / 有 `ad_show` 的会话数 | 按会话；Banner 关闭不进业务参数 | 全屏、Native |
| 激励完成率 | 同时有 `ad_show` 与 `ad_reward` 的会话数 / 有 `ad_show` 的会话数 | 按会话，不看先后；“有奖励无曝光”的会话单独列为异常 | 激励 |
| 填充率 | `ad_loaded` 条数 / `ad_load` 条数 | 按 `request_id`；批次 3 完成前不做 AdMob 与 TopOn 全屏横向对比 | 全部 |
| eCPM | `revenue_amount` 总和 / 带 `revenue_amount` 的 `ad_impression` 条数 × 1000 | 按币种分开计算；TopOn 开屏/Native 金额可能为估算，看 `precision_type` | 全部 |
| 收益覆盖率 | 带 `revenue_amount` 的 `ad_impression` 条数 / `ad_show` 条数 | 按条数；TopOn 无金额的 `ad_impression` 不计入分子 | 全部 |

收益对账的真值不在 SDK 埋点内；专用收益通道仍是 `revenueListener`。

验算样例（README 与测试按此核对）：

| 样例 | 事件 | 结果 |
|---|---|---|
| Banner 刷新 | 1 个周期：`ad_position` ×1，`ad_show` ×3（`refresh_index` 1–3），`ad_click` ×1 | 展示率 1/1；每次机会展示数 3；点击率 1/3 |
| Native 重试 | 同一会话：`ad_position` ×1，`ad_show_fail` ×1，随后 `ad_show` ×1 | 展示率 1/1；机会失败率 0/1（直接用 `ad_show_fail / ad_position` 会误算为 100%） |
| TopOn 开屏无收益 | `ad_position` ×1，`ad_show` ×1，不带金额的 `ad_impression` ×1 | 展示率 1/1；收益覆盖率 0/1；不进入 eCPM 分母 |

### 日志检索

启用 `loggingEnabled` 后，用 `adb logcat -v threadtime AdsMediation:D '*:S'` 获取日志。全屏按 `session_id` 检索一次展示；Banner/Native 按页面周期 `slot_id` 检索，加载另按 `request_id` 关联。
`AdsModuleLogger` 的普通事件日志输出 `session_id` 和可用的 `slot_id`；Native 以 `[原生广告][<slot_id>]`（DEBUG 为 `[原生广告][调试][<slot_id>]`）显示周期，内部 `sessionId` 在 DEBUG 中显示为“展示记录”。开屏完整身份在 DEBUG 关联行，`session_id` 实际标为 `sid=`，`request_id` 标为 `req=`。Banner 的单条广告内部 `session_id` 会随刷新变化，不适合代替整个页面周期。
检索时使用对应 ID 的值；Native 加载记录在 DEBUG 中标为“获取记录”。这些是日志身份；业务参数使用 `ad_session_id`，全屏取展示 session，Banner/Native 取页面 slot，不输出 `session_id` 或 `slot_id` 键。`ad_scene_skip` 无业务会话。
日志开启时，业务事件因契约不满足而返回空业务参数会输出警告，`BANNER_REFRESH` 与 Banner `DISMISS` 除外；无效收益被丢弃时输出平台、格式及可确认的会话诊断。诊断不会新增业务事件，也不改变回调交付。

### 字段与接入变更记录（com.cashcraft.sdk:ads-mediation:1.0.11）

- 新增 `AdEventName.SHOW("ad_show")`，实际曝光新增一条事件，`ad_impression` 的回调时机、收益字段和去重不变。宿主对公共枚举做无 `else` 的穷举 `when` 时必须补 `SHOW` 分支，否则升级后源码编译失败；按事件名分发上报平台时，必须为 `ad_show` 配置去向。
- `ad_load_fail` / `ad_show_fail` 新增可选 `reason_detail`，保留现有 `reason` 类别；`error_detail` 继续承载平台错误文本。
- 有效 `ad_show_fail` 必传 `ad_source`，失败素材来源不可确认时为 `"unknow"`；`ad_load_fail` 不增加来源字段。
- AdMob 开屏、插屏、激励持续预加载首个终态后的成功/失败回调各建新 `request_id`，补报加载与终态并带 `load_trigger=sdk_auto`、不带 `latency_ms`，包含首个结果之后仍属于初始填池的回调；库存耗尽及过期代次回调不产生加载事件。详见[加载语义](docs/integration-guide.md#事件回调)。
- 不再发送 `ad_load_request`、`ad_load_result`、`ad_paid`、`ad_reward_earned`；现行事件分别使用 `ad_load` 与 `ad_loaded` / `ad_load_fail`、`ad_impression`、`ad_reward`。

## 6. 收益回调

`ad_impression` 携带业务收益字段；Firebase/Tenjin 等专用收益上报继续使用独立的 `revenueListener`，
该监听仍由平台收益回调触发，不受 `ad_show` 或 `ad_impression` 的触发时机影响。AdMob 和 TopOn
已归属收益保留各自的 payload 类型，公共字段位于 `AdRevenuePayload` 接口上，宿主
无需按平台分支。只有 Tenjin 等依赖 Provider 原生字段的集成才需要判断具体子类型。

```kotlin
// 旧 lambda 接口保持兼容，继续只接收已归属收益。
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
| `eventId` | 收益事件 ID；优先使用 Provider impression ID，无 showId 时也可使用本地 showToken，其他情况使用展示 session ID；稳定性边界见下文 |
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

TopOn 开屏、插屏、激励每次展示使用独立的本地 `showToken`。只有能够安全确认展示归属的收益才进入
`onRevenuePaid`；SDK 缺少 token 且 showId 也无法匹配，或存在 ID 冲突、归属记录被淘汰时，
收益通过独立的 `onUnattributedRevenuePaid` 交付。未知指展示归属未知，金额仍须合法。
宿主必须实现 `AdRevenueListenerWithUnattributed` 并接入新方法才能接收未知归属收益；旧 lambda 和
仅实现 `AdRevenueListener` 的对象继续兼容，但不会接收这部分收益。

```kotlin
revenueListener = object : AdRevenueListenerWithUnattributed {
    override fun onRevenuePaid(payload: AdRevenuePayload) {
        reportAttributedRevenue(payload) // 宿主实现：保存规范化字段和展示上下文。
    }

    override fun onUnattributedRevenuePaid(payload: UnattributedTopOnRevenuePayload) {
        reportUnattributedRevenue(payload) // 宿主实现：仅保存规范化字段，不补造会话。
        reportTopOnNativeRevenue(payload.adInfo) // 需要原生 ILRD 时即时调用。
    }
}
```

`UnattributedTopOnRevenuePayload` 是独立 data class，不继承 sealed `AdRevenuePayload`；原有两个子类型及
穷举 `when` 保持兼容。字段包括 `eventId`、`occurredAtMillis`、`mediationMode`、`format`、`placementId`、
`valueMicros`、`currencyCode`、`adNetwork`、`impressionId`、`precisionType`、`adInfo`，`platform` 固定为
`TOPON`，没有 `sessionId` / `position`。基础校验与已归属收益一致：标识、广告位和币种非空白，时间为正，
金额非负。两个 TopOn payload 的原始 `adInfo` 都不得持久化，异步业务上报只保存规范化字段。

未知归属收益不产生会话业务 `ad_impression`，也不占用当前会话的去重状态；不要按当前页面或当前活跃会话
补齐归属。存在可安全复用的稳定标识时，宿主可用 `eventId` 去重；无任何稳定标识时，只能保证单次接收的
`eventId` 稳定，无法保证重复 SDK 回调具有相同 ID，不能用金额加时间猜重。

全屏 TopOn 的稳定收益 ID 包含格式和广告位作用域，避免不同作用域的同名 showId 碰撞；宿主应将 eventId 当作不透明标识，不解析其字符串格式。已投递身份的重复通知不会再次占用等待队列。冲突身份单独隔离，保留最近 128 个冲突组合，并在对应收益去重记录仍保留时继续隔离。

归属记录保留最近 32 次展示，收益去重保留最近 128 条记录，均仅在当前进程有效。非空 showId 尚未匹配且
没有 token 时最多等待 5 秒（最多 32 条待匹配收益）；展示回调确认 token 后立即匹配，超时或容量淘汰则走
未归属回调。未知收益已经交付后，后续确认 showId 与 token 的关联时只补充内存中的去重别名，不再次投递金额，也不补造业务
`ad_impression`。超出保留窗口的重复通知不能保证精确去重。

SDK 隔离宿主回调异常，但不自动重试；宿主应在回调内可靠接收规范化记录，再用同一 eventId 重试自己的
上报。不保证进程死亡后的投递。showToken 经 `TUShowConfig.showCustomExt` 传递；实际广告源是否在收益
回调中完整回传仍需设备验证，缺失时继续按上述规则处理。

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
退到后台时，已通过准入检查的机会用同一业务会话的 `ad_show_fail` 收口；未通过准入检查则不补造 `ad_position`。

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
  -PVERSION_NAME=1.0.11
```

R8 smoke app 会让 AdMob、TopOn 和竞价路径都保持可达，然后构建开启压缩和资源收缩的 Release
APK，用于验证 AAR 的 consumer rules 和 GMA 反射字段。

TopOn 实网测试位于 `src/topOnLiveTest/java`，默认设备测试不编译或运行它。
运行前需在 TopOn 控制台为设备 GAID 启用测试模式，并在测试目标应用的 `filesDir`
预置私有 `topon-test.properties`，包含 `applicationId`、`applicationKey`、
`appOpenPlacement`、`interstitialPlacement`、`rewardedPlacement` 和 `nativePlacement`。
不要提交该配置。

使用 `topOnLiveTest` 显式选择一个方法；每条命令单独启动 instrumentation 进程，
避免 TopOn 单例初始化状态串扰。运行全部实网场景时逐条执行，任一失败即停止：

```shell
for method in testRealLoadsAndSharedNativeRefill \
  testRealFailuresRespectCooldownAndRetryBudget \
  testRealSharedNativeTakeRefillAndRender; do
  ./gradlew :connectedDebugAndroidTest -PtopOnLiveTest="$method" || break
done
```

此入口仅接受上述单个方法名；不要额外覆盖 instrumentation 的 `class` 参数。

GitHub Actions 中的 `CI` 和 `Publish GitHub Package` 都只支持在 Actions 页面手动触发，不会因
push、PR 或 tag 自动运行。发布时可填写 `version` 指定版本；本次填写 `1.0.11`。

本地或手动发布 CI 都会先读取 GitHub Packages 的 `maven-metadata.xml`，找到最高的稳定
`major.minor.patch` 版本并自动递增 patch。例如新 group 当前为 `1.0.11` 时，下一次会发布 `1.0.12`。

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
- Maven 坐标：com.cashcraft.sdk:ads-mediation:1.0.11
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
2. 在 App 模块加入 implementation("com.cashcraft.sdk:ads-mediation:1.0.11")。
3. 为每个 product flavor 设置 manifestPlaceholders["admobApplicationId"]。不要使用生产 ID
   进行测试；开发环境可使用 AdMobIds.TEST 对应的官方测试 App ID。
4. 检查现有配置来源，选择 AdMob、TopOn 或 Bidding。不要把 App ID、App Key、ad unit ID、
   placement ID 硬编码进通用业务类；沿用项目现有 BuildConfig、properties 或 secret 体系。
5. 在自定义 Application.onCreate 中调用 Ads.initialize。默认启用 UMP；记录 onInitialized，
   但初始化失败不能导致 App 崩溃或阻塞业务流程。
6. AdsConfig 必须接入 eventListener：使用 event.name.analyticsName 作为事件名，使用
   event.analyticsParameters() 作为完整参数。保持同一 ad_session_id，按第 5 节的回调时机区分实际曝光、收益统计与失败；
   不把收益回调缺失当作未曝光，也不按曝光/失败等式反推展示机会。
7. AdsConfig 必须接入 revenueListener。通用后端直接消费 AdRevenuePayload 的 eventId、时间、
   平台、格式、展示上下文、微单位金额、币种、广告网络和 impressionId；AdMob 子类型保留
   mediationAdapterClassName，TopOn 子类型保留原始 adInfo。不要从业务 ad_impression 反推原生收益对象。
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
            val parameters = event.analyticsParameters()
            if (parameters.isNotEmpty()) analytics.logEvent(event.name.analyticsName, parameters)
        },
        revenueListener = { payload ->
            when (payload) {
                is AdMobRevenuePayload -> reportAdMobRevenue(payload)
                is TopOnRevenuePayload -> reportTopOnRevenue(payload)
            }
        },
        loggingEnabled = BuildConfig.DEBUG,
        autoShowAppOpen = false, // 开屏由业务调用 Ads.showOpen(position)。
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
