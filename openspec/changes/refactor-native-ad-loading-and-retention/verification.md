# 原生库存与展示保留实施记录

> 当前续做工作树为 `/Users/jiaoyun/.codex/worktrees/native-loading-retention/ads-mediation`；下方首轮记录中的 73e5 路径属于历史基线。最新结果见文末“归档恢复后的继续实施”。

## 2026-09-30：实施基线

- 授权：用户执行 `openspec-apply`，实施 `refactor-native-ad-loading-and-retention`；提交、推送和归档不在本轮操作中。
- 唯一目标工作树：`/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation`，detached HEAD `652f6f1efd0b2c6e04511efc971fa6f383d5398d`。默认 cwd 的另一个 checkout 未参与实施。
- 修改前快照：`/private/tmp/ads-native-apply-baseline-20260930-194439`，保存 134 个非忽略文件（1,172,595 字节）、SHA-256 清单、HEAD、工作树状态和 tracked diff。基线含先前未提交的 Native/Compose/测试改动，本轮差异需对比此快照，不使用整棵 dirty tree 统计成果。
- 依赖保持 GMA Next-Gen 1.2.1、TopOn 6.6.22.3；本机 JDK 17 路径为 `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`。设备验证使用既有 API 36 模拟器，真实宿主和 Native ID 仍沿用用户后续提供的安排。

## 历史证据承接

| 路径/修改 | 已有证据 | 仍未证明 |
| --- | --- | --- |
| 普通 Native 加载、早期重写及落选缓存 | 旧记录的 109 项 JVM、14 项 instrumentation，普通加载 R8 和 AdMob 跨 Activity 曝光 | 新预加载路径和后来价格改动的设备/R8行为 |
| GMA 1.2.1 预加载探针 | Debug/API 36、三轮零价格测试广告的查询/领取身份与自动补货 | 非零报价、异常队列、R8、跨 Activity 渲染、逐来源支持 |
| TopOn 跨 Activity 转移 | 同一对象及 isValid | 确认模板比例后的真实渲染/曝光 |
| Pangle TestImage2 | 曝光及真实 paid 回调 | 主图/图标完整，空白问题尚未关闭 |
| 后续 eCPM 换算修改 | Debug 编译及 23 项 JVM 测试 | 本次取价修改后的非零设备样本与 R8 |

详细证据继续引用 [旧变更验证记录](../add-page-native-ads/verification.md:435)，不重标旧任务。新探针、生产接口和视觉结果在取得后分别追加，不能用规划/编译结果替代。

## 能力门槛与并行边界

新 SDK 预加载生产切换必须等待任务 1.3–1.5 所需证据；未取得非零测试样本时不宣称通过。来源隐藏/恢复未获实际证据前，handle 的保留入口返回不支持，页面请求保留时明确降级释放；fake handle 的所有权单测只证明本层控制流程。

主会话负责共享契约、缓存期限/取消、事件日志及构建集成。原生子代理分别负责只读素材/布局工厂、位置与 Compose 生命周期、SDK 探针测试，文件责任分离；子代理不操作设备或运行共享 Gradle。请求继承主模型并使用 high，实际 provider/model/推理元数据未能独立核验。

## API 与事件兼容核对

| 对象 | 处理 | 验证要求 |
| --- | --- | --- |
| NativeRequest 单平台及双 ID | 保留现有构造与字段 | 现有请求/竞价 JVM 与宿主编译 |
| NativeLayout.Custom | 旧 Context lambda 与 factory 保留；新增 withAssets 命名工厂 | 默认、旧 Custom、新 withAssets 编译与独立 View 绑定 |
| NativeRetentionPolicy | 新增销毁/保留策略，默认销毁 | 不显式传策略的旧调用保持源码可编译 |
| position | 唯一识别展示位置；同位置第二容器明确失败 | 不同卡片改用不同业务位置，真实位置冲突测试 |
| AdEvent.position | 保留既有 `_native` 事件格式 | 既有事件归因回归 |
| AdEvent.slotId | 保留字段；NativeSlot 默认值采用业务 position，不再生成额外页面编号 | 这是 Native 字段语义迁移，原按 UUID 使用的宿主须适配；requestId/sessionId 继续区分多轮获取与展示 |
| 全屏事件与依赖 | 不更改原有格式及版本 | 既有日志/事件和全屏边界回归 |

以上公共新增项的源码兼容与设备结果待本轮构建/测试补充；不宣称旧已编译宿主二进制兼容。库内调用点和 smoke 卡片已使用可区分的业务位置，额外宿主仍须按实际调用核对。

## 本轮实现范围与确定性证据

- position 展示记录复用原 controller/handle，拒绝相同 position 同时占用；以实际记录/连接引用防旧 release/observer 删除新连接。默认 DESTROY_ON_HIDE，RETAIN_WHILE_PAGE_ALIVE 增加来源 pause/resume 接口；先解除当前连接、移除原平台子 View，再按来源契约保留。所有生产来源当前返回不支持，明确降级释放。
- Compose onRelease 调用 release，页级 owner 才可跨组合保存展示记录；Activity-only 退出销毁。request/layout/policy/owner/Activity 变化重建，旧 Custom 和尾随 callback 调用保留。已绑定来源恢复不再被未展示 isValid/TTL 机械否决。
- 新 NativeAssets/NativeMediaType 与 Custom.withAssets；AdMob/TopOn 文案复用同一快照绑定；未知素材维持 UNKNOWN/null。smoke 的 layout=assets 使用已有两套 XML，根据明确 IMAGE 选择紧凑样式；retention=destroy|retain 可在 View/Compose/Navigation 选择策略。
- 候选保留区维持每 key 一条/合计四条；首次期限使用弱键记录，取出、换 key、再次入区不续期；未知原期限的 AdMob 对象释放。取消后的有效未渲染候选保留，旧竞价重复成功不能再次夺回已交付对象。
- Native 日志收口到 AdsModuleLogger：中文关键结果、DEBUG 身份/落选保留、未知与零价区分、去换行限长、关闭时惰性构造。来源异常只记类型，避免把 SDK 原始响应或 URL 带入日志；全屏日志格式未改。

最新核心 JVM 为 **126 tests / 0 failures / 0 errors / 0 skipped**。覆盖 controller 的保留/降级/许可撤回/取消/重入、registry 旧连接隔离、竞价/缓存期限/重复交付、素材类型/比例与日志边界。fake handle 的恢复成功只证明本层控制流程，不能作为真实来源支持证据。

## Debug 构建与设备结果

JDK 17、锁定依赖、离线 Gradle。核心 testDebugUnitTest、assembleDebugAndroidTest、lintDebug，以及 ads-compose 编译、smoke Debug 和 androidTest 构建通过。日志目录见下方；没有发布或版本升级。

API 36 / emulator-5580：

| 检查 | 结果与边界 |
| --- | --- |
| NativeAssetsLayoutTest / NativeBindingTest / NativeDimensionsTest / NativeFragmentTest / NativeReadinessTest | 最终 **23/23**，真实 View 尺寸/Fragment owner/position 冲突/素材工厂/就绪资格；不发生产 Native 请求 |
| NativeComposeHostTest / NativeLiveLifecycleTest | 最终 **8/8**，真实 AndroidView/Navigation/Preview/等值重组/回调及配置更换；官方 AdMob 广告的隐藏、父容器、Dialog、详情返回、旋转、同 placement 双卡片、加载中销毁。默认销毁策略证据，不是保留模式实际恢复 |
| NativePreloadTransferTest | **3/3**，公开 peek 非消费、受控队列变化、空 poll、destroy 后不复活、消费自动补货；A 销毁后 B 领取同公开身份并真实曝光；图片同 SDK 对象和平台 View 重新附着 |
| 官方 Google 图片探针 | 隐藏 2 秒曝光增量 0；恢复后观察 5 秒，原始曝光总数 1、恢复曝光增量 0、paid 回调 1；注册调用 1、恢复取货 0。同期 SDK 自动补货回调增量 1，不能归因于恢复或称已关闭补货 |
| 图片画面 | 恢复前后主图、图标、标题、正文、CTA 与广告披露可见；后图出现 SDK 测试广告 validator 提示。此为 Flood-It 官方图片单样本，不覆盖视频/音频、长时、深色、大字体、分屏或第三方来源 |
| 价格样本 | 新 Debug 探针累计 4 个对象均为 USD/次 0.0，未知 0、非零 0；没有非零队首报价关联证据 |

探针最初把 MediaView.mediaContent getter 当成 registerNativeAd 的对象身份凭据，锁定 SDK 返回 null 而实际已曝光。修正为同 ad/平台 View/媒体槽位引用、SDK 实际媒体子 View、公开 responseId 及截图；没有为通过测试改动生产注册路径。官方 [MediaView 文档](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/nativead/MediaView)说明 NativeAd 媒体在 registerNativeAd 时关联，custom native 的 setter 不是这一入口。

测试适配记录：NativeDimensionsTest 原私有字段注入迁到 Presentation；Compose 测试替换宿主内容前最终销毁原 View 卡片；真实生命周期测试等待旧 Activity 完全销毁后再进入下个同 position 用例。这些失败均保留原日志，最终结果单列，避免把旧测试资源重叠解释成允许重复位置。

## 当前能力门槛和剩余工作

本轮没有切换 NativeAdPreloader 生产入口，也没有新增共享准备/查看/领取/关闭引擎、TopOn 2/4/8 秒退避补货、五分钟前台闲置及四个无需求 key 管理、非消费报价后只领赢家。现有普通 AdMob loader、TopOn 实际对象比价继续工作。任务 1.3–1.5 的非零/两平台能力门槛未完成，因此这些依赖任务保留未勾选。

| 来源/能力 | 结论 |
| --- | --- |
| AdMob 官方 Google 图片（Debug/API 36） | 短时同对象/平台 View 重新附着样本通过；尚未接入生产保留白名单 |
| AdMob 视频、第三方来源、长时后台/音频 | 未验证；生产请求保留时释放降级 |
| TopOn 各来源保留、同 placement 不同宽度与非消费队首稳定性 | 未验证；生产保留降级，使用实际领取对象价格 |
| TopOn 模板跨 Activity 真实展示 | 缺后台确认模板比例；不重复用猜测比例请求 |
| Pangle TestImage2 主图/图标空白 | 本轮未取得新来源样本，保持未完成；早期 CARD3 画面不替代这一问题 |
| 非零 AdMob 样本、真实宿主/Native ID、来源视觉和真实长时老化 | 外部输入/场景未具备，继续保留待验；不以单测或零价替代 |
| 真实后台与许可撤回/恢复的 SDK 代次隔离 | 新共享预加载路径未启用，阶段 0 门槛仍未完成 |

两份在途规格的唯一合并候选及旧缺口映射在 [spec-reconciliation](spec-reconciliation.md)。原 add-page-native-ads 的任务、证据和两个目标 spec 均未改写；本轮不执行同步、归档或提交。


## Release/R8 与收尾

最终核心/Compose AAR、两模块 lint、核心 instrumentation 及 smoke Debug/Release/instrumentation 构建通过，126 项 JVM 保持全绿。额外修正了保留失败原因跨广告复用的问题：下一轮尚在加载时取消，使用本轮 native_inactive，不沿用上一条广告的 pause_unsupported；覆盖在既有降级测试内。

Release 公开预加载探针取得 **1/1** 设备通过：锁定 GMA 1.2.1、buffer=1、重复 peek 非消费、poll 的公开 responseId 一致、实际对象经核心 fromNative 读取 USD 0.0、未再次 start 的 SDK 自动补货、destroy 清空和 2 秒后仍为空。它不读取新队首价格配置，不证明非零样本、Release 跨 Activity 渲染或来源恢复；任务 1.3/1.4/3.2/6.4 仍未完成。

该探针与 SDK 调用均编入 smoke 目标 APK，接受正常 R8；独立测试 APK 只调用最小诊断入口。opt-in 的 native-probe-rules.pro 仅保留宿主诊断入口，不增加 SDK/核心 keep 规则，默认发行构建会移除未用探针。原试验直接跨 APK 访问被内联的 Kotlin/SDK 方法产生 NoSuchField/NoSuchMethod，已改为同 APK 执行，没有把这些测试装置错误计为 SDK 运行缺陷。

R8 继续报告既有 Pangle 的 NetExtParams$RenderType、TTSdkSettings$FETCH_REQUEST_SOURCE Missing class 警告。本轮官方 Google Native 探针未触发相关 Pangle 路径，不能据构建和该探针宣称警告对应的来源路径安全。

任务勾选为 **11/42**：1.1、1.2、2.6、4.1、4.2、5.1、5.2、6.1、7.4、7.5、7.6。其余任务保留原验收标准及未完成状态。OpenSpec strict 校验通过；没有修改旧变更任务、依赖版本、核心 consumer rules、提交、推送或归档。

证据保存到 `build/reports/native/loading-retention-20260930/`（本地忽略目录），包括构建/JVM汇总、最终设备输出、初次失败诊断、公开探针脱敏回调及三个截图。文件级实施差异见同目录 incremental.patch，以本轮 134 文件快照为基线，不使用整个 dirty tree 冒充本轮变更。


## 2026-09-30：归档恢复后的继续实施

### 工作区与增量边界

- 用户调用 openspec-apply 继续现有变更；发现原 73e5 目录已不存在，Git 保存了 20:32:39 的归档清理快照 `8d0bb05da9e71b1c9167addd62dd1e157270aae3`，父提交仍为原 `652f6f1`。快照含本变更及 11/42 状态。
- 通过原生工具从该快照恢复隔离工作树 `/Users/jiaoyun/.codex/worktrees/native-loading-retention/ads-mediation`，本轮全部改动均在此进行。默认 checkout 的 `codex/1.0.6`/Banner 内容未参与改写。用户随后批准建立 CodeGraph 索引，已执行成功。
- 归档快照不含旧 ignored 的 build/reports，本轮没有声称找回或重新验证旧全部证据；历史报告描述保持原记录。当前增量补丁和新日志保存于 `build/reports/native/resume-20260930/`，以恢复快照为基线，包含两份新增测试文件。
- 一名原生子代理负责 5.3 的绑定补丁，主会话负责 Compose、示例 XML、整合及全部构建/设备验证。子代理请求继承主模型、high；实际 provider/model/effort 未能独立核验。子代理已关闭，没有外部委派、SDK 逆向或后台任务。

### 本轮实现与已确认缺陷

1. **Compose 更换配置的同位置冲突**：新增 Loaded/retain 场景发现 key 更换时新 AndroidView factory 可早于旧 onRelease，原容器仍占 position，新容器失败且旧对象留在保留记录。复用原 retry 状态容器记录本 Compose 实例当前连接；新 factory 先 destroy 本实例旧配置，再创建新外层；旧 onRelease 只在引用一致时清空连接，不影响其他卡片。
2. **素材文案与视频保护**：AdMob 填入真实必需文案时恢复可见，缺标题/CTA 或纯空白明确失败，缺失 TopOn 专属 metadata 清空隐藏；TopOn 旧 advertiser 槽位在缺 adFrom 时保留真实广告主，已知 VIDEO 缺 SDK media 明确失败，不用封面/图片列表冒充视频。
3. **两套 XML 示例**：补齐 adFrom/domain/warning 控件及工厂映射，缺素材时不显示占位文案。默认布局与平台 render/prepare、监听注册先后保持原契约；不更换 SDK、不增加依赖或公共配置。

### 设备、构建与源码证据

全部命令在恢复工作树运行，JDK 17，锁定原依赖，离线构建；设备仅 `emulator-5580`/API 36。未使用其他模拟器或实体机；未读取受保护测试配置，真实 SDK 请求仅使用 smoke 中既有官方 AdMob 测试 ID。

| 检查 | 最终结果及范围 |
| --- | --- |
| testDebugUnitTest | **126/126**，0 failure/error/skipped；覆盖既有核心状态/缓存/素材逻辑 |
| Debug/Release/R8 与 lint | 核心 instrumentation、smoke Debug/instrumentation/Release 构建及核心/Compose lint 通过；最终组合构建 52s，0 退出码 |
| 核心设备 | **30/30**：Binding 19、AssetsLayout 2、Dimensions 2、Fragment 3、Readiness 4；素材 helper 与真实 View/Fragment，未请求平台 Native 广告 |
| NativeRetentionComposeTest | **6/6**（原 5 项 + 后补 Navigation 单项）：真实 Compose/AndroidView，受控广告句柄；跨组合同子 View、最新回调、旧 release/destroy 隔离、Activity-only、visible/owner 暂停、配置更换、Activity recreate、三轮动画退出/返回，以及真实 Navigation pageEntry 返回栈保留/弹栈最终释放。观察者不增长、最后释放一次；不证明 SDK 媒体静默或真实来源恢复 |
| NativeComposeHostTest | **5/5**：原 4 项稳定配置/Navigation/Preview/资格检查，加两套实际 XML 工厂披露槽位及独立性检查 |
| NativeLiveLifecycleTest | **4/4**：官方 AdMob 普通 loader；可见性/父容器/Dialog/详情返回、同 placement 双卡片、旋转及加载中离页。仍为默认销毁策略，不是新共享预加载或 SDK 保留恢复证据 |

宿主三组原同一运行 **14/14**，后续仅补跑新增 Navigation 用例 **1/1**；宿主共 **15** 个不同用例，本轮不同 instrumentation 用例共 **45**。另一次独立进程重启检查使用 retain 策略和官方 AdMob 普通加载：旧进程 PID 9462、新进程 PID 9730，两个进程分别走 Loading→Loaded，没有恢复旧 View。进程内保留数据没有 saveable/持久化入口；该检查不声明 SDK 来源可跨进程保留。保留测试桥放在 androidTest 的 Java 文件中，通过项目自身 Debug JVM 接口和既有测试注入方式替换 SDK 效果；无 Kotlin internal 可见性抑制，不加入目标 APK 发行代码或 SDK keep。本轮没有新 Release 设备探针，Release 构建成功不等于新绑定来源的 R8 运行验收。

初次失败单列保留：首次受控测试未设置 Compose 容器宽度，4 项等待超时；修正尺寸后暴露真实策略更换冲突（expected destroy 1, actual 0）；修复后测试第二次 setContent 尚未提交时读到已销毁旧 View，调整测试等待到新 composition/Idle 后通过。首次 Kotlin internal 抑制方案已替换为 Java 测试桥。最终通过数没有混入这些失败运行。

### 任务状态与仍需条件

- **4.4、4.5、4.6 完成，14/42**：完成的是本层连接/Compose 行为，真实来源能力仍由 1.6/4.3/6.3 等独立门槛约束。
- **4.5 分层证据**：Activity-only、owner 暂停/最终销毁、Activity 重建和 Fragment viewLifecycleOwner 的两策略通过；真实 Navigation 组件中的受控句柄验证 pageEntry 存活/返回/弹栈归属；进程重启以官方 AdMob 普通加载验证重新获取。真实 SDK 隐藏静默/来源恢复依然由 1.6、4.3、6.3 验收，不由本项代替。
- **5.3 保留未完成**：上述绑定修复和 6 项新增回归通过，示例 metadata 槽位已齐备；尚无对应真实 TopOn 来源的 SDK CTA 替换、披露画面和完整素材组合证据，不能由 helper 测试关闭整个任务。
- 生产预加载仍等待 1.3–1.5 的非零/两平台/新路径证据；所有生产来源保留继续降级释放。非零 AdMob 样本、确认模板比例、Pangle TestImage2 空白、逐来源后台/音频/长时及真实宿主仍沿原安排待验。
- Pangle R8 的 NetExtParams$RenderType、TTSdkSettings$FETCH_REQUEST_SOURCE 既有缺类警告仍在。本轮 AdMob 设备结果不证明相关 Pangle 路径安全。
- 未修改旧 OpenSpec 任务、SDK 版本、consumer rules 或用户级记忆；没有提交、推送、同步主规格、归档或清理新工作树。

复现本轮构建：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
ANDROID_HOME=/Users/jiaoyun/Library/Android/sdk ./gradlew \
  :testDebugUnitTest :assembleDebugAndroidTest :lintDebug :ads-compose:lintDebug \
  :r8-smoke-app:assembleDebug :r8-smoke-app:assembleDebugAndroidTest \
  :r8-smoke-app:assembleRelease -PnativeSmoke=true --offline --console=plain
```

设备测试按表中 5 个核心类和 3 个宿主类分别通过内置 `android.test.InstrumentationTestRunner` 运行；完整输出及首次失败见证据目录。OpenSpec 严格校验仅代表文档结构，不代表剩余 28 项实现完成。

新增 Navigation 测试编译日志、单项运行输出、进程重启 JSON 和可重跑脚本一并保存在本轮证据目录；最终测试 APK 哈希已更新。全部验证后已 force-stop 本轮宿主及核心测试包，未留后台测试进程。严格 OpenSpec 校验通过。


## 2026-09-30：收益重入与日志续做

### 基线与实现

继续同一工作树及 HEAD `8d0bb05da9e71b1c9167addd62dd1e157270aae3`，修改前保存 149 个非忽略文件到 `/private/tmp/ads-native-apply-20260930-212130`。本轮增量以这份会话基线核对，保留交接时的 14 个变更文件；默认 checkout 未参与修改。

`NativeAuction.finish` 的交付可同步触发首次曝光和业务销毁。原实现交付后才读取候选上暂存的 paid，而销毁中的 `cancel` 已清空列表，导致原广告合法收益丢失。现先把暂存收益移至局部变量并清空候选，再交付广告；原轻量回调仍负责身份、去重及两个全局出口。没有新增公共接口、SDK 路径或依赖。

新增一个 JVM 回归用例：TopOn 候选先收到零金额 paid，另一端完成后胜出，首次曝光回调立即销毁页面；验证一次释放、原 session/requestId、一次 PAID/收益交付、迟到重复去重和无伪造 SHOW_FAIL。修复前该用例因 PAID 缺失失败；修复后整个 NativeAuctionTest **17/17** 通过。首次失败与最终结果分开保留，没有将失败计入通过数。

日志仍复用 AdsModuleLogger：普通收益优先从原始 micros 精确换算，保留原报价显示精度；DEBUG 补平台、获取结果、错误码和原始金额。Native 异常仅保留类型、原因链和一次根因栈，不输出原始 SDK 消息；原因链限八层、根因栈限二十帧，循环原因链有界退出。关闭日志保持惰性。AdsNativeView 将工厂/渲染异常接到此出口后继续原失败清理，记录真实渲染阶段耗时，稳定英文保留失败原因移至 DEBUG。

新增两项日志 JVM 检查覆盖原始微单位精度、零/未知收益、获取结果及等待单位、错误去换行限长、根因栈/循环原因链及关闭日志。仅验证当前出口，不证明共享预加载、消费补货或等待条件日志已完整接通。

### 最终验证与证据

- **JVM：129 tests / 0 failures / 0 errors / 0 skipped**，其中竞价 17、日志 3。本轮只新增三项用例；子代理的独立编译/JUnit 检查不重复计数，最终以主会话 Gradle 结果为准。
- **构建：37 秒、退出码 0**。核心 instrumentation、smoke Debug/instrumentation/Release/R8、核心/Compose lint 通过。命令沿用上一轮组合构建，JDK 17、锁定依赖、`--offline`。
- **静态：`git diff --check` 通过**。本轮未运行 ADB/设备测试，也没有新的 SDK、视觉或 Release 设备证据；不重标之前 45 个设备用例为本轮运行。
- R8 仍报告既有 Pangle `NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE` 缺类警告；本轮未新增对应来源运行证据。

本轮证据位于 `build/reports/native/followup-20260930/`：`paid-regression-before.log` 保存修复前失败，`paid-regression-after.log` 保存修复后竞价结果，`build-final.log` 保存最终构建，另有 `jvm-summary.json`、`junit/`、`apk-sha256.json`、`incremental.patch` 和 `changed-files.txt`。增量补丁以本轮 149 文件快照为基线；该证据目录被 Git 忽略，清理工作树前须保留所需文件。

### 验证边界与任务状态

上述是任务 3.5 的现有实际对象竞价路径补全，不能关闭含新库存加载来源、获取及恢复身份验收的整项任务。任务 2.8 的新库存/补货日志也仍依赖未接通的生产路径；完整任务计数保持 **14/42**，本轮不因局部修复新增勾选。

生产共享预加载仍受 1.3–1.5 的非零报价、两平台取货关联和新路径设备/R8 证据约束；生产来源保留仍降级释放。真实宿主/Native ID、确认模板比例及逐来源视觉、音频和老化验收沿原安排待补。本轮的 JVM/构建结果不替代这些证据。

一名原生子代理负责 AdsModuleLogger 和 NativeFlowLogTest，主会话负责收益修复、集成与构建。子代理请求继承当前模型、high；实际 provider/model/effort 未能独立核验。没有 SDK 逆向、受保护配置读取、设备请求、提交、推送或归档。

更新记录后，OpenSpec 严格校验通过。本轮相对会话基线共修改七个文件（五个源码/测试文件和两份 OpenSpec 记录）。原生子代理已关闭，没有待完成的构建、设备或代理任务。

## 2026-09-30：候选期限与 Release 设备续做

### 增量与任务状态

沿用工作树 `/Users/jiaoyun/.codex/worktrees/native-loading-retention/ads-mediation` 和 HEAD `8d0bb05da9e71b1c9167addd62dd1e157270aae3`，保留会话开始前的修改；本轮不是上一节 149 文件快照的重跑。完成 **1.4**，累计 **15/42**；2.7、7.2 仅补已验证子项，未关闭整项。

- **2.7 原期限继承：** TopOn 首次入区上限现在绑定到原 SDK 句柄；领取、离区等待和首次渲染前均继承，移除缓存内弱映射这一第二期限来源。AdMob 原始期限未知仍不入区，不制造加载时间。两项行为回归覆盖等待另一平台期间跨期后的释放、拒绝渲染及有效低价候选胜出。替换回原缓存实现时 19 项竞价测试中两项失败；最终竞价 19/19、全部 JVM 131/131。新生产预加载会话一小时期限仍受阶段 0 门槛约束，不能据此关闭整项。
- **1.4 Release 新路径：** `NativePreloadR8Test` 的两项测试均由独立 Release 测试 APK 驱动已混淆的目标 APK；真实 SDK 调用、队列对象及锁定版本报价探针位于目标 APK。证明查看不消费、报价与领取对象一致、取空、消费后无需再次 start 的 SDK 自动补货、销毁，以及 A 的 Activity 销毁后同一排队对象由 B 领取，新建 B 的 NativeAdView/MediaView 并收到实际曝光。未增加 SDK/core keep 规则或升级依赖。
- **7.2 Fragment/retry 子项：** `testRealAdFragmentRecreationAndExplicitRetry` 使用官方 AdMob Native 测试 ID，一个用例依次覆盖 `DESTROY_ON_HIDE` 与 `RETAIN_WHILE_PAGE_ALIVE`。真实填充后业务 Custom 工厂明确抛错，得到 `native_layout_invalid`、无子 View 和无自动重试；恢复工厂后显式 retry 成功，Loaded 时重复 retry 不增加请求。detach/attach 销毁旧 View/卡片并创建新卡片；旧引用无法复活，重建卡片加载一次。该失败不是 SDK no-fill；Fragment View 销毁也不证明真实来源同对象恢复能力。

### 实际运行结果

JDK 17、现有锁定依赖、Gradle `--offline`。本轮主构建：

```sh
./gradlew :testDebugUnitTest :assembleDebugAndroidTest :lintDebug \
  :ads-compose:lintDebug :r8-smoke-app:assembleDebug \
  :r8-smoke-app:assembleRelease :r8-smoke-app:assembleReleaseAndroidTest \
  -PnativeSmoke=true -PsmokeTestBuildType=release --offline --console=plain
```

- `build-after.log`：**BUILD SUCCESSFUL / exit 0**；解析最终 XML 得 **131 tests / 0 failures / 0 errors / 0 skipped**。核心 instrumentation、smoke Debug/Release/R8、Release instrumentation、核心和 Compose Debug lint 通过。
- `fragment-live-after-network.log`：联网的 `emulator-5580` / API 36，**OK (1 test)**，同一用例包含两策略循环。旧卡片加载两次（工厂失败、业务 retry），重建卡片一次。
- 最终探针布局沿用库内 `createDefaultNativeLayout`，尊重宿主系统栏，避免手写卡片的披露缺失和首行遮挡。最终源码重新构建安装目标与测试 APK，`release-insets-final-build.log` **exit 0**；`release-preload-insets-final.log` **OK (2 tests)**。中间修复后的重复运行不计为新增用例。
- `native-acceptance-final-callbacks-correct-tag.log`：B **曝光=1、收益=1**；最终价格边界 **unknown=0、zero=2、positive=0、nonzeroVerified=false**。无广告点击；零值是有效样本，不是非零报价验证。
- 已取回并实际查看 Fragment 两策略重建截图以及 `native-preload-r8-transfer-b-insets-final.png`：最新 Release 画面可见真实主图、图标、标题、正文、INSTALL、Ad 标签和 SDK 右上披露。仅证明当前 Google 图片样本，不代表 7.3 的逐来源、视频、大字体/深色/窄屏矩阵完成。
- R8 继续报告 Pangle `NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE` 缺类；官方 Google 路径不替代对应 Pangle 运行验证。

### 首次失败及环境处理

首次候选回归构建缺 `SystemClock` import，补齐后保存原缓存的两项真实行为失败及最终通过结果。首次 Fragment 设备请求因无可用默认网络返回 NETWORK_ERROR；未修改生产错误处理来规避。首次 Release 转移已产生 SDK 曝光，但截图调用的 Kotlin `CloseableKt` 被目标 R8 移除；改用 Java `FileOutputStream`/finally，未增加保活规则。取回截图发现诊断布局首行遮挡和披露缺失后，改为复用默认绑定并处理系统栏，重新构建、运行、观察最终画面。

离线阶段尝试的自建隔离 AVD 因重复实例约束、磁盘空间及克隆 userdata 启动时 `/data` 只读和超时未能启动；没有运行通过记录，没有改用户原 AVD 或 SDK 原始 seed。未进一步定位克隆镜像的挂载根因。自建进程已终止，临时 AVD 已删除；`isolated-avd-failure.log` 保留实际失败片段，不计入 SDK 结果。

用户批准临时恢复 `emulator-5580` 的 Wi-Fi；授权后首次实际检查已经启用并联网，enable 是无状态变化操作，最终仍为 enabled/AndroidWifi，不按旧离线快照禁用。忙碌的 `emulator-5560` 只读检查，未安装、启动测试或改联网。

### 证据与未关闭门槛

本轮证据根目录：`build/reports/native/apply-current/`。关键文件：`candidate-regression-before-behavior.log`、`candidate-regression-before.xml`、`build-after.log`、`jvm-summary.json`、`fragment-live-after-network.log`、`release-insets-final-build.log`、`release-preload-insets-final.log`、`native-acceptance-final-callbacks-correct-tag.log`、`wifi-final-observed.log`，以及 `screenshots/fragment-live-*-recreated.png` 和 `screenshots/native-preload-r8-transfer-b-insets-final.png`。该目录被 Git 忽略，清理前需保留所需证据。

尚缺可产生**非零 AdMob Native 报价**的确认测试配置；TopOn **测试模式配置、非零样本和后台确认模板比例**；本变更的**真实宿主与 Native ID**；逐来源恢复、Pangle 空白、音视频/长时老化及适配样本。1.4 完成不等于 1.3–1.5 全部通过，生产共享预加载和真实来源保留仍未启用。没有提交、推送、SDK 升级、保护配置读取或归档。

收口已删除本会话创建的临时 AVD，并停止本会话的两个测试包；Wi-Fi 保持授权后首次观察到的启用状态。`openspec-validate-final.log` 严格校验 **exit 0 / valid**；`openspec-apply-final.log` 返回 **total=42、complete=15、remaining=27**。未关闭任务等待上述明确输入，不因构建或当前 Google 样本通过而重标。

## 2026-10-01：后台关闭与重建续做

### 本轮范围与基线

用户以 `native-ad-loading-and-retention` 恢复 Apply；当前唯一对应变更为 `refactor-native-ad-loading-and-retention`，起点 **15/42**。修改前五份文件快照保存在 `build/reports/native/lifecycle-20260930-235954/baseline/`，保留已有工作树修改。只扩展 smoke 目标 APK 内的预加载机制探针及其 Release 测试驱动，没有修改核心生产代码、公共 API、SDK 版本或混淆保活规则。

### 1.7 AdMob 机制子项

新增 `NativePreloadR8Test.testBackgroundCloseAndRestartDoesNotReuseOldInventory`：

1. 官方 Google Native 测试广告填充后保存真实队首身份；启动本应用 B Activity，等待 500ms，超过既有 `AdLifecycleMonitor` 的 100ms pause 宽限。累计后台通知 **0**，应用仍在前台，队首身份未改变。
2. B 的 `moveTaskToBack(true)` 使应用真正退后台，既有生命周期监测器累计后台通知 **1**。在此状态明确调用 SDK `destroy`；公开数量、peek 与 poll 均为空，等待 **3 秒**后仍空。
3. 返回应用，使用同一 SDK key 再 start；新队首身份与上一代不同，报价和实际领取对象一致。领取触发自动补货后在同一主线程操作内 destroy，等待 **3 秒**后队列仍空；再次 start 后只领取新的队首身份。
4. teardown 移除本轮观察监听并释放领取对象、平台 View 和 Activity。未让探针监听成为生产补货/后台控制器。

实际 SDK 操作和生命周期查询均位于受 R8 压缩的目标 APK，测试 APK 只驱动。该证据证明本轮观察窗口内 destroy/同 key 重建的可控机制；不证明底层网络请求已取消，也不把三秒窗口提升为无限时长保证。没有真实更改 UMP 许可；TopOn 不可取消请求和许可恢复后旧共享缓存隔离仍待真实配置与验证。因此 **1.7 保持未完成**，不据本项启用共享预加载生产入口。

### 实际验证

```sh
./gradlew :r8-smoke-app:assembleRelease :r8-smoke-app:assembleReleaseAndroidTest \
  -PnativeSmoke=true -PsmokeTestBuildType=release --offline --console=plain
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.cashcraft.ads.mediation.smoke.NativePreloadR8Test \
  com.cashcraft.ads.mediation.smoke.native.test/android.test.InstrumentationTestRunner
```

- JDK 17、锁定 Next-Gen **1.2.1**、`emulator-5580` / API **36**。`release-lifecycle-build.log`：**BUILD SUCCESSFUL in 1m 31s / exit 0**；目标和测试 APK 安装均 Success。
- `release-lifecycle-device.log`：**OK (3 tests)**，运行 41.686 秒；新增后台机制一项，既有取货/补货与 A 销毁→B 渲染两项继续通过。没有将历史 JVM 或 Fragment 检查计为本轮重跑。
- `release-lifecycle-callbacks.log` 保留下来的 SDK 输出：B **曝光=1、收益=1**，价格 **unknown=0、zero=4、positive=0、nonzeroVerified=false**。后台日志行在随后采集的环形缓冲区中未保留；后台 0/1 及关闭/重建结果以通过的测试断言和完整 instrumentation 输出为证据。
- 取回并实际查看 `screenshots/native-preload-r8-transfer-b.png`：当前 Google Flood-It 图片样本的主图、图标、标题、正文、INSTALL、Ad 与 SDK 右上披露完整可见；没有广告点击。仍不关闭视频、TopOn 或完整适配矩阵。
- APK SHA-256：目标 `6327746f6f019705a63a0f21782c54c9abd6eda4c4bdb6ef0ff0fdbfd73b758a`；测试 `4aa1a8d600492eee1d8d43257940b709e21549f258eb7da604ab51ca5f7421d4`。
- R8 既有 Pangle 两个 Missing class warning 仍在，没有抑制或新增 Pangle 运行结论；非零 AdMob、TopOn 测试配置/确认模板比例、真实宿主/Native ID 与逐来源验收门槛不变。任务合计 **15/42**。

本轮证据根目录 `build/reports/native/lifecycle-20260930-235954/` 被 Git 忽略；包含修改前快照、构建/安装/设备日志及截图。未提交、推送或归档。

收口：`openspec-lifecycle-validate.log` 为 **exit 0 / valid**；`openspec-lifecycle-status.log` 返回 **total=42、complete=15、remaining=27**。已停止本轮 smoke 包，原 `LanguageGuideActivity` 回到 RESUMED（`foreground-final-observed.log`）；Wi-Fi 始终保持开始时的 enabled/AndroidWifi，未修改网络设置，未操作忙碌的 `emulator-5560`。


## 2026-10-01 点击与外跳返回确定性回归

继续推进任务 6.5 中不依赖真实来源配置的交互门控检查；本轮只修改现有 JVM 测试，生产代码、SDK、公共 API 和验收标准均未改。

- `NativeInteractionStateTest` 新增两项：覆盖层在后台关闭后，长时间外跳的本次返回仍被抑制，下次独立往返恢复；重复点击不缩短覆盖层保护，过期覆盖层关闭不延长另一张卡片的点击窗口。
- `NativeAuctionTest` 新增一项，分别令 AdMob 与 TopOn 胜出，经真实 `NativeAuction` → `NativeCardController` → `NativeDelivery` 逻辑连接可控时钟状态机。落选回调不触发抑制；展示赢家点击/覆盖层触发抑制；卡片销毁后关闭不伪造 DISMISS；后续独立前后台往返恢复，旧点击/打开/重复关闭不重新污染状态。
- 命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :testDebugUnitTest --console=plain`，退出码 **0**。核心 JVM **134/134**，其中竞价 **20/20**、交互状态 **5/5**；没有失败或跳过。
- 基线、完整日志及测试 XML：`build/reports/native/interaction-20261001/` 下的 `baseline.diff`、`baseline-status.txt`、`baseline-head.txt`、`jvm.log`、`test-results/`。以上为本地忽略产物。

该结果是确定性逻辑验证，不是实际浏览器/商店跳转、真实自动开屏展示或手动全屏设备验收；不声明已覆盖 SDK 的回调时序和全屏锁运行行为。未重复既有 Release/R8 或设备测试。**6.5 继续未勾选，总计仍为 15/42**；非零报价、TopOn 配置与确认模板比例、真实宿主和逐来源验收门槛不变。

## 2026-10-01 手动全屏锁边界回归

任务 6.5 补充 `NativeInteractionStateTest` 的手动全屏锁隔离检查：独立 NativeInteractionState 处于覆盖层抑制状态时，FullScreenShowGate 仍可 reserve/commit/complete，手动锁释放不改变该交互状态。静态核对 AutoAppOpenController 的三处 NativeInteractions 检查，以及 FullScreenShowGate/AdLifecycleMonitor 的手动资格路径，未发现 Native 抑制接入手动资格。此测试只证明本层状态与锁操作，不驱动全局 NativeInteractions、真实 SDK 点击、Activity 焦点或手动 SDK 展示，不据此关闭整项。

执行 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :testDebugUnitTest --console=plain`，退出码 0，核心 JVM **135/135**，失败/错误/跳过均为 0。日志与 XML 快照：`build/reports/native/manual-gate-20261001/`。本轮仅新增一个 JVM 用例，未改生产代码，未重跑设备/Release/R8；任务保持 **15/42**。

## 2026-10-01 HealthTracker 真实宿主接入续做

用户提供的真实宿主为 `/Users/jiaoyun/od-fz/HealthTracker`。当前 settings 的 composite build 已替换核心与 Compose 坐标至本 Native 工作树。宿主存在既有未提交修改，本轮保留它们，未提交或清理。

- 现有 View 页面与 Compose 弹窗使用 TopOn Native 配置入口。初始化代码含内部 Debug 的 Pangle 设备测试设置；这仅证明接线存在，不证明目标设备/placement 的测试模式实际成功，也未核验或披露保护配置。未找到 AdMob Native 配置入口，非零报价与模板比例仍缺证据。
- View 包装器增加 layout/retentionPolicy 默认参数，旧页面仍用旧 Custom/销毁策略。Tracker 改用稳定 `standardWithAssets` 工厂及 `RETAIN_WHILE_PAGE_ALIVE`，继续绑定 `viewLifecycleOwner` 和滚动可见性；饮水完成页使用 `NativeLayout.Default`。接入时误认为 standard XML 含媒体槽位，设备检查后已修正为复用 card3 带媒体 XML；仅覆盖新工厂接入，不增加素材驱动排版。
- Compose 包装器透传可选策略，显式使用当前 `LocalLifecycleOwner`，仍默认销毁。宿主主页实际使用 Fragment/ViewPager，而非 Navigation pageEntry；退出及确认 Dialog 已传 `viewLifecycleOwner`。不同现有业务位置保持原字符串及 `_native` 后缀；未新增同位置双实例。
- 新增宿主 `NativeHostLayoutTest`，检查旧/新工厂各两次创建的独立 View 树、必需槽位、空媒体/图标/披露容器和可见广告标识。该测试仅编译，未在设备执行；调用公开 `factory` 检查空快照路径，不证明真实素材快照或 SDK 绑定。测试本身不主动请求 SDK，但宿主 Application 初始化仍可能发起请求，所以没有启动 instrumentation。

使用 JDK 17 和 `gradlew -p /Users/jiaoyun/od-fz/HealthTracker`：

| 检查 | 结果与边界 |
| --- | --- |
| `:app:compileInternalDebugKotlin --offline --console=plain` | exit 1，当前无该任务；是文档任务名不适用，不是源码编译失败 |
| `:app:compileDebugKotlin --offline --console=plain` | 修改前 exit 0，19 秒 |
| `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline --console=plain` | 修改后 exit 0，含新增测试编译；不是设备测试通过 |
| 两工程 `git diff --check` | 通过 |

完整日志保存于库 `build/reports/native/healthtracker-20261001/` 的 `host-compile.log`、`host-compile-debug.log`、`host-final-compile.log`。核心生产代码未变化，复用最近 **135/135 JVM** 结果；未重跑设备、Release/R8、未启动宿主或发起真实广告请求/点击。生产来源保留仍降级释放，共享预加载门槛不变。真实宿主路径输入已具备，但运行、来源画面/音频和用户场景验收未完成，**7.1 保持未勾选，任务合计 15/42**。

## 2026-10-01 HealthTracker ADB 设备验收

用户明确选择 emulator-5580（API 36）并授权更新安装/启动宿主，不清除数据、不点击广告 CTA。本轮未操作 emulator-5560，未修改系统网络/显示、正式广告配置或健康记录；闹钟新建弹窗用 Cancel 退出，未保存。结束时 force-stop 宿主，模拟器保持运行。

| 场景 | 实际结果 | 边界 |
| --- | --- | --- |
| 宿主及测试 APK 构建/安装 | assembleDebug、assembleDebugAndroidTest 成功；两 APK install -r 成功 | 使用当前 composite build，非 Release/R8 验收 |
| 旧/新带媒体工厂独立性 | 首次 1 项失败：media 为 null；改新 withAssets 使用 card3、测试旧工厂使用 card3 后 OK (1 test) | standard XML 不含媒体槽位；factory 空快照测试不证明真实素材快照 |
| Pangle 测试模式 | 初始化前日志 Pangle test source enabled before Ads.initialize；画面 Pangle Test Ads | 仅本设备当前自渲染样本，不证明所有来源或非零报价 |
| Tracker 新 withAssets | 获取、挂载、Loaded、平台曝光与 0 USD paid | 截图 tracker-loaded.png 图标白块，媒体黑底含 SDK CTA；不能判完整视觉通过 |
| Tracker → Settings → Tracker | 离开降级释放/Idle；返回新获取记录和响应标识、Loaded、曝光及 0 USD | 不支持来源按预期重建，不是原 SDK 对象/View 恢复；没有完整注册计数证据 |
| Tracker 后台/正常入口返回 | Home 后降级释放/Idle；SplashScreen 正常入口返回后 UI 有测试广告 | 直接启动未导出 MainAct 被系统拒绝，改正常入口；不据 UI 推断原对象恢复，未验证隐藏音频 |
| 闹钟管理旧 Custom/card7 | 获取成功，渲染失败 native_media_requirement_unknown | 栈定位 requireTopOnCompactMedia；不移除媒体保护检查来掩盖无槽位问题 |
| Compose 闹钟配置弹窗/card7 | 获取成功，同原因渲染失败；取消未保存 | Compose 真实入口已运行，但显示验收未通过 |
| Home 退出弹窗旧 Custom/card3 | Loaded、曝光/0 USD；稳定截图可见视频内容、标题/正文/CTA/AD；Cancel 后 Idle → 最终释放 → Destroyed | 图标仍白块，视频画面两次采样变化不等于音频验收，点击能力未测 |
| 默认布局饮水完成页 | 未运行 | 正常业务入口需先写饮水记录，当前不变更健康数据；非导出 Activity 不改 manifest 绕过 |

证据目录：`build/reports/native/healthtracker-device-20261001/`。构建 `build.log`、修复后 `fixed-build.log`；首失败 `layout-test.log`、最终 `layout-test-fixed.log`；Tracker 切页 `return-log.txt`；后台 `background-log.txt`；最终 Native 日志 `final-native-log.txt`；图像 `tracker-loaded.png`、`tracker-return.png`、`tracker-background-return.png`、`alarm-manager.png`、`alarm-compose-dialog.png`、`exit-old-custom.png`、`exit-old-custom-settled.png`。语言页未出现卡片，不算旧 Custom 通过；`alarm-old-custom.png` 仍是语言页，`tracker-top.png` 是转场，均不可当对应目标画面。

工具限制：gesture.py 导入 get_device_screen_size 失败，改直接 ADB swipe，未改技能；MainAct 非导出启动被拒绝，使用 SplashScreen；语言页系统 Back 未退出，使用页面返回按钮。没有为 SDK 填充进行无限重试，没有点击广告或启动浏览器/商店。本轮核心生产代码未改，复用 135/135 JVM；宿主布局修正已通过重建与设备测试。**7.1、5.4、6.3、7.3 保持未勾选，合计 15/42**。图标空白、Tracker 媒体画面及 compact 无媒体布局是后续待定位项；不能把零收益/曝光覆盖视觉失败，也不能将本视频样本称为 TestImage2 修复。

## 2026-10-01 测试设备代理复验

用户明确授权为测试设备添加代理，并指出上午已确认此方法。电脑当前系统 HTTP/HTTPS 代理为 127.0.0.1:7890，emulator-5580 原 http_proxy 为 null；仅该设备设置为 10.0.2.2:7890。电脑经代理 HTTPS 检查返回 204（不是设备端探针）。重新启动宿主获取 Pangle 测试样本后，tracker-proxy-loaded.png 已观察到红色 Pangle 图标、视频内容、标题/正文/CTA/AD；相关日志保存为 proxy-native-log.txt。

首次导航发生于 Splash，模糊匹配应用名未进入 Tracker；等待主页就绪后准确点击 Tab，只有后一截图算目标证据。图标白块在加代理后本样本不再出现，支持网络因素影响素材加载；前后创意分别为 Video 1 与 Video 3，非同创意对照，不能据此宣称全部空白根因确定或 TestImage2 已修复。此前图标空白不应直接归因库绑定；compact/Compose 的 native_media_requirement_unknown 仍是独立未通过项。

未改源码/SDK/广告配置，未点击 CTA。按用户授权保留测试设备代理 10.0.2.2:7890，宿主复验后已停止；恢复原无代理状态可删除 global http_proxy 设置。任务仍 15/42。

记录脚本首次出现 stdin Non-UTF-8 SyntaxError，未执行文档写入；改用专用 Write 写报告、脚本读取 UTF-8 文件追加，未改变验收结果。

## 2026-10-01 无媒体槽位行为修订与验收

用户明确要求：没有媒体视图就不渲染媒体，不能影响其他素材。此要求替代旧无媒体来源白名单与媒体类型限制，不通过给 HealthTracker card7 强加媒体槽位实现。

### 实现

- TopOn 删除 `requireTopOnCompactMedia` 及调用；保留 `binding.media?.let`，无槽位不获取、创建、绑定或注册媒体，其他素材和 render/prepare 继续。
- AdMob 视频测量及尺寸监听仅在实际有 MediaView 时执行；空媒体继续 nullable media 注册，没有隐藏媒体。
- 有媒体槽位时继续真实媒体、SDK 视频 View、视频尺寸保护；正文、图标及来源披露槽位检查未放宽。技术支持不代表所有来源合规或保证曝光。

### 构建与确定性测试

- 首次未限定 root 的 `assembleDebugAndroidTest` 选中了 Compose androidTest manifest 任务，因缺少 `admobApplicationId` placeholder 失败，见 `core-build.log`。改为 `:testDebugUnitTest :ads-compose:compileDebugKotlin :assembleDebug :assembleDebugAndroidTest` 后成功，见 `core-build-fixed.log`；未改广告配置或 manifest。
- 核心 JVM **134/134**，比此前少一项是删除已取消策略的断言，独立报价测试仍保留。
- 核心 NativeBindingTest 设备 **19/19**，覆盖无媒体其他文字素材绑定、有媒体缺 SDK 视频仍失败及尺寸保护，见 `binding-test.log`。绑定 helper 检查不是完整 provider 模拟。
- 宿主 `:app:assembleDebug :app:assembleDebugAndroidTest` 构建通过，见 `host-build.log`；更新安装成功。

### emulator-5580 真实宿主

沿用已授权 API 36 设备及代理 `10.0.2.2:7890`，未清数据、未点击广告、未保存闹钟或健康记录。

| 场景 | 结果 | 视觉边界 |
| --- | --- | --- |
| 闹钟管理旧 Custom/card7，无媒体 | 获取、渲染、Loaded、平台曝光、0 USD；退出后 Idle/最终释放/Destroyed | `alarm-no-media.png` 可见红色图标、标题、正文、CTA、AD，无媒体区 |
| Compose 闹钟弹窗/card7，无媒体 | 获取、渲染、曝光、0 USD；Cancel 后移除平台容器及最终释放 | `compose-no-media-settled.png` 可见标题、正文、CTA、AD，没有媒体区；图标位置仍为空白，不能称全部素材视觉通过 |
| Tracker withAssets/card3，有媒体 | 获取、渲染、Loaded、曝光、0 USD | `tracker-media.png` 图标、标题、正文、CTA、AD 可见，但主媒体区域空白；该图片样本不能证明媒体完整或视觉未退化 |

两个原失败入口不再出现 `native_media_requirement_unknown`。本轮曝光及生命周期证据见 `host-native-log.txt`，只适用于当前 TopOn/Pangle 样本。先前加代理的视频样本显示图标及媒体，不代表本轮不同创意也完整；空白根因未确定，不直接归因库绑定，也不伪造素材填补。

AdMob 无媒体真实视频 SDK 运行、全部来源合规、隐藏音频、同对象恢复、非零报价及长时老化未验证。默认饮水完成页需写健康记录，本轮未运行。生产来源恢复仍降级销毁，共享预加载切换门槛不变；7.1 等混合任务不勾选，保持 **15/42**。没有提交、推送、归档或 SDK 升级。

## 2026-10-01 三星真机验收

用户授权使用已连接三星。设备 SM_S721U1，Android API 35，1080×2340。更新安装当前 HealthTracker Debug APK 成功，从正常 SplashScreen 入口启动；使用 Pangle Test Ads。本轮未改源码、SDK、广告配置、手机代理/VPN或网络设置，未清数据、点击广告、保存闹钟或健康记录。

| 场景 | 实际结果 | 验收边界 |
| --- | --- | --- |
| 闹钟管理旧 Custom/card7 无媒体 | `alarm.png` 可见红色 Pangle 图标、标题、正文、CTA、两个广告标识，没有媒体区；渲染、曝光及 0 USD 回调 | 当前 Pangle Test Video 1 样本的无媒体其他素材显示通过，不代表所有来源合规 |
| Compose 闹钟配置弹窗/card7 无媒体 | `compose.png` 可见图标、标题、正文、CTA、广告标识，没有媒体区；渲染、曝光及 0 USD | 当前 Pangle Test Image 1 样本通过；Cancel 后 02:40:53 移除平台容器并最终释放，未保存 |
| Tracker withAssets/card3 初次图片 | `tracker.png` 有标题、正文、CTA、AD；图标白块、主媒体空白，虽有曝光及 0 USD | 视觉完整未通过，真机也复现素材空白，根因未确定 |
| Tracker 返回后视频 | `tracker-return.png` 有红色图标、视频媒体中的品牌画面、标题、正文、CTA、AD | 证明该视频样本有可见媒体内容；后续截图换了创意且媒体被裁出视口，不构成同视频连续播放证据；不证明声音或其他创意完整 |
| 切页、真实后台与返回 | 日志有 native_retention_pause_unsupported、移除平台容器/Idle，返回产生新获取与响应身份 | 是按现行能力降级释放并重新请求，不是同对象恢复；滚动可见性期间还出现取消/重建，不能仅凭本轮归因其是否异常 |

限定 Native 日志 `native-log.txt` 内没有 native_media_requirement_unknown。终态按 Home 退后台，02:42:56 有降级释放与 Idle；没有 force-stop，宿主进程可仍存在。截图期间状态栏出现 VPN 图标，本会话未配置，不据此认定网络是空白的根因。

设备导航一次文本查找 Settings 未命中，改按已观察截图底栏坐标操作；随后截图实际位于 Alarm Management，按实际画面验收，不将 settings.png 当设置页证据。tracker-video-later.png、background-return.png、tracker-final.png 的广告不完整或不在视口，不作为完整视觉证据。

本轮完成三星 TopOn/Pangle 的局部真实宿主验收。未测试 AdMob 无媒体真视频注册、默认饮水完成页、广告点击外跳、音频暂停、长时老化、非零报价或全部来源合规。7.1 等混合任务仍不关闭，进度保持 15/42。完整本地进程日志含宿主业务/SDK数据，不发布至外部服务；正文只引用必要 Native 结果。

## 2026-10-01 素材可见性诊断与 AdMob 无媒体真视频

本轮推进 **5.3、5.4、7.3 的子项**，不改变生产 provider、SDK 版本、广告配置、预加载切换门槛或真实来源保留声明。证据目录为 `build/reports/native/image-diagnosis-20261001-024234/`，含修改前快照、实际命令/退出码、诊断日志和截图；本地忽略产物不发布至外部服务。

### Pangle：SDK 素材边界与实际画面

- 使用既有 HealthTracker Debug 和授权 `emulator-5580`/API 36，保持代理 `10.0.2.2:7890`。临时 instrumentation 顺序运行同一生产 `AdsNativeView`/TopOn provider 的 card3 withAssets、card7 和默认布局；成功两轮分别取得三、四个广告，没有自动循环重试或点击 CTA。
- 首次从 Splash 等待 MainAct 的诊断失败，系统日志显示启动 Google AdActivity。改用同 UID instrumentation 直接创建内部 MainAct，仅替换本次 Activity 的临时诊断内容；没有改 manifest、正式导航、偏好或健康记录。此受控页面不计正常业务导航或 Compose 宿主验收。
- 第一轮 `actual-sdk-image-diagnostic-corrected.log` 为 **OK (1 test)，24.799 秒**；第二轮 `actual-sdk-screen-diagnostic.log` 为 **OK (1 test)，31.111 秒**。诊断完成不等于每张广告视觉通过。
- 第二轮 `image-diagnostic-media-screen2.png` 的 **Pangle Test Image 2** 主图、红色图标、标题、正文、CTA 和广告标识已实际观察完整。对应 `sdk-screen-observations.log:40-64`：图标 View 可见、147×147 px、BitmapDrawable 原始 336×336；SDK 主媒体内部 ImageView 可见、1174×427 px、BitmapDrawable 原始 640×640。图标素材端点返回 **HTTP 200 / image/png / 解码336×336**，未输出素材地址。
- 此图片和视频样本的 `getAdType()` 都返回 **UNKNOWN(0)**，`getMainImageUrl()` 为空，素材实际由 SDK media View 提供。不会按测试文案猜公共媒体类型，也不会因为主图 URL 为空就替换 SDK View。锁定 SDK 的公开类型及常量在 `sdk-native-ad-api.log`、`sdk-media-type-constants.log`；当前[官方渲染示例](https://github.com/toponteam/TPN-Android-Demo/blob/main/app/src/main/java/com/test/ad/demo/SelfRenderViewUtil.java)同样优先 SDK media，仅 URL 自渲染分支设置 mainImageView。
- `image-diagnostic-media-screen1.png` 和 `image-diagnostic-default-screen.png` 已观察到不同测试视频的实际内容，`image-diagnostic-compact-screen.png` 的无媒体卡片其他素材可见。没有测声音、完整播放、后台静默或同一对象恢复。
- 初次 `View.draw()` 图片不含视频独立合成表面，黑色媒体区域不能作为真实黑屏证据；后续改用 **UiAutomation 实际屏幕截图**。受控页面的诊断标题与状态栏重叠，不作为完整宿主布局验收，广告素材及披露元素未被该标题遮挡。

本轮的完整 Test Image 2 是新的样本，不是历史空白广告同一响应的修复前后对照。既有模拟器/三星空白仍保留，历史广告已释放且缺当时的资源诊断，**网络/SDK 资源/绑定的历史根因尚未确认，5.4 不勾选**。没有为了“修复”而替换 SDK 媒体、填造素材、添加图片加载器或增加重试。

### AdMob：无媒体槽位的真实视频回归

现有 `NativeLiveLifecycleTest` 新增 `testGoogleVideoWithoutMediaSlotStillBindsOtherAssets`，使用 [Google Next-Gen 官方 Native Video 测试 ID](https://developers.google.com/admob/android/next-gen/test-ads#demo_ad_units) `ca-app-pub-3940256099942544/1044960115`：

1. 活动中原未启用卡片先销毁，用既有 compact XML 工厂创建独立 withAssets 绑定；测试明确移除其媒体槽位，不改 XML 或生产配置。
2. 经过真实普通 AdMob loader，确认素材快照为 **VIDEO**；卡片实际子树不存在 SDK MediaView，没有创建隐藏视频。
3. 原始标题/正文/CTA 绑定一致且可见，广告标识可见；只发生一轮 Loading。
4. 实际屏幕 `google-video-without-media.png` 可见测试图标、标题、正文、INSTALL、Ad 与右上角谷歌广告披露，未点击广告；隐藏后 Idle/移除子 View，最终 Destroyed。

最终 Debug 目标与测试构建/安装通过；`google-video-no-media-device-final.log` 为 **OK (1 test)，5.103 秒**。`google-video-native-current.log` 仅保留本轮 PID 的 Native 结果：加载请求1、填充1、真实曝光1、paid1、show_fail0；ILRD 为 **0 USD**。这补齐当前无媒体真视频技术路径，不声明所有来源合规、视频展示、Release/R8、非零报价或保留能力。

首次构建失败因测试 APK 不直接暴露 SDK 类型，改运行时类型核对且未加依赖。首次设备检查发现 compact XML 实际仍有120dp媒体，修正测试工厂后通过；不是 provider 失败。第一次失败后 `adb exec-out` 拉取不存在截图仍返回0，保留错误文本，最终只在 instrumentation 明确通过且字节为 PNG 后存图。失败及修正日志均保留，不隐藏首次结果。

### 收口与剩余门槛

临时 `NativeImageDiagnostic.kt` 已从宿主源码删除，宿主测试 APK 重建并更新安装成功；诊断源码仅留在忽略的证据目录。提取的 SDK jar 已移除，本轮 smoke 包已 force-stop。代理仍为 **10.0.2.2:7890**，Wi-Fi仍为 enabled/AndroidWifi；未操作忙碌模拟器或三星设备，未清数据、提交、推送或归档。

核心生产代码未修改，未重跑历史 JVM、核心绑定或 Release/R8；不将旧运行结果写成本轮执行。新增永久回归及实际截图、日志构成本轮证据。5.3仍缺完整来源/模板边界，5.4仍缺空白根因与对应修复证据，7.3仍缺音频和完整显示矩阵；**合计保持15/42**，非零报价、确认模板比例及许可隔离等原门槛不变。

最终 `openspec-final-validate.log` 为 **exit0 / valid**，`openspec-final-status.log` 返回 **42项、已完成15、剩余27**；`.wolf/buglog.json` 已成功解析且当时26个ID唯一。没有用诊断成功或零收益勾选整个混合验收项。

## 2026-10-01 SDK库存许可隔离与来源恢复机制

本轮沿用既有HealthTracker/Native ID、JDK17、TopOn **6.6.22.3**、Next-Gen **1.2.1** 与授权 `emulator-5580/API36`。证据根目录 `build/reports/native/continuation-20261001-032622/` 为本地忽略产物；没有改SDK版本、正式广告配置或生产预加载入口。

### 实施与失败前/修复后

- `NativeCandidateCache.clear()` 先递增许可代次，竞价请求捕获并带回原代次。许可已恢复也不会接纳旧请求迟到对象；新增确定性回归先失败，修复后通过。
- TopOn实际公共API有 `checkValidAdCaches()`、`checkAdStatus().getTUTopAdInfo()`、`clearCache(List<TUAdInfo>)`；空库存可能返回null，没有公共网络cancel方法。签名保存在 `topon-public-inventory-signatures.log`；不沿用此前“无SDK清理API”的错误判断。
- 宿主SDK探针证明同placement跨实例共享：第一实例标记10、第二实例标记20，后者仍领到标记10；报价/实际对象requestId匹配，第二次领取为空，Pangle自渲染、eCPM(USD)=0。同实例改标记30后完成通知仍对应旧标记10；选择清理移除该候选后，新实例补货标记40。`host-sdk-boundary-device-fresh.log` 为1/1，具体结果在 `host-sdk-boundary-observations-fresh.log`。这不能证明模板宽度隔离或无竞态报价。
- 永久设备回归 `NativeCacheTransferTest.testOldInventoryCannotBeDeliveredAfterConsentGenerationEnds` 直接预备旧代次TopOn SDK候选，再清理本层许可库存。修复前 `sdk-generation-before.log` 失败：`SDK returned an ad from the invalidated consent generation`；第一次只清理/拒绝旧对象后失败 `native_ad_missing_after_load`，没有把拒绝旧对象当作恢复成功。
- 最终provider把许可代次写入SDK `localExtra`，准备/领取前只选择清理本库标记的旧候选，领取后核对实际对象。没有当前代次候选的旧成功通知只允许一次回调退出后的可取消刷新，复用现有主线程Handler，避免回调内递归领取或无限请求。`sdk-generation-refresh-after.log` 为 **1/1、3.751秒**，旧SDK对象未交付且取得新SDK请求身份。
- 同一永久回归的AdMob官方广告分支 **1/1、4.734秒**；现有普通取货A销毁→B同对象渲染曝光及旧监听断开的回归，TopOn/Pangle **1/1、3.373秒**、AdMob **1/1、4.260秒**。这两项是修复后的普通路径回归，不替代任务6.2的新共享生产库存。

### 1.7 真实UMP资格失效/恢复

`host-ump-live-isolation-device.log`：**1/1、6.486秒**。目标宿主APK及测试APK均由当前核心源码重新构建并更新安装；临时同UID页面关闭业务Native卡片，不写健康记录。

1. 实际UMP初始 `consent=1、canRequest=true、privacy=NOT_REQUIRED`，SDK缓存预备本库标记代次0的真实TopOn候选。
2. 官方emulator调试地域请求返回 `consent=2、canRequest=false、privacy=REQUIRED`。调用现有Native就绪通知，实际库存代次变为1；没有注入假ConsentInformation或修改私有SDK字段。
3. 正常地域请求返回 `canRequest=true、privacy=NOT_REQUIRED`，通知现有门禁后经生产 `TopOnNativeProvider` 取得不同SDK请求身份。日志 `umpSdkIsolation oldEpoch=0 restoredEpoch=1 freshSdkObject=true`。
4. finally再次执行正常地域请求，恢复初始许可状态；没有reset、清数据、点击同意/撤回表单、广告CTA或浏览器/商店外跳。该证据是**真实SDK请求资格失效/恢复**，不表述成用户点击撤回表单。

此结果结合本记录“后台关闭与重建续做”里的真实Activity切换/后台、AdMob destroy及同key重建证据，完成 **1.7阶段0隔离机制**。TopOn本层可控操作为监听/本层任务取消、标记并选择移除旧库存、领取核对；没有证据声明取消SDK在途网络。新共享库存生产后台/补货接入仍属于2.1–2.5，未完成。

### 1.6 原始SDK来源恢复边界

临时原始SDK媒体探针运行三轮，各 **1/1**。使用SDK媒体View、真实素材及SDK `renderAdContainer→prepare` 一次，`onPause/pauseVideo→移除原平台View→重新附着→onResume/resumeVideo`；同一SDK对象及平台View恢复可见内容，`isValid`曝光后/隐藏/恢复均true，曝光总数仍1。

`media-lifecycle/{before,hidden,resumed}.png` 已实际检查：Pangle Test Image 3的主图、图标、文案与CTA恢复后可见，隐藏画面为空。三轮均 `videoStarts=0、progressCallbacks=0、duration=0`；对应audio dump的当前播放器没有该宿主音频会话，历史事件不用于本次声音判断。**没有得到视频静默或恢复证据，不能以静态图片宣称所有Pangle可保留**。公开媒体类型仍UNKNOWN，不能据文案、时长0或内部View树反推公共IMAGE类型。

[官方Debug参数表](https://help.toponad.net/docs/Debug-Mode-Parameter-List)对Pangle Global仅列Native默认样式0；锁定Builder虽有setNativeType，不使用其他平台的值2伪造强制视频配置。[官方测试说明](https://help.toponad.net/docs/How-to-test-ads)要求明确测试模式。此原始SDK受控页面不是库双策略/真实业务导航或完整披露验收；生产AdMob/TopOn handle保留仍降级释放，1.6/4.3不勾选。

### 构建与进度

- `sdk-generation-core-refresh-build.log`：核心JVM、Debug、instrumentation、Compose编译 **exit0**；当前JUnit XML **135/135、失败/错误/跳过0**，摘要 `jvm-current-summary.json`。
- `host-ump-live-isolation-build.log`：宿主Debug及AndroidTest **exit0**，两APK更新安装Success。
- 初始临时探针经历错误初始化FQN、等待无Activity的UMP就绪超时、空SDK库存null及测试编译不暴露Google Identifier类型；分别改用既有Ads初始化、同UID创建已有页面、nullable库存和公开API运行时调用。导出测试配置只在执行内存传递，证据日志脱敏；不把临时夹具失败记为SDK生产能力失败。

**当前16/42、剩余26**。非零报价、模板比例/宽度、逐来源视频恢复、共享库存主链、真实点击外跳和长时/完整视觉验收仍按原门槛保留；没有提交、推送或归档。

## 2026-10-01 真实点击返回与SDK宿主过滤

完成任务 **6.5，合计17/42**。证据根目录为 `build/reports/native/continuation-20261001-032622/`；只操作获授权的emulator-5580，Google使用官方测试ID，TopOn通过公开Identifier及官方 `TUDebuggerConfig.Builder(50)` 开启Pangle调试模式；未点击正式广告、登录商店、安装广告商品或购买，未清应用数据。

### 真实SDK路径与结果

| 路径 | 实际结果 | 日志与截图前缀 |
| --- | --- | --- |
| 单平台AdMob Native→Google Play→返回 | 原生点击有实际session/request身份；返回无自动开屏，手动SDK开屏曝光成功；后续合法前台自动曝光恢复 | `real-click-single-*` |
| 单平台TopOn/Pangle Native→SDK IAB浏览器→Chrome→返回 | 修复前意外出现自动开屏，非假回调；修复后SDK浏览器及主页面返回均无新增自动曝光，后续合法前台自动曝光1→2 | `real-click-topon-browser-back-*`、`sdk-landing-fixed-*` |
| Bidding Native赢家→Google Play→返回 | 两端候选真实可用、报价均0，AdMob同价胜出；点击赢家session=`c03aa3c4-cefa-49ac-80b7-b5ec089a03c7`，返回无新的自动session | `bidding-click-native-filled-*`、`bidding-click-store-destination`、`bidding-click-return-*` |
| Bidding返回期间手动开屏、再合法前台 | 返回探针 `blockedAuto=true fullscreen=false`；手动session=`bbf06e25-c332-481a-9876-8fdb8161cc08`实际曝光；下一次合法前台session=`29a7154c-5064-48ad-91c8-96798c449d85`实际曝光 | `bidding-click-manual-*`、`bidding-click-later-*` |
| Native全屏边界 | Native展示时全屏锁false；`select(NATIVE)` 与 `selectAvailable(NATIVE)` 均拒绝为 `unsupported_ad_format` | 返回/点击前后的`observations.log` |

Google Play停在登录首页，证明实际跨应用商店启动及返回，不声明商店详情加载或安装。Chrome首次运行页亦未登录或更改配置。截图已实际观察；默认销毁策略下返回重新获取的Native不是原对象恢复证据。零价Bidding不替代6.4非零验收，未取得真实TopOn胜出的Bidding样本；两种赢家交互归属另由既有确定性回归覆盖。

### 根因与修复前后

三个自动入口原先各自过滤部分SDK命名空间，漏掉真实 `com.bytedance.sdk.openadsdk.activity.single.IABLandingPageActivity`。点击5秒窗口已经过期时，Chrome返回会把SDK落地页当作业务宿主；最终调度也未再次过滤。

`AutoAppOpenController`共用锁定合并manifest的广告SDK Activity分类，并在最终显示资格再次检查；移除Ads、AdMob和TopOn三个局部过滤/私有helper。过滤仅限自动机会，不改变手动全屏gate、Native交互窗口或来源保留策略。`NativeInteractionStateTest`新增SDK/业务宿主分类回归：`sdk-landing-regression-before.log/xml`为失败前，`sdk-landing-core-after-build.log`构建及全套JVM通过，`jvm-landing-summary.json`为 **136/136、失败/错误/跳过0**。

临时smoke官方调试初始化、自动开屏开关及状态/手动按钮在验收后撤除；恢复原smoke配置。一次临时断言漏掉既有 `_app_open` position后缀，实际曝光已成功，仅修正观察断言，不改事件契约或重复展示。命令/时间/退出码见 `commands-final.jsonl`、`commands-resumed.jsonl`。

验收后恢复smoke源码的 `click-restored-smoke-build.log/install.log` 均exit0/Success；当前核心/Compose lint、核心instrumentation及Release/R8目标/测试包构建 `click-final-core-release-lint-build.log` **exit0**，JUnit `jvm-click-final-summary.json` **136/136**。既有Pangle两个Missing class warning仍在，不据构建扩张运行来源声明；本轮未重跑Release设备点击。OpenSpec严格校验 `click-final-openspec-validate.log` **exit0/valid**，buglog43个ID唯一。


## 2026-10-01 默认业务页真实素材与退出释放

7.1 的默认布局子项补齐：在获授权的 emulator-5580，以临时同 UID instrumentation 直达 HealthTracker `HydrateCompleteScreen`，不经过饮水按钮或健康记录 DAO。仅测试实例在 `onActivityCreated` 设置已有 `BaseInterActivity.entryAdRequested`，避免入口插屏遮挡；未修改生产 Activity、广告配置或用户记录，不将这条诊断入口计为正常业务导航验收。

证据目录 `build/reports/native/continuation-20261001-032622/default-screen/`：

- `probe-build.log`：`:app:assembleDebugAndroidTest` 构建成功；`probe-instrumentation.log`：**1/1，通过，25.035秒，INSTRUMENTATION_CODE=-1**。
- `hydrate-complete-default-screen.png` 与 `hydrate-complete-host-log.txt`：默认卡片的主媒体帧、图标、标题、正文、CTA、Ad 与 SDK AD 披露实际可见。获取记录 `002a7df2-4d21-4e7e-9e9b-69bfe7c79390`、展示记录 `42667f7c-edbe-4d3b-8994-a6e85cee94f9`；SDK曝光一次、收益回调0 USD。来源/domain/warning未提供时槽位隐藏，不能算这些真实素材已验收。
- 点击业务 `btnDone` 后页面结束，日志依次确认移除原平台容器、展示记录及生命周期观察最终释放。
- 文案含 `Pangle Test Video 1` 且树中有 `PAGVideoMediaView`，只证明当前素材与容器画面；没有帧推进、播放进度或音频状态证据，**不声明视频播放、隐藏静默或原对象恢复通过**。
- 临时 `HydrateCompleteDefaultScreenProbeTest.kt` 已删除；`restored-build-install.log` 记录原测试 APK 重建成功及更新安装 Success。探针初次资源ID/SDK编译类路径错误和入口插屏遮挡仅为诊断夹具问题，未据此修改生产绑定。

当前仍 **17/42**。7.1 还缺正常用户饮水路径及真实来源保留；1.6/4.3/7.3不由此截图或零收益关闭。长时 SDK 老化仍在独立核心进程运行，不修改其 APK 或全局显示配置。

## 2026-10-01 长时观测中断与测试构建恢复

6.6 原始 SDK 老化观测目标为3,665,000ms（61分5秒），实际最后保存的心跳为1,261,578ms（21分1.578秒）。`aging-partial-outcome.json` 记录开始时两平台未渲染候选有效、原始未入缓存TopOn对象有效且本层期限null，以及缓存候选的一小时期限；后续心跳仅证明经过时间，**不证明该时点对象仍有效，更不证明一小时到期及释放**。`aging-observation-run.log` 没有最终测试成功结果。

11:54附近获授权的emulator-5580从ADB消失，主机没有emulator/qemu进程，退出底层原因未确定；ADB仅连接三星手机，未改用手机、重启AVD、修改网络/音量/显示配置或用户数据。原SDK对象随模拟器进程结束，不能通过新启动接续原计时，**6.6继续未勾选**。

另外准备的原始SDK视频/暂停/重新附着观察器修正宿主JUnit4约定后构建通过，`raw-video-host-build-after.log`为exit0；`raw-video-host-install.log`明确设备not found，因此未安装或运行，未取得任何新增播放/音频/恢复证据。临时源文件及公共API查询jar已删除。既有Fragment用例补充可选TopOn参数/官方Pangle调试模式及测试线程Identifier获取；最终核心instrumentation编译通过，新增TopOn分支仍未运行，7.2/7.3不勾选。

长时探针初次按HEAD清理误移除本轮已有许可代次回归，发现后按`NativeCacheTransferTest.kt.captured-before-aging`精确恢复；`cmp -s`退出0，保留`testOldInventoryCannotBeDeliveredAfterConsentGenerationEnds`和原SDK初始化。未把恢复到HEAD当作恢复到任务开始状态。

- `source-probe-restored-host-build.log`：无临时探针的原宿主测试APK重建 **exit0**。
- `interrupted-runtime-restored-core-build.log`：保留原回归及新增Fragment测试参数的核心 `:assembleDebugAndroidTest` **exit0**；本轮未重跑JVM、lint、Release/R8或设备测试。
- `interrupted-runtime-openspec-validate.log`：严格OpenSpec校验 **exit0/valid**；不代表未勾选任务验收通过。
- 设备离线阻止将重建的核心测试APK更新安装及删除非敏感`/data/local/tmp/native_aging_status.txt`；这两个设备恢复动作尚未执行。此前默认页测试APK恢复成功与本次离线安装失败分开记录。

**仍17/42**。新生产共享库存尚未接通，非零报价/确认模板比例门槛不改；当前视频静默、来源保留、Fragment新增分支及视觉矩阵还缺实际运行，不能因编译或部分老化记录关闭整项。

## 2026-10-01 5580恢复与TopOn Fragment真实分支

原数据启动现有 `Pixel_9_Pro` 到 emulator-5580（API36、1280×2856、density480；`-no-snapshot-save`），未wipe或改AVD配置。硬件匹配不是旧进程恢复：当前快照没有HealthTracker，原21分钟SDK对象不能接续。核心原测试APK安装Success、旧非敏感aging状态删除exit0；随后宿主按实际输出名重新安装Success。

`resume-topon-fragment-real.log` 首轮 **1测试/1失败，91.331秒**，停在“工厂失败后的明确终态”。真实屏幕 `resume-device-surface.png` 显示Android旧target警告占有焦点；只有开始检查缓存，没有实际Native本层获取。确认系统提示后，不改门禁或超时，`resume-topon-fragment-after-warning.log` **1/1，8.86秒**：

- 官方Pangle调试模式，两策略都收到真实布局工厂失败、显式retry；旧卡片加载两次，重建卡片加载一次。
- 旧viewLifecycleOwner最终销毁、旧卡片不可恢复、新Fragment独立请求及最终清理通过。保留策略日志仍是来源能力不足后降级释放，不是同对象保留通过。
- 四张 `fragment-live-topon-*` 为实际屏幕；“Pangle Test Video1”媒体为黑帧/加载圈且图标未加载，**不计完整视觉、视频播放或隐藏音频通过**。

Google官方测试广告的 `visual-google-default.png` 与 `visual-google-font130.png` 实际主媒体、图标、长正文、标题、CTA及披露可见，SDK validator显示无实现问题。`visual-google-font130-night.png` 虽设置night=yes，固定浅色宿主表面仍浅色，不计深色。320dp全屏截图的SDK浮层挡住正文/CTA下部，不能据此关闭窄屏视觉；另准备真实默认布局的局部深色/窄卡观测。

全局修改已按当前恢复前基线回滚；`fresh-aging-restored-baseline.log` 实测 **1280×2856、night=no、font=1.0、WiFi=1、proxy=null**。未动音量/网络/健康记录/三星。新61分钟对象观测与宿主视频观测已构建安装，最终运行结果另记；不把准备或旧计时算通过。

**仍17/42**；7.2模板配置/完整场景、逐来源音视频与新生产共享库存门槛保持未完成。

## 2026-10-01 默认布局深色窄卡与Google生命周期增量

临时探针复用真实 `createDefaultNativeLayout` 工厂与官方Google Native对象，仅为工厂提供局部Material深色Context、fontScale1.3和320dp卡片宽度，不改变设备全局设置。`visual-actual-default-dark-narrow-run.log` **1/1，12.573秒**，`visual-actual-default-dark-320dp-font130.png` 实屏完整显示Flood-It主媒体、图标、完整长正文、标题、CTA、Ad与SDK披露；SDK validator无实现问题。它证明默认工厂在该深色窄卡上下文的真实绑定，不等同宿主自动night切换、系统分屏或所有来源通过。

已有官方Google真广告场景 `visual-google-existing-cancel-rotation-two-cards.log` **3/3，29.865秒**：同placement不同position双卡片独立释放、Activity重建旧卡片最终销毁/新独立请求、加载期间退出不复活旧卡片。事件与本层Loading状态计数见 `visual-google-real-lifecycle-events.log`，不把状态计数当SDK网络请求次数。TopOn Fragment两策略增量仍见上一节；模板比例/不同宽度未获得。

临时深色方法已移除，原文件hash恢复40EC；`visual-restored-original-test-build.log` **Success4秒**、`visual-restored-original-test-install.log` **Success**，未留新测试/主题配置或修改生产实现。

新SDK老化已进入真实后台计时，`fresh-aging-initial-events.log` 的600048ms心跳实测自然TopOn SDK、两缓存句柄仍有效且原期限不变。不是61分钟终值；另一个UID宿主视频首次20.112秒、前台化后26.936秒仍超时，定位为Kotlin object内部方法被按未改写名字/null receiver反射及吞异常，不是UMP未允许。已修正JVM入口/异常传播并重建安装；不靠增加超时或绕过资格。

**仍17/42**；来源视频/静默/恢复、真实61分钟终值、新库存闲置及模板/非零门槛不由这些子项代替。

## 2026-10-01 新对象61分钟真实老化终值

新一轮独立于此前中断的21分钟对象。`fresh-aging-run.log` **1/1，3676.415秒**；实际后台单调计时 **3,665,009ms**，超过3,665,000ms目标，`fresh-aging-outcome.json`/`fresh-aging-heartbeats.json` 保存63个计时心跳及1个额外终值标记，共64条记录，未改系统时间。

- 未入缓存且观测期间不销毁的原始Pangle SDK对象，在初始、30分钟及61分钟后 `isValid=true`、本层期限null。这是超过一小时仍有效的自然SDK样本，**不是SDK自然TTL到期证明**。
- 两平台缓存句柄的原单调期限始终未变；约3600秒起本层 `isValid=false`。Pangle原始SDK仍有效；AdMob没有可用的公开自然有效性API，非空SDK引用不证明其自然有效。
- 返回前台后的两平台获取均交付新对象，非已过期缓存对象；真实新SDK请求完成。该结果证明本层上限隔离，不证明新共享预加载会话、闲置库存或所有来源的长时策略。
- finally取消加载、释放新旧句柄、清SDK选择缓存和本层缓存、结束测试Activity。原207行源码按 `fresh-aging-backup-NativeCacheTransferTest.kt` 精确恢复；核心原测试APK重建 **Success3秒**、安装 **Success**，两份新建私有老化JSON已删除，主机证据保留。

**6.6仍未勾选，合计17/42**：新生产库存的五分钟闲置、许可变化与前后台长时闭环未接通；原始SDK自然TTL结论只覆盖本次Pangle对象，AdMob公开自然有效性无法据本层包装推断。此处新增真实61分钟证据，不降低任务验收条件。

## 2026-10-01 原SDK视频与隐藏恢复证据收口

`raw-video-fresh-device-run5.log` **1/1，68.273秒**；三个Pangle自渲染样本及12张真实屏幕、6份音频保存于 `raw-video-fresh-*`。综合结果见 `source-retention-integrated-outcome.json`：

| 来源/样本 | 已观察 | 尚不能声称 |
| --- | --- | --- |
| Pangle样本1/3 | 实际媒体多帧不同；隐藏采样时宿主UID10216当前player列表为空，旧player已释放；恢复后新MediaPlayer started/clientVolume静音；原SDK对象/平台容器一致，每样本曝光1次 | 未静音基线下隐藏静默、仅pause而不detach的效果、播放时间线连续及生产隐藏/恢复链已通过 |
| Pangle样本2 | 原SDK对象/平台容器重新附着、曝光仍1 | 图片媒体区域空白，不计完整视觉 |
| AdMob官方VIDEO | 真实视频标记及其他素材；实际填充海报/SDK Play控件可见 | 公共play后进度超时，未证明播放、隐藏静默或同对象视频恢复 |

`raw-video-fresh-media-phase-contact.png` 可见样本1/3动态媒体；`raw-video-fresh-1/3-hidden-audio.txt`、`restored-audio.txt` 分别记录player **87→95、103→111**，恢复播放器为clientVolume静音。公共 `adType=UNKNOWN(0)`、duration/progress=0与视频回调0只说明这些遥测未提供；原汇总的 `videoPlaySupported=false` 为探针派生判断，**不能据此认定实际未播放或SDK不支持视频**。采样静默也不替代全程或未静音音频验证。

AdMob第一次观测因MediaView媒体getter空接收者失败，改从实际NativeAd取公开MediaContent后，`google-video-owned-content-run.log` **1测试/1失败，47.191秒**，明确停在真实进度。实际SDK验证浮层确认关闭后操作Play，两张 `google-video-play-without-popup-2s/5s.png` 仍无媒体帧推进，原因未定位，不增加超时或修改门禁。[官方MediaView契约](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/nativead/MediaView)要求加入View树并 `registerNativeAd(nativeAd, mediaView)` 自动渲染；当前实现遵循该契约，未无依据添加第二次绑定。失败前未执行的7份图片/音频仅保留 `google-video-capture-errors.log`，不作为有效捕获。

两端临时观察源码均已撤除；smoke恢复原40EC快照，宿主原测试APK重建 **Success16秒**、smoke **Success4秒**，两包安装Success。恢复后的既有官方VIDEO无媒体真实场景 **1/1，18.066秒**，实际截图 `google-video-restored-no-media-smoke.png` 标题、图标、正文、CTA及广告披露完整；无SDK MediaView、加载一次、隐藏与最终释放通过。不据无媒体样本声明视频播放。

**1.6/4.3/7.3保持未勾选、17/42**；生产AdMob/TopOn保留仍全部降级释放。未重跑JVM/lint/Release，未改SDK、全局网络/音量/显示配置、健康记录或另一台设备。

最终收口：严格OpenSpec校验 **exit0/valid**，实际清单 **17完成/25未完成/42总计**，buglog **65个唯一ID**；`resumed-final-summary.json` 汇总当前证据而不覆盖此前中断快照。另已移除仅本轮创建的19份宿主私有观测捕获，并结束本次核心测试进程释放可能残留的SDK资源，主机真实证据保留。最终数据核对区分63个计时心跳与额外终值标记，不把所有64条事件断言为63条心跳。未提交、推送或归档。

## 2026-10-01 Pangle附着暂停与卸载隔离验证

`pause-isolation-run.log` **1/1，64.279秒**：TopOn 6.6.22.3官方Pangle调试，3个自渲染SDK样本，各12个阶段，保存 **36张UiAutomation真实截图、36份dumpsys audio** 及原始 `pause-isolation-outcome.json`。这是观测夹具成功完成，不是保留验收通过；派生结论见 `pause-isolation-summary.json`。

| 样本/操作 | 实际结果 | 能力结论 |
| --- | --- | --- |
| 样本2，Pangle Test Video 2，View始终附着 | `pauseVideo()`后两帧相隔1,094ms仍变化；`onPause()`后两帧相隔1,131ms仍变化；UID10213当前player119持续started/clientVolume | 本样本未观察到有效暂停；此前卸载停止不能归功于这些API |
| 样本2，仅SDK取消静音 | `setVideoMute(false)`之后当前player仍clientVolume静音；没有修改全局音量 | 缺未静音基线，不证明可听音频暂停/恢复 |
| 样本2，暂停后卸载/重新附着 | 卸载采样时当前UID播放器列表空；同SDK对象/同平台View重新附着后新player127 started/clientVolume，但两张恢复截图均为黑屏 | 仅证明对象/容器身份及播放器重新建立，不证明视频画面恢复、时间线连续或生产保留链 |
| 样本1/3，Pangle Test Image 3 | 图片媒体区域空白；重新附着同SDK对象/View，曝光仍1 | 不计完整素材/视觉或来源恢复通过 |

三样本探针各只调用一次render，恢复后曝光均1；公共adType仍UNKNOWN(0)、duration/progress及视频回调0。真实视频帧推进再次证明这些0遥测不能解释为未播放。公共 `NativeAd` 暴露pauseVideo/onPause/resumeVideo/onResume/setVideoMute；另查 `TUCustomVideo` 仅有URL与reportVideo*上报，没有播放控制，不能用上报或自建播放器替代SDK容器。结论仅覆盖该锁定版本/官方调试样本，不将内部原因未定位改写为所有生产来源不支持。

临时方法撤除，核心文件与 `pause-isolation-original-NativeCacheTransferTest.kt` **逐字节一致的207行任务前快照**，保留未提交的许可代次设备回归。原核心测试APK重建 **BUILD SUCCESSFUL in 853ms**、安装 **Success**；恢复后的既有无请求位置冲突/旧连接隔离用例 **1/1，0.626秒**。宿主及smoke源码/APK本轮未改，未重跑JVM/lint/Release/R8。

**1.6/4.3/7.3仍未勾选，合计17完成/25未完成/42总计**；生产来源保留继续降级，SDK共享库存未切换。没有绕过门禁、SDK绑定或验证标准；没有修改全局音量、网络、显示配置、健康记录或其他设备。

本次清理/校验终值：仅删除核心测试UID内本探针生成的 **73份私有捕获**，36图/36音频及结果已保留主机；结束本测试进程。严格OpenSpec校验 **exit0/valid**，清单仍17/42，buglog有效且 **67个唯一ID**。未提交、推送或归档。

## 2026-10-01 AdMob视频控制前提与媒体传输补证

复用`NativePreloadTransferTest`、锁定GMA Next-Gen **1.2.1** 和官方测试广告；只调用公开SDK API，不反编译实现、不重复绑定平台View，不以SDK控制器方法存在推断实际可控制。证据仍在`build/reports/native/continuation-20261001-032622/`，入口为`google-control-summary.json`。

### API前提与真实样本

[官方VideoOptions文档](https://developers.google.com/admob/android/next-gen/native/options#custom_playback_controls)说明自定义控制默认关闭、需要申请且广告实际支持，能力受Ad Manager reservation ads限制；[VideoController契约](https://developers.google.com/admob/android/next-gen/reference/com/google/android/libraries/ads/mobile/sdk/common/VideoController)明确play/pause/mute在`isCustomControlsEnabled=false`时无效，stop另受广告位白名单限制。`setAdPersistenceEnabled`为实验性磁盘缓存/refresh能力，不是已展示对象保留开关。本轮未把这些选项加入生产请求。

| 测试输入 | 实际控制资格与观测 | 不能声称 |
| --- | --- | --- |
| 官方AdMob VIDEO，默认控制申请 | `customControlsEnabled=false`，两阶段实际截图/UID音频，进度0；不调用无效play/pause/mute | 不能用公开play进度不变宣称SDK不支持视频 |
| 同官方AdMob VIDEO，显式申请自定义控制 | 仍为false，两阶段进度0；样本曝光1次 | 请求选项不保证实际开启，也不证明暂停/恢复 |
| [官方Ad Manager native-video测试单元](https://developers.google.com/ad-manager/mobile-ads-sdk/android/next-gen/native)，显式申请 | 实际true，临时可见控制UI；初始/播放/暂停/卸载/恢复共9阶段，平台View硬件加速；同SDK对象、曝光1、注册1，但全部进度0且当前UID播放器列表为空 | 初始、播放及恢复实际媒体均黑屏，未建立播放基线；不能证明静默暂停、原时间线恢复或生产保留 |

`google-control-run.log` **1/1，25.231秒**，`google-control-manager-run.log` **1/1，28.99秒**仅表示观测方法完成，不是视频保留通过。保存 **13张1280×2856 UiAutomation真实PNG、13份dumpsys audio**；实际观察初始、播放后与恢复后截图均黑屏，SDK原生验证器显示“No implementation issues found”。`google-control-sdk-logcat.log`同测试UID记录`NotSupportedError: Failed to load because no supported source was found`及后续`The element has no supported sources`。不按“验证器通过”覆盖媒体失败，也不延长原失败超时。

### 同测试媒体的只读环境诊断

从观测SDK GET提取实际测试媒体，保持原URL，未替换SDK素材或自建播放器。普通设备GET和按实际SDK headers匹配的GET各完成一次只读环境诊断：**1/1，3.919秒**与**1/1，3.946秒**。匹配请求结果如下：

| 路径 | 实际终点/响应 | 边界 |
| --- | --- | --- |
| emulator-5580、同测试UID、匹配SDK headers | `asset.gvt1-cn.com`，404，`text/html; charset=UTF-8`；未使用显式HTTP代理 | 这次测试媒体请求没有取得MP4；不是SDK播放器内部网络栈的完整追踪 |
| 主机curl、同URL与匹配SDK headers | `r1---sn-oji3b5-cc.gvt1.com`，206，`video/mp4`，1次重定向 | 支持当前两路径媒体交付不同，不证明设备上的SDK已可播放 |
| 系统/独立WebView只读声明 | `c2.goldfish.h264.decoder`，H264/AAC与WebM的canPlayType均`probably` | 声明不等于实际SDK解码/播放通过，未做独立播放器替代验收 |

原始结果见`google-control-environment.json`、`google-control-environment-matched.json`、`google-control-asset-host-matched.json`。主机HEAD200及Range GET206也各保存；不同方法/路径的成功不覆盖设备404。本轮将媒体交付差异列为播放基线缺口，**SDK失败完整根因仍未完全定位**，不归罪本库绑定，也不泛化至所有真实来源。

### 恢复与验收边界

临时源码保存在生成产物`google-control-executed-probe.kt`；生产文件未改，设备夹具已撤除临时方法，与`google-control-original-NativePreloadTransferTest.kt` **逐字节一致的371行任务前快照**，保留所有既有测试。核心测试APK恢复构建 **BUILD SUCCESSFUL in 720ms**、安装 **Success**；恢复后的既有无请求位置冲突/旧连接所有权smoke **1/1，1.457秒**。仅删除核心测试UID内本轮生成的 **29份私有捕获**，主机原始证据全部保留，停止本测试进程。SDK宿主/smoke APK、全局网络/音量/显示设置、健康数据及其他设备均未改。

首次临时探针Kotlin属性/可空编译失败修正后正常运行，见bug068；PNG误用UTF-8辅助读取仅为工具错误，改用file核对13张真实PNG，见bug071，不改SDK结果。未重跑JVM/lint/Release/R8。**仍17完成/25未完成/42总计，1.6/4.3/7.3未勾选**；生产来源继续降级销毁、共享预加载门槛不变。SDK媒体可播放基线和逐来源真实暂停/恢复仍待满足；未提交、推送或归档。

最终校验：严格OpenSpec **exit0/valid**，清单仍 **17/42**，buglog为有效JSON且 **71个唯一ID**；核心UID本轮私有观测文件核验剩余 **0**。`google-control-summary.json`保留上述终值，不覆盖旧来源或长时记录。


## 2026-10-01 缓存清理重入回归

2.6/2.7 增量：到期对象 `destroy()` 同步重入 `clear()` 时，旧实现仍在遍历 entries，新增回归复现 `ConcurrentModificationException`（22 项中 1 项失败）。修复先移出全部到期对象，再调用平台销毁；put 在到期清理及同 key 替换销毁后重检许可代次，拒绝清理前的入池候选。另补同 key 替换销毁触发 clear 的回归，防止旧代次对象进入新库存。

完整基线、失败与最终日志：`build/reports/native/cache-reentry-20261001-173213/`。使用 JDK17 离线执行 `:testDebugUnitTest :assembleDebug :lintDebug :ads-compose:compileDebugKotlin`，exit0；核心 JVM **138/138**、Debug AAR、核心 lint 与 Compose 编译通过。本轮没有执行设备、Release/R8、SDK 网络、宿主或来源保留验证，不将 JVM 受控重入称为真实 SDK 复现。

保持 **17/42**：2.7 仍缺生产预加载会话期限，2.1–2.5/3.1–3.5 的新库存主链仍未接通；1.3/1.5 非零报价及模板尺寸门槛、1.6/4.3 来源恢复与视觉验收不变。2.6 已完成状态由本轮回归补强，不新增整项勾选。未提交、推送或归档。

## 2026-10-01 共享库存内部控制实施

按用户批准方向新增 `NativeInventoryControl.kt` 和 `NativeInventoryControlTest.kt`，不增加公共API或空SDK适配器，不切换现有普通加载入口。控制单元位于既有 internal/nativeads 库存职责，通过注入 prepare/close/错误报告及单调时钟调度验证；不持有 Activity、View、页面位置或已展示对象。

| 对应任务 | 本批实际实现与验证 | 尚缺的生产部分 |
| --- | --- | --- |
| 2.1 | 同兼容key合并准备、独立幂等需求订阅、状态查看、关闭和旧订阅隔离 | SDK适配、独占真实对象领取及页面接线 |
| 2.2 | AdMob单次启动、持续状态回调、消费不叠加本层load/start | NativeAdPreloader生产入口与回调退出后领取 |
| 2.3 | TopOn消费触发补货、2/4/8秒三次额外重试、重复订阅不重置、显式retry/网络恢复开启新轮 | 真实TopOn共享loader补货、实际网络恢复通知接线 |
| 2.4 | 最后需求解除五分钟、最多四个无需求key、活跃保护、同毫秒闲置顺序、重订阅取消淘汰 | 页面资格/组合订阅及SDK关闭接线 |
| 2.5 | 后台/许可变化取消任务与会话、仅活跃需求恢复、旧回调及关闭重入隔离 | 既有生命周期/UMP向生产SDK适配器接线 |
| 2.7 | AdMob会话起点一小时、轮换不续期、旧对象带旧期限跨页/再入候选缓存回归 | 实际预加载对象领取时绑定原会话期限 |

新增15项JVM回归，核心最终 **153/153，0失败/错误/跳过**。JDK17离线 `:testDebugUnitTest :assembleDebug :lintDebug :ads-compose:compileDebugKotlin` exit0，Debug AAR、核心lint和Compose编译通过。证据根目录 `build/reports/native/inventory-control-20261001/`，包含工作树基线、HEAD、原候选缓存源码、首次未实现编译失败、9项中1项闲置顺序失败、151项中1项错误日志触发Android初始化失败、最终日志和XML报告。未实现时编译失败不是行为复现；后两项均修复后重新验证。

错误出口改为必需注入，避免纯控制逻辑加载Ads/Handler；取消与会话关闭异常独立报告，一个key失败不阻止其他key关闭。SDK适配时使用既有Ads.nativeLog接入此出口。TopOn同placement的不同尺寸key仅表达本层兼容条件，不能据该测试宣称SDK隔离。AdMob prepare回调是持续状态通知，TopOn是一轮终态通知，后续适配必须遵守该差异。

**仍17/42**，不按内部子项关闭含SDK接线的混合任务。本轮没有设备、SDK网络、Release/R8、宿主运行或来源恢复验收，没有提交/归档。下一接线顺序：阶段0非零/R8及模板门槛→平台prepare/close及实际领取适配→页面需求/生命周期接入→队首报价核对、事件来源与真实长时验证；来源保留继续独立降级，不与库存控制混为一项。

## 2026-10-01 真实 SDK 适配增量（尚未切换生产）

真实 AdMob 预加载适配及 TopOn 会话适配已接入 NativeAdCache/NativeInventoryControl；领取复用平台句柄，AdMob 继承原会话一小时期限，TopOn 关闭仅清自身会话标记缓存。不承诺取消 SDK 网络。新增实际适配的核心设备及 R8 用例只完成编译，未在设备运行。普通 load 生产入口未替换，页面需求与完整报价重选闭环尚未接通。

续做修正：环境按平台资格分别停止/恢复；clear 清除包括后台已停止会话在内的控制需求。两项回归及核心共155项JVM通过，Debug与核心设备APK构建通过。新增回归首次编译因尚未实现集合资格接口及clear失败，属于测试先于实现，不是SDK运行失败。此前lint、Compose、Release/R8与HealthTracker两项编译通过；最新环境修正后的对应复验待补。R8仍有既有Pangle缺类警告，不证明相关运行路径。

仅在线另一设备 emulator-5554/P4a_Step_Core，未获本任务设备授权，未安装或请求广告；原授权 emulator-5580 未在线。非零报价、模板尺寸及真实来源恢复仍未通过。保持17/42，未新增勾选、不发布或归档。证据：build/reports/native/sdk-host-wiring-20261001/。

### 真实适配续做最终静态/构建证据

新增逐平台资格隔离、后台clear需求清理、后台停止期间不兼容TopOn模板key仍被保留三项控制回归；核心最终156/156通过。查看/领取必须等待控制就绪，避免SDK回调交付前取货漏记消费补货；两平台库存回调使用既有NativeMainThread.post，离开SDK回调栈再通知。TopOn准备异常失效token并解除监听。新增真实适配设备用例覆盖真实后台关闭和活跃需求前台重建，但仅编译，未运行。

最终库Debug、核心设备APK、lint、Compose编译、Release/R8目标与测试APK构建成功（readiness-final.log）；HealthTracker两项源码/测试编译成功（healthtracker-readiness-final.log）。既有Pangle两项缺类警告仍在。普通业务入口与页面有效需求尚未切换；报价重选闭环、真实设备和非零/来源恢复证据未完成。任务保持17/42，无新增勾选。

## 2026-10-01 生产共享库存切换交付

当前单平台及双平台生产入口均经NativeAdCache.load连接AdMob预加载、TopOn共享库存；真实零价不阻塞，可控非零验证算法，未知不伪造零且paid不替代报价。首版先领实际对象再比价，落选有界保留，不宣称非消费只领赢家。Handler在SDK回调退出后领取；赢家显示期间持有需求，隐藏/销毁释放，同步及异步完成均有回归。单平台冷库存等待30秒，双平台仍默认七秒。TopOn网络onAvailable接入已有恢复控制，但无本轮真实断网恢复验收；加入已有需求不重开耗尽轮次。真实来源保留仍全部降级销毁。

### 最终验证

证据目录：`build/reports/native/production-cutover-20261001/`。

- delivery-build-scoped.log：核心JVM157/157、核心设备APK、核心/Compose lint、smoke Debug/设备APK/Release构建退出0，34秒。delivery-jvm-final.log补hasDemand断言后再次通过；生产源码未再变化。
- delivery-native-smoke-build.log：显式-PnativeSmoke=true的Debug/设备APK/Release构建退出0；默认模式包不当作Native运行证据。
- delivery-device.log：三星SM_S721U1/API35，最终核心生产库存独占测试**OK (1 test)，23.955秒**。两页不同真实对象、取消页不交付、同会话期限、消费补货及后台清理通过。
- live-device-after.log：此前生产版本5项4通过；双卡、返回、退出加载和旋转通过，视频加载超时不计通过。最终smoke复核另见delivery-smoke-device.log。
- R8仍有既有Pangle NetExtParams$RenderType、TTSdkSettings$FETCH_REQUEST_SOURCE两项缺类警告，不声明来源运行路径通过。

### 首次失败与限制

- 单平台最初七秒超时导致双卡/返回native_load_timeout；改30秒后相关场景通过。
- video-final.log：最新视频单项**1失败，91.085秒，Timed out: SDK Loaded**，不能归因媒体绑定，不再扩展播放器调查。
- 受管A→B原测试先finish最后Activity导致真实后台清队列；调整B先launch后仍未完成，主动force-stop，inventory-final.log的Process crashed是中止结果，不算通过。
- delivery-build.log首次hasDemand未定义；补实现/断言修复。delivery-build-fixed.log根任务未限定误选Compose测试Manifest而缺admobApplicationId；限定根任务后通过，不修改广告配置。
- TopOn新设备准备因跨工程凭据访问被拒绝停止，无绕行。没有本轮TopOn生产曝光/补货或最终R8设备结果。
- 文档追加首个脚本因UTF-8声明缺失失败，补编码声明后重写；不是生产代码失败。

新增完成2.1、2.2、2.3、2.4、2.7、3.1，当前23/42；完整后台/跨Activity矩阵、两平台转移、最终R8运行、模板尺寸、来源恢复/素材视觉和新路径长时仍未完成。无SDK升级、播放器改造、提交、推送、reset、clean或归档。

最终Native smoke复核：双卡Timed out: SDK Loaded、退出加载Timed out: request in flight；旋转开始后整条命令达180秒上限停止。无最终OK，不能引用旧4/5作为最终版本整组通过。已force-stop本轮核心/smoke测试包，未清数据。 证据：build/reports/native/production-cutover-20261001/delivery-smoke-device.log。

## 2026-10-01 生命周期失败根因与定点修复

证据目录：build/reports/native/lifecycle-fix-20261001/。先单独复现加载中退出失败；runtime.log显示只有ad_position，无Native ad_load_request。dumpsys window确认DeprecatedTargetSdkVersionDialog占焦点，window.xml确认是本库核心测试包的旧Android版本提示，不是SDK无填充。AdsNativeView的hasWindowFocus资格检查正常阻止加载。

通过系统提示的OK关闭后，原取消用例**1/1、11.548秒**通过，未改生产代码。NativeLiveLifecycleTest.launch增加hasWindowFocus前置等待，让环境遮挡直接报host ready失败而非误报SDK Loaded。测试APK构建通过；新APK下双卡、加载中退出、旋转三项结果见lifecycle-after.log。此结果取代此前被焦点遮挡的未完成回归，不外推来源保留或TopOn新路径。

最终生命周期新测试APK **OK (3 tests)，30.615秒**，日志lifecycle-after.log。受管库存复验定位第二次NEW_TASK启动result code=3、无新Activity，修测试launch为NEW_TASK|MULTIPLE_TASK；不修改生产生命周期。复验结果见managed-fixed.log。

跨页面受管库存最终 **OK (1 test)，16.949秒**（managed-fixed.log），覆盖A销毁、B同一对象真实曝光、自动补货、真实后台关闭及前台按有效需求重建。缺陷是测试NEW_TASK复用而非生产库存；测试launch要求新实例。最终三个生命周期用例 **3/3，30.615秒**，不再列作未定位生产回归。

视频去除焦点遮挡后仍 **1失败，31.147秒**（video-after.log），明确native_load_timeout。仅证明SDK库存本轮未在30秒交付，不证明绑定错误，也不算视频恢复通过。TopOn新增设备结果仍无授权配置，完整混合任务不勾选。

Release构建38秒成功。混跑Debug NativeLiveLifecycleTest于Release进程触发kotlin.Result.Companion NoSuchFieldError（r8-crash.log），属于测试跨APK压缩边界，整组不得计通过。改用既有Java桥接NativePreloadR8Test，结果另记。runtime.log记录达到600秒上限停止，日志已保留，没有自动重启记录进程。

最终Release/R8 Java桥接受管库存探针 **OK (1 test)，13.257秒**，r8-managed-device.log覆盖真实适配领取、原会话期限及SDK自动补货。Debug生命周期3/3、跨页受管库存1/1另有独立证据；不宣称Release完整生命周期或TopOn已通过。已恢复原Debug目标/测试APK（restore-debug.log两次Success），无提交/推送/归档，任务仍23/42。

## 2026-10-01 实际对象比价与 Google 图片生产保留

- 完成 3.3，当前 **24/42**。实际对象价格在最终选择时读取；新增价格由100降为1的回归证明不沿用旧价，重复成功/失败不重复 bid_result。既有取空、过期、缓存独占、竞争页面、模板排除和落选有界保留覆盖保持通过。
- 页面库存获取不再产生伪网络 LOAD_REQUEST/LOAD_RESULT；真实库存准备使用 preload_native 的独立请求身份，不回填 position。SDK自动补货无本层网络请求事实时不伪造事件。新增回归核对获胜、曝光、零收益仍按原页面身份归因。**3.5 暂不勾选**：SDK准备结果目前无逐对象来源/responseId，完整加载→领取关联及补货事件仍有缺口；2.8同样保留未完成。
- 生产 AdMob handle 仅对 IMAGE、无视频、Google AdMobAdapter 启用保留。原始SDK设备用例 **1/1、13.934秒**：注册1次、恢复取货0、曝光总数1、恢复增量0、paid1。生产 AdsNativeView 设备用例 **1/1、6.438秒**：隐藏卸载原平台View，恢复同View，Loading仅1次，最终销毁；恢复截图主图/图标/标题/正文/CTA及披露可见。
- 最终核心JVM **159/159**，Debug/lint/Compose编译及Release/R8构建通过。两项既有Pangle missing-class警告仍在；未运行新的Release图片恢复设备测试。1.6/4.3/6.3涉及完整来源/音频/跨组合矩阵，不按本Google图片子项整项勾选。
- 证据：`build/reports/native/auction-events-20261001/`，含auction.log、final-build.log、release-final.log、image-sdk.log、image-runtime.log、retention-device.log、retention-runtime.log和image-before/after.png。未读跨项目凭据、未提交/推送/归档。

## 2026-10-01 可用首版交付

用户明确要求快速交付可用版本，不要求十全十美。本地交付包：`build/outputs/native-first-release-20261001/native-first-release-20261001.zip`，包含核心/Compose Release AAR、核心依赖POM、Gradle依赖配置和接入说明及SHA256。`:assembleRelease :ads-compose:assembleRelease`通过，ZIP/AAR完整性检查通过；复用最新159项JVM和Google图片设备证据，不重复扩大验证。完整change保持24/42，未完成来源/模板/视频/视觉矩阵不阻塞本首版；未修改版本号、提交、外部发布或归档。


## 2026-10-01 Native 广告位统一封装

NativeRequest 仅提供 position/topOnTemplateAspectRatio/bidTimeoutMillis，ID 收到初始化配置。JVM 160项、0失败、0错误；核心 Debug androidTest APK、Compose 编译、smoke Debug和R8 Release APK及Debug测试编译通过。完整日志：build/reports/native/unified-config/build.log。未执行新接口真实设备加载；不将旧接口设备证据视为本次通过。smoke 来源通过 -PnativePlatform=admob|topon|bidding 初始化选定，Activity 不再覆盖平台或ID。
