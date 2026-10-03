# Banner 接入（本次变更，尚未远程发布）

正式入口支持 AdMob GMA Next-Gen 1.2.1。显式 TopOn 请求通过 `onState` 返回
`AdShowResult.Failed("topon_banner_not_supported")`，不会请求 TopOn 或回退 AdMob。
TopOn-only 配置使用 position 便捷入口时会抛出 `IllegalStateException`，见下方初始化要求。
TopOn 的尺寸、刷新身份反例保留在 [验证记录](../openspec/changes/archive/2026-09-29-add-banner-support/verification.md)，后续单独补齐。

## 依赖与初始化

本地构建只需依赖 `com.cashcraft:ads-mediation:1.0.0-SNAPSHOT`；同一库包含 View 与 Compose 入口。
稳定版 `1.0.5` 尚不包含这些新入口。库为 minSdk 26、compileSdk 36、JVM 17；Compose 使用 Kotlin／Compose 编译插件 2.2.21、Compose UI 1.7.6、Lifecycle Compose 2.8.7。

沿用 `Ads.initialize()`，配置 AdMob 或含 AdMob 的 Bidding provider。Banner 检查 AdMob 自身初始化结果与请求前 UMP 许可；整体 Bidding 初始化成功不能放行失败的 AdMob。
仅使用 Banner 时，配置 AdMob 应用 ID 和 Banner 广告位 ID，三种全屏广告位 ID 都可省略：

```kotlin
val config = AdsConfig(
    provider = AdMobProviderConfig(
        ids = AdMobIds(
            applicationId = "AdMob App ID",
            bannerId = "ca-app-pub-3940256099942544/9214589741", // 官方测试 ID
        ),
    ),
)
```

在应用中沿用 `Ads.initialize()` 传入配置，再使用下面的便捷入口；调用前必须已经调用
`Ads.initialize()`，无需等待初始化完成。便捷入口从 AdMob provider 或 Bidding provider 中的
AdMob 配置读取 `bannerId`，Banner 不参与全屏竞价。
未调用初始化、缺少 Banner ID 或使用 TopOn-only 配置时，便捷入口立即抛出说明原因的
`IllegalStateException`；空 `position` 仍抛出 `IllegalArgumentException`。

`AdMobIds.bannerId: String? = null` 为可选配置；缺少它只影响便捷入口，显式
`BannerRequest` 仍可提供广告位 ID。此配置不会启动全屏预加载或自动开屏。
`AdMobPreloadConfig(banner = 0)` 只关闭 `Ads.preloadBanner()` 的预加载，不关闭 Banner View 本身的请求。
测试宿主关闭 UMP 仅用于自动化，不应复制到生产隐私配置。

## Compose：一行接入

```kotlin
import com.cashcraft.ads.mediation.compose.AdsBanner

AdsBanner(position = "home_bottom")
```

默认使用 `LocalLifecycleOwner.current`，应将组件放在所属页面的生命周期作用域内。
需要覆盖默认 owner 时，再传该页面实例的 `lifecycleOwner`；不要给不同页面共用 Activity owner。
Preview 不要求调用 `Ads.initialize()`，也不初始化广告 SDK。原有 request 重载继续可用。
默认 `AnchoredAdaptive` 保持原有 large anchored adaptive 尺寸语义；可通过 `size` 覆盖。
普通接入不需要状态回调，定制 UI 时可选传 `onState`。

## Fragment：一行绑定

在 `onViewCreated()` 中调用：

```kotlin
import com.cashcraft.ads.mediation.bindBanner

bindBanner(container = bannerContainer, position = "home_bottom")
```

内部使用 Fragment 的 `viewLifecycleOwner`，自动挂载和释放；业务无需保存返回对象，
无需在 `onDestroyView()` 手动调用 `destroy()`。重复相同绑定不重复加载，
不同绑定替换并释放前一个。绑定只操作自身创建的 View，不清空容器中的其他子 View。
调用在主线程进行；默认尺寸同样保持原有 `AnchoredAdaptive` 语义。
需要定制时可选传 `size`、`active`、`onState`，或使用返回的 `AdsBannerView`。

## Activity：集中配置与 View 挂载

Activity 公共区域继续使用 `AdsBannerView`，通过 `Ads.bannerRequest()` 取得默认广告位，
页面无需再填写平台或广告位 ID。以下代码在实现 `LifecycleOwner` 的 Activity 中执行一次：

```kotlin
val request = Ads.bannerRequest(position = "home_bottom")
val banner = AdsBannerView(activity = this, lifecycleOwner = this, request = request)
bannerContainer.addView(banner, ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
))
```

`bindBanner()` 只用于 Fragment；Activity 仍负责添加自己的 Banner View。
Activity owner 销毁时会自动释放；若持有字段，也可在 `onDestroy()` 显式销毁并清空。
只属于某个页面的广告应使用该页面的 owner 或业务 `active`，公共底栏才适合随 Activity 持有。

## HealthTracker 宿主接入

HealthTracker 已迁移到集中配置与 `Ads.bannerRequest()`。当前仍由 `MainAct` 持有原生 View 公共底栏，
未改成 Compose Banner 或 Fragment 的 `bindBanner()`。

| 环节 | 当前调用与行为 |
| --- | --- |
| 初始化 | `AppInitializer.initializeAds()` 将 `BuildConfig.ADMOB_BANNER_ID` 写入 `AdMobIds.bannerId`，沿用渠道配置 |
| 共用请求 | `MainAct.homeBannerRequest` 在访问时调用 `Ads.bannerRequest(AdPosition.BA_HOME_BOTTOM, BannerSize.StandardAnchoredAdaptive)`；先完成 `Ads.initialize()` 调用即可，无需等 SDK READY |
| 预加载 | 冷启动的 `SplashScreen` 在根布局完成测量后传入该请求及内容宽度，`autoRefill = false` |
| 展示 | `MainAct.setupHomeBanner()` 在权限／引导流程完成并恢复前台后，用同一请求创建 `AdsBannerView(this, this, request)` |
| Tab 与遮罩 | 主界面 Tab 切换复用同一个 Banner；首页引导遮罩出现时设为 `INVISIBLE`，结束后恢复 |
| 释放 | `MainAct.onDestroy()` 销毁 Banner 并清空引用，owner 的自动释放可重复安全执行 |

尺寸保持 `StandardAnchoredAdaptive`，不能因便捷入口默认值为 `AnchoredAdaptive` 而省略，
否则会切换到大尺寸自适应。预加载和展示共用这一定义，以保持广告位、位置和尺寸一致。

宿主默认通过 `includeBuild("../ads-mediation")` 使用相邻广告库源码，无需发布远程包。
切换到已发布 AAR 时，必须确认版本含 `AdMobIds.bannerId` 和 `Ads.bannerRequest()`，并重新编译宿主及依赖模块。

本次宿主迁移于 2026-10-03 通过 `:app:assembleDebug`。这项证据仅覆盖宿主编译及打包，
尚未完成本次迁移后的设备展示、刷新或视觉验收；下文广告库的历史设备记录不能替代宿主验收。

## 高级接入：显式 request 与页面控制

需要显式指定平台／广告位 ID、手动管理 View 或控制页面广告归属时，继续使用原有接口。
显式 TopOn 请求仍返回 `AdShowResult.Failed("topon_banner_not_supported")`，
不会请求 TopOn 或回退 AdMob；这与 TopOn-only 配置调用便捷入口时的配置异常不同。

### 显式 request 与 active／visible：Compose

```kotlin
AdsBanner(
    request = BannerRequest(AdPlatform.ADMOB, bannerId, "detail_bottom", BannerSize.AnchoredAdaptive),
    lifecycleOwner = backStackEntry,
    active = pageOwnsBanner,
    visible = !sameWindowOverlay,
    modifier = Modifier.fillMaxWidth(),
    onState = { state ->
        if (state is AdShowResult.Failed) {
            // 与全屏广告共用失败处理逻辑，例如记录 state.reason。
        }
    },
)
```

默认使用 `LocalLifecycleOwner.current`；页面作用域未提供实际 entry owner 时再显式传入。相同路由的不同 entry 不能共用一个 View。
等值请求、普通重组、回调更新和页面内 Tab 切换不重新请求；`visible=false` 留在组合中并映射 `INVISIBLE`。
移出组合意味着最终释放。Preview 不初始化广告 SDK。

可选公共底部区域由宿主放在 NavHost 外，传公共区域的 owner：在允许广告的页面间保留该 owner 和 request；
离开允许区域时设置 `active=false`，遮罩覆盖广告时另设 `visible=false`。路由仅用于业务白名单，不能用路由字符串代替 owner。
页面独立持有的可运行示例见 [SmokeActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/SmokeActivity.kt)。

### 显式 request：View／Fragment

```kotlin
private var banner: AdsBannerView? = null

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val request = BannerRequest(
        platform = AdPlatform.ADMOB,
        adUnitId = "ca-app-pub-3940256099942544/9214589741", // 官方测试 ID
        position = "home_bottom",
        size = BannerSize.AnchoredAdaptive,
    )
    banner = AdsBannerView(requireActivity(), viewLifecycleOwner, request, active = true) {
        state -> // 仅更新 UI；Ready 不等于实际曝光
    }.also {
        bannerContainer.addView(it, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }
}

override fun onDestroyView() {
    banner?.destroy() // owner 销毁也会释放，重复调用安全
    banner = null
    super.onDestroyView()
}
```

所有 View 宿主调用在主线程进行。Activity、owner、request 固定于构造时；更换时销毁旧 View，创建新 View。
只向宿主添加这个自有 View，不跨页面搬运平台 View，不调用宿主 `removeAllViews()`。
使用 `bindBanner()` 的完整、参与编译的 Fragment 示例见 [TraditionalBannerActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/TraditionalBannerActivity.kt)。

- `setActive(false)` 结束业务周期并释放；再次启用创建新 slot。最终 `destroy()` 后不能重新启用。
- 临时隐藏使用 `visibility = View.INVISIBLE`，保留合法占位。隐藏父容器、失焦、后台及临时脱离会隐藏真实广告；全部资格恢复后复用原实例。
- 完整页面离开应结束其业务归属。Fragment 的 View owner 销毁会自动释放；不能把所有 `ON_PAUSE` 都视为离页，因为 Dialog、落地页和后台属于临时暂停。

## 可选预加载与补货

普通展示无需预加载。需要预加载时，可用便捷入口生成 request，也可沿用显式 `BannerRequest`：

```kotlin
val request = Ads.bannerRequest(position = "home_bottom")
```

`Ads.bannerRequest(position: String, size: BannerSize = BannerSize.AnchoredAdaptive): BannerRequest`
从初始化配置选取 AdMob provider 或 Bidding provider 中的 AdMob `bannerId`，不经过全屏竞价。
它与前述 Compose／Fragment 便捷入口共用初始化与 ID 检查。
在根布局首次完成测量后，用与展示容器一致的内容宽度（dp，已扣宿主 padding）预加载：

```kotlin
Ads.preloadBanner(activity, request, contentWidthDp, autoRefill = false)
```

`request` 的广告位 ID、position 和解析后的尺寸必须与 `AdsBannerView` 一致。
初始化未完成时只保留 placement 的最新配置，AdMob 初始化成功后再启动；不保留 Activity。
默认 `autoRefill = true` 保持 SDK 持续补货行为，库存上限由 `AdMobPreloadConfig.banner` 控制。
同 placement 的相同配置不重复启动；尺寸或补货参数改变时替换配置，true 模式的库存上限改变也会替换。
旧预加载／尚未消费的缓存先销毁再启动；旧单次请求不可取消，但其晚回调会立即销毁广告，
即使配置切回原值也不会把旧结果放入新库存。已经取出的广告归 View 所有，由 View 释放。

**false 为单次预加载：**1.2.1 的 `PreloadConfiguration` 没有关闭补货开关，因此直接调用公开的
`BannerAd.load(request, callback)`，不调用 `BannerAdPreloader.start()` 或 `pollAd()`。
每个 placement 配置只请求一次、最多存一条；`banner > 0` 时即使配置大于 1，false 仍只存一条。
空 poll 不取消在途加载；成功 poll 仅移交库存，不触发任何加载。失败、消费或过期后不自动重试／补货，
加载中或库存有效时再次调用同配置不重复请求；消费、失败或过期后，调用方可再次显式调用预加载来启动新的一次请求。没有 timer，也不会自动刷新预加载库存。

1.2.1 没有公开的 Banner 有效性查询；复用 `RetainedAd`，以请求开始的单调时钟时间为起点，
采用保守的 1 小时缓存上限，不因晚回调或重复调用延长寿命。加载完成／取出时检查过期并销毁；
无 timer，未访问的过期对象会在下一次取出或配置替换时清理。该上限是本库策略，不保证素材有效期。
没有预加载库存时，Banner View 仍按原流程直接加载；其展示请求不属于预加载补货。

这里的补货指预加载库存补齐；已展示广告的自动刷新由 AdMob 广告位后台及 SDK 控制，
`autoRefill = false` **不关闭广告位展示刷新**，也不改变页面可见性与生命周期规则。
参数带 Kotlin 默认值，现有源码调用可继续使用；宿主及依赖库需要重新编译，不保证旧二进制签名兼容。
依据：[PreloadConfiguration](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/common/PreloadConfiguration)、
[BannerAdPreloader](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/banner/BannerAdPreloader)。
单次加载依据：[BannerAd.load](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/banner/BannerAd#load)。
该 API 在较新 SDK 已弃用；本实现以实际依赖 1.2.1 的公开签名为准，升级时需复核。

## 尺寸、刷新与失败

View 与 Compose 的 `onState` 仍接收 `BannerState`；失败值统一为 `AdShowResult.Failed`，
它同时实现 `AdShowResult` 和 `BannerState`。其余状态仍为 `Inactive`、`Waiting`、`Loading`、
`Ready`、`Destroyed`。Banner 的失败描述当前请求或配置问题，不代表此前没有曝光或收益；
同一个 Banner 仍可能通过 SDK 刷新恢复为 `Ready`。

`Standard320x50` 明确为 320×50 dp，内容宽度不足 320 dp 时失败。
`StandardAnchoredAdaptive` 使用实际内容宽度调用 `getCurrentOrientationAnchoredAdaptiveBannerAdSize`，用于恢复普通锚定自适应的紧凑高度；HealthTracker 首页使用此选项。该 Google API 已弃用，本选项为历史尺寸兼容保留，升级底层 SDK 时需复核。
`AnchoredAdaptive` 保持原有的 large anchored adaptive API 行为，已有使用方不会因新增普通尺寸而改变高度。
只扣 View 自身 padding；系统安全区、底部导航栏及 IME 由宿主处理一次。容器用 `WRAP_CONTENT`，不限制成 50 dp。
仅有效请求尺寸改变才重建；广告自然高度变化只重新测量。初始隐藏也会测量合法占位，dp 转 px 向上取整，避免分数密度下少分配像素。

Pixel 7 Pro/API33 的早期横屏版本出现过 `INVALID_REQUEST: Ad size will not fit on screen.`。
修正不可见空 View 预挂载、原生子布局及像素取整后，最终 Release/R8 两次横屏首次请求均展示完整素材，
曝光与收益关联一致；详见验证记录中的最终横屏证据。竖屏标准／自适应、窄容器拒绝与 IME 也有实际证据。
最终 Release/R8 的 Compose 与 Fragment 已在真实分屏中展示完整测试素材；受控设备测试确认子 View 高度变化只重新测量、不新增加载。平台服务端主动改变素材高度未单独观察，受控测量测试不冒充该来源的实测。

刷新由 AdMob 控制，SDK 本层没有计时器、网络监听或自动 `retry()`。请在自己的 AdMob 广告位后台记录是否开启刷新及间隔。
Google 文档说明自动刷新依赖广告可见，开启后也可处理加载失败；本仓库的官方测试广告已观察到自动刷新，但这不证明生产广告位的后台设置。
首次失败保留合法占位和失败 AdView；关闭刷新或平台未恢复时会持续为空，直至真实新周期或必要尺寸变化。
不能借反复启停、导航或重组模拟重试。首次断网同页恢复已有设备证据；关闭刷新对照与首次无填充恢复已由用户批准延期，登记为 [BANNER-REFRESH-01](../openspec/changes/archive/2026-09-29-add-banner-support/tasks.md#已批准延期的技术债务)，在后续接入正式广告位时使用测试设备／测试模式补验。延期不代表该路径已通过。
[Google Banner 指南](https://developers.google.com/admob/android/next-gen/banner#refresh_an_ad)

## 事件、收益与兼容

`ad_position` 每个 active 周期一次，`slot_id` 关联整个周期。实际本层加载才产生 request；失败后平台内部成功不补第二个 load result。
首次展示关联已确认的初始 request；平台刷新没有本层 request，刷新事件不冒充曝光。
每个已确认 response ID 的展示有独立 session，paid／impression 分别去重，顺序不限，合法零收益照常交付。
收益同时进入 `ad_paid` 和全局 `revenueListener`；一个监听抛异常不阻断另一个。

每次本层加载最多保留 32 个展示的不可变身份和去重元数据，不含页面引用。
已确认的旧展示收益即使页面销毁仍可交付；未知／已淘汰身份只写诊断，不归给最新广告，不补造正常收益。
这不保证 SDK 在销毁后一定回调。AdMob 全屏内容关闭表示落地内容退出，**不映射为 Banner 本体 `ad_close`**；本版不提供 Banner 关闭按钮。

新增 `AdFormat.BANNER`、`AdEventName.BANNER_REFRESH` 需要更新穷尽 `when` 与事件解析。
原 `BannerState.Failed` 已移除，构造、类型判断及穷尽 `when` 分支统一改为 `AdShowResult.Failed`，
并重新编译使用 Banner 的宿主与依赖模块；`onState` 参数类型和 `reason: String` 保持不变。
`AdEvent` 增加 `slotId` 后构造及 `copy` 的 JVM 签名改变，宿主及依赖它的二进制模块必须重新编译；不能把默认参数视为二进制兼容保证。
`AdMobIds` 新增带默认值的可选 `bannerId` 参数，原有源码调用可继续使用；
宿主及依赖模块需要重新编译，不承诺二进制兼容。原有显式 request 接口继续可用，
不要求配置 `AdMobIds.bannerId`。便捷入口的默认尺寸仍为原有 `AnchoredAdaptive`。
原全屏入口明确拒绝 BANNER，Banner 不参与全屏缓存竞价或展示锁。

完整任务与分层证据见 [tasks.md](../openspec/changes/archive/2026-09-29-add-banner-support/tasks.md) 和
[verification.md](../openspec/changes/archive/2026-09-29-add-banner-support/verification.md)。未勾选项仍未完成，不表示正式发布已获验收。

## 本地复验

`r8-smoke-app` 使用官方 AdMob 测试 ID。Release 仅为了安装验收使用 debug 签名；不是待发布的业务应用。
在专用模拟器安装构建出的 APK 后，可通过启动 extra 复验以下状态：

```shell
adb -s <专用设备> shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --ez banner_visible false
adb -s <专用设备> shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --ez banner_active false
adb -s <专用设备> shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --ez shared_footer true
adb -s <专用设备> shell am start -n com.cashcraft.ads.mediation.smoke/.SmokeActivity --ez standard_banner true
```

每组启动前关闭该测试应用，避免复用旧 Intent。隐藏场景按 “Reveal while deactivating” 后仍应无 Banner load；
Platform 切换到 TopOn 后应显示明确 Failed，不能显示旧 View 的 Destroyed。`standard_banner` 只影响传统 Fragment 示例。
Fragment 的 Parent visibility 和 Detach/reattach 按钮是临时宿主变化测试，实际业务无需复制这些按钮。
Show test interstitial 用于检查应用内全屏覆盖与既有全屏入口。记录真实方向配置和截图，不能仅凭发出的旋转命令判断横屏。


横屏首载回归可用 `r8-smoke-app/check_banner_log.py` 检查事件。用全新进程启动正式 smoke 页，
记录实际 `am get-config` 为 land 并保留完整素材截图，再从该次日志取 slot_id：

```shell
adb -s <专用设备> logcat -d --pid=<本次进程> -v time AdsMediation:D '*:S' > /tmp/banner-cold.log
python3 r8-smoke-app/check_banner_log.py /tmp/banner-cold.log <本次slot_id>
```

该检查要求首次请求直接成功并产生匹配的曝光/收益；首次失败后靠 SDK 刷新恢复、或只有 Ready 没有曝光，都会失败。
它不替代素材布局截图，也不证明关闭刷新配置或所有生命周期场景。

## 设备端契约回归

[BannerContractInstrumentation](../r8-smoke-app/src/androidTestDebug/java/com/cashcraft/ads/mediation/smoke/BannerContractInstrumentation.kt)
使用平台原生 Instrumentation、Debug 专用空 Activity 和官方测试广告，不增加测试框架或生产测试入口。
测试 Application、回调记录与 SDK 接口替身仅在测试 APK 中。初始化等待、零宽／隐藏、加载通知重入、
在途销毁／重启、配置异常、过期对象、三个回调出口异常、已确认收益的排队交付与交错暂停均有断言。
回调工厂注入只访问本仓库自有代码；SDK 对象采用公开接口。注入的曝光／收益属于测试事件，不能当作真实曝光或收入证据。

```shell
./gradlew :r8-smoke-app:assembleDebug :r8-smoke-app:assembleDebugAndroidTest
adb -s <专用设备> install -r r8-smoke-app/build/outputs/apk/debug/r8-smoke-app-debug.apk
adb -s <专用设备> install -r r8-smoke-app/build/outputs/apk/androidTest/debug/r8-smoke-app-debug-androidTest.apk
adb -s <专用设备> shell am instrument -w \
  com.cashcraft.ads.mediation.smoke.test/com.cashcraft.ads.mediation.smoke.BannerContractInstrumentation
```

默认执行 11 组断言，以最终 `PASS: 11 Banner device contract cases` 判断成功；`am instrument` 的进程退出码不能单独证明用例通过。
可在专用模拟器增加 `-e offline true`，执行第 12 组首次断网失败、暂停交错及 SDK 自动恢复检查。
此选项要求初始 Wi-Fi 开启、移动数据关闭；用例临时关闭 Wi-Fi 并在 `finally` 恢复，最长等待 SDK 恢复 120 秒。
它验证保留实例和零本层重试，不替代广告位后台刷新开关的对照。
