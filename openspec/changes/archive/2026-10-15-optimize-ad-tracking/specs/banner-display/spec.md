## MODIFIED Requirements

### Requirement: 完整公共事件与事实边界

系统 SHALL 通过现有公共出口保留 `ad_position`、`ad_load`、`ad_loaded`、`ad_load_fail`、`ad_banner_refresh`、`ad_show`、`ad_impression`、`ad_click` 及语义明确的 `ad_show_fail`、`ad_close`。本层加载请求最多有一次终态结果；不可观察的平台内部请求 SHALL NOT 被伪造成本层请求。`ad_show` SHALL 只在平台确认真实曝光时发送；刷新成功、加载成功、恢复显示或收到收益 SHALL NOT 代替平台确认的真实曝光；一次曝光去重不得阻止下一次真实刷新曝光。

#### Scenario: 首次展示及连续两次刷新
- **WHEN** 平台确认首次展示及连续两次新广告刷新，并分别回调真实曝光
- **THEN** 各展示独立发送 `ad_show` 并携带各自的 `refresh_index`，刷新结果单独记录，各自重复曝光回调不重复上报，所有公共事件保持对应身份

#### Scenario: 失败终态后的平台内部重试成功
- **WHEN** 本层加载已上报失败终态，而平台内部重试后来成功
- **THEN** 可更新有效广告状态并处理真实曝光及收益，不为原请求补发第二个结果或伪造新 request；仅确认属于刷新的结果才报告刷新事件

#### Scenario: 点击和展示失败归属
- **WHEN** 实际广告产生点击或已进入展示尝试后的确定失败
- **THEN** 事件归属该广告，不从当前页面补身份

#### Scenario: 未曝光的展示机会结束
- **WHEN** 已建立的展示机会在平台确认曝光前，因加载失败、加载调用异常、取消或替换、加载前失去资格、页面周期结束而终止
- **THEN** 该机会只以一条 `ad_show_fail` 收口，携带对应原因，不发送 `ad_show` 或 `ad_close`

#### Scenario: 已曝光展示的隐藏与清理
- **WHEN** 已确认曝光的展示随后被隐藏、导航清理或页面周期结束
- **THEN** 不产生 `ad_show_fail` 或 `ad_close`；只有平台确认的 Banner 主体关闭才产生 `ad_close`

### Requirement: 收益顺序金额与迟到归属

系统 SHALL 独立处理曝光和收益，不要求曝光先到；身份可关联时两者共用稳定展示 session 且分别去重。合法零收益 SHALL 正常交付，缺失或非法金额 SHALL NOT 补零或用 eCPM 冒充收入。已确认身份的迟到收益 SHALL 仍交付全局收益监听及 AdMob Banner 的 `ad_impression` 并归属原展示，即使页面已结束；不得调用已释放页面回调或长期持有页面资源。未来 TopOn 聚合链路接入时，收益 SHALL 仅走承载平台出口；本轮 unsupported 路径不得产生 Banner 展示收益。

#### Scenario: 收益和曝光先后互换及重复
- **WHEN** paid 先于 impression 或顺序相反，并各自重复回调
- **THEN** 可关联时使用同一展示身份，`ad_show` 与 `ad_impression` 分别去重，不补造曝光；合法零金额也通过正常收益出口

#### Scenario: 旧展示收益跨周期迟到
- **WHEN** 旧页面已销毁且新页面建立后，旧展示的可确认收益到达
- **THEN** 收益仍归原展示并交付全局出口，不更新新页面或读取新周期身份补全旧收益；无法保证平台销毁后必定回调

#### Scenario: 金额或身份不可靠
- **WHEN** 金额缺失或非法，或平台回调不能可靠关联到某次展示
- **THEN** 保留平台实际金额、币种及身份用于诊断，不伪造正常 session 或静默归到最新展示；无法关联路径在明确降级表达前不计为收益验收通过

#### Scenario: TopOn 不支持路径无正式收益
- **WHEN** 公开 TopOn Banner 请求返回 unsupported，即使独立探针曾观察到聚合下 Google 广告收益
- **THEN** 正式 Banner 出口不伪造展示、曝光或收益；未来 TopOn 接入需独立确认单一承载平台收益出口及逐次展示身份
