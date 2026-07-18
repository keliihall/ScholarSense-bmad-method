---
title: Sprint 变更提案——实施就绪整改与委托闭环（v4）
status: approved
revision: 4
implementation_status: all-readiness-blockers-closed
implementationReadiness: ready
externalGateStatus: approved-for-implementation
runtimeEvidenceStatus: pending-story-execution
date: 2026-07-17
project: ScholarSense-bmad-method
trigger: implementation-readiness-report-2026-07-17-2.md
review_mode: batch
scope: moderate
previous_approval: 2026-07-17-v1
authorityRecordId: AUTH-2026-07-17-001
---

# Sprint 变更提案：实施就绪整改

> 版本说明：§1—§6 保留 v2 在阻塞尚未关闭时的诊断、影响和原始整改方案，§7—§10 保留 v3 首次委托闭环结果，均作为审计历史；其中“pending/blocked/需外部 owner”、v2.0.0 与 319 AC 等旧当前态均由 v4 的 §11—§12 和 [委托决策与实现准入基线](delegated-decision-baseline-2026-07-17.md) 1.1.0 覆盖。历史原文不得被解释为当前状态。

## 1. 问题摘要

### 1.1 触发事件

2026-07-17 的最新实施就绪性评估仍将项目判定为 **NOT READY**。现有 58 条 FR 均被 Story 引用，但名义覆盖不等于语义完整、无冲突或可测试；报告确认了 **12 个实施阻塞问题簇**。对照后发现，v1 提案只完整覆盖其中 1 项、部分覆盖 7 项、未覆盖 4 项，因此旧审批不能自动扩展到本次新增内容。

本次纠偏由 `implementation-readiness-report-2026-07-17-2.md` 触发，不对应单一开发 Story。影响覆盖 8 个 Epic，重点包括 1.1、1.6、1.8、1.9、2.5—2.8、3.1、3.3—3.10、3.14、4.1、4.4—4.5、5.2—5.3、6.3、7.1—7.2、8.1—8.3，以及 PRD、Architecture、UX、治理附件和端到端追踪矩阵。

### 1.2 核心问题分类

1. 权威基线自相矛盾，且规范性附件缺批准人与生效证据。
2. PRD 缺正式 BR/NFR ID，Epics 的 NFR1—NFR25 多处语义错配。
3. MVP 的 FR20 依赖课表排除，但课表相关数据被放在 P1；平台回写、UJ6 治理行动和授权代办等需求未被独立追踪。
4. Candidate 生成时点、线索状态、转介业务状态与投递状态、关闭权限和熔断后既有线索可见性不一致。
5. UX 允许离线读取缓存摘要，而 Architecture 要求每次读取重算授权并在返回前审计，缺少安全合同。
6. UX 的用户感知 P95 与 Architecture 的网关响应边界不一致，跨端 5 秒也没有统一起止事件。
7. 宿主、SSO、公共待办、数据、UI、措辞和灾备等 DEC/契约仍未关闭。
8. 生产前端版本、供应链、数据保留、UI token 与物理 DR 基线未冻结，Vite 5 原型不得直接生产化。
9. 至少 8 组 Story 存在前向事实或完成度依赖。
10. Story 4.1 的 blocked 拒绝路径可通过，却不能交付 FR21。
11. FR4、FR21、FR40、FR51、FR55 等缺完整端到端验收；一次恢复演练不能证明月度 99.9% 可用性。
12. 47/53 个 Story 只有一条复合 AC，至少 14 个 Story 过大，多个边界词没有稳定判据。

### 1.3 证据

- `implementation-readiness-report-2026-07-17-2.md`：总体结论 NOT READY，确认 12 个实施阻塞问题簇、58/58 名义 FR 覆盖、47/53 单一复合 AC 和 14 个超大 Story。
- `rule-catalog.md`：`ACC-SAFE-001/002` 均为 `review`，阈值及生效日期待定；`ECON-012` 为 `blocked`。
- `open-decisions.md`：DEC-001/002/011/012/013 等仍为 open；DEC-004 虽标 closed，但关闭记录不含批准人、日期和证据链接。
- `high-risk-action-matrix.md`：标记 `approved-baseline`，但只有角色型 owners，无实际批准证据。
- `epics.md`：AC-3.6.1 引用尚未由 4.7 产生的合证优先级；AC-3.10.1 引用尚未由 5.3 产生的协同结果；AC-4.1.1 缺少成功链路。

## 2. 影响分析

### 2.1 Epic 与 Story 影响

| Epic | 影响 | 结论 |
|---|---|---|
| Epic 1 | 1.1/1.6 过大；1.8、1.9 只能作为阶段 Enabler；宿主、SSO、公共能力和生产前端基线未关闭 | 保留目标，拆 Story 并建立最终验收 owner |
| Epic 2 | 2.5—2.7 过大；2.8 混淆月度可用性与 DR 演练，并引用未来业务对象 | 拆分运行证据、阶段恢复和 RC 全域恢复 |
| Epic 3 | 状态/候选语义、FR4 生命周期、多个 AC 缺失和前向事实依赖 | 主要整改对象 |
| Epic 4 | 4.1 blocked 冒充 FR21；4.4/4.5 过大，规则与专项依赖未冻结 | 主要整改对象 |
| Epic 5 | 转介投递状态、业务状态、关闭权限与通知生命周期不一致 | 主要整改对象 |
| Epic 6 | UJ6 治理行动缺独立需求；6.3 过大；需承接全域报告和 RC 恢复证据 | 增补需求、拆 Story |
| Epic 7 | 离线安全冲突、FR55 被窄化、7.2 过大，且受 DEC-006/007/009 阻塞 | 重写在线/离线边界和移动命令 AC |
| Epic 8 | 8.1 依赖后置 8.3 的发布关卡；FR51 阈值和证据不完整 | 将关卡骨架前移并重排 Story |

不新增、不删除 Epic，也不改变“模块化单体 + 六边形架构”主干。新增 FR-59—FR-62、BR-1—BR-12 与正式 NFR-1—NFR-34；FR20 所需的最小课表/校历排除事实提升为 MVP P0 合同。MVP 业务目标保持不变，但需求编号、数据依赖和分期基线必须重算。

### 2.2 制品冲突

- **PRD**：除 §0/§14/§15 冲突外，还缺 BR/NFR ID、四项独立功能需求、FR20 的 P0 数据依赖和稳定验收词典。
- **Architecture**：除 companions/规则三状态外，还缺前置评估与 Candidate 边界、投递状态正交、离线安全、用户感知 SLI、生产前端 ADR、保留销毁和完整 DR 证据合同。
- **UX**：除 UJ-2/UJ-4 外，离线缓存、性能起止、候选/正式线索、转介待发送和任务中心入口均需消歧。
- **Epics**：8 组前向依赖、关键 FR E2E 缺口、14 个超大 Story 和大量互斥路径复合 AC 必须重构。
- **治理附件**：需增加权威基线登记、决策关闭矩阵、最小课表合同、生产保留策略和验收词典的 owner/日期/证据；不得伪造外部批准。

### 2.3 技术与交付影响

- 不要求回滚已完成代码；当前证据未显示已有生产实现需要撤销。
- Story 1.1 可继续；受未决契约或规则批准影响的 Story 不得启动或宣称验收通过。
- 需要新增需求命名空间、对象/事件/状态合同、移动安全边界、端到端性能合同和跨生产者验收套件，但不改变架构主干。
- 内部文档与 backlog 修订工作量在需求和拆分方案批准后重新估算；旧的 1—2 个工作日估计撤回。外部治理与契约关闭时间不得由研发伪估。

## 3. 推荐方案

采用 **直接调整 + MVP 数据依赖修正 + 外部治理门禁**：在现有 8 个 Epic 内修订 Story 和权威制品，将 FR20 所需的最小课表/校历排除事实提升为 P0-MVP 合同，同时把无法由研发自行决定的规则、SSO、数据源、UI、保留与灾备事项保持为硬阻塞。

不推荐回滚：当前没有需撤销的生产实现，问题主要位于规划与验收契约。也不缩减住宿安全 MVP 目标，但必须修正其数据依赖；课表最小排除合同、住宿规则或关键外部 owner 无法按门禁提供证据时，对应 Story 保持 blocked，不得静默省略排除条件。

| 维度 | 评估 |
|---|---|
| 变更范围 | 中等：制品一致性 + backlog 重组 |
| 内部工作量 | 中至高：待 v2 需求与拆分方案批准后估算；不得沿用 1—2 日旧估计 |
| 外部依赖 | 高：门户、身份、数据、基础设施、业务与安全 owner |
| 技术风险 | 中：主干不变，但安全、状态、性能、保留和生产基线必须新增可执行合同 |
| 进度风险 | 高：住宿规则及关键决策未关闭前，MVP 生产链路不可验收 |

## 4. 详细变更提案

### 4.1 PRD

#### PRD §0 文档目的

**原文：**

> 审批、数据提供及合规事项均视为已完成并按学校既定方案执行，不构成开放问题或建设阻塞。

**新文：**

> 产品立项、基本业务原则与原则性合规方向已确认；具体门户、身份、数据、规则、安全与灾备实施契约以 `open-decisions.md` 为关闭依据。到达 Story 或发布阻塞点仍未具备批准人、批准日期和证据链接时，相关实施或发布必须停止。

#### PRD §14 已决策前提

**原文：**

> 所有审批、数据提供与合规事项已经解决，系统按学校既定方案执行。

**新文：**

> 产品边界和原则性合规要求已决；具体实施审批、数据契约和发布证据按未决事项决策表及治理附件逐项关闭，未关闭事项不得被推断为已批准。

#### PRD UJ-4 结束状态

**原文：**

> 结果同步给发起辅导员，原线索状态自动更新。

**新文：**

> 协同结果同步到原线索时间线并通知发起辅导员；工单回填/关闭不自动关闭原线索，原线索仅由具权辅导员根据后续行动合法迁移为观察、已跟进或关闭。

### 4.2 Architecture

#### Frontmatter companions

**原文：** `companions: []`

**新文：**

```yaml
companions:
  - ../../rule-catalog.md
  - ../../high-risk-action-matrix.md
  - ../../open-decisions.md
```

#### AD-6 三类正交状态

在 AD-6 增加以下规范，不删除现有不可变版本、去重与解释规则：

- 治理审批状态：`draft → review → approved → retired`，任一非历史版本可进入 `blocked`；由 `rule-governance` 管理。
- 运行状态：`inactive → canary → production → fused/disabled → rolled-back`；只有治理状态 approved 且有生效日期的版本才能进入 canary/production。
- 质量可用状态：`eligible | fused | recovering`；由 `ingestion-quality` 发布，不能反向改写治理审批状态。
- `production + fused` 是合法组合，表示已批准生产版本因质量问题暂停计算；不得把 `review/approved` 当运行态，也不得把 `fused` 当线索业务状态。

### 4.3 Epics 与 Stories

#### Story 2.4：限制登记边界

**原 AC 关键句：**“登记规则版本依赖”。

**补充：**只能引用规则目录中已存在的 `ruleId/version` 作为注册依据；不得创建规则定义、规则运行实体或推断未来版本。未知版本必须拒绝并记录可诊断错误。

#### Story 3.1：可执行的成功与拒绝路径

保留当前拒绝条件，并拆为两项 AC：

- 通用生命周期：用一个具备 approved、生效日期、匿名样本包和依赖质量证据的目录版本验收 canary/production/rollback 成功路径。
- 治理拒绝：review/blocked、样本不足、依赖不合格、审批过期或矩阵版本变化时必须拒绝且不改变运行状态。

若没有任何 approved 规则，则 Story 3.1 只能完成生命周期基础设施与拒绝路径，不得标记完整完成。

#### Story 3.6：移除对 4.7 的前向事实依赖

**原文：**“分数/等级/身份调整/合证优先级分栏”。

**新文：**“分数、关怀等级、身份排序调整及当前已存在的解释字段分栏；Epic 3 不要求创建或伪造合证字段。真实合证优先级及参与事实由 Story 4.7 增量验收。”

同时在 Story 4.7 增加：列表、详情和证据链均展示真实合证优先级与参与事实，且撤销/噪声/失效后同步重算。

#### Story 3.10：移除对 5.3 的前向事实依赖

**原文：**“区分机器信号、人工核实、协同结果和关怀行动”。

**新文：**“Epic 3 验收机器信号、人工核实和关怀行动；不存在的协同结果不得以空业务对象或样例值交付。”

在 Story 5.3 增加：将真实 TransferEvent 投影到原线索时间线，明确类型、来源、时间与授权范围。

#### Story 3.9 / Story 5.2：补齐 FR4 生命周期

在 Story 3.9 增加候选/正式线索待办端到端 AC；在 Story 5.2 增加转介待办端到端 AC：

- 临期与超期按唯一业务键更新原待办，并各自只发送一次相应提醒。
- 等级、截止、责任人或状态变化更新原待办，不创建重复待办。
- 完成、拒绝、合并、撤权或终态关闭时同步关闭/撤销待办。
- 外部消息失败持久重试，不阻塞平台内状态，不重复通知；恢复后完成对账。

更新 FR→Story 追踪矩阵，将 FR4 最终验收补充为 3.9 与 5.2。

#### Story 4.1：拆分治理阻塞与生产经济候选闭环

将现 Story 4.1 拆为两个可独立追踪的 Story，后续 Story 顺延或使用 4.1a/4.1b，具体编号由 PO 维护：

**Story 4.1a：关闭 ECON-012 规则决策与发布前置**

- 验收量纲、公式、28/56 日窗口、持续期、下降幅度、排除顺序、最低样本、owner、批准人、批准日期、生效日期和证据链接。
- blocked 状态申请 canary/production 必须拒绝。

**Story 4.1b：生成并核实经济压力变化候选**

- Given：ECON-012 为 approved/production，依赖批次 sealed/quality-passed，质量 eligible。
- When：计算本人基线、有效餐次、请假/离校排除、去重和候选生成。
- Then：保存 ruleId/version、窗口、当前值、历史区间、样本量、变化、排除、质量快照和时钟；不得使用污名化标签。
- 补充样本不足/排除命中/质量熔断不产出、重放不重复、边界等值、依赖失败等可测试路径。

4.1b 在 4.1a 完成前保持 blocked backlog，不得用拒绝路径宣称 FR21 完成。

#### Story 4.4：解除无条件前置耦合

**原文：**“经济、夜间、学业等源规则已交付”。

**新文：**“至少一个被专项声明为必需且已 approved/production 的合格源规则可用；专项配置必须明确 required/optional 源。缺少 required 源时拒绝发布；缺少 optional 源时显示缺失维度，不伪装为完整覆盖。”

#### Story 5.2 / 5.3：统一关闭权限与顺序

- 协同人员可完成处理并提交结果，使工单进入“已回填”。
- 工单是否进入“已关闭”由目标部门工单责任人按部门流程确认，不依赖原线索关闭。
- 发起辅导员确认回填后决定原线索继续行动、观察、已跟进或关闭；原线索关闭也不反向自动关闭未完成工单。
- 两个聚合互不自动关闭，只通过事件同步结果和提示下一责任人。

#### Story 3.13 与其他 AC 质量

- 3.13 明确 schema 可预留 nullable 字段，但不存在的协同事实必须保持“不可用/未交付”，不得解释为 0；真实指标仅由 6.4 验收。
- 删除 AC-3.7.1 重复行（若当前版本已无重复，则记为 N/A）。
- 对高风险或状态迁移 Story 补成功、拒绝、并发/重放、依赖降级四类业务断言；通用 DoD 不能替代具体预期结果。

### 4.4 UX

#### UJ-2 blocked 状态

在进入经济旅程前增加：当 ECON-012 非 approved/production 时，不展示可处理的经济候选或模拟详情；入口显示“规则尚未批准，当前不可用于生产研判”，仅具权治理人员可查看准备状态和缺失证据。

#### UJ-4 关闭语义

**原文：**“发起辅导员完成最终闭环后，工单进入‘已关闭’。”

**新文：**“协同人员提交结果后工单进入‘已回填’，目标部门责任人可按工单流程关闭；发起辅导员独立确认结果并迁移原线索。工单与原线索互不自动关闭。”

#### DESIGN 视觉假设

将校徽安全区等普通 `[ASSUMPTION]` 统一标注为 `[PRODUCT BASELINE v1.0 / DEC-008]`，并纳入正式视觉验收清单。

### 4.5 治理附件与外部关闭动作

#### 高风险动作矩阵 / DEC-004

在获得真实双 owner 批准前：

- `high-risk-action-matrix.md` 状态从 `approved-baseline` 调整为 `proposed-baseline` 或 `pending-approval`。
- DEC-004 从 `closed` 调整为 `pending-evidence`。

获得批准后必须记录：批准人姓名/标识、代表角色、批准日期、矩阵版本和证据链接。不得由本次对话代替校方业务与安全 owner 批准。

#### 住宿安全规则

`ACC-SAFE-001/002` 只有在最终阈值、量纲、排除顺序、样本包、owner、复核人、生效日期及证据链接齐备后，才可由 review 进入 approved；再通过 `rule.publish` D4 才能进入 production。Story 3.4 在此之前保持 blocked。

#### 决策表

DEC-001、002、011、012、013 至少补充具名 owner 与日历日期。DEC-012 按数据源拆分责任记录，避免“各数据 owner”无法执行。关闭任何决策必须填写版本、批准人、批准日期和证据链接。

### 4.6 v2 增量：PRD、需求 ID 与追踪合同

本节增量高于 v1 中“FR1–FR58 与分期保持不变”的旧结论；其余不冲突条款继续有效。

#### 正式需求命名空间

**OLD：** §6 只有 FR-1—FR-58；§2、§5.1、§8、§11.3、§15 的绑定业务规则没有 BR ID；§9 的 NFR 为无编号项目符号，而 Epics 自行引用 NFR1—NFR25。

**NEW：**

- 保留 FR-1—FR-58 并新增 FR-59—FR-62；不得重用旧编号或按文档顺序推断含义。
- 将跨 FR 的业务不变量编号为 BR-1—BR-12，至少覆盖“线索而非结论、证据先于队列、个人基线、闭环、质量决定产出、增强渐进、减负、最小必要、禁止惩戒、默认拒绝、权威版本绑定、治理行动可验证”。
- 将 §9 现有 33 条 NFR 按原语义分配 NFR-1—NFR-33，并新增 NFR-34“移动端不得持久化离线业务数据”；每条具有唯一标题、测量方式和证据 owner。
- 在 PRD 增加“旧引用→正式 ID”迁移表；Epics 删除语义错误的旧 NFR 引用，全部改为正式 ID。
- 在 `epics.md` 建立唯一 PRD→UX→Architecture→Epic→Story→AC 追踪矩阵，覆盖状态标记为 `full | phase | enabler | blocked`；`phase/enabler/blocked` 不计为完整交付。

#### 新增功能需求

**FR-59 数智学工任务与关怀结果回写**

平台将候选、正式线索、督办和转介的共享任务状态，以及经字段最小化的关怀/协同结果摘要回写数智学工大系统，保存双方 ID、对象版本、幂等键和最后对账水位。

- 同一对象更新原任务，不创建重复任务；完成、拒绝、合并、撤权或终态关闭同步关闭/撤销外部任务。
- 每次回写包含本地聚合 ID、aggregateVersion、eventId、状态、发生时间、结果类别、traceId 和契约版本；自由文本、证据正文和敏感字段不得默认回写。
- 外部失败进入可见的持久重试，不回滚本地事实、不宣告成功；乱序或旧版本回调不得覆盖新状态。

**FR-60 运营指标发布与回写**

平台按授权和最小必要原则，向数智学工运营域发布版本化汇总运营指标，不回写个体明细。

- 指标携带 metricId、口径版本、统计周期、数据截止时间、质量状态与来源水位；低于匿名阈值或质量不合格的切片不得发布。
- 数据缺失、不可用和真实零值必须区分；重复发送幂等，失败可重试和对账。

**FR-61 资源调配与改进行动闭环**

具权管理人员可从驾驶舱或治理报表记录资源调配、改进要求、责任方、截止时间、基线周期和复查周期，并验证后续变化。

- 行动不得自动惩戒学院或个人；必须展示数量、质量、时效和上下文，不能只按线索量排名。
- 到期形成唯一复查任务；改善、未改善和数据不足使用不同结果并进入运营记录。

**FR-62 授权代办**

平台在不改变权威责任人的前提下，为指定对象、动作和期限授予代办；永久责任转移仍由 FR-34 管理。

- 代办记录授权人、代办人、范围、原因、生效/失效时间和撤销；所有操作同时归因实际操作者与权威责任人。
- 代办不重置截止时间、不扩大字段范围；过期、撤销、登出或失去对象权限后立即失效。

FR-28 保留“解释/建议反馈采集”，但必须新增明确 Story 处理反馈进入规则运营分析；FR-55 保留完整移动核实、结果记录、观察和协同处理语义，不得只实现进度查看。

#### MVP 数据依赖裁决

**OLD：** FR-20 要求结合课表排除；FR-9 将学业类数据整体置于 P1，而 MVP 只接入 P0。

**NEW：** 将“有效校历 + 学生有效课程时段/教学活动窗口”作为 FR-20 的 **P0-MVP 最小排除合同**，不等同于把成绩、论文等完整学业域提前到 MVP。DEC-012 为该最小切片单列 owner、SLO、字段、补数与对账；合同未关闭时，依赖课表排除的住宿规则保持 blocked，不得省略排除后继续产出。

#### Candidate 与稳定验收词典

**OLD：** FR-25 表述为先生成 Candidate 再执行质量、排除、白名单与去重检查；失败结果也被称为候选，与 Architecture 的“失败不得创建 Candidate”冲突。

**NEW：** `signal-evaluation` 只产生通过数据质量门的 `RuleEvaluation`；`clue-care` 再持久化 `CandidateAdmissionDecision`。排除、白名单或去重失败只产生版本化拒绝决定和审计事实，不创建 Candidate、不发 `candidate.generated`。只有 admitted 决定才在同一事务创建 Candidate、启动时钟并写唯一公共待办 outbox。

PRD §5 增加版本化验收词典，定义 Top-k/K 来源、容量计划、稳定排序与 tie-breaker、工作日校历、FR-56 去重/配对上限、FR-58 七日含边界、大导出阈值、保留期、质量恢复观察和规则发布阈值。生产值未知时必须登记 owner、deadline 和阻塞级别；测试可使用显式 fixture，禁止隐藏默认值。

### 4.7 v2 增量：Architecture

#### 对象、事件与正交状态

**OLD：** AD-5/AD-19 对 RuleEvaluation→Candidate 的前置门禁边界不完全一致；`待发送` 在 UX/FR-42 中容易被理解为 TransferOrder 业务状态；属实结果和关闭顺序存在跳转歧义。

**NEW：**

- AD-2/AD-5/AD-19 明确 `RuleEvaluation` 归 `signal-evaluation`，`CandidateAdmissionDecision` 和 Candidate 归 `clue-care`；只有 admitted 决定可创建 Candidate，拒绝决定不得伪装为 Candidate。
- TransferOrder 业务状态固定为待接收、处理中、待补充、已回填、已关闭、退回；外部投递另有 `deliveryStatus=pending|retrying|confirmed|failed`，不得混入业务状态机。
- `clue.verified=属实` 必须进入处理中；只有有效关怀动作完成后才可进入已跟进/待观察。工单与原线索互不自动关闭。
- 质量熔断仅禁止新评估/候选；既有 Candidate/Clue 保持授权可见并显示质量快照、受影响范围与可执行动作，不能被静默隐藏或改写业务状态。
- 为非法迁移、过期版本、幂等键冲突、并发冲突、依赖失败和授权拒绝建立稳定错误码与事件表。

#### 移动离线安全裁决

**OLD：** UX 允许离线读取已缓存对象/证据摘要；AD-8/AD-10/AD-24 又要求按当前授权读取并在返回前持久审计，但没有设备安全合同。

**NEW：** 首期 **禁止持久化离线业务数据**。Service Worker、Cache Storage、IndexedDB、localStorage 和持久化 Pinia 不得保存待办、对象、证据、学生标识或授权投影。网络中断时只显示非敏感壳层和断网状态；当前页面的表单可暂存在内存，但身份变化、登出、WebView 销毁或刷新时清除，恢复联网后由用户显式重试。所有业务读取和命令均须在线完成当前授权与审计。若未来需要真正离线能力，必须另立安全变更，覆盖设备加密、TTL、账号隔离、撤权清理、离线审计补记和远程擦除。

#### 用户感知性能与跨端同步 SLI

**OLD：** UX 验收“呈现可操作内容/完成状态”，AD-22 主要测量网关请求到响应；跨端 5 秒缺少起止事件。

**NEW：**

- 2 秒/3 秒产品 SLI 从用户导航或筛选动作时间开始，到必需数据、授权投影、关键控件和可访问名称完成并发出 `ui.content-ready` 为止；骨架屏不算完成。
- 网关、查询、序列化、网络、解析和渲染分别记录诊断分段，但端到端用户感知 SLI 是发布判据。
- 跨端 5 秒从服务端事务提交的 `committedAt` 开始，到另一在线客户端观察到同一或更高 aggregateVersion 并发出 `ui.state-observed` 为止；使用服务端同步时钟，不用两个设备本地钟直接相减。
- 证据包必须固定浏览器/WebView、参考设备、网络剖面、数据规模、缓存冷热、采样窗口和失败样本处理；Story 1.1 冻结性能测试 profile，相关 Story 引用同一版本。

#### 生产运行基线与保留/DR

**OLD：** 冷启动 seed 只固定后端候选版本；Node/Vite/Vue/query-state/ECharts、UI token、非审计数据保留和 DR 物理方案 Deferred，现有 Vite 5 原型被禁止直接生产化。

**NEW：**

- Story 1.1 在任何生产前端 Story ready 前产出并批准 `frontend-production-baseline` ADR：受支持 Node/Vite/Vue/Router/Pinia/Element Plus/query-state/ECharts 组合、锁文件与构建可复现、漏洞门、浏览器/WebView、许可证、视觉和无障碍回归。原型 Vite 5 只作迁移输入。
- Architecture frontmatter `companions` 引用规则目录、高风险动作矩阵、未决事项表和最终追踪矩阵；每项保存版本与生效日期。
- 新增 `RetentionPolicyPort` 与对象分类表；校方策略未关闭前只允许合成/匿名测试数据进入非生产环境，生产业务数据不得自行采用默认保留期。审计仍至少 6 个月。
- DEC-013 关闭证据必须包含生产拓扑、备份/日志链、密钥/对象存储、恢复顺序、故障域和演练计划。Story 2.8 只验当期对象恢复；完整 Candidate/Clue/Transfer/Export 一致性恢复由 RC Story 验收。
- 月度 99.9% availability 由连续 SLI 证据证明，不由单次 DR 演练代替。

Architecture 新增三条编号不变量：AD-26“客户端持久化与离线边界”、AD-27“数据生命周期与删除回执”、AD-28“生产制品与供应链基线”。AD-2 写所有权表新增 `CandidateAdmissionDecision`、`DelegationGrant`、`ExplanationFeedback`、`GovernanceAction`、`QueuePolicyVersion` 和 `RetentionExecution/DeletionReceipt`。

### 4.8 v2 增量：UX

#### 离线、候选和转介状态

**OLD：** `State Patterns` 允许离线读取已缓存摘要；UJ-2 用“待发送”描述工单；UJ-1/状态文本可能让属实结果跳过处理中。

**NEW：**

- 离线时不展示任何持久化业务摘要；显示“无法验证当前权限，联网后重新加载”，表单仅内存暂存且不宣告成功。
- 候选页只展示已通过全部前置门并实际创建的 Candidate；被门禁拒绝的 CandidateAdmissionDecision 仅在具权治理/运维表面展示，不出现在辅导员队列。
- UJ-1 明确“属实→处理中→完成关怀动作→已跟进/待观察”，不得接纳后直接显示闭环。
- UJ-2 将 pending/retrying/failed 显示为投递徽标，与 TransferOrder 业务状态分栏；未 confirmed 时不得显示对方已接收。
- UJ-4 由目标部门工单责任人按流程关闭工单，发起辅导员独立迁移原线索；双方页面明确下一责任人。

#### 性能、任务入口与移动范围

**OLD：** EXPERIENCE 只写 P95 数值，不定义计时事件；异步结果可从“公共待办或任务中心”查看，但任务中心不是明确产品对象；移动 7.2 只实现部分协同能力。

**NEW：**

- EXPERIENCE 引用 Architecture 的 `ui.content-ready`、`committedAt`、`ui.state-observed` 与版本化测试 profile；加载、空态、局部失败和完成态分别定义。
- 删除未定义的通用“任务中心”；异步作业从公共待办深链或所属领域的任务状态页查看，不新建重复的通用任务中心。
- 移动端支持 FR-55 所列快速核实、结果记录、持续观察和授权范围内的协同处理；复杂配置/驾驶舱仍不进入移动端。
- 7.1 将在线加载、网络中断、宿主版本不兼容拆为三个状态；7.2 将核实/观察命令与协同命令拆分，每条均有权限、幂等、并发和网络失败反馈。

### 4.9 v2 增量：Epics、Stories 与验收结构

#### 前向依赖与完整验收 owner

| 原依赖 | v2 处理 |
|---|---|
| 1.8→3.14 | 1.8 只验列表/详情/API 投影并标 `phase`；3.14 验导出生成、下载重检、撤权和更正失效，作为 FR6/7 导出最终 owner |
| 1.9→真实生产者 | 1.9 标 `enabler`，只交付契约/沙箱夹具；3.4、3.9、5.2 与质量生产者分别执行真实 E2E |
| 2.7→未来生产者 | 2.7 交付可复用降级/幂等对账套件；每个后续生产者必须引用并通过，不能由 2.7 提前宣称完成 |
| 2.8→未来业务对象 | 2.8a 建月度可用性 SLI，2.8b 验 Epic 2 当期对象和基础设施恢复；新增 6.5 RC 全域恢复 Story，覆盖 Candidate/Clue/Transfer/Export |
| 3.6→4.7 | 3.6 不显示不存在的合证事实；4.7 成为合证展示最终 owner |
| 3.10→5.3 | 3.10 只显示当前已有事实；5.3 成为真实 TransferEvent 时间线最终 owner |
| 3.13→6.4 | 3.13 明确基础周报为 `phase`；6.4 完整验收 FR46 和协同指标 |
| 8.1→8.3 | 将发布关卡骨架及初始灰度审批前移为新 8.1；灰度建议顺延到 8.2，未通过关卡不能开始 |

#### 14 个超大 Story 拆分台账

| 原 Story | 拆分后的最小交付单元 |
|---|---|
| 1.1 | 1.1a 资产/仓库核验；1.1b 后端与迁移骨架；1.1c 生产前端/性能 profile ADR；1.1d CI、供应链与质量门 |
| 1.6 | 1.6a 身份组织同步；1.6b 责任关系与对账；1.6c 撤权事实和失效传播 |
| 2.5 | 2.5a 熔断判定/影响；2.5b 恢复审批/重算；2.5c 恢复观察与回退 |
| 2.6 | 2.6a trace/指标/告警；2.6b 角色隔离运行面板与隐私负例 |
| 2.7 | 2.7a 降级状态；2.7b 持久重试/幂等；2.7c 对账与生产者契约套件 |
| 3.1 | 3.1a 规则编辑/匿名测试；3.1b canary/production 发布；3.1c 回退/历史不改写 |
| 3.3 | 3.3a 个人基线；3.3b 排除/白名单；3.3c 版本化边界样本 |
| 3.9 | 3.9a 持续观察；3.9b 永久责任转移；3.9c 授权代办；3.9d 关怀闭环与 FR4 待办生命周期 |
| 3.14 | 3.14a 导出申请/审批；3.14b 异步生成/状态；3.14c 下载重检、撤销和更正失效 |
| 4.4 | 4.4a 通用专项生命周期；4.4b 毕业季；4.4c 入学/考试季 required/optional 源矩阵 |
| 4.5 | 4.5a 身份等级；4.5b 业务标签；4.5c 来源冲突、审核与失效 |
| 5.2 | 5.2a 接收/处理；5.2b 待补充/退回；5.2c 回填/关闭；5.2d 通知、失败和对账 |
| 6.3 | 6.3a 规则效果计算；6.3b 分群/版本/噪声分析；6.3c 校准任务与后续周期测量 |
| 7.2 | 7.2a 移动核实/观察；7.2b 移动协同处理；7.2c 跨端幂等/并发/网络恢复 |

每个拆分单元必须声明 `type`、`dependsOn`、`readyWhen`、估算和最终需求覆盖；Enabler 不得独立标记用户 FR 为 full。

#### 关键 FR 的最终可执行 AC

- **FR4/FR59：** 候选、正式线索、质量、督办和转介生产者分别验证新建、临期、超期、一次升级、聚合更新、完成/拒绝/合并/撤权关闭、失败重试、乱序与对账；新增 Story 5.5 作为跨生产者发布级 conformance 最终 owner，MVP 之前只允许标注阶段覆盖。
- **FR21：** 4.1a 只关闭治理门；4.1b 在 ECON-012 approved/production 后验当前值、历史区间、样本量、变化、排除、质量、去重、边界、重放和失败。4.1a 未完成时 4.1b 保持 blocked。
- **FR40/41：** 业务状态、投递状态、通知、非法迁移、回填 schema、下一责任人和两个聚合独立关闭分别验收。
- **FR51：** 新 8.1 保存关卡阈值、比较基线、样本/分群、人工复核、审批和完整发布证据；任一门失败不得形成灰度资格。
- **FR55：** 7.1/7.2 覆盖两类角色、四类轻量命令、在线授权、无持久离线数据、跨端同步、重复/并发和网络失败。
- **FR28/32/34/49/62：** 增加解释反馈处理、受控动作目录、永久转移/代办分离、建议采纳/编辑/拒绝及不得产生高影响动作的独立 AC。
- **可用性/DR：** 2.8a 月度 99.9% SLI、2.8b 阶段 RPO/RTO 演练和 6.5 RC 全域恢复是三套不同证据，互不替代。

所有高风险或状态迁移 Story 至少拆出 happy path、授权拒绝、非法状态、边界、依赖失败、幂等重放和并发冲突；每条 AC 只包含一个可观察结果，通用 DoD 不替代具体断言。

### 4.10 v2 增量：治理附件与关闭证据

- 新增“权威基线登记表”，列出 PRD、UX 双脊柱、Architecture、Epics、规则目录、高风险矩阵、未决事项和追踪矩阵的路径、版本、状态、生效日期、批准人、证据和冲突优先级。
- 扩展决策关闭矩阵至 DEC-003、DEC-006—DEC-013，并为最小课表合同、前端生产基线、性能 profile、非审计保留策略和回写契约新增决策/ADR owner。
- 新增 DEC-014“生产前端与供应链基线”、DEC-015“数据保留与销毁”、DEC-016“Top-k/容量/校历验收词典”、DEC-017“端到端性能测试 Profile”、DEC-018“增强策略发布阈值”；若校方已有同类编号，实施时保留语义并迁移到其正式 ID。
- 所有关闭项必须有具名 owner、日历日期、版本和证据链接；OpenAPI/事件契约类还需 SLO、沙箱与契约测试。无法由项目组决定的值保持 open/blocked，绝不以文档编辑代替校方批准。
- 高风险动作矩阵在缺真实批准人/日期/证据前改为 `pending-approval`；住宿规则在阈值、排除顺序、样本包和生效日期齐备前保持 review/blocked。

新增 Gate 表：G-01 权威附件、G-02 宿主/SSO、G-03 P0/P1 逐源数据与质量恢复、G-04 公共任务/工单、G-05 前端/UI、G-06 规则/队列/校历、G-07 数据保留、G-08 灾备、G-09 智能发布。每个 Gate 记录受影响 Story、owner、deadline、退出证据和状态；未 approved 时相关 Story 只能按其 evidence-ready/blocked 边界执行，不得生产验收。

## 5. 实施交接

### 5.1 范围分类

**Moderate（中等，扩大）**：不改变产品愿景、8 个 Epic 或架构范式，但新增 4 条 FR、12 条 BR、正式 NFR 体系、MVP 最小数据合同，并重构大量 Story/AC。需要 PM/Architect/UX/PO/QA 协同；外部业务、安全、数据、门户、公共能力和基础设施 owner 仍负责关闭其治理门禁。

### 5.2 责任分工

| 接收方 | 责任 |
|---|---|
| Product Manager | 更新 PRD 权威声明、FR-59—FR-62、BR/NFR ID、MVP 依赖与分期 |
| Architect | 更新 companions、AD-2/5/6/7/14/17/19/20/22，并新增 AD-26—AD-28 与 Gate 表 |
| UX | 更新 Candidate/Clue、离线安全、性能 SLI、转介投递、移动命令和解释反馈交互 |
| Product Owner | 重构 Story/AC、owner/contributor/blockedBy 追踪、依赖、估算和 ready 状态 |
| Developer / QA | 将状态表、规则样本、契约、性能 profile 和边界词典转成可执行测试；不得绕过 blocked 门禁 |
| 学工/安全 owner | 批准风险矩阵与住宿规则并提供证据 |
| 门户/身份/公共能力 owner | 关闭 DEC-001/002/011 与回写契约，提供 schema、SLO、沙箱和契约测试 |
| 各数据 owner | 逐源关闭 DEC-012，特别是 P0 最小课表合同，登记 owner/SLO/补数/对账 |
| 技术与基础设施 owner | 关闭前端供应链、性能 profile、数据保留与 DEC-013，提供批准和演练证据 |

### 5.3 建议顺序

1. 批准本 v2 提案并冻结权威基线清单；旧 v1 批准仅保留历史意义。
2. 更新 PRD：权威声明、FR-59—FR-62、BR/NFR ID、MVP 课表合同、Candidate 语义和验收词典。
3. 更新 Architecture：对象/状态、安全/离线、性能 SLI、生产供应链、保留/DR 和 Gate 表。
4. 更新 UX DESIGN/EXPERIENCE：离线、状态、投递、性能、移动写命令、建议与解释反馈。
5. 重构 Epics/Stories：处理 8 组前向依赖、14 个超大 Story、2.8/4.1 拆分、新增 5.5/6.5/6.6，并重写高风险与状态迁移 AC。
6. 同步治理附件和单一追踪矩阵；外部 owner 按证据标准关闭规则与关键决策，研发不得代替批准。
7. 重新运行 implementation-readiness；通过前只启动明确标为 Enabler 且不依赖未决契约的准备工作。

### 5.4 成功标准

1. 权威基线表能唯一定位各制品的版本、状态、生效日期、批准人与冲突优先级；PRD 不再宣称具体实施契约全部已决。
2. FR-1—FR-62、BR-1—BR-12、NFR-1—NFR-34 均有稳定 ID；所有 Story NFR 引用语义正确，单一追踪矩阵无未知 ID。
3. FR20 的最小课表/校历排除事实已成为 P0-MVP 合同；FR-59—FR-62 和 FR28/FR55 的下游落点完整。
4. CandidateAdmissionDecision、Candidate、Clue、TransferOrder、deliveryStatus、工单/线索关闭和熔断可见性在 PRD/UX/Architecture/Stories 中一致。
5. 首期禁止持久化离线业务数据；UX、AD-26、NFR-34 与 7.1/7.2 的正负 AC 一致。
6. 2 秒/3 秒用户感知 SLI、网关诊断分段和跨端 5 秒 SLI 使用相同起止事件、测试 profile 与证据 schema。
7. DEC-003、DEC-006—DEC-013 及新增 Gate 均有受影响 Story、owner、日历日期和退出证据；未关闭项真实标为 blocked。
8. AD-28、前端生产 ADR、UI 基线、RetentionSchedule 和 DRPlan 均有可核验证据；Vite 5 原型不进入生产基线。
9. 8 组前向依赖均已通过阶段覆盖、最终 owner 或顺序调整消除；Enabler 不提前宣称 full。
10. 4.1b 在 ECON-012 approved/production 前保持 blocked，批准后具有完整 FR21 E2E AC。
11. FR4/59、FR21、FR40/41、FR51、FR55 以及 availability/DR 均有独立 happy、拒绝、失败、幂等、并发和边界证据。
12. 14 个超大 Story 已拆分；高风险 Story 不再用单一复合 AC 替代互斥路径，Top-k、容量、校历、配对、七日边界、导出和保留词典均可测试。

只有 12 项全部具备制品链接和可核验证据，且重新执行的 implementation-readiness 结论不再为 NOT READY，才可进入 Sprint Planning。

## 6. 变更分析检查表

| 检查项 | 状态 | 结论 |
|---|---|---|
| 1.1 触发 Story | [x] | 非单 Story；最新 readiness 报告确认 12 个跨制品阻塞簇 |
| 1.2 核心问题 | [x] | 原需求表达/追踪、架构合同、UX 安全和 Story 质量问题 |
| 1.3 支持证据 | [x] | 最新报告、五类正式制品及三份治理附件交叉验证 |
| 2.1 当前 Epic 可完成性 | [x] | 8 个目标可保留，但所有 Epic 均需不同程度修订 |
| 2.2 Epic 级变更 | [x] | 不增删 Epic；新增 FR/BR/NFR、拆 Story、调整 owner 与阻塞 |
| 2.3 后续 Epic 影响 | [x] | 8 组前向依赖及 Epic 4—8 的顺序/最终 owner 已评估 |
| 2.4 新增/失效 Epic | [N/A] | 无 |
| 2.5 顺序与优先级 | [x] | 先 PRD/追踪，再架构/UX，再 Story，最后外部证据与复检 |
| 3.1 PRD 冲突 | [x] | 权威声明、ID、MVP 数据、缺失需求、Candidate 与验收词典 |
| 3.2 Architecture 冲突 | [x] | 对象/状态、离线、安全、性能、供应链、保留和 DR |
| 3.3 UX 冲突 | [x] | 离线缓存、候选/转介状态、性能、移动写命令和范围漂移 |
| 3.4 其他制品 | [x] | 规则、风险矩阵、决策、追踪、契约、性能和恢复证据 |
| 4.1 直接调整 | [x] 可行 | 推荐 |
| 4.2 回滚 | [x] 不可取 | 无证据表明回滚有收益 |
| 4.3 MVP 复审 | [x] 部分适用 | 目标保留；FR20 最小课表/校历事实提升为 P0-MVP 合同 |
| 4.4 推荐路径 | [x] | 直接调整 + MVP 数据依赖修正 + 外部治理门禁 |
| 5.1–5.5 提案组件 | [x] | 本文件已覆盖 |
| 6.1 检查完整性 | [x] | 12 项均已映射到具体制品、Story/AC 或外部证据门 |
| 6.2 提案准确性 | [x] | v2 已与最新 readiness 报告和当前正式制品对齐 |
| 6.3 用户批准 | [x] | Hei 已于 2026-07-17 明确回复 yes，批准完整 v2 提案 |
| 6.4 sprint-status 更新 | [N/A] | 当前没有 sprint-status.yaml，且不增删 Epic；批准后更新 Story backlog 状态 |
| 6.5 下一步与交接 | [x] | PM→Architect→UX→PO/QA 顺序及成功标准已定义 |

## 7. v3 授权与审批记录

- 当前状态：**v3 已批准，全部实现就绪阻塞项关闭**。
- v1/v2 批准：Hei 于 2026-07-17 先后批准原始纠偏与扩充提案。
- v3 触发：Hei 明确授权“所有未关闭项按照行业通用做法闭环；无法闭环的由执行代理自行决定、记录、确认；所有阻塞项必须全部关闭”。
- 授权记录：`AUTH-2026-07-17-001`；批准人/最终 accountable owner=`Hei`；批准、生效日期=`2026-07-17`。
- 决策范围：DEC-001—DEC-018、G-01—G-09、六条 RuleVersion、13 个高风险 actionType、17 个 source contract、11 个 dependency、生产供应链、保留、DR、性能/可用性、UX 与智能发布阈值。
- 证据边界：批准静态参数与实现准入，不宣称尚未执行的沙箱、契约、压测、自然月 SLI、灾备、删除、无障碍或 canary 已通过。

## 8. v3 Correct Course 执行日志

| 项目 | 记录 |
|---|---|
| 触发源 | v2 readiness 报告 + 用户全权委托闭环指令 |
| 评审模式 | Batch；用户已预先授权全部必要裁决 |
| 范围分类 | Moderate；不改变愿景、8 个 Epic 或架构范式 |
| 新增权威制品 | `delegated-decision-baseline-2026-07-17.md` 1.0.0 |
| 决策状态 | DEC-001—DEC-018 全部 `closed` |
| Gate 状态 | G-01—G-09 全部 `approved-for-implementation` |
| Story 状态 | 外部 DoR 阻塞解除，原 `blocked` Story 转为 `planned`；依赖、发布责任与 DoD 已复检并同步 |
| 运行证据 | `pending-story-execution`；按唯一 Story owner 真实产生，失败即禁止 Story 完成/RC 提升 |
| 更新顺序 | Correct Course/治理附件 → PRD → Architecture → UX → Epics/Stories → traceability → readiness 复检 |
| on_complete customization | 空；无附加动作 |

## 9. v3 裁决原则

1. 认证授权采用 OIDC Code+PKCE/BFF、服务端每请求重算、ABAC-on-RBAC、deny-overrides、未映射默认拒绝。
2. 数据与规则采用个人基线、含边界阈值、required all-of、质量熔断、不可变版本、人工核实，不产生诊断/处罚结论。
3. 高风险操作采用 D1—D4；D4 运行时必须不同自然人 maker/checker，基线批准不能替代职责分离。
4. 集成采用 OpenAPI/版本化事件、outbox、幂等、签名、乱序保护和水位对账，投递状态与业务状态正交。
5. 供应链精确锁版本、可复现构建、SBOM、provenance、签名、漏洞/许可证门禁；Vite 5 原型仅作迁移输入。
6. 运行证据与实现准入分离：未来测试不是外部 DoR 批准，但仍是不可绕过的 Story/Release DoD。

## 10. v3 实施结果与交接

- 12 个 readiness 阻塞项全部 `closed`；[需求追踪矩阵](requirements-traceability.md) 记录逐项关闭证据和后续非 DoR 验收。
- [决策关闭登记](open-decisions.md) 中 DEC-001—DEC-018 全部 closed；17 个数据源均有 accountable owner、Responsible 角色、SLO、质量门和证据 URI。
- [高风险动作矩阵](high-risk-action-matrix.md) HRAP-1.0.0 已 approved；未知动作 D0，已列动作只有对应 Story 的运行测试通过后才启用。
- [规则目录](rule-catalog.md) RC-1.0.0 已 approved；六条规则可实现，runtime 仍 inactive/fused，直到依赖、样本、D4 和 canary 通过。
- PRD、Architecture、UX 和 Epics/Stories 统一为 v2.0.0；所有 Story 可进入排期/实现，但不得提前标记 done 或 production-ready。
- [正式 readiness 复检](implementation-readiness-report-2026-07-17-3.md) 已给出 **READY FOR IMPLEMENTATION**：62/62 FR 覆盖，88 Story、319 AC、36 个七路径 Story，依赖未知/前向/循环均为 0，未解决 Critical/Major/Minor 均为 0；未来运行证据仍保持 `pending-story-execution`，不计为已完成。

## 11. v4 独立终检与最终闭环

v3 首次复检后执行了与原作者分离的对抗式终检。第一轮撤回当时的 READY，识别出 3 个 Blocker 和 2 组 Major；修订后的第二轮独立验证又识别出 2 个 Blocker 与 2 个 Major 语义残余。IA-01—IA-09 记录全部修复后的唯一当前状态。此次修订不新增 Epic 或 Story，不改变产品愿景、8 个 Epic、架构范式或运行证据边界，仅把缺失的确定性合同和场景验收补进既有 owner Story。

| ID | 终检发现 | 裁决与受控制品 | Story/AC 关闭证据 | 最终状态 |
|---|---|---|---|---|
| IA-01 | `RFP-1.0.0` 只有策略名称，缺 7 角色对象/动作/范围、字段明文/脱敏/隐藏矩阵及固定判定夹具，FR-3/5/6/7 不可确定验收 | 在委托基线 1.1.0 冻结 R1—R7、对象/动作/范围 allowlist、8 类字段矩阵、deny-overrides、适用角色合并与 `RFP-FIXTURE-1.0.0` | Story 1.7 验证角色首页和对象授权；Story 1.8 逐格验证 C/M/H、越权、撤权、幂等与并发 | closed |
| IA-02 | Story 4.4c 引用不存在的 required/optional 季节矩阵，并把直接数据源错误写成 `production` | 冻结非规则 `SPM-1.0.0`：毕业/考试/入学三场景 required/optional 源、最小合证数、边界、降级与拒绝；直接源仅使用合同/生效、质量 eligible、sealed 与 DCC 水位，`production` 只属于规则运行态 | Story 4.4a/4.4b/4.4c 增加发布、单信号不下结论、必需源失败、可选源降级、隐私授权、边界/重放/更正 AC | closed |
| IA-03 | CareAction 没有版本化动作目录、完成事件和 `dueAt` 规则，FR-29/32/33 的状态守卫无法一致测试 | 冻结 `CAC-1.0.0` 六类动作与唯一 completionKind；统一 planned→in-progress→completed/cancelled；普通动作必须显式 `dueAt`，Observation 使用 `[startsAt, reviewAt)` 且 1—30 天 | Story 3.8、3.9a、3.9d、5.3 增加完成事件、Observation 守卫和“转介回填不等于 CareAction 完成”的断言 | closed |
| IA-04 | FR-22、FR-23、FR-57 的最终 owner Story 只覆盖 happy path，缺授权、非命中边界、依赖失败和更正/重放；学业节点也没有封闭枚举 | 冻结 `ACN-1.0.0` 七类学业关怀节点及 sealed/effective/去重语义；沿用 `SPM-1.0.0` 的合证与单信号边界 | Story 4.2 由 1 条扩为 5 条 AC，4.3 由 1 条扩为 4 条，4.4b 由 1 条扩为 5 条，4.4c 由 1 条扩为 6 条 | closed |
| IA-05 | `AC-4.1b-AUTH-DENIAL` 验的是规则发布权限，而不是经济对象的读取/初审权限，未能证明 FR-21 场景授权 | 将 AC 改为在 ECON-012 已具运行资格时，以责任学生或有效 DelegationGrant 范围验证 RFP 的 `care.read` 与 `candidate.review`；越权不读取敏感明细、不改变 Candidate，并记录拒绝审计 | `AC-4.1b-AUTH-DENIAL` 与 Story 1.7/1.8 的 RFP 固定夹具共同形成对象与字段授权证据 | closed |
| IA-06 | R5 C*/S*/N* 只引用未落盘的 purpose allowlist，R2 CASE-A 督办 scope 与斜杠 actionId 也不确定 | RFP 冻结 7 个星号字段全集、purpose/fieldAllowlist 求交、WORKITEM-A、TRANSFER-A 字段 oracle、斜杠逐项展开及 RFP→HRAP 映射 | Story 1.7/1.8 增加 WORKITEM-A、展开 action 拒绝、R5 四 clear/三 hidden 与全集外字段 fixture | closed |
| IA-07 | ACADEMIC-001 目录只要求 ACADEMIC/TIMETABLE，但 4.3 又把 CALENDAR 当 required，规则资格不可一致判断 | RC-1.0.0 将既有 `DEP-P0-CALENDAR-001` 加入 ACADEMIC-001 required all-of，不增加 11 个 dependency 数量 | AC-4.3-DEPENDENCY-AUTH-FAILURE 与规则目录、PRD/AD/Trace 统一验证三依赖 | closed |
| IA-08 | 毕业专项可引用多个 Clue，却允许“专项上下文”直接创建 Observation，缺 clue owner 与唯一业务键 | CAC 冻结 Observation 必须显式绑定一条可见、未终态参与 clueId；专项仅作 sourceContextRef；定义 observation/task 唯一键与缺省/多选/隐藏拒绝 | Story 3.9a 与 AC-4.4b-OBSERVATION-BOUNDARY 验证单 clueId、边界、幂等、successor 和 fail-closed | closed |
| IA-09 | `academicCalendarProjection` 与 termStartAt 没有 schema、owner、来源、版本或 program-specific 必填，三专项窗口不可复现 | SPM 冻结教务 owner、ACADEMIC+CALENDAR 两源、schema、source/projectionVersion、sealed/effective、term 唯一、program-specific 时间字段与 `NOT_APPLICABLE_FOR_PROGRAM` | Story 4.4a/4.4b/4.4c 验证 Admission/Exam/Graduation 各自必填、时序、两源质量/水位、封账、更正与 required failure | closed |

本轮形成的权威合同均由 `AUTH-2026-07-17-001` 授权，版本为 `RFP-1.0.0` + `RFP-FIXTURE-1.0.0`、`CAC-1.0.0`、`SPM-1.0.0`、`ACN-1.0.0`。它们是确定实现和验收预期的静态基线，不代表测试环境、沙箱、性能、灾备、删除、无障碍或 canary 已运行。

## 12. v4 最终实施结果与交接

- 委托决策基线升至 1.1.0；PRD、Architecture、UX 与 Epics/Stories 升至 2.1.0，规则目录与决策登记同步升至 1.1.0/2.1.0。
- 12 个原始 readiness 阻塞项仍为 12/12 closed；两轮独立终检的 IA-01—IA-09（5 个 Blocker、4 个 Major）也全部 closed，没有外部 owner 静态证据待确认。
- 总量保持 62 FR、12 BR、34 NFR、28 AD、88 Story 和 36 个七路径 Story；细化验收条件由 319 增至 335，所有 AC 均有 Given/When/Then。
- FR owner 覆盖 62/62；Story `dependsOn` 的 unknown/forward/cycle 均为 0；未解决 Critical/Major/Minor 均为 0。
- [最终 readiness 复检](implementation-readiness-report-2026-07-17-3.md) 是当前裁决来源。结论仅为 **READY FOR IMPLEMENTATION**；所有未来运行证据继续为 `pending-story-execution`，缺失或失败时按 Story/Release DoD fail closed，禁止标记 done、提升 RC 或进入生产。
