## Context

动机见 proposal.md。与实现方式相关的现状：

- 三条展示链路都已在实际曝光回调处做一次性收口，只是没有对所有平台发出事件：全屏 `AdShowSession.impression()` 以 `terminal` 收口，只有 TopOn 开屏和 Native 在此发送 `ad_impression`；Banner `BannerDisplaySession.impression()` 以 `impressionOrFailure` 收口，只对 TopOn 发送；Native `NativeAdEvents.impression()` 以 `shown/failed` 收口，只对 TopOn 发送。
- 全屏 `ad_close` 要求先有实际曝光，否则改为以 `dismissed_before_impression` 失败收口。`ad_reward` 只在“已失败收口且没有曝光”时被拦截，奖励早于曝光回调到达时仍会发送（`AdShowSession.emit`）。
- 业务参数统一由 `AdEvent.analyticsParameters()` 生成；不满足契约时返回空 Map，宿主据此跳过上报。
- 失败原因由 `AdEvent.failureReason()` 归类，未识别的原因码统一落 `ad_error`，原码丢失。
- 收益无效时，AdMob 全屏（`AdMobProvider` 的 `paid`）与 TopOn 全屏（`TopOnAds.revenuePaid`）直接返回，没有日志；Banner 与 Native 已有 `invalid_revenue` / `invalid_native_revenue` 诊断。
- AdMob 全屏预加载每次启动只创建一个加载会话，首个终态之后的补货和重试回调被会话的终态保护吞掉。AdMob Native 已通过 `sdkAutoLoad = true` 为每个自动回调新建加载会话。

## Goals / Non-Goals

**Goals:**
- 复用现有的一次性收口点发送 `ad_show`，不新增状态。
- 新字段与诊断只做加法，已有事件的触发时机、字段和去重保持不变。

**Non-Goals:**
- 不重构失败原因的归类表，不改变任何 `reason` 取值。
- 不统一三条展示链路的会话模型。
- 不调整 TopOn 加载事件，也不调整 AdMob Native。

## Decisions

### D1 `ad_show` 挂在三个现有收口点

在 `AdShowSession.impression()`、`BannerDisplaySession.impression()`、`NativeAdEvents.impression()` 赢得收口的分支里无条件发送 `ad_show`；原有的 TopOn 专属 `ad_impression` 发送保留在其后，所以同一回调中 `ad_show` 先于 `ad_impression`。一次性保证直接来自现有的收口标志，失败先收口时自然不会再发 `ad_show`。

- `ad_show` 只携带 `adSource`、`responseId`，不传收益字段。Banner 从展示会话的元数据复制，元数据本身不含收益，并保留 `refreshIndex`。
- `analyticsParameters()` 中 `SHOW` 与 `CLICK` 共用 `ad_source` 分支；Banner 的 `SHOW` 另输出 `refresh_index`。
- 日志：开屏日志与 Native 日志为 `SHOW` 增加“已确认实际曝光”文案，`IMPRESSION` 的文案不变。

备选方案：
- 把 `ad_impression` 改为曝光驱动，收益另设事件。放弃：会改变已有看板口径，也会推翻删除 `ad_paid` 的决定。
- 用 `ad_close` 或 `ad_impression` 反推曝光。放弃：只适用于全屏，进程被杀或收益缺失时不准确。

### D2 `reason_detail` 先按来源排除，再按格式判定，不维护白名单

在 `analyticsParameters()` 的失败分支中，按顺序判断：

1. 来源：上报的 `reason` 为 `exception` 时不输出。
2. 格式：原始 `reason` 匹配 `^[a-z][a-z0-9_]{0,63}$`，且不等于上报的 `reason`，才输出 `reason_detail`。

格式本身不能证明来源。`AdsBannerView` 的加载异常会把 `error.message` 作为原始 `reason`，同时 `errorCode=load_exception`，因此归为 `exception`。如果消息恰好是 `disconnected` 这样的纯小写单词，只靠格式判断会把它当成原因码输出。

已核对的其他消息来源：
- 全屏加载失败在 `AdLoadSession.failed` 中已把平台消息替换为类别。
- Banner `onAdFailedToLoad` 传入的是 `analyticsReason`，不是平台消息。
- Native 渲染失败只保留 `native_` 前缀的原因码（`NativeCardController`）。

新增失败路径须把平台或异常文本放进 `errorDetail` 或异常分支，不得作为非异常原因码传入；README 的取值表同步写明这一约定。

备选方案：维护可上报原因码的白名单。放弃：最需要暴露的正是未归类的新原因码，白名单会再次把它们藏起来。

### D3 诊断落在日志层和收益丢弃点

- **契约外事件**：`AdsModuleLogger.event()` 在日志开启时，若事件不是 `BANNER_REFRESH`，也不是 Banner 的 `DISMISS`，且 `analyticsParameters()` 为空，就输出一条警告。关闭日志时不计算。
- **无效收益**：在 AdMob 全屏 `paid` 的两个提前返回点、TopOn 全屏 `revenuePaid` 的金额校验返回点，以及 `AdShowSession.revenue()` 返回 `false` 的金额或币种校验处，调用新的日志方法。日志带平台、格式，可确认时带会话。
- 不新增业务事件。宿主的回调交付不变。

### D4 AdMob 全屏首个终态之后按 `sdk_auto` 新建加载会话

预加载回调先取当前会话；若该会话已经终态，就像 Native 一样新建 `sdkAutoLoad = true` 的加载会话，先发 `request`，再发终态。现有的代次检查、`canLoadAds` 检查和库存耗尽分支保持不变。

### D5 指标口径锁定

README 的指标表以本节为准。“会话”指业务参数 `ad_session_id`；Banner 与 Native 的 `ad_session_id` 是页面周期 `slot_id`，周期内的刷新和重试共用。逐广告身份不在本次范围内，所以 Banner/Native 的点击率按条数统计，精度受这一点限制。

| 指标 | 分子 / 分母 | 统计单位与限制 | 适用格式 |
|---|---|---|---|
| 展示率 | 有 `ad_show` 的会话数 / 有 `ad_position` 的会话数 | 按会话去重，不超过 100% | 全部 |
| 每次机会展示数 | `ad_show` 条数 / `ad_position` 条数 | 按条数，可以大于 1，不叫“率” | Banner、Native |
| 机会失败率 | 有 `ad_show_fail` 且没有 `ad_show` 的会话数 / 有 `ad_position` 的会话数 | 按会话；不能用 `ad_show_fail / ad_position` | 全部 |
| 拦截率 | `ad_scene_skip` 条数 / (`ad_scene_skip` + `ad_position`) 条数 | 按条数；部分准入前拦截不发事件，结果偏低 | 全部 |
| 点击率 | 全屏：有 `ad_click` 的会话数 / 有 `ad_show` 的会话数。Banner、Native：`ad_click` 条数 / `ad_show` 条数 | 后者不按单条广告去重 | 全部 |
| 关闭率 | 有 `ad_close` 的会话数 / 有 `ad_show` 的会话数 | 按会话；Banner 关闭不进业务参数 | 全屏、Native |
| 激励完成率 | 同时有 `ad_show` 与 `ad_reward` 的会话数 / 有 `ad_show` 的会话数 | 按会话，不看先后；“有奖励无曝光”的会话单独列为异常 | 激励 |
| 填充率 | `ad_loaded` 条数 / `ad_load` 条数 | 按 `request_id`；批次 3 完成前不做 AdMob 与 TopOn 全屏横向对比 | 全部 |
| eCPM | `revenue_amount` 总和 / 带 `revenue_amount` 的 `ad_impression` 条数 × 1000 | 按币种分开计算；TopOn 开屏/Native 金额可能为估算，看 `precision_type` | 全部 |
| 收益覆盖率 | 带 `revenue_amount` 的 `ad_impression` 条数 / `ad_show` 条数 | 按条数；TopOn 无金额的 `ad_impression` 不计入分子 | 全部 |

收益对账的真值不在 SDK 埋点内；专用收益通道仍是 `revenueListener`。

验算样例（README 与测试按此核对）：

| 样例 | 事件 | 结果 |
|---|---|---|
| Banner 刷新 | 1 个周期：`ad_position` ×1，`ad_show` ×3（`refresh_index` 1–3），`ad_click` ×1 | 展示率 1/1；每次机会展示数 3；点击率 1/3 |
| Native 重试 | 同一会话：`ad_position` ×1，`ad_show_fail` ×1，随后 `ad_show` ×1 | 展示率 1/1；机会失败率 0/1（直接用 `ad_show_fail / ad_position` 会误算为 100%） |
| TopOn 开屏无收益 | `ad_position` ×1，`ad_show` ×1，不带金额的 `ad_impression` ×1 | 展示率 1/1；收益覆盖率 0/1；不进入 eCPM 分母 |

## Risks / Trade-offs

- [宿主对 `AdEventName` 做无 `else` 的穷举 `when`，升级后编译失败] → README 变更记录写明，宿主补分支即可。
- [每次展示多一条事件，按事件名分发的业务方未配置 `ad_show` 时会漏报] → README 变更记录写明需要为 `ad_show` 配置去向。
- [断言完整事件序列的既有测试需要插入 `ad_show`，修改时可能掩盖回归] → 只在实际曝光位置插入 `ad_show`，其他断言不动。逐个核对 diff。
- [AdMob 全屏补货的实际回调次数没有设备证据（B3）] → 单测覆盖“终态后回调新建会话”；设备验证要求实际观察到取用后的补货结果，并与初始填池的回调区分开（任务 5.3）。结果与假设不符时，回到本设计调整。
- [首个结果之后的初始填池回调也会按 `sdk_auto` 上报] → 这符合规格定义（首个结果之后的回调都算 SDK 自动加载）；设备验证时以取用时点为界区分两者。
- [`reason_detail` 可能出现新的原因码] → 原因码来自 SDK 常量，数量有限；格式限制排除平台文本。README 的取值表随新增原因码更新。

## Migration Plan

- 作为下一个版本发布。README 当前版本号、依赖示例与 `gradle.properties` 在发布时统一为实际发布的版本号。
- 回滚：所有改动都是新增，回退提交即可，不需要处理数据。
