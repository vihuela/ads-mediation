# 第一阶段实施记录

日期：2026-09-28。基线：`codex/1.0.6`，`a99eaaa`。未提交、未推送；保留原有未跟踪的工具配置目录。

## 已实现的路径

- `Ads` 新增开屏（有/无容器）、插屏、激励的 `show...WhenReady`，返回具体类 `AdDisplayOpportunity`。激励继续返回 `AdRewardResult`。
- `DisplayOpportunityController` 使用调用时捕获的 `elapsedRealtime` 和最多 100ms 检查；等待初始化、缓存及首次窗口就绪共用同一期限。普通 Compose 重组不参与机会的创建与计时。
- `FullScreenShowGate` 在各 Provider、立即展示、竞价和自动开屏之间共享 owner；等待与 SDK 展示分开，释放必须匹配 owner。等待阶段按固定期限决策候选；最终交接重新检查许可、场景、原 Activity、缓存和容器，候选准备跨过等待期限不构成超时。
- 取消仅结束机会，移除检查、生命周期监听和页面引用，保留平台加载；TopOn 每次机会最多主动 ensure 一次，新机会的展示准备失败不再执行旧入口的额外补加载。
- 新机会只在有候选后建立展示会话，提前登记 sessionId，再发 position/bid 事件，处理事件内取消。纯等待结束没有展示事件。
- 原 Activity 暂停、销毁、其他 Activity 恢复及应用退后台会终结等待。单 Activity 的页面身份和主动离开由宿主传入场景谓词并调用 cancel；文档包含 Navigation/Fragment、保留 Tab 与 Compose 示例。
- 旧 `autoShowAppOpen` 配置及触发流程保留，新接入文档明确关闭自动模式，由宿主触发。

## 已取得的验证证据

运行 `./gradlew testDebugUnitTest assembleDebug lintDebug` 成功。58 项 JVM 测试，0 失败、0 错误、0 跳过；其中 15 项是新增的机会、所有权、事件重入和缓存有效性测试。库模块 lint 0 issue；smoke 宿主 lint 有 25 条警告（版本提示、图标/备份配置等），没有 lint 错误。

可复核的本地输出（构建目录未纳入版本控制）：

- `build/reports/fullscreen-phase1-build.log`
- `build/test-results/testDebugUnitTest/TEST-com.cashcraft.ads.mediation.internal.DisplayOpportunityControllerTest.xml`
- `build/reports/tests/testDebugUnitTest/index.html`
- `build/reports/lint-results-debug.xml`
- `r8-smoke-app/build/reports/lint-results-debug.xml`

新增测试使用可控时钟和调度器，覆盖：

- 主线程排队计入期限、到期边界及迟到检查。
- 初始化等待/失败、场景失效/异常、运行中许可撤销。
- 竞价仅一家就绪时可在整体初始化完成前交接；仍拒绝许可不足、未初始化及失败，不重置原始期限。
- A 超时后共享加载仍在进行，B 加入同一加载；检查不重复 ensure。
- 等待/展示冲突、同 owner 交接和旧 owner 不能释放新占用。
- 取消清理先于结果、结果抛异常不影响后续机会、重入取消只结束一次。
- position/bid 事件内取消；奖励结果保留真实展示 sessionId，纯等待没有 sessionId。
- 交接后跨过期限或宿主暂停不会改写模拟 SDK 结果。
- 待复用对象不重新计龄，未知年龄/有效期不可复用，未知价格保留 null。
- TopOn 已知旧 showId、旧主线程排队回调被拒绝；缺少 ID 的合法回调不被一律丢弃。

本节是源码、JVM 和构建证据；后续真实广告与 Android/Compose 宿主验收单独记录于 [host-acceptance.md](host-acceptance.md)。

构建初次受 Mintegral 17.1.71 下载阻塞。已从配置的官方 Maven 地址取得同版本 AAR，SHA-1 与服务器返回值 `e20e4ae8b2a965d50ca366e066d600083ccf4341` 一致，之后标准 Gradle 命令成功。依赖版本及构建配置未修改。

## 尚未完成的验证与限制

### AdMob 已取出、尚未展示的对象（任务 2.4）

`responseId` 可准确匹配预加载回调和取出的广告；回调顺序不代表 poll 顺序。公开 API 没有原始加载完成时间。实现使用对应预加载启动时间作为保守下界，绝不以取消或重试时间重置年龄。

开屏采用官方四小时上限，加载时间下界不可靠或保守年龄已到期即丢弃。这个策略可能提前丢弃进程较晚加载的广告；它不代表取得了精确加载时间。

Next-Gen 1.2.1 没有可验证的插屏/激励离队对象有效期依据，不能直接套用传统 GMA 的一小时规则。最终检查中止时，这两类已取出对象解绑回调后销毁；SDK 自己的预加载和队列仍保留。纯等待取消尚未 poll，不受这个狭窄窗口影响。2026-09-30 用户已批准以此保守策略替代原完整离队对象复用要求；更新后的任务 2.4 以源码和 JVM 证据完成，真实 SDK 验收仍单独跟踪。

参考：[ResponseInfo](https://developers.google.com/admob/android/next-gen/reference/kotlin/com/google/android/libraries/ads/mobile/sdk/common/ResponseInfo)、[App Open](https://developers.google.com/admob/android/next-gen/app-open)、[Interstitial](https://developers.google.com/admob/android/next-gen/interstitial)、[Rewarded](https://developers.google.com/admob/android/next-gen/rewarded)。公开文档会更新，当前 1.2.1 依赖实际编译通过；没有逆向 SDK 或借用未公开字段确认有效期。

### Provider 联调（任务 2.3、2.5、4.1）

交接、同步异常清理、容器归属检查和结果映射已经实现。正常 TopOn 三种格式、AdMob 开屏/插屏及竞价已有设备证据；AdMob 激励闭环与各异常窗口仍有缺口，详见宿主验收记录。

TopOn 使用入口 session 快照和最近 32 个 showId 的归属阻止可识别的旧回调影响新 owner。缺少或无法识别展示身份的 SDK 回调仍依赖 SDK 自身的每次展示回调顺序；不能宣称已证明任意异常迟到回调的归属。历史 ID 仅保存字符串，不保存页面回调。

### 宿主验收（任务 6.2–6.4）

用户先后提供 od_parking 与 Music，并明确授权 Music 临时调试接入。已在两个 API 33 模拟器完成多 Activity 与 Navigation/保留 Tab 验收；详细命令、18 次通过、失败记录及适用边界见 [host-acceptance.md](host-acceptance.md)。AdMob 激励和全部兼容组合仍未完整通过。

## OpenSpec

`tasks.md` 当前 16/21 完成；2.3、2.4、2.5、4.1、6.4 保留未完成。规划内容和验收标准未为适应实现限制而改写。

## 代码审查修正（2026-09-28）

- 新等待入口原先同时受 `commonShowFailure()` 和整体 `Ads.state` 的初始化判断阻塞。现允许已经开始 Provider 初始化、且任一竞价平台 READY 的机会等待或使用该平台的广告。许可不足、未初始化和初始化失败仍拒绝；旧立即入口、自动开屏及整体初始化结果的报告规则保持原样。
- 使用实际前置判断函数和现有控制器 Harness 增加回归用例，覆盖整体仍在初始化时单平台就绪、前置失败，以及恰好到期时不得交接。先运行 `:testDebugUnitTest --tests com.cashcraft.ads.mediation.internal.DisplayOpportunityControllerTest`，再运行完整 Debug 测试、构建和 lint，均通过。
- Compose 示例的两个 `rememberUpdatedState` 也通过 `key(hostActivity, pageInstanceId)` 隔离，普通重组继续更新同一页面的回调，页面实例切换不把旧广告结果转给新页面。本项完成示例和状态归属检查，尚未进行 Compose 宿主运行验收。
- 再次核对 AdMob 插屏/激励指南及 `AdPreloader` 公开接口，仍没有取得当前 1.2.1 离队对象有效期的可靠依据；任务 2.4 保持未完成，没有为消除审查缺口而假定有效期或修改验收标准。

## 代码精简（2026-09-28）

- `FullScreenShowGate` 改为共享对象，删除两家 Provider 的无状态门禁实例；保留同步、owner 匹配和交接检查。
- 三种全屏广告的立即竞价共用现有 `AdRewardResult` 内部结果封装，合并重复展示与失败路径；同步候选选择直接返回 `BidDecision`。公开签名、奖励及 sessionId、事件顺序和加载副作用保持原样。5 个 Kotlin 文件净减少 59 行，等待控制器及生命周期逻辑未改动。
- 再次运行 `./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`，58 项 JVM 测试通过，Debug 构建与 lint 通过；库 lint 无问题，smoke 宿主仍为 25 条警告、无错误。构建日志为 `build/reports/fullscreen-simplification-build.log`。本轮未进行真实 SDK 或宿主验收，未完成任务保持原状态。

## Apply 续验（2026-09-28）

现有宿主 runner 新增 12 轮真实边界验收全部通过，细节见 [host-acceptance.md](host-acceptance.md) 的续验章节。新增一项取消/超时竞态与交接后失败回归测试，JVM 总计 59 项通过；库生产源码未改。AdMob 激励三次续验未通过，分别为 API 33/36 关闭等待超时和 API 36 no_fill，保持 16/21，不替代缺失的 SDK 证据。


## 2026-09-29 策略更新

本节替代此前“第一家就绪立即展示”和“到期一律拒绝展示”的行为描述。Bidding 等待所有参与平台成功或明确失败后提前决策；截止检查使用当前有效缓存兜底，无候选才超时。默认插屏/激励 5 秒、开屏 12 秒。普通立即展示入口不变。

验证：`./gradlew testDebugUnitTest assembleDebug lintDebug` 通过；补充后到高价候选用例后 `./gradlew testDebugUnitTest` 再次通过。控制器覆盖等待另一家、提前失败、截止兜底和取消/宿主/许可保护。未执行本次策略的真实 SDK 设备与业务 UI 验收。


### 截止交接边界修复

先增加回归测试，在修复前复现 2 个失败用例：截止前选出广告、准备跨期限被拒绝，以及期限错误掩盖了最终场景/宿主/许可错误。统一规则为等待阶段决定是否可选候选，候选准备不再重复按期限拒绝；取消与最终环境检查保留。修复后 `./gradlew testDebugUnitTest assembleDebug lintDebug` 通过，共 64 项单元测试、0 失败。新增测试覆盖 499/500/501ms 决策、502ms 交接及跨期限后的取消/场景/宿主/许可失效。没有新增设备验证，此前按旧规则断言“准备跨期限必须超时”的宿主证据不作为新规则验收。

### 就绪状态重复读取竞态修复

新增 `cache becoming ready during a check does not report load failure` 回归，在 5 秒期限内的 100ms 检查中注入缓存从未就绪变为就绪的时序。修复前实际运行失败，断言消息为 `A successful load must not end the opportunity: ad_load_failed`。修复后 Ads 每轮只读取一次各平台的就绪状态，据此同时计算 ready 和 settled；控制器通过一个 LoadSnapshot 回调取得两者，避免拼接两次读取的结果。

`./gradlew :testDebugUnitTest :assembleDebug :lintDebug --console=plain` 通过；库的 65 项单元测试本次实际执行，0 失败、0 错误、0 跳过，包含新增竞态回归及已有竞价等待、截止兜底和交接保护测试。本次证据为确定性 JVM 时序模拟与库构建/lint，未进行真实 SDK 设备复现或业务 loading 验收。

后续等价精简：取消操作复用 `fail()` 的等待状态保护，`check()` 的收尾分支改为提前返回；保留同一份就绪状态快照、失败判断顺序和交接前校验。`./gradlew :testDebugUnitTest --console=plain` 重新编译生产源码并实际执行 65 项测试（控制器 22 项），0 失败、0 错误、0 跳过。本轮精简未重新执行 assembleDebug、lintDebug 或设备验收。


## 2026-09-30 Apply 续验

`./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain` 成功，根库 `testDebugUnitTest` 实际执行，XML 汇总 101 项、0 失败、0 错误、0 跳过（包含 Banner 测试；与此前仅全屏阶段的 65 项统计范围不同）。日志 `build/reports/fullscreen-apply-20260930/gradle.log`。本轮没有修改库生产源码；不因更新文档重复执行已通过的构建。

`od_parking` opt-in 宿主使用当前库 composite build，Debug 与 AndroidTest APK 构建成功，后续测试断言与 both_ready 扩展也重新编译通过；日志为同目录 `host-build.log` / `host-test-rebuild.log` / `host-bidding-build.log`。修正的宿主测试仅覆盖全屏格式，并保持旧入口生命周期优先的失败语义。真实 SDK 5 次成功及 3 份保留失败输出见 [host-acceptance.md](host-acceptance.md) 同日续验节。

用户批准调整任务 2.4：未知有效性离队对象销毁、开屏以 Preloader 启动时间保守计龄。源码确认 `retainUnshownAd` 对三个格式解绑 adEventCallback 后，仅保留 isUsable 的对象；`pendingAd`、bidPrice 和 takeAd 优先面对同一保管候选。既有 `retained ad keeps age and unknown validity or price stays unknown` JVM 回归本轮通过，覆盖年龄到期边界、时钟早于起始、未知年龄/TTL/价格及复制不重新计龄。此证据不宣称真实广告自然过期或任意 SDK 迟到回调已验收。

AdMob 激励真实奖励/关闭缺口补齐，4.1 完成；SDK show 同步异常、真实许可撤销/过期、准备窗口宿主替换以及业务 loading UI 等未完成验收继续保留未勾选。

## 2026-09-30 晚初始化修复与业务宿主验收

晚初始化时，原 startedActivityCount 对未观察到 start 的旧 Activity 的 stop 仍扣数，可能将已观察到的新宿主误判为后台。共享 AdLifecycleMonitor 最小修复为 WeakHashMap 按 Activity 记账：start/resume/seed 幂等加入，仅已记录实例 stop 且集合为空才进入后台，destroy 清理自身。未放宽展示前台检查或修改 paused grace；Native 工作树同步相同记账修复并保留 NativeInteractions 调用。

独立真实宿主回归 `late-init-before-fix.txt` 先失败，修复后 `lifecycle-late_init-fixed.txt` 通过；background/backstack 两份真实回归也通过。现有 opt-in instrumentation 的 scenario=late_init 保留为可重跑检查，不引入新的测试框架。原 HealthTracker 的精确回调顺序没有逐条记录，根因证据是源码中的实例计数缺陷和独立 red/green 复现。

修复后执行 `./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`，根库 XML 汇总实际执行 101 项、0 失败、0 错误、0 跳过；日志 `build/reports/fullscreen-apply-20260930-resume/lifecycle-gradle-fixed.log`。SDK 宿主及 HealthTracker 修复后构建通过，临时业务计时日志移除后的最终 HealthTracker Debug 构建也通过（`health-final-build.log`）。这些构建/JVM 证据与真实设备证据分开记录。

后到候选、明确失败后提前决策、截止缓存兜底和 HealthTracker 实际 loading/7 秒超时收尾已有设备证据，详见 [host-acceptance.md](host-acceptance.md) 新增章节；新增等待策略任务完成。当前 26/29，2.3、2.5、6.4 的真实 SDK/旧兼容组合缺口仍保留。

收尾验证：OpenSpec strict validate 通过；根库、od_parking、HealthTracker、Native 工作树 git diff --check 均通过。生命周期两份源码仅保留原 NativeInteractions 差异。未提交、推送或归档。

## 2026-09-30 用户批准的非阻塞异常验证

用户明确要求不为取出至 show 极短窗口中的小概率事件阻塞整体进度。2.3、2.5 保留现有最终条件检查与 SDK 异常捕获/失败清理，按既有源码、JVM 和已完成的真实主要场景证据勾选，当前 28/29。两家 show 同步异常、该窗口内自然过期及真实许可撤销的设备复现转为非阻塞补充验证；没有新增相应设备证据，也没有弱化许可、生命周期或占用保护。6.4 常规兼容性尚未全部完成。本轮仅修改验收约定与文档，未修改生产或测试源码，无须重复已通过的构建。

## 2026-09-30 新接口主流程验收完成

用户选择直接使用新接口验证，6.4 完成范围为三个 WhenReady 入口的两平台三格式及竞价、等待/取消/复用、互斥和正常关闭/奖励/收入。已逐份检查 9 份代表性设备输出均有 OK (1 test)，列表为 `build/reports/fullscreen-apply-20260930-resume/new-api-acceptance-evidence.json`。源码确认 HealthTracker 的插屏/激励封装、SplashScreen 开屏与前台开屏入口均使用 WhenReady，autoShowAppOpen=false；业务 loading 与库最终构建证据沿用已记录结果。

旧接口全设备组合不再阻塞本次验收。历史 show_callback_timeout 的真实日志证明失败回调与后续补加载；TopOn 的 finishAppOpenFailed 实现包含 attempt.complete、清理 activeAppOpen/容器、回调失败和补加载。SDK 当次缺少及时回调的原因未知，保留非阻塞跟踪，不称为已修复。当前 **29/29**，没有新增设备运行或生产源码变化；原失败输出保留。
