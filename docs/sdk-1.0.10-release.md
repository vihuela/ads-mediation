# Ads Mediation SDK 1.0.10 业务更新说明

发布日期：2026-10-18（台北时间）。Maven 坐标为 `com.cashcraft.sdk:ads-mediation:1.0.10`，仓库沿用 `https://maven.pkg.github.com/vihuela/ads-mediation`。源码包名仍为 `com.cashcraft.ads.mediation`。

本版基于 `codex/1.0.10` 的 `4bb8bfd`，仅修改发布坐标、版本配置及文档，没有新增功能或进行重构。旧 group `com.cashcraft` 下的 `1.0.10` / `1.0.11` 来自另一条开发线，不代表本版功能。

## 业务方更新

1. 将原依赖替换为：

   ```kotlin
   implementation("com.cashcraft.sdk:ads-mediation:1.0.10")
   ```

2. 保留原 GitHub Packages、Google、Maven Central、TopOn、Pangle 和 Mintegral 仓库，以及现有私有凭据读取方式。仓库白名单或 `includeGroup` / `includeModule` 过滤器需要允许 `com.cashcraft.sdk`。
3. 移除旧 `com.cashcraft:ads-mediation`，同时检查间接依赖；新旧坐标包含相同类，不能同时打包。源码 import 无需因 group 变更而修改。
4. 重新编译宿主和依赖广告库 API 的模块。若来自旧 main 开发线，不能直接照搬旧 Native renderer 接口，须按[完整接入文档](integration-guide.md)迁移。
5. 按业务实际使用的广告形式验证初始化、加载、展示、点击、关闭、收益和前后台切换，并完成启用 R8 的 Release 构建。

## 本版包含的现有能力

- AdMob、TopOn 及双平台缓存竞价；开屏、插屏、激励场景任务，支持等待、取消、频控及已配置的全屏 Native 兜底。
- AdMob Banner View/Compose 接入、预加载和生命周期管理；TopOn Banner 当前不支持。
- Native View/Compose 接入、实际广告对象比价、预加载及生命周期和保留/销毁策略。具体来源的恢复支持以已有验收记录为准。
- TopOn Core / Tramini `6.6.52`，GMA Next-Gen `1.2.1`。宿主不要另加 legacy Google Ads SDK。
- TopOn 回调身份与收益归属隔离、失败重试冷却和策略通知修复；后台继续加载库存，展示仍受前台条件约束。
- Banner 在展示前才预留频控配额；v2 广告事件、实际曝光 `ad_show` 和持续加载 `sdk_auto` 统计。

## 接入注意

- 推荐设置 `autoShowAppOpen = false`，由宿主调用 `Ads.showOpen(position)`；旧自动开屏配置已弃用，但仍保留兼容默认值，升级不会自动关闭。
- `AdEventName` 穷举 `when` 需覆盖 `SHOW`；通用事件使用 `analyticsParameters()`，按当前 `ad_format`、`ad_session_id`、失败来源与收益契约上报。
- 专用收入平台继续使用 `revenueListener`；不要把 `ad_show`、`ad_impression` 和专用收益回调重复计为多笔收入。
- API、事件及收益详情见 [README](../README.md)、[Banner 接入](banner-integration.md)和 [Native 接入](native-integration.md)。

## 验证边界

本地发布前验证：640 项 JVM 单测通过，0 失败、0 错误、0 跳过；Lint 和 Release/R8 构建通过。生成的 POM 与 Gradle Module Metadata 坐标均为 `com.cashcraft.sdk:ads-mediation:1.0.10`，源码包包含当前 Banner/Native 实现。

本机 token 只有 `read:packages`，上传返回 401；改由仓库手动发布工作流使用具备 `packages: write` 权限的 `GITHUB_TOKEN` 发布。远程发布结果以对应 Actions 运行及包版本记录为准。

本次不新增设备、真实广告填充、点击、收益后台入库或各来源素材恢复验收；构建和单测通过不代表这些业务验收已完成。
