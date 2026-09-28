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
