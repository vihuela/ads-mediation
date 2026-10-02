## Why

现有 Native 卡片已具备双平台加载、实际对象比价和有限的未渲染落选缓存，但生产路径尚未接入 SDK 预加载与消费补货；隐藏和 Compose 组合退出仍会释放广告，无法满足同页返回恢复原广告的需求。依据 [原生广告落地方案](../../../docs/native-implementation-plan.md)，本变更将共享库存、按业务位置保留的展示对象和统一素材接入整理为同一条可验证的流程。

## What Changes

- 保留 `NativeRequest`、`AdsNativeView`、`AdsNative` 和旧 `NativeLayout.Custom` 接入；内部复用现有 provider、controller、选择器、缓存和日志出口，避免第二套加载管理链。
- 在锁定版本能力验证通过后，AdMob 使用 `NativeAdPreloader`；TopOn 使用其已有库存能力。优先非消费报价、只领取赢家，消费后补货，落选广告继续留在 SDK；无法可靠关联报价与实际对象的平台采用实际对象比价及有界保留。
- 分开处理共享库存与已领取对象，限制容量、闲置预热、退避、到期和最多一次重选；跨 Activity 只复用有效且从未渲染的广告。
- 为 View/Compose 提供 `DESTROY_ON_HIDE` 与 `RETAIN_WHILE_PAGE_ALIVE`，本稿采用前者为兼容默认。保留模式在同一 Activity、同一业务位置和有效页级生命周期内恢复原广告及平台容器；逐来源验证，不把 Activity 存活视为恢复通过。
- 以现有 `NativeRequest.position` 唯一标识业务广告位置，同位置只允许一个展示容器占用；生命周期提供状态及最终销毁信号，库不生成页面实例编号、不读取导航栈。
- 增加只读 `NativeAssets` 和 `NativeLayout.Custom.withAssets`，由业务决定布局与样式，库负责素材填充、平台注册、曝光点击和真实收益归因；保留模板与自渲染的边界。
- 将原生流程日志收口为简短中文，普通日志记录关键结果，DEBUG 记录落选去向与内部身份；中文注释、取消、迟到收益、所有权和宿主验收随功能完成。
- **BREAKING**：同一 `position` 被多个容器同时使用将明确失败，旧宿主的不同卡片必须改用不同业务位置；原生内部编号及 `AdEvent.slotId` 的处理需先完成兼容性核对，不能无依据删除公共字段或改变其他格式事件。旧单平台和 Custom 源码接入继续验证，公开签名变化不自动等同二进制兼容。

## Capabilities

### New Capabilities

- `page-native-ads`：复用在途 `add-page-native-ads` 的能力名称，汇总页面 Native 卡片的目标契约，更新库存、取货、素材、位置唯一性和双展示策略，并保留必要的渲染、事件和全屏边界。当前 `openspec list --specs --json` 返回空列表，因此以 `ADDED Requirements` 描述未来的完整能力，不对尚未发布的规格伪造 `MODIFIED`。

### Modified Capabilities

无已发布规格需要修改。本变更承接 `add-page-native-ads` 的实现基础和历史证据；旧变更中的“不预取”“隐藏即释放”“组合释放即销毁”“相同 position 多实例”等约束由本变更的新契约替代。其余有效契约在本规格中保留，旧任务与验证记录不因规划生成而勾选或改写。

## Impact

- 核心影响 `internal/nativeads` 的平台 provider、`NativeBiddingProvider`、`NativeAdCache` / `NativeCandidateCache`、`NativeAdHandle`、`NativeCardController`，以及公共 Native 类型和布局绑定。主线程所有权和请求代次继续复用。
- Compose 包装需分离外层容器连接与原位置广告所有权；保持核心 AAR 不强制引入 Compose/Navigation。现有 smoke 宿主承担 View、多 Activity、导航和 R8 验证。
- 价格反射只在现有版本化模块与配置中扩展，事件与日志复用原出口。保持 GMA Next-Gen 1.2.1、TopOn 6.6.22.3 及现有适配器、minSdk 和 legacy GMA 排除规则。
- 历史普通加载器的构建、设备和 R8 结果作为基线；新预加载路径、TopOn 跨 Activity 展示、Pangle 媒体/图标、隐藏恢复及逐来源证据需独立补齐。真实宿主和 Native ID 沿用用户后续提供的输入。
- 本次仅生成规划文档。实施时按 `tasks.md` 分阶段记录；归档前必须将两个在途变更的同名能力合并为一份最终规格，防止旧限制覆盖新契约，不以任一文档校验通过代替实现验收。
- 不包含长信息流复用、任意 Compose 素材插槽、SDK 升级、全屏缓存重构、远程配置系统或 Music 参考工程修改。
