# 第一阶段实施记录

日期：2026-09-28。基线：`codex/1.0.6`，`a99eaaa`。未提交、未推送；保留原有未跟踪的工具配置目录。

## 已实现的路径

- `Ads` 新增开屏（有/无容器）、插屏、激励的 `show...WhenReady`，返回具体类 `AdDisplayOpportunity`。激励继续返回 `AdRewardResult`。
- `DisplayOpportunityController` 使用调用时捕获的 `elapsedRealtime` 和最多 100ms 检查；等待初始化、缓存及首次窗口就绪共用同一期限。普通 Compose 重组不参与机会的创建与计时。
- `FullScreenShowGate` 在各 Provider、立即展示、竞价和自动开屏之间共享 owner；等待与 SDK 展示分开，释放必须匹配 owner。最终交接重新检查期限、许可、场景、原 Activity、缓存和容器。
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

开屏采用官方四小时上限，年龄下界不可靠或已到期即丢弃。这个策略可能提前丢弃进程较晚加载的广告；它不代表取得了精确加载时间。

Next-Gen 1.2.1 没有可验证的插屏/激励离队对象有效期依据，不能直接套用传统 GMA 的一小时规则。最终检查中止时，这两类已取出对象解绑回调后销毁；SDK 自己的预加载和队列仍保留。纯等待取消尚未 poll，不受这个狭窄窗口影响。完整保管复用验收保留未完成。

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
