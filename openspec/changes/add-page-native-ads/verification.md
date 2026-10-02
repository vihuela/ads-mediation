# Native 实施验证记录

日期：2026-09-29。状态：核心实现已接入，平台/视觉验收仍有未完成项；不将本记录或规划通过视为所有来源已支持。

最近实测：三星 Pangle、Meta 定向静态测试广告已分别显示并产生真实 SDK 曝光/paid 回调；AdMob 实机生命周期新增 4 个用例均取得通过证据。模拟器 Pangle 静态空白仍未定位，允许 HTTP 的对照未消除空白。下文按时间保留历史结果，最新来源与生命周期结论见末尾对应章节；当前仍为 26/39。

执行安排更新：用户明确要求先依据最佳实践和官方文档完成实现，具体展示及来源表现留到生产环境验证。无需再等待本机模板/逐来源测试配置来推进实现；现有真实验收要求不删改，无实测证据的混合任务仍保留未勾选。模板比例继续作为宿主显式输入，不用猜测值代替后台配置。

## 基线、版本与范围

- 工作目录：`/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation`，启动时 detached HEAD。保留已有两份 Banner/Native 方案及在途全屏变更，没有提交、发布或修改 HealthTracker 配置。
- 广告版本保持 GMA Next-Gen 1.2.1、TopOn 6.6.22.3、GMA adapter 1.2.1.1.0、UMP 4.0.0，minSdk 26。新增明确的 Lifecycle 2.9.4 API 依赖及可选 Compose 模块。
- 核心 `debugRuntimeClasspath` 没有 Compose、Navigation、legacy `play-services-ads` 或 `play-services-ads-lite`。证据：`/private/tmp/native-core-runtime-dependencies.log`；TopOn 版本约束见 `/private/tmp/native-core-dependency.log`。
- 测试配置由用户授权的 `/Users/jiaoyun/od-fz/HealthTracker/scripts/internal.gradle` 只读提取，临时 properties 文件权限 0600；凭据未写入版本库。文件有 `native`、`full_native`，没有模板宽高比或逐来源开关。测试 APK 含本机注入的测试配置，不作为可发布产物。
- 原生子代理分别处理 AdMob、TopOn、默认/Compose UI 和全屏格式边界，并进行一次限定范围的重入复核。请求继承主模型、high 推理；实际 provider/model/effort 未被工具独立验证。所有子代理已结束。

## 已实现边界

- 页面独占 `AdsNativeView`，显式平台/ID/position；Default 与 Custom 共享 View/XML binding。核心不使用 Compose/Navigation，`:ads-compose` 提供 AndroidView 包装、Preview、稳定身份和释放。
- 主线程门控包括所选平台配置/初始化、UMP、owner RESUMED、active/visible、附着、聚合可见性、窗口焦点和有效宽度。Bidding 的另一端 READY 不替代所选平台 READY。
- 每个 slot/request 有独立身份。释放先失效代次并解绑页面闭包，再取消/拆 View/销毁；外部事件重入不能覆盖新周期。失败事件内立即 retry 已专门回归。
- 隐藏、后台、detach、失焦固定释放重建；尚不声称任何网络可安全保留视频。只取消本层有效请求，不承诺底层网络或 TopOn 共享缓存全部取消，不盲取缓存清理。
- AdMob 使用单条 Native loader，按单调时钟记录一小时失效边界。TopOn 不伪造统一 TTL。无定时刷新、预取或全局 Native 池。
- 自定义布局拒绝 TopOn 模板；默认模板必须有确认的比例，否则 `native_template_size_unknown`。保留平台模板容器的明确高度，不被外层 wrap_content 覆盖。
- paid 与曝光独立，零金额合法；非法数据丢弃并诊断。旧广告迟到 paid 使用原 request/session，不写入新卡片状态。Native 不参与全屏锁或竞价，事件使用实际 ADMOB/TOPON mode。
- 自动开屏共用交互票据：点击 5 秒、缺少覆盖层关闭时 120 秒兜底；外跳首次返回消费资格。其确定性状态测试通过，平台完整设备时序尚未验收。TopOn 没有凭空补造覆盖层关闭信号。

## 构建、JVM 与 Android 绑定测试

- 基线 `:compileDebugKotlin --offline` 通过：`/private/tmp/native-baseline-build.log`。
- 最终 JVM 92 tests / 0 failures / 0 errors。包含既有全屏、竞价、展示机会回归，以及 Native 生命周期/单在途/取消/双实例/重入/expiry/retry/迟到收益/零金额/非法金额/重复 paid/实际 mode/交互票据。
- 重入修复有红绿证据：`/private/tmp/native-reentry-red.log` 中两个回归先失败，修复后全量通过。最终报告：`build/reports/tests/testDebugUnitTest/index.html`。
- 核心与 Compose `assembleDebug`、`lintDebug` 通过；lint 无 error，保留可解释的布局/显式构造函数/依赖写法 warning，没有升级依赖来消除版本提示。
- Android 测试使用平台自带 runner，测试库仅 `androidTestCompileOnly`，没有将测试库加入核心运行依赖。API 33 上 **5 tests passed**：独立默认 binding、已挂载 root 不被移除、外部/重复引用拒绝、占用媒体/缺少广告标识拒绝。另有真实 Fragment detach/attach 的 viewLifecycleOwner 销毁重建测试：旧卡片不可复活，新 View 得到独立卡片。最终原始结果 `build/reports/native/fragment-and-binding-instrumentation.log`。
- Debug 与 minified Release smoke 构建通过。R8 有 Pangle `NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE` 两条缺类 warning；没有新增 blanket keep/dontwarn。不能据此宣称所有 Pangle 路径都安全。
- 构建命令使用 `--offline --console=plain`、`-PnativeSmoke=true -PnativeTestConfig=/private/tmp/ads-native-test.properties`。过程日志：`/private/tmp/native-validation-build.log`、`/private/tmp/native-smoke-build.log`、`/private/tmp/native-smoke-final.log`；稳定错误码/收益诊断收口验证见 `/private/tmp/native-final-checks.log`；随后 TU 素材路径验证见 `/private/tmp/native-tu-final-checks.log`。Fragment/第二 XML 的最终宿主构建另见 `/private/tmp/native-host-checks.log`（退出码 0）。

## 设备与实际广告

仅使用 `emulator-5560`，Android API 33；独立包名 `com.cashcraft.ads.mediation.smoke.native`。没有操作已连接实体设备或覆盖已有 smoke 包。使用本机测试配置及 AdMob 官方 demo ID；未点击未确认测试属性的创意。

| 场景 | 观察与证据 | 结论范围 |
| --- | --- | --- |
| AdMob 默认 View | 官方 Native ID；filled → Loaded → SDK impression → paid；0 micros USD，原 response/session 一致 | 加载、真实收益和默认素材绑定通过 |
| AdMob 验证器 | 默认和自定义布局均显示 `No implementation issues found` | 不替代大字体/深色/分屏/视频验收 |
| AdMob compact XML / 最终 R8 | 最终 APK 进程 4091 使用 120dp 第二套 XML，真实 filled/Loaded/impression/0 micros paid；截图 `compact-release.png` | 第二套业务 XML 的基本渲染通过 |
| AdMob Compose 自定义双卡片 | 同 ID 两个 request/response/session，各自 Loaded；屏幕内第一张有曝光和 paid，第二张未曝光不伪造事件 | 基本实例隔离通过 |
| Compose 回调重组 | 控件 XML 确认 Callback: 1；当前进程保持一次 Native load，未因等值 request 或新回调重建 | 基本重组行为通过 |
| Compose 详情/返回 | 详情 XML 确认真正离页，最新 callback=1 收到 Idle；返回恰好新增一次请求，新 callback=1 收到 Loading/Loaded 和新收益 | 临时生命周期路径，非 Fragment/NavBackStackEntry 全矩阵 |
| TopOn 默认自渲染 | `native` 位返回 Pangle Test Ads；render 后 Loaded，paid **先于** impression；**2000 micros USD**，使用真实 `TUAdInfo.publisherRevenue`/showId | Native paid 已接通，不是 eCPM 推算 |
| TopOn 隐藏/恢复 | visible=false → Idle、卡片移除；恢复仅新增一条 request | 本层释放重建通过，视频静默未证明 |
| TopOn 混合渲染结果 | 同一个 `native` 位下一次返回模板；无比例时明确 Failed(`native_template_size_unknown`)，没有自动循环加载 | 未知模板尺寸失败通过；模板正常展示仍未验收 |
| TopOn full_native + Custom | 用户同一配置的另一测试位返回模板，明确 `unsupported_native_render_mode`，无静默回退 | 自定义/模板失败边界通过 |
| TopOn 同位双实例 | 两个有效本层请求都收到 loader 成功通知，但仅第一张可 `getNativeAd()`；第二张 `native_ad_missing_after_load`，没有复用第一张对象 | SDK 共享库存并不保证双填充；不能声明双实例稳定同时可展示 |
| TopOn 素材修正后复测 | Pangle Test Video 3 的 SDK 视频结束页/内嵌 CTA 可见，仍有 2000 micros paid；截图 `topon-material-retest.png` | 该视频创意基本显示通过；不同于早期 Image 3，不能据此判定图片空白已修复 |
| TopOn Pangle 媒体 | 文字、标识、CTA 显示，SDK 提供的 FrameLayout/ImageView 均有非零 bounds，但本次静态媒体区域空白 | **视觉不通过**；未证明是库绑定、SDK 素材或网络原因，不用收益成功替代视觉验收 |

主要截图和 UI XML：`build/reports/native/`。包括 `admob-default-loaded.png`、`compose-current.png`、`topon-default-loaded.png`、`topon-hidden.png`、`topon-restored.png`、`recompose-checked.xml`、`details-checked.xml`。

事件证据：`verified-native-events.log`（仅本轮 NativeSmoke 行），原始记录 `topon-debug.log`、`continued-runtime.log`。后者包含设备历史 AndroidRuntime 行，核验时必须按本次进程/时间过滤，不能把其他应用旧异常归给本实现。TU 注册调整以官方自渲染示例为依据，并补齐 SDK 自带图片组件回退；本轮没有做素材 URL 下载或 SDK 逆向。早期 AdMob 首次事件曾在终端观察到，后续 Compose 的真实 paid 另有持续日志保存，不依赖已被环形日志覆盖的记录。

## 精确接口与来源

- AdMob：`NativeAdLoader.load(NativeAdRequest.Builder(id, listOf(NATIVE)).build(), NativeAdLoaderCallback)`；在交付对象前设置 `NativeAd.adEventCallback`，同一个 `NativeAdEventCallback` 承接 impression/click/paid/fullscreen overlay。素材用 Next-Gen `NativeAdView.registerNativeAd(nativeAd, MediaView)`。销毁 View 和 ad 都幂等，不引入 legacy API。AdChoices 使用 SDK 自动 overlay，避免依赖 allow-list 的自定义披露 API。
- TopOn：`TUNative(Activity, placement, TUNativeNetworkListener)` → `makeAdRequest()` → 有效代次才 `getNativeAd()`。`NativeAd.setAdRevenueListener(TUAdRevenueListener)` 在交付、render/prepare 之前安装；回调 `onAdRevenuePaid(TUAdInfo)`，金额 `getPublisherRevenue()` 转 micros，币种 `getCurrency()`，身份 `showId`，来源 `networkName`。
- TU 自渲染：平台 `getAdMediaView(mediaContainer)`/icon/logo 与普通业务 root 组合，`renderAdContainer(container, root)` 后 `prepare(container, TUNativePrepareInfo)`。只将宿主图片回退注册为 mainImage，SDK 媒体 View 自己注册；无 SDK View 时用 `TUNativeImageView.setImage(url)` 回退媒体/icon/披露，披露 bitmap 用 ImageView。该精确 TU API 在 6.6.22.3 编译通过（`/private/tmp/native-tu-material-compile.log`）。收到收益/曝光的应用回调在主线程；这不证明 SDK 所有原始回调都在主线程。
- 来源矩阵：Pangle 本次有真实 Native 填充/收益但媒体视觉未通过；GMA Next-Gen、Meta、Mintegral、ADX 没有逐来源 Native 测试配置及成功证据。已声明 adapter 不等于后端启用清单。不可用全屏填充补齐这些行。

官方依据：[AdMob Native](https://developers.google.com/admob/android/next-gen/native)、[素材绑定](https://developers.google.com/admob/android/next-gen/native/advanced)、[ILRD](https://developers.google.com/admob/android/next-gen/impression-level-ad-revenue)、[TopOn Native API](https://help.toponad.net/docs/Native-Ad-koMu)、[官方自渲染示例](https://github.com/toponteam/TPN-Android-Demo/blob/main/app/src/main/java/com/test/ad/demo/SelfRenderViewUtil.java)。网页示例不能替代本项目锁定版本编译与设备证据。

R8 安装运行：2026-09-29 15:00 起进程 1462 启动成功；TopOn 模板返回明确尺寸错误，AdMob 随后完成 filled/Loaded/impression/0 micros USD paid，没有本进程缺类/缺成员崩溃。其原始事件保存在 `continued-runtime.log`。这不替代旧全屏反射竞价展示路径的独立设备验收。

## 剩余验收

- TopOn 模板确认比例/正常展示、同 placement 双实例成功展示与各来源完整素材/收益；媒体空白原因仍须定位。
- 带真实广告的 Fragment View 重建、实际 NavBackStackEntry 替换、旋转/分屏、断网/no_fill 后手动 retry 等完整宿主矩阵；已有确定性控制器测试不替代全部设备操作。
- 图片/视频、后台静默、落地页/商店长时间返回、可靠模板关闭以及三种自动开屏模式的设备时序；本轮 smoke 默认关闭自动开屏，不能据此声称其验收通过。
- 默认/两套业务 XML/TU 模板的长文案、大字体、深色、窄屏和无障碍视觉矩阵；两套业务 XML 已有可编译 smoke 示例（custom/compact），完整视觉矩阵未完成。
- 原在途全屏变更的未完成验收保持原状。对应 tasks 项继续未勾选，本变更未归档。

## 本轮收口

- 最终 `native-tu-final-checks.log` 构建退出码 0：92 JVM tests 通过，核心/Compose assemble 与 lint、smoke Debug/Release 通过；R8 警告如上保留。
- `openspec validate add-page-native-ads --type change --strict --no-interactive` 通过；`git diff --check` 通过。
- `tasks.md` 仅勾选已取得所需证据的项，混合实现/完整设备矩阵的任务保留未完成；变更未归档。

最终 R8 包也已安装并运行第二套业务 XML：进程 4091，15:14:38 filled/Loaded，15:14:49 impression/paid。Fragment/第二 XML 最终构建退出码 0，日志 `/private/tmp/native-host-checks.log`。

## 续作：导航归属、View 门控与素材规则

- 新增可运行 `mode=nav` 宿主，使用 Navigation Compose 2.8.7 的具体 `NavBackStackEntry`；导航到详情结束 Home 的 active 周期，Dialog 只暂停其 entry，返回与最终释放都有明确归属。Navigation 与 serialization 插件只加在 smoke app。
- `NativeComposeHostTest` 在同一 API 33 模拟器上 **4 tests passed**，日志 `build/reports/native/compose-nav-instrumentation.log`：等值请求/回调重组复用 View；owner/layout/request 替换释放旧实例；Destroyed 不可复活；Preview 在非 Activity Context 中不创建广告 View/执行工厂；真实 NavHost 的同位双卡片独立、Dialog 暂停保留、详情离页释放和返回新建；实际 View 未附着、owner 未 RESUMED、零宽、失焦、inactive、invisible 均不触发加载，全部资格齐全只启动一次，重复更新/测量不重复请求。
- 上述导航/身份测试使用 inactive 卡片检验 Android/Compose 生命周期，不等同所有 SDK 视频/来源验收；门控测试使用官方 AdMob Native 测试 ID，独立确认从 0 到 1 次 Loading。
- 真实导航宿主进程 7491 已收到官方 AdMob Native filled、Loaded、impression、0 micros USD paid；验证器显示无实现问题。真实 Dialog 的 UI `nav-dialog-latest.xml` 确认打开；16:06:38 卡片 Idle，关闭后 16:08:23 仅新增一条请求，16:08:26 Loaded，16:08:37 再次收到曝光/paid。恢复事件见 `nav-runtime.log`。完整实际详情/返回矩阵仍不以单次 UI 点击替代自动测试。
- 修复 TopOn 自渲染容器固定首次 width 的问题，改为 MATCH_PARENT 随宿主测量；模板继续保留明确请求宽高。窗口变化的完整来源视觉验收留到生产环境。
- 对照 [TopOn Native API](https://help.toponad.net/docs/Native-Ad-koMu) 与 [自渲染来源要求](https://help.toponad.net/cn/docs/native_ad_platform_notice)，返回描述却无 body 时报 `native_body_required`；返回 SDK 图标 View 却无 icon 时报 `native_icon_required`，避免丢弃 Meta 等来源的必需 SDK 素材。无素材仍允许省略，默认/两套 XML 保持兼容。
- 续作 JVM 回归仍为 **92 tests / 0 failures / 0 errors**；Debug/测试包构建、smoke lint 与 R8 构建通过。导航构建 `/private/tmp/native-nav-build.log`、lint `/private/tmp/native-nav-final-build.log`、宽度修正/JVM/R8 `/private/tmp/native-host-final.log`。R8 原有两条 Pangle warning 保留。
- 本轮已据证据完成任务 **2.3、7.2、7.3**，共 **25/39**。剩余模板、素材视觉、各来源填充/收益、视频和自动开屏时序按用户安排进入后续生产环境验收，不自动勾选或归档。

素材规则最终验证：核心 `NativeBindingTest` **5 tests passed**（包含新增的返回描述/SDK 图标不可漏绑检查），日志 `build/reports/native/material-binding-instrumentation.log`。最终 `:assembleDebugAndroidTest :r8-smoke-app:assembleRelease` 退出码 0，日志 `/private/tmp/native-material-final.log`。曾误用未限定模块的 `assembleDebugAndroidTest` 触发无测试的 Compose 模块缺少测试 manifest placeholder，随后改用核心绝对任务路径完成；不为该空测试目标改写库的生产 manifest。`git diff --check` 和 OpenSpec strict validation 通过。

最终 R8 APK 已以本机 debug key 签名并安装启动：进程 8912，`mode=nav`、`active=false`，UI 确认真实导航主页/Details/Dialog 可达，观察窗口 Native load request 为 0，未见该进程启动崩溃。证据 `build/reports/native/nav-r8-final.xml`、`nav-r8-final.log`。此项证明最终压缩包入口和 inactive 门控，不替代尚未执行的生产来源展示验收。

## verify 后修复：尺寸约束、订阅和手动机会

对应 `verify-report.md` 的 C1、C2、W1、W2。任务 2.2 完成，当前 **26/39**；任务 3.4 的尺寸实现与可控回归已补齐，但真实媒体比例、TopOn 模板展示及完整尺寸变化验收仍与 4.2/8.5 一起保留待验收。没有修改规格、SDK 版本或生产测试配置，没有提交、推送或归档。

- **C1**：使用当前 GMA 1.2.1 的 `MediaContent.hasVideoContent`；视频在注册前测量完整素材树，按实际 SDK 媒体 View 检查 120 × 120 dp 下限。`native_video_media_too_small` 明确失败。后续尺寸缩小时先隐藏再经已有失败路径释放，销毁移除布局监听；自定义 View 测量引起同步销毁时不再注册。媒体比例仍由 SDK MediaView 呈现，不能把本轮几何检查当成真实视频视觉验收。
- **C2**：保存请求所用的内容宽度；`onLayout` 以及挂载前比较外层宽度扣除 padding 的结果。变化使旧代次失效并复用现有释放/重取流程；相同布局不重载。加载回调先于新 layout 抵达也不能把旧尺寸模板挂上去。
- **W1**：Android runtime 中用可控 UMP 接口、真实 UmpConsentManager 快照及两平台状态，验证 Ads.nativeAvailability 与通知链；测试恢复自有全局字段，不初始化 SDK、不发请求。修复 CopyOnWrite 通知快照中已解除监听仍可能收到本次通知的边界：每个订阅用独立有效标志，解除后快照中的包装回调也不再交付。异常监听不阻断其他订阅。
- **W2**：`docs/native-integration.md` 补充 `showInterstitialWhenReady/isSceneValid` 示例，从既有 SDK Native 点击事件取消手动机会；页面暂停/离开/业务 Dialog 同样取消，返回不主动建立新机会，下一次独立业务操作才恢复。没有给广告素材加宿主 click handler，也没有新增调度器。

### 本轮执行证据

- API 33 `emulator-5560`：`NativeBindingTest` 7 项、`NativeDimensionsTest` 2 项、`NativeFragmentTest` 1 项，合计 **10 tests passed**。原始结果：`build/reports/native/dimensions-fix-instrumentation.log`。其中包含 60dp 自定义区域、窄默认卡片、120dp 区域扣 padding、后续缩小和移除监听，以及固定外层宽度时加载中/已显示模板重取、重复 layout 幂等、旧结果拒绝。
- 同一模拟器：`NativeReadinessTest` **4 tests passed**。原始结果：`build/reports/native/readiness-fix-instrumentation.log`。覆盖 UMP 等待/拒绝/允许/再次拒绝、所选平台失败/未配置、Bidding 另一平台仍初始化/成功/失败、重复解除、通知内解除后续监听和异常隔离。
- JVM XML 汇总 **92 tests / 0 failures / 0 errors**。核心/Compose Debug、lint、Android 测试 APK 和 smoke R8 构建通过。完整命令：`./gradlew :testDebugUnitTest :assembleDebug :lintDebug :assembleDebugAndroidTest :ads-compose:assembleDebug :ads-compose:lintDebug :r8-smoke-app:assembleRelease -PnativeSmoke=true --offline --console=plain`。退出码 0，日志 `/private/tmp/native-verify-fixes-final.log`，R8 原有两条 Pangle warning 保留。
- 初次新增就绪测试放在 JVM 中时因 Ads 的 Android Looper 初始化失败；随后移至 instrumentation，并将只读本地变量的模拟改为真实 Ads 状态读取。修正后 JVM 和设备测试均通过，没有启用 returnDefaultValues 来掩盖 Android 调用。初次失败日志 `/private/tmp/native-verify-fixes-build.log`，尺寸测试包日志 `/private/tmp/native-dimensions-build.log`。
- 本轮构建未注入 TopOn 凭据；没有用生产广告点击验证，也未重新验证最终 R8 包的实际广告展示。既有静态 Pangle 媒体空白、模板正常展示、逐来源收益及自动开屏设备时序仍未解决或未验收。

官方依据：[Google Native 尺寸要求](https://support.google.com/admob/answer/6329638)、[GMA Next-Gen MediaContent](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/nativead/MediaContent)。当前锁定版本 API 另由本轮编译验证。

协作记录：一个原生子代理参与 W1，按当前模型继承、high 推理请求；工具未返回可独立核验的实际 provider/model 元数据。主会话检查产物、修正订阅保护实现并执行全部构建与设备验收；子代理已关闭。

最终宿主回归：修改后的核心库与 smoke Debug/测试包构建成功（`/private/tmp/native-verify-host-build.log`）；API 33 上现有 `NativeComposeHostTest` **4 tests passed**，涵盖实际 View 资格、重复布局不重复加载、稳定身份/回调更新、owner/layout 替换、Preview 及真实导航生命周期。结果 `build/reports/native/verify-fixes-compose-instrumentation.log`。本轮设备测试合计 **18 项通过**；OpenSpec strict validation 与 `git diff --check` 通过。没有把这些宿主回归计为生产来源或 SDK 视频视觉验收。

## TopOn 媒体尺寸改进与静态空白复测（2026-09-29）

依据 [TopOn Native 素材 API](https://help.toponad.net/cn/docs/yuan-sheng-guang-gao) 和 [官方自渲染示例](https://github.com/toponteam/TPN-Android-Demo/blob/main/app/src/main/java/com/test/ad/demo/SelfRenderViewUtil.java)，用户授权继续处理尺寸与挂载差异。本轮没有认定这两处差异就是此前 Pangle Image 3 空白的根因。

- 默认媒体容器读取有效的大图宽高，按本容器实际内容宽度测量高度，处理横图、方图、竖图、padding 和后续宽度变化。使用 Android 原生测量流程，不新增加载代次或布局监听。无有效宽高时仍采用原 180dp；没有复制示例的屏幕宽度或把 600/1024 偏好当成实际素材比例。
- SDK 返回的媒体 View 一律先解除 parent 再用明确的 MATCH_PARENT 参数挂载，修复 SDK 已自行挂载时原先跳过参数设置的分支。保持 MediaView 优先、SDK 媒体不重复绑定 mainImage、render→prepare 顺序；自定义容器高度仍由业务决定。
- 新增一项 Android 测量回归，验证无效素材尺寸回退、横图/方图/竖图、连续宽度变化及容器 padding 后 SDK 子 View 的实际大小。`NativeBindingTest` 8 项和 `NativeDimensionsTest` 2 项共 **10 项通过**，日志 `build/reports/native/media-ratio-instrumentation.log`。JVM **92 tests / 0 failures / 0 errors**；Debug 与测试包构建通过，日志 `/private/tmp/native-media-ratio-build.log`。
- API 33 `emulator-5560` 使用先前获授权的本机测试配置，同 placement、同 SDK、同模拟器网络，未点击广告。调整前两次请求都返回模板，按已有规则拒绝未知比例；截图 `media-baseline.png`、`media-baseline-second.png`。调整后前两次自渲染都返回 Pangle 视频并正常显示/曝光，真实 paid 为 **2000 micros USD**；第一张截图 `media-ratio-first.png` 可见 Pangle Test Video 3 画面。
- 临时诊断只读取公开素材 API 与标准 Android View 属性，不读取 SDK 私有字段、不下载素材 URL。两个视频对象的 `getAdType()` 为 `0`、大图和视频宽高均为 `-1`，SDK 提供 `PAGVideoMediaView`，宿主与 SDK 媒体均为 976×540px。说明这个来源/版本可能不提供尺寸，不能据本轮自适应实现承诺它会改变这些创意的大小。记录为 `build/reports/native/media-comparison-events.log`；临时探针已从最终源码移除。

第三次调整后请求（进程 15030，17:16:26）返回 **Pangle Test Image 3**，空白复现；截图 `build/reports/native/media-ratio-static.png`。3 秒后的标准 View 探针显示：媒体容器、SDK FrameLayout 和其内部 ImageView 都是 **976×540px**、`shown=true`、`alpha=1`，但 ImageView 的 **drawable=null**。大图/视频宽高同样均为 -1。这证明本次尺寸与挂载调整没有消除该创意空白；“内部图片未取得可绘制内容”是观察结果，下载、解码或 SDK 填充失败的具体原因仍未确定。按本进程日志检索没有找到直接对应的图片下载异常，不能据此排除网络问题。

当前依然 **26/39**；3.3、3.4、4.2、8.5、8.6 等完整展示验收项未整体勾选。视频显示和真实收益不能替代静态媒体验收。

### 独立 SDK 对照与最终收口

在同一 smoke 进程环境、同 placement、同 SDK 和网络下，临时绕过 AdsNativeView、NativeCardController、TopOnNativeProvider，直接使用 TUNative → getNativeAd → 素材绑定 → renderAdContainer → prepare。素材树由普通 Android LinearLayout/FrameLayout 构成；SDK MediaView 的解除 parent、Gravity.CENTER、显式高度，以及未提供尺寸时的屏幕内容宽度 × 600/1024，均按官方 SelfRenderViewUtil 的媒体分支实现。本项是移植官方相关路径的最小对照，**没有构建运行整个官方 Demo APK**。

- 第一次直接 SDK 请求返回模板；第二次（进程 16999，17:27:03）返回 **Pangle Test Image 1**，同样空白。3 秒和 15 秒后，媒体区、SDK View 和 ImageView 均为可见的 **1050×615px**，ImageView 仍为 **drawable=null**。paid 为 Pangle / 0.002 USD。截图 `build/reports/native/media-official.png`；日志 `media-comparison-events.log`；最小对照源码留档 `OfficialNativeProbe.kt.txt`（均在同一报告目录）。Image 1 与库路径 Image 3 不是同一个广告对象/创意，不能称为同素材 A/B。
- 该对照说明静态空白也出现在绕过本库控制器、使用官方媒体尺寸算法的直接 SDK 路径；固定 180dp 或本库挂载逻辑不能充分解释现象。尚无证据区分 SDK 图片填充、下载、解码、测试素材本身等原因，也未拿到可对应的图片错误回调，因此不新增强制 URL 回退或擅自升级 SDK。
- 临时 View 探针、独立入口、对照源码和仅供对照的 debugCompileOnly 声明均从生产源码移除；源码副本只留在被忽略的本地报告目录。最终保留默认媒体测量、统一挂载、一个测量回归及接入文档改动。
- 最终源码的 `:lintDebug :r8-smoke-app:assembleRelease` 通过，日志 `/private/tmp/native-media-final-build.log`，仅保留此前两条 Pangle R8 warning。该 Release 在临时独立对照代码加入前构建，含最终媒体实现且无探针；使用本机 debug key 签名为 `/private/tmp/native-media-final-release.apk`，签名校验通过。最终源码还原后与该构建的生产改动一致。

后续排查所需最小证据已具备：TopOn 6.6.22.3、Pangle adapter 8.1.0.3.1.0、API 33、两种尺寸下的静态空白截图、ImageView Drawable 状态、真实收益及独立 SDK 复现。静态视觉仍判定未通过，可据此继续核查同创意的图片加载错误或向平台支持定位；本轮未发送外部消息。

最终 R8 包已安装到 emulator-5560，进程 17689 在 17:28:03 完成 AdMob 默认 Native 的 filled/Loaded，17:28:07 收到 impression 和真实 0 micros USD paid；新的 XML 媒体容器可在压缩包中正常实例化。截图 `media-final-r8.png`、本进程日志 `media-final-r8-process.log`，事件同见 `media-comparison-events.log`。不将 AdMob 回归记为 Pangle 静态通过。OpenSpec strict validation 与 diff/本轮新增文件空白检查均通过。

## HTTP 明文策略单变量验证（2026-09-29，18:00–18:07）

用户授权按交接建议验证。结论：**仅在 smoke Debug 允许 HTTP 明文流量未解决本次 Pangle 静态空白**，不保留全局放开配置，不将其记为修复。任务仍为 **26/39**，静态视觉验收未通过。

- 设备实时确认是 `emulator-5560` / API 33；包名仍为 `com.cashcraft.ads.mediation.smoke.native`。两组都使用 Debug、同一份本机测试配置、同 SDK/广告位及现有默认媒体绑定；未修改库代码、Release/生产网络策略或点击广告。
- 对照仅在 smoke 的 `src/debug/AndroidManifest.xml` 引用临时网络配置，其唯一规则是 `<base-config cleartextTrafficPermitted="true" />`；未添加用户 CA 信任。进程内使用公开 `NetworkSecurityPolicy.isCleartextTrafficPermitted` 断言 false/true；允许组日志还确认实际加载了该网络配置。观测通过临时 androidTest 遍历标准 Android View 属性，不读取 SDK 私有字段。

| 组别 | 实测策略与静态创意 | 媒体与视觉结果 | 证据（相对 `build/reports/native/`） |
| --- | --- | --- | --- |
| 禁止 HTTP，进程 20303 | `cleartext=false`；Pangle Test Image 2；18:03:37 Loaded | 两次采样均为 ImageView 976×540、shown=true、alpha=1、drawable=null；截图空白 | `cleartext-denied-2-process.log`、`cleartext-denied-2-test.log`、`cleartext-denied-2-15.png` |
| 允许 HTTP，进程 21675 | `cleartext=true`；Pangle Test Image 2；18:07:01 Loaded | 两次采样同样为 ImageView 976×540、shown=true、alpha=1、drawable=null；截图空白 | `cleartext-allowed-2-process.log`、`cleartext-allowed-2-test.log`、`cleartext-allowed-2-15.png` |

探针采样标签为 t=3、t=15；第二次采样还包含前一次截图耗时，实际日志分别在 Loaded 后约 16 秒和 17 秒。两组都通过“策略符合预期且广告 Loaded”的 instrumentation 断言，**该测试通过不代表图片渲染通过**。创意名称相同，但广告对象/请求不同，未取得素材 URL 或内容哈希，不能声称是同素材严格 A/B；未清除应用数据或 SDK 素材缓存，因此也不能排除缓存影响。

附带轮次：禁止组第一次返回 Pangle Test Video 1；允许组第一次约 59 秒后返回 Pangle Test Video 2，超过探针原 45 秒等待上限，instrumentation 因等待超时失败。它不是静态图片失败证据。随后仅将探针等待上限延长至 90 秒，第二次允许组正常完成，返回上述静态创意。

两份静态进程日志中未找到对应的明文拦截、域名解析、TLS 或图片解码错误。允许组只记录了网络配置生效信息；这不能排除下载、解码或 SDK 填充问题，也不能据此认定 SDK 内部缺陷。此次证据只支持“单独放开明文未消除空白”，不支持推广为生产修复或按域名收敛。后续若继续调查，应获取素材协议/响应及下载、解码或填充失败的直接证据。

基线、允许组和清理后的 Debug/测试包构建均成功，日志分别为 `/private/tmp/native-cleartext-baseline-build.log`、`/private/tmp/native-cleartext-allowed-build.log`、`/private/tmp/native-cleartext-probe-build.log`、`/private/tmp/native-cleartext-restored-build.log`。临时 Debug Manifest、网络 XML 与 androidTest 探针已移除；复现源码、运行脚本、配置副本及摘要保留在被忽略的 `build/reports/native/cleartext-experiment/`。本轮仅向验证记录追加结论，不修改任务完成状态，不重跑未受影响的全量回归。

清理后已重新安装 Debug 与正常测试包至 emulator-5560，两次安装均成功；最终目标 APK 的全部 ZIP 条目与本轮禁止明文的基线包逐项相同，合并 Manifest 无 `networkSecurityConfig` / `usesCleartextTraffic`。`git diff --check` 通过。设备当前安装的是恢复默认策略的 Debug 包，非此前的 Release 包；没有启动额外广告复测。

## 三星真机对照（2026-09-29，18:11–18:14）

用户授权在三星设备验证网络因素。实时识别为 Samsung SM-S721U1，Android 15 / API 35，序列号 `R58N341CK5Z`；此前未安装该独立 Native smoke 包。本轮只操作该三星设备，没有操作两个模拟器或改变 Wi-Fi、蜂窝、VPN、代理、明文策略。

- 安装与上轮禁止明文基线相同的 Debug 宿主 APK，配置、SDK、广告位、默认布局及媒体绑定保持不变；临时复用只读取标准 Android View 的 instrumentation 探针，等待上限 90 秒。
- 4 个独立测试进程（23617、25250、26077、27938）均实测 `cleartext=false`；4 次均返回 `Failed(reason=native_template_size_unknown, errorCode=null)`。这是 SDK 返回模板而宿主没有已确认比例时的现有拒绝路径，**未取得可对比的 Pangle 自渲染静态创意，也未观察到原先静态 ImageView drawable=null 的路径**。不能把页面没有广告当成原静态空白复现，也不能据此断言三星网络正常或异常。
- 4 次 instrumentation 均因“必须 Loaded”的断言失败，原因是上述模板拒绝；并非图片下载超时。没有猜测模板比例、切换广告位或绕过模板规则来制造视觉通过。
- 网络快照显示系统默认网络 103 为 Wi-Fi，另外有 Wi-Fi/VPN 网络 102；截图状态栏也有 VPN 标识。仅凭系统快照未确认该应用实际是否走 VPN，不能声称已取得独立或直连网络对照；设备/API 与模拟器也不同，无法独立归因网络。
- 原始日志及截图：`build/reports/native/samsung-native-{1,2,3,4}-process.log`、对应 `-test.log`、`-3.png`、`-15.png`。网络快照 `samsung-connectivity-before.txt` 只保存在忽略的本地证据目录，不将其中网络标识写入仓库。

本轮结论：**三星验证因连续返回模板而未覆盖目标静态路径，网络假设仍未确定**。后续有效对照需要先在三星取得同类静态自渲染创意，再记录应用实际网络路径；此轮不更新验收勾选，仍为 26/39。临时探针源码已移除，业务源码与网络配置未修改。探针构建日志 `/private/tmp/native-samsung-probe-build.log`，清理构建日志 `/private/tmp/native-samsung-restored-build.log`。

## 三星填充来源与应用网络定位（2026-09-29，18:17）

继续调查时仅为 smoke Debug 临时启用 SDK 日志，并通过公开 Android ConnectivityManager API 在应用进程内记录网络状态；媒体代码、广告位和明文策略未变。

- 进程 29657 的应用内快照在请求前与返回后均为 `network=102`、`bound=null`、`wifi=true`、`vpn=true`、`cellular=false`、`validated=true`、`proxy=true`。因此可以确认本轮应用的默认网络包含 VPN 且配置了代理，不能把前轮系统默认 Wi-Fi 快照当成该应用直连证据；尚未抓取单个 SDK socket 路由。
- 按本次 Native placement 过滤 SDK 公开调试日志：Google Ad Manager NO_FILL 后，瀑布位置 4 的 **AdMob** 请求成功，其模板配置 `render_type=2`，随后宿主返回 `native_template_size_unknown`。后续缓存填充也是 AdMob。该次 Native 日志未出现 Pangle 请求。确认此次模板来自 AdMob，而非已确认的 Pangle 模板；前四轮没有来源日志，不能逐次追认。
- 其他未集成广告源的 Adapter 缺失属于当前测试瀑布的旁支结果，不为本次 Pangle 排查扩大依赖集。原始 SDK 日志只存忽略目录并设为 0600；对外结论使用白名单字段摘要 `build/reports/native/samsung-network-summary.json`，不引用 SDK 配置内容。

官方 [TopOn Android 调试文档](https://help.toponad.net/docs/How-to-test-ads) 提供 `TUSDK.setDebuggerConfig` / `TUDebuggerConfig.Builder(Pangle_NETWORK)`，可对指定测试设备限定 Pangle 测试源。该模式使用 TopOn 预置源，不能描述为原线上瀑布不变的 A/B。准备的无设备标识代码见本地忽略目录 `build/reports/native/samsung-network-experiment/PangleDebugConfig.kt.txt`，尚未运行或验证编译。

启用该模式的命令被自动审批在执行前拒绝，理由是需读取三星 GAID、注入 Debug 包并通过调试请求发送至 TopOn/广告服务，现有授权未明确覆盖该标识传输。没有通过替代方式执行；已向用户请求本次设备标识传输的明确授权。临时 SDK 日志开关与网络探针源码已恢复/移除，清理构建成功；日志 `/private/tmp/native-samsung-network-restored-build.log`。静态媒体验证仍未完成，任务保持 26/39。

### 用户授权后的 Pangle 定向验证：三星静态显示成功（18:24）

用户随后明确回复“允许本次测试使用 GAID”。据此使用 [TopOn 官方调试 API](https://help.toponad.net/docs/How-to-test-ads) 在初始化前对该三星测试设备临时限定 Pangle；使用 [官方平台 ID 表](https://help.toponad.net/docs/Detailed-parameters-of-network) 中的 Pangle ID **50**。设备标识仅由本地 0600 临时 properties 注入，不写进源码或本记录。第一次编译因文档示例常量的所属类型未确认而失败，改为官方 ID 50 后编译通过；没有反编译 SDK 或读取私有字段。

- SDK 日志确认此次 Native 请求来源为 **Pangle / networkFirmId=50 / render_type=1**，不是此前的 AdMob 模板。进程 32590 在 18:24:43 Loaded，随后真实 impression；返回 **Pangle Test Image 1**。
- `cleartext=false`；应用内网络仍为 `network=102`、Wi-Fi + VPN、proxy=true、validated=true、bound=null。没有切换 VPN/网络或放开 HTTP。
- Loaded 后约 3 秒与 15 秒，两次标准 View 观测均为主图 ImageView **980×506、shown=true、alpha=1、drawable=BitmapDrawable**；图标 ImageView **135×135、drawable=BitmapDrawable**。截图已人工查看，Pangle 红色静态主图与图标均正常显示。**该设备/测试创意的静态显示通过**；本轮未点击广告。
- 原始 Native 请求中先有一次 Pangle `40060 / Appid is not registered on pangle media platform`，随后 `loadType=8` 请求成功并展示，之后 `loadType=9` 缓存请求成功。不能删去先前错误，也不能把它当成最终未填充；本轮不推断这些内部 loadType 的未公开含义。
- instrumentation **1 test passed**，断言仅验证禁止明文策略与 Loaded；静态成功另外由 Drawable 日志和截图支持。证据：`build/reports/native/samsung-pangle-1-process.log`、`samsung-pangle-1-process.summary.json`、`samsung-pangle-1-test.log`、`samsung-pangle-1-3.png`、`samsung-pangle-1-15.png`。

结论边界：现有 SDK 和当前库媒体绑定在三星该环境下能够正常显示 Pangle 静态图，故静态渲染并非普遍失效。模拟器此前空白与网络/设备环境有关的假设仍可能成立，但此次同时改变设备/API 和调试源选择，未取得完全相同素材请求，**不能确认网络是根因，也不能宣布模拟器空白已修复**。前轮三星持续模板的直接阻碍，本轮已定位到 AdMob 填充并通过官方 Pangle 定向测试绕开；该手段仅用于诊断，不成为生产配置。

临时 GAID 配置文件已删除，相关本轮日志中的该标识已脱敏。Pangle 限定设置、额外 compileOnly、BuildConfig 标识字段、日志开关与 androidTest 探针均移除，原源码从本轮备份精确恢复。复现代码（不含标识值）留在本地忽略的 `samsung-network-experiment/`。本次单创意通过不足以完成跨来源、模板与其他视觉矩阵，OpenSpec 仍为 26/39，不整体勾选 3.3/3.4/4.2 等混合验收项。

收尾：恢复后的 Debug/正常测试包构建与三星安装均成功；目标 APK 的全部 ZIP 条目与禁止明文实验前的基线逐项一致，Gradle 与 SmokeApplication 源码也与本轮备份一致，`git diff --check` 通过。最终设备保留原配置 smoke 包；不再运行定向请求。清理构建日志 `/private/tmp/native-samsung-pangle-restored-build.log`。

## Meta 定向验证准备（2026-09-29，待专项授权）

用户提出限定 Meta 来源验证。[TopOn Android 官方测试文档](https://help.toponad.net/docs/How-to-test-ads) 支持 Meta（Bidding）定向调试，同时列出测试设备需安装 Facebook、已登录可用账号的条件。

- 三星设备仍在线；公开包管理信息显示 `com.facebook.katana` 路径为 `Facebook_stub_preload`，versionCode=1、versionName=`stub (115.0.13)`，User 0 的 stopped=true / notLaunched=true；未发现 Facebook Lite。尚无完整 Facebook 已安装并登录的证据。已向用户确认，不读取账号数据，不自行安装或登录 Facebook。
- Meta network firm ID=1 的临时调试代码及标准 View/网络探针已经使用空 GAID 配置编译成功。空标识时不会启用定向模式；只启动过 inactive 的 smoke 页面以准备诊断，没有发出 Meta 定向请求。
- 读取三星 GAID 并为 Meta 生成临时配置的命令，被自动审批在执行前拒绝。理由：此前明确授权仅覆盖 Pangle，未明确覆盖将该标识用于 Meta。已请求本次 Meta 用途的明确授权；未绕过拒绝，`/private/tmp/ads-native-samsung-meta.properties` 未生成。
- 等待期间已停止 smoke 进程、恢复 Gradle/SmokeApplication、移除探针；无标识值的已编译代码副本保存在本地忽略目录 `build/reports/native/meta-experiment/`。准备与恢复构建日志分别为 `/private/tmp/native-meta-prepare-build.log`、`/private/tmp/native-meta-restored-build.log`。

本轮没有 Meta 填充或渲染结论，任务仍为 26/39。收到专项授权后可复用已准备代码执行有限请求；如设备仍仅有占位 Facebook 包，则必须保留该前置条件未满足的证据限制，不能把可能的无填充当成渲染缺陷。

### 用户授权后的 Meta 定向验证：默认自渲染显示成功（2026-09-29，18:42–18:43）

用户明确回复“允许”将三星 GAID 经 TopOn 官方调试 API 用于本次 Meta 测试。临时使用 `TUDebuggerConfig.Builder(1)`（Meta network firm ID）并在初始化前配置，设备标识仅由本地 0600 properties 注入。沿用原 SDK、Native 广告位及默认媒体绑定，没有修改生产渲染实现或网络设置。

- 目标仍为 Samsung SM-S721U1 / API 35；请求进程 8256。应用内实测 `cleartext=false`、`network=102`、Wi-Fi + VPN、proxy=true、validated=true、bound=null。
- SDK 的 Native 请求日志确认 **Meta / networkFirmId=1**，首次请求 `request_result=success`，18:42:46 Loaded，随后 `impression=success`。后续缓存请求也成功；无 AdMob/Pangle 混入该定向 Native 请求。
- 截图确认主图、图标、标题“An ad for Facebook”、Sponsored、正文“Your ad integration works. Woohoo!”、Install Now CTA、Ads served by Meta/广告标识均正常显示。没有点击广告。
- 两次采样中主媒体容器与 `com.facebook.ads.MediaView` 为 **980×502**，实际图片子 View **896×502、shown=true、alpha=1、drawable=BitmapDrawable**；图标 **135×135、drawable=BitmapDrawable**。SDK 其他占位 ImageView 的 drawable=null 不代表实际图片为空，按可见图片及截图判断。
- SDK 真实收益回调进入本库：`adNetwork=Meta`、`valueMicros=100761`、`currency=USD`。这是测试广告的实际回调值，不能作为生产收入结算依据。
- instrumentation **1 test passed**，覆盖禁止明文断言与 Loaded；视觉成功另由 View 观测和截图支持。证据：`build/reports/native/samsung-meta-1-process.log`、`samsung-meta-1-process.summary.json`、`samsung-meta-1-test.log`、`samsung-meta-1-3.png`、`samsung-meta-1-15.png`。原始日志中的本次 GAID 已脱敏，文件权限 0600。

关于 Facebook 前置条件：本轮复核仍是 `stub (115.0.13)`、notLaunched=true 的预装占位包，未检查账号或安装/登录 Facebook；**实际仍成功填充 Meta 测试广告**。因此前轮“可能因 Facebook 条件不足而不填充”的推测未在本轮成立；同时不能据一个调试广告推翻官方对常规填充的建议，或据此声称已验证正式流量、全部创意与视频。

本轮结论：三星当前网络与官方 Meta 定向测试配置下，默认 Native 自渲染的基本加载、显示、曝光及收益回调均通过。此结果不构成真实线上 Meta 填充率保证，不覆盖点击/返回、视频生命周期、模板、自定义布局和其他视觉矩阵，任务保持 26/39。临时 GAID 配置已删除，定向调用、BuildConfig 字段、额外 compileOnly、日志开关和探针源码均已撤销；准备日志 `/private/tmp/native-meta-authorized-build.log`，清理日志 `/private/tmp/native-meta-authorized-restored-build.log`。

Meta 收尾：恢复构建、两包安装及 `git diff --check` 均成功；Gradle/SmokeApplication 与本轮备份一致，最终目标 APK 全部 ZIP 条目与原禁止明文基线一致。三星已恢复原配置 smoke/测试包，未再启动定向广告请求。

## 三星 AdMob 生命周期自动验收（2026-09-29，18:58–19:02）

继续 `openspec-apply add-page-native-ads`。新增持久化 `NativeLiveLifecycleTest`，仅在 smoke 的 androidTest 中使用官方 AdMob Native ID；没有修改核心实现、SDK 版本、GAID 配置、网络策略或自动开屏开关。设备仍为三星 SM_S721U1 / Android 15 / API 35。没有点击广告或操作其他设备。

| 用例 | 实机断言与结果 |
| --- | --- |
| visible、父容器 GONE、Dialog、详情返回 | 首次加载 1 次，四次恢复后累计依次为 2、3、4、5；每次隐藏均 Idle 且实际子 View 数为 0。Loaded 上 retry、隐藏时多次 retry 不新增请求，恢复不重复加载。通过。 |
| 同 placement 双卡片 | 两个独立 request/response，各自 Loaded、平台容器不同；销毁第一张并尝试 retry/激活后仍 Destroyed/无子 View，第二张保持 Loaded、原容器、1 次加载。通过。 |
| 实际旋转 | 旧 Activity/卡片销毁并清空；新 Activity 的独立卡片只请求一次、正常 Loaded。旧卡片 retry/激活不复活；测试恢复原 requestedOrientation 并等待新宿主稳定。通过。 |
| 加载中离开 | 观察到 Loading 后销毁卡片并结束 Activity，等待 10 秒仍 Destroyed、无子 View、无 Loaded；本层一条请求以 cancelled 收口。通过。此窗口不证明底层请求已取消或必然收到了迟到对象。 |

测试修正历史保留：首轮旋转自身通过，但未等待方向恢复导致后续用例持有已销毁宿主，主动停止该轮；`samsung-live-lifecycle-first-test.log` 的 `Process crashed` 是本次 force-stop 的结果。第二轮 4 项中 3 项通过，旋转收尾因复用 ActivityMonitor 取到旧实例超时。为恢复方向单独创建 monitor 后，仅重跑旋转和紧随的双卡片，**2 tests passed**。因此当前是 **4 个独立用例均有成功证据**，不是声称最后一轮完整运行 4/4，也不将测试宿主同步问题记为广告库缺陷。

构建：`:r8-smoke-app:assembleDebugAndroidTest -PnativeSmoke=true -PnativeTestConfig=/private/tmp/ads-native-test.properties --offline --console=plain` 成功，日志 `/private/tmp/native-live-lifecycle-build.log`。安装到三星成功；执行方式已加入 `docs/native-integration.md`。

证据均在 `build/reports/native/`：

- `samsung-live-lifecycle-test.log` / `-process.log`：第二轮完整结果与事件，10 条 Native 请求对应 10 条唯一结果（9 filled、1 cancelled），9 次真实 impression/paid。
- `samsung-live-lifecycle-rotation-fix-test.log` / `-process.log`：最终两项复测，4 条请求对应 4 条唯一 filled 结果，2 次真实 impression/paid。其他 Loaded 未伪造曝光；两份 `-events-summary.json` 保存请求/结果配对断言摘要。
- `samsung-lifecycle-returned.png`：返回后的主图、图标、正文和 CTA 可见，AdMob native ad validator 显示 `No implementation issues found`。横屏 `samsung-lifecycle-rotation.png` 确认媒体仍可显示，但单屏未包含卡片全部内容，不能据此勾选横屏完整视觉矩阵。所有 paid 均为测试广告 0 micros USD。

剩余边界：本轮为 AdMob 默认 View 的加载和生命周期验证；Fragment 真广告、真实 NavBackStackEntry 广告切换、断网/no_fill 后 retry、视频后台静默、自动开屏/广告点击外跳及 TopOn 共享缓存、模板、其余来源仍需对应验收。8.3/8.4 等混合任务不整体勾选，保持 **26/39**，未归档。核心代码未变，不重复既有 92 项 JVM、lint 或 R8 构建。

收尾：OpenSpec strict validation、`git diff --check` 及新增测试文件空白检查通过。SmokeApplication 与定向测试前备份内容一致；保留新增测试包及源码，没有遗留定向网络/GAID 开关，没有提交或发布。

### TopOn 第一批续验：模板边界与生命周期（2026-09-29，19:18–19:33）

使用现有受保护 `nativeTestConfig`，没有记录 placement/密钥值，没有启用新的来源定向、改网络设置或点击广告。目标为已授权三星 SM_S721U1 / Android 15 / API 35 和 `emulator-5560` / Android 13 / API 33。详细尝试及证据路径见 [`topon-phase1-observations.md`](../../../build/reports/native/topon-phase1-observations.md)。

- 两台设备的当前 `native` TopOn placement 均返回已填充模板；由于配置没有平台确认的宽高比，卡片明确进入 `native_template_size_unknown`，不调用模板渲染，也不能记录模板实测尺寸。该失败是缺输入的安全拒绝，不是 no-fill。
- emulator-5560 上有界 retry 得到 Pangle Test Video 2 和 Video 3。Video 3 实际进入 Loaded、paid 2000 micros USD、impression，截图 `topon-native-smoke-pangle-video-retry.png` 可见视频内容和 CTA；Video 2 的媒体区为黑色，截图保留为视觉失败样本。应用层状态/事件回调运行在主线程。实际代码的自渲染调用顺序为 `renderAdContainer` → `prepare`；实时日志没有对这两个 SDK 调用逐条埋点。
- 对已 Loaded 卡片执行 `visible=false` 后状态为 Idle，UI 层级中不再有广告素材；恢复只启动一条新请求。Home 后视频卡片也转为 Idle；返回同一 smoke Activity 启动一条新请求，但该次返回模板并因比例缺失失败。没有音频采样，因此未证明后台视频静音。
- 同 placement 双卡片产生两条独立请求：第一张得到模板后因比例未知失败，第二张 `native_ad_missing_after_load`。这与此前 TopOn 共享库存观察一致；不声明双卡片可同时展示或平台内部缓存已被取消。
- Native 点击/外跳与自动开屏返回抑制未做实机验收：smoke 配置的 `autoShowAppOpen=false`，即使点击测试创意也无法在此宿主观察自动开屏抑制结果；本轮没有做无法验证目标的外部跳转，也没有点击广告。

因此 1.3、1.5、1.6 继续未勾选，整体仍为 26/39。缺模板比例、后台音频证据和启用自动开屏的测试宿主分别限制对应结论；不把自渲染 Loaded/paid 外推为模板、后台视频或自动开屏验收通过。

### TopOn compact 自定义 XML 续验（2026-09-29，19:39–19:41）

在 emulator-5560 使用 `mode=view, layout=compact`（测试页标记为 custom）验证另一条渲染路径。首次返回的 TopOn 模板被拒绝为 `unsupported_native_render_mode`；一次有界 retry 得到 Pangle Test Video 2，进入 Loaded、paid 2000 micros USD 与 impression，事件回调仍在主线程。截图 [`topon-native-smoke-compact-pangle.png`](../../../build/reports/native/topon-native-smoke-compact-pangle.png) 显示标题、广告标识及 CTA 已绑定，但视频媒体区为黑色，故只通过基本绑定/回调链路，视觉验收失败。

已加载时将可见性切为 false 后，卡片转为 Idle；无障碍节点数从 50 降至 23，广告素材不再出现在层级中。脱敏日志为 [`topon-phase2-compact-visibility.log`](../../../build/reports/native/topon-phase2-compact-visibility.log)。本 compact 用例未再恢复显示；恢复后单请求的证据来自前述 phase 1 普通布局用例。测试应用已停止。该结果不补足未知模板比例、后台音频测量、TopOn 双卡片库存/清理边界或自动开屏外跳抑制，1.3/1.5/1.6 仍未完成，整体保持 26/39。

## TopOn 标准尺寸候选与分路验收（2026-09-29，20:21–20:24）

用户批准借鉴旧 HealthTracker 的标准布局/尺寸策略，保留新库主体及原有验收要求。本次只改 Native smoke 参数入口、添加一份 instrumentation 测试和更新文档；核心请求、绑定、实例隔离、收益、销毁以及 `autoShowAppOpen=false` 不变。

### 策略与边界

- 新增显式 `templatePreset=healthtracker-4x1`，来源是旧提交 `a856c1fe` 的固定 4:1，**不是当前后台已确认比例**。仅 Native smoke Debug / TopOn / 默认布局允许使用；与显式 `templateRatio` 互斥，覆盖构建测试配置中的比例，锁定布局切换。无参数时仍用原有配置并保留 `native_template_size_unknown`，Release 源码路径拒绝诊断预设（本轮未运行 Release 验收）。
- 复用核心库现有“内容宽度→请求尺寸→相同容器尺寸”路径，没有改成屏幕宽度，没有将 4:1 设为公共 API 默认值。与官方要求的尺寸一致原则对应：[TopOn Native](https://help.toponad.net/docs/Native-Ad-koMu)。
- `layout=custom` 单独验证自渲染，`compact` 单列。入口不强制指定瀑布来源或实际填充类型，混合广告位未返回目标类型时如实记为未覆盖；不自动重试、不改用另一平台、不把模板塞入自定义布局。

### 构建与设备结果

目标为已授权 Samsung SM_S721U1 / Android 15 / API 35，未操作两个模拟器。现有受保护配置只作为构建输入；未修改凭据、GAID 定向、SDK 版本或网络策略，没有点击广告。

- `:r8-smoke-app:assembleDebug :r8-smoke-app:assembleDebugAndroidTest :r8-smoke-app:lintDebug`（`-PnativeSmoke=true -PnativeTestConfig=/private/tmp/ads-native-test.properties --offline --console=plain`）退出码 0，日志 `/private/tmp/native-topon-sizing-build.log`。两包安装成功；核心/JVM 未改，不重复既有全量检查。
- `NativeSmokeSizingTest#testSizingOptionsKeepHistoricalCandidateExplicit`：**1 test passed**。检查原配置和显式比例、历史候选必须显式选择、自定义/compact/AdMob/双参数/未知预设拒绝，compact 显示正确布局名称。日志 `build/reports/native/topon-sizing-options-test.log`。
- `NativeSmokeSizingTest#testLiveTopOnTemplateCandidateDimensions`：**1 test passed**，6.862 秒。进程 23045，20:21:50 一次 Native 请求，20:21:53 filled/Loaded；平台 Native paid 和 impression 均进入本库，`adNetwork=Admob, valueMicros=1000, currency=USD`。这是测试广告回调，不是生产收入保证。应用层回调在主线程。
- 20:21:56 测量内容宽度 **1048px**、期望高度 **262px**，平台容器 LayoutParams 与实际测量均为 **1048×262**。公开 View 树包含 `TUNativeAdView → Google NativeAdView → ThirdPartyNativeTemplateView`，确认该样本为平台模板。单次 Loading 断言通过；随后设置 invisible 得到 Idle/childCount=0。本测试不覆盖恢复后的新填充、音频或 SDK 缓存清理。
- 人工查看 `samsung-topon-template-candidate-3s.png`：模板图像/图标、广告标识、安装 CTA 和平台关闭按钮可见，标题以省略号显示。**此样本基本模板展示与几何路径已验证，长文案和完整视觉矩阵未通过/未完成**。未点击 CTA 或关闭按钮。instrumentation 直接激活卡片并接管状态回调，截图中的 smoke 按钮状态不是资格断言；实际状态、加载次数和尺寸以测试及日志为准。
- 20:24:40 另起普通 `layout=custom`，不传任何候选参数，只请求一次。进程 25634 在 20:24:44 filled 后明确 `unsupported_native_render_mode`，没有静默换模板或产生循环请求。**此次没有取得自渲染样本**，不能据此宣称 Pangle 媒体空白/黑屏已修复。日志 `topon-sizing-self-render-events.log`，截图 `samsung-topon-sizing-self-render.png`。应用已停止。

### 证据与剩余项

新增截图和脱敏日志位于被忽略的 `build/reports/native/`：`topon-sizing-template-test.log`、`topon-sizing-template-events.log`、`samsung-topon-template-candidate-terminal.png`、`samsung-topon-template-candidate-3s.png`，以及上述参数/自渲染结果。临时原始捕获文件已删除；修改前的 8 份相关文件和 SHA-256 存在 `topon-sizing-baseline/`，保留共享 dirty worktree 的其他改动。

此次已解除**显式历史候选测试路径**的未知尺寸阻塞，并补齐一个真实模板样本；仍没有后台比例确认、SDK render/prepare 逐次时序埋点、完整尺寸/关闭/视频/来源矩阵。1.3、1.5、1.6、3.3、3.4、4.2、4.4、6.2、8.2–8.6 保持未勾选，**26/39**。接入文档给出两类入口和逐方法测试命令，原核心规格不降级。

## HealthTracker 宿主 AdMob 默认卡验证（2026-09-30；不计入旧布局验收）

在 `emulator-5560` / API 33 的 HealthTracker Debug 宿主中，临时恢复闹钟管理页底部容器并挂载当前工作树的 `AdsNativeView`，使用 AdMob 官方 Native 测试 ID。宿主原先的 composite build 指向另一份仅含 Banner API 的库，测试期间临时切换路径并跳过首页 Banner；为避开全新用户引导，还临时导出闹钟管理 Activity。以上四处宿主源码改动在测试后从逐文件备份还原，宿主 `git status --short` 为空；原配置 Debug 包重新构建并安装到同一模拟器。未改实体设备或生产配置。

- 宿主 `:app:assembleDebug --offline` 成功，最终 CTA 复测构建记录 `/private/tmp/healthtracker-native-host-build-cta.log`。安装后的首个卡片日志为 `Loading → Loaded`；实际 UI 树有媒体、图标、标题、正文、广告标识及 CTA。AdMob 测试验证器显示 `No implementation issues found`。
- 宿主浅色主题使默认 CTA 白底白字：UI 树有 `INSTALL`，原截图中不可辨。默认 XML 给 CTA 增加主题正文色后，再次构建并安装；[修复前](../../../build/reports/native/healthtracker-admob-cta-before.png) 与 [修复后](../../../build/reports/native/healthtracker-admob-cta-after.png) 可见 `INSTALL` 已有对比度。此次只验证该宿主浅色主题，没有宣称全部主题通过。
- 页面退出记录 `Idle → Destroyed`。一次重新进入时 SDK 测试广告落地页取得焦点，卡片转 `Idle`；返回闹钟管理页后记录 `Loading → Loaded`，素材及 CTA 再次挂载。这是官方测试创意，不代表生产广告点击链路或自动开屏抑制已验收。

本次宿主验证覆盖 AdMob 默认布局的基本填充、真实渲染、CTA 对比度和一次页面返回；未捕获可归因的 impression/paid 原始日志，也未测试 TopOn、模板比例、视频后台音频或完整视觉矩阵。原有 **26/39** 状态不变。

## HealthTracker 历史 Native 布局与页面场景验证（2026-09-30）

针对用户指出的布局错误，重新从 HealthTracker 删除广告前的提交 `18a07b04^` 找回原 `monetize` 模块的 `NativeAdStyle` 对应资源：`dh_layout_native_ads.xml`、`dh_layout_native_ad_card3.xml`、`dh_layout_native_ad_card5.xml`、`dh_layout_native_ad_card7.xml`、`dh_layout_native_ad_card8.xml`。五份 XML、所依赖的八份 drawable 和药物列表广告行布局恢复到宿主 `app/src/main/res`；移除先前临时拼出的 `dh_native_validation_compact.xml`。仅将历史 XML 的最外层 `NativeAdView` 改成 SDK 外层可包裹的 `FrameLayout`、图标/媒体位改成供当前 SDK 填充的空容器，并补 AdChoices 容器。布局视觉尺寸、颜色、素材顺序和旧资源 ID 保持历史来源。

按旧 `git grep NativeAdStyle` 映射恢复当前仍存在的 18 个展示位置：STANDARD 5 处（四类新记录及心率测量）、CARD_3 1 处（退出弹窗）、CARD_5 5 处（四类详情及确认弹窗）、CARD_7 5 处（闹钟管理/编辑、饮水完成、语言、个人设置）、CARD_8 2 处（Tracker 中段、用药提醒列表）。旧 Insights 及卸载挽留页面已不在当前宿主，不计入可恢复的现存业务页面。所有入口只在 Debug 环境加载 AdMob 官方 Native 测试 ID；旧版 CARD_8 等无媒体位，因此库侧允许无媒体自渲染，同时仍拒绝无法展示的媒体创意和 TopOn 缺媒体位路径。

在 `emulator-5560` / API 33 的真实 HealthTracker 页面完成五种布局的加载和可见渲染：血糖新记录 STANDARD、退出弹窗 CARD_3、血糖结果 CARD_5、闹钟管理和 Compose 闹钟编辑 CARD_7、用药列表及 Tracker 中段 CARD_8。以上共七类实际页面场景；各场景 AdMob 测试验证器显示 `No implementation issues found`，实际素材的标题、广告标识、图标/正文及 CTA 可见。CARD_3 首次请求 `NO_FILL`，重试后 `Loaded`；最终 Tracker 缩小空容器后也重新 `Loaded` 并可见。各页带验证器的截图、宿主改动补丁和构建日志位于 `build/reports/native/healthtracker-historical/`，其中 [CARD_3 退出弹窗](../../../build/reports/native/healthtracker-historical/healthtracker-historical-card3-exit-validator.png)、[CARD_7 闹钟管理](../../../build/reports/native/healthtracker-historical/healthtracker-historical-card7-alarm-validator.png) 和 [CARD_8 Tracker 最终画面](../../../build/reports/native/healthtracker-historical/healthtracker-historical-card8-tracker-final-validator.png) 可直接检查。宿主 `:app:assembleDebug --offline` 最终构建通过；库侧 `NativeBindingTest#testHistoricalTextOnlyLayoutDoesNotRequireMediaSlot` 在模拟器 1/1 通过。

证据边界：五种布局有设备样本，**不等于 18 个位置逐一验收**；Language、Profile、HydrateComplete、心率及其余指标、ConfirmDialog 未用历史布局逐页实测。仅验证 AdMob 官方测试广告，不推断 TopOn、正式广告、视频、收益回调或全视觉矩阵。前节的默认卡截图不能作为历史布局证据。宿主当前保留验证修改供复查：composite build 临时指向本 Native 工作树，因该工作树尚无原宿主使用的 Banner API，首页 Banner 调用暂时关闭；此状态不能直接作为生产集成。完整宿主补丁单独保存以便对照/恢复，原有 **26/39** 状态不变。

## HealthTracker 历史布局的 TopOn Native 宿主验证（2026-09-30）

应用户要求，在保留上述五份历史 XML 和真实页面入口的 HealthTracker Debug 宿主中，将验证请求切至现有 TopOn Native 测试广告位。宿主 `:app:assembleDebug --offline` 构建并安装到 `emulator-5560` / API 33 成功。没有将平台模板冒充旧 XML 渲染，也没有用导航过程中出现的其他全屏测试广告作为 Native 证据。TopOn Debug 切换补丁、构建日志、筛选后的 `HostNativeAd` 状态日志和截图保存在 `build/reports/native/healthtracker-topon-host/`。

| 历史布局 / 真实宿主场景 | TopOn 状态与画面 | 验收 |
|---|---|---|
| CARD_3 / 首页退出弹窗 | Pangle Test Video 2 自渲染返回 `Loaded`；标题、正文、广告标识、CTA 可见，但图标空白，视频区在加载后与稍后截图中均为黑色（[`card3-exit-later.png`](../../../build/reports/native/healthtracker-topon-host/card3-exit-later.png)） | **视觉未通过**；`Loaded` 不证明媒体展示正确 |
| CARD_8 / Tracker 中段 | `Loading → Failed(unsupported_native_render_mode)`；这次返回平台模板，不能嵌入历史自定义 XML（[`card8-tracker-failed.png`](../../../build/reports/native/healthtracker-topon-host/card8-tracker-failed.png)） | 未通过 |
| STANDARD / 血糖新记录 | `Loading → Failed(unsupported_native_render_mode)`，本次同样返回模板 | 未通过 |
| CARD_5 / 血糖结果 | `Loading → Failed(unsupported_native_render_mode)`，本次同样返回模板 | 未通过 |
| CARD_7 / 闹钟管理 | 收到自渲染素材，但 `Loading → Failed(native_media_slot_missing)`；历史 CARD_7 无媒体容器（[`card7-alarm-failed.png`](../../../build/reports/native/healthtracker-topon-host/card7-alarm-failed.png)） | 未通过 |

这是五种布局各一个真实页面样本，并非所有 18 个位置逐一实测；Compose 编辑弹窗、用药列表等未在本轮用 TopOn 逐页验证。旧版 TopOn 绑定代码曾省略媒体，但不能据此认定当前视频素材可安全省略；[TopOn 自渲染注意事项](https://help.toponad.net/cn/docs/native_ad_platform_notice)对部分来源明确要求媒体展示。当前库对模板与自定义 XML 的不兼容、无媒体容器等情况实行显式拒绝。本轮未改库的媒体规则，TopOn 历史布局验收保持**未通过**，既有 **26/39** 任务计数不变。宿主保留 TopOn Debug 验证切换供复查，仍不是可发布集成。

### CARD_3 媒体黑屏假设复测（2026-09-30）

在同一 `emulator-5560` 上使用已恢复的 HealthTracker `dh_layout_native_ad_card3.xml` 和首页真实退出弹窗。临时库侧探针记录 `getAdMediaView` 前、绑定后、布局后及两秒后的公开 View 类型、尺寸、挂载状态和视频事件；宿主 `:app:assembleDebug --offline` 成功，诊断 APK 安装成功。探针随后从库源码移除；无探针宿主包再次构建成功并重新安装到模拟器，保留应用数据。

- 首次返回 **Pangle Test Image 3**，重启后返回 **Pangle Test Image 2**。两者均 `Loaded`，标题、CTA 可见，媒体区域空白；截图为 [Image 3](../../../build/reports/native/healthtracker-topon-media-probe/card3-current.png)、[Image 2](../../../build/reports/native/healthtracker-topon-media-probe/card3-restart.png)。Image 3 的[过滤日志](../../../build/reports/native/healthtracker-topon-media-probe/card3-first.log)显示：取媒体时容器未测量，布局后容器、Pangle View 和内部 ImageView 均已挂载、可见且为 `888×384px`，两秒后不变。本次宿主探针没有读取 Drawable 或图片错误，空白的具体原因未确认；前述独立 SDK 对照中其他 Pangle 静态素材曾出现 `drawable=null`，不能直接等同于本次素材。
- 同一进程后续三次请求返回模板，历史自渲染 XML 按规则报 `unsupported_native_render_mode`，与媒体空白无关。
- 再次重启后返回 **Pangle Test Video 2**。`getAdMediaView` 返回 `PAGVideoMediaView`；取回时未测量，布局后它及媒体容器均已挂载、可见且为 `888×384px`，两秒后仍如此。初始[截图](../../../build/reports/native/healthtracker-topon-media-probe/card3-video.png)为黑底 Pangle 标识，稍后的[画面一](../../../build/reports/native/healthtracker-topon-media-probe/card3-video-later.png)和[画面二](../../../build/reports/native/healthtracker-topon-media-probe/card3-video-frame3.png)显示不同视频内容，证明本次视频在真实宿主布局中播放。采样日志中未见 TopOn 视频事件回调，不能据此反推播放器未启动。

结论：取媒体时 `0×0` 但随后正常测量，且同一 Video 2 在本次实际播放，因此“零尺寸、挂载冲突或宿主硬件加速必然导致 Video 2 黑屏”不成立；此前 Video 2 黑屏样本仍有效，原因未定位。图片素材返回非空 SDK View，现有仅对 `null` 的主图回退不会处理其空白。没有修改生产媒体绑定或来源配置，也未将 TopOn 历史布局验收改为通过。


### CARD_3 素材下载网络对照（2026-09-30）

在 `emulator-5560` / API 33 的真实 HealthTracker 首页退出弹窗和历史 CARD_3 XML 继续诊断。SDK 保持 TopOn 6.6.22.3 / Pangle 8.1.0.3 / adapter 8.1.0.3.1.0。本轮未改生产媒体绑定或历史 XML。

- **配置核对**：合并 Manifest 已开启硬件加速、包含 `org.apache.http.legacy(required=false)`，宿主网络配置已允许 HTTP；无需新增这些配置。
- **静态失败复现**：Image 1（PID 11837）与 Image 2（PID 12982）均 Loaded。绑定后 2/10/30 秒，内部主图 ImageView 为 888×384、attached/shown、alpha=1、hardware=true，但 drawable=null；图标 132×132 也无 Drawable。分别在 Loaded 后约45秒/36秒的截图仍为空白。见 [Image 1](../../../build/reports/native/healthtracker-topon-network-probe/attempt4-75s.png)、[Image 2](../../../build/reports/native/healthtracker-topon-network-probe/attempt5-75s.png)。
- **直接下载错误**：完成30秒原画面采样后，通过 SDK 公开 `getIconImageUrl()` 在同进程用 HttpURLConnection 下载：Image 1 抛 SSLHandshakeException(connection closed)；Image 2 指向 `p16-sign-sg.tiktokcdn.com`，抛 SocketTimeoutException(Read timed out)。这是独立探针的图标错误，不是已捕获的 SDK 主图加载错误；`getMainImageUrl()` 为空。
- **同 URL 受控对照**：PID 14860 中，Video 1 的同一图标 URL（SHA-256 `ab2ffe5f7a27c5a4929827d96c63efc2a8af3285abb66aaaf1fbe1309f5c49ce`）经模拟器默认路径读取超时；立即经已有桌面 HTTP 代理 `10.0.2.2:7890` 返回 HTTP 200 / image/png / 8763 bytes，解码为336×336 Bitmap。未禁用证书校验、未覆盖广告 View、未读取 SDK 私有字段。此对照直接确认该图标素材有效且下载结果受网络路径影响。
- **真实 SDK 代理复测**：临时将该测试模拟器全局代理设为上述代理。Video 2（PID 16023）图标在绑定后约2.280秒变为 BitmapDrawable，截图有红色 Pangle 图标和有效视频画面；恢复发生在30秒独立下载探针之前。见 [代理下 SDK 画面](../../../build/reports/native/healthtracker-topon-network-probe/proxy-host1-35s.png)。后续三轮均返回模板并被 unsupported_native_render_mode 拒绝，未取得代理下静态主图成功样本。
- **视频边界**：本轮默认网络下 Video 1、Video 2 也捕获有效画面，不能归纳成默认网络下一律视频黑屏；未测得精确首帧时间，原先间歇黑屏根因仍未确认。

结论：已确认模拟器默认网络路径存在广告图标 CDN 访问故障，并通过同URL代理下载与真实SDK图标恢复闭环验证；静态主图网络故障是更强的排查方向，但未取得主图URL/错误或代理下主图成功，不宣布全部媒体问题解决。任务仍为 **26/39**，不整体更新历史布局验收。日志、探针副本、脚本和详细报告见 `build/reports/native/healthtracker-topon-network-probe/`；原始进程日志0600，不外发。所有临时库探针已精确恢复，恢复后宿主 `:app:assembleDebug --offline` 构建成功。

收尾确认：模拟器全局代理及Android自动生成的拆分代理键均已恢复到实验前的缺省状态；无探针Debug包保留数据安装成功，git diff --check通过。本轮仅持久追加验证文档和本地证据，未保留生产源码修改。


### CARD_3 Pangle 静态主图成功样本（2026-09-30）

真实 HealthTracker `MainAct → ExitDialog`，emulator-5560 / API33，原历史 CARD_3 XML 与媒体绑定逻辑不变。临时官方 `TUSDK.setDebuggerConfig(..., TUDebuggerConfig.Builder(50).build())` 固定 Pangle 测试来源；模拟器临时通过 `10.0.2.2:7890` 使用现有桌面代理。GAID 仅应用内取得并传入官方测试 API，探针不输出标识。

- PID19236，10:35:21.404 Loading，10:35:23.193 Loaded；截图 `pangle-proxy1-5s.png` 明确显示 **Pangle Test Image 1** 静态主图及图标。
- 绑定后 +2.030s：SDK 内部主图 ImageView 为888×384，`BitmapDrawable` / intrinsic1200×628；SDK 图标为132×132，`BitmapDrawable` / intrinsic336×336。+10s/+30s 均维持正常，attached/shown/alpha1/hardware/globalVisible 正常。
- 本轮没有独立下载、替换图像或修改 SDK 媒体注册；补齐了“代理下真实宿主静态主图可显示”的证据。结合上轮同URL图标默认网络失败、代理成功，后续优先保证测试网络可达。此次同时固定测试来源，且主图URL未公开，不能表述为同素材单变量主图网络 A/B 或全部媒体问题解决。
- 官方 Pangle 参数仅列 native 默认样式0，无只返回图片的选项：[测试模式](https://help.toponad.net/docs/How-to-test-ads)、[参数表](https://help.toponad.net/docs/Debug-Mode-Parameter-List)。
- 两处临时源码均精确恢复；模拟器所有全局代理键恢复实验前缺省状态；恢复后 `:app:assembleDebug --offline` 成功（23s），无探针APK保留数据安装成功并停止应用。未保留生产代码变更；任务仍26/39。

证据：`build/reports/native/healthtracker-pangle-static/report.md`、`pangle-proxy1-media.log`、`pangle-proxy1-5s.png`，原始进程日志仅本地私有留存。


### 三星真机既有代理环境验证（2026-09-30）

用户指定已开启代理的三星 SM-S721U1 / API35。保留数据安装与模拟器恢复阶段相同的无探针 Debug APK；不修改手机现有网络、不增加定向测试配置、不修改源码。真实 `MainAct → ExitDialog` / 历史 CARD_3 已显示 **Pangle Test Image 2**：静态主图、红色图标、标题、描述、CTA 正常。PID3221，10:42:25.958 Loading、10:42:25.980 Loaded；可能涉及缓存，不以22ms声称新网络下载耗时。画面保留供用户检查。

同进程较早另有一次 `unsupported_native_render_mode`，属于模板支持边界，不能作为主图下载失败。三星跨设备成功进一步支持原布局/绑定有效；结合此前同URL图标默认路径超时、代理成功及模拟器代理下Image1正常，优先处理测试网络可达性，无依据批量修改图标FrameLayout。未做三星同素材清缓存代理开关A/B，不将结论扩大为所有媒体空白均已解决。任务仍26/39。

证据：`build/reports/native/healthtracker-samsung-proxy/after-back.png`、`samsung1-media.log`、`report.md`（含APK SHA256）。


### 退出弹窗间歇不显示：测试来源渲染类型不匹配（2026-09-30）

三星PID3221现有日志确认：内部测试广告位 `b5aa1fa2cae775` 包含 AdMob 来源 `301051680` 与 `301051686`，SDK request_result日志中的两者均为 `render_type=2, template_type=1`。官方参数表明确2代表TopOn模板渲染、1代表开发者自渲染：[参数说明](https://help.toponad.net/cn/docs/AZUgZD)。

10:46:39.621退出弹窗Loading →10:46:40.545来源301051686加载成功 →10:46:40.554 `unsupported_native_render_mode`；10:41:47来源301051680也存在同样紧邻失败的成功回调。10:42:25则消费到Pangle Image2并正常显示。该测试位包含不适配历史自定义XML的模板来源，导致间歇失败。10:44:28的快速失败可能来自缓存，但未逐条识别缓存对象来源。

宿主内部渠道ID来自 `scripts/internal.gradle:19`；历史CARD_3是NativeLayout.Custom。库按SDK `isNativeExpress()` 判断，`AdsNativeView.kt:152`拒绝Custom与模板搭配，随后释放广告并报告Failed；并非没有触发加载。保留此契约与原XML，解决方向是定向自渲染测试来源，或在自有广告位将参与来源统一配置自渲染（AdMob render_type=1）。禁止用随机重试或静默换模板掩盖配置不匹配。正式渠道使用不同广告位，本结论不代表已审核正式后台。

本轮只读诊断，无源码、手机网络或后台配置修改；无需重构建。证据：`build/reports/native/healthtracker-native-template/report.md`、`source-summary.log`。任务仍26/39。


### 固定 Pangle 内部 Debug 验证（2026-09-30）

按用户授权，宿主新增内部Debug专用 `TOPON_PANGLE_TEST`：在后台读取GAID，检查限制追踪及空/零ID后，主线程先调用官方 `TUSDK.setDebuggerConfig(...Builder(50))`，再执行原 Ads.initialize。只修改宿主AppInitializer.kt与app/build.gradle.kts；GAID不打印/硬编码，库及五种历史XML未变。初始化测试失败明确记录、不静默回退混合来源。该官方配置影响本设备所有TopOn格式，直接AdMob仍独立参与全屏竞价。

三星 SM-S721U1/API35 保留数据安装成功，PID21674；11:06:13.745记录测试配置生效。真实退出弹窗连续三次均单次Loading→Loaded→Pangle impression：11:06:52 Image3、11:07:49 Image2、11:08:37 Image1；对应截图主图和图标正常，无unsupported_native_render_mode。`check-exit-runs.py`断言三轮请求/状态/来源均通过。Debug构建成功36s；改动文件diff检查通过。

Gradle实际模型断言internal/debug=true、internal/release=false通过；playstore配置检查被现有google-services.json缺少正式包名client阻断，正式渠道关闭仅为源码结论，未构建或运行正式包。未改Firebase配置绕过检查。

继续历史布局验收：Tracker/CARD_8在11:10:18报native_media_slot_missing；新增血压记录/STANDARD在插屏关闭后11:13:56报同一原因。静态核对五种XML，只有CARD_3存在ads_mv_media，其余STANDARD/CARD_5/CARD_7/CARD_8均无主媒体槽；当前TopOn实现要求该容器。未通过删检查或丢弃媒体掩盖问题；其余布局需要单独决定媒体区域适配。未保存健康记录。本轮仅退出CARD_3重复样本通过，不代表18位置或全部来源完成，总计仍26/39。

持久证据及相对修改前备份：`build/reports/native/healthtracker-pangle-fixed/`，包括report.md、三轮截图/日志、检查脚本、修改补丁与APK校验值。固定测试配置此次保留在内部Debug宿主及三星安装包中，便于继续复测；手机原代理未改。

### TopOn 可选媒体绑定与 UNKNOWN 实机限制（2026-09-30）

按用户批准的方案，库内移除 `binding.media` 的无条件必填检查，媒体块改为可选绑定；随后仍执行图标、广告角标、点击视图注册及原 render/prepare 顺序。提供媒体槽位时保留 SDK MediaView 优先、静态大图兜底、素材缺失及容器自身错误检查。Host 五种 XML、固定 Pangle 调试配置、SDK 版本及手机代理未改；保留 Host 与库既有 dirty 修改，包括 Host 的 scripts/internal.gradle。

无媒体兼容性使用当前广告的公开 `NativeAd.getAdInfo()?.networkFirmId` 及 `getAdType()`，不等待曝光、不猜 View 类名。该 getter 已在 TU 6.6.22.3 编译及三星实际调用中验证；官方 API 表未列出不能据此断言方法不存在。仅已识别 Pangle 图片且有可绑定图标放行；Meta/Vungle 返回 native_media_required，Pangle 视频返回 native_video_media_missing，其他来源、缺来源或未知类型返回 native_media_requirement_unknown。依据：[Pangle Native 指南](https://www.pangleglobal.com/knowledge/native-ad-guideline)的标题加图片或图标最低组合、视频展示/曝光要求，以及 [TopOn 素材要求](https://help.toponad.net/cn/docs/native_ad_platform_notice)与[来源 ID 表](https://help.toponad.net/docs/Debug-Mode-Parameter-List)。Meta 原生横幅例外未能可靠识别，当前不扩大支持。

源码/JVM/构建：新增 TopOnCompactMediaTest，一项确定性回归覆盖已知图片放行、Meta/Vungle、视频、null/UNKNOWN 类型、缺图标、其他/未知来源失败。最终 `:testDebugUnitTest :assembleDebug :lintDebug --offline` 通过，93 tests、0 failures/errors/skipped；先前 Android 测试包构建通过。现有 NativeBindingTest 在模拟器运行 9 项通过，覆盖独立 View、无媒体结构、必需描述/SDK 图标、媒体比例和视频尺寸。Host `:app:assembleDebug --offline` 成功；临时探针去除后再次构建无探针 Debug 成功。以上不证明紧凑 SDK 广告已经成功展示。

三星 SM-S721U1/API35：CARD8 触发 Loading 后为 native_media_requirement_unknown；STANDARD 新增血压入口也在同一最终无探针进程日志中出现该失败。设备曾有并行页面操作，未将该进程所有入口算成本会话自动操作；用户随后明确同意暂时由本会话独占手机，重新执行 CARD8 公开 API 探针。探针只输出非敏感来源、素材类型及视频元数据：source=50、type=0（UNKNOWN）、IMAGE_TYPE=2、VIDEO_TYPE=1、videoDuration=0.0、videoSize=-1×-1。此结果不能证明图片，不能用测试广告文案、缺视频尺寸或时长 0 代替类型契约。用户明确回答当前不能保证或尚未确认广告位仅图片，因此保留 UNKNOWN 拦截，未新增图片专用声明或绕过开关。当前广告位紧凑布局仍未通过验收，图片放行分支尚无真实 SDK 渲染证据。

独占设备阶段，CARD3 退出弹窗取得一次 Loading→Loaded、一次 Pangle impression，截图为 Pangle Test Image 3，主图、图标、标题、描述、CTA、AD 标识可见；未点击广告。该结果仅回归有媒体路径，不代表无媒体成功。探针源代码随后删除，构建并恢复无探针安装包；不清应用数据，不修改网络，不执行保存健康记录。用户原固定 Pangle 测试配置继续保留。

证据目录：`build/reports/native/healthtracker-optional-media/`。源码修改前 `.before` 与本轮 `.patch`、`unit-summary.txt`、`core-checks-final.log`、`binding-device.log`、`host-clean-build.log`、`probe-tracker-process.log`、`standard-final-process.log`、`exit-card3-loaded.png` / `-ui.xml` / `-process.log`、`clean-home` 状态及 `check-results.py`。检查脚本明确断言 UNKNOWN 仍被拒绝和 CARD3 正常，不把拒绝分支通过标为紧凑显示成功；raw SDK 日志仅本地使用，不打印设备或广告标识。原生子代理只读核对官方资料，继承配置，实际 model/reasoning metadata 未能独立核验；公开 getter 的可用性由主会话编译与实机补充确认。整体仍 26/39，无提交/推送。


## 2026-09-30 TopOn 重写、Native 比价与跨 Activity 落选缓存

本轮目标工作树为 `/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation`。用户追加要求参考 remax 的原生比价，并明确选择跨 Activity 复用未胜出对象；这些要求覆盖最初计划中不保留全局 Native 候选的范围限制。原有脏工作树按修改前文件快照校验，只合并本轮文件；未提交或推送。初始 cwd `/Users/jiaoyun/od-fz/ads-mediation` 未改。

实现：保留单平台请求，两个命名 ID 启用并行比价；复用 BidCandidateSelector，USD/次展示统一报价，默认 7 秒期限，区分未知/零价/无对象。每端独立 requestId，获胜 handle 直接交付；只缓存有效且从未渲染的落选对象。每个平台/广告位一条、总计四条，取出即独占，缓存时清空旧回调，新页面重新绑定；到期、SDK 失效、容量淘汰及许可撤回清理。AdMob 一小时从原加载完成时刻计算；TopOn 检查实际 isValid，并有一小时本层保留上限，不虚构 SDK 加载时间。TopOn 以 Application Context 加载，以目标 Activity 创建容器，未使用 MutableContextWrapper 或复用旧业务 View。

TopOn 重写包括一次性加载交付/取消、监听安装、renderAdContainer→prepare、可选文字、SDK icon/media 优先、URL/多图回退、来源/domain/warning、SDK CTA/关闭/广告信息入口，以及先移除再 destory。单平台 Custom 拒绝模板；比价时不兼容模板在选择前排除，不使其他有效候选丢失。既有 compact 图片识别约束保留。

参考依据：

- [TopOn Native 官方文档](https://help.toponad.net/cn/docs/yuan-sheng-guang-gao)及[官方 SelfRenderViewUtil](https://github.com/toponteam/TPN-Android-Demo/blob/main/app/src/main/java/com/test/ad/demo/SelfRenderViewUtil.java)。
- [TopOn 来源注意事项](https://help.toponad.net/cn/docs/native_ad_platform_notice)：Pangle 的渲染容器必须使用 Activity；文档未对所有来源的 Application 加载和跨 Activity 展示作出承诺，来源验收仍单列。
- remax_sdk 固定提交 `83aecfbd9921b75073f761cf5797b7d076efed30`，NativeBiddingManager、TopOnNativeAdController 及 regular NATIVE 价格配置。其 TU 为 6.6.40；本项目保持 6.6.22.3。未照搬将未填充作为 0 候选、混用单次收益与 eCPM 或全局 showing 对象的做法。
- AdMob 的 regular Native 路径 a→b→m 仅对锁定 GMA Next-Gen 1.2.1 配置生效，严格检查 micros 和 USD；反射失败保留未知价格，不把商品 price 或 paid 当作预展示报价。未反编译 SDK 或分析其二进制。

### 本轮确定性与构建证据

最终 `:testDebugUnitTest :assembleDebugAndroidTest :lintDebug :ads-compose:compileDebugKotlin :r8-smoke-app:assembleRelease -PnativeSmoke=true -PnativeTestConfig=<protected-file> --offline` 成功，**109 tests / 0 failures / 0 errors / 0 skipped**。覆盖价格单位/未知值、有效候选选择、超时、初始化等待、同步回调、取消/重试、业务回调重入销毁、模板排除、落选缓存转移/独占、旧回调清空、跨轮收益归因、容量与过期。未新增依赖或升级 SDK。

TopOn 6.6.22.3 的 `NativeAd.isValid`、素材接口和渲染调用均已实际编译通过。R8 release 构建保留 Pangle 依赖的两个 Missing class warning（NetExtParams$RenderType、TTSdkSettings$FETCH_REQUEST_SOURCE）；构建成功，这不等于这些外部类型的所有运行时路径均已验证。

### Android 与 SDK 实测（emulator-5580 / API 36）

- 最终源码的 **14 项 instrumentation 通过**：13 项 NativeBindingTest，加 1 项真实 AdMob NativeCacheTransferTest。后者使用官方测试位，A 加载后将对象保留、A 完成销毁、B 取到同一 handle 并渲染，新监听收到真实曝光，旧监听未收到曝光。
- TopOn 转移测试：现有受保护测试位这次返回 **Admob 来源模板**，A 销毁后 B 命中同一对象且 isValid 通过；因配置无已确认模板比例，渲染检查明确失败。首次测试把该检查抛到主线程导致测试进程退出，已修正测试辅助函数，将异常转报至 instrumentation 并执行 finally 清理；重跑为 1 error，原因仍是缺少模板比例，不算 TopOn 跨 Activity 展示通过。
- 最终 R8 包通过真实比价：AdMob `0.0 USD`，TopOn/Pangle `0.002 USD`，两端 available=true，TopOn 胜出；收到 Loaded、Pangle impression 和 `2000 micros USD` 的真实 ILRD。说明当前样本在 R8 后报价、选择和事件链路工作，不代表逐来源视觉验收。
- 同一最终 R8 进程用 NEW_TASK|CLEAR_TASK 替换 smoke Activity，再通过公开单平台入口请求 AdMob。两次加载结果的 responseId 相同，新请求/展示 session 不同；缓存命中即时返回，原 Activity 销毁后新 Activity 收到曝光与合法 0 收益。这覆盖真实竞价落选→共享缓存→下一 Activity 使用的完整公开入口。截图中主图、图标、标题、描述、CTA 可见，Google native validator 对这个样本显示 No implementation issues found。
- **Pangle 视觉未通过**：同轮 Pangle Test Image 2 的文字和 CTA 可见，主图及图标区域空白；不能用 impression/paid 证明素材完整。已对照官方 getter、媒体优先级及 setImage 调用，未发现足以确认根因的直接调用差异。留存日志未包含可确认的图片网络错误；不把网络、Context 或 SDK 素材归因为已证实原因。

### 待用户真实工程继续的范围

用户明确将提供真实业务工程和 Native ID，再在具体广告场景验收。后续需完成 TopOn 模板的已确认比例、TopOn 未展示对象跨 Activity 后的实际渲染、Pangle 主图/图标空白定位，以及视频、外跳/返回和其他启用来源。历史未完成矩阵不回填为通过。本轮没有点击广告、变更 GAID 定向或操作其他业务应用。

证据保存于 `build/reports/native/topon-rewrite-bidding/`：最终构建日志、JVM 汇总、14 项设备结果、TopOn 失败和转移阶段、脱敏 R8 事件、Pangle 与缓存 AdMob 截图；`incremental.patch` 相对本轮开始的文件快照，不把原有工作树变更归为本轮。原始含标识日志及临时测试配置留在本机受保护临时目录，不复制到记录。测试 APK 使用本机调试签名，不是发布产物。

协作：原生子代理只读核对参考项目和 TopOn Context/有效性公开依据，另一个子代理暂存 TopOn renderer；主会话复核、修正、实现比价/缓存并完成验证。按继承模型与 high 请求执行，实际模型/推理元数据无法独立核验；子代理已关闭。

## 2026-09-30 AdMob Native 预加载比价可行性验证

范围：用户要求先验证上一轮提出的 NativeAdPreloader 方案。本轮只新增 `src/androidTest/java/com/cashcraft/ads/mediation/NativePreloadProbeTest.kt` 和验证记录，未切换生产 Native 加载器、修改反射配置或调整缓存策略。

环境：本工作树锁定的 GMA Next-Gen **1.2.1**；`emulator-5580` / Android API **36**；Debug instrumentation；Google 官方原生测试位 `ca-app-pub-3940256099942544/2247696110`，bufferSize=2。初始化后停止此独立测试进程的全屏预加载，避免全屏缓存占用干扰原生验证。未点击广告。

结果：`:assembleDebugAndroidTest --offline --console=plain` 构建成功；`NativePreloadProbeTest` **1 test passed**（40.764 秒），同一进程连续完成 **3 轮**：

1. 只调用一次 `NativeAdPreloader.start()`，缓存成功装入两条广告。
2. 反射读取队首报价并暂停一秒，模拟本轮落选；再次读取后队首内部对象相同、Response ID 相同、库存仍为两条。没有 poll、销毁或自行补量。
3. `pollAd()` 返回的 Response ID 与报价对象的 `getResponseInfo()`、官方 `peekAdResponseInfo()` 一致；同时断言队首对象 `b()` 返回的配置与取出后 `NativeAd.a.b` 是**同一个配置对象**，该对象的 `m` 就是报价来源。再与已有 `AdMobNextGenBidPrice.fromNative()` 的读数比较，三轮均为合法 `0 USD`。
4. 每轮 poll 后库存为一条。在未展示、未销毁该广告且未再次调用 start/load 的情况下，SDK 自动发出新的 onAdPreloaded 回调，库存补回两条；三轮分别约 10.8、10.5、10.6 秒。总共收到五次成功预加载回调。补量完成后才销毁已取出的广告；最终 destroy(preloadId) 后库存为零。

已验证的 **1.2.1 专用**路径：`gt0.a() -> P0.get() -> qg2.B[preloadId] -> manager.B(queue) -> peek().a.a -> internalAd.b().m`，价格字段 `b` 为 micros、`d` 为 USD。运行时 manager 类型为 `ads_mobile_sdk.wf2`，队首类型为 `vg2`，内部广告类型为 `sp1`。测试通过运行时反射定位 Native map；未反编译或扫描 SDK 二进制。首次探针误以为队列内部包装对象应与 `NativeAd.a` 是同一对象，该断言失败；修正为验证它们共享的底层配置对象后全部通过，保留首次结果以免混淆证据。

结论与边界：当前版本具备“取出前报价、未取出时保留缓存、取出后自动补量”的可用基础。生产接入时仍需把报价与实际取出对象绑定并在不匹配时重新判断；本测试不证明所有并发、过期或队列变化场景都不会不匹配。只验证 Google 的零价格测试广告；**非零报价、其他广告来源、R8 包、跨 Activity 渲染/曝光、TopOn 与该预加载流程的完整竞价尚未在本轮验证**。此前普通 NativeAdLoader 的展示/R8 结果不能替代本方案的这些检查。生产原生流程目前仍未自动补缓存。

依据：[NativeAdPreloader 官方 API](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/nativead/NativeAdPreloader) 定义了非消费的 ResponseInfo peek、poll 和持续填充 buffer；[官方 NativePreloadFragment](https://github.com/googleads/gma-next-gen-sdk-android-examples/blob/main/kotlin/NextGenExample/app/src/main/java/com/example/nextgenexample/preloading/NativePreloadFragment.kt) 展示了 start→poll 的使用方式。公开 API 不提供报价，上述价格路径是版本相关的内部反射。

复现：

```sh
./gradlew :assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5580 install -r build/outputs/apk/androidTest/debug/ads-mediation-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class com.cashcraft.ads.mediation.NativePreloadProbeTest com.cashcraft.ads.mediation.test/android.test.InstrumentationTestRunner
```

证据：`build/reports/native/native-preload-probe/{build.log,instrumentation.log,runtime.log,instrumentation-discovery.log}`。测试完成后停止该测试包进程。本轮没有提交或推送。

## 原生比价改用 eCPM（2026-09-30）

按用户确认，TopOn 原生广告展示前直接读取 `getEcpm(TUAdConst.CURRENCY.USD) / 1000`，统一为 USD/次展示；`getPublisherRevenue()` 不再参与原生比价。AdMob 仍使用 micros/1_000_000；实际收益仍由平台 paid 回调交付。缺失、读取异常、负数、NaN 和无穷值按未知报价处理，合法零价保留。依据为 [TopOn 官方回调字段说明](https://help.toponad.net/docs/Callback-Information-cAVk)，不把 AdMob 政策说明解释为 publisherRevenue 展示前必然不可用。

同步更新原生落地方案、接入文档及本变更 design；仅调整原生取价和对应测试。验证：

- JDK 17、离线执行下列命令，核心 Debug 与测试源码编译成功；四个测试类共 **23 tests / 0 failures / 0 errors / 0 skipped**，覆盖 eCPM 单位换算、真实零价、非法金额、AdMob 单位/币种、竞价选择及获胜收益归因。
- `openspec validate add-page-native-ads --type change --strict --no-interactive` 成功；本轮新增行无尾随空白。

```bash
./gradlew :testDebugUnitTest \
  --tests 'com.cashcraft.ads.mediation.internal.TopOnCompactMediaTest' \
  --tests 'com.cashcraft.ads.mediation.internal.NativeAuctionTest' \
  --tests 'com.cashcraft.ads.mediation.admob.NativeBidPriceTest' \
  --tests 'com.cashcraft.ads.mediation.internal.BidCandidateSelectorTest' \
  --offline --console=plain
```

本轮未新增设备、R8 或非零测试广告报价证据；历史展示与预加载探针结果不能视为此次 eCPM 取价调整的实机验证。其余尚待 Review 的预加载与展示保留方案不因此视为已实现。
