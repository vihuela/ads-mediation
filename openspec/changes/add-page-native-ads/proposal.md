## Why

当前 SDK 仅提供全屏广告能力，宿主缺少可随页面生命周期管理的 Native 独立卡片入口。依据 [Native 实现方案](../../../docs/native-implementation-plan.md)，首版需要同时提供开箱可用的默认卡片和业务 View/XML 布局接入，统一素材绑定、资源释放、事件及收益归因，避免每个宿主自行处理平台差异。

## What Changes

- 新增页面持有的 `AdsNativeView`、支持单平台及双 ID 比价的 `NativeRequest`，以及 `NativeLayout.Default / Custom` 和最小 `NativeLayoutBinding`；默认与自定义布局复用相同绑定流程。
- 接入锁定版本的 AdMob Next-Gen 与 TopOn Native。TopOn 默认入口可接受模板，自定义布局仅接受自渲染结果；模板不兼容明确失败，不忽略业务布局。
- 按所选平台初始化、UMP、页面 active/visible、owner RESUMED、挂载和尺寸决定请求资格；每个实例隔离请求代次、广告对象和资源，支持显式失败重试及幂等销毁。
- 复用现有事件和 ILRD 出口，新增 `AdFormat.NATIVE`，保留页面 position、请求及展示身份；Native 不占全屏锁、不参加全屏竞价，并处理广告覆盖层/落地页返回与自动开屏的交互。
- 增加独立可选 Compose 模块及默认、自定义 XML、Fragment、Navigation 接入示例；核心 AAR 不强制依赖 Compose。
- **BREAKING**：公共 `AdFormat` 新增枚举项，Kotlin 宿主的穷举 `when` 可能需要补充分支；新增公开数据字段须核对源码与二进制兼容性，不能宣称对全部已编译宿主透明兼容。
- 不增加主动 Native 预加载池、下一条预取、自动刷新、信息流复用、纯 Compose 素材插槽或 Native-only 初始化模式；不升级广告 SDK、不依赖尚未实现的 Banner 组件。

## Capabilities

### New Capabilities

- `page-native-ads`：页面内独立 Native 卡片的请求门禁、对象归属、布局绑定、双平台渲染、Compose 接入、事件与收益，以及全屏兼容边界。

### Modified Capabilities

无。`openspec list --specs` 当前没有已登记能力；在途 `add-fullscreen-display-opportunities` 不作为已发布规格修改，其三种全屏格式契约须继续保持。

## Impact

- 新增 Native 公共 API、页面 View、默认布局和两端内部渲染实现；`Ads.kt` 增加最小可取消的所选平台状态订阅，不通过页面反复调用 `initialize()` 等待就绪。
- 调整 `AdEvent.kt`、`AdEventDispatcher.kt` 及相关格式映射，使页面加载不标为全局预加载、Native 会话不依赖全屏占用；`AdRevenue.kt` 仅在现有字段不足时修改。
- 检查 config/provider/compatibility、展示机会及自动开屏入口的格式边界；`Ads.isReady(NATIVE)` 返回 false，由卡片状态表达是否加载完成。
- 新增可选 Compose 模块涉及 settings、版本目录和独立依赖声明；保留当前广告依赖版本、minSdk 26 及 legacy GMA 排除规则。
- 使用现有 JUnit、Debug 构建/lint、Release/R8 smoke 和真实宿主测试，分别记录源码、编译、设备和视觉证据。当前方案中的精确 SDK API、TU Native paid 监听及隐藏/恢复策略须先验证，未验证项不视为能力已实现。

2026-09-30 用户追加范围：参考 remax_sdk 的原生比价，以一个页面请求完成双平台并行加载、价格选择和获胜对象渲染，保留旧单平台入口。用户随后明确要求跨 Activity 复用未胜出对象，新增有界的未渲染候选缓存；实现与验证见 design、spec 和 verification。
