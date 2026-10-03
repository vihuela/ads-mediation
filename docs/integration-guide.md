# Ads Mediation Android 广告接入文档

本文面向 Android 宿主开发者，说明如何通过统一入口 `com.cashcraft.ads.mediation.Ads` 接入开屏、插屏、激励、Banner 和 Native 广告，覆盖工程配置、初始化、页面生命周期、事件及收益回调。

文档依据 2026 年 10 月 3 日的当前工作树编写，基线提交为 `32634be`。**下文以当前源码构建的库为准。**仓库 README 标注的远程稳定版为 `1.0.5`，并明确 Banner、Native 等新增接口尚未包含在该版本中；本次没有查询远程包版本。不要直接使用 `1.0.5` 依赖后复制全部新 API。

## 1 接入前选择

### 支持范围

| 广告形式 | AdMob 直连 | TopOn | 双平台模式 | 宿主入口 |
| --- | --- | --- | --- | --- |
| 开屏 | 支持 | 支持 | 比较已启用平台的候选 | `Ads.showAppOpenWhenReady` |
| 插屏 | 支持 | 支持 | 比较已启用平台的候选 | `Ads.showInterstitialWhenReady` |
| 激励 | 支持 | 支持 | 比较已启用平台的候选 | `Ads.showRewardedWhenReady` |
| Banner | 支持 | 当前不支持 | 使用 AdMob 配置，不做 Banner 竞价 | `bindBanner` / `AdsBannerView` / `AdsBanner` |
| Native | 支持 | 支持 | 双 ID 时对实际广告对象比价 | `AdsNativeView` / `AdsNative` |

全屏广告也保留 `showAppOpen`、`showInterstitial`、`showRewarded` 立即尝试接口。页面可以等待广告时使用 `WhenReady`；业务不允许等待时使用立即尝试接口，并处理无缓存的失败结果。

### 工程基线

| 项目 | 当前库配置 |
| --- | --- |
| Android 最低版本 | `minSdk = 26` |
| 编译 SDK | `compileSdk = 36` |
| Java 和 Kotlin 字节码 | JVM 17 |
| 构建插件 | AGP `8.13.1`，Kotlin `2.2.21` |
| AdMob | GMA Next-Gen `1.2.1` |
| TopOn 核心 | `6.6.22.3` |
| Google UMP | `4.0.0` |
| WorkManager | `2.11.2` |

新宿主可先对齐这套基线。以上是仓库当前版本组合，不表示任意其他组合已验证兼容。纯 View 宿主可以只调用 View 入口；使用 Compose 入口的宿主需配置自己的 Compose 编译插件。

## 2 添加依赖与 Manifest 配置

### 使用当前源码构建

在广告库根目录执行以下命令，将当前源码安装到本机 Maven 仓库：

```shell
./gradlew :publishReleasePublicationToMavenLocal -PVERSION_NAME=1.0.0-SNAPSHOT
```

这一步只发布到本机，不会发布远程版本。`1.0.0-SNAPSHOT` 来自当前仓库的 `gradle.properties`，也可以用 `-PVERSION_NAME=...` 指定一个团队约定的本地版本；宿主依赖必须使用相同版本。

在宿主 `settings.gradle.kts` 的现有仓库配置中加入：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal {
            content { includeModule("com.cashcraft", "ads-mediation") }
        }
        google()
        mavenCentral()
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

库当前将各广告 SDK 和适配器一起作为依赖分发；即使业务只选择 AdMob，也应保留依赖解析所需的这些仓库。不要仅复制一个裸 AAR 而遗漏传递依赖。

在宿主 App 模块中添加：

```kotlin
android {
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        // Google 测试 App ID。生产 flavor 必须替换成自己的 App ID。
        manifestPlaceholders["admobApplicationId"] =
            "ca-app-pub-3940256099942544~3347511713"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.cashcraft:ads-mediation:1.0.0-SNAPSHOT")
}
```

将上述内容合并到现有配置，不要重复创建已有的 `android` 或仓库配置。多个 flavor 应分别设置 App ID，并与对应 flavor 的 `AdMobIds.applicationId` 保持一致。App ID 使用 `~`，广告位 ID 使用 `/`，不能互换。

库的 Manifest 已声明网络权限、`org.apache.http.legacy` 可选库和 AdMob App ID 占位符。**即使选择 TopOn，当前库也要求提供 `admobApplicationId`**，因为它包含 Google 适配器及对应 Manifest 配置。

### 使用远程发行包

已经确认发行包包含所需 API 后，再使用团队发布的版本。GitHub Packages 仓库为 `https://maven.pkg.github.com/vihuela/ads-mediation`；凭据配置和现有稳定版接入见 [README 的接入依赖部分](../README.md#1-接入依赖)。用户名及 token 放在不提交的 `local.properties`、Gradle 属性或 CI 环境变量中。

当前源码的完整接入按上面的本地构建步骤进行，不要把未核实的版本号当作已发布版本。团队构建环境不能依赖另一台机器的 `mavenLocal`，应使用一致源码构建或已发布的对应包。

### SDK 与混淆依赖

宿主不要再添加 legacy `com.google.android.gms:play-services-ads` 或 `play-services-ads-lite`，也不要再单独初始化 `MobileAds` 或 TopOn。它们由广告库统一处理。其他依赖引入 legacy Google Ads 时，应先整理依赖树，避免与 Next-Gen 同时打包。

AAR 会携带 consumer ProGuard 规则，宿主无需复制库内规则。当前全屏缓存报价路径依赖 GMA Next-Gen `1.2.1`；不要只升级底层 SDK 版本而跳过对应的兼容性和 Release/R8 验证。

## 3 初始化广告库

### 最小测试配置

在自定义 `Application.onCreate()` 中初始化一次。下面使用库内 Google 测试 ID，适合开发验证；生产包需要替换成自己的配置。

```kotlin
import android.app.Application
import android.util.Log
import com.cashcraft.ads.mediation.*
// BuildConfig 使用宿主 App 自己的包名导入。

class AdsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Ads.initialize(
            application = this,
            config = AdsConfig(
                provider = AdMobProviderConfig(ids = AdMobIds.TEST),
                umpConsent = UmpConsentConfig(enabled = true),
                // 本文由业务显式触发开屏，避免与旧自动开屏重复。
                autoShowAppOpen = false,
                loggingEnabled = BuildConfig.DEBUG,
                logTag = "AdsMediation",
                eventListener = AdEventListener { event ->
                    // 此处接入宿主统计 SDK：
                    // event.name.analyticsName 和 event.analyticsParameters()
                },
                revenueListener = AdRevenueListener { payload ->
                    // 此处接入业务收益上报，字段与去重方式见第 7 节。
                },
            ),
            onInitialized = { success ->
                Log.d("AdsMediation", "initialized=$success")
            },
        )
    }
}
```

在宿主 Manifest 注册 Application；已有自定义 Application 时，把初始化代码合并进去：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".AdsApplication">
        <!-- 保留宿主已有的 Activity、Service 等声明。 -->
    </application>
</manifest>
```

合并到宿主现有 Manifest，保留已有的 application 属性及组件声明。

初始化默认先等待第一个 Resumed Activity 执行 UMP 流程；允许请求广告后才初始化平台并预加载启用的全屏广告。不要阻塞主线程等待初始化，也不要为了等广告而阻止首个 Activity 恢复。

### 选择 AdMob 或 TopOn

将以下配置中的 `YOUR_...` 替换为宿主真实配置，再传给 `AdsConfig.provider`：

```kotlin
val admob = AdMobProviderConfig(
    ids = AdMobIds(
        applicationId = "YOUR_ADMOB_APP_ID",
        appOpenId = "YOUR_ADMOB_APP_OPEN_ID",
        interstitialId = "YOUR_ADMOB_INTERSTITIAL_ID",
        rewardedId = "YOUR_ADMOB_REWARDED_ID",
        nativeId = "YOUR_ADMOB_NATIVE_ID",
        bannerId = "YOUR_ADMOB_BANNER_ID",
    ),
    preload = AdMobPreloadConfig(
        appOpen = 2,
        interstitial = 2,
        rewarded = 2,
        banner = 1,
    ),
)

val topon = TopOnProviderConfig(
    ids = TopOnIds(
        applicationId = "YOUR_TOPON_APP_ID",
        applicationKey = "YOUR_TOPON_APP_KEY",
        appOpenPlacementId = "YOUR_TOPON_SPLASH_PLACEMENT_ID",
        interstitialPlacementId = "YOUR_TOPON_INTERSTITIAL_PLACEMENT_ID",
        rewardedPlacementId = "YOUR_TOPON_REWARDED_PLACEMENT_ID",
        nativePlacementId = "YOUR_TOPON_NATIVE_PLACEMENT_ID",
    ),
)

// 按实际需要选择其中一种；不要在进程内反复切换初始化配置。
val provider: AdProviderConfig = BiddingProviderConfig(admob, topon)
// 单平台时改为：val provider: AdProviderConfig = admob 或 topon
```

全屏广告位按需填写，省略或 `""` 表示关闭该格式；纯空白字符串非法。AdMob 对应全屏 `preload` 值为 `0` 也会关闭该格式，合法缓存容量为 `0..15`。未启用的格式不创建广告对象、不预加载，展示返回 `ad_format_disabled`。

Banner 的默认广告位在 `AdMobIds.bannerId` 中配置，页面只传 `position`；需要独立广告位时仍可显式传 `BannerRequest`。`banner = 0` 仅关闭显式预加载，Banner View 仍可请求。Native 广告位来自初始化的 `nativeId` / `nativePlacementId`，页面的 `NativeRequest` 不接收平台或广告位 ID；不要用全屏 `preload` 控制 Native。

只接激励时可省略其他 ID：

```kotlin
val rewardedOnly = AdMobProviderConfig(
    ids = AdMobIds(
        applicationId = "YOUR_ADMOB_APP_ID",
        rewardedId = "YOUR_ADMOB_REWARDED_ID",
    ),
)
```

### 初始化状态与配置约束

- `Ads.state` 为 `NOT_INITIALIZED`、`INITIALIZING`、`READY` 或 `FAILED`。`READY` 表示平台可用，不代表广告有缓存。
- 双平台初始化会等待双方初始化结果；至少一方成功即整体成功。某个具体格式仍需对应平台成功且启用了该格式。
- `Ads.isReady(AdFormat.INTERSTITIAL)` 等只查询全屏缓存；对 Banner / Native 始终返回 `false`，它们使用自己的状态回调。
- 同一进程的 Application 和 `AdsConfig` 被首次初始化固定。复用相等配置可以再次观察结果；更换 ID、provider、监听器实例等会抛出 `IllegalArgumentException`。
- 新接入统一使用 `Ads`。不要同时调用旧的 `admob.AdMobAds.initialize()`。

页面需要等待初始化时，在主线程登记一次性监听：

```kotlin
val stopObserving = Ads.observeInitialization { success ->
    // success=true 后，由当前有效页面创建一次展示机会。
    // success=false 时结束页面自己的 loading 或继续原业务流程。
}
```

在页面超时或销毁时调用返回的 `stopObserving()`。这只解绑监听，不取消 SDK 初始化。**库仅保留一个待完成的初始化监听，新监听会替换旧监听**，包括 Application 传入的 `onInitialized`。已处于终态时会同步通知，页面自身的初始化等待上限需要由宿主管理。

## 4 展示全屏广告

以下片段中的 `activity`、页面有效状态和业务继续/发奖函数由宿主提供。SDK 类型均来自 `com.cashcraft.ads.mediation`。展示前完成初始化和许可流程；调用及页面状态维护统一放在主线程，避免生命周期与业务事件交错。

### 开屏广告

```kotlin
val appOpenOpportunity = Ads.showAppOpenWhenReady(
    activity = activity,
    position = "startup",
    timeoutMillis = 7_000L,
    isSceneValid = { isStartupPageActive },
    onResult = { result ->
        when (result) {
            AdShowResult.Dismissed -> { /* 当前启动流程有效时进入首页 */ }
            is AdShowResult.Failed -> { /* 记录 result.reason，再继续启动流程 */ }
        }
    },
)
```

保存返回句柄。在启动页真正离开时，先把 `isStartupPageActive` 设为 `false`，再调用 `appOpenOpportunity.cancel()`。取消可能同步回调，回调内也应检查页面资格，避免取消引发第二次导航。

开屏默认等待 `12_000ms`，示例覆盖为 7 秒。当前新接口在原 Activity 暂停或 App 退后台时暂停计时、保留等待；回到同一个 Activity 后继续剩余预算。不要把它按插屏的方式无条件放在 `onPause` 里取消。页面销毁、另一 Activity 恢复、主动取消或场景失效会结束机会。

TopOn 开屏容器由库创建并清理，宿主不用传容器。保留旧自动开屏行为时使用 `autoShowAppOpen = true`，其前台等待窗口为 7 秒；同一场景不要再显式触发一次开屏。本文的 `autoShowAppOpen = false` 也意味着前台返回的开屏由宿主按业务规则自行触发。

### 插屏广告

```kotlin
val interstitialOpportunity = Ads.showInterstitialWhenReady(
    activity = activity,
    position = "level_complete",
    timeoutMillis = 3_000L,
    isSceneValid = { isLevelPageActive },
    onResult = { result ->
        // Dismissed 或 Failed 都表示本次机会结束。
        // 页面仍有效时清理 loading，并且只继续一次原业务流程。
    },
)
```

插屏默认等待 `5_000ms`。退出页面、切换 Tab 或导航前显式取消；Activity 暂停或应用退后台也会结束尚未交接的机会。回到页面不会自动补弹，需要新的业务触发。

### 激励广告

```kotlin
val rewardedOpportunity = Ads.showRewardedWhenReady(
    activity = activity,
    position = "unlock_feature",
    timeoutMillis = 5_000L,
    isSceneValid = { isRewardPageActive },
    onResult = { result ->
        if (result.rewardEarned) {
            // 根据业务规则幂等发奖，并用 result.sessionId 关联广告奖励。
        }
        // result.showResult 用于关闭/失败 UI；不能据此发奖。
    },
)
```

只有 `rewardEarned == true` 才发奖，不能根据 `Dismissed`、曝光或收益事件发奖。纯等待期间失败/取消时没有真实展示会话，`sessionId` 可以为 `null`。交给平台展示后，即使原页面离开，后续奖励结算仍应由业务处理，避免只因 UI 已离场而丢失已获得的奖励。

### 不等待的展示入口

```kotlin
Ads.showInterstitial(activity, position = "level_complete") { result ->
    // 无缓存立即 Failed，不会继续等待后补弹。
}
Ads.showRewarded(activity, position = "unlock_feature") { result ->
    if (result.rewardEarned) { /* 幂等发奖 */ }
}
Ads.showAppOpen(activity, position = "startup") { result ->
    // 处理 Dismissed 或 Failed。
}
```

以上是三个独立场景的替代写法，不要连续执行来串播广告。`isReady()` 只是瞬时状态，不是展示保证，查询为 `true` 后仍须处理失败回调。立即展示要求 Activity 位于前台、Resumed、Window 已附着且有焦点。

### 页面生命周期与等待规则

1. 一次业务触发只创建一个机会。等待期间禁止重复点击，不在 Composable 函数体或每次 `onResume` 自动重发同一业务事件。
2. 用页面实例或 Navigation entry 判断资格。只比较 route 字符串不足以区分同一路由的不同页面；A → B → A 不能复活 A 的旧机会。
3. 离开页面时先标记场景失效，再 `cancel()`；Fragment 用 View 生命周期兜底，Compose 用 `DisposableEffect` 清理，保留 Tab 在切换事件中主动取消。
4. `cancel()` 幂等且只在交给 SDK 展示前有效。交接后不会撤回广告，也不会屏蔽关闭、奖励或收益回调。
5. 等待超时/取消不停止共享加载，不清空有效缓存。后续新机会可以复用它们，但使用新的期限和页面资格。
6. 同一时刻只能有一个全屏等待/展示占用者；冲突可能返回 `request_in_progress` 或 `another_full_screen_ad_showing`。`Ads.isFullScreenAdShowing` 只表示已交接展示，不包含纯等待。

业务 loading 放在当前页面内，避免用会抢夺窗口焦点的独立 Activity 或 Dialog。超时限制等待阶段，不限制广告播放时长。

双平台等待只包含启用当前格式的参与方：双方成功时比价，一方成功且另一方明确失败时使用成功方；仍有参与方未完成时继续等，到期从有效缓存中选择。已知价格优先于未知价格，价格高者优先，价格相同或都未知时以 AdMob 兜底。报价单位为 USD 单次展示收益，不是最终收益确认。

Navigation、Fragment 和 Compose 的完整生命周期示例见 [全屏展示机会接入说明](fullscreen-display-opportunities.md)。

## 5 接入 Banner

Banner 当前只支持 AdMob，初始化 provider 必须是 AdMob 或包含 AdMob 的 Bidding 配置。显式 TopOn 请求通过 `onState` 返回 `AdShowResult.Failed("topon_banner_not_supported")`；TopOn-only 配置使用便捷入口时抛出同原因的 `IllegalStateException`。两者均不会自动回退；双平台整体初始化成功也不能替代 AdMob 自身初始化成功。

在初始化的 `AdMobIds` 中配置 `bannerId`，普通页面只声明展示位置：

```kotlin
val ids = AdMobIds(
    applicationId = "AdMob App ID",
    bannerId = "ca-app-pub-3940256099942544/9214589741", // 官方测试 ID
)
```

将它传给 `AdMobProviderConfig`，并先调用 `Ads.initialize()`；无需等 SDK 初始化完成。
便捷入口在未初始化、未配置 `bannerId` 或 TopOn-only 配置下抛出 `IllegalStateException`，
应在接入时修正配置；这些配置异常不通过 `onState` 返回。`bannerId` 为可选项，省略或 `null` 只关闭便捷入口，空字符串和纯空白非法；缺少默认 ID 不影响原有显式 `BannerRequest` 接口。

### Fragment

下面放在 Fragment 的 `onViewCreated` 中。`bannerContainer` 是宿主自己的 `ViewGroup`；使用内置默认尺寸，无需额外 XML 广告布局。

```kotlin
import com.cashcraft.ads.mediation.bindBanner

bindBanner(bannerContainer, position = "home_bottom")
```

扩展内部使用 `viewLifecycleOwner`，自动挂载和释放，不需要保存对象或编写 `onDestroyView` 清理代码。同一容器、owner 和请求的重复绑定复用原 View；请求变化会替换旧绑定，只处理绑定自己添加的 View。所有 View 操作在主线程执行。非 Fragment 或需要显式指定广告位时，仍可使用 `AdsBannerView(activity, lifecycleOwner, request)`。

`BannerState.Ready` 只代表素材就绪，曝光以 `ad_impression` 为准。失败类型是 `AdShowResult.Failed`，它也实现 `BannerState`；不存在独立的 `BannerState.Failed`。

### Activity 公共底栏

由 Activity 持有的广告区域，使用 `Ads.bannerRequest()` 读取集中配置，再交给现有 View 入口。
以下代码放在实现 `LifecycleOwner` 的 Activity（如 `ComponentActivity`、`AppCompatActivity`）中，
在业务允许展示时执行一次；`bannerContainer` 为该 Activity 的广告容器：

```kotlin
import android.view.ViewGroup
import com.cashcraft.ads.mediation.Ads
import com.cashcraft.ads.mediation.AdsBannerView

val request = Ads.bannerRequest(position = "home_bottom")
val banner = AdsBannerView(activity = this, lifecycleOwner = this, request = request)
bannerContainer.addView(
    banner,
    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
)
```

`bindBanner()` 当前是 Fragment 扩展；Activity 继续自行挂载 View，由 Activity owner 销毁时自动释放。
如保存为字段，可在 `onDestroy()` 调用 `destroy()` 并清空引用，重复销毁安全。
公共底栏可跨内部 Tab 复用；同窗口遮罩使用 `View.INVISIBLE`。如果广告只属于其中一个页面，
应按该页面归属管理 `active`，而不是一直跟随整个 Activity。

### Compose

放在页面 Composable 内，默认使用当前 `LocalLifecycleOwner.current`：

```kotlin
import com.cashcraft.ads.mediation.compose.AdsBanner

AdsBanner(position = "home_bottom")
```

需要指定导航 entry 或控制遮罩、业务启停时再传可选参数；`backStackEntry` 必须属于当前页面实例：

```kotlin
AdsBanner(
    position = "home_bottom",
    lifecycleOwner = backStackEntry,
    active = pageOwnsBanner,
    visible = !overlayCoversBanner,
    onState = { state -> /* 只更新 UI 或诊断状态 */ },
)
```

`active` 表示业务归属，`visible` 表示当前可见性。临时遮罩用 `visible=false`；View 对应 `visibility = View.INVISIBLE`，保留占位和可恢复实例。真正离开该广告业务区域时用 `active=false`，会结束周期并释放。Compose 移出组合会最终销毁，不能把它当成临时隐藏。

自适应 Banner 使用实际内容宽度，高度由库测量，容器用 `WRAP_CONTENT`，不要强制 50dp。`Standard320x50` 要求内容宽度至少 320dp。`AnchoredAdaptive` 使用 large anchored adaptive 行为；需要历史普通锚定自适应尺寸时，可选择 `StandardAnchoredAdaptive`。系统安全区、底部导航栏和 IME 间距由宿主处理。

### 可选预加载

Banner 不要求先预加载，没有缓存时 View 会自行请求。需要预热时，在容器测量完成后传入已扣除宿主 padding 的内容宽度，单位为 dp：

```kotlin
Ads.preloadBanner(
    activity = activity,
    request = Ads.bannerRequest(position = "home_bottom"),
    contentWidthDp = contentWidthDp,
    autoRefill = false,
)
```

预加载与展示的广告位 ID、`position` 和解析尺寸必须一致。默认 `autoRefill=true` 持续补货；`false` 每次有效预加载周期最多请求并保存一条，消费、失败或过期后不自动补货，需要宿主再次显式调用。**`autoRefill=false` 不会关闭已展示 Banner 的 SDK 自动刷新。**不要在状态回调或重组中无条件再次调用预加载。

如果展示端显式传了 `size`，预加载也必须传相同值，或与展示共用同一请求定义。
例如 HealthTracker 使用 `StandardAnchoredAdaptive`，不能在预加载端省略 `size` 而切到默认的大尺寸自适应。
该宿主已迁移到集中配置和请求解析，Activity 的挂载、遮罩与释放仍由原有代码管理；
具体调用链及验证范围见 [HealthTracker 宿主接入](banner-integration.md#healthtracker-宿主接入)。

`AdMobIds` 新增参数后，宿主及依赖它的模块需重新编译；默认参数不保证旧二进制兼容。

更多尺寸和生命周期细节见 [Banner 接入说明](banner-integration.md)。

## 6 接入 Native 卡片

先在初始化配置中设置 `AdMobIds.nativeId`、`TopOnIds.nativePlacementId`，或同时设置两者。页面只提供业务位置：只有一端 Native ID 时使用该来源，两端都有时进行 Native 比价。它与全屏广告缓存竞价独立，业务不需要自己持有两份候选广告。

只接 Native 时可以省略全部全屏 ID，应用凭据仍必填：

```kotlin
val nativeOnly = AdMobProviderConfig(
    ids = AdMobIds(
        applicationId = "YOUR_ADMOB_APP_ID",
        nativeId = "YOUR_ADMOB_NATIVE_ID",
    ),
)
```

### View 和 Fragment

下面放在 `onViewCreated`，`nativeContainer` 是宿主容器。默认 Native 布局已包含在库中。

```kotlin
import android.view.ViewGroup
import com.cashcraft.ads.mediation.*

val native = AdsNativeView(
    activity = requireActivity(),
    lifecycleOwner = viewLifecycleOwner,
    request = NativeRequest(position = "home_card"),
    onStateChanged = { state ->
        when (state) {
            NativeState.Loaded -> { /* 素材已绑定，尚不等于曝光 */ }
            is NativeState.Failed -> { /* 记录 state.reason / state.errorCode */ }
            else -> Unit
        }
    },
)
nativeContainer.addView(
    native,
    ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
)
```

用 `native.setActive(false)` 表达当前业务不再允许展示，用 `native.setVisible(false)` 表达临时不可见。owner 销毁会最终释放；保存到 Fragment 字段的引用应在 `onDestroyView` 调用 `destroy()` 并清空。销毁后的外层 View 不再用于新页面。

### Compose

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.cashcraft.ads.mediation.*
import com.cashcraft.ads.mediation.compose.AdsNative

AdsNative(
    request = NativeRequest(position = "home_card"),
    lifecycleOwner = backStackEntry,
    active = pageOwnsNative,
    visible = !overlayCoversNative,
    modifier = Modifier.fillMaxWidth(),
    onStateChanged = { state -> /* 处理加载或失败状态 */ },
)
```

Banner 的回调名是 `onState`，Native 的是 `onStateChanged`。Compose 入口使用 Activity 支持的 Context，并传递当前页面自己的 owner。当前 Native 入口用于页面卡片，不作为 RecyclerView / LazyColumn 的广告复用组件。

### 加载和页面位置

- `NativeRequest.position` 必须非空。不同卡片使用不同位置；同一位置同时连接两个容器会返回 `native_position_occupied`。可能并存的同路由页面也要区分位置。
- `bidTimeoutMillis` 默认 `7_000L` 且必须为正数。`preferCachedAds=true` 时有缓存可立即选择；两端都没有缓存仍按期限等待。
- `NativeState.Loaded` 不表示曝光；不要从状态回调手动上报 `ad_impression`。
- 可在 `Ads.initialize()` 后调用 `Ads.preloadNative()`。它按平台等待许可、前台和就绪，预热初始化 ID 对应的自渲染库存，不占用单个初始化观察者，也不创建页面 View。
- 失败后可调用 View 的 `retry()`，或变更 Compose 的 `retryToken`。仅当前处于失败且具备展示资格时生效；不要自行加无限重试循环。

### 隐藏和保留策略

默认 `NativeRetentionPolicy.DESTROY_ON_HIDE` 在隐藏时释放，恢复后重新获取，适合作为首次接入策略。

可选 `RETAIN_WHILE_PAGE_ALIVE` 只对当前支持安全恢复的来源生效：素材明确为图片、无视频，且 AdMob adapter 为 `com.google.ads.mediation.admob.AdMobAdapter`。视频、来源未知、其他 AdMob adapter 和 TopOn 当前均降级释放。选中保留策略不代表所有来源都能保留。

Compose 跨组合保留还要求持续存活的页级 owner，不能用 Activity owner 代替。owner 或 Activity 销毁、显式 `destroy()` 都会最终释放。保留策略下 `active=false` 可能仍保留已绑定对象；真正结束页面时使用最终销毁路径。

### 自定义布局和 TopOn 模板

业务布局使用 `NativeLayout.Custom`，每次工厂调用返回一份新的 View 树，Compose 中用 `remember` 稳定持有布局对象。库负责点击、曝光和收益；宿主不要直接操作广告 SDK 对象、覆盖素材点击或伪造曝光。

自定义布局需保留广告标识、标题、CTA 和 AdChoices 等必需槽位；媒体和图标槽位是否可省略还取决于真实返回素材。当前不支持用纯 Compose 子槽位替代素材 View。字段约束和 XML 示例见 [Native 自定义布局说明](native-integration.md#自定义-viewxml-布局)。

TopOn 模板广告使用默认布局入口，并在 `NativeRequest.topOnTemplateAspectRatio` 中提供已确认的真实比例；不要猜一个比例投入生产。自定义布局要求自渲染素材，收到模板时返回 `unsupported_native_render_mode`。启动时 `preloadNative()` 只预热自渲染库存，不能替代模板所需的页面宽度和比例。

## 7 事件统计与收益上报

### 事件回调

在初始化的 `eventListener` 中转发 `event.name.analyticsName` 和 `event.analyticsParameters()`。后者是 `Map<String, Any>`，接入 Firebase 等统计 SDK 时由宿主转换为对应参数类型；本库没有替宿主直接调用统计平台。

| 事件名 | 用途 |
| --- | --- |
| `ad_load_request` / `ad_load_result` | 加载请求及结果，通过 request/session 关联 |
| `ad_position` | 进入实际展示尝试或页面广告展示周期 |
| `ad_bid_result` | 竞价候选、价格和获胜平台 |
| `ad_impression` | 平台确认曝光 |
| `ad_show_fail` | 展示尝试或页面广告请求失败 |
| `ad_click` | 平台确认点击 |
| `ad_close` | 全屏关闭 |
| `ad_paid` | 平台返回收益，供通用统计观察 |
| `ad_reward_earned` | 激励平台确认奖励 |
| `ad_banner_refresh` | Banner 刷新事件 |

`position` 使用稳定的业务场景名，如 `startup`、`level_complete`、`home_bottom`，不要传 SDK 广告位 ID。全屏展示会去除首尾空白，空值归一为 `unknown`；Native 则拒绝空位置。展示事件的广告类型由 `ad_type` 区分，加载事件不携带 `position`。

纯全屏等待期间超时、取消或场景失效，不创建 `ad_position`、`ad_bid_result` 或 `ad_show_fail`，业务等待漏斗应在调用和结果回调处单独记录。Banner 刷新和页面 Native 也不能直接套用全屏的一次调用事件数量规则。

`Ads.mediationMode` 表示初始化模式；每条广告的来源读 `event.platform`，获胜方读 `winnerPlatform`，实际填充广告网络读 `adSource`。Bidding 模式下 `Ads.platform == null`，这不表示没有广告来源。

### 收益回调

展示级收益使用独立的 `revenueListener`。业务通常读取公共 `AdRevenuePayload` 即可，不需要按平台拆两套后端协议：

| 字段 | 含义和宿主处理 |
| --- | --- |
| `eventId` | 稳定收益事件 ID，建议作为后端去重键 |
| `occurredAtMillis` | 收益回调到达时间，Unix 毫秒 |
| `platform` / `mediationMode` / `format` | 实际聚合平台、配置模式和广告形式 |
| `sessionId` / `position` | 展示会话与业务位置 |
| `placementId` | AdMob 广告位 ID 或 TopOn placement ID |
| `valueMicros` / `currencyCode` | 金额微单位及币种；一单位货币为 1,000,000 micros |
| `adNetwork` / `impressionId` / `precisionType` | 实际广告网络、曝光标识和精度；可能为空 |

后端上报保留 `Long` 金额和币种；显示金额时再按百万换算。AdMob 保留平台币种，TopOn 收益归一为 USD。只接受美元的下游先检查 `currencyCode == "USD"`，不要把其他币种当美元。

`ad_paid` 与 `revenueListener` 是同一收益的不同出口，不要各记一笔收入。价格竞价结果也不是确认收入。监听器内只做轻量处理或投递上报任务，避免阻塞广告回调。

需要第三方原生 ILRD 集成时，AdMob 可读取 `AdMobRevenuePayload.mediationAdapterClassName`；TopOn 可即时使用 `TopOnRevenuePayload.adInfo`。不要持久化 TopOn 原始 SDK 对象，异步业务上报保存公共字段即可。

## 8 UMP 与隐私选项

UMP 是所有 provider 共用的请求前置条件，包括 TopOn。默认 `enabled=true`；`Ads.consentSnapshot` 可读取 `status`、`canRequestAds` 和 `privacyOptionsRequired`。

设置页根据实际要求提供隐私入口，在用户点击时调用：

```kotlin
if (Ads.isPrivacyOptionsRequired) {
    Ads.showPrivacyOptions(activity) { errorMessage ->
        // null 表示正常关闭；否则展示/记录失败原因。
        // 关闭后重新读取 Ads.consentSnapshot，并更新设置页入口状态。
    }
}
```

`tagForUnderAgeOfConsent` 按宿主实际用户配置。`enabled=false` 会跳过本库 UMP 门禁并允许初始化，不应为了消除启动等待直接照搬到生产。上述是库行为说明，宿主仍需完成自己的隐私消息配置。

## 9 常见问题排查

| 现象或原因 | 检查方向 |
| --- | --- |
| 找不到 `AdsNative`、`AdsBanner`、`show...WhenReady` | 核对实际解析的 AAR 版本，确认使用当前源码产物；Compose 入口包名为 `com.cashcraft.ads.mediation.compose` |
| Manifest 合并提示缺少 `admobApplicationId` | 每个 flavor 都填写 App ID 占位符，不能使用广告位 ID |
| duplicate classes 涉及 Google Ads | 检查是否混入 legacy `play-services-ads` / `play-services-ads-lite` |
| `sdk_not_initialized` | Application 是否注册，是否先调用 `Ads.initialize()` |
| `sdk_initializing` | 首个 Activity 是否恢复、UMP 是否完成、平台初始化是否完成；不要阻塞主线程 |
| `consent_not_obtained` | 检查 `Ads.consentSnapshot` 和 UMP 结果 |
| `sdk_initialization_failed` | 查看平台初始化日志；重复 initialize 不会以新配置重启 SDK |
| `ad_format_disabled` | 对应全屏 ID 是否省略，AdMob preload 是否为 0 |
| `no_preloaded_ad` | 立即展示时没有有效候选；允许等待的场景可改用 `WhenReady` |
| `wait_timeout` / `ad_load_failed` | 等待到期或参与平台明确加载失败；及时结束 loading，按业务决定后续动作 |
| `activity_window_not_attached` / `activity_window_not_focused` | 页面是否具备展示窗口，是否被 Dialog 或其他窗口占用焦点 |
| `activity_not_resumed` / `app_not_in_foreground` | 宿主已暂停或退后台；不要复活已取消的插屏/激励机会 |
| `request_in_progress` / `another_full_screen_ad_showing` | 重复触发或其他全屏机会尚未结束 |
| `opportunity_cancelled` / `scene_invalid` | 主动取消或页面资格失效，通常按正常离页处理 |
| `topon_banner_not_supported` | 当前 Banner 仅支持 AdMob，不能用 TopOn placement |
| `Call Ads.initialize before using a configured Banner` | 在 Application 初始化中先调用 `Ads.initialize()`，再使用 position 入口或 `Ads.bannerRequest()`；无需等待 SDK READY |
| `Configure AdMobIds.bannerId before using a configured Banner` | 在 AdMob provider（或 Bidding 的 AdMob 配置）填写 `bannerId`；全屏 ID 不能替代 Banner ID |
| `native_not_configured` | 初始化配置缺少 Native ID；NativeRequest 不接受 ID 覆盖 |
| `native_position_occupied` | 同一 Native 位置同时连接了多个容器或页面实例 |
| `unsupported_native_render_mode` | 自定义 Native 布局收到了不支持的渲染模式，例如 TopOn 模板 |

调试包可启用 `loggingEnabled = BuildConfig.DEBUG`，查看日志：

```shell
adb logcat -v threadtime AdsMediation:D '*:S'
```

定位一次展示时保留 `position`、`ad_type`、`session_id`、`reason` 和平台错误码；加载通过 `request_id` 关联。不要只看 `READY` 状态判断展示、曝光或收益已经发生。

## 10 宿主接入验收

以下是接入后应在实际宿主执行的检查，不代表本文已完成设备验收：

- [ ] Debug 使用测试 ID，生产 flavor 使用对应真实 ID，Manifest 和初始化 App ID 一致。
- [ ] `Application` 只通过统一入口初始化，UMP 能结束或由业务等待上限正常放行页面。
- [ ] 三种全屏广告的正常关闭、无缓存、超时、取消均能结束 loading，业务不会重复继续。
- [ ] 激励只有明确奖励回调才发奖，并完成业务幂等结算。
- [ ] 插屏/激励等待中退后台或切页不补弹；开屏在原 Activity 恢复后按剩余预算继续。
- [ ] Navigation 返回、同路由多实例和保留 Tab 不复活旧机会；Compose 重组不重复请求。
- [ ] Banner 检查竖横屏、窄容器、遮罩、IME、刷新及页面销毁；尺寸没有被宿主强制截断。
- [ ] Native 检查默认布局或自定义素材完整性、位置唯一性、隐藏恢复和最终销毁。
- [ ] 曝光从事件回调确认，收益从独立收益出口确认；字段可关联且没有双重计费统计。
- [ ] 宿主开启 R8 / 资源收缩的 Release 包可启动并走通实际所用广告路径。

## 11 源码与详细示例

本文的 API 和参数已按当前源码静态核对；没有在业务宿主编译本文片段，也没有新做设备、真实广告填充或远程发布验证。示例中的容器、Navigation entry、页面状态及业务回调需由宿主提供。

| 内容 | 对应文件 |
| --- | --- |
| 初始化及全屏公开入口 | [Ads.kt](../src/main/java/com/cashcraft/ads/mediation/Ads.kt) |
| Provider、ID、缓存和 UMP 参数 | [AdsConfig.kt](../src/main/java/com/cashcraft/ads/mediation/AdsConfig.kt) |
| 展示与奖励结果 | [AdResult.kt](../src/main/java/com/cashcraft/ads/mediation/AdResult.kt) |
| 事件与收益字段 | [AdEvent.kt](../src/main/java/com/cashcraft/ads/mediation/AdEvent.kt)、[AdRevenue.kt](../src/main/java/com/cashcraft/ads/mediation/AdRevenue.kt) |
| Banner 参数与入口 | [BannerRequest.kt](../src/main/java/com/cashcraft/ads/mediation/BannerRequest.kt)、[BannerBinding.kt](../src/main/java/com/cashcraft/ads/mediation/BannerBinding.kt)、[AdsBanner.kt](../src/main/java/com/cashcraft/ads/mediation/compose/AdsBanner.kt)、[AdsBannerView.kt](../src/main/java/com/cashcraft/ads/mediation/AdsBannerView.kt) |
| Native 参数与入口 | [NativeRequest.kt](../src/main/java/com/cashcraft/ads/mediation/NativeRequest.kt)、[AdsNativeView.kt](../src/main/java/com/cashcraft/ads/mediation/AdsNativeView.kt) |
| 全屏页面生命周期示例 | [全屏展示机会接入说明](fullscreen-display-opportunities.md) |
| Banner Fragment 宿主示例 | [TraditionalBannerActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/TraditionalBannerActivity.kt) |
| Native View 与 Compose 宿主示例 | [NativeSmokeActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/NativeSmokeActivity.kt) |

smoke 示例用于了解调用和生命周期组织方式，其中的探针、测试凭据读取和自动化开关不属于业务接入要求。
