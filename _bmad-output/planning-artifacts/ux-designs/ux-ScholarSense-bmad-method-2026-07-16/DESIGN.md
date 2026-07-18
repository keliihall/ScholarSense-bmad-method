---
name: 学林知微设计系统
description: 苏州大学数智学工体系下，审慎、可信、以关怀行动为导向的视觉契约。
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
colors:
  brand-web-primary: '#AF251B'
  brand-web-hover: '#C53227'
  brand-web-pressed: '#A7180D'
  brand-mobile-primary: '#D03D37'
  text-strong: '#1A1C1C'
  text-primary: '#333333'
  text-secondary: '#666666'
  text-muted: '#999999'
  text-disabled: '#AAAAAA'
  text-inverse: '#FFFFFF'
  surface-base: '#FFFFFF'
  surface-subtle: '#FBFBFC'
  surface-disabled: '#ECECEC'
  border-control: '#E8ECF0'
  border-divider: '#EAEAEA'
  status-danger: '#FF3B3B'
  status-danger-bg: '#FFD8D8'
  status-success: '#1DAF1B'
  status-success-bg: '#D2EFD1'
  status-warning: '#DE8416'
  status-warning-bg: '#F8E6D0'
  status-info: '#247FFF'
  status-neutral-bg: '#EBEBEB'
  focus-ring: '#247FFF'
typography:
  page-title:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 24px
    fontWeight: '600'
    lineHeight: '1.4'
  section-title:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 18px
    fontWeight: '600'
    lineHeight: '1.5'
  body:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.6'
  body-large:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
  label:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 14px
    fontWeight: '500'
    lineHeight: '1.5'
  caption:
    fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.5'
rounded:
  sm: 2px
  md: 4px
  lg: 8px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 20px
  '6': 24px
  '8': 32px
  mobile-gutter-compact: 10px
  mobile-gutter-comfortable: 24px
components:
  unified-shell:
    background: '{colors.surface-subtle}'
    foreground: '{colors.text-primary}'
    divider: '{colors.border-divider}'
  page-header:
    title-size: '{typography.page-title.fontSize}'
    foreground: '{colors.text-strong}'
    gap: '{spacing.3}'
  metric-card:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  workload-summary:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  task-table:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    border: '{colors.border-divider}'
  filter-bar:
    background: '{colors.surface-base}'
    gap: '{spacing.3}'
  status-tag:
    foreground: '{colors.text-primary}'
    radius: '{rounded.sm}'
  evidence-chain:
    background: '{colors.surface-subtle}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  explanation-feedback:
    background: '{colors.surface-base}'
    border: '{colors.border-control}'
    radius: '{rounded.md}'
  sensitive-field:
    foreground: '{colors.text-primary}'
    masked: '{colors.text-muted}'
  action-form:
    background: '{colors.surface-base}'
    border: '{colors.border-control}'
    radius: '{rounded.md}'
  transfer-work-order:
    background: '{colors.surface-base}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  delivery-status:
    foreground: '{colors.text-secondary}'
    radius: '{rounded.sm}'
  delegation-grant:
    background: '{colors.surface-base}'
    border: '{colors.border-control}'
    radius: '{rounded.md}'
  timeline:
    foreground: '{colors.text-primary}'
    rail: '{colors.border-divider}'
  data-quality-panel:
    background: '{colors.surface-base}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  chart-with-table:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    border: '{colors.border-divider}'
  governance-dialog:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    radius: '{rounded.lg}'
  rule-lifecycle-panel:
    background: '{colors.surface-base}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  suggestion-card:
    background: '{colors.surface-subtle}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
  governance-action:
    background: '{colors.surface-base}'
    border: '{colors.border-control}'
    radius: '{rounded.md}'
  state-panel:
    background: '{colors.surface-subtle}'
    foreground: '{colors.text-secondary}'
    radius: '{rounded.md}'
  primary-button:
    background: '{colors.brand-web-primary}'
    hover: '{colors.brand-web-hover}'
    pressed: '{colors.brand-web-pressed}'
    foreground: '{colors.text-inverse}'
    radius: '{rounded.md}'
  mobile-task-card:
    background: '{colors.surface-base}'
    foreground: '{colors.text-primary}'
    border: '{colors.border-divider}'
    radius: '{rounded.md}'
---

# 学林知微视觉脊柱

> 与 `EXPERIENCE.md` 共同构成下游实现契约。原型和导入物用于说明来源；若发生冲突，以两份脊柱为准（spines-win-on-conflict）。

`AUTH-2026-07-17-001` 已批准 `UXBaselineVersion=UXB-1.0.0`、PAB/CTV/PP/AP-1.0.0 和 G-05 实现准入。本文 token 可直接实现；Web 视觉回归、对比度、键盘/辅助技术和用户感知性能由 1.1c/1.1d、1.2 的 DoD 真实验证。`AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 仅把 1.1c/1.1d 的 App/WebView 报告裁定为 N/A，真实 WebView/真机视觉与性能仍由 7.x 在未来新基线版本下验证；适用门未通过时禁止对应 Story 完成或发布。

## Brand & Style

学林知微位于“学校 → 数智学工大系统 → 学林知微 → 观澜智核”的品牌层级中。视觉应像可信的校园业务工具：克制、清楚、可追溯，以证据和下一步行动为重，不渲染风险，不把学生做成被审视的数据对象。模块复用统一门户品牌壳，不在页面重复堆叠校徽或另造科技品牌。

基础控件继承学校统一门户主题与 Element Plus。次要按钮、危险按钮、文本按钮、导航、面包屑、分页、Tabs、提示、选择器、上传和基础表单若未在本文列出增量规则，均使用宿主系统默认值，不得另行自由设计。本文的颜色、排版、状态、权限与无障碍规则优先于默认值。

校徽以 [苏大校徽源文件](imports/苏大logo.png) 为唯一受控位图依据：等比、完整、无裁切、无重绘、无阴影。`UXB-1.0.0 / DEC-008` 要求素材透明边界之外至少保留校徽直径 1/4 的净空；替换为矢量素材须发布 UXB 新版本并保持相同视觉边界。

## Colors

Web 使用 `{colors.brand-web-primary}`，悬浮与按下分别使用 `{colors.brand-web-hover}`、`{colors.brand-web-pressed}`；统一 App WebView 内使用 `{colors.brand-mobile-primary}`。两套主红不得混成一个 token，原型的蓝绿色不得作为品牌主色。

正文层级采用 `{colors.text-strong}`、`{colors.text-primary}`、`{colors.text-secondary}`、`{colors.text-muted}`；禁用专用 `{colors.text-disabled}`。状态色仅作辅助，必须同时提供文字或图标。危险、成功、提醒、信息分别使用 `{colors.status-danger}`、`{colors.status-success}`、`{colors.status-warning}`、`{colors.status-info}`。正文文字与背景色的组合应达到 WCAG 2.2 AA：普通文字至少 4.5:1，大字至少 3:1；品牌主按钮文字使用 `{colors.text-inverse}` 并在实现验收中实测。

学校 Web 示例中的有底色文字 `#594343/#464359` 由统一门户主题继承，ScholarSense 不直接引用；移动辅助色 `#FC6864/#FEAE4A/#478FE4` 当前不进入产品语义色板，避免在源文件色名错位未确认前制造错误含义。

## Typography

继承学校 Web 字体方向：中文苹方、英文 SF Pro，并以思源黑体和通用 sans-serif 回退。页面标题用 `{typography.page-title.fontSize}`，常规正文用 `{typography.body.fontSize}`；核心任务信息不得降到 12px 以下。数字与中文共用字体栈，避免驾驶舱另造“科技数字字体”。移动正文优先 `{typography.body-large.fontSize}`，紧凑标签仍不得承载唯一关键信息。

## Layout & Spacing

Web 以学校规范 1440×900 为基准，并验收 1366×768；移动以 375px 宽为基准。使用 `{spacing.1}` 至 `{spacing.8}` 的 4px 基础比例，移动左右留白按内容密度使用 `{spacing.mobile-gutter-compact}` 或 `{spacing.mobile-gutter-comfortable}`。

移动端高密度内容使用 `{spacing.mobile-gutter-compact}` 作为左右留白，常规内容使用 `{spacing.mobile-gutter-comfortable}`。`UXB-1.0.0 / DEC-007/008` 采用 0—767 mobile（4 栏）、768—1023 tablet/narrow（8 栏）、≥1024 desktop（12 栏）；移动主操作目标≥44×44 CSS px，控件高度 mobile 44px、desktop 36px。学校 PDF 未给出的值是项目受控产品基线，不冒充原始学校 VI 条文。

## Elevation & Depth

层级优先依靠白色容器、浅灰背景、留白与 `{colors.border-divider}` 细分割线。普通卡片不因不可点击而产生 hover 抬升；对话框仅使用 Element Plus 默认低强度阴影，不新增品牌阴影 token。驾驶舱不使用发光、渐变或“战情室”式高反差效果。

## Shapes

`UXB-1.0.0 / DEC-008` 固定 `{rounded.sm}=2px`、`{rounded.md}=4px`、`{rounded.lg}=8px`；仅徽标、状态点等本质为圆形的元素使用 `{rounded.full}`。不得用大面积胶囊形制造消费应用语气；任何变更通过 UXB 新版本统一替换 token，不在组件内散落半径。

## Components

| 组件 | 视觉契约 |
|---|---|
| `unified-shell` | 统一门户内的模块壳；浅灰底、白色内容区和细分隔，不重复底部导航或校徽。 |
| `page-header` | 页面标题、口径/更新时间和主操作形成稳定首行；标题用 `{typography.page-title.fontSize}`。 |
| `metric-card` | 白底细边框；数字、口径、周期和 MetricPublicationPolicyVersion 并置；零、缺失、不可用、suppressed 使用不同文字语义，suppressed 不显示为 0 且不可下钻。 |
| `workload-summary` | 六项工作台概览使用等权卡组；统计日期、本人范围、QueuePolicyVersion、今日容量值/来源紧邻标题，缺失/未批准不用 0 占位。 |
| `task-table` | 表头、行、选择、排序、焦点、临期/超期均有文字或图标；专项行并列 program/window、SPM-1.0.0、academicCalendarProjection 的 term/source/projectionVersion/sealed 状态及 required/optional/degraded 文本，学业行显示 ACN-1.0.0 nodeCode/batchVersion，不以整行红底或单信号制造恐慌。 |
| `filter-bar` | 与结果口径同容器；控件间距 `{spacing.3}`，已选条件持续可见。 |
| `status-tag` | 短文字 + 图标/形状；背景使用语义浅色，文字保持 AA；关怀等级同时写明“核实优先级”。 |
| `evidence-chain` | 事实、规则判断、建议分层；按 comparisonMode 显示“个人基线/变化”“绝对阈值/比较符”或“节点事实/批次”，非适用字段显示原因，不把空基线伪装为缺数据；来源、时间窗、质量快照有稳定视觉顺序。 |
| `explanation-feedback` | 证据充分度、解释清晰度、建议有用度使用对称的正/负/不适用选择和版本化原因字段，不做评分羞辱；提交状态和失败输入同屏，视觉上不暗示其会改变线索等级。 |
| `sensitive-field` | clear/固定脱敏/hidden 三态绑定 RFP-1.0.0 字段类与版本；R5 星号字段逐项展示 purpose/fieldAllowlist 命中结果，R2 个案显示督办 workItem scope。masked 不暗示原长度，hidden 完全不渲染键、占位或可访问名称。策略/密钥失败使用无数据泄露的拒绝态，不以模糊文字制造可见错觉。 |
| `action-form` | CAC-1.0.0 categoryCode、动作状态、dueAt/来源、completionKind/证据、wasOverdue、唯一 clueId、Observation 半开窗口与复查任务同屏；多 Clue 专项使用无默认值的显式单选控件，隐藏/终态项不出现。字段内错误、错误摘要、必填原因和提交状态可访问，非法后继不渲染为可用主操作。 |
| `transfer-work-order` | 工单卡片和详情以工单号、责任人、dueAt、TSP-1.0.0、pause interval、业务状态轨迹及最小必要摘要为主体；业务状态、投递状态和 CareAction 完成资格分栏。delivery confirmed/已回填/关闭不得使用“关怀已完成”视觉，只有原责任人确认后显示 `transfer-result-confirmed`。deny 时显示 matrixVersion、失败说明和禁用主操作。 |
| `delivery-status` | 显示 recordType/归属；转介只绑定 `TransferOrder.deliveryStatus`，公共任务只绑定 `TaskDelivery.status`。pending/retrying/confirmed/failed 使用短文字、图标和辅助色；confirmed 只写“外部系统已确认投递”，不得与待接收/处理中等业务状态共用标签、挂到来源聚合或暗示业务接收。 |
| `delegation-grant` | 与责任转移使用不同标题和信息组；突出 draft/pending-approval/active/expired/revoked/cancelled、原责任人、对象/动作/字段交集、RoleFieldPolicyVersion、期限、grantId、matrixVersion 与受控撤销原因。策略交集外字段不渲染，结束边界 active→expired；pending/deny、非法态、幂等结果和 409 使用持久状态说明。 |
| `timeline` | 机器信号、人工核实、协同结果用文字类别和图标区分，不只靠颜色。 |
| `data-quality-panel` | 质量指标、sourceId→dependencyId、required/optional、组合算子/失败成员、QualityRecoveryPolicyVersion、consecutivePassedBatches、observationDuration、latestActionableAt、影响范围和 fused/recovering/eligible 同屏；组合依赖未全部 eligible 时不得显示规则 eligible，失败回 fused 使用文字说明，异常色不覆盖数据文本。 |
| `chart-with-table` | 图表与等价表格/数值摘要成对并显示 MetricPublicationPolicyVersion；suppressed 使用文字说明且不进入误导性比较；序列同时用颜色、线型或标记区分。 |
| `governance-dialog` | 高风险动作显示 HRAP-1.0.0/matrixVersion、影响预览、理由、复核/认证门槛与不可逆后果；动作未映射、运行测试未通过或执行时失败时呈现 D0/deny 和失败证据，禁用提交。 |
| `rule-lifecycle-panel` | 治理审批态、运行态、质量态使用三列/三组，不把 review、production、fused 混成单一进度条。 |
| `suggestion-card` | 明确“建议”标签、策略版本与采纳/编辑/拒绝动作；不得使用确定性结论或默认主按钮诱导采纳。 |
| `governance-action` | 指标依据、责任方、措施、截止、基线、planned/active/completed/cancelled、pending/reviewed 与四类复查结果同屏；动作未映射、运行测试未通过或执行时 deny 时显示失败且不生成任务，不使用学院排行榜或个体明细构图。 |
| `state-panel` | 加载、空、错误、无权限、降级均用对应插图/图标、标题、说明和唯一下一步。 |
| `primary-button` | Web 采用三态品牌红；移动环境以 `{colors.brand-mobile-primary}` 覆盖背景，禁用态不使用品牌红。 |
| `mobile-task-card` | 在线摘要优先：对象、事项、时限、证据摘要、角色允许的主动作；单列且 375px 无横向滚动。离线状态只显示无对象网络提示，不显示缓存卡片。 |

组件行为均在 `EXPERIENCE.md` 同名 `Component Patterns` 中定义。学校组件参考见 [Web 端规范](imports/校园服务总入口WEB端设计规范.pdf) 与 [移动端规范](<imports/苏大UI规范说明(1)(1).pdf>)。

关键构图参考：[辅导员工作台](mockups/workbench.html)、[住宿安全线索详情](mockups/clue-detail.html)、[领导驾驶舱](mockups/leader-cockpit.html)、[移动轻量处置](mockups/mobile-task.html)。这些 mockup 只说明构图和视觉层级；与本文或 `EXPERIENCE.md` 冲突时，以双脊柱为准。

## Do's and Don'ts

| Do | Don't |
|---|---|
| 用证据、口径、更新时间建立可信度 | 用大红数字、发光地图或告警音制造紧张 |
| 将关怀等级写成“核实优先级”并附时限 | 把等级、标签或规则分数表现为学生结论 |
| 状态同时使用文字、图标/形状和必要色彩 | 仅用红绿或 I/II/III 传达状态 |
| 继承 Element Plus/ECharts 基础视觉并做品牌与无障碍增量 | 复制原型中散落的蓝绿色、11px 字号和无语义点击块 |
| 保持校徽原始比例与完整环形文字 | 拉伸、裁切、重绘、重复堆叠校徽 |
| 图表提供表格替代并显示周期、口径、更新时间 | 用学院线索数量做单一排名 |

## 决策关闭与视觉验收

移动触控 DEC-006、断点 DEC-007、校徽/净空/控件/栅格/圆角 DEC-008、辅助色 DEC-009、正式措辞 DEC-010 和前端制品 DEC-014 均已由 `AUTH-2026-07-17-001` 关闭，G-05 为 approved-for-implementation。UXB-1.0.0 明确禁用未确认辅助色，状态不得只靠颜色；CTV-1.0.0 固定“I/II/III 级核实优先级 + 绝对截止时间”。视觉/无障碍/真机/性能报告必须由对应 Story/DoD 产生，未通过时不完成或发布，但不存在待外部 owner 确认的设计参数。
