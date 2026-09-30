# 2026-09-28 宿主与真实 SDK 验收

首轮新增宿主验收接入；续验追加宿主边界检查和一项 JVM 回归测试，均未修改广告库生产 Kotlin。未经提交或推送。

## 环境与接入范围

- 广告库：`/Users/jiaoyun/od-fz/ads-mediation` 当前未提交源码，Next-Gen 1.2.1 / TopOn 6.6.22.3。
- 多 Activity：用户指定的 `/Users/jiaoyun/od-fz/od_parking`，localDebug，独立测试 runner 跳过生产 Application 的自动广告与统计初始化。使用原 MainActivity / SettingsActivity 和 local 广告配置。AdMob 官方 demo ID；TopOn 使用工程现有开发配置，返回素材未全部带测试标记，操作仅关闭/跳过，没有点击推广或安装。
- 单 Activity：用户授权在 `/Users/jiaoyun/touka/Music` 增加临时调试接入。新增可选 `:ads-acceptance`，复用 MusicTheme / Navigation Compose，独立包 `com.player.music.adsacceptance`，仅 debug。未迁移现有 Music 业务广告，未加载 ReMax Bill 或生产统计。测试 ID 来自 `scripts/internal.gradle`。
- 设备：`emulator-5556`、`emulator-5560`，均 Android API 33；未使用已连接的实体手机。
- 所有运行日志、截图及构建输出：广告库 `build/reports/host-acceptance/`（本机构建产物，不纳入版本控制）。截图只证明当时画面，不替代回调日志。

## 构建与复现

od_parking 的旧收入字段与当前库不兼容，已把 payload 字段映射更新为 `placementId` / `impressionId`，默认依赖对齐现有稳定版本 1.0.5；本轮验证实际使用 composite build 的当前库源码：

```sh
# od_parking 根目录
./gradlew --include-build /Users/jiaoyun/od-fz/ads-mediation \
  -PadsAcceptanceRunner=com.example.lcb.app.AdsAcceptanceRunner \
  :app:assembleLocalDebug :app:assembleLocalDebugAndroidTest :app:testLocalDebugUnitTest
adb -s emulator-5560 shell am instrument -w -r \
  -e class com.example.lcb.app.AdOpportunityAcceptanceTest \
  -e provider admob -e scenario backstack -e format INTERSTITIAL \
  com.leafmotivation.quizguessoncolor.test/com.example.lcb.app.AdsAcceptanceRunner
```

构建成功，23 项宿主 JVM 测试通过。未单独验证发布仓库中的 1.0.5 AAR。

Music 的 AGP 9.1.0 与库 AGP 8.13.1 不能直接组合构建。使用 `publishReleasePublicationToMavenLocal` 配合 `-Dmaven.repo.local=<广告库>/build/acceptance-maven`、版本 `1.0.0-acceptance`，只在本地生成当前源码 AAR/POM；Music 通过该目录读取 POM 与依赖。未上传仓库、未改全局 Maven 缓存。命令详见 Music 的 `ads-acceptance/README.md`，最终构建日志 `music-build-final.log`。

## 多 Activity 与平台展示

以下每个 PASS 均为实际 instrumentation 的 `OK (1 test)`，不是单元测试模拟，共 18 次通过：

| 场景 | 证据文件前缀 | 结果与范围 |
| --- | --- | --- |
| AdMob 冷缓存取消、返回栈、重建、退后台、1ms 超时 | `admob-{cancel,backstack,recreate,background,timeout}` | 5 次通过；旧机会一次结束，真实加载继续，回到宿主后没有旧展示事件 |
| TopOn 同上 | `topon-{cancel,backstack,recreate,background,timeout}` | 5 次通过 |
| AdMob A 取消后 B 复用插屏 | `admob-reuse-v2` | 1 次通过，匹配原缓存 response ID，展示/收入/关闭正常 |
| AdMob 开屏 | `admob-app-open` | 1 次通过 |
| TopOn 开屏、插屏、激励 | `topon-{app-open,interstitial,rewarded}` | 3 次通过；激励有真实 REWARD_EARNED、收入与最终 rewardEarned=true |
| 竞价开屏、插屏、激励 | `bidding-{app-open-retry,interstitial,rewarded-retry}` | 3 次通过；本次均 TopOn 先就绪并胜出，不能外推为两家同时就绪的价格比较验收 |

最新 show 场景将机会期限设为 1 秒，观察 impression 后等待 1.5 秒，确认等待期限不结束已经交接的广告；此时 cancel 不改写结果，竞争机会返回 `another_full_screen_ad_showing`。TopOn 开屏及三个竞价格式执行了这个版本。不同 SDK 展示期间宿主出现 RESUMED / PAUSED / STOPPED / DESTROYED，最终合法关闭与奖励仍能交付。

### 保留的失败与限制

- `admob-reuse.txt`：最初测试在 isReady 后立即取加载事件，异步通知尚未到达，导致测试前置快照错误。修正为先等 filled 通知，`admob-reuse-v2` 通过。不能把前一次失败归因于库没有缓存复用。
- `admob-rewarded.txt`：no_fill 后机会正常 wait_timeout；`admob-rewarded-retry.txt` 已展示且有 paid，但测试视频进度停留，120 秒内无关闭/奖励，失败。未证明 SDK 或素材停滞的根因，AdMob 激励完整闭环仍未验收。
- `bidding-app-open.txt`：真实出现 `show_callback_timeout`；后一次 `bidding-app-open-retry` 通过。重试成功不消除首次失败，该间歇问题未定位。
- `bidding-rewarded.txt`：奖励到达，但测试未及时完成手工关闭，120 秒关闭等待失败；结束清理附近才收到 dismiss。重试在当前关闭按钮出现后操作，`bidding-rewarded-retry` 通过。首次结果仍保留，不算 SDK 正常关闭通过。
- 同步 SDK 抛异常、最终就绪到 show 的过期窗口、真实事件重入撤销、无效/替换 TopOn 容器、旧自动开屏与旧立即入口的全部设备兼容组合尚未完整覆盖。

## Music 单 Activity

通过真实 UI 按钮、系统 Back 和屏幕左边缘返回手势操作；没有新增或运行 Compose UI Test、Espresso 或截图回归测试。`check_trace.py` 校验保存的日志，不驱动 UI。

| 验收 | 可复核证据 | 观察 |
| --- | --- | --- |
| 普通重组、更新最新回调 | `music-recompose-v2.log`，PID 6249 | 只有一次 CREATE，ready=false；重组及 callback=1 更新后约 4014ms 得到 wait_timeout，原期限 4000ms 未重置 |
| 保留 Tab 快速 A→B→A | `music-tab.log`，PID 6713 | 两次切换间隔约 56ms；原机会在第一次切换时取消，只回调一次；两 Tab 都没有 DISPOSE；后续真实 LOAD_RESULT 无旧 POSITION/IMPRESSION |
| Navigation 同路由不同 entry、更新 B 回调、系统 Back | `music-navigation-final.log`，PID 7845 | A 约 108ms 取消；B 有独立 entry ID，callback=1；返回 A 保留 callback=0，没有重启机会。最终版本另以 currentBackStackEntry.id 约束场景 |
| 边缘手势离开 B | `music-gesture.log`，PID 7374 | B 的冷缓存机会约 289ms 取消，B 被 dispose，返回 A；后续加载完成没有旧展示 |
| 明确新触发复用缓存 | `music-show.log` + `music-final-runtime.log`，PID 7374 | 机会 2 从 ready=true 开始，impression response 与之前加载的 `DFC6atidIdykvr0Pxa-G6A4` 相同；paid 后最终 Dismissed，约 44739ms，跨过原 8000ms 期限 |

四份生命周期日志分别以 `check_trace.py recompose/tab/navigation/gesture <文件>` 校验通过。最终 current-entry 约束增加后重新构建并重复 Navigation 检查通过，其余对应日志是同一接入在增加该约束前的设备证据。没有宣称实际 Music 业务页面或 Fragment 已完成迁移/验收。

## 任务状态

6.2、6.3 以以上真实宿主证据完成。2.3、2.4、2.5、4.1、6.4 继续未完成；合计 16/21。库原有 58 项 JVM 测试、Debug 构建与 lint 证据沿用 `verification.md`，与本轮设备验收分开记录。

AdMob 已 poll 且未 show 的插屏/激励对象仍缺少当前 Next-Gen 1.2.1 的可验证有效期依据。重新查看官方预加载/开屏文档也未解决该限制；未猜测 TTL、未逆向 SDK，任务 2.4 不能关闭。

收尾验证：OpenSpec strict validate 通过，三个工程 `git diff --check` 通过；没有提交、推送或归档。

## Apply 续验：展示准备边界

继续使用同一份广告库源码，扩展 od_parking 的 opt-in instrumentation；没有修改库生产 Kotlin、Music 或 SDK 配置。新增 `scenario=boundary` 和 `scenario=final_deadline`，仍按每次独立进程运行。构建日志为 `boundary-build.log`、`final-boundary-build.log`。

| 新增验收 | 设备与证据前缀 | 结果 |
| --- | --- | --- |
| 两平台 × 三格式，POSITION 事件回调重入 cancel，以及事件处理跨过 1 秒期限 | AdMob：5556；TopOn：5560；`boundary-{admob,topon}-{app-open,interstitial,rewarded}` | 6 次 `OK (1 test)`；每次准备只有一份 POSITION、SHOW_FAIL 和机会结果，没有 impression/paid/reward，缓存仍 ready |
| TopOn 开屏未挂载容器、添加子容器时脱离、取消、抛异常 | 包含在 `boundary-topon-app-open` | 4 个额外检查通过；临时子容器为 0，真实 SDK 缓存保留 |
| 两平台 × 三格式，在最终场景检查中跨过期限 | `final-{admob,topon}-{app-open,interstitial,rewarded}` | 6 次 `OK (1 test)`；准确返回 wait_timeout，无 impression/paid/reward |

合计新增 12 次通过，覆盖 22 个边界检查，并分别以新机会再次进入 POSITION 后取消，证明旧 owner 已释放。与前轮 18 次通过分开计数。

`final_deadline` 使用测试专用 `isSceneValid` 延迟：POSITION 后第一次检查位于 Provider `canShow`，第二次位于 `commit`，只在第二次阻塞 1100ms，并断言次数为 2。按当前源码调用顺序，AdMob 已完成 poll，TopOn 已完成准备。此处验证的是**机会期限**，不声称模拟了 SDK 广告自身过期。没有反射、替换或逆向 SDK。AdMob 开屏中止后仍 ready；插屏/激励有效性未知，测试只要求不展示旧机会并能等待下一缓存，不把补缓存当作已 poll 对象复用证明。

公开依据复核：[Next-Gen rewarded 预加载](https://developers.google.com/admob/android/next-gen/rewarded)说明 SDK 队列会处理过期，并建议准备展示时才 poll；[单次加载文档](https://developers.google.com/admob/android/next-gen/rewarded/single-load)也未给出已 poll 对象的明确有效期。仍不能据此给 Next-Gen 1.2.1 插屏/激励自行填入一小时 TTL，2.4 保持未完成。

激励对照失败继续保留：`admob-rewarded-continue` 在 API 33 再次出现 impression/paid 后 120 秒关闭等待超时；`admob-rewarded-api36` 在新安装的 emulator-5580（API 36）看到双素材播放进度，但 120 秒内仍未完成，测试清理时才收到 Dismissed、rewardEarned=false。API 36 的前半段 logcat 被环形缓冲覆盖，完整 instrumentation 失败输出及截图仍在，不将该日志称为完整事件序列。随后仅将人工关闭等待窗口延长为 240 秒做有界对照，业务机会期限仍为 1 秒。

最后一次 `admob-rewarded-api36-extended` 返回 no_fill，50 秒内缓存未就绪，未进入展示及延长后的关闭窗口；不再继续重试。本轮不能确认激励停滞的根因，也不能确认延长关闭等待解决了问题。

新增 JVM 回归覆盖取消/超时相邻执行的首结果优先，以及 SDK 交接后失败结果不被迟到成功回调改写。`./gradlew testDebugUnitTest --console=plain` 通过，XML 汇总 59 项、0 失败、0 错误、0 跳过，日志 `continuation-jvm.log`。生产源码没有变化，本轮未重复原已通过的 lint。状态仍为 16/21，未完成项细化在 `tasks.md`。

续验收尾：OpenSpec strict validate 通过；广告库与 od_parking 的 git diff --check 通过；12 份成功 instrumentation 输出及配套日志存在。全部测试与日志采集进程已结束，未提交、推送或归档。


## 2026-09-30 Apply 续验

当前源码与 `od_parking` 的 localDebug opt-in runner 组合构建；Next-Gen 1.2.1、TopOn 6.6.22.3。设备为 `emulator-5560`（API 33）和 `emulator-5580`（API 36），未操作实体设备。本轮日志、instrumentation 输出及截图位于 `build/reports/fullscreen-apply-20260930/`；连续事件日志为 `device.log` / `api36-device.log`，未清空原设备 Logcat。

复用 `app/src/adsAcceptance/java/com/example/lcb/app/AdOpportunityAcceptanceTest.kt`，只修改 opt-in 测试：补 Banner 排除分支以恢复编译；将 deadline/final_deadline 的旧超时断言对齐已批准的新规则；增加旧三个立即入口的冲突拒绝检查和双平台真实缓存参与竞价断言。宿主生产配置及业务源码未改动。旧立即入口在广告暂停 Activity 时可能优先返回 `activity_not_resumed`，测试保留这一既有顺序，断言立即失败；新等待入口明确返回 `another_full_screen_ad_showing`。

每个成功项都有 `OK (1 test)` 和 PASS 日志：

| 设备与测试 | 证据文件 | 已验证行为 |
| --- | --- | --- |
| API 33 / 竞价插屏 cold_wait | `bidding-interstitial-cold-wait.txt` | 冷缓存固定 5 秒等待，约 5041ms 后只有一次 wait_timeout，sessionId=null，无 POSITION/IMPRESSION；不代表有候选时的截止兜底 |
| API 33 / TopOn 开屏 boundary | `topon-app-open-boundary.txt` | 事件重入取消、POSITION 中延迟 1100ms 后正常展示/收入/关闭、未挂载、添加时脱离/取消/抛异常共 6 个边界；临时子容器清理、缓存保留及下一 owner 可取得资格 |
| API 33 / AdMob 开屏 final_deadline | `admob-app-open-final-deadline-close.txt` | 最终场景检查延迟 1100ms 跨过原 1 秒等待期限，仍产生真实 IMPRESSION/PAID/收入、正常关闭；后续机会取消证明 owner 已释放 |
| API 36 / AdMob 激励 show | `admob-rewarded-api36.txt` | 交接后跨原等待期限、宿主 PAUSED、cancel 无效、三个旧入口和新等待入口冲突拒绝；真实奖励 coins:10、正常 DISMISS、唯一 rewardEarned=true 且携带 sessionId 的最终结果 |
| API 33 / 竞价插屏 both_ready | `bidding-interstitial-both-ready.txt` | 两家真实 filled 后进入决策；BID admob=true、topon=true、winner=TOPON；单次展示/收入/正常关闭及新旧入口互斥。只证明本次真实候选选择，不代表所有报价顺序/未知价组合 |

API 36 激励日志的奖励时刻为 21:56:14，关闭及最终结果为 21:57:01，最终 PASS 为 21:57:02（Asia/Taipei）。素材包含双广告流程；点击已显示的 Next Ad 后进入素材结束页，收到奖励后通过返回关闭。没有点击 Learn More、推广或安装链接。TopOn 仍使用宿主既有开发配置，素材未全部带测试标记，操作限于 Skip/关闭。

保留失败，不作为通过证据：

- `admob-rewarded-show.txt`：API 33 官方测试激励返回 NO_FILL，50 秒 cache-before-display 等待失败；没有进入 SDK 展示，不能推断奖励处理有缺陷。
- `admob-app-open-final-deadline.txt`：本轮新增测试最初把旧入口合法的 activity_not_resumed 误判为失败；修正断言后重跑，保留原输出。
- `admob-app-open-final-deadline-recheck.txt`：产生真实展示和收入，但未及时操作关闭，240 秒 dismissal 等待失败；测试清理附近的 Dismissed 不算正常关闭通过。带及时关闭操作的 `...-close.txt` 后续通过，不据此归因于 SDK 关闭回调故障。

### 用户批准的 2.4 策略

用户在本轮明确选择“接受当前保守策略，调整 2.4 验收”。当前策略是：开屏仅在响应 ID 可关联 Preloader 启动时间且保守年龄小于官方四小时上限时保管复用；插屏/激励及有效性未知的已取出对象解绑回调后销毁。启动时间是加载时刻的保守下界、广告年龄的保守上界，可能提前丢弃，不是精确原加载时间。未知价格保持 null，SDK 队列和在途加载不被取消。

只读原生子代理复核了 Google 官方 [插屏指南](https://developers.google.com/admob/android/next-gen/interstitial)、[激励指南](https://developers.google.com/admob/android/next-gen/rewarded)、[开屏有效期](https://developers.google.com/admob/android/next-gen/app-open)及公开 [AdPreloader API](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/common/AdPreloader)。当前公开文档仍未提供已 poll 插屏/激励对象的 TTL 或对象级 isValid；持续更新页面也不等于固定的 1.2.1 契约。没有逆向 SDK、猜测 TTL 或升级依赖。子代理实际 provider/model 路由无法独立核验，记录为未知。

当前完成 25/29。2.4 由用户批准的新验收要求与源码/JVM 证据完成；4.1 的真实 AdMob 激励缺口已补齐。2.3、2.5、6.4 及后续等待策略/业务 loading 验收仍有上述未覆盖项，不宣称全部真实 SDK 验收完成。

## 2026-09-30 HealthTracker 与 SDK 后续验收

用户指定 `/Users/jiaoyun/od-fz/HealthTracker` 为业务 loading 宿主。证据目录 `build/reports/fullscreen-apply-20260930-resume/`；连续 SDK 日志为 `device.log`，业务计时日志为 `health-business.log`。本节之后当前进度为 **26/29**，2.3、2.5、6.4 仍未完成。

### 实际源码与构建

od_parking 继续使用当前根库 composite build，API 33 / emulator-5560，SDK 版本同上一节。HealthTracker 在 API 36 / emulator-5580 验收；其 settings 原引用不存在的 `73e5/ads-mediation` 工作树。只修正为已有 `/Users/jiaoyun/.codex/worktrees/native-loading-retention/ads-mediation`，保留两项 dependency substitution 和 Native 业务改动，没有将整个 Native 宿主切到根库的 Banner 分支。

五份公共/内部全屏核心文件逐字一致的核对见 `health-fullscreen-source-comparison.txt`；Provider 全屏函数一致。生命周期修复同步到两处；Native 版本原有 NativeInteractions 前后台调用保留。Native 自动开屏另有交互抑制，因此 HealthTracker 的明确 WhenReady 验收不证明两个库的全部行为等价，也不作为旧自动开屏验收。

HealthTracker 初次构建因旧路径失败（`health-build.log`），修正后 Debug 与 AndroidTest 构建成功（`health-build-fixed.log`）。计时只使用临时 DEBUG 日志，收尾已移除，AppCompatExt 与加日志前备份一致；最终 `:app:assembleDebug` 成功（`health-final-build.log`），最终 APK 已装回，SHA-256 见 `health-final-apk-sha256.txt`。设备录屏对应包含临时计时日志的修复后 APK，其摘要单独保存在 `health-lifecycle-traced-apk-sha256.txt`，不混用最终 APK 摘要。

### 新增真实 SDK 结果

本目录共 15 份 SDK/生命周期 instrumentation 成功输出；另有 1 份控件 instrumentation 成功输出。每份成功均有 `OK (1 test)`，重复复核独立计数、不当成不同业务场景。

| 场景 | 输出文件 | 证据与边界 |
| --- | --- | --- |
| TopOn 三格式准备窗口宿主替换 | `topon-{app-open,interstitial,rewarded}-host-replacement.txt` | 就绪后第二次场景检查使原宿主跳转并结束；唯一 activity_not_available，无 impression/paid/reward，缓存保留，开屏临时容器清理，新宿主可取得 owner。`topon-app-open-host-replacement-probe-fixed.txt` 是探针修正后的再次复核 |
| AdMob 三格式最终宿主替换 | `admob-app-open-host-replacement.txt`、`admob-interstitial-host-replacement-probe-fixed.txt`、`admob-rewarded-host-replacement.txt` | 最终检查拒绝，唯一结果、没有 SDK 展示事件，新宿主 owner 可取得；不代表自然过期/许可撤销/SDK 同步异常已复现 |
| 旧立即入口成功 | `topon-app-open-legacy-show.txt`、`admob-interstitial-legacy-show.txt` | 真实展示、收入、关闭及共享冲突拒绝；尚未覆盖旧入口全部平台/格式组合 |
| 冷缓存截止兜底 | `bidding-app-open-cold-wait.txt` | 5 秒时只有 TopOn 缓存，约 5056ms 决策，真实展示/收入/关闭；AdMob 后续加载完成不产生第二次展示 |
| 等待后到候选后提前竞价 | `bidding-app-open-later-bidder-retry.txt` | 请求 22:49:40，TopOn filled 22:49:41，AdMob filled 22:49:55；BID 两方 available=true，winner=TOPON，约 15783ms 交接，早于 45 秒截止；正常关闭、唯一结果 |
| 等待明确失败后提前竞价 | `bidding-rewarded-failed-bidder-retry.txt` | 请求 22:51:10，TopOn filled 22:51:15，AdMob no_fill 22:51:29；BID 22:51:30，约 21041ms 展示，早于 45 秒截止；IMPRESSION/PAID、REWARD_EARNED、DISMISS、唯一 rewardEarned=true，正常测试通过 |
| 修复后生命周期 | `lifecycle-{late_init,background,backstack}-fixed.txt` | 晚初始化可见新宿主不再被误判后台；退后台和原 Activity 留在返回栈的旧机会仍结束，结果不复活 |

45 秒仅为两次等待时序验收的显式覆盖参数；库默认值和 HealthTracker 7 秒业务参数均未改变。测试广告只操作显示的 Skip/X/返回，没有点击推广或安装链接。

### HealthTracker 实际业务 loading

按真实 UI 创建一条 4.2 mmol/L、22:26 的临时血糖记录并进入 HealthDetailAct，专家建议倒计时结束触发 `RV_BloodSugar_Note`。控制无缓存条件下，22:43:33.771 发出 timeoutMillis=7000 请求，22:43:40.776 唯一结果为 wait_timeout、rewardEarned=false、sessionId=null，elapsedMillis=7005。

`health-loading-fixed.mp4` 连续记录倒计时、灰色禁用 Loading 按钮和超时后的页面；`health-loading-active.png` 为约 11.8 秒画面（screenrecord 静态画面帧较稀疏），`health-loading-ended.png` / `health-timeout-ended-ui.xml` 记录收尾。没有把仅控件测试 `health-loading-widget.txt` 代替真实业务验收。现有 AdviceUnlockPolicy 允许无广告超时解锁，因此录屏中的解锁不是 SDK 奖励证据；真实奖励证据在上述独立竞价激励测试。

先前同一可见详情页分别在 1ms、9ms 被 app_not_in_foreground 拒绝，见 `health-business.log` PID 12475。根库独立 late_init 在修复前失败，`late-init-before-fix.txt` 断言期望 opportunity_cancelled、实际 app_not_in_foreground；修复后同场景通过。这与晚注册监听后旧实例 stop 扣掉新宿主的计数缺陷一致；没有逐条记录原 HealthTracker 当次生命周期回调来声称完整还原那一次的精确顺序。

收尾已通过 UI 删除本次唯一测试记录，`health-clean-ui.xml` 确认 No records yet；恢复 airplane_mode_on=0 和原 Wi-Fi 启用状态。只停止本次四个 logcat 采集进程，未关闭模拟器或 ADB server；保留已有业务数据与原 dirty 工作树。

### 保留失败与尚缺条件

- `admob-interstitial-host-replacement.txt`：原拒绝正确，但后续探针误要求销毁未知有效性离队对象后 5 秒内再次填充；改为新 owner 获准后主动取消，不依赖网络填充，修正后通过，生产策略未改。
- `bidding-app-open-later-bidder.txt` 与 `bidding-rewarded-failed-bidder.txt`：均观察到正确等待和展示，但人工未在 240 秒内关闭，instrumentation dismissal 等待失败。清理附近的奖励/关闭不算通过；及时关闭的 retry 输出独立通过，不据此认定 SDK 关闭回调故障。
- `late-init-before-fix.txt` 是真实根因回归的修复前失败；对应 green 输出单独保存。
- SDK show 同步异常仍无真实可重复触发条件；自然到期与真实 UMP 许可撤销尚未取得设备证据，不能以机会 wait_timeout、宿主失效或 JVM 模拟替代。旧自动开屏/旧立即入口全部组合、历史竞价开屏 show_callback_timeout 的独立调查仍待完成，因此保留 2.3、2.5、6.4 未勾选。

收尾验证：OpenSpec strict validate 通过；根库、od_parking、HealthTracker、Native 工作树 git diff --check 均通过。生命周期两份源码仅保留原 NativeInteractions 差异。未提交、推送或归档。

## 2026-09-30 用户批准的验收优先级调整

用户要求保留可捕获的失败处理，不让取出至 show 的极短异常窗口阻塞整体进度。2.3、2.5 按已有实现、JVM 回归、主要真实设备场景证据完成，当前 28/29。两家 SDK 同步异常、该窗口内广告自然过期与真实许可撤销仍未设备实测，转为非阻塞补充验证；上述原失败和证据缺口保留。本轮无新增设备测试、不把验收豁免记作测试通过。6.4 的常规兼容性覆盖和历史 show_callback_timeout 调查继续单独记录，尚未勾选。

## 2026-09-30 新接口主流程验收完成

用户选择直接使用新接口验证。本次设备验收统一使用三个 show...WhenReady，旧立即入口和旧自动开屏全组合不再作为本次完成条件；原 API 保留且已有编译/JVM/冲突兼容性证据保留。HealthTracker 的插屏/激励封装、启动开屏和回前台开屏已经使用新接口，autoShowAppOpen=false，不需要重复迁移。

核对下列现有输出，每份均有 OK (1 test)。本表不表示又运行 9 次，也不以旧日期正常展示证据替代 2026-09-30 新竞价等待时序和 loading 证据，后者仍见上文。

| 平台 / 格式 | 新接口代表性成功输出（相对 build/reports） |
| --- | --- |
| AdMob 开屏 | `fullscreen-apply-20260930/admob-app-open-final-deadline-close.txt` |
| AdMob 插屏 | `host-acceptance/admob-reuse-v2.txt` |
| AdMob 激励 | `fullscreen-apply-20260930/admob-rewarded-api36.txt` |
| TopOn 开屏 | `fullscreen-apply-20260930/topon-app-open-boundary.txt` |
| TopOn 插屏 | `host-acceptance/topon-interstitial.txt` |
| TopOn 激励 | `host-acceptance/topon-rewarded.txt` |
| 竞价开屏 | `fullscreen-apply-20260930-resume/bidding-app-open-later-bidder-retry.txt` |
| 竞价插屏 | `fullscreen-apply-20260930/bidding-interstitial-both-ready.txt` |
| 竞价激励 | `fullscreen-apply-20260930-resume/bidding-rewarded-failed-bidder-retry.txt` |

原 `host-acceptance/bidding-app-open.log` 中 19:16:44.721 已有唯一 show_callback_timeout 失败结果，紧接着发出新 LOAD_REQUEST。源码的 finishAppOpenFailed 负责结束当前 attempt、移除 activeAppOpen 和临时容器、回调失败并补加载。该日志没有证明 SDK 未及时回调的具体原因，正常重跑也不证明根因已解决；按用户“不为极小概率事件影响进度”的要求保留为非阻塞跟踪。原失败文件保留。

当前 **29/29**，6.4 以用户批准的新接口主要流程范围完成。无新增设备运行、无生产或测试源码改动；沿用已有构建/JVM/设备证据，未提交、推送或归档。
