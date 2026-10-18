# Ads Mediation SDK 1.0.13 业务更新说明

Maven 坐标：`com.cashcraft.sdk:ads-mediation:1.0.13`。仓库仍为 `https://maven.pkg.github.com/vihuela/ads-mediation`，源码包名仍为 `com.cashcraft.ads.mediation`。

## 业务更新

```kotlin
implementation("com.cashcraft.sdk:ads-mediation:1.0.13")
```

从 `1.0.12` 升级只需修改版本号；新增配置项有默认值，不设置时行为不变。`AdsConfig` 的最后一个参数由 `onAdBlocked` 变为 `nativeValidatorDisabled`，如果宿主用尾随 lambda 传 `onAdBlocked`，需要改成 `onAdBlocked = { ... }`。

## 本版变化

`AdsConfig` 新增 `nativeValidatorDisabled`（默认 `false`）。设为 `true` 时，AdMob 初始化会调用 `InitializationConfig.Builder.setNativeValidatorDisabled()`，Google 测试广告不再弹出 “AdMob native ad validator” 浮层。直连 AdMob 和 Bidding 里的 AdMob 都生效，TopOn 单独模式不受影响。

背景：紧凑 Native 布局的媒体槽位小于 120 × 120 dp 时，验证器即使遇到图片素材也会提示 `MediaView is too small for video`。视频素材的 120 dp 下限仍由 SDK 在注册前检查保证（`native_video_media_too_small`），这个开关只影响开发期的浮层，不改变素材展示和合规检查；正式广告请求本来就不会显示该浮层。宿主 Manifest 里的 `com.google.android.gms.ads.flag.NATIVE_AD_DEBUGGER_ENABLED` 是旧版 SDK 的开关，对 GMA Next-Gen 无效，可以删除。

## 验证与发布记录

本地按 README 的完整验证命令（`clean testDebugUnitTest lintDebug :r8-smoke-app:assembleRelease publishToMavenLocal`）执行，全部通过：JVM 单测 646 个用例无失败，其中新增 3 个用例覆盖默认保持开启、直连关闭、Bidding 关闭；临时去掉 `AdMobProvider` 里的调用或 Bidding 的映射后，对应用例会失败。R8 Release 构建通过，本地产物坐标为 `com.cashcraft.sdk:ads-mediation:1.0.13`，其中 `AdsConfig` 含新字段。

已于 2026-10-18 19:05 启动、19:14 完成（台北时间）正常发布，[发布任务](https://github.com/vihuela/ads-mediation/actions/runs/39121823508)成功，发布提交为 `9472286`；远程 1.0.13 可用。

发布后已使用现有读取凭据回读元数据、POM、Gradle Module Metadata、AAR 和源码包：`maven-metadata.xml` 的 latest 为 1.0.13；新坐标正确；58 个 Kotlin 源码文件与发布提交完全一致（源码包另含构建生成的 `BuildConfig.java`）；AAR 的 `AdsConfig` 含 `getNativeValidatorDisabled()`。远程 AAR SHA-256：`b1868023935642857cf8efe617837aaae2816196acdaa4d3d3e0e02b39babadd`。

宿主 HealthTracker（Samsung SM-S721U1，Android 15）升级到远程 1.0.13 并设置 `nativeValidatorDisabled = true` 后覆盖安装验证：New Record 页（standard 布局）截图确认广告正常渲染且没有浮层；Alarm Management 页（card7 布局）的广告加载后也没有验证器请求。新包安装后 GMA 记录的 516 次网络请求中验证器请求为 0 次，旧版每次加载 card7、standard 布局后都会请求一次。

未新增设备实网广告、点击、收益后台入库或素材恢复验收。
