## 1. 锁定版本能力与证据

- [x] 1.1 核对实施时的工作树、共享文件修改和当前广告依赖声明，建立本变更 `verification.md` 的源码/编译、设备、视觉、平台来源记录；以文件清单和版本记录确认未升级广告 SDK、未将 Banner 或在途全屏未完成项当成既有能力。
- [x] 1.2 在现有 GMA Next-Gen 1.2.1 上编译最小单条 Native 加载、事件与 paid 监听、素材/媒体注册及释放调用；将精确 API 与编译命令结果写入证据，确认没有 legacy GMA API/依赖混入。
- [ ] 1.3 在 TopOn 6.6.22.3 上编译最小 loader、自渲染/模板、Native 容器、媒体/图标及销毁调用；用测试广告记录 render/prepare 顺序、模板尺寸和回调线程，确认普通业务 root 的嵌套绑定可行。
- [x] 1.4 核对并接通 TU Native 真实 paid 监听，记录 setter 所属对象、签名、安装时机、实际收入和可用身份字段；以编译及测试广告真实 paid 证据完成，不用全屏监听或 eCPM 替代，未接通保持未完成。
- [ ] 1.5 验证两端隐藏/后台视频、恢复、缓存有效性及 TU 同 placement 双实例的取用和清理行为；记录各平台采用安全保留还是固定释放重建、恢复请求次数和不可回收范围，未通过来源不得声明完整支持。
- [ ] 1.6 核对 Native 覆盖层、点击外跳及返回信号在单平台/Bidding 自动开屏中的路径；用设备事件时序确定有限抑制的开始/结束与兜底窗口，记录无跳转点击、长时间外跳及遗漏关闭回调的处理依据。

## 2. 页面核心与生命周期

- [x] 2.1 定义最小 `NativeRequest`、卡片状态/失败原因及 View 公共入口，明确 Activity/owner/request/layout 身份；通过可编译调用和现有 JUnit 验证无效输入、等值请求不重建及 Destroyed 不可复活。
- [x] 2.2 为 `Ads` 增加可取消的所选平台配置、状态及 UMP 就绪订阅，复用共享监听；验证所选平台失败、未配置、初始化等待/完成、UMP 拒绝/变化、Bidding 另一平台成功及解绑后不继续回调，页面不重复 initialize。
- [x] 2.3 实现主线程页面资格、slot/request/generation 和一次有效在途加载控制；用加载计数验证初始 active/visible=false、零宽度、未附着、失焦、重复恢复/测量/通知不误发或重复请求，成功挂载前重检资格。
- [x] 2.4 实现停用、销毁、Fragment View/导航 owner 结束和迟到对象清理；用确定性回调测试验证先失效后清理、重复释放、A→B→A、身份替换、同 ID 双实例隔离，以及 TU 不盲取共享缓存回收。
- [x] 2.5 实现 Idle/Loading/Loaded/Failed/Destroyed 转换、仅失败且合资格时生效的 retry，以及状态回调异常/重入保护；验证新 requestId、重复 retry 无额外请求、Loaded 不等于曝光、destroy 重入后不挂载，临时不合资格的 retry 不排队。
- [x] 2.6 按阶段 1 策略实现临时隐藏、媒体暂停或释放重建及缓存有效期检查；用可控单调时钟验证 AdMob 待挂载/恢复达到一小时失效、系统改时不影响、持续显示不定时刷新，TopOn 不伪造加载时间或统一 TTL。

## 3. 布局与素材绑定

- [x] 3.1 实现 `NativeLayout.Default / Custom` 和最小 `NativeLayoutBinding`，使用 Activity 主题 Context 创建独立 View 树；以默认和两个业务 XML 接入用例确认同一绑定流程、同工厂双实例不共享 View，position 不决定布局。
- [x] 3.2 实现 root/素材引用/必需项/空占位及工厂异常校验；验证已挂载 root、外部引用、缺项、非空占位和异常均明确失败并释放，不挂载部分广告、不清空宿主其他 View。
- [ ] 3.3 实现默认媒体卡片和共用素材填充规则，由平台提供或绑定媒体、图标、披露元素；用素材有无及不完整创意验证可选项清空隐藏、必需项失败、无重复媒体图片、无宿主广告 click handler。
- [ ] 3.4 实现宽度、媒体比例、最小视频区域及 TopOn 模板尺寸校验与变化处理；以测量/接入检查确认默认卡片不套 Banner 高度、未知模板尺寸失败、实际尺寸变化最多启动一个必要新代次，重复测量不重载。

## 4. 双平台加载与渲染

- [x] 4.1 接入 AdMob 单条加载、统一 Native 事件/paid 监听、素材注册和幂等释放；以锁定版本编译及测试广告验证监听早于挂载/Loaded 通知、收益监听不被覆盖、资格失效不挂载、迟到对象释放。
- [ ] 4.2 接入 TU loader 的页面所有权、自渲染绑定及平台模板分支，按已验证顺序 render/prepare；以测试 placement 分别验证自渲染和模板正常展示、监听清理及资源释放。
- [x] 4.3 实现自定义布局遇到 TU 模板的 unsupported_native_render_mode、默认模板未知尺寸错误及同步渲染异常收口；通过请求/释放计数验证不忽略 layout、不静默回退、不循环取广告、失败不残留平台 View。
- [ ] 4.4 接入平台真实曝光、合法多次点击、覆盖层通知及模板卡片关闭；验证曝光去重、覆盖层关闭不关闭卡片、可靠模板关闭后当前 slot 不再因恢复/测量/retry 自动出现。

## 5. 事件与收益

- [x] 5.1 扩展既有事件分发支持业务 Native position、周期关联和不依赖全屏占用的广告身份；复用计数/日志/分发，用事件断言确认每 slot 一次 position、后缀为 _native、页面加载不是 preload_native，旧全屏默认事件保持原样。
- [x] 5.2 实现每本层请求一对 load_request/result 及未曝光尝试的 show_fail 终态；验证取消后迟到成功、加载成功后渲染失败、retry 多次失败、周期未曝光结束、已曝光后离页，终态不重复且取消不冒充 no_fill。
- [x] 5.3 按原广告保存 request/session/平台 response 或 show 身份并接入真实 ILRD；以两个广告回调交错、paid 先于 impression、零金额、重复 paid、非法金额及身份缺失用例确认事件/payload 分别去重、不用 eCPM、不归给当前错误广告。
- [x] 5.4 实现旧 UI 失效与合法迟到收益的分离及监听异常隔离；验证广告 A 释放后收益仍归 A、广告 B 状态不变、事件异常不吞收益 payload，收益身份不持有 Activity/View。
- [x] 5.5 为单平台 Native 事件使用实际 ADMOB/TOPON mediationMode，核对必要公开字段及兼容性；通过 Bidding 配置下单平台 Native 事件测试确认无 ad_bid_result，并记录枚举穷举和数据类源码/二进制影响。

## 6. 全屏边界与自动开屏交互

- [x] 6.1 为 `AdFormat.NATIVE` 补全 config/provider/compatibility、竞价和全屏遍历的显式分支；验证 isReady(NATIVE)=false、通用全屏机会稳定失败 unsupported_ad_format、全屏 ID 不接收 Native，卡片存活不占全屏锁。
- [ ] 6.2 按阶段 1 已确定的信号与窗口接入最小 Native 交互抑制，覆盖 AdMob/TopOn/Bidding 自动控制器并提供页面机会资格接入示例；用可控时序测试验证覆盖层期间抑制、首次外跳返回不紧接开屏、后续合法机会恢复、旧关闭不清除新交互、无跳转 click 不永久抑制。
- [x] 6.3 运行既有全屏事件、竞价、展示机会及自动开屏相关 JUnit 回归，并补 Native 格式边界用例；记录既有三种格式的等待/取消/奖励等行为未被本变更改变，原在途设备验收缺口单独保留。

## 7. Compose 与宿主接入文档

- [x] 7.1 创建或复用独立 `:ads-compose` 模块，确认 Kotlin/AGP 兼容的 Compose/Lifecycle 版本及独立依赖声明；构建核心和模块并检查依赖图，确认核心不强制带入 Compose/Navigation、当前广告版本和 legacy GMA 排除规则不变。
- [x] 7.2 实现 `AdsNative` 的 Preview 占位、身份 key、factory/update/onRelease 和最新回调同步；在 Compose 测试宿主验证稳定 layout/等值 request 重组无新增请求、owner/layout 替换释放旧实例、Preview 不执行广告或自定义工厂。
- [x] 7.3 提供默认卡片、自定义 XML、Fragment viewLifecycleOwner、具体 NavBackStackEntry 及同 ID 双卡片示例；用可编译示例和离页/返回操作核对 active、visible、remember 布局和最终释放，不把 Activity owner 或 Dialog 暂停误当页面永久归属。
- [x] 7.4 更新 README/接入说明中的状态、失败原因、模板限制、平台隐藏策略、实际 mediationMode、测试配置和迁移提示；逐项对照公开签名及规格确认未承诺信息流复用、Native-only 初始化、自动刷新或未验证网络支持。

## 8. 集成与验收

2026-09-29 续作：三星 AdMob 实机生命周期新增 4 个用例均取得通过证据，覆盖隐藏/恢复、Dialog、详情返回、旋转、同位双卡片和加载中销毁。8.3/8.4 仍缺真实广告 Fragment 重建、失败后 retry、视频及自动开屏完整矩阵，保持未勾选；详见 `verification.md` 的“三星 AdMob 生命周期自动验收”。整体仍为 26/39。

2026-09-29 TopOn 续验：三星与 emulator-5560 的模板请求均因缺少已确认比例而安全失败；Pangle 自渲染视频在 emulator-5560 通过基本 Loaded/paid/impression，隐藏与后台均回到 Idle，恢复各触发一条新请求。同 placement 第二张卡片仍可能 `native_ad_missing_after_load`。后台音频及自动开屏交互未验证，1.3/1.5/1.6 保持未完成；详见 `verification.md` 的“TopOn 第一批续验”。整体仍为 26/39。

2026-09-29 TopOn compact 续验：自定义 XML 路径明确拒绝模板 `unsupported_native_render_mode`；Pangle 视频进入 Loaded/paid/impression，但截图媒体区黑屏。隐藏后转 Idle 且无障碍树移除素材，保存截图与脱敏日志；不据此勾选跨平台/后台/外跳任务，整体仍为 26/39。

2026-09-29 标准尺寸策略：Native smoke Debug 新增显式 `templatePreset=healthtracker-4x1` 历史候选，仅用于 TopOn 默认布局，不成为核心库或 Release 的默认比例。三星真实模板样本通过 1048×262 布局/实测尺寸、单请求、隐藏清空，并取得 AdMob 来源的曝光/paid；截图基本可见但标题省略，不代表完整视觉矩阵或后台比例确认。另开 custom 用例仍明确拒绝模板，未得到本轮自渲染样本。模板与自渲染入口/证据已分开；1.3 等混合任务保持未完成，总计仍 26/39，详见 verification 的“TopOn 标准尺寸候选与分路验收”。

- [x] 8.1 执行 `./gradlew testDebugUnitTest assembleDebug lintDebug :ads-compose:assembleDebug :ads-compose:lintDebug`（模块复用时替换实际路径），处理本次引入的问题；保存退出码及摘要，区分 JVM、构建和 lint 证据，不反复运行未变化检查。
- [ ] 8.2 扩展 `:r8-smoke-app` 的测试 Activity 与两平台 Native 初始化/默认及自定义渲染可达入口，执行 `./gradlew :r8-smoke-app:assembleRelease`；检查依赖不混入 legacy GMA，真实安装运行后确认无相关缺类/缺成员，原全屏反射路径继续可用，构建与设备结果分别记录。
- [ ] 8.3 使用官方测试广告和平台测试配置验收首页→详情→返回、Fragment View 重建、旋转、快速离开、双卡片和断网/no_fill 后 retry；记录设备、版本、请求次数及清理证据，禁止以生产广告点击验证或将同一 placement 的对象跨实例复用。
- [ ] 8.4 在真实宿主验收 Dialog、父容器隐藏、前后台视频、落地覆盖层及浏览器/商店返回，分别覆盖所用自动开屏模式；记录隐藏交互/媒体、恢复策略和有界抑制结果，证明本次返回无意外开屏且后续合法机会可恢复。
- [ ] 8.5 对默认、自定义及 TU 模板分别完成图片/视频、素材缺失、长文案、大字体、深色、窄屏与分屏视觉验收；保存可复核截图/日志，确认媒体、AdChoices、广告标识、CTA、对比度及点击区域无裁切、遮挡或扩大。
- [ ] 8.6 对 TopOn 实际启用的 GMA Next-Gen、Meta、Pangle、Mintegral、ADX 等 Native 来源逐项核对 placement、渲染类型、版本、填充及真实收益；记录实际启用清单与各来源证据，无配置/无填充项保持未验证，不以全屏成功代替。
- [x] 8.7 按实际证据更新本变更任务和 `verification.md`，执行 `openspec validate add-page-native-ads --type change --strict --no-interactive`；完成标准为文档/实现一致且所有完成项可追溯，缺少 TU paid、设备、视觉或来源验收时保留相应未完成项，不将规划通过视为实施完成。

2026-09-30 可选媒体续作：共享 TopOn 绑定仅在 media 非空时绑定媒体；无媒体仅放行可靠识别的 Pangle 图片且有图标，明确媒体要求及未知来源/类型继续失败。核心 93 项 JUnit、9 项 Android 绑定回归、核心构建/lint、Host Debug 构建与 OpenSpec 严格校验通过。三星公开 API 探针确认当前 Pangle 返回 UNKNOWN(0)，用户无法保证广告位仅图片，紧凑布局实机验收仍未通过；CARD3 主图/图标、Loaded 与 Pangle 曝光回归通过。未完成项不勾选，仍为 26/39，详见 verification 的“TopOn 可选媒体绑定与 UNKNOWN 实机限制”。

## 9. 用户追加：原生加载/渲染重写与跨平台比价（2026-09-30）

- [x] 9.1 按 TopOn 官方文档/demo 重写加载所有权、监听、render→prepare、素材优先级及资源释放；锁定 SDK 编译并保留必要素材与模板边界检查。
- [x] 9.2 保留单平台 API，以双命名 ID 请求接入页面内并行竞价、统一 USD 单次价格、超时、失败候选清理与获胜对象直接交付。
- [x] 9.3 增加确定性竞价/价格/取消/回调重入回归，验证事件与迟到 paid 的获胜身份；更新 View/Compose 共用接入文档。
- [x] 9.4 验证本轮最终源码的 Android 素材绑定、官方测试广告比价入口和 R8 后实际报价读取；逐项记录证据边界。
- [ ] 9.5 补齐 TopOn 模板、多个来源和双端有效价格竞争的真实广告验收；历史未覆盖矩阵不由 JVM 或构建代替。

- [x] 9.6 按用户指定加入跨 Activity 落选缓存、独占取出、回调重绑、容量/过期/许可清理及确定性归因测试。
- [ ] 9.7 在锁定 SDK 上验证 A 销毁后 B 取用相同对象并完成真实曝光；分别记录 AdMob 和 TopOn 的来源与模板范围。

9.7 当前证据：AdMob 在 instrumentation 及 R8 公共入口均完成跨 Activity 缓存取用和真实曝光；TopOn/Admob 模板完成同对象转移及 isValid，但缺少已确认比例，完整展示未通过。9.5 已有 R8 双端 0 / 0.002 USD 报价及 Pangle 胜出，媒体/图标空白仍未验收。用户将提供真实工程与 Native ID 后继续，不额外重跑缺少配置的用例。
