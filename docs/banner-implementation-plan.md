# Banner 实现方案

状态：AdMob 正式 View／Compose 已实现并本地发布；TopOn 经用户批准后续补齐。完整验收仍有未完成项，见第 14 节及 OpenSpec 任务清单。

整理日期：2026-09-29。  
适用项目：Ads Mediation Android SDK。  
目标：支持页面底部 Banner，正式覆盖 Compose 与单 Activity 页面导航。

本文保留设计依据与验收契约，并在第 14 节记录实施状态。实际可接入 API 以 [Banner 接入说明](banner-integration.md) 为准，历史依据不作为当前运行证据。既有全屏功能只引用原有文档，不在此重新定义。

## 1. 已确认需求与方案边界

用户已经明确的需求：

- Banner 通常位于页面底部，Tab 切换本身与 Banner 的加载、刷新和实例更换无关。
- 需要正式考虑 Compose 应用的接入，而非仅提供 View 示例。
- 重点覆盖单 Activity 内页面进入、离开、返回的场景，不能只依赖 Activity 生命周期。
- 容器 View 的高度必须自适应广告合法尺寸和实际测量结果，不能统一固定为 50 dp。
- 保留加载、刷新、曝光、点击、收益等埋点及其身份关联，不以简化实现为由删减。
- 简化首版实现，优先减少容器管理和重复状态逻辑，不扩展通用广告框架。
- 原方案阶段只写文档；后续用户已授权 OpenSpec apply，实现范围以当前变更为准。
- 本次不考虑运行期间的隐私状态变化；保留现有初始化及本层请求前的 UMP 门禁，不增加隐私变化监听或联动销毁流程。

采用 SDK 自有的 `AdsBannerView` 管理平台广告子 View，Compose 的 `AdsBanner()` 薄包装复用它；本轮正式支持 AdMob，TopOn 请求明确 unsupported。平台负责自动刷新，本 SDK 负责页面资格、尺寸测量、资源释放和统一事件出口。

首版不再设计任意外部容器的绑定登记或独立绑定句柄，不增加通用状态机、历史回调重排系统或多套可配置的暂停策略。每个平台只实现阶段 1 验证成立的暂停方式。将所有临时暂停统一改为释放重建会增加请求和恢复等待，仍是待确认取舍，不在本次文档更新中默认采用。

**“多个导航页面是否共用同一个 Banner 实例”尚未得到用户确认。** 本文以页面独立持有作为默认接入建议，同时描述公共底部区域的接入方式。二者使用同一套能力，不需要先建设跨页面广告缓存系统。不能把“位于页面底部”推导成“必须全局共享”，也不能因切换 Tab 就自动销毁广告。

## 2. 首版范围

| 内容 | 首版建议 |
| --- | --- |
| 展示位置 | 固定在页面底部的独立布局区域 |
| 广告平台 | 本轮正式 AdMob；TopOn 保留探针与未通过门槛，后续独立补齐 |
| Compose | 正式提供 `AdsBanner()` 组件，封装 View 与资源管理 |
| 传统 View | 提供 SDK 自有 `AdsBannerView`，宿主直接加入布局；不另设外部容器绑定入口和句柄 |
| 尺寸 | 容器宽度跟随宿主、高度自适应；AdMob 锚定自适应，标准 320 × 50 dp 可选；TopOn 广告自适应能力验证后开放，不限制容器只能为 50 dp 高 |
| 初始化与隐私 | 复用现有初始化和 UMP 门禁，并核对所选平台状态 |
| 事件与收益 | 保留完整事件契约，包括刷新事件及 slot/request/session 关联，沿用现有统一出口 |
| 全局 Bidding 模式 | Banner 请求显式选择已配置的 AdMob 或 TopOn，不默认进行跨 SDK 比价 |

首版不包含信息流 Inline、可折叠 Banner、原生广告混排、跨页面 View 搬运、全局 Banner 缓存池、自建刷新定时器、失败后的跨平台连跳。不额外添加用户手动关闭按钮；仅处理平台实际提供且语义明确的关闭回调，不承诺所有广告源支持用户关闭。

TopOn 内部仍可以聚合其广告源。这里不把现有全屏缓存竞价扩展到 Banner：两端预展示价格与实际展示对象的一致性尚未验证，展示后的收益回调不能当成展示前的报价。

## 3. 当前项目依据与复用边界

### 3.1 当前 SDK

以下是方案制定时的版本与结构依据。实施阶段已解析实际 Gradle 依赖，记录在 [verification.md](../openspec/changes/archive/2026-09-29-add-banner-support/verification.md)；此历史表本身不是运行证据。

| 现有内容 | 依据 | Banner 的处理 |
| --- | --- | --- |
| GMA Next-Gen 1.2.1、TopOn 6.6.22.3、GMA adapter 1.2.1.1.0、UMP 4.0.0 | [版本目录](../gradle/libs.versions.toml) | 首版保持现有广告依赖，不顺带升级 |
| 原有格式为 APP_OPEN、INTERSTITIAL、REWARDED | [AdEvent.kt](../src/main/java/com/cashcraft/ads/mediation/AdEvent.kt) | 已增加 BANNER，并让全屏专用入口明确拒绝该格式 |
| 统一配置及初始化 | [AdsConfig.kt](../src/main/java/com/cashcraft/ads/mediation/AdsConfig.kt)、[Ads.kt](../src/main/java/com/cashcraft/ads/mediation/Ads.kt) | 复用全局配置、UMP 和已配置平台，不另起初始化流程 |
| 单次全屏展示会话 | [AdEventDispatcher.kt](../src/main/java/com/cashcraft/ads/mediation/internal/AdEventDispatcher.kt) | 复用日志和分发出口，不把一个全屏会话用于 Banner 整个生命周期 |
| 统一收益对象 | [AdRevenue.kt](../src/main/java/com/cashcraft/ads/mediation/AdRevenue.kt) | 延续平台原生收益及公共字段，按实际展示关联 |
| 全屏等待机会、互斥、缓存补充 | [全屏接入说明](fullscreen-display-opportunities.md) | Banner 不加入全屏展示锁、等待机会或预加载队列 |
| 全屏变更的既有设计与任务 | [变更目录](../openspec/changes/add-fullscreen-display-opportunities/) | 保留原范围，不把 Banner 状态写进全屏任务 |

当前 `AdShowSession.impression()` 通过一次性终态限制曝光，整条 Banner 复用一个此类对象会丢掉刷新后的曝光。应使用 Banner 自己的展示身份管理，避免为了 Banner 改松全屏的终态保护。

现有 README 记录了 GMA 1.2.1 的全屏价格反射兼容边界；若后续升级，应单独验证反射、consumer rules 和 R8，而不是作为 Banner 的隐含依赖升级。[README](../README.md)

### 3.2 HealthTracker 历史实现的借鉴

参考仓库为 `$HOME/od-fz/HealthTracker`，删除前版本为 `a856c1fed581f1b219c0e029197aee315ffb9733`，广告模块删除提交为 `3ee0c0225a92ec3d37f5a26c9f2f9a8e96ee7c1e`。以下路径及行号均指删除前版本，可用 `git -C "$HOME/od-fz/HealthTracker" show <版本>:<路径>` 查看，不依赖临时源码快照。旧版声明使用 legacy GMA 24.7.0、TopOn 6.5.16；此次只核对历史源码，不作为当前依赖的编译或运行证据。

| 可借鉴之处 | 历史定位 | 本方案采用方式 |
| --- | --- | --- |
| Banner 独立于 Tab 内容，底部容器自然测量高度 | `app/src/main/res/layout/dh_activity_main.xml:101–136`；`app/src/main/java/com/daily/health/manager/face/act/MainAct.kt:341,464–475` | Banner 放在拥有公共底部区域的页面层，Tab 内容切换不重建它；使用 `MATCH_PARENT × WRAP_CONTENT`，不额外建设高度状态机 |
| 按宽度确定合法广告尺寸 | `monetize/src/main/java/net/corekit/monetize/ads/BannerAds.kt:132–138` | 沿用宽度 → 平台尺寸 → 正常测量的流程，将旧屏幕宽度改为当前容器实际内容宽度 |
| 异步操作前后复核展示条件 | `app/src/main/java/com/daily/health/manager/utils/AppCompatExt.kt:44–64` | 保留资格复核思路，并将结果处理时的检查放在挂载之前；旧版展示调用返回后才隐藏容器的顺序不沿用 |
| 平台加载、曝光和刷新回调分开处理 | `monetize/src/main/java/net/corekit/monetize/ads/topon/TopOnBannerAdController.kt:131–158,212–257` | 参考回调边界，仍按第 10 节完整上报；旧刷新回调仅有日志，不能作为完整埋点实现 |

不移植旧版全局缓存、预加载竞价、跨容器 View 搬运及 `removeAllViews()` 清理宿主容器的做法。旧 TopOn 请求固定 60 dp 高且未使用计算出的自适应尺寸；旧 AdMob 的 `pause()` 仅写日志、释放方法只清理缓存池，不能证明展示中广告被释放。旧 AdMob 在收益回调发送曝光事件、TopOn 混用 `publisherRevenue` 与 `ecpm` 的收入兜底也不沿用。尺寸、生命周期和收益均以当前平台公开能力与本方案契约为准。

### 3.3 StepCounter 历史缺口归纳出的改进约束

参考仓库为 `$HOME/sea/StepCounter`，历史版本为 `0b7df84ed44d054b312660b534147c0d92a13958`，声明使用 legacy GMA 22.4.0。以下两条已确认纳入本方案，是从旧实现缺口归纳出的改进要求，不是旧代码已经具备的能力，也不作为当前 Next-Gen SDK 的运行证据。

| 历史缺口及定位 | 本方案采用的约束 |
| --- | --- |
| `lib_ad/src/main/java/com/sea/mobile/ad/base/BaseBannerAd.kt:36–50,144–150`：只在加载成功后保存 AdView，而 destroy 只清理已保存的成员；页面在加载中销毁时无法通过该成员清理在途实例 | 平台实例创建后立即由当前 AdsBannerView 持有，配置异常、加载中离开和最终销毁统一清理；迟到加载结果仍检查代次，不能恢复已结束对象，见第 6.1 节 |
| 同文件 `:47–50`：先通知业务加载成功，再注册收益监听；业务可能立即展示或抛异常 | 在平台允许的最早时点注册收益监听，且先于可能触发展示的业务回调和首次可见，见第 10.3 节 |

## 4. 结构与公开接口

### 4.1 最小实现结构

```text
Compose 页面 / View 页面
          │
     AdsBanner() / View 页面直接添加
          │
       AdsBannerView
    页面资格 + 高度测量 + 请求代次
          │
    AdMob Banner / TopOn Banner
          │
    现有日志、事件监听、收益监听
```

共享逻辑只管理真实存在的两端差异和页面状态，不增加全广告类型通用注册机、缓存框架或新的任务调度器。UI 操作和本层状态串行在主线程处理；平台回调在读取必要身份后切回主线程，SDK 初始化仍遵循已有线程规则。

页面生命周期、加载资格、释放和布局测量集中在 `AdsBannerView`；Compose 只负责创建、同步参数和最终释放，不再复制一套生命周期控制器。SDK 只管理这个 View 内的广告子 View，宿主负责把 `AdsBannerView` 放入自己的布局；同一个 View 不跨页面或父容器搬运。

Compose 不能只停留在文档示例。建议以一个薄的可选 Compose 扩展模块交付 `AdsBanner()`，核心 AAR 保持 View 能力，避免普通 View 宿主被强制引入 Compose 和 Navigation。模块名、发布坐标、最低 Compose 版本在实施阶段按现有发布方式确定；本文不把尚不存在的坐标当成可用依赖。

核心层不依赖 `NavController`，也不解释业务路由。Navigation 示例放在 Compose 接入文档或宿主示例中。

### 4.2 请求及 SDK 自有 View

`BannerRequest` 的字段：

| 字段 | 契约 |
| --- | --- |
| `platform` | 明确选择 ADMOB 或 TOPON，且该平台已在全局配置中声明 |
| `adUnitId` | 当前 Banner 的 AdMob 单元 ID 或 TopOn placement ID，不能为空 |
| `position` | 稳定业务广告位名，沿用广告类型后缀约定，例如 `home_bottom_banner` |
| `size` | 自适应或固定尺寸；尺寸能力由当前平台校验 |

Banner ID 随请求传入，不新增到现有全屏 ID 配置的必填参数中。全局 Bidding 初始化成功并不保证本次选择的平台成功，必须检查该平台本身的状态。

以下为 View 接入方式；完整、参与编译的宿主见 [TraditionalBannerActivity.kt](../r8-smoke-app/src/main/java/com/cashcraft/ads/mediation/smoke/TraditionalBannerActivity.kt)：

```kotlin
val banner = AdsBannerView(
    activity = requireActivity(),
    lifecycleOwner = viewLifecycleOwner,
    request = homeBannerRequest,
    active = isBannerPageActive,
)
binding.bannerContainer.addView(
    banner,
    ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ),
)

// 业务确认这个广告位所在页面已经离开时调用；不关联页面内部 Tab。
banner.setActive(false)

// 生命周期最终结束会自动释放，也允许提前结束。
banner.destroy()
```

`AdsBannerView` 自身提供 `setActive(Boolean)` 和 `destroy()`，无需独立句柄。创建参数中的 `active` 默认 true，必须在首次资格评估前应用，避免先请求再停用。`active=false` 表示广告位业务资格结束，释放本轮平台实例；恢复为 true 且满足条件后可以创建新实例。`destroy()` 是这个 View 的永久结束，调用后不能复活，且重复调用无副作用。

重复设置相同的 active 不产生新的广告位周期或加载。临时遮罩使用 View 可见性控制，不通过反复切换 active 模拟暂停或重试。View 宿主可隐藏 `AdsBannerView` 或其外层容器；SDK 暂停时只控制内部广告子 View，不擅自改回宿主设置的可见性。

一个 `AdsBannerView` 同时最多持有一个平台实例。request、Activity 和页面 owner 在该 View 创建时确定，不提供重新绑定到其他容器的 API；这些值改变时释放旧 View，再创建新 View。外层布局的添加／移除由宿主负责，`destroy()` 只清理自身资源，不访问宿主其他子 View。旧回调不得修改后来创建的 View 或实例。

不同 `AdsBannerView` 独立持有实例、页面状态和事件身份；即使 platform 与 adUnitId 相同，也不能按广告位 ID 合并生命周期或回调。释放一个 View 不得影响另一个 View；此约束用于实例隔离，不引入多广告位缓存或调度功能。

### 4.3 Compose 参数

`AdsBanner()` 接收 `request`、`modifier`、`lifecycleOwner`、`active`、`visible`，以及可选的状态回调。`lifecycleOwner` 默认读取当前 Composition 提供的 owner；导航示例显式传 entry，避免错误绑定到外层 Activity。正式状态为 Inactive、Waiting、Loading、Ready、Failed 和 Destroyed；临时隐藏不清除已加载状态。UI 状态不代替真实曝光和收益事件。

`active` 默认 true 适用于“组件存在即代表广告位业务有效”的布局。保留页面或自定义导航必须提供实际页面资格；它不是 Tab 选中开关，也不是 Activity 是否 Resumed 的复制。

`visible` 默认 true，仅表达宿主是否允许槽位此刻显示；它不改变广告位业务归属。false 映射为 `AdsBannerView` 的 `View.INVISIBLE`，保留当前合法占位并触发临时暂停；true 恢复为 `View.VISIBLE`，仍须通过生命周期等资格检查。此参数与 View 宿主控制可见性使用同一路径，不增加独立的 pause/resume 方法。是否能进一步合并 active／visible，取决于第 6.2 节的体验取舍，当前保留两者。

临时隐藏时保持 `AdsBanner()` 留在 Composition 中，不能用 `if (visible)` 移除组件；后者触发最终释放。`Modifier.alpha(0f)` 或覆盖透明拦截层不属于暂停协议，不能据此保证平台停止刷新或广告不再接受点击。

## 5. 单 Activity 导航与广告位归属

### 5.1 页面独立持有：默认推荐

把 Banner 放在页面自己的底部布局中。首页内部的 Tab 内容可以变化，但不改变底部容器的身份。进入另一个完整页面后，原页面结束广告资格；详情页是否展示 Banner，由详情页自身决定。

Navigation 的返回栈条目有页面生命周期，Activity 前台状态不足以区分首页与详情页。Dialog 目的地还可能让底层条目保持 STARTED，不能把所有降级都视为页面彻底离开。[S4]

下面只演示普通全屏 Navigation Compose 目的地；`HomeContent` 和请求对象由宿主提供，`AdsBanner` 为拟议 API：

```kotlin
val currentEntry by navController.currentBackStackEntryAsState()

NavHost(navController = navController, startDestination = "home") {
    composable("home") { entry ->
        Scaffold(
            bottomBar = {
                AdsBanner(
                    request = homeBannerRequest,
                    lifecycleOwner = entry,
                    active = currentEntry?.id == entry.id,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) { padding ->
            HomeContent(Modifier.padding(padding))
        }
    }

    composable("details") {
        DetailsScreen()
    }
}
```

这里比较的是页面实例，不是路由字符串。同一路由的多个 entry、快速 A → B → A 和返回栈恢复不能把旧加载结果交给新绑定。首页若包含多个 Tab，`home` 应代表拥有公共底部区域的页面范围，而非每个 Tab 内容。

**上面的 current-entry 判断不能原样用于 Dialog 目的地。** 对 Dialog／临时遮罩，宿主应把底层广告位归属保持在原页面。Navigation Dialog 可由 entry 生命周期触发临时暂停；不改变生命周期或窗口焦点的同窗口遮罩、自定义 Sheet，则由宿主隐藏槽位或设置 `visible=false`，核心不推断业务遮挡关系。预测返回未提交时也不能提前当成永久离开；需要对实际导航库的转场和返回时序做运行验证。

### 5.2 多个页面共享公共底部区域：可选接入

仅当产品选择跨页面共用时，将一个 `AdsBanner()` 放到这些页面共同的 `Scaffold` 中，位于其 `NavHost` 外。允许展示的目的地间导航不重建；进入禁止展示广告的页面时，公共广告位变为 inactive，释放后再按条件重建。

公共 Banner 使用稳定的 request、容器身份和 position；不能在每次导航时把 position 改成当前路由来强迫换广告。需要记录当前业务页面时，应另记页面访问事件。该方案不需要把同一个广告 View 在多个父容器之间移动。

### 5.3 场景契约

| 场景 | 页面独立持有的建议行为 |
| --- | --- |
| 页面内切换 Tab，底部槽位持续存在 | 保留实例，不触发本层 reload，不主动刷新 |
| 首页进入无 Banner 的详情页 | 首页广告位 inactive；清理平台实例，详情页没有广告 |
| 首页进入有 Banner 的详情页 | 首页结束其绑定周期，详情页建立自己的周期；转场中避免两条广告叠在同一区域 |
| 从详情页返回首页 | 按当前首页 entry 建立新周期，允许重新加载，不承诺跨页面缓存或无等待恢复 |
| 页面保留在返回栈或 Composition 中但不再可见 | 导航资格信号立即生效，不等 Activity 或页面最终销毁 |
| 应用暂时退后台 | 暂停新增请求及本层展示资格；与业务页面离开分开，不因单次 ON_PAUSE 自动销毁 |
| Dialog、广告落地页或其他临时覆盖 | 暂停或隐藏真实广告 View，保留可恢复的归属；不按 Tab 或完整页面导航处理 |
| 同窗口遮罩未改变生命周期或焦点 | 宿主隐藏容器／设置 visible=false；恢复后不重复发送广告位进入事件 |
| 配置变化导致 Activity 重建 | 旧 AdsBannerView 释放，在新 Activity 创建；不在 ViewModel／保存状态里保存广告 View |
| Fragment 保留但其 View 销毁后重建 | 跟随旧 viewLifecycleOwner 释放，用新 owner 创建新 AdsBannerView；不能因 Fragment／Activity 仍存活而复用旧广告 View |
| 组件退出组合、owner 销毁或主动 destroy | 完成最终释放；忽略旧加载/UI 回调 |

临时暂停能否保留实例并可靠停止刷新，以当前两端 SDK 的实际能力为准；若某端无法做到，则该端采用释放重建。不得把未验证的 pause/resume 方法写成两端都有的统一 API。

## 6. 请求资格、暂停和释放

### 6.1 请求资格与回调边界

首次加载须同时满足：业务 `active`、本层请求前检查 UMP 允许请求、所选平台初始化成功、原 Activity 未结束且处于 RESUMED、页面 owner 处于 RESUMED、窗口可见且有焦点、宿主槽位及其父层级可见、AdsBannerView 已附着且可用宽度大于零。创建外层 View 本身不发请求。新页面转场期间先等待，不通过反复请求来等页面恢复。

所选平台未配置或已明确初始化失败时报告失败，不无限等待，也不改用另一平台；平台尚在初始化时才等待其结果。UMP 仅复用现有初始化流程和请求前门禁，运行期间隐私变化不属于本次契约。

Bidding 下必须分别读取所选平台的初始化结果，不能以整体成功代替该平台成功。一个平台失败、另一个成功时，失败平台的 Banner 明确失败且不请求、不自动切换；成功平台按正常资格加载。两种成功／失败排列都需要验收。

加载前检查的是宿主槽位能够参与布局，不能要求“尚未加载的广告子 View 已经有高度或曝光”，否则会形成循环等待。实际展示和平台刷新则必须落实到真实 View 的可见性，不能只修改本层布尔值。

| 状态变化 | 必须处理的动作 |
| --- | --- |
| 正在等待初始化、宽度或页面就绪 | 不发请求；只观察与当前绑定相关的状态 |
| 条件就绪 | 每个有效请求代次只调用一次加载 |
| 相同条件再次通知 | 保持当前实例，去掉重复请求 |
| 应用后台／临时暂停 | 阻止本层新请求；隐藏或按已验证方式暂停广告，仍可能有先前在途请求的返回 |
| 导航确认离开或 active=false | 使请求代次失效，移除并销毁平台实例；是否以后重建由新的页面资格决定 |
| 本层准备发起请求时 UMP 不允许请求 | 不发起该请求；沿用已有初始化／许可流程，不另建隐私变化处理链路 |
| destroy／owner 最终销毁 | 解除监听、取消本层等待，清空 Activity、View 和业务回调引用 |
| 旧加载结果迟到 | 不挂载，不通知新页面；如返回可释放资源，按平台接口清理 |

平台实例创建后立即由当前 `AdsBannerView` 持有并承担释放责任，不能等加载成功才保存引用。创建后的配置异常、加载中离开和最终销毁均使用同一幂等清理入口；不能只清理已加载成功的实例。若平台仅在异步回调中交付可释放对象，先检查绑定身份和代次：有效时立即接管，过期时按平台接口清理，不恢复已结束对象的成员状态。此约束复用现有持有关系和代次，不新增资源登记框架。

每个异步请求捕获绑定身份和递增代次；加载和 UI 回调处理前与当前有效值比较。SDK 没有取消 API 时，只能保证旧结果不会影响页面，不能宣称网络请求已经取消。已发生展示的收益按第 10.3 节独立处理，不能共用“当前代次失效就丢弃所有回调”的判断。

发起请求前和异步结果准备挂载／恢复显示前，分别复核当前代次、页面资格与容器状态，不能先挂载再依赖宿主隐藏。临时暂停期间仍按第 6.2 节记录合法结果或保留资源；这次检查只阻止过期 UI 操作，不否定真实加载结果和已确认归属的收益。

宿主事件、收益和可选 UI 状态回调分别隔离异常并记录诊断，不能中断加载终态、去重标记或资源清理；一个出口抛异常不阻止同次事实的其他合法出口。复用现有分发方式，不为此新增任务队列或回调重试框架。

`ON_PAUSE`、页面离开和 `onRelease` 承担不同职责：暂停控制即时资格，导航控制业务归属，最终释放做资源兜底。只使用其中任意一个都不能覆盖全部单 Activity 场景。

### 6.2 临时暂停与恢复

以下描述的是平台能力不同情况下的处理契约，不要求为每个平台都实现两套可切换模式。阶段 1 确定各端实际可用的方式后，每个平台只保留对应的一条路径；共享层复用同一个资格判断和幂等释放逻辑。

- 暂停由页面／Activity 不再 RESUMED、窗口失焦或不可见、容器临时脱离窗口、宿主隐藏槽位触发。任一条件仍不满足就继续暂停；恢复不把宿主的 active 或 visible 擅自设为 true。
- 支持保留实例的平台隐藏或使用已验证的暂停方式处理真实广告 View；保持 `slot_id`、实例和当前请求代次。暂停期间当前代次的在途结果可以记录并保留资源，但不能自行显示；恢复时复用有效广告，不额外调用加载。
- 不支持可靠暂停的平台，在进入暂停状态时销毁一次并使旧代次失效；资格恢复后创建新代次并加载一次。连续的暂停通知不重复销毁，恢复通知不重复加载。此时保留原 `slot_id`，不重复发送 `ad_position`，新广告使用新的展示身份。
- SDK 判断恢复条件时读取宿主槽位的可见性和生命周期，不把自己隐藏的广告子 View 当作恢复前置条件，避免“先显示才能恢复”的循环等待。
- 临时暂停不是重试入口；失败实例在可以保留的情况下，恢复可见性不重置其加载失败状态。平台必须释放重建的路径按本节的释放重建规则执行，不承诺跨暂停保留实例。

可选的进一步简化是所有临时暂停都释放、恢复后重建，并合并 active／visible 的使用方式。这会使前后台或 Dialog 恢复时重新请求、可能重新等待广告；该体验取舍尚未确认，不能作为已接受的首版契约，也不能据此删掉现有保留／恢复语义。平台确实无法暂停时的必要释放，仍按上面的能力降级规则处理。

## 7. Compose 实现规则

Android 官方将 `AdView` 列为 `AndroidView` 的使用场景；`factory` 创建 View，`update` 同步状态，`onRelease` 处理不再复用的 View。[S3] 组件建议遵循以下约束：

- 在 `AndroidView.factory` 中创建 `AdsBannerView`；不在 Composable 函数体直接创建广告或发请求。
- 以 Activity、页面 owner／实例和 request 的实际值维持 View 身份；这些值改变时释放并创建新 View，不设计 View 内热切换请求。新的等值 request 对象不应因引用变化重载。
- 首次绑定先应用当前 active、visible，再评估请求资格，不能先按默认可见状态发请求后补隐藏。`update` 同步两者，由底层幂等判断决定动作；普通重组、文本变化、回调变化不增加请求。
- 用 `rememberUpdatedState` 读取最新业务回调，避免陈旧闭包；页面监听统一由 `AdsBannerView` 注册和解除，Compose 不再重复订阅同一生命周期。[S5]
- 新 View 使用独立回调身份；同一 View 内有效请求尺寸变化、停用、销毁及必须释放的暂停使旧代次失效。可保留实例的临时隐藏不切换代次，普通重组或仅高度测量变化不切换代次。
- `onRelease` 和 owner 销毁都可调用幂等释放；默认不启用广告 View 在不同列表项／页面间的 `onReset` 复用。
- Preview 只绘制占位，不初始化广告 SDK、不请求网络；按 `LocalInspectionMode` 分支处理。

宽度变化以布局结果为准；只在规范化后的宽度、密度、方向或尺寸参数使有效广告请求尺寸发生变化时重建平台实例，不让每次测量回调触发加载。广告返回尺寸或刷新导致的容器高度变化只更新布局，不能反向触发加载。组件不内置某个导航框架，也不依据 Tab 下标决定是否请求。

## 8. 尺寸、占位及底部布局

**容器高度自适应是已确认要求。** `AdsBannerView` 及宿主外层容器使用 `WRAP_CONTENT` 高度，Compose 包装不设置固定 `height(50.dp)` 或限制合法广告高度。高度按平台确认的广告尺寸和实际测量结果更新，不拉伸、缩放或裁切广告。

优先依赖平台广告 View 和 Android 正常测量／布局流程：宿主提供可用宽度，平台确定合法尺寸，容器自然得到高度。不为此新增独立高度状态流、轮询测量或通用尺寸控制器；仅在锁定版本实测出现测量问题时，针对对应平台补最小布局处理。

容器高度自适应与平台的“自适应 Banner 广告尺寸”是两件事：标准 320 × 50 dp 广告可以自然测得 50 dp，高度不同的合法广告也必须能完整显示。TopOn 某来源尚不支持自适应请求，不意味着容器可以写死 50 dp。首版保留这个布局能力，不建立通用尺寸策略框架。

Google 当前指南使用 Next-Gen 的 `AdView`、`BannerAdRequest` 和自适应 `AdSize`；该文档也提供了 Compose 接入示例。[S1] 具体使用哪个尺寸方法，应先按项目锁定的 1.2.1 编译确认。

推荐用容器实际内容宽度计算尺寸，先扣除宿主实际提供的 padding；系统安全区由宿主布局统一处理一次，不重复扣除。公共接口使用明确的 dp 语义，传给各平台前按其实际参数单位转换。TopOn 的参数名、单位和自适应能力必须以当前 TU SDK 及 GMA Next-Gen adapter 验证结果为准。

固定 320 × 50 dp 模式中，空间不足时返回明确失败，不缩放、裁切广告来塞进容器。自适应模式按请求返回的合法尺寸布局，不再用固定 50 dp 高度截断。

底部布局使用独立槽位，正文消费 `Scaffold` 的内边距。Banner 不覆盖正文、导航按钮或其他可点击控件；高频操作区域与广告保持可区分的空间。[S7] 处理边到边、系统导航栏与键盘时，应改变宿主布局，不能给广告表面盖一个可点击遮罩。

首版建议在首次加载前按平台可确定的合法请求尺寸占位，不能统一预留固定 50 dp。广告尺寸确认后及平台刷新改变合法高度时，按结果更新布局；高度自适应不承诺完全没有布局变化。若加载前无法确定高度，阶段 1 必须验证该平台的占位与首次失败恢复方式，不能用零高度永久等待一个要求可见才能触发的刷新。首次无填充不显示业务错误弹窗；是否折叠空白区域属于产品布局选项，尚未确认。若将空槽彻底隐藏，必须验证后续如何恢复请求。

## 9. 加载失败与自动刷新

**每条 Banner 只有一个刷新调度方。** 下表是本项目的推荐配置；首版不实现自己的周期刷新器。

| 承载路径 | 刷新安排 |
| --- | --- |
| AdMob 直连 | 由 AdMob 后台及 SDK 管理，优先使用 Google optimized |
| TopOn | 由 TopOn 管理，核对并关闭下层广告平台的重复刷新 |
| Ads Mediation | 首次按资格加载、处理回调及释放，不再叠加固定时间 loadAd |

AdMob 官方支持优化刷新以及 30–150 秒的自定义间隔；这不是对本项目硬编码最佳间隔的建议。[S6] 直连 AdMob 与 TopOn 下层 Google 来源建议使用不同 Banner 单元，便于独立配置刷新和核对报表。

加载失败不在回调内立即递归重试，不持续快速创建新 View。自动刷新开启时交由平台；关闭时不补造无限重试，首版依靠新的有效绑定重新请求。需要同页显式重试时，另行确定 API 和节流规则。

**首版持续展示的接入基线是开启平台自动刷新。** TopOn 官方说明其自动刷新默认关闭，接入方必须在后台显式开启，并记录各实际使用广告位的配置；AdMob 直连和 TopOn 下层 Google 来源分别核对，TopOn 下层关闭重复刷新。[S10] 这是后台接入要求，不假设 SDK 能读取或修改后台开关。

| 失败或恢复场景 | 首版契约 |
| --- | --- |
| 开启自动刷新后首次请求失败 | 本层保留可见的合法空槽，交由平台恢复；阶段 1 必须验证同页断网后恢复网络、首次无填充后恢复填充的行为，不能只验证已展示广告的周期刷新 |
| 接入方选择关闭自动刷新 | 本层在当前代次不再次加载；没有平台自行重试时，同页可能持续为空，直至真实业务重新绑定或其他必要重建；这是明确的降级行为 |
| 重组、Tab 变化、相同布局／资格通知 | 不清除失败状态，不产生重试；不得用反复切换 active、改变 request 或重建 View 绕过此规则 |
| 刷新失败但仍有有效旧广告 | 保留旧广告和其展示身份，不先清空再等新广告 |

首版不增加 `retry()` 或网络恢复监听。若当前平台在开启刷新后仍无法恢复首次失败，则不能以“已开启自动刷新”通过恢复验收；先记录限制并明确接受降级，或另行调整重试契约，再交付该路径。后台关闭刷新只约束本层不叠加请求，不保证旧版 SDK 自身不重试。

刷新失败不主动清掉平台仍保留的有效旧广告；刷新成功也不等于曝光。Google 指南说明自动刷新与实际可见性有关，资源使用完毕应从父容器移除并销毁。[S1] TopOn 的刷新、移除及销毁行为要按当前 TU 版本验证，不能照搬 AT 版或旧 Google adapter 的方法名。

版本风险：Google 在 1.3.0 调整了关闭自动刷新时 `AdView.loadAd()` 对失败请求的自动重试行为。项目基线为 1.2.1，不能直接把较新版本的说明当成旧版本的实测结果。[S2] 测试时区分本层新发起请求、平台自行重试和进入后台前已经发出的请求。

## 10. 曝光、刷新和收益归因

**本节埋点完整保留。** 简化只针对实现组织，不暂缓 `slotId`、`ad_banner_refresh`，也不以诊断日志替代已定义的公共事件。复用现有出口，用必要的实例代次和展示快照隔离回调，不额外建设跨页面历史管理或通用回调重排系统。

### 10.1 身份模型

| 身份 | 用途 |
| --- | --- |
| `slot_id` | 一次有效 Banner 广告位绑定周期；导航离开后重新建立时生成新值 |
| `request_id` | 本层确实发起的一次加载请求，延续现有加载会话口径 |
| `session_id` | 一次广告展示周期；刷新产生新广告后使用新展示身份 |
| 平台 response ID／show ID | 平台实际提供时，用于识别广告、关联回调和去重 |

建议在公共广告事件中增加可选 `slotId`，只让 Banner 填充；字段命名及序列化需遵循现有事件惯例。绑定事件可用 slot_id 作为其 session_id；展示、点击、收益使用对应展示周期的 session_id，通过 slot_id 关联场景。收益 payload 可继续用 session_id 关联展示，不必仅为归属容器而复制一套收益类型。

同一 `AdsBannerView` 的一次 active 有效周期对应一个 `slot_id`；active=false 结束该周期，再次启用时创建新值。更换宿主／页面 owner 或 request 会创建新 View，并建立新周期。临时暂停、同一 View 的有效请求尺寸变化和平台刷新保留 `slot_id`；只有实际发起本层加载才创建 `request_id`，每次可识别的新广告展示使用新的 `session_id`。普通高度重测不切换上述身份；暂停保留的旧广告恢复显示不凭恢复动作自行生成曝光或新会话。

同一个平台对象可能被自动刷新复用，因此对象引用本身不能证明是同一次展示。每次回调要捕获当时可用的响应身份，不能等异步处理时再从已刷新的对象读取。

### 10.2 事件语义

| 事件 | Banner 拟议含义 |
| --- | --- |
| `ad_position` | 一次有效广告位绑定进入；普通重组和 Tab 内容变化不重复发送 |
| `ad_load_request`／`ad_load_result` | 本层显式加载及结果；内部自动请求没有可观察开始回调时不伪造 request |
| `ad_banner_refresh` | 新增的刷新结果事件，区分成功／失败；有何回调才报告何种事实 |
| `ad_impression` | 平台确认的一次真实曝光，按展示身份去重 |
| `ad_click` | 平台点击事件，归属实际广告 |
| `ad_paid` | 平台展示级收益，使用回调实际对应展示的会话和金额；不从当前页面或最新广告补归属 |
| `ad_show_fail` | 已进入展示尝试后的确定失败；不能把所有加载失败或正常导航清理都改成展示失败 |
| `ad_close` | 仅映射有明确语义的平台关闭回调；页面销毁、隐藏不是用户关闭广告 |

Banner 的一次 position 可以对应多次刷新曝光，因此现有 `ad_position = ad_impression + ad_show_fail` 只继续约束原全屏展示会话。报表按 format 区分，不为了维持旧等式补造 Banner 事件。

平台回调名称中的“全屏内容关闭”可能指广告落地内容关闭，不能直接解释为 Banner 槽位消失。两端事件映射需要写入适配说明，无法等价的事件不要强行统一。

TopOn 提供 `onBannerClose`，官方示例在该回调移除广告 View；这不代表所有来源都展示关闭按钮。[S10] 仅在当前平台确认是 Banner 本体关闭时，记录 `ad_close` 并清理平台实例，本次 slot 不因重组、尺寸或临时恢复自动重新展示；新的业务周期才允许再次加载。关闭若对应落地内容，则按临时暂停恢复处理。不增加 SDK 关闭按钮或通用用户关闭功能。

本层一次 `ad_load_request` 最多对应一次终态 `ad_load_result`。平台内部重试在失败终态之后又返回成功时，可以按实际结果恢复广告状态并记录后续曝光，但不能为原请求补发第二个终态或伪造新 request。只有确认属于刷新结果的回调才映射 `ad_banner_refresh`；无法分类的内部回调保留诊断，不猜测其请求次数。

### 10.3 收益处理

收益交付不以曝光事件已经先到达为前提。在平台提供可关联身份时，paid → impression 和 impression → paid 都使用同一展示身份，各事件分别去重；不能为了补事件顺序伪造曝光。合法的 `valueMicros=0` 按真实零收益交付，不视为缺失；金额缺失或非法时不能补零或用 eCPM 冒充展示收入，按平台映射记录诊断。身份无法关联时仍遵循本节的未知归因规则。

收益监听必须在平台允许的最早时点注册，并先于任何可能触发展示的业务事件／UI 状态回调及首次可见。创建平台 View 后即可注册时，在加载前完成；必须等待加载成功取得广告对象时，在成功回调内先接管对象、完成收益监听和必要身份准备，再通知业务或恢复可见。允许在资格复核后将当前实例的空 AdView 以 INVISIBLE 挂载再加载；挂载后仍复核代次与资格，加载期间不得提前可见。该顺序经 2026-09-29 用户批准。业务回调立即展示或抛异常，都不能使监听注册被推迟或跳过。注册时尚不可得的响应身份，仍在实际平台回调入口按本节规则捕获，不猜测或补造。

AdMob 从 `onAdPaid` 获取微单位金额、币种和精度；在可设置监听的最早时点完成注册，并及时捕获关联响应数据。Google 的展示级收益指南强调在展示前设置监听并及时处理回调。[S8]

TopOn 使用其当前 TU SDK 对应的展示级收益入口，保留原始平台信息；具体监听签名和刷新后的 show ID 行为列入实施验证。由 TopOn 承载的 Google 广告只走 TopOn 的收益出口，避免又从直连层重复上报。

已确认身份的迟到收益仍归属原展示，不能因页面离开而改归新页面；清理 View 不等于否定已发生的收入。只保留必要的有界元数据，不为等待收益长期持有 Activity、View 或页面业务回调。平台销毁后是否还会回调不作保证。

UI 资格校验与收益归属校验分开：旧代次不得挂载 View、更新新页面状态或调用已释放的页面回调；已确认原展示身份的收益仍交付全局 `revenueListener` 和对应 `ad_paid` 出口，并按同一展示的稳定事件身份去重。这两个收益出口不要求页面仍 active，也不能读取新绑定的数据补全旧收益。平台信息在回调入口捕获，异步处理只使用该快照。

若自动刷新不提供足够的展示身份或明确排序，就不能仅凭“当前广告对象”保证乱序回调正确归属。该能力必须用当前版本确认；无法确认的归因要标记未知并记录诊断，不静默归到最新展示，也不根据估计 eCPM 伪造收入。

未知归因诊断保留平台实际给出的金额、币种和身份，不生成一个共享的 `unknown` sessionId 冒充正常展示会话。现有收益对象要求 sessionId 非空；若某路径不能建立可靠关联，阶段 1 必须确定其降级方式及未知结果的表达后再冻结接口，不能把无法关联的收益当成已验收通过。

## 11. 平台实现与兼容检查

| 项目 | AdMob | TopOn |
| --- | --- | --- |
| API 家族 | GMA Next-Gen，不引入 legacy play-services-ads | 与现有 `com.thinkup`／TU 家族一致 |
| 容器持有 | 页面 Activity 对应的广告 View | 页面 Activity 对应的 TUBannerView 路径，具体签名按当前版本确认 |
| 尺寸 | 1.2.1 的合法自适应或固定尺寸请求；容器随合法高度测量 | 验证标准尺寸、dp/px 和 Next-Gen adapter 自适应参数；容器不得固定 50 dp |
| 自动刷新 | 核对 View 路径回调、失败重试和可见性行为 | 核对平台刷新开关、回调顺序、下层重复刷新配置 |
| 释放 | 移除 SDK 子 View、destroy、清除引用 | 按 TU 官方 API 实现对应清理并实际验证 |
| 收益 | onAdPaid 与响应身份 | 当前 TU 展示级收益回调与 show ID |

TopOn 普通 Banner 文档同时列出 AT 与 TU 示例，说明了可见性、刷新开关、释放及尺寸参数。[S10] 不能混用两套包名和类型，也不能把文档中 AdMob 自适应示例直接视为当前 GMA Next-Gen adapter 已验证的能力；具体签名和行为仍以阶段 1 结果为准。自定义原生混排资料 [S9] 仅作 API 家族补充，不作为本方案普通 Banner 的实现依据。

新增 `AdFormat.BANNER` 后需检查配置映射、`isReady`、全屏预加载、竞价选择和测试中的穷尽分支。Banner 的 adUnitId 来自请求，不能落入任意全屏 ID 的 fallback；全屏专用入口收到 BANNER 应明确返回不支持或按该入口契约拒绝。

枚举和事件字段扩展可能要求宿主调整穷尽 `when` 或事件解析逻辑。保持旧行为不等于自动保证所有源码／二进制兼容，发布说明必须列出实际影响。

## 12. 实施步骤与交付物

### 阶段 1：固定契约与当前版本验证

按本文固定请求、active／visible、绑定周期和回调处理契约，以页面独立示例先验证；公共底部布局使用相同核心，不以该产品选择阻塞基础能力验证。用现有广告依赖完成最小两端接入，至少记录以下结果后再冻结平台事件映射：

| 验证项 | 必须形成的结论 |
| --- | --- |
| 首次展示及连续两次刷新 | 各次加载／刷新／曝光／收益的实际回调、响应身份及关联关系；重复和乱序回调的处理方式 |
| 前后台、窗口失焦、槽位隐藏／恢复、最终销毁 | 为每个平台确定一条可用的暂停路径，不预建两套可配置模式；分清平台新请求与此前在途请求 |
| 首次断网或无填充后同页恢复 | 开启刷新能否恢复首次失败；关闭刷新时本层无额外加载，平台自身重试行为单独记录 |
| 尺寸与真实 API | 锁定版本可编译；两端容器高度自适应、加载前占位及刷新高度变化不额外请求；TopOn 参数单位和广告自适应能力分别验证 |

产出：可编译的最小验证结果、版本能力表和逐平台事件映射。每个平台明确首次及刷新曝光由哪个回调确认、收益如何关联与去重，不能只凭回调名称推导。无法在当前依赖成立的能力明确降级或提出单独变更，不擅自升级广告 SDK，不用反编译或猜测私有字段代替公开 API 证据。

### 阶段 2：View 核心与平台接入

实现 `AdsBannerView`、资格检查、自身可见性观察、自适应高度测量、请求代次、释放和两端回调；不实现任意外部容器绑定或独立句柄。复用初始化、日志、完整事件及收益出口，加入 BANNER 格式的明确处理。分别实现过期加载／UI 回调隔离与原展示收益处理，把最关键的状态分支纳入现有测试框架；不新增隐私变化监听。

产出：View 接入、平台适配、针对性测试及事件映射说明。

### 阶段 3：Compose 正式接入

实现可选 Compose 薄模块与 `AdsBanner()`，直接包装 `AdsBannerView`，不复制生命周期和广告状态逻辑。提供单 Activity 普通导航示例，以及产品采用时的公共底部示例。补齐 active／visible 区分、同窗口遮罩示例、Preview、重组、自适应高度和 owner 清理。

产出：可接入的 Compose 组件、宿主示例、生命周期验证结果；依赖与发布坐标此时才作为可用信息写入 README。

### 阶段 4：回归与接入文档

让 Banner 两端在现有 R8 smoke 验证中保持可达，补充必要的 Compose 宿主验证，运行与实际改动相关的构建和检查。将最终接口和事件映射写入接入说明，并更新本方案状态。

产出：验证记录、接入说明、兼容性说明。以上阶段是实施及验收要求；当前交付状态见第 14 节和 OpenSpec 验证记录。

## 13. 验收矩阵

本节是待执行的验证要求，不代表已有运行结果。对停用、隐藏、owner 销毁和替换，至少分别覆盖“等待初始化／有效宽度”“加载在途”“已展示”三个时点；用可控回调验证固定时序，再用实际平台验证其能力，不展开无意义的全排列。

| 验证层次 | 场景 | 通过条件 |
| --- | --- | --- |
| 单元 | adUnitId 为空、非法／不支持的尺寸、所选平台未配置 | 给出明确失败，不调用平台加载，不回退到全屏 ID 或另一平台；合法请求仍按正常资格处理 |
| 单元 | View 更换、重复销毁和旧实例回调 | 幂等；旧实例不能清理或更新新 View，只操作自身广告子 View |
| 单元 | 平台实例创建后配置异常、加载中销毁后成功回调迟到 | 无需等待加载成功即可清理已创建实例；重复清理幂等，迟到加载结果不恢复成员或挂载；仅在回调交付的过期对象按平台接口清理，不宣称网络已取消 |
| 单元 | 等值 request、实际 request 变化及 owner 替换 | 等值对象和普通回调更新不重载；平台／ID／position／显式 size 或 owner 改变按新 View 建立新周期，旧结果不挂载；同一 View 的宽度引起有效尺寸变化时保留 slot，仅更新请求代次 |
| 单元 | 初始化／宽度／页面状态反复通知 | 同一个有效代次最多发起一次本层加载 |
| 单元 | 等待中失活后条件就绪、加载中暂停／替换再恢复 | 失活期间初始化完成或宽度就绪仍不请求；挂载前复核资格；按实际暂停策略保留或释放结果，恢复只启动当前有效代次，不把旧结果计到新 slot |
| 单元 | active、visible 及暂停原因交错变化 | 重复通知幂等；全部恢复条件满足才恢复；隐藏不结束 slot，停用后重新启用才建立新 slot |
| 单元 | 每个平台实际采用的暂停路径 | 保留路径恢复不新增请求，释放路径恢复只请求一次；不重复 ad_position，不为未采用的可配置模式写测试 |
| 单元 | 同一 platform／adUnitId 的两个 View 实例 | 各自 slot/request/session 和加载、刷新、曝光、收益归属独立；销毁其中一个后另一个仍可用，不按 adUnitId 合并去重或生命周期 |
| 单元 | 同槽位首次展示、连续两次刷新及重复回调 | 每次实际曝光独立统计；刷新结果不冒充曝光，同次重复回调不重复记账 |
| 单元 | 本层加载已失败后平台内部重试成功 | 不伪造 request，不重复发送原 load_result；仍按真实回调记录新曝光及收益 |
| 单元 | 迟到收益及跨代次乱序 | 已知身份的旧收益仍交付全局出口并去重，旧 UI 回调隔离；未知归因不冒充当前展示 |
| 单元 | paid 与 impression 先后互换、各自重复及零收益 | 可关联时共用稳定展示身份，各事件分别去重；合法零收益正常交付，收益不依赖曝光先到；缺失／非法金额不补零或 eCPM，不伪造曝光 |
| 单元／接入 | 加载成功业务回调立即展示或抛异常 | 验证收益监听注册先于可能触发展示的业务回调和首次可见；注入即时 paid 回调时按真实身份交付，业务异常不会跳过监听注册；两端实际 API 的最早注册时点分别记录 |
| 单元 | 完整公共埋点及身份 | 加载、刷新、曝光、点击、收益等出口保留；slot/request/session 关联正确，不能退化为仅日志 |
| 单元 | eventListener／revenueListener／状态回调抛异常 | 分别在加载、曝光、收益、停用及销毁相关回调注入异常；异常不逃逸分发边界，不阻断其他合法出口、终态或清理，新实例仍能正常建立 |
| 单元／接入 | 新增 BANNER 后的格式分支及既有全屏入口 | 全屏专用入口明确拒绝 BANNER，不读取全屏 ID、进入全屏缓存／竞价／等待队列；已有全屏格式的选择、互斥及一次性事件终态保持原契约 |
| 编译 | 两端锁定版本、Compose 模块 | 真实 API 和最小版本约束可编译 |
| Release／R8 | 核心及 Compose 宿主 | 混淆后入口可达，无相关缺类／缺成员；不影响既有全屏反射 |
| 设备 | Compose 普通重组、页面内 Tab 切换 | 无额外本层请求，容器与实例保持稳定 |
| 设备 | 单 Activity 首页 → 详情 → 返回 | 旧页面不留广告；返回按约定重新创建，无迟到 View 挂回 |
| 设备 | Fragment 的 onDestroyView → onCreateView，Activity 持续前台 | 旧 owner 的监听及平台实例释放，新 owner 加载一次；旧加载／UI 回调不能进入新容器，已确认旧收益仍按原身份交付 |
| 设备 | 同路由多个 entry、快速导航、预测返回取消 | 身份隔离；未提交手势不被错误当作永久离开 |
| 设备 | Dialog、落地页、应用内全屏广告、前后台切换 | 暂时失去交互与业务离开区分正确；恢复遵循平台暂停策略；Banner 不占用全屏展示锁，平台刷新行为符合验证记录 |
| 设备 | active=false／visible=false 首次挂载及遮罩恢复 | 初始状态在请求前生效；临时隐藏不退出 Composition；恢复遵循对应平台策略，无错误点击区域 |
| 设备 | 父容器 INVISIBLE／GONE 后恢复、View 临时脱离再附着 | 真实广告不继续显示或接收点击；恢复不受 SDK 自己隐藏的子 View 阻塞，不因重复通知多次加载；保留／重建次数及身份遵循已验证的平台策略 |
| 设备 | 平台提供且已确认语义的 Banner 关闭回调 | 正确上报并清理，当前 slot 不自动重现；不把落地页关闭当作 Banner 关闭，不要求所有来源都有关闭按钮 |
| 设备 | 旋转、分屏、边到边、键盘及带 padding 的窄容器 | 使用容器实际内容宽度而非屏幕宽度，不重复扣除安全区；标准尺寸放不下时明确失败，自适应合法尺寸不裁切；有效宽度变化不导致测量／加载循环 |
| 设备 | 广告高度变化、刷新及反复测量 | View／Compose 容器跟随合法高度，无固定 50 dp 截断；仅高度变化不新增加载，加载前占位不阻断首次失败恢复 |
| 设备 | 开启刷新后首次无填充／断网，同页恢复 | 保持绑定及可见占位，确认平台实际恢复；不通过时记录降级，不能用已展示广告的刷新替代此验收 |
| 设备 | 关闭刷新及刷新失败后恢复 | 关闭时本层不自动重试，平台自身行为单独记录；刷新失败不清理仍有效的旧广告 |
| 设备／报表 | 各实际启用的 TopOn Banner 来源 | 对应来源独立验证，不能用全屏可用代替 Banner 验收 |
| Preview | Compose 预览 | 仅占位，未初始化或请求广告 |
| 静态／运行 | 初始化／本层请求前 UMP 不允许请求、所选平台失败 | 门禁未满足时不发起请求；所选平台明确失败时报告失败，业务页面正常使用；不验收运行期间隐私变化 |
| 单元／接入 | Bidding 部分初始化失败，及单平台模式失败 | AdMob 失败／TopOn 成功和反向组合分别验证：整体成功不放行失败平台；成功平台可按资格请求，失败平台不请求、不等待另一平台代替、不自动切换 |

开发时使用官方测试广告或平台测试配置；不将生产广告点击作为验收动作。单元测试、编译、R8 构建、设备运行和视觉验收分别记录，不把某一层通过描述成全部功能通过。

本次场景复核发现缺少独立验收的重点是：Bidding 部分初始化失败、同 ID 多实例隔离、曝光／收益顺序和合法零收益、外部回调异常。另将资格变化与请求阶段交错、Fragment View 重建等已有规则具体化，并补齐非法请求、全屏入口回归，细化父层隐藏、窄容器和全屏广告覆盖。上述条目已补入矩阵；平台暂停、首次失败恢复及收益身份能力仍须阶段 1 实测，不能据文档覆盖宣称功能验证通过。

## 14. 待确认项与当前验证状态

| 项目 | 当前状态 | 推荐处理 |
| --- | --- | --- |
| 容器高度自适应 | 用户明确要求 | View 与 Compose 均按合法广告尺寸和实际测量布局；撤回首版只固定 50 dp 高度的建议 |
| 埋点保留 | 用户明确要求 | 保留第 10 节全部事件及身份关联；撤回暂缓 slotId／ad_banner_refresh 的建议 |
| SDK 自有 View 与 Compose 薄包装 | 本次简化方案 | 使用 AdsBannerView，集中资源与生命周期管理，不另设外部容器绑定登记或句柄 |
| 所有临时暂停统一释放重建 | 尚未确认体验代价 | 目前保留按平台能力确定的暂停方式和 active／visible；不默认接受后台／Dialog 恢复重新等待广告 |
| 用户手动关闭 | 未要求新增功能 | 不添加 SDK 关闭按钮；只处理平台实际支持、语义已确认的关闭回调 |
| 多个导航页面是否共用一条 Banner | 用户尚未确认 | 默认示例按页面持有，公共底部作为可选布局，不建设跨页面缓存 |
| 首次无填充是否收起槽位 | 尚未确认 | 先保留合法占位，避免布局跳动；产品改变时一并明确恢复机制 |
| 持续展示所需的刷新配置 | 本文约定平台开启刷新，后台尚未核验 | 接入时记录实际广告位配置；关闭刷新按同页可能不恢复的降级行为处理 |
| TopOn 自适应与 TU 回调签名 | TU 回调与自适应常量已编译；锁定 AdMob 测试来源实测仍请求固定尺寸，自适应未通过 | 阶段 1 继续确认，未通过前不承诺自适应或暂停 API |
| GMA 1.2.1 的失败重试、刷新回调与身份 | 正式入口已验证首次断网同页恢复、刷新失败保留旧广告、连续刷新及不同 response/session、paid 先于 impression | 后台关闭刷新及真实无填充恢复仍缺对照，不套用较新版本行为 |
| 临时隐藏与前后台期间的 SDK 请求 | 正式路径已验证 Dialog／真实落地网页返回／后台暂停恢复及多原因交错设备契约；平台请求计数以各场景记录为准 | 采用保留实例并隐藏自有 SDK View 的路径；按场景区分本层调用、平台新请求和旧在途结果 |
| active／visible 与迟到回调契约 | 正式 View／Compose 已实现，事件层受控测试覆盖已知旧收益和未知身份诊断 | 页面归属与临时显示分开；UI 隔离不丢弃已确认身份的原展示收益 |
| 运行期间隐私状态变化 | 用户明确不纳入本次范围 | 只保留现有初始化和本层请求前的 UMP 门禁，不增加变化监听或联动销毁 |
| Compose 入口与最低依赖版本 | 2026-10-02 按用户决定并入 `ads-mediation` | Kotlin／Compose 插件 2.2.21、Compose UI 1.7.6、Lifecycle 2.8.7；主库包含两个 Compose 包装器，不依赖 Navigation |

2026-09-29 用户已批准首版先交付 AdMob，TopOn 后续补齐。`add-banner-support` 已实现正式 `AdsBannerView`、可选 `AdsBanner` Compose 组件、逐展示事件／收益归因、页面独立及公共底部 Navigation 示例、传统 Fragment 示例。最终尺寸与零宽恢复修正版 100 项测试通过，完整 CI、Release R8 与两个模块本地发布成功；具体设备覆盖以 [OpenSpec 实施记录](../openspec/changes/archive/2026-09-29-add-banner-support/verification.md) 为准。正式 AdMob 已记录加载、曝光、零收益、连续刷新、测试点击与返回、Fragment 重建、首次断网恢复及刷新失败保留旧广告；修正不可见预挂载、原生布局及分数像素取整后，R8 横屏首次展示已连续两次通过。不把部分设备路径等同于第 13 节全部验收。

TopOn 正式请求在 SDK 加载前返回 `topon_banner_not_supported`，不自动回退 AdMob。旧探针在用户配置和锁定 AdMob 测试来源下仍有尺寸偏差、showId 复用及刷新来源不明的反例；原双平台方案中的 TopOn 能力与各来源验收保留为后续门槛。稳定版 1.0.5 不包含本次接口，未执行远程发布；新增枚举和 AdEvent JVM 签名要求宿主及相关二进制模块重新编译，详见 [接入说明](banner-integration.md)。

2026-09-29 用户批准将1.5／6.4剩余验证作为技术债务 [BANNER-REFRESH-01](../openspec/changes/archive/2026-09-29-add-banner-support/tasks.md#已批准延期的技术债务)，待正式广告位接入后补验；当前33项完成、2项批准延期，不阻塞本轮实施收尾，未宣称全部验收通过。

2026-10-02 架构后续：将 `AdsBanner` 与 `AdsNative` 移入主库，删除独立 Compose library；调用方只需依赖 `com.cashcraft:ads-mediation`。这覆盖并替代前文“可选 Compose 模块”的交付选择；稳定版 1.0.5 未改，未执行远程发布。

后续阅读顺序：先看第 1、5、6 节确定场景与生命周期，再看第 10、14 节确认事件和待验证边界，最后按第 12、13 节实施验收。

## 15. 官方参考资料

下列资料于 2026-09-29 核对。官方网页可能面向较新 SDK；本项目以锁定依赖实际支持的能力为准。本文的页面归属、状态管理和接口设计属于项目建议，并非官方原样架构。

- [S1：Google — GMA Next-Gen Banner](https://developers.google.com/admob/android/next-gen/banner)：加载、尺寸、刷新、View 释放及 Compose 示例。
- [S2：Google — GMA Next-Gen Release Notes](https://developers.google.com/admob/android/next-gen/rel-notes)：版本变化，尤其 1.3.0 的失败请求重试调整。
- [S3：Android — Using Views in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)：AndroidView 创建、更新、复用与释放。
- [S4：Android — Interact programmatically with Navigation](https://developer.android.com/guide/navigation/use-graph/programmatic)：NavBackStackEntry 生命周期和 Dialog 目的地差异。
- [S5：Android — Side-effects in Compose](https://developer.android.com/develop/ui/compose/side-effects)：Effect、DisposableEffect 与最新回调引用。
- [S6：AdMob — Automatic refresh](https://support.google.com/admob/answer/3245199)：优化刷新与自定义频率。
- [S7：AdMob — Recommended banner implementations](https://support.google.com/admob/answer/6275335)：独立布局区域与内容／控件分隔。
- [S8：Google — Impression-level ad revenue](https://developers.google.com/admob/android/next-gen/impression-level-ad-revenue)：收益监听、金额单位与响应信息。
- [S9：TopOn — 自定义横幅广告](https://help.toponad.net/cn/docs/heng-fu-hun-yong-yuan-sheng-guang-gao)：官方 AT／TU 示例区分；自定义原生混排不属于本文首版范围。
- [S10：TopOn — 横幅广告](https://help.toponad.net/cn/docs/heng-fu-guang-gao)：普通 Banner 的 TU／AT 示例、默认关闭的自动刷新、可见性要求、释放及尺寸参数；不替代当前依赖验证。

后续 TopOn 普通 Banner、刷新和收益的具体签名，应结合官方 Android 接入目录及其页面引用的 [TPN Android Demo](https://github.com/toponteam/TPN-Android-Demo) 核验。本轮没有展开该示例仓库，不将其内容列为已验证实现。

[S1]: https://developers.google.com/admob/android/next-gen/banner
[S2]: https://developers.google.com/admob/android/next-gen/rel-notes
[S3]: https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose
[S4]: https://developer.android.com/guide/navigation/use-graph/programmatic
[S5]: https://developer.android.com/develop/ui/compose/side-effects
[S6]: https://support.google.com/admob/answer/3245199
[S7]: https://support.google.com/admob/answer/6275335
[S8]: https://developers.google.com/admob/android/next-gen/impression-level-ad-revenue
[S9]: https://help.toponad.net/cn/docs/heng-fu-hun-yong-yuan-sheng-guang-gao
[S10]: https://help.toponad.net/cn/docs/heng-fu-guang-gao
