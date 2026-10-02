# add-page-native-ads 核验报告

日期：2026-09-29。核验对象：当前工作树的 proposal、design、delta spec、tasks、实现及已有验证产物。

**最新状态（随后 apply 修复）：初次核验的 C1、C2 实现缺口及 W1、W2 测试/文档缺口均已处理。当前 26/39 项完成，剩余完整展示和来源验收尚未完成，暂不归档。**

| 项目 | 修复与新证据 |
| --- | --- |
| C1 | AdMob 读取真实 `MediaContent.hasVideoContent`，注册前测量填充后的 SDK MediaView；不足 120 × 120 dp 明确失败，后续布局缩小时隐藏并释放。真实 Android 测量覆盖过小自定义区域、窄默认卡片、容器 padding、合法边界和后续缩小/解除监听。 |
| C2 | 比较请求使用的有效内容宽度，在实际布局及挂载前重检；padding 改变时使旧代次失效。受控模板通过加载中/已展示变化、布局前迟到结果、不重复重载的 Android 回归。 |
| W1 | 新增 4 项 Android runtime 测试，读取真实 Ads → UMP 快照/provider 状态链。另修复通知列表快照中已解除监听仍可能被调用的问题；每个订阅有独立有效标志，异常监听保持隔离。任务 2.2 已完成。 |
| W2 | 接入文档补充 SDK Native 点击取消手动机会、页面资格检查、首次返回不创建机会、后续业务操作恢复的具体示例。任务 6.2 所需完整 SDK 信号/自动模式设备时序仍待验收。 |

新验证：JVM **92/92**；API 33 上尺寸/绑定/Fragment **10/10**、就绪订阅 **4/4**、Compose/Nav 宿主 **4/4**，设备合计 **18 项通过**；核心/Compose Debug 和 lint、smoke R8 构建通过。过程及原始日志见 [verification.md](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/openspec/changes/add-page-native-ads/verification.md)。这些检查没有使用生产广告填充；真实 SDK 视频比例和 TopOn 模板展示/来源矩阵继续按用户安排后续验收，因此任务 3.4 等混合验收项暂不整体勾选。

后续 Pangle 排查：默认 TopOn 媒体已支持有效素材比例随内容宽度测量，并统一处理 SDK 已挂载媒体的布局参数；10 项 Android 尺寸/绑定回归及 92 项 JVM 检查通过。Pangle Test Image 3 在调整后仍空白：媒体树尺寸为 976×540px、可见，但内部 ImageView 的 Drawable 为空。该视觉问题仍未解决，详细运行对照见 verification.md 的“TopOn 媒体尺寸改进与静态空白复测”。

**以下保留初次核验的修复前记录**，用于追溯问题与验收依据；其中的“当前行为”和 25/39 完成度指修复前快照。

后续实机进度（2026-09-29）：三星 Pangle/Meta 定向静态广告均已显示，收到真实曝光与 paid；随后 AdMob 默认 View 的 4 个实机生命周期用例均取得通过证据，涵盖隐藏/恢复、Dialog、详情返回、旋转、双卡片和加载中销毁。模拟器静态空白仍未定位，完整模板/视频/来源/自动开屏矩阵仍未完成，任务继续 26/39。精确测试轮次及缺口见 verification.md 末尾；此更新不将既有初次审阅结果重记为新一轮代码审阅。

初次结论：发现 2 项 CRITICAL 实现缺口、2 项 WARNING 文档/测试缺口。生产展示和逐来源验收按用户决定延期；这些未验收项不等于已确认的代码缺陷，也不记为通过。初次 verify 只新增本报告，未修改实现或任务勾选，未重新运行 Gradle、设备测试或真实广告请求。

## 总览

| 维度 | 结果 |
| --- | --- |
| 完整性 | 25/39 个任务已完成；规格包含 21 个 Requirement、50 个 Scenario。剩余 14 项包含实现缺口、可独立补齐的测试/示例，以及延期的设备和来源验收，不能统称为生产验收。 |
| 正确性 | 页面资格、代次失效、释放顺序、实例隔离、迟到收益和全屏格式边界已有实现与测试依据；媒体最小尺寸及模板有效宽度变化处理存在缺口。 |
| 一致性 | 页面独占、显式平台、View/XML 共用绑定、可选 Compose 模块、固定释放重建和共享自动开屏抑制整体符合设计；未发现需要新增架构层或升级 SDK 才能解决的问题。 |

本轮执行 `openspec validate add-page-native-ads --strict --no-interactive` 和 `git diff --check`，均通过。它们验证规格格式及 diff 格式，不证明下面的行为要求已满足。

## CRITICAL：归档前必须修复

### C1 — Google 视频的最小媒体区域没有在实际渲染路径落实

- 对应规格：[spec.md:193](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/openspec/changes/add-page-native-ads/specs/page-native-ads/spec.md:193)，任务 3.4。默认和自定义路径的 Google 视频媒体区域至少为 120 × 120 dp。
- 位置：[NativeLayout.kt:26](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/NativeLayout.kt:26)、[AdMobNativeProvider.kt:175](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/nativeads/AdMobNativeProvider.kt:175)。
- 触发条件：自定义布局提供 60 × 60 dp 的空媒体容器，其余必需引用合法；或默认卡片所在宿主过窄。
- 当前行为：binding 只验证引用结构、空容器及广告标识；页面资格只要求内容宽度大于零。SDK `MediaView` 以 MATCH_PARENT 填入该容器后直接注册广告，没有按实际视频检查媒体区域。默认布局的 180 dp 媒体高度也不能保证宽度下限。文档写出要求不能阻止上述不合规格的路径。
- 建议：在实际测量和视频信息可用后校验媒体区域；空间不足时明确失败或等待满足约束，不能进入可展示状态。尺寸变化后仍须保持该约束。补充过小自定义区域及窄宿主的可运行检查，避免只检查 XML 声明值。
- 证据边界：这是源码确认的缺失校验；本轮没有复现真实视频尺寸违规。未据此断言所有固定高度媒体都会破坏素材比例。

### C2 — TopOn 模板遗漏外层宽度不变时的内容宽度变化

- 对应规格：[spec.md:174](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/openspec/changes/add-page-native-ads/specs/page-native-ads/spec.md:174) 及 186–189 行，任务 3.4。请求与展示尺寸必须一致，实际尺寸变化只启动一个必要的新代次。
- 位置：[AdsNativeView.kt:123](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/AdsNativeView.kt:123)、[TopOnNativeProvider.kt:75](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/nativeads/TopOnNativeProvider.kt:75)、[TopOnNativeProvider.kt:148](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/internal/nativeads/TopOnNativeProvider.kt:148)。
- 触发条件：外层 View 宽度保持 360，加载后因窗口 inset 或宿主调整，水平 padding 总和从 0 变为 40，有效内容宽度降到 320。
- 当前行为：失效逻辑只在 `onSizeChanged` 的外层宽度变化时触发；`contentWidth` 却扣除了 padding。外层尺寸不变时不会触发这一分支。若加载尚未完成，请求按旧宽度创建、render 按新内容宽度创建模板；若已展示，模板保留旧的固定宽高。两种情况都可能使请求、可用空间和展示尺寸失配。
- 建议：跟踪布局后的有效内容宽度，并与当前代次使用的宽度比较。变化时按已有 `sizeChanged`/代次机制处理，同一宽度的重复布局保持幂等。补充“固定外层宽度、修改 padding”的加载中和已展示检查。
- 证据边界：源码可确认尺寸失效路径遗漏；本轮未用真实 TopOn 模板复现裁切。修复与回归不需要先取得新的生产 placement。

## WARNING：归档前应补齐的证据和示例

### W1 — 就绪订阅的真实通知链尚缺验证

对应任务 2.2。[Ads.kt:677](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/main/java/com/cashcraft/ads/mediation/Ads.kt:677) 已有可移除订阅，初始化和隐私回调也有通知路径；[NativeAvailabilityTest.kt](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/src/test/java/com/cashcraft/ads/mediation/internal/NativeAvailabilityTest.kt) 验证的是纯状态判定，控制器测试验证门控变化。当前证据未覆盖真实订阅链的 UMP 变化、Bidding 两端完成顺序及解绑后不再回调。建议用可控通知补齐这一集成边界；这是测试缺口，未确认存在通知丢失或泄漏，不应等待逐来源生产广告验证才能推进。

### W2 — 手动全屏机会只有文字提醒，缺少约定的接入示例

对应任务 6.2。[native-integration.md:103](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/docs/native-integration.md:103) 提醒通过 `isSceneValid` 控制机会，但没有把 Native 交互/页面状态接入手动展示机会的具体示例。现有交互票据测试不能替代这一接入约定，smoke 又关闭了自动开屏。建议补一个复用既有机会 API 的最小示例，明确覆盖层期间的页面资格，以及返回后的合法机会恢复；不需要引入新的全屏调度器。实际 SDK 信号和三种自动开屏模式仍按后续设备验收安排记录。

## 未完成任务分类

任务保持原状态，不把“实现已存在”直接等同于混合任务完成。

| 任务 | 当前缺口及后续处理 |
| --- | --- |
| 1.3、4.2 | TopOn API 已编译、自渲染已有证据；模板正常展示、尺寸及完整 render/prepare 行为仍待实际模板验收。 |
| 1.5 | 固定释放重建和同 placement 不复用已实现；视频隐藏/恢复、底层缓存和同位双实例完整行为仍待设备验收。 |
| 1.6、4.4 | 事件和交互抑制路径已接通；真实覆盖层/外跳、可靠模板关闭及自动开屏时序仍待验证。 |
| 2.2 | 订阅实现存在，真实通知链的可控验证待补，见 W1。 |
| 3.3 | 默认/自定义绑定及必需素材规则已有测试；素材组合和来源视觉未完整通过，尤其既有 Pangle 静态媒体空白尚未解释。 |
| 3.4 | 存在 C1、C2 两项代码缺口；修复后再完成实际比例、窄屏、模板变化验收。 |
| 6.2 | 共享抑制和纯状态测试存在；补 W2 示例，真实 SDK 信号与各自动模式的时序仍待设备验收。 |
| 8.2 | Debug/R8 构建和部分 Native 运行有证据；原全屏反射路径及两平台全部 Native 路径没有完整运行证明。 |
| 8.3 | Fragment/Compose 宿主、门控和真实 Dialog 有部分证据；旋转、分屏、断网/no_fill、快速离页等完整矩阵未完成。 |
| 8.4 | 真广告视频、前后台静默、覆盖层/浏览器返回及三种自动开屏模式的完整矩阵待验收。 |
| 8.5 | 默认、自定义、模板的图片/视频、素材缺失、长文案、大字体、深色和窄屏视觉矩阵待验收。 |
| 8.6 | Pangle 已有填充和真实收益，视觉仍有未解决项；GMA Next-Gen、Meta、Mintegral、ADX 等逐来源 Native 证据待补。 |

## 已有证据复核及其限度

完整过程记录见 [verification.md](/Users/jiaoyun/.codex/worktrees/73e5/ads-mediation/openspec/changes/add-page-native-ads/verification.md)。以下为本轮读取已有产物的结果，不是本轮重新执行测试：

- JVM XML 汇总：**92 tests、0 failures、0 errors**，涵盖既有全屏及 Native 控制器、事件/收益、格式边界和交互票据；不能替代实际 View 媒体尺寸及 SDK 行为测试。
- Compose/Nav instrumentation：**4 tests passed**；最终素材 binding instrumentation：**5 tests passed**。较早 Fragment/binding 日志也通过，其中 binding 与后续检查重叠，不将结果相加宣称更多独立用例。
- 已有 Debug、lint、R8 日志为成功；保留两条 Pangle 缺类 warning：`NetExtParams$RenderType`、`TTSdkSettings$FETCH_REQUEST_SOURCE`。没有证据将它们判定为所有相关运行路径均安全。
- 最终 R8 包的 `mode=nav`、`active=false` 启动记录证明入口可达、观察窗口没有 Native 请求；不证明最终包的全部来源展示通过。
- 已有 AdMob 官方测试广告填充、曝光和 0 USD paid，以及 TopOn Pangle 2000 micros USD paid 证据。TopOn 视频结束页可见不能消除早期不同静态创意媒体空白的未决问题。
- 真实 Nav Dialog 的 UI 与事件日志能对应暂停和恢复；不把其他仅点击过的 UI、陈旧截图或纯状态测试当成完整真广告宿主矩阵。

## 初次核验建议的后续顺序（修复前）

1. 在 apply 中修复 C1、C2，增加针对实际尺寸和有效宽度变化的检查。
2. 补 W1 的订阅链测试及 W2 的手动机会示例，再核验相关差异。
3. 按用户安排完成剩余生产环境展示/来源验收，逐项保存证据、更新任务；未验证项继续保持未勾选。满足规格及任务验收后再归档。

初次 verify 未提交、发布、归档或修改实现；随后的 apply 修复由用户另行授权，结果见报告开头。
