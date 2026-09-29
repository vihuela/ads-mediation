# Banner 实施证据

本记录对应 `add-banner-support`，不是完整交付声明。执行日期：2026-09-29。只有实现及适用验证均完成的任务才在 [tasks.md](tasks.md) 勾选。

## 当前检查点（17:24）

AdMob 正式 View／Compose 已实现；TopOn 经用户批准保留探针，正式请求明确 unsupported。最终尺寸／零宽恢复修正版完整 CI 通过：100 tests，0 failures/errors/skipped；核心／Compose lint 无问题，smoke 45 个 Warning、0 Error/Fatal；Release/R8 和两个模块本地发布成功。真实窗口设备契约默认11例通过，含断网恢复的12例也通过；最终只增强 filled 断言后的默认11例复跑通过，断网用例未变。

独立 emulator-5582 的 API33 最终 R8 已覆盖横屏两次首次展示、78.020秒后台无新增平台请求、Compose／Fragment 真实分屏；随后同端口只读 API36 完成预测返回取消、正式手势返回及测试广告真实落地网页／返回，原实例与事件身份保持。当前33项完成、2项经用户批准延期。2026-09-29 用户明确将1.5／6.4剩余验证转为技术债务 BANNER-REFRESH-01，待接入正式广告位后补验，不阻塞本轮实施收尾；延期不代表验证通过。详情见 [任务清单](tasks.md#已批准延期的技术债务)。以下各节保留实施过程和反例，早期状态不覆盖本检查点。

## 依赖与基线（1.1）

执行命令：

```sh
./gradlew testDebugUnitTest dependencies --configuration debugRuntimeClasspath :r8-smoke-app:dependencies --configuration releaseRuntimeClasspath
./gradlew :dependencyInsight --configuration debugRuntimeClasspath --dependency play-services-ads :r8-smoke-app:dependencyInsight --configuration releaseRuntimeClasspath --dependency play-services-ads
```

两条命令均成功。第一次运行时尚无代码修改，核心 65 个单元测试通过。核心 Debug runtime 与 smoke Release runtime 的实际解析结果一致：

| 依赖 | 实际版本 |
| --- | --- |
| GMA Next-Gen `ads-mobile-sdk` | 1.2.1 |
| TopOn `core-tpn` | 6.6.22.3 |
| TopOn `adapter-tpn-gma-next-gen` | 1.2.1.1.0 |
| UMP `user-messaging-platform` | 4.0.0 |

完整解析树中无 `com.google.android.gms:play-services-ads` 或 `play-services-ads-lite`。名称包含 `play-services-ads-identifier` 的广告标识依赖存在，不能将它误判为 legacy 广告 SDK。构建基线：AGP 8.13.1、Kotlin 2.2.21、Gradle 9.1.0、JDK/JVM 17、compileSdk 36、minSdk 26；广告 SDK 未升级。

本地原始日志保留在 `build/reports/banner/baseline-dependencies.log` 和 `build/reports/banner/dependency-insight.log`。`build/` 是忽略目录，以上命令可重新产生证据。

## 已实现核心与单元证据

- 任务 3.1：`AdFormat.BANNER`、`ad_banner_refresh`、可选 `slot_id`；Banner slot 和加载入口沿用既有计数、position 后缀、日志及异常隔离。Banner 加载不伪装为 preload，不输出 buffer 数量，失败后迟到成功不产生第二个加载结果。原全屏默认输出由既有测试回归。
- 任务 2.1 的平台门禁部分：使用现有 `Ads` 初始化回调逐平台更新结果，观察先注册再读取，解除幂等；所选平台成功不必等另一平台，失败不会被整体 OR 成功覆盖。请求时仍读 UMP，现有隐私选项恢复可重新开始之前被许可阻止的初始化。请求/尺寸公共接口及校验尚未实施，因此 2.1 不勾选。
- 任务 5.1 的实现：全屏配置、就绪、预加载、竞价、等待及展示分支均显式拒绝 BANNER；无 Banner 全屏 ID fallback、缓存或展示锁路径。配置和竞价入口使用 JVM 测试；真实 Android 公共就绪入口另由探针检查。

第一轮 `./gradlew :testDebugUnitTest`：76 tests，0 failures/errors/skipped。加入 UMP 恢复用例后，最终 XML 为 77 tests，0 failures/errors/skipped。新增 `BannerEventTest`、`BannerProviderReadinessTest` 与格式拒绝断言；原 `AdShowSessionTest`、`DisplayOpportunityControllerTest` 等全屏测试仍通过。第一轮日志：`build/reports/banner/core-tests-first.log`。

## 平台能力门槛

公开文档只用于定位编译路径，不替代 1.2—1.6 的设备证据：

- [Google Next-Gen Banner 指南](https://developers.google.com/admob/android/next-gen/banner)给出测试 ID、事件监听及刷新接入；[AdView API](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/banner/AdView)描述加载时自动注册广告，故探针先在未挂载对象上加载，并在业务可见前注册监听。最新文档不等同于 1.2.1 已验证行为。
- [TopOn TU Banner 指南](https://help.toponad.net/docs/Banner-Ad-I8kI)给出 `TUBannerView`、尺寸参数及收益监听接入，要求窗口中可见才能自动刷新。文档的 AT/TU 类型混排、自适应参数与当前 Next-Gen adapter 的关系仍需编译和来源实测确认。

用户已提供 TopOn 测试应用/广告位，已运行 AdMob 来源测试广告；仍缺两端可核验的刷新开关及聚合下层防重复刷新配置。未确认暂停路径、首次失败同页恢复、连续两次刷新身份或迟到收益归属；不得因此选择统一暂停即释放、主动重试、私有字段推断或静默修改验收范围。此门槛只阻塞依赖这些结论的部分。

## 设备与验收边界

检测到真机 `R58N341CK5Z` 和模拟器 `emulator-5560`、`emulator-5580`。拟使用 `emulator-5560`（API 33、sdk_gphone64_arm64），未发现运行中的 instrumentation；未操作其他设备的应用或权限对话框。

当前仍未交付正式 `AdsBannerView`、Compose 模块、Navigation/Fragment 示例、展示/收益身份映射；无 Banner 全矩阵、逐来源或视觉验收完成结论。未提交、推送或远程发布。原全屏变更的任务状态不变。

## 本轮 API 编译与最小运行路径

Debug 探针位于 `r8-smoke-app/src/debug/`；release 保留原全屏 smoke Application。两端公开 API 已在锁定版本编译通过：GMA `AdView.loadAd`、`BannerAdRequest`、`BannerAdEventCallback`、`BannerAdRefreshCallback`、`AdSize.getLargeAnchoredAdaptiveBannerAdSize`/`getHeightInPixels`；TU `TUBannerView`、`TUBannerListener`、`TUAdInfo`、`setAdRevenueListener`、`loadAd`/`destroy`。GMA 读取响应须调用 `BannerAd.getResponseInfo()`，Kotlin 属性语法编译失败，已修正。TU 通用尺寸参数以 dp 换算成 px；后续已编译并实测自适应参数，结果和限制见末节。

设备运行使用 `emulator-5560`、Debug APK、官方 AdMob Banner 测试 ID。启动断言并记录 `Ads.isReady(BANNER)=false`，全屏就绪入口拒绝 Banner。截图 `build/reports/banner/admob-loaded.png` 已查看，显示 Test Ad 且未裁切；宿主内容宽 1048px，请求宽 349dp，合法占位及实际广告高 327px（109dp），广告子 View 宽 1047px。该截图不代表正式底部布局或 Compose 视觉验收。

首次真实回调：`load_call(request=1)` → `loaded` → `attached` → `impression` / `paid`，response ID 均为 `cUe7auquF963vr0P9OjoOQ`；收益为合法 `0 micros / USD / UNKNOWN`。收益与刷新监听设置先于探针挂载。隐藏截图 `build/reports/banner/admob-hidden.png` 已查看，广告不可见、占位保留；恢复后收到新 response ID `sUe7asepIpyz9tMP6bXskQ4` 的 refresh/impression/paid，本层 load_call 仍只有一次。原始记录保存在 `build/reports/banner/device-admob.log`。

探针只记录平台原始事实，不是正式 Banner 收益归因实现。首次失败时 detached AdView 不挂载，该路径尚不能用于验收可见槽位的首次失败恢复；未知平台刷新配置不能据此补记 1.5。初轮 TopOn 因无配置未请求；后续用户提供配置后的设备记录见下节。

复跑：`./gradlew :r8-smoke-app:assembleDebug` 后安装 Debug APK，启动 `com.cashcraft.ads.mediation.smoke/.BannerProbeActivity`。TopOn 测试配置使用本地 Gradle 属性 `probeTopOnAppId`、`probeTopOnAppKey`、`probeTopOnBannerPlacement` 及静态测试设备 `probeTopOnTestDeviceGaid`，不要将实际凭据或设备标识写入仓库；还须提供所启用来源及后台刷新配置。

第二次刷新产生 response ID `_ke7aqmtM5Om1e8Pp83wuQM`，同样分别收到 refresh/impression/paid。因此本轮观察到一次本层加载、三次独立响应的曝光及合法零收益。主动 destroy 后，广告子 View 测量回到 `0×0`；SDK 随后报告一个 `CANCELLED` 的 refresh_failed，属于销毁引发的迟到结果，不能当成用户关闭或正常刷新故障。最后退出探针，再次清理没有重复销毁记录。任务 1.2 的最小公开 API 路径完成；这不等于 1.4—1.6 的跨平台及交错回调验收完成。

设备环形日志在最后读取时已淘汰早期加载记录，`device-admob.log` 因此按时间合并本轮工具先前实际捕获的原始回调行并去除重复行，文件头已注明来源；没有补造回调。测试结束后已 force-stop 本次 smoke 应用。最终 probe Debug classpath 也已单独解析，未混入 legacy GMA，记录在 `build/reports/banner/probe-debug-dependencies.log`。

## 本轮构建结果与剩余检查

```sh
./gradlew testDebugUnitTest lintDebug :r8-smoke-app:assembleDebug :r8-smoke-app:assembleRelease publishToMavenLocal
```

修正上述 GMA 属性语法后，命令成功（3m 31s），完整日志位于 `build/reports/banner/validation.log`。核心 77 个测试全部通过；核心 lint 无问题，smoke lint 无 Error/Fatal，有 28 个 Warning（依赖/工具版本、原 manifest 及 3 处建议使用 KTX 的可见性写法）。探针明确使用 `INVISIBLE` 保留占位，不将其替换成设置 `GONE` 的 `isVisible=false`。没有为版本提示升级依赖。

R8 构建成功，但仍输出第三方 TikTok BillingClient 和 Pangle 内部类型的缺类警告；本轮未扩大范围处理这些依赖。Banner 探针只在 debug source set，因此本次 R8 只证明原 release smoke 路径可构建，不能勾选要求正式 Banner/Compose 在压缩宿主可达并运行的 5.3。5.2 保留未完成，正式 View/Compose 实施后仍需跑最终全量基线。

本轮完整完成任务为 1.1、1.2、3.1、5.1；2.1 已完成所选平台状态与订阅部分，1.3 已完成标准 TU API 编译部分。其余阶段继续按能力门槛推进，等待测试配置不等于已接受产品降级或允许更改规格。新增枚举会影响穷尽 `when`；`AdEvent` 增加字段会改变构造/copy 的二进制签名，宿主须重新编译，完整迁移样例仍属于 5.4。

## 用户提供 TopOn 测试配置后的设备记录

2026-09-29 使用用户授权的 `/Users/jiaoyun/touka/Music/scripts/internal.gradle` 中 TopOn `applicationId`、`appKey`、Banner placement；通过 `ORG_GRADLE_PROJECT_probeTopOn*` 进程环境注入 debug 构建，不执行该 Groovy 文件，不改源项目，不将值写入受版本控制的文件。其他平台和归因/后端配置未导入。

`emulator-5560` 上初始化成功。13:24:26 发起一次本层 `loadAd`，13:24:38 加载成功，13:24:39 收到 paid 后紧接 show；两者的 `showId` 均为 `14c1ff45599a76f9d0d650c914754818_415_1790659478638`，来源 `Admob`，平台报告收益 `0.021 USD`。此数值是测试回调原值，不代表实际收入；尚未归一化为本库公共事件或收益。收益监听早于 load 和首次挂载。

请求 `AD_WIDTH=960px`、`AD_HEIGHT=150px`（320×50dp），但实际 View 为 `960×300px`（320×100dp），GMA 公开调试日志的网络请求也报告 `format=320x100_as`、`sz=320x100`、`adtest=on`。已查看 `topon-loaded.png`，包含 Test Ad 标识；不能因通用宽高参数编译通过就认定它们控制了 AdMob 来源的请求尺寸。

13:25:48、13:27:16 再次收到 paid/show，三轮回调 showId 完全相同，均无 TopOn `onBannerAutoRefreshed`；公开 GMA 网络日志同时记录多次 Banner 请求。本层 load 次数始终为 1。这证明当前配置下 showId 不能单独用作“每次刷新展示”的稳定去重键，尚不能区分重复回调与下层刷新，不能将三次收益直接合并或全部计为独立展示。用户回复也无法判断后台 TopOn 自动刷新和下层 AdMob 刷新配置，故明确记为未知；未擅自选择未知归因降级或修改规格。

用户明确允许后点击已确认的 Test Ad。13:26:32 收到相同 showId 的 TopOn click，启动 Chrome 首次设置页；未接受浏览器条款，直接返回探针。因此已证明点击和外部页面启动，未证明实际落地内容加载，也未将返回当作 Banner 本体关闭。

原始本地证据：`build/reports/banner/device-topon.log`、持续采集的 `device-topon-stream.log`（广告配置值已脱敏）、`topon-loaded.png`、`topon-click.png`。上述轮次只覆盖 AdMob 来源，不能代表所有 TopOn 来源。1.3 的尺寸/自适应门槛以及 1.4–1.6 尚未全部通过。

探针已补充公开 `TUSDK.setNetworkLogDebug(true)`、Dialog 失焦及临时 detach 操作，并在失焦/非 RESUMED 时隐藏内部广告 View，供验证保留实例候选路径；该实验不代表正式库已选定暂停策略。执行 `./gradlew :r8-smoke-app:assembleDebug :r8-smoke-app:lintDebug` 成功（7s），日志 `probe-topon-build.log`。本次未改核心库，复用前轮 77 个核心测试结果，未重复全量/R8 构建。

### TopOn 保留实例候选路径的局部观察

对同一 TopOn/AdMob 来源实例依次测试 Dialog 失焦、临时 detach、退后台、父容器 INVISIBLE；本层始终只有一次 load，最后一次显式 destroy 后 child 为 0×0。`topon-dialog.png`、`topon-hidden.png` 已查看，实际广告隐藏且原有高度在 INVISIBLE 时保留。

| 观察窗口 | 时长 | 公开日志中新增 GMA Banner 网络请求 | TU Banner 来源 request-start |
| --- | ---: | ---: | ---: |
| Dialog 失焦 | 48.986s | 0 | 0 |
| 临时 detach | 32.134s | 0 | 0 |
| 后台至重新获得焦点 | 60.164s | 0 | 0 |
| 父容器隐藏 | 70.820s | 0 | 0 |

记录为 `device-topon-lifecycle.log`，精简事实及 GMA 请求白名单字段见 `topon-lifecycle-summary.log`。这些是有限时窗内的观测，不证明所有刷新间隔/来源、失败实例或所有交错暂停条件；后台配置未知，任务 1.4 仍未勾选。恢复未增加本层 load，恢复后再次 paid/show 仍复用原 showId。

临时 detach 实验还暴露探针的占位从实际 100dp 回到初始 50dp，已将探针改为脱离前保留实际测量高度，新 load 才恢复初始占位；这是探针修正，不能替代正式 View 的测量验收。最终 Debug 构建和 lint 再次通过（8s），日志 `probe-topon-final-build.log`，lint 无 Error/Fatal，28 个既有/建议性 Warning。

公开 TopOn 文档的 AdMob adaptive 示例使用 `AdmobTUConst.ADAPTIVE_*`；初轮未确认当前 adapter 类型，后续已编译这些常量并完成一次设备对照，见末节。[Banner 文档](https://help.toponad.net/docs/Banner-Ad-I8kI)还要求通用宽高与后台来源比例匹配；[来源参数表](https://help.toponad.net/cn/docs/AZUgZD)单列 AdMob 的 size 配置。这与当前 320×50 参数/320×100 请求差异相符，但原因仍未确认。

下一阶段需要解决已观察到的 TopOn 请求尺寸偏差，并取得单一刷新调度的可核验配置，或由用户明确批准尺寸/未知身份/首次失败恢复的降级契约后同步规格。当前不冻结依赖这些结论的正式平台接入、展示收益映射和公共尺寸支持范围；既有独立核心基础已保留，OpenSpec 行为及验收标准未改。


最终占位回归使用官方直连 AdMob 测试广告，高 327px（109dp）：挂载时 `child=1047x327px`，临时 detach 后 `child=0x0px`，宿主仍为 `1048x327px`，本层 load 为 1。记录 `topon-placeholder.log`；这里只断言脱离保持高度，不将未完整记录的最后一次 reattach 标为通过。

同一共享 TopOn placement 在最后一次加载切换到 Topon Adx 来源，实测为 320×50dp；截图 `topon-adx-loaded.png` 未见 Test Ad 标识，因此停止该来源且没有点击，不将它算作官方测试广告验收。这进一步表明源文件的“测试 ID”注释不能保证每次瀑布流结果均为已验证测试来源。

为限制后续探针，已接入官方 `TUSDK.setDebuggerConfig`，构建时通过 `probeTopOnTestDeviceGaid` 静态注入指定测试设备 GAID，使用公开网络编号 2 锁定 AdMob 测试源；不在运行时自动读取任意设备 GAID。未提供 GAID 时不启动 TopOn。当前 6.6.22.3 的 `TUDebuggerConfig.Builder(2)` 已编译通过，文档中未限定类名的 `Admob_NETWORK` 不能直接当成 `TUDebuggerConfig` 字段。[官方设备调试模式](https://help.toponad.net/cn/docs/5xiiue)说明了测试源锁定；这不提供后台刷新开关对照。


### 锁定 AdMob 测试源与自适应对照的最终结果

`TUDebuggerConfig.Builder(2)` 配合静态测试设备 GAID 已在 emulator-5560 生效：公开日志标记 `tpn_thinkup_network(DebuggerMode)`，Banner 请求来源为 Admob/networkFirmId=2，GMA `adtest=on`，截图明确显示 Test Ad，paid 为合法 0 USD。GAID 与应用密钥均仅进本地构建环境；`ProbeApplication` 未配置设备 GAID 时不启动 TU。

为调用公开常量，仅向 debug compile 增加已经存在的 `adapter-tpn-gma-next-gen:1.2.1.1.0`。`com.thinkup.network.admob.AdmobTUConst` 的 `ADAPTIVE_TYPE/ADAPTIVE_ANCHORED`、`ADAPTIVE_ORIENTATION/ORIENTATION_CURRENT`、`ADAPTIVE_WIDTH` 已在锁定版本编译通过。自适应场景用启动 extra `--ez topon_adaptive true`，宽度传宿主实际内容 1048px；默认启动仍为通用 320×50dp 对照。

| 场景 | TU 公开网络日志中的来源 size | GMA 实际请求 | 容器/子 View 实测 |
| --- | --- | --- | --- |
| 用户共享广告位，通用 320×50dp 参数 | 320x50 | 320x100 | 320×100dp |
| 锁定 AdMob 测试源，通用 320×50dp 参数 | 300x250 | 300x250 | TUBannerView 为 320×250dp |
| 锁定同一测试源，显式 anchored adaptive，宽 1048px | 300x250 | 300x250 | TUBannerView 为 1048×750px；广告仍为 300×250dp |

第一行的后端来源字段确为 320x50，所以不能把 320x100 偏差直接归因于用户误配后台。后两行证明当前测试路径中通用宽高和显式自适应参数均未覆盖来源固定尺寸；编译通过不代表这些参数在 Next-Gen adapter 上生效。保留这一设备限制，不反编译 adapter、不改锁定 SDK、不伪造自适应支持。截图用于识别测试广告与固定尺寸，ScrollView 截图不代表正式底部布局视觉验收。

原始记录：`device-topon-debugger.log`、`device-topon-adaptive.log`；后者最终回调与释放补充合并为 `topon-adaptive-callbacks.log`（文件头注明两次捕获来源）；截图 `topon-debugger-loaded.png`、`topon-adaptive.png`。SDK 还输出过 `PreInitNetwork may affect DebuggerMode` 提示，实际观察的测试请求为 AdMob；未将这个提示当成所有网络未来行为的保证。

最终执行 `./gradlew :r8-smoke-app:assembleDebug :r8-smoke-app:lintDebug` 成功（13s），日志 `probe-topon-adaptive-build.log`。再次解析 debug runtime，GMA 1.2.1、TU 6.6.22.3、adapter 1.2.1.1.0、UMP 4.0.0 不变，无 legacy GMA，记录 `probe-final-dependencies.log`。原核心 77 项测试结果仍适用；本轮新增改动仅在 debug 探针/构建和证据文档，未重跑无关全量/R8。最终退出并 force-stop smoke 应用，专用日志采集进程已停止。

任务维持 4/35（1.1、1.2、3.1、5.1）；1.3 已新增尺寸与自适应反例，1.4 已有局部保留路径观察，1.5/1.6 缺可核验的刷新开关和可靠展示身份。正式 View、Compose 和完整归因尚未交付。

锁定测试源的自适应对照在 13:48:13 再次 paid/show，仍复用首次 13:47:02 的 showId，且无 TopOn refresh 回调；因此来源锁定也没有消除刷新身份阻塞。最后一次显式 destroy 后 child=0×0，本层仅一次 load。最终 OpenSpec strict 校验及 git diff --check 通过，最终 probe lint 为 0 Error/Fatal、28 Warning；扫描确认受版本控制及未忽略的新文件不含注入的凭据或 GAID。

## 2026-09-29 续做：获批先交付 AdMob（进行中）

用户明确选择“先交付 AdMob，TopOn 后续补齐”。proposal/design/spec/tasks 已同步为 AdMob 正式 View／Compose，TopOn 正式请求返回 `topon_banner_not_supported`；此前 TopOn 探针反例继续保留，不算通过。

本轮新增 `BannerRequest`／`BannerSize`、`BannerState`、`AdsBannerView`、逐展示事件与 AdMob 元数据分发、可选 `ads-mediation-compose` 模块、正式 Compose Navigation／Fragment smoke 入口以及 `docs/banner-integration.md`。广告 SDK 依赖锁定不变；核心新增公开 LifecycleOwner 依赖 2.8.7。

- `core-session-validation.log`：请求及展示事件基础的 Debug、lint、单测通过（21 秒）。`core-session-ci.log`：同阶段既定 CI 与本地发布通过（1 分 52 秒），早于正式 View／Compose，不冒充最终检查。
- `formal-first-build.log`：正式核心／Compose 编译通过，宿主错误引用 `matchParentSize` 导入失败；已移除该导入。
- `formal-debug-validation.log`：正式三个模块 Debug/lint 通过（3 分 34 秒），当时核心 92 个测试通过，lint 无 Fatal/Error。
- `formal-view-tests.log`：加入 Robolectric 构造重入、初始停用隐藏、owner 销毁及同 ID 多实例测试后，96 个单测全部通过（22 秒）。
- 设备初轮使用 emulator-5560／API33、PID26448、正式 `SmokeActivity`，只有官方 AdMob 测试 ID；初始加载、曝光、零收益、至少两次刷新已实际发生。`formal-compose-first.log/png`、`formal-compose-refresh.log`、`formal-live.log` 和 `formal-callback-tail.log` 保留证据。日志环形缓冲及 adb 输出缓冲可能缺早期／末尾记录，汇总须按时间和事件去重，不补造回调。
- 首个 Home slot 为 `94da2444-3c63-488f-bb24-d45dc6aa3ba3`，初次 session `ba278a4a-5c54-483c-bfd7-e33aa2daf638`；14:24:24 第一次刷新 session `4ef6fe51-a7d5-4b04-aa73-1672ce5a5f56`，14:26:31 第二次展示 session `71c7dc9a-81bb-49bc-8b97-9c3f645861fe`。刷新没有伪造本层 request，均为合法零收益。
- 14:28:27 返回 Detail 新周期时，paid 先于 impression，两者共用 session `88d415ab-29dc-45be-91c9-c88fd77a3414`。同路由 Detail 的不同 entry、返回、Fragment View 重建均观察到独立 slot。
- `formal-dialog.png` 证明 Dialog 失焦期间真实广告隐藏且保留空间；恢复沿用 slot。后台观察、精确平台请求窗口、首次断网恢复、最终 R8 启动仍在继续，不能仅凭截图勾选整项暂停矩阵。早期 `formal-actions.log` 的 shell 日期空格被拆分，只保存月日，不用它声称精确暂停时长；后续 JSONL 使用主机 epoch。
- `formal-fragment.png`：传统 Fragment 真实测试 Banner 自然高度位于底部；14:34:20 重建后新 slot `7655ff81-06c5-4ca9-8cf6-f5ea5111ff00`。其 14:36:50.564 测试点击归 session `dd5f1a48-345e-442f-b75b-35375c1ce392`。打开的是 Chrome FirstRunActivity，未接受条款；随后返回，不宣称落地正文已加载，也未伪造 Banner 本体 close。
- 原生子代理 Erdos/Hegel/Parfit/Pascal 均核对本次运行元数据为 openai / gpt-6-sol / high。Pascal 定点检查发现组合更新 `active=false, visible=true` 的瞬时加载风险，已修正为停用先于显示、隐藏先于启用；设备回归待最终 Debug 包。
- 正式 View 保有当前 BannerAd 到资源释放，SDK 回调仅弱持有该对象与页面；迟到收益使用无页面引用的不可变元数据。每代最多 32 个展示，未知或淘汰身份仅诊断。受控测试覆盖失败终态后恢复、收益先到、重复／乱序、响应快照、生命周期结束后的已知收益及未知身份。
- 14:38:44.676，PID28303 首次断网本层加载；14:38:46.675 收到 `NETWORK_ERROR`，仅一个失败终态。恢复网络后，14:40:51.646/686 同页产生曝光/收益，session 为 `490c25d4-b185-42ab-ba0a-6b9e6407b366`，response 为 `YV27avjzJvWW9tMP0s_luQM`。仍是原 slot `73c9040d-1d82-4b8d-8fd5-ca4ed1216cd8`，本层只有 request `cdef618a-6f24-42ae-a9c2-a77842139101`，没有第二个 load result；恢复展示不错误关联已失败的初始 request。证据：`formal-first-failure-tail.log`、`formal-first-failure-recovered.png`。
- 同一正式实例在 14:41:56 成功刷新，随后再次断网；14:43:06.889 收到 `ad_banner_refresh result=failed` / `NETWORK_ERROR`，没有伪造新展示或本层请求。`formal-refresh-failed-retained.png` 中旧 Test Ad 和合法占位仍在。完整补充日志为 `formal-failure-final.log`。两次断网试验后恢复 emulator-5560 原有 Wi-Fi 与数据开关，14:47 核对原值均为 1；未修改其他设备。
- `formal-ci.log`：正式三个模块的首轮 `testDebugUnitTest lintDebug :r8-smoke-app:assembleDebug :r8-smoke-app:assembleRelease publishToMavenLocal` 全部成功（19 分 44 秒，含首次 Compose 宿主 R8）。最终小修订另跑 `final-ci.log`，不能以前一轮包替代。

截至此检查点，新正式入口已实现，最终 CI、部分设备边界及任务勾选待本轮收尾；不能沿用此前“正式入口尚未实现”的阶段状态。

### 最终构建与设备隔离

最终命令：`./gradlew --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx8g -XX:ActiveProcessorCount=6 -Dfile.encoding=UTF-8' testDebugUnitTest lintDebug :r8-smoke-app:assembleDebug :r8-smoke-app:assembleRelease publishToMavenLocal`。`final-ci.log` 记录成功（4 分 3 秒）；8GB 和处理器上限只作用于这次运行，未修改项目 Gradle 性能配置。96 项测试，0 failure/error/skipped；核心与 Compose lint 均为 0 Fatal/Error/Warning，smoke 为 0 Fatal/Error、41 Warning（测试宿主文案／依赖等，未扩大修改范围）。R8 仍提示既有 Pangle 两个缺失注解类型，不能将构建成功写成 SDK 无任何告警。

本地 `.m2` 两份 POM 与当前生成文件逐字一致。核心仅增加 public Lifecycle runtime 2.8.7，不含 Compose／Navigation；Compose 的 compile 依赖为核心 snapshot、UI 1.7.6、Lifecycle Compose 2.8.7，foundation/preview 为 runtime。正式 View／Compose 调用样例均随宿主编译。未远程发布、未提交代码。

14:50 在 emulator-5560 安装最终 Debug，初始隐藏截图 `final-initial-hidden.png` 符合占位，事件当时无 Banner load。但随后另一独立包 `com.cashcraft.ads.mediation.smoke.native` 的原生广告页进入前台，UIAutomator 查找失败，原坐标回归步骤被中断；`final-reveal-inactive.png` 是该无关页面，**不能当作本次功能证据**。未覆盖其 APK；发现干扰后停止操作该设备，后续改用本任务独立 emulator-5582。共享 P4a AVD 不允许第二实例，改为未运行的 Pixel_7_Pro/API33，以 `-read-only -no-snapshot -no-window` 冷启动，不保存源 AVD 数据。

独立设备首次 R8 运行 PID3633：初始 `visible=false` 产生 slot `a0f2b814-7419-4f8c-90a4-bfeb3e67613c`，无 Banner load；等待 AdMob 全屏预加载成功证明平台已就绪，再按 “Reveal while deactivating”，`r8-reveal-inactive.png` 显示 Inactive，仍无 Banner load。启用后 14:56:31 只新增一个 slot `fdd4bf86-c427-414f-af88-ea112e79c4c8` 和 request `7a36ba19-8d5e-49eb-976d-8bec6b5a9769`，14:56:34 曝光和两个收益出口一致，session `cb43e376-dcc7-46e9-b247-bd94b49572f8`，金额 0 USD。连续记录在 `r8-device-events.log`；`r8-initial-hidden.png` 拍到冷启动 splash，不作为占位截图，键盘首张 `r8-ime.png` 仅显示输入焦点、没有软键盘，也不算 IME 验收。

同包切换 TopOn 时无新增 Banner load，但 `r8-topon-unsupported.png` 暴露旧 View 的 Destroyed 通知覆盖新 View 初始 Failed 状态。已在 Compose 回调上比较当前 Activity/owner/request 值身份：仅当前实例通知 UI，最终移除同身份实例仍可通知 Destroyed；全局事件／收益不受该 UI 门禁影响。该真实回归由可运行 smoke 的 Platform 按钮复现，修复后的编译、R8 和设备状态另记，不把修复前结果写成通过。

`final-callback-fix-ci.log`：同一完整 CI 命令再次成功（3 分 28 秒，53 执行、226 up-to-date）。此次仅修复 Compose UI 身份隔离，核心 96 项测试与 View 设备证据仍适用。

独立模拟器调整为 1080×1920 / 480dpi（360dp），只影响不保存的测试实例。R8 PID6293 以初始 `active=false` 启动，无 Home Banner slot/request；进入 Fragment 后，15:04:44 请求标准尺寸，`r8-standard-loaded.png` 显示实际 960×150px 广告，即 320×50dp，底部未裁切。padding 两侧各 24dp 后内容宽 312dp，`r8-standard-too-narrow.png` 明确 Failed、广告移除、没有新请求。恢复宽度于 15:05:24 新加载一次，仍是 slot `2172c723-9d0d-48cd-8a8a-2643234218e1`；全程 1 position、2 request/result、2 impression/paid，见 `r8-standard-final.log` 和连续事件日志。早期命名 `r8-standard.png` 拍到的仍为 Home，不作标准尺寸证据。

R8 PID8573 公共底部模式：Home → Detail → 第二个同路由 Detail 保持 slot `8d72246e-5a1d-4fcb-b292-6c436fb47bba`，本层仅 request `e5c80454-5949-4b70-89ce-023461a5a925`。`r8-shared-footer-detail.png` 为导航后的实际广告；只读实例启用软键盘显示后，`r8-shared-footer-keyboard.png` 显示文字输入、完整 Banner、键盘各自占位，正文滚动而不叠盖广告。输入和 IME 改变可用高度期间无额外本层请求，SDK 刷新另按真实回调记录。

修复后 R8 PID9161：AdMob 首次展示 session `93b7758d-c5a7-4f0e-a733-e4b33becf334`、再次刷新 session `3e37733c-0f2f-4043-8b6a-34c26adc0fbf` 均正常；切换 TopOn 后 `r8-fixed-topon-state.png` 确认 UI 保持 `Failed(reason=topon_banner_not_supported)`，没有被旧 Destroyed 覆盖，也没有新的 Banner 请求。所有正式 Banner 事件平台仍为 admob；R8 smoke 的伪 TopOn 全屏预加载失败是既有构建可达性配置，不应混算为 TopOn Banner load。

### 横屏自适应：未通过

PID8573 在 15:07:35 Activity 旋转重建后新建一个 slot `c8b0b083-17e1-4ee4-ab04-9b8ad1eae1f5`、只发一次 request；15:07:36 返回 `INVALID_REQUEST / Ad size will not fit on screen.`。`r8-shared-footer-landscape.png` 是失败后占位，不能称为成功显示。

移除测试实例的 `wm size/density` 覆盖，恢复 Pixel 7 Pro 原始 1440×3120 / 560dpi；使用 `wm user-rotation lock 1` 后，`am get-config` 确认 `land / w850dp / h359dp`。R8 横屏冷启动 PID10408 仍在 15:12:20 返回同样错误，见 `r8-landscape-cold-confirmed.log/png`。此前 `r8-landscape-cold-native-size.png` 和 Debug PID10857 的首次成功发生在实际已回到 portrait 的配置，不能冒充横屏反证；每次场景以实际配置和截图为准，不仅看发出的旋转命令。

最终在 Debug `BannerProbeActivity` 重新确认 `lock 1` 与 `land / w850dp`，不用 UIAutomator，按截图直接点击 AdMob load。公开 API 直接调用 `getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)`，实际内容宽 2944px / 3.5 ≈ 841dp，15:16:29 也返回相同 `INVALID_REQUEST`。证据 `debug-probe-landscape-before.png`、`debug-probe-landscape-live.log`、`debug-probe-landscape-result.png`。因此这不是仅正式 View／Compose 才有的现象；现有证据不足以进一步断言 SDK 内部根因或某新版已修复。未反编译、未私有 API 探测、未猜测固定 inset 偏移、未升级 SDK。

[官方 Banner 指南](https://developers.google.com/admob/android/next-gen/banner) 和 [AdSize 公开参考](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/banner/AdSize) 支持当前 API 用法，但不替代锁定版本的失败实测。保留 2.6/6.3 未完成；后续需要可复现的公开 API 尺寸诊断或经批准的 SDK/尺寸策略修订后，再验收横屏与分屏。此限制也已写入接入说明。

### 任务证据索引与保留项

以下对应原方案第 13 节；设备观察只对所述设备／来源／路径成立。

| 任务 | 本轮证据与边界 |
| --- | --- |
| 1.1、1.2、3.1、5.1 | 前文锁定依赖、公开 SDK 探针、事件／全屏拒绝测试的既有记录继续适用；未因本轮范围调整重复宣称新增通过 |
| 1.3、6.5 | BannerRequestTest、AdsBannerViewTest 的 unsupported 测试、正式与 R8 Platform 切换零 Banner 加载；最终 Failed 截图；TopOn 反例未抹除 |
| 1.6、3.3 | BannerDisplaySessionTest 与 AdMobBannerEventsTest：首次及两次刷新、重复回调、乱序、同 SDK ID 两实例隔离；正式 Home 连续刷新、Detail paid 先到、Fragment 测试点击；AdMob 不提供本体关闭映射 |
| 2.1 | BannerRequestTest、BannerProviderReadinessTest：空 ID/position、窄标准尺寸、未配置、初始化结果、UMP、Bidding 两种部分失败及订阅时序；正式平台分支不回退 |
| 2.2 | AdsBannerViewTest：构造重入销毁、owner 终态、同 ID 双实例、自有 child 清理；Fragment 替换和设备 active 周期记录 |
| 2.7 | 目前没有可确认的 AdMob Banner 本体关闭来源，不映射落地全屏关闭；事件层 close 一次结束 slot 的受控测试通过，不新增业务关闭按钮 |
| 3.2 | 失败恢复事件测试与正式断网恢复只发一个失败终态；标准宽度恢复同 slot 新 request；临时暂停和公共 footer 导航不额外记 position/load |
| 3.5 | 金额校验、0 USD、paid/impression 独立去重、旧身份迟到、未知／淘汰身份诊断、双全局出口异常隔离；32 条不可变元数据上限，无页面／SDK View 引用 |
| 4.1 | 当前核心／Compose 发布 POM 和本地仓库逐字一致；实际编译使用坐标、插件、依赖；核心 POM 无 Compose／Navigation |
| 4.2 | 组合更新停用先于显示；实际初始隐藏与停用零请求；请求替换旧 UI 通知回归已修复并通过 R8；等值请求/Tab、Navigation owner 和最终释放均有源代码／运行记录；Preview 为源代码分支检查，未声称渲染器实测 |
| 4.3、4.4 | 真实编译的页面独立 NavHost、共享 footer、Fragment 示例；同路由 entry、Dialog、Fragment 重建与共享底部设备截图/事件 |
| 5.2、5.4 | 完整 CI/96 tests/lint/R8/local publish；代表性 View/Compose 宿主实际编译；接入说明明确枚举穷尽处理与 AdEvent 构造/copy JVM 签名要求重新编译 |

尚未满足的验收条件分开记录：

- 1.5/6.4 的可控关闭刷新对照、真实 no-fill 后填充恢复仍缺配置／场景。官方测试广告的实际刷新与断网恢复不等于可核查的后台开关设置。
- 2.3/2.4/3.4/3.6 目前有事件层顺序、异常、去重与基础 View 生命周期测试，完整正式 SDK 创建／配置失败和在途回调受控矩阵另行补证，不用 source inspection 冒充注入成功。
- 1.4/2.5/6.2 的父层隐藏／临时脱离、应用内全屏覆盖已补正式路径证据（见后文）；多暂停原因的受控回调矩阵、各窗口精确平台请求对照仍不完整。Chrome 未接受首次启动条款，未验收落地网页正文。
- 2.6/6.3 有标准尺寸、竖屏自适应、窄容器与键盘证据；横屏已有明确失败，分屏和平台主动高度变化未实测。
- 6.1 已有页面导航、同路由 entry、Fragment 和 Activity 重建；预测返回取消尚未覆盖，旋转后的自适应加载未通过。
- 5.3 已有 R8 正式 Banner、unsupported、竞价测试插屏入口运行；AdMob 价格可用、获胜、曝光、收益和关闭均有事件（见后文）。SDK 的 Pangle 缺失注解告警不因 Banner 成功而消失。

补充受控 View 测试由 Hume 执行，运行元数据核对为 openai / gpt-6-sol / high。Robolectric 夹具虽 attached/shown/focused，但 windowVisibility 仍为 GONE，正式 View 停在 Waiting，不能伪称已执行 SDK load 或迟到回调。未通过的实验全部撤回，没有新增受控覆盖；`view-controlled-tests.log` 最终基线 96 tests/0 failures/0 errors。后续自定义 View Shadow 的一次定点方向尚未执行验证即结束，不将其写成可用方案；不添加生产测试入口。主代理接回构建与设备工作，2.3/2.4/3.4/3.6 保持未完成。

`final-host-controls-ci.log` 为最后宿主测试按钮变更后的完整 CI：同上命令成功（2 分 50 秒），96 tests/0 failures/errors/skipped；核心和 Compose lint 0 Fatal/Error/Warning，smoke 0 Fatal/Error、45 Warning。本轮没有修改广告 SDK 版本或 consumer rules。新增按钮只用现有公开 API 展示官方测试插屏，以及对 Fragment 的同一容器做 GONE／detach/reattach，便于实际覆盖暂停路径；不移动 SDK View 到另一页面。


### 独立设备：父容器恢复与 R8 全屏回归

最终 Debug PID12155 的 Fragment slot 为 `4d251b78-e6f8-4bdc-851d-3505de0dc1c6`，初始 request `84992939-d88b-4c38-acf6-51b57788eec7`。先隐藏父容器，再脱离同一容器，最后仅恢复父容器可见性而保持脱离；从 `parent-actions.jsonl` 的 epoch 1790666966106 到重新挂载 1790667104940，共 138.834 秒。`formal-parent-summary.json` 对公开 SDK 网络日志按 PID、时间窗及 Banner format 过滤，此窗口 0 个新 Banner 网络请求。恢复后 `formal-parent-restored.png` 显示真实 Test Ad，仍为原 slot，仅 1 position/1 本层 request/1 load result，随后正常 SDK 刷新。`formal-parent-events.log` 合并去重两份事件来源，原始网络流为 `formal-parent-pause-network.log`；请求日志含设备信息，仅保存在忽略的本地目录，报告不复制原始请求头或完整 URL。

最终宿主控制按钮版本 Release/R8 PID13178：Home Banner 于 15:35:59 展示，slot `46696c23-0cbd-4708-b67b-d422b130fcfa`，request `6661467d-aa11-4391-b470-7639a6abfef7`。Show test interstitial 经既有 Bidding 入口于 15:36:05 产生获胜事件，`admob_price_available=true`，15:36:07 插屏曝光与 0 USD 收益，15:36:42.512 关闭，同属 session `ca6ed055-2df6-4986-b12b-4410a83cfffc`。`r8-fullscreen-visible.png` 确认 Test Ad 与真实 AdActivity；`r8-fullscreen-restored.png` 确认关闭动画结束后 Banner 回到原位置。本轮 Banner 仍仅 1 position/request/result/impression/paid，没有新增本层加载或 Banner close，见 `r8-fullscreen-events.log`。即时 `r8-fullscreen-return.png` 是关闭过渡帧，不作为稳定恢复截图。此覆盖观察约 35 秒，不冒充超过 SDK 刷新间隔的暂停网络对照；该对照由上一段父容器场景提供。

### 横屏修复：不可见预挂载顺序（用户已批准）

前述失败的正式实现和直接探针都在成功回调之后才挂载 AdView，因此先前对照尚未隔离挂载顺序。新增仅 Debug 的 `attach_before_load=true` 对照：同一 Pixel 7 Pro/API33、实际 land/w850dp、2944px 内容宽与 841×77dp SDK 请求，在加载前将空 AdView 以 INVISIBLE 加入自有容器，加载成功完成监听注册后再显示。PID13725 于 15:37:53.701 加载，15:37:55.286 成功，随后 impression/paid 共用 response `4Wq7ap3zGaij1e8Plfm3sQs`，0 USD；子 View 实际 2943×269px，截图 `probe-preattach-landscape-loaded.png` 完整显示 Test Ad。日志 `probe-preattach-landscape-final.log`；对照 Debug 构建/lint `probe-attach-order-build.log` 成功（18 秒）。这定位到本次集成的挂载顺序，不据此推断 SDK 私有实现。

用户随后明确批准将“监听先于首次挂载”修订为“允许资格复核后的不可见空 View 预挂载，监听仍先于首次可见及任何可能触发展示的业务通知”。正式 `AdsBannerView.load()` 因此在 Loading 通知及资格复核之后加入 INVISIBLE AdView，并在挂载之后再次检查代次与资格才发起 load；成功与失败的处理继续复用原有释放、监听和显示路径。spec/design/tasks/原方案同步这一已批准的顺序，未降低身份归属、暂停或监听时点要求。正式修复后的 CI 与最终 R8 方向复验在下一段记录；探针成功本身不勾选正式尺寸验收。


### Compose 首次布局诊断与修正

仅预挂载的第一版完整 CI `final-mount-order-ci.log` 成功（2 分 20 秒），但 R8 PID14790 的 850×77dp 首载仍失败；约一分钟后 SDK 内部恢复，未新增本层请求。传统 Fragment 同宽横屏在 15:44:29 首次展示正常。因此不能将探针的预挂载成功直接提升为正式 Compose 全面通过，`mount-fix-r8-landscape.png` 是这一失败证据，`mount-fix-r8-controls-end.png` 是其后内部恢复。

后续只用 Android View 公开尺寸诊断：PID15935 在横屏 load 前，宿主 2976×269px，SDK 子 View 仍为 0×0。仅等待首次布局未修复；给 SDK View 设置公开 AdSize 对应的最小宽高后，子 View 为 2975×269px，首载成功，但只显示 Test Ad 标识，没有实际素材或曝光。将调用移到布局回调后的主线程下一轮也未独自解决素材问题。最终在 INVISIBLE→VISIBLE 的真实变化时补一次标准 `requestLayout()` 后，PID17301 的 Compose 横屏首次显示完整素材，并于 15:53:33.748/749 收到归属一致的 impression/paid，未等刷新补救。临时几何诊断已移除；没有定时器、额外 SDK 重试、硬编码尺寸偏移或 SDK 升级。

最终核心保留：空 AdView 的合法最小尺寸、不可见预挂载、首次布局后投递加载并复核当前代次/资格、可见性变化后的单次布局请求。此结论是本次集成对照结果，不以公开 AndroidX 其他版本问题或 SDK 私有实现推断原因。`layout-diagnostic-events.log`、`layout-minimum-events.log`、`layout-post-events.log` 保留失败边界，`visible-layout-events.log/png` 保留成功证据。

`r8-smoke-app/check_banner_log.py` 是可运行的冷加载检查：按 slot 要求单一 position/request/filled result、首次曝光和收益各一次且关联初始 request/response/session；截图仍独立检查。该脚本在 slot `bc25a7aa-81d4-40df-a1e9-9484e4f7cab1` 通过，并在“首次失败后 SDK 恢复”和“filled 但缺曝光/收益”两份反例上按预期失败。15:54:43 同一横屏 slot 正常刷新成新 session。旋转回竖屏后新 slot `0f211abe-3bed-4521-b9a9-fadf5768c855` 的加载结果在 Dialog 失焦期间到达，15:55:48 返回后才曝光/paid，仍仅一个本层请求。`layout-final-dialog-stable.png` 为真实失焦隐藏截图；早期 `layout-final-dialog.png` 是打开前过渡帧。


### 最终原生子布局补齐（继续验证）

`final-layout-ci.log` 成功（3 分 28 秒、96 tests），但其 Release PID18473 横屏仍首次失败。因此先前 Debug 成功仅是局部证据，不能认定 R8 已修复。将 Debug 的 application 临时切换为与 Release 相同的 SmokeApplication/Bidding 后，也出现 loaded/Ready 但只有 Test Ad 标识的情况，不能单独归因于压缩或 TopOn 初始化；该临时清单已按原文件逐字恢复。

最终增加一个独立的原生布局回归：模拟宿主在外层尺寸不变时只测量而不再摆放，子 FrameLayout 的自然宽度从 20 改为 80 后，布局必须完成。去掉补齐代码，`interop-layout-negative-test.log` 精确失败 `expected 80 but was 20`；恢复后 `interop-layout-tests.log` 的 5 个 View 测试通过。此测试不需要模拟广告 SDK 就绪，也没有生产测试入口。

修正是在正常测量后投递一次检查：仅当仍有原生布局请求、组件附着且外层实测尺寸未变时完成自身布局；正常宿主已完成布局则不处理，销毁或脱离也不处理。它与合法 SDK 初始尺寸、不可见预挂载、加载前资格复核及可见性切换时的布局请求一起保留，不引入网络重试。最后 Bidding Debug PID20104，明确 land/w850dp，slot `7f411868-84c3-4c95-99e0-a988b8535ad7` 的首次 request `80f8d933-491d-4d66-98a2-73962b712347` 于 16:09:41 成功，随后完整素材、impression/paid 共用 session `990a217d-b4d5-49ca-90d4-104574fd7c19`，冷加载检查脚本通过。`complete-layout-debug-loaded.png` 为稳定画面，`complete-layout-debug-landscape.png` 仍是 Loading 过渡帧，不能据此判失败。

构建期间补过一次旧修正版的 86.718 秒后台窗口（PID17301），SDK 新 Banner 网络请求为 0，本层请求仍 1，见 `final-background-summary.json`。返回后一秒截图只有 Test Ad 标识，没有验收完整素材恢复；最终子布局版本另行复验，不能用旧早期截图替代。

### 最终像素尺寸、R8 横屏与后台恢复

`final-interop-ci.log` 通过（2 分钟、97 tests），但 PID20865 的实际 land/w850dp 首次仍在 16:17:24 返回 INVALID_REQUEST，slot `8e95af29-64cb-4a14-8f2a-0ba20013868f`。该反例保留于 `final-interop-r8-events.log/png`，不能被前一版 Debug 成功替代。

最后确认一个独立可复现的尺寸问题：77dp × 3.5 = 269.5px，原截断得到 269px，小于请求尺寸。占位与 SDK View 最小宽高统一使用 `ceil(dp * density)`，不改变请求的 dp 宽高。新增 420dpi 下标准 50dp 占位测试；临时恢复截断时该断言失败（`fractional-size-negative-test.log`），恢复向上取整后通过。`final-pixel-size-ci.log` 完整命令如下，成功 1 分 40 秒，98 tests/0 failures/errors/skipped。核心与 Compose lint 0 Fatal/Error/Warning；smoke 0 Fatal/Error、45 Warning。既有 Pangle 缺失注解告警仍存在，广告 SDK 版本和 consumer rules 均未改变。最终两个生成 POM 与 Maven Local 对应文件逐字一致。

```sh
./gradlew --max-workers=4 '-Dorg.gradle.jvmargs=-Xmx8g -XX:ActiveProcessorCount=6 -Dfile.encoding=UTF-8' testDebugUnitTest lintDebug :r8-smoke-app:assembleDebug :r8-smoke-app:assembleRelease publishToMavenLocal
```

最终 Release 安装到仅本任务使用的 emulator-5582（Pixel 7 Pro/API33、560dpi）；两次新进程均检查实际 `land/w850dp`，请求 850×77dp，没有等 SDK 刷新补救：

| 进程／时刻 | slot | 首次 request | 曝光／收益 session | 证据 |
| --- | --- | --- | --- | --- |
| PID21667，16:24:56 filled，16:24:57 impression/paid | `f3310ada-00f4-4554-ad25-dfa58243f01b` | `e4763fdd-5477-4067-89a9-97ad1f3adab5` | `fd89b522-97fe-4a25-aaae-616a3775fa56` | `final-pixel-r8-events.log`、`final-pixel-r8-landscape.png` |
| PID22182，16:25:42 filled，16:25:43 impression/paid | `720850a0-2169-438f-b6bc-d2b6d916f065` | `fae20e17-f769-4fd8-8dc1-19508747d7b2` | `ac34dcb0-5988-45a4-8880-370f9e6e7f70` | `final-pixel-r8-repeat-events.log`、`final-pixel-r8-repeat.png`，position 起点取连续 `r8-device-events.log` |

两组 `check_banner_log.py` 均通过，截图均人工查看为完整 Test Ad 素材。公开几何和负向测试证明截断缺陷；这两次最终组合成功不用于断言所有先前失败只有这一原因，也不推断 SDK 内部实现。

随后将 PID22182 明确旋转至 `port/w411dp`，新 slot `10b93d20-8971-4509-9998-1c455b430da3`、request `7187e34b-0c2d-403f-bb7c-337af4df01dc` 在 16:26:28 首次成功并曝光/paid。后台前后均为竖屏且 PID 不变，78.020 秒窗口后返回已有任务，`final-pixel-background-restored.png` 仍显示完整素材。原 slot 的 position/request/result/impression/paid 各 1；按 PID、epoch 时间窗和 Banner format 过滤公开 SDK 网络日志，后台 0 个新 Banner 请求。证据为 `final-pixel-background-actions.jsonl`、`final-pixel-background-summary.json`、`final-pixel-background-events.log` 及连续日志。没有以横屏进入、竖屏重建的旧对照代替同实例验证；旧 PID20104 的 356.269 秒窗口因方向变化不用于同实例恢复结论。

### 收尾范围与仍未完成项

1.4 的保留路径已由正式 Dialog/隐藏、父容器隐藏后脱离及恢复、最终 R8 前后台记录共同支撑。父容器交错窗口 138.834 秒和最终后台窗口 78.020 秒均无新平台 Banner 请求，恢复不新增本层加载。最终后台网络采集确实覆盖同 PID 的进入后台前和返回后请求，零请求结论不是因为网络标签未输出。选择保留并隐藏自有 SDK View；更全面的受控暂停原因交错仍属于 2.5，未随 1.4 勾选。

按用户要求，Gauss 子代理使用请求参数 `gpt-6-sol/high` 尝试自定义 Shadow 的受控 View 测试，两个实验仍未满足真实窗口资格，`loadAd` 捕获数为 0。测试失败后撤回其创建的文件，未修改生产代码。`controlled-view-shadow-test.log` 记录失败；随后 `post-controlled-baseline-tests.log` 全量测试 8 秒通过，恢复为 98 tests/0 failures/errors/skipped。没有把 Waiting 夹具当作加载、迟到回调或监听顺序的验证。

当前剩余任务按原因分为：

- 1.5/6.4：缺可核查且可控的关闭刷新广告位配置、真实 no-fill 后填充恢复场景。用户无法确认后台设置；已有断网恢复和自动刷新证据不能替代这些条件。
- 2.3/2.4/3.4/3.6：正式 SDK 创建／配置异常、加载在途与迟到交付、业务重入和所有回调出口异常的完整受控矩阵尚未跑通。已有事件层测试及设备成功不替代该矩阵。
- 2.5/6.2：多暂停原因交错及失败实例恢复仍缺完整受控覆盖；测试广告落地仅到 Chrome 首次启动页，没有网页正文验收。
- 2.6/6.3：最终横屏已通过；分屏和平台主动自然高度变化仍未验收。设备公开 task resize 命令执行后实际仍为 fullscreen，未形成多窗口，因此不记录分屏通过。
- 6.1：普通 Navigation、多 entry、Fragment/Activity 重建已有证据；预测返回取消尚未覆盖。

7.1—7.3 的接入文档、原方案当前状态、验证边界和任务证据已同步；`openspec validate add-banner-support --type change --strict --no-interactive` 与 `git diff --check` 通过。当前完成 24/35 项，保留其余 11 项，不归档、不提交、不推送或远程发布。构建日志、设备原始事件与截图保存在忽略的 `build/reports/banner/`，本文件记录复验命令及必要身份；原始 SDK 网络 URL/请求头不复制到仓库文档。

设备收尾：本任务创建的 `Pixel_7_Pro -read-only -no-snapshot -port 5582` 已通过 `emu kill` 正常关闭，三个专用 logcat 进程已停止；没有关闭或修改 emulator-5560、emulator-5580 及真机。只读模拟器未保存快照。所有子代理均已结束。

## 继续实施：真实窗口契约测试与零宽恢复修复

2026-09-29 后续继续使用当前工程，无须业务工程。新增 Debug 专用 `BannerContractActivity` 与测试 APK 的 `BannerContractInstrumentation`／`BannerContractApplication`，通过 Android 原生 Instrumentation 在真实附着、Resumed、可见且获焦的窗口运行。无新增测试框架、广告依赖升级或生产测试入口。ProbeApplication 未保留测试观察器，测试 Application 单独延迟初始化以覆盖尚未就绪的 owner。

公开 API 编译依据：[AdView](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/banner/AdView)、[BannerAd](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/banner/BannerAd)、[AdValue](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/common/AdValue)、[Android Instrumentation](https://developer.android.com/reference/android/app/Instrumentation)。锁定 1.2.1 使用显式 `getBannerAd()`，不能以网页或 Kotlin 属性语法代替实际编译结果。

### 新发现与修复

真实设备前两轮均在“初始隐藏→零宽→恢复宽度”超时：窗口资格正常、宽度 1440px、SDK child 已创建，但没有实际 load request。`device-contract-width-negative.log` 保留反例。子 View 从全局布局回调中加入后，首次布局等待没有继续；`AdsBannerView.load()` 现在仅对当前代次，在该布局回调结束后追加一次 `requestLayout()`。原失败用例随后通过，未增加 SDK 重试或周期计时器。既有不可见预挂载、布局后加载、收益先于可见与代次复核保持原契约。

### 可运行断言与边界

最终 `device-contract-final-run.log` 为 **12 组通过**（含断网可选项）。随后只加强“配置失败恢复／替换必须真实 filled”的断言，`device-contract-default-run.log` 为最终测试源的 **11 组默认用例通过**；断网用例源码未变化，复用前一轮通过证据。首次/后续完整广告加载都使用官方测试 ID。运行命令与可选断网开关见 [接入说明](../../../../docs/banner-integration.md#设备端契约回归)。这些设备用例不是 JVM 测试数的一部分。

| 用例 | 断言 |
| --- | --- |
| 初始化等待与旧 owner 替换 | 旧 owner 在初始化前销毁，零请求／零子 View；初始化后新 owner 只请求一次 |
| 真实首次加载 | 实际 LOAD_REQUEST 时 child 不可见且已布局；LOAD_RESULT 外部通知及 Ready 前监听已安装；实际曝光和收益到达 |
| 初始 inactive／hidden／零宽 | 不具资格时 SDK 零请求；零宽期间失活、再启用和恢复宽度后仅请求一次 |
| Loading 重入停用 | 回调停用并抛异常后不挂载、不请求；新周期仍能加载 |
| 实际请求中销毁 | SDK 调用后的 LOAD_REQUEST 回调中重复销毁；无旧曝光／回挂，替代 View 正常加载 |
| 实际请求中重启 | 旧 SDK child 脱离；新业务周期使用新 child，两个独立 slot/request，旧周期不覆盖新周期 |
| 回调配置异常 | 公开 SDK 接口替身在刷新监听 setter 抛异常；已持有 child 释放，交付对象销毁一次，同周期不自动重试，新周期恢复成功 |
| 过期对象交付 | 捕获自有回调入口的旧代次，完成新代次后再交付公开接口替身；只销毁过期对象，当前 child 和结果保持不变 |
| 全局／状态出口异常 | 事件、收益和 Loading/Ready/Inactive/Destroyed 状态回调抛异常；另一个出口、实际曝光、去重与最终清理继续完成 |
| 销毁后排队交付 | Ready 时通过已安装公开监听注入两次 paid 和曝光／点击，再立即销毁；已确认收益交付一次，旧 UI 事件不复活，child 保持为空 |
| 多原因暂停与高度变化 | owner 暂停、父层隐藏、脱离、重新挂载、宿主隐藏交错；只有全部恢复才可见，保持原 child／一次请求；原生 child 最小高度增加后容器随之测量且不加载 |
| 首次失败后暂停／恢复 | 关闭专用模拟器 Wi-Fi 后真实 NETWORK_ERROR；交错暂停后保留失败实例，网络恢复约 60 秒后由 SDK 自动曝光／paid，仍一次 position/request/失败 result |

配置异常与过期对象测试只反射本仓库的 `events`、`generation` 和 `loadCallback`，以注入受控输入；SDK 替身只实现公开 BannerAd 接口，不读取 SDK 私有实现。注入 paid／曝光是合约测试数据，不计作真实曝光或收入验收。自然高度用例是受控原生 child 测量，不声称广告服务器主动换了素材高度。

最终断网设备日志 PID9678；前一轮 PID8815 的明确反例/恢复记录为：slot `b8cd84ab-8ddb-4122-8490-34598bf90185`、request `d95f5f73-dc7c-4f0f-b481-a127de930c73`，16:58:01 NETWORK_ERROR，16:59:01 新展示 session `03241585-9329-4135-b127-76ba2a29e92d` 曝光/0 USD paid，恢复事件不补造本层 request。原始记录为 `device-contract-events.log`、`device-contract-offline-run.log`。最终 Wi-Fi=1、移动数据=0，恢复初始状态。

### 单元与构建

Avicenna 子代理只新增 `BannerCallbackIsolationTest.kt` 的两个事件层测试，逐一注入 POSITION/LOAD_REQUEST/LOAD_RESULT/IMPRESSION/PAID/CLICK/DISMISS 监听异常，以及失败恢复与收益出口异常。主会话审查并运行；本次子任务自身 session_meta／turn_context 核实为 **openai / gpt-6-sol / high**，已关闭。

`device-contract-final-ci.log`：完整单元测试、lint、测试 APK、Release/R8、本地发布成功（1 分 42 秒）；**100 tests/0 failures/errors/skipped**。核心／Compose lint 0 问题；smoke 45 Warning、0 Error/Fatal，既有两条 Pangle 缺失注解告警未变。后续仅测试 APK 断言增加，`device-contract-extra-build.log` 与 `device-contract-assertion-build.log` 均编译通过；生产源码未再修改。

以上证据补齐 2.3、2.4、2.5、2.6、3.4、3.6，当前暂为 30/35 项；后续设备场景与刷新配置缺口单独记录。早期 Robolectric 的未进入加载实验仍作为历史限制保留，不能覆盖本次真实窗口测试结论。


## 最终 Release/R8 真实分屏（6.3）

API33 Pixel 7 Pro、独立 emulator-5582、最终 Release PID10603。通过设备公开 WMShell `splitscreen moveToSideStage 311 1` 进入真实分屏，Settings task310 bounds `[0,0][1440,1543]`，Smoke task311 bounds `[0,1578][1440,3120]`。不是早期仍保持 fullscreen 的 task resize 尝试。

- Compose：slot `bfdf8abf-9c30-4347-bc1e-f688001dde7b`、request `3300b272-976f-49fa-ab67-7a29fa756cec`，17:06:33 filled / impression / paid，session `ba1ef0ce-3a45-441d-8e80-e2d030035aa5`。截图 `multiwindow-compose.png` 已查看，完整 Test Ad 位于底部独立区域，正文可滚动。
- Fragment：slot `18abec64-1fbd-4fdf-ba91-be9ab8cd9e08`、request `a1743cba-e7a4-43e7-9fb3-d882d1717d12`，17:09:36 filled / impression / paid，session `841b9763-8bfe-4d54-a91a-a87a7fc8ee87`。截图 `multiwindow-fragment.png` 已查看，完整素材和正文控件分离。

两个最终 slot 均通过 `check_banner_log.py` 的首次请求、曝光及收益关联检查。进入分屏时确有配置／owner 重建及中间取消的加载，不将其写成整个切换过程只有一个请求；最后稳定周期各只有一次本层加载。原始证据为 `multiwindow-active.txt`、`multiwindow-fragment-window.txt`、两个 `multiwindow-*-events.log` 与连续 `device-contract-events.log`。

结合前述旋转、边到边、IME、padding 窄容器及设备契约第11例的高度单独变化断言，勾选6.3。高度证据是受控改变本实例原生子 View 的 minimumHeight，断言宿主重新测量且不新增请求；没有声称观察到服务器主动更换不同高度素材。


## API36 预测返回取消与真实落地页（6.1／6.2）

先关闭本任务 API33 实例，再用现有 Pixel_9_Pro AVD 的只读副本在同一端口 emulator-5582 启动 API36（1280×2856、480dpi、16KB 系统镜像）。安装同一最终 Release/R8 APK，宿主 PID4004，系统 navigation_mode=2。未升级任何依赖或修改广告后台配置。`api36-home.png` 确认完整 Test Ad 和 Ready。

导航详情后验证真实边缘手势：系统 `input motionevent DOWN 1 1400` → `MOVE 430 1400`，公开 SystemUI dump 显示 EdgeBackGestureHandler `mAllowGesture=true`，BackPanelController `currentState=ACTIVE`；再 `MOVE 2 1400` → `UP 2 1400` 取消。`api36-back-progress-system.txt` 保存活动手势证据；`api36-back-canceled.png` 显示仍为 Detail / Ready，完整广告恢复。另一次提交 `input swipe 1 1400 900 1400 700` 确实返回 Home，并建立新页面周期，`api36-back-commit.png` 是 Home Loading 的过渡帧。

取消这一轮的 Detail slot `fcbde750-f146-428f-b2b3-2aea7b740db7`、request `260be5bd-2897-4381-aea4-bb335932e0ef` 于17:22:00 filled，展示 session `462ca5ba-b461-4923-91b5-2a3624810f09`。手势预览将 Home 组合入树，产生临时 slot `5d80295c-9930-4a32-9c5c-dd4588462ab2` 的 position，但因 owner 不具备加载资格，整个取消过程该预览零 load_request。Detail 未新建 slot／request，未误销毁重载。不能把只看截图不动误认成手势取消；这里同时有系统 ACTIVE、预览 position 和提交手势的对照。

用户已授权点击测试广告。随后点击同一 Detail 的 Test Ad OPEN，收到同 session 的 click；Chrome 实际加载 developers.google.com/admob 正文，`api36-landing-loaded.png` 可见 Google AdMob 内容及浏览器通知提示。选择 No thanks，`api36-landing-content.png` 保存正文；未登录、未授权通知、未修改广告后台。系统返回后 `api36-return.png` 和 UI XML 显示原 Detail Ready、完整广告。到该检查点，原 slot 的 position/request/result/impression/paid/click 各1，close=0，曝光／收益／点击同 session。`check_banner_log.py` 通过，额外断言确认取消预览零加载；结构化计数在 `api36-return-summary.json`，连续事件为 `api36-events.log`。没有把落地页返回映射成 Banner 本体关闭。

API36 日志里其他预装应用 StepCounter 的独立 PID 出现启动崩溃；不属于 PID4004 测试宿主，未修复或操作该应用，不据此归咎 Banner。本次烟测不代表整套第三方 SDK 的16KB兼容认证。

结合前述普通导航、多 entry、快速导航、Fragment／Activity重建及暂停矩阵证据，勾选6.1／6.2。当前33/35。剩余1.5／6.4已有首次断网同实例恢复、刷新失败保留和连续两次刷新身份记录，仍缺可确认刷新开关的关闭对照及首次无填充后的可控恢复；官方测试 ID 的观察不能证明业务后台配置，不将这些任务勾选或自动归档。

收尾：`openspec validate add-banner-support --type change --strict --no-interactive` 与 `git diff --check` 均通过。本任务独立只读 emulator-5582 已关闭；未提交、推送、远程发布或归档，保留现有工作树变更。

后续决策（2026-09-29）：用户批准1.5／6.4剩余验证延期，登记为 BANNER-REFRESH-01；以后续正式广告位接入为补验触发点。原缺失条件与历史记录保留，未将其改写为通过。

归档结果（2026-09-29）：用户明确授权归档，主规格已合并16项新增要求；变更移至2026-09-29-add-banner-support。任务保持33项完成、2项批准延期，债务及补验条件随归档保留。归档前文档一致性问题已修正。
