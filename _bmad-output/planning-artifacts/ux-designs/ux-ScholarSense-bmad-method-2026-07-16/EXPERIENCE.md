---
name: 学林知微体验规格
status: controlled-baseline
version: 2.1.1
implementationReadiness: ready
externalGateStatus: approved-for-implementation
runtimeEvidenceStatus: pending-story-execution
binds:
  - FR-1..FR-62
  - BR-1..BR-12
  - NFR-1..NFR-34
normativeCompanions:
  - ../../architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md
  - ../../requirements-traceability.md
  - ../../open-decisions.md
  - ../../high-risk-action-matrix.md
  - ../../delegated-decision-baseline-2026-07-17.md
  - ../../app-applicability-baseline-2026-07-19.md
sources:
  - ../../prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md
  - imports/校园服务总入口WEB端设计规范.pdf
  - imports/苏大UI规范说明(1)(1).pdf
  - imports/苏大logo.png
updated: 2026-07-19-story-1.1d-applicability-alignment
---

# 学林知微体验脊柱

> 本文定义“如何工作”；视觉以 `DESIGN.md` 为准。原型和导入物仅作来源参考，冲突时两份脊柱优先。

## Foundation

产品形态为学校统一门户内的响应式 Web 管理端，以及统一 App WebView 内的移动轻量处置。UXB/PAB/CTV/PP/AP-1.0.0 与 G-05 已由 `AUTH-2026-07-17-001` 批准进入实现，Vue/Element Plus/ECharts 精确组合见 Architecture AD-28。产品复用学校 SSO、公共消息、待办和移动壳层。Web 视觉、无障碍和用户感知性能由 1.1c/1.1d、1.2 的 DoD 验证；`AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 仅把 1.1c/1.1d 的 App/WebView 报告裁定为 N/A，真实 WebView/真机证据仍由 7.x 在未来新基线版本下验证。适用门失败时禁止对应 UI Story 完成或发布。`DESIGN.md` 是视觉身份参考，本文件只定义行为增量。

核心体验原则：证据先于建议；线索而非结论；质量不达标即熔断；所有正式事项有责任人、时限、状态与结果；已有信息自动带入。完整功能边界、FR 与术语引用上游 [PRD](../../prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md)，本文不重复枚举。

## Information Architecture

| 领域 | 表面 | 主要到达方式与闭合任务 |
|---|---|---|
| 公共 | 角色化首页、公共待办深链、无权限/会话失效 | 从门户或 SSO 进入；按常设角色与对象权限落到首屏。会话失效后，重新认证可返回原目标 |
| 一线关怀 | 工作台、候选初审、正式线索 Top-k、线索详情、核实反馈、持续观察、一人一档 | 辅导员从待办或概览进入证据—核实—行动闭环 |
| 协同 | 转介列表/详情、处理回填 | 从公共待办或原线索进入；业务状态与投递状态分栏。目标部门责任人关闭工单，结果回到原线索后由辅导员独立决定原线索状态 |
| 学院治理 | 学院成长总览、超期督办、周报/月报 | 汇总指标仅在授权范围下钻到任务并形成督办 |
| 校级治理 | 领导驾驶舱、改进计划/资源调配 | 仅汇总态势，不提供临时个体下钻；行动记录指标版本、责任方、截止、基线和复查结果 |
| 规则与标签 | 规则配置/版本、专项、标签治理、规则运营复盘 | 治理审批态、运行态、质量态分栏；测试、灰度、生产、熔断、停用与回退只执行合法动作 |
| 数据责任 | 数据源目录、标识异常、质量面板、熔断恢复 | 从异常待办进入影响范围—修复—校验—授权恢复，可见数据口径/批次/规则影响 |
| 系统运维 | 技术运行监控、作业、接口与审计检索 | 仅见技术状态和 traceId，默认不可见学生明细或证据正文；月度可用性显示 AvailabilityPolicyVersion、业务时段、采样缺口和维护/外部依赖单列 |
| 智能增强 | 建议卡、自然语言问数、模型/策略发布关卡 | 建议可采纳/编辑/拒绝但不自动产生高影响动作；问数只回答已定义汇总指标；关卡未通过不得灰度 |
| 工作纪实 | 进宿舍周/月统计、异常记录 | 仅用于工作纪实与资源安排；不得生成学生线索或辅导员绩效结论 |
| 移动轻量处置 | 待办、线索/证据摘要、联系、快速核实、持续观察、协同接收/处理/补充/回填 | 统一 App 深链；所有业务读写在线完成；不承载驾驶舱、规则配置、复杂质量页或独立底部导航 |

表面闭合：每个 IA 表面均落入 UJ-1—UJ-6 或支撑这些旅程的治理路径；原型的四组 IA 与双壳层可继承，但演示角色切换、越权路由和 Toast 假闭环不得进入正式产品。

关键构图参考：[辅导员工作台](mockups/workbench.html)、[住宿安全线索详情](mockups/clue-detail.html)、[领导驾驶舱](mockups/leader-cockpit.html)、[移动轻量处置](mockups/mobile-task.html)。协同转介、学院治理、规则与标签、数据质量、审计检索、问数、报表、一人一档和工作纪实按本文表格实现，不另设 mockup。UX 不新增 PRD 未定义的“合规材料上传/复核”或通用“任务中心”。所有视觉参考与本文冲突时，以双脊柱为准。

## Voice and Tone

| 场景 | 使用 | 避免 |
|---|---|---|
| 线索 | “发现一项需要人工核实的变化” | “该生存在……问题/风险” |
| 等级 | “III 级核实优先级 · 24 小时内” | 只显示“高危”“III” |
| 经济场景 | “消费变化低于本人历史区间，建议沟通核实” | “贫困生”“经济异常已确认” |
| 熔断 | “门禁数据连续性未达门槛，相关规则已暂停产出” | “系统异常，请稍后再试” |
| 权限 | “当前职责范围不包含此对象；本次访问已记录” | 暴露对象是否存在或敏感字段名 |
| 建议 | “可考虑……；请结合沟通结果判断” | 命令式或诊断式建议 |

句子短、具体、中性；事实、规则判断、建议分段标注。成功反馈必须说明对象状态和下一步，不只说“操作成功”。

## Component Patterns

| 组件 | 行为契约 |
|---|---|
| `unified-shell` | 根据 RFP-1.0.0 的七角色 object/action/scope 矩阵生成菜单与单一默认首页，并以 RFP-FIXTURE-1.0.0 验证；禁止模拟身份切换。只合并 scope predicate 成立的 action，applicable deny/职责冲突优先；无该 object/action 权限的角色包不参与字段降级。会话失效认证后返回原目标。 |
| `page-header` | 始终展示页面名；数据表面追加统计周期/更新时间；主操作至多一个。 |
| `metric-card` | 回显 metricId、口径和 MetricPublicationPolicyVersion；零值、缺失、不可用与 suppressed 分开表达，suppressed 不可下钻且不得显示为 0。可点击指标以按钮/链接语义下钻；不可下钻指标不响应 hover/click。 |
| `workload-summary` | 固定显示候选待初审、正式线索待核实、处理中、今日已跟进、超期和今日核实容量；回显统计日期、本人范围、QueuePolicyVersion、容量值/来源。前五项与清单总数可对账；无任务、数据缺失和容量 policy 未批准分开表达。 |
| `task-table` | 支持键盘行导航、排序、分页、筛选回显；行打开详情，行内高风险动作独立聚焦；大数据不使用无限滚动。专项视图显示 SPM-1.0.0、program/window、academicCalendarProjection 的 term/source/projectionVersion/sealed 状态、required/optional 状态与 degraded 原因；单信号不出现专项优先项，专项行只引用既有 Candidate/Clue。 |
| `filter-bar` | 提交后 URL/视图状态可恢复；显示已选条件与清除入口；筛选无结果不等同于系统空。 |
| `status-tag` | 输出文字状态、语义图标和可访问名称；超期是并行标记，不覆盖业务状态。 |
| `evidence-chain` | 按 EvidenceSchemaVersion 与 comparisonMode 展示：personal-baseline 显示基线/变化，absolute-threshold 显示阈值/比较符，node-fact 显示节点/批次；非适用字段显示“本模式不适用”及原因，不把空基线误报为缺数据。事实、规则判断、建议分区，显示来源、窗口、质量快照与最近更新时间；规则熔断时既有对象仍可见并标质量影响。 |
| `explanation-feedback` | 分别提供“证据足够/不足”“解释清晰/不清”“建议有用/无用/未使用”与版本化负向原因；提交失败保留输入，不改变线索状态、分数或等级，生成 ExplanationFeedback 事实。 |
| `sensitive-field` | 显示 RFP-1.0.0、适用 rolePackage/scope 与 B/I/C/S/E/N/G/T 字段类；R5 星号字段只允许 C* phone/email、S* referralReasonCode/requestedServiceCode、N* referralSummary/supplementRequestText/resultSummary，并与 Transfer purpose/fieldAllowlist/有效窗求交；R2 个案还需显式督办 workItem。clear 显示批准最小字段，masked 使用与原长度无关的固定替代，hidden 不进入 JSON、导出、DOM、缓存或无障碍树且不泄露键/长度。策略/密钥不可用时 fail closed，权限变化在下一请求和序列化前生效。 |
| `action-form` | 根据属实/噪声/待观察动态必填；显示 CAC-1.0.0 categoryCode、动作状态、dueAt/来源、唯一 completionKind、完成证据和 wasOverdue。属实先处理中；只有 completed 动作可让当前责任人显式已跟进。Observation 必须显示唯一 clueId、`[startsAt,reviewAt)`、in-progress 状态和唯一复查任务；从多线索专项进入时强制选择一条当前可见参与 Clue，缺省/多选不可提交，专项仅显示 sourceContextRef。未知目录项禁用并给出原因；提交幂等，失败保留输入并聚焦错误摘要。 |
| `transfer-work-order` | 显示 dueAt、TSP-1.0.0、时钟与批准的 pause interval；待补充、退回、重提或换责任人不重置 dueAt。退回态只向仍具权发起方提供“补齐并重提”，成功后以同一 transferId 执行 `退回→待接收`，保留原 dueAt/历史并等待目标方重新接收。`transfer.submit` 使用 HRAP-1.0.0 D1；失败不创建工单或显示成功。submit、接收、delivery confirmed、已回填或目标方关闭均不等于 CareAction 完成；原 Clue 当前责任人确认回填后才显示 CAC `transfer-result-confirmed`，并独立决定原线索后继。 |
| `delivery-status` | 必须显示 DeliveryRecord 归属；转介表面只绑定 `TransferOrder.deliveryStatus`，公共任务故障表面绑定 `TaskDelivery.status`，不得把字段挂到 Candidate/Clue/QualityEligibility/GovernanceAction。状态只取 pending/retrying/confirmed/failed 并与来源业务状态分栏；confirmed 的文案是“外部系统已确认投递”，不得显示“业务人员已接收”。任何投递状态都不能替代具权接收命令或把待接收变为处理中。 |
| `delegation-grant` | 与永久责任转移分开；状态显示 draft、pending-approval、active、expired、revoked、cancelled。展示授权人、受托人、对象/动作/字段交集、RFP-1.0.0、生效/失效、原责任人、grantId、matrixVersion 和受控撤销原因；不得展示或返回策略交集外字段。issue/revoke 均经 HRAP-1.0.0；动作未映射、运行测试未通过或执行时失败时显示 deny reason。达到结束边界时 active→expired 且只生成一个事件；非法态、无权与 409 不改变状态，幂等取消/撤销/过期调度不重复发事件，且不重置业务时钟。 |
| `timeline` | 合并机器信号、人工核实、协同结果但明确类型；噪声不作为属实历史；支持按类型过滤。 |
| `data-quality-panel` | 数据责任人看到批次、口径、质量和受影响规则；依赖树逐项显示 sourceId→dependencyId、source/dependency version、required/optional、all-of/any-of/threshold 与失败成员，不能把“一个门禁源 eligible”显示为整条 ACC-SAFE eligible。面板同时显示 QualityRecoveryPolicyVersion、consecutivePassedBatches、observationDuration、样本重算预期、latestActionableAt 和 D4 状态。任一条件失败回 fused；只恢复未超过 latestActionableAt 的窗口。系统运维表面只见技术状态。 |
| `chart-with-table` | 图表与表格共享筛选、口径和 MetricPublicationPolicyVersion；suppressed 单元使用文字说明且不参与误导性排序/比较。切换不丢状态；键盘与读屏可读取等价数据。 |
| `governance-dialog` | 规则发布、回退、熔断恢复、权限变更等显示影响预览与理由。HRAP-1.0.0 已批准；只为对应 Story 运行测试已通过的 actionType 展示 D1—D4 流程，未知/未验证动作显示 D0/deny。提交后进入真实持久状态。 |
| `rule-lifecycle-panel` | 治理审批态、运行态、质量态分栏；只显示当前状态可执行动作、所用规则/矩阵版本和未通过的 Story/Release 证据。 |
| `suggestion-card` | 标明策略版本和“建议”属性；允许采纳、编辑、拒绝和原因反馈；不得自动发送给学生、改等级、转介或关闭。 |
| `governance-action` | 记录 metricId/口径、责任方、资源/措施、截止、基线、目标和复查周期；状态为 planned→active→completed/cancelled，复查为 pending→reviewed 并使用 improved/not-improved/data-insufficient/metric-changed。`leader-action.record` 使用 HRAP-1.0.0 D1；未映射、运行测试未通过或执行时失败时显示 matrixVersion/deny reason，不持久化行动或任务。不提供个体下钻或自动惩戒。 |
| `state-panel` | 为加载、数据为空、筛选无结果、错误、无权限和降级状态分别给出准确说明；对可恢复状态提供唯一的恢复动作。 |
| `primary-button` | 仅用于当前表面的主要推进动作；提交中锁定重复触发但不清空表单；成功后显示新状态与去向。 |
| `mobile-task-card` | 在线卡片进入当前授权摘要；电话等系统动作明确确认；支持辅导员核实/观察和协同人员接收/处理/补充/回填。断网不显示缓存对象，命令未获服务端确认不得显示成功。 |

以上同名组件均在 `DESIGN.md.Components` 有视觉定义。

## State Patterns

| 状态族 | 覆盖表面 | 处理规则 |
|---|---|---|
| 冷加载/刷新 | 全部列表、详情、图表 | 保留页面骨架；超过阈值显示进度语义；不把旧数据伪装实时 |
| 数据为空/筛选无结果 | 所有队列、报表、档案时间线 | 分开文案；数据为空时说明入口或完成态，筛选无结果保留条件并提供清除 |
| 错误/重试 | 表单、消息、导出、接口、图表 | 保留输入/筛选；说明影响范围；幂等重试；异步任务给任务号和结果入口 |
| 无权限/会话失效 | 所有路由、字段、导出 | 不泄露对象信息；记录审计；重认证后回原目标；对象授权失效后，立即撤销对该对象的访问权限 |
| 降级/陈旧 | 工作台、详情、驾驶舱、质量/运行面板 | 显示数据时间、不可用依赖和受影响动作；若核心既有任务仍可处理，明确说明可继续处理的任务和不可用的动作 |
| Candidate 前置门 | 规则治理、质量面板、候选队列 | 质量失败只显示质量资格/熔断事实且不产生 RuleEvaluation；质量通过后，排除/白名单/去重失败才显示 rejected CandidateAdmissionDecision。两类失败均不发 `candidate.generated`、不进辅导员队列、不启动时钟/待办；只有 admitted 才创建 Candidate |
| 熔断/恢复观察 | Candidate、Clue、规则、质量面板 | 熔断期不生成新 RuleEvaluation/Candidate/Clue；既有 Candidate/Clue 保持授权可见，只更新证据质量与可执行动作。恢复按 QualityRecoveryPolicyVersion 显示连续通过批次、观察时长、latestActionableAt 与 D4；任一失败回 fused |
| 并发/重复提交 | Web 与移动的核实、转介、治理动作 | 以服务端 aggregateVersion 为准；冲突时显示最新操作者、时间和版本，并保留当前进程内草稿。跨端同步按 `committedAt→ui.state-observed` P95≤5 秒 |
| 临期/超期 | Candidate、正式线索、转介、督办 | 与业务状态并存；接纳、权威责任转移或代办均不重置时钟；完成后保留“曾超期” |
| 字段校验/高风险确认 | 所有表单与治理对话框 | 字段内错误 + 顶部摘要；聚焦首错；高风险动作按门禁要求复核而非单击生效 |
| 离线/网络波动 | 移动轻量处置 | 首期不读取或显示持久化业务摘要；只显示无对象信息的网络状态。当前页面表单仅在进程内易失保存，刷新/关闭/登出/换号即清除；恢复后重新认证、授权、刷新版本并显式重试 |

Candidate 初审与首次核实只从 admitted 后真实发布的 `candidate.generated` 起算；拒绝门禁没有业务时钟。接纳、责任转移和代办不得重置：III/II/I 级初审分别为 2 小时、8 小时、2 个工作日；首次核实分别为 24 小时、48 小时、5 个工作日。界面同时显示绝对截止时间、剩余时间和 BusinessCalendarVersion；缺校历版本时阻塞工作日动作而非猜测。

## Interaction Primitives

- 鼠标与键盘并重：`Tab` 顺序符合阅读顺序，`Enter/Space` 激活，`Esc` 关闭顶层浮层，焦点返回触发点。
- 深链保持对象、来源与筛选上下文；返回不丢工作台位置。
- 桌面 hover 只作补充；任何动作不可仅在 hover 出现。触屏使用按下反馈与显式菜单。
- 表格分页，不用无限滚动；批量操作显示选择数量、影响范围与结果。
- 页面切换后焦点进入主标题；动态提交、问数回答与同步冲突通过合适的 live region 宣告。
- 禁止：多层模态叠加、Toast 代替持久状态、未知问数返回看似成功的默认答案、拖拽作为唯一排序方式、颜色作为唯一编码。

## 治理与专项交互

| 专项 | 正式行为契约 |
|---|---|
| 权威责任转移 | 展示旧/新责任人、权威来源版本、原因、生效时间和未完成事项；原截止时间不变，双方收到同一事项更新；无接收人进入学院异常队列。 |
| 授权代办 | 独立于责任转移，展示授权人、受托人、对象/动作范围、生效/失效、原责任人、grantId 和撤销；原责任人/截止不变，到期或撤权后下一次请求立即失效。 |
| 标签治理 | 标签创建/修改须声明来源、用途、敏感等级、可见角色、有效期；经历待审核→生效，可过期、撤销或因争议暂停参与计算；历史版本不可覆盖。 |
| 自然语言问数 | 仅查询口径库中的汇总指标，并显示问题解释、筛选、口径、时间范围、更新时间和来源；无法理解时请求改写，无数据时区分“零”与“缺失”，敏感/越权时拒答且不泄露对象存在性。 |
| 模型与策略治理 | 版本详情展示 StrategyGatePolicyVersion、比较基线、阈值、样本范围、分群指标、质量门、灰度范围和回退条件；初始 canary、扩大灰度、生产与回退均通过 `governance-dialog` 留下完整证据。 |
| 解释反馈 | `explanation-feedback` 按规则/模板版本聚合证据充分度、解释清晰度、建议有用度及负向原因，进入规则复盘；未评价与负向评价分开统计，反馈本身不改变对象状态或分数。 |
| 审计检索 | 按用户、对象、动作、时间、结果、traceId 检索；原始记录只读且至少保留 6 个月。PRD 未定义合规材料对象，因此不提供上传、复核、退回或完成工作流。 |
| 导出 | 所有报表/明细导出遵守 RFP/RS/HRAP-1.0.0 与最小化范围并异步生成；对应 actionType 的运行测试未通过、执行时 deny、下载已过 7 日或授权失效时，申请/生成/下载失败并显示版本/deny reason。获准时显示作业号、申请范围、审批/失败状态、RS-1.0.0、文件有效期和下载审计，不通过 Toast 假装完成。 |
| 期限专项 | 仅按 SPM-1.0.0 展示入学 2-of-2、考试 2-of-2、毕业 2-of-3 的只读投影；三者共同依赖由 ACADEMIC+CALENDAR 源生成的 sealed/effective academicCalendarProjection，详情显示 termStartAt、考试/封账/毕业边界、sourceVersions 与 projectionVersion。required 失败不生成新投影，optional 缺失明确标 degraded，单信号保留原队列且不显示专项结论。直接 source 只显示合同/生效、eligible、sealed 与 DCC 水位，不能显示规则 `production` 状态。专项本身不拥有 Observation；进入观察时必须显式选择一条当前可见、未终态的参与 Clue。 |
| 学业关怀 | 学业数据按 ACN-1.0.0 的期中、期末、缓考后、课程完成、体测、论文和毕业七个 nodeCode 显示 term、sealed/effective batchVersion、来源、完整性和本人基线；未知/未封账/撤销节点不可操作，单次成绩或排名变化不得直接成为学生结论。 |
| 跨类别合证 | 每条独立线索保留来源、规则与时钟；只在发生时间差≤168h（含边界）时合证，解释共同事实、独立证据和排除项，不能把多类别简单相加为更高风险。 |
| 工作纪实 | 引用 WorkVisitPolicyVersion 显示去重、配对和异常口径；只汇总已记录的工作动作，不进入学生规则计算，也不以次数形成个人绩效排名。 |

## 体验性能与连续性

- 工作台、线索列表和详情从用户导航/操作开始，到必需数据、当前授权投影、关键控件和可访问名称完成并发出 `ui.content-ready`，在 approved PerformanceProfileVersion 的精确负载与环境下 P95≤2 秒；骨架不算完成。汇总看板从筛选动作开始，到图表、等价表格和辅助技术反馈全部更新，P95≤3 秒。
- 单条核实反馈以 3 分钟内完成为设计目标：已有学生、证据和责任信息自动带入，只让用户补充人工核实与行动结果。
- Web 与移动共享唯一业务状态；从服务端事务 `committedAt` 到另一在线端应用同一或更高 aggregateVersion 并发出 `ui.state-observed`，P95≤5 秒。冲突处理遵循 `State Patterns`。
- 网关响应仅是诊断分段，不替代上述 UX SLI；证据引用同一 PerformanceProfileVersion，冻结设备、浏览器/WebView、宿主、网络、冷热缓存、数据规模、数据分布、场景窗口、采样点和失败归因。
- 异步导出、重算和发布不占用页面会话；用户可从公共待办深链或所属领域任务状态页查看结果，不新建通用任务中心。

## Accessibility Floor

- 全部 Web/WebView 达到 WCAG 2.2 AA；视觉对比见 `DESIGN.md`。
- `UXB-1.0.0 / DEC-006/008` 固定移动主操作命中目标至少 44×44 CSS px、桌面常规控件视觉高度 36px；桌面独立图标/行内动作通过外层命中区提供至少 40×40 CSS px 的可点击区域，视觉控件仍保持 36px 高。内联文本链接等例外至少满足 WCAG 2.2 AA 的 Target Size (Minimum)，且不得紧邻到造成误触。Story 1.2、7.x 仍须在目标浏览器/WebView、键盘和辅助技术上验证。
- 表格具有可访问名称、列头关系、排序状态和键盘行操作；焦点始终可见。
- `chart-with-table` 必须提供同口径的数值摘要或数据表；图例不只靠颜色。
- 状态、等级、错误、必填均有文字；错误摘要关联到字段。
- 屏幕阅读器读出敏感字段的状态而非脱敏长度；不可见字段不进入无障碍树。
- 尊重 `prefers-reduced-motion`；上传/加载动画可停止或降为静态进度，不使用闪烁。
- 200% 缩放下无关键内容丢失；移动 375px 无横向页面滚动，数据表采用优先列/详情展开。

## Responsive & Platform

| 环境 | 行为 |
|---|---|
| ≥1440px 基准桌面 | 完整门户/模块导航；表格与详情可并列但不牺牲证据阅读顺序 |
| 1366×768 最低桌面 | 侧栏可收起；工具栏允许分行；主要任务无需横向页面滚动 |
| `[PRODUCT BASELINE v1.0 / DEC-007]` 768–1023px 窄桌面/平板 | 导航抽屉化；双栏改单栏；表格保留优先列并把其余字段放详情 |
| `[PRODUCT BASELINE v1.0 / DEC-007]` <768px / 375px 基准 | 仅移动轻量处置；使用 `mobile-task-card`，不缩放桌面驾驶舱或配置页 |

正式验收覆盖 AD-28 ProductionArtifactBaselineVersion 声明的 Edge/Chrome 和学校统一 App iOS/Android WebView/宿主版本；不得以“最新”作为不可复现条件。具体中间断点是产品假设，不是校方硬规范；需以真实设备测试校准。

## Inspiration & Anti-patterns

- **继承学校 UI 规范：** 统一门户壳、苏大品牌红、字体、表单/表格/状态组件家族；视觉依据见 [Web 规范](imports/校园服务总入口WEB端设计规范.pdf)、[移动规范](<imports/苏大UI规范说明(1)(1).pdf>) 和 [校徽](imports/苏大logo.png)。
- **继承现有原型：** 四组业务 IA、双壳层概念、证据链、人工核实主闭环、事实/判断/建议分层。
- **拒绝原型模式：** 演示角色切换、只隐藏菜单不拦路由、固定时间、无分页、单击循环权限、Toast 假闭环、自然语言问数未知意图默认成功。
- **拒绝“预警大屏”：** 不做学生风险排行榜、学院单指标排名、红色告警墙；驾驶舱必须落到资源协调或督办行动。

## Key Flows

### UJ-1 王老师在晨间工作台发现并核实住宿安全线索

运行前置：ACC-SAFE RuleVersion 的 `runtimeStatus≠production`、`qualityStatus≠eligible`，或最小课表/队列/校历 Story 证据未通过时，业务用户只看到“规则尚未具备生产运行资格”，不显示模拟或可处理 Candidate；治理人员分栏查看状态与未通过证据。这不阻止 UI/规则实现开工。

1. 在已批准的 HIP/ISP/RFP/RC/QP/BC 基线上，王老师从统一门户进入角色化工作台，看到候选待初审和正式线索 Top-k 分区。
2. 她打开一条住宿安全候选，`evidence-chain` 展示门禁事实、时间窗、课程活动、请假/住宿/设备排除和质量快照。
3. 她接纳候选；原公共待办更新为正式线索，截止时钟保持从候选生成起算。
4. 她联系学生，在 `action-form` 中记录联系情况、核实结果与关怀动作。
5. **Climax：** 属实结果先进入“处理中”；只有同次提交也包含 CAC-1.0.0 匹配 completionKind 与证据的 completed 动作时，当前责任人才可显式进入“已跟进”。待观察原子创建有效 Observation、in-progress 观察动作与唯一复查任务；噪声带原因关闭。工作台计数、责任人与下一步同步更新。

失败路径：质量门失败只形成质量资格/熔断事实，不产生 RuleEvaluation；排除、白名单或去重拒绝形成 rejected CandidateAdmissionDecision，但不显示 Candidate。规则熔断时不生成新 RuleEvaluation/Candidate/Clue，既有对象保持授权可见并标注证据质量。候选拒绝/合并要求原因和目标，409 冲突刷新最新版本并保留当前进程内草稿。

### UJ-2 王老师识别隐性经济压力并发起资助协同

1. 王老师从工作台打开经济类线索详情。
2. 她查看 28/56 日个人基线、有效餐次及请假/离校排除，不将低消费当作结论。
3. 与学生沟通确认需要支持后，她选择“资助协同”。
4. `transfer-work-order` 自动带入最小必要证据、TransferSlaPolicyVersion 和 dueAt；她补充原因、已完成动作与期望结果，并在 `transfer.submit` 门禁通过后发送。
5. 资助人员接收、处理并回填，王老师收到原线索内的协同结果。
6. **Climax：** 王老师基于回填选择继续观察或完成关怀，系统全程只表述“消费变化”和人工核实结果，不给学生贴“贫困”标签。

运行前置：`ECON-012` 的 `runtimeStatus≠production` 或 `qualityStatus≠eligible` 时不展示模拟 Candidate 或可操作详情；业务用户看到“规则尚未具备生产运行资格，当前不可用于生产研判”，只有具权治理人员可分栏查看状态与未通过 Story 证据。RC-1.0.0 已批准，因此该前置不阻止 4.1a/4.1b 开工。

失败路径：动作未映射、运行测试未通过或执行时门禁失败时显示 D0/deny，不创建工单或显示已发送；接口不可用时只把 `TransferOrder.deliveryStatus` 置为 pending/retrying/failed，工单业务状态不伪造改变，输入不丢失且不得宣告对方已接收；工单与原线索互不自动关闭。

### UJ-3 李主任督办本学院超期事项

1. 李主任进入学院成长总览，确认周期、更新时间与指标口径。
2. 他查看待核实、超期、闭环率和噪声率，按责任人和场景筛选。
3. 他从可下钻的 `metric-card` 进入授权任务清单，定位超期和无责任人事项。
4. 在 `governance-dialog` 中填写督办原因、要求、责任人和截止时间并确认影响范围。
5. **Climax：** 督办生成真实可跟踪事项，完成状态回到总览并进入周报/运营复盘，而非停留在一次通知。

失败路径：越出学院授权范围时显示无权限且不泄露明细；无有效接收人的事项进入学院异常队列，不能静默丢失。

### UJ-4 林老师完成跨部门转介回填

1. 林老师从公共待办深链进入唯一转介工单。
2. 她仅看到完成任务所需的摘要、原因、已完成动作、dueAt 和 TransferSlaPolicyVersion。
3. 她接收工单并记录处理过程；需要信息时转为“待补充”并指定下一责任人。若工单退回，仍具权发起方补齐最小字段后以同一 transferId 重提，状态只允许 `退回→待接收`，等待目标方重新接收；待补充、退回、重提或换责任人均不重置 dueAt，只有 policy 允许且有审计的 pause interval 才暂停。
4. 信息完整后，她回填处理结果和后续建议，并注明是否需要辅导员继续行动。
5. **Climax：** 工单回填后进入“已回填”；目标部门当前工单责任人按流程将其关闭，不依赖原线索。原 Clue 当前责任人确认回填后才产生 `transfer-result-confirmed`，再独立决定继续处理、观察或已跟进；投递/工单状态不自动完成动作或关闭原线索。操作者、时间、结果和下一责任人均可追溯。

失败路径：临时授权过期或工单已被他人处理时，页面刷新到服务端最新状态，保留未提交草稿但禁止重复回填；接口回调保持幂等。

### UJ-5 陈工处置数据质量异常并恢复规则

1. 陈工从质量异常待办进入 `data-quality-panel`。
2. 他查看连续性指标、异常时间窗、受影响规则、自动熔断记录与 QualityRecoveryPolicyVersion。
3. 修复/补数后，他运行完整性校验与样本重算，界面逐项显示质量阈值、consecutivePassedBatches、observationDuration 和样本预期。
4. 条件通过后，他在 `governance-dialog` 提交恢复理由、latestActionableAt 影响预览和 D4 证据，由授权人员确认。
5. **Climax：** 规则进入 recovering；达到 policy 的连续批次和观察时长后才回 eligible/production。仅 `recoveryCompletedAt≤latestActionableAt` 的窗口恢复计算，既有线索历史不被改写。

失败路径：校验、样本重算、连续批次、观察时长或 D4 任一失败即回 fused，不生成新 RuleEvaluation；面板定位失败批次并允许重试，不得通过降低门槛静默恢复。

### UJ-6 赵处长查看全校关怀态势并调配资源

1. 赵处长进入领导驾驶舱，默认仅见全校汇总。
2. 他确认新增线索数、重点关怀事项数、按时跟进率、噪声率、学院对比指标和 7 日/30 日趋势，并核对 metricId、MetricPublicationPolicyVersion、统计周期、更新时间及 suppressed 说明。
3. 他用 `chart-with-table` 比较业务量、质量与闭环指标，识别某学院超期偏高。
4. 他在 `governance-action` 中记录依据 metricId/口径版本、责任方、资源/措施、截止、基线、目标和复查周期，不从驾驶舱临时下钻个体；提交前执行 `leader-action.record` 门禁。
5. **Climax：** 仅在矩阵 approved/effective 且门禁通过后，管理动作以 planned→active→completed/cancelled 进入运营记录并生成唯一复查任务；复查结果使用 improved/not-improved/data-insufficient/metric-changed，口径变化不得直接比较。

失败路径：动作未映射、运行测试未通过或执行时门禁失败时显示 matrixVersion 与 D0/deny，不持久化 GovernanceAction 或生成复查任务；汇总数据陈旧、suppressed 或依赖不可用时，页面标明数据时间、MPP-1.0.0 与受影响指标，禁用基于失真数据的导出/行动；无汇总权限时进入无权限状态。

## Open Questions

[高风险动作矩阵](../../high-risk-action-matrix.md) HRAP-1.0.0 与 [决策关闭登记](../../open-decisions.md) DEC-001—DEC-018 已由 `AUTH-2026-07-17-001` 批准；G-01—G-09 均为 approved-for-implementation。统一术语为 CTV-1.0.0 的“I/II/III 级核实优先级 + 绝对截止时间”，不得使用诊断或风险定性措辞。真机、视觉、无障碍、契约和性能结果由 Story/DoD 生成，未通过时拒绝完成或发布，不把委托批准当成运行证据。
