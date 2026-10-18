# Ads Mediation SDK 1.0.12 业务更新说明

Maven 坐标：`com.cashcraft.sdk:ads-mediation:1.0.12`。仓库仍为 `https://maven.pkg.github.com/vihuela/ads-mediation`，源码包名仍为 `com.cashcraft.ads.mediation`。

## 业务更新

```kotlin
implementation("com.cashcraft.sdk:ads-mediation:1.0.12")
```

从 `1.0.11` 升级只需修改版本号，无接口变化。

## 本版变化

`AdsBannerView` 在 `Failed`、`Blocked` 状态下不再保留自适应高度占位（高度折叠为 0），避免加载失败、无填充或被策略拦截时页面底部出现空白条；状态回到 `Waiting`、`Loading`、`Ready`（例如 `refresh()` 重试）时自动恢复占位。`Inactive` 状态保持原有的合规空间保留行为。

宿主容器若带有内边距或背景色，仍需在这两种状态下自行隐藏。

## 验证与发布记录

本地 JVM 单测全部通过，新增断言覆盖“加载失败后测量高度为 0”。发布结果、发布任务及远程回读记录见后续提交补充。

未新增设备实网广告、点击、收益后台入库或素材恢复验收。
