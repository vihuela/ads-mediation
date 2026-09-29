# OpenSpec 核验报告

日期：2026-09-29。对象：`add-banner-support` 当前工作树。范围：完整性、正确性、一致性；本次只核验并生成报告，未修复生产代码、修改规格或归档。

## 结论

未发现新的 CRITICAL 问题；2 项 WARNING、1 项 SUGGESTION。按用户已批准的交付范围，可进入带技术债务的归档流程；不代表35项全部验收通过。建议归档前同步下列旧文档表述，归档时必须保留 BANNER-REFRESH-01 及其补验条件。

## 完整性

proposal、specs、design、tasks 已存在。任务33项完成，1.5／6.4明确批准延期，其未验证部分已登记为 BANNER-REFRESH-01；TopOn正式能力为后续范围，当前公开入口明确unsupported。

对应代码已核对：请求参数与平台门禁、View资格和代次、释放与过期对象、Compose按值身份与最新回调、尺寸与原生布局、平台刷新、slot/request/display关联、收益独立去重与迟到收益、全屏入口隔离、可选Compose发布模块。

## 正确性

- AdsBannerView在加载前检查代次及当前资格，空AdView不可见预挂载；成功回调先准备身份和安装监听，再投递业务结果和恢复显示。
- 失活结束slot，临时暂停隐藏并保留实例；尺寸重建保留slot，旧回调按generation隔离。失败实例保留合法占位，不增加本层自动重试。
- AdMobBannerEvents按回调入口的response快照处理展示；元数据保留上限32，未知身份诊断而非归到最新展示。收益和曝光分别去重，已知迟到收益不依赖页面存活。
- Compose使用Activity、owner、请求值作为身份；停用先于显露、隐藏先于启用，释放调用destroy，Preview不进入SDK路径。
- 现有设备证据覆盖真实窗口重入／异常／过期交付、暂停交错、断网恢复、分屏、预测返回取消及实际落地网页返回；没有把受控回调当作真实收入或服务端素材高度变化证据。

## WARNING

### W1：批准延期的刷新配置与NO_FILL验证仍未完成

位置：tasks.md 1.5、6.4及BANNER-REFRESH-01；specs/banner-display/spec.md“平台独占刷新调度与明确的失败恢复边界”。

官方测试广告的断网恢复和自动刷新证据不能证明业务后台开关或首次NO_FILL恢复。用户已批准待后续接入正式广告位再补验，因此不是本轮新增阻塞；保持未勾选并随归档保留债务。关闭标准和测试设备要求沿用tasks.md，不降低为“代码路径存在即通过”。

### W2：部分上游文档仍保留旧的顺序与验收状态

- proposal.md:12仍写收益监听先于“首次挂载”；spec与design已按批准方案允许不可见空View预挂载，要求监听先于首次可见。当前实现符合后者。应将proposal同步为批准后的顺序，避免后续照旧文档回退已验证的实现。
- design.md:60写“先读当前状态，再…观察”；实际BannerProviderReadiness.observe先注册再同步回读，避免读取／订阅间隙，符合规格。应将设计描述同步到实际顺序。
- docs/banner-implementation-plan.md:506仍写“落地启动页／完整暂停矩阵尚未验收”；最新verification已记录真实网页返回及多原因暂停契约测试。应更新该现状摘要，保留各类证据的适用范围。

上述为文档一致性问题，未发现需要将已批准实现改回旧设计的依据。

## SUGGESTION

归档后让后续正式广告位接入任务直接引用BANNER-REFRESH-01的归档路径，避免债务只停留在历史验收记录中；当前无需新增生产配置或测试框架。

## 验证依据与边界

本次重新读取源码／规格并检查已有产物：JVM XML共17个suite，100 tests、0 failures/errors/skipped；device-contract-final-ci.log为BUILD SUCCESSFUL，默认设备契约11例及含断网12例日志均为PASS。生产源码自该轮验证后未在本会话修改，本次未重复构建或启动模拟器；已有设备执行细节和截图索引见verification.md。

本次执行`openspec validate add-banner-support --type change --strict --no-interactive`通过，`git diff --check`通过。文档结构校验不替代实际广告后台验证。未修改任务勾选，未提交、推送、发布或归档。

## 归档前复核

2026-09-29 根据用户归档指令，已同步W2列出的proposal监听顺序、design订阅读取顺序及原方案暂停验收摘要，W2已解决。当前无CRITICAL，剩余1项WARNING（批准延期的BANNER-REFRESH-01）及债务承接建议；按已批准范围带债务归档，未将1.5／6.4标为通过。
