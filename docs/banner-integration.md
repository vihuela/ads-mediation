# Banner 接入（本次变更，尚未远程发布）

正式入口支持 AdMob GMA Next-Gen 1.2.1。TopOn 请求返回
`BannerState.Failed("topon_banner_not_supported")`，不会请求 TopOn 或回退 AdMob。
TopOn 的尺寸、刷新身份反例保留在 [验证记录](../openspec/changes/archive/2026-09-29-add-banner-support/verification.md)，后续单独补齐。

## 依赖与初始化

本地构建使用 `com.cashcraft:ads-mediation:1.0.0-SNAPSHOT`。Compose 可选坐标是
`com.cashcraft:ads-mediation-compose:1.0.0-SNAPSHOT`；稳定版 `1.0.5` 不包含这些新入口。
普通 View 宿主只依赖核心，核心没有 Compose／Navigation 依赖。两模块均为 minSdk 26、compileSdk 36、JVM 17。
Compose 模块使用 Kotlin／Compose 编译插件 2.2.21、Compose UI 1.7.6、Lifecycle Compose 2.8.7。

沿用 `Ads.initialize()`，配置 AdMob 或含 AdMob 的 Bidding provider。Banner 检查 AdMob 自身初始化结果与请求前 UMP 许可；整体 Bidding 初始化成功不能放行失败的 AdMob。
测试宿主关闭 UMP 仅用于自动化，不应复制到生产隐私配置。

## View／Fragment

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
完整、参与编译的 Fragment 示例见 [TraditionalBannerActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/TraditionalBannerActivity.kt)。

- `setActive(false)` 结束业务周期并释放；再次启用创建新 slot。最终 `destroy()` 后不能重新启用。
- 临时隐藏使用 `visibility = View.INVISIBLE`，保留合法占位。隐藏父容器、失焦、后台及临时脱离会隐藏真实广告；全部资格恢复后复用原实例。
- 完整页面离开应结束其业务归属。Fragment 的 View owner 销毁会自动释放；不能把所有 `ON_PAUSE` 都视为离页，因为 Dialog、落地页和后台属于临时暂停。

## Compose

```kotlin
AdsBanner(
    request = BannerRequest(AdPlatform.ADMOB, bannerId, "detail_bottom", BannerSize.AnchoredAdaptive),
    lifecycleOwner = backStackEntry,
    active = pageOwnsBanner,
    visible = !sameWindowOverlay,
    modifier = Modifier.fillMaxWidth(),
    onState = { state -> /* 使用最新回调 */ },
)
```

默认使用 `LocalLifecycleOwner.current`，Navigation 页面应明确传实际 entry owner。相同路由的不同 entry 不能共用一个 View。
等值请求、普通重组、回调更新和页面内 Tab 切换不重新请求；`visible=false` 留在组合中并映射 `INVISIBLE`。
移出组合意味着最终释放。Preview 不初始化广告 SDK。

可选公共底部区域由宿主放在 NavHost 外，传公共区域的 owner：在允许广告的页面间保留该 owner 和 request；
离开允许区域时设置 `active=false`，遮罩覆盖广告时另设 `visible=false`。路由仅用于业务白名单，不能用路由字符串代替 owner。
页面独立持有的可运行示例见 [SmokeActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/SmokeActivity.kt)。

## 尺寸、刷新与失败

`Standard320x50` 明确为 320×50 dp，内容宽度不足 320 dp 时失败。`AnchoredAdaptive` 使用实际内容宽度调用锁定 SDK 的 large anchored adaptive API。
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
`AdEvent` 增加 `slotId` 后构造及 `copy` 的 JVM 签名改变，宿主及依赖它的二进制模块必须重新编译；不能把默认参数视为二进制兼容保证。
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

[BannerContractInstrumentation](../r8-smoke-app/src/androidTest/java/com/cashcraft/ads/mediation/smoke/BannerContractInstrumentation.kt)
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
