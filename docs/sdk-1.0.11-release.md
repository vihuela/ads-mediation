# Ads Mediation SDK 1.0.11 业务更新说明

Maven 坐标：`com.cashcraft.sdk:ads-mediation:1.0.11`。仓库仍为 `https://maven.pkg.github.com/vihuela/ads-mediation`，源码包名仍为 `com.cashcraft.ads.mediation`。

## 业务更新

```kotlin
implementation("com.cashcraft.sdk:ads-mediation:1.0.11")
```

从新 group 的 `1.0.10` 升级只需修改版本号。若来自旧 `com.cashcraft:ads-mediation`，请替换 group 并移除旧依赖，避免同名类冲突；仓库过滤器需要允许 `com.cashcraft.sdk`。完整迁移要求见 [1.0.10 发布说明](sdk-1.0.10-release.md)。

## 本版变化

本版包含提交 `fd3aed6` 的竞价埋点修正：`ad_bid_result` 中 `admob_value`、`topon_value`、`winning_value` 仅在金额合法时输出 USD 数值；未知、负数及非有限值省略对应字段，无胜者时省略 `winning_value`，真实 `0.0` 保留。通过价格可用性布尔字段和 `result` 区分未知报价及无候选。

如果统计后台已把金额属性建为字符串类型，需要业务方核对并处理后台元数据；客户端升级不会自动修改既有字段类型。

本次未重构。新 group 的原 `1.0.10` 保留，不覆盖；旧 group 下同版本属于另一条开发线。

## 验证与发布记录

该源码已在本地通过 641 项 JVM 单测（0 失败、0 错误、0 跳过）、Lint 和 Release/R8 构建，并在 [CI 任务](https://github.com/vihuela/ads-mediation/actions/runs/39108865423)中通过完整验证。该任务后续因 GitHub 不允许单独删除最后一个版本而停止，未删除原包；随后的包级替换任务已取消。

已于 2026-10-18 16:05:25（台北时间）正常发布，[发布任务](https://github.com/vihuela/ads-mediation/actions/runs/39109729329)成功，发布提交为 `4bbdcf4`；[远程 1.0.11](https://github.com/vihuela/ads-mediation/packages/3320026?version=1.0.11)可用。

发布后已使用现有读取凭据回读 POM、Gradle Module Metadata、AAR 和源码包：新坐标正确；58 个唯一 Kotlin 源码文件与发布提交完全一致，文件集合无遗漏，包含金额字段修正；AAR 含 Banner/Native。远程 AAR SHA-256：`16e911aba1355bf73d986aa12c25083f94123a9ccab227687bf55cc795d046ad`。原 `1.0.10` AAR 的 SHA-256 保持不变，未删除或覆盖。

未新增设备实网广告、点击、收益后台入库或素材恢复验收。
