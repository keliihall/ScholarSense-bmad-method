---
title: 学林知微需求与实施追踪矩阵
status: controlled-baseline
version: 2.1.1
updated: 2026-07-19-story-1.1d-applicability-alignment
implementationReadiness: ready
externalGateStatus: approved-for-implementation
runtimeEvidenceStatus: pending-story-execution
idPolicy: immutable
derivedFrom:
  - prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md
  - architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md
  - ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md
  - ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md
  - epics.md
  - open-decisions.md
  - high-risk-action-matrix.md
  - rule-catalog.md
  - delegated-decision-baseline-2026-07-17.md
  - app-applicability-baseline-2026-07-19.md
---

# 需求与实施追踪矩阵

本文是 PRD→UX→Architecture→Epic→Story→AC 的唯一派生追踪表，不创建或改写需求。`full` 表示规格已有最终 owner AC；`phase/enabler` 不计为完整交付。`ready` 表示实现参数与 DoR 证据齐备；后续契约、性能、灾备、删除、视觉/无障碍和 canary 实测由绑定 Story/DoD 生成，未通过时禁止完成 Story 或发布，但不再构成外部实现准入阻塞。G-01—G-09 均已依据 `AUTH-2026-07-17-001` 批准进入实现。

最终可实施 oracle 明确包含 `RFP-FIXTURE-1.0.0`（含 R5 星号字段全集/WORKITEM-A）、`CareActionCatalogVersion=CAC-1.0.0`（含单 clueId Observation）、`SeasonalProgramMatrixVersion=SPM-1.0.0`（含 academicCalendarProjection）与 `AcademicCareNodeSetVersion=ACN-1.0.0`；它们关闭静态定义缺口，运行测试仍由对应 Story DoD 产生。

## 权威基线与冲突优先级

| 制品 | 版本 / 状态 | control effective | 批准/证据 | external evidence | 冲突优先级 |
|---|---|---|---|---|---|
| `sprint-change-proposal-2026-07-17.md` | rev 4 / approved | 2026-07-17 | Hei 的 `yes` 及 AUTH-2026-07-17-001 | 全部纠偏、委托裁决与 IA-01—IA-05 终检闭环 | P0：本次变更范围与理由 |
| `delegated-decision-baseline-2026-07-17.md` | 1.1.0 / approved | 2026-07-17 | Hei / 2026-07-17 | DEC-001—018、G-01—09 与 RFP/CAC/SPM/ACN 静态基线 | P0-A：窄域决策与实现准入 |
| `app-applicability-baseline-2026-07-19.md` | AAB-1.0.0 / approved | 2026-07-19 | Hei / `USER-2026-07-19-SCHOOL-APP-NA` | 仅 Story 1.1c/1.1d：App/WebView `not-applicable`、`runtimeEvidenceClaim=none`；不取消 7.x | P0-A：作用域内后续适用性裁决 |
| `prds/.../prd.md` | 2.1.1 / controlled-baseline | 2026-07-19 | AUTH + AAB-1.0.0 | 产品范围不变；1.1c/d App 运行证据 N/A | P1：产品目标、FR/BR/NFR、分期与业务语义 |
| `architecture/.../ARCHITECTURE-SPINE.md` | 2.1.1 / controlled-baseline | 2026-07-19 | AUTH + AAB-1.0.0 | G-01—G-09 approved；1.1c/d App 适用性已同步 | P2-A：技术不变量和 Gate |
| `ux-designs/.../EXPERIENCE.md` + `DESIGN.md` | 2.1.1 / controlled-baseline | 2026-07-19 | AUTH + AAB-1.0.0 | UXB/PAB/CTV/RFP/CAC/SPM 与 1.1c/d App 适用性已冻结 | P2-B：交互行为与视觉契约 |
| `epics.md` | 2.1.1 / controlled-baseline | 2026-07-19 | AUTH + AAB-1.0.0 | Story planned；1.1d Web 必测、App N/A；DoD fail closed | P3：实施分解、依赖与 AC |
| `rule-catalog.md` | 1.1.0 / approved | 2026-07-17 | Hei / RC/SPM/ACN-1.0.0 | runtime inactive/fused，等待 Story 实测 | 窄域规则/专项矩阵 |
| `high-risk-action-matrix.md` | 1.0.0 / approved | 2026-07-17 | Hei / HRAP-1.0.0 | runtime 逐 actionType 测试 | 窄域高风险策略 |
| `open-decisions.md` | 2.1.1 / closed | 2026-07-19 | Hei / AUTH + AAB-1.0.0 | DEC-001—018 closed；后续作用域裁决已登记 | 决策登记 |
| 本文件 | 2.1.1 / controlled-baseline | 2026-07-19 | 派生对账制品 | 不冒充运行证据 | P4：追踪，不得覆盖上游 |
| `prds/.../addendum.md` | historical / non-normative | superseded | 无 | 与 v1.1 冲突项不再生效 | 历史输入，不参与裁决 |
| `implementation-readiness-report-2026-07-17-2.md` | audit input | 2026-07-17 | readiness 审计 | 非规范性 | 审计证据，不定义产品 |

冲突按以下顺序处理：适用法律或更严格正式校方制度 → 委托决策基线及已 approved/effective 的窄域合同（仅在其职责域）→ PRD → Architecture/UX 各自职责域 → Epics/Stories → 本追踪表。运行测试结果只能决定 Story/Release 是否通过，不能暗改产品参数；参数变化必须发布新版本。

`AAB-1.0.0` 是后续窄域 companion：仅使 1.1c/1.1d 的 App/WebView 基线与运行报告为 `not-applicable + runtimeEvidenceClaim=none`，同时保持 1.1d 的 Web 浏览器/视觉/无障碍正式证据必需。它不把 FR-55、NFR-18/NFR-29/NFR-31/NFR-34 或 Story 7.x 写成通过；NFR-31 与真实 App/WebView/真机证据的最终 owner 仍为 7.1/7.x。

Story 1.5 runtime companion（2026-07-23）：FR-8 的授权搜索/字段投影/归档与 retention conformance 由本 Story 提供；`conformanceVerified=true`。生产权威角色尚待 Story 1.6/1.7，因此 `productionAuthorizationEnabled=false`，RFP fixture 不得激活生产搜索。独立生产 WORM adapter 与真实跨域 `DeletionReceipt` 仍分别等待基础设施绑定和 Story 6.6；当前证据只允许 `scopeType=audit-domain`、`nonProductionEvidence=true`。

Story 1.1d 的规划准入与完成平台分离：当前基线足以开始 U1 本地可复现构建合同；真实 Git/CI、digest-addressed store、attestation/signing、受保护环境、正式 Web runner 与 promotion endpoint 必须由 `CISB-1.0.0` 以实际值另行冻结。CISB 未完成时，U2—U4 与整体 `review/done` 保持不可验收，不得把 planning `ready` 解读为已有运行平台。

## Gate 批准登记

| Gate | accountable / Responsible | DoR 基线 | 运行证据 owner（Story/Release） | 状态 |
|---|---|---|---|---|
| G-01 权威附件/发布基线 | Hei（A）；制品/测试/SRE（R） | AUTH、PAB/PP/AP/RFP 与冲突优先级 | 1.1c、1.1d、2.8a | approved-for-implementation |
| G-02 宿主/SSO/授权 | Hei（A）；门户/身份/权限（R） | HIP/ISP/RFP + RFP-FIXTURE | 1.2、1.6a—c、1.7、1.8、7.1/7.2c | approved-for-implementation |
| G-03 P0/P1 数据/质量恢复 | Hei（A）；逐源 owner（R） | DCC/QG/QRP、17 源、11 dependency | Epic 2、3.2—3.4、6.5 | approved-for-implementation |
| G-04 任务/工单/回写 | Hei（A）；公共能力/协同（R） | PIC/TSP/MPP | 1.9、5.1—5.5、6.4 | approved-for-implementation |
| G-05 前端/UI | Hei（A）；UX/前端/App/品牌（R） | UXB/PAB/PP/AP/CTV | 1.1c/d、1.2、7.x | approved-for-implementation |
| G-06 规则/证据/队列/校历/动作/专项 | Hei（A）；规则/业务/校历（R） | RC/ES/QP/BC/WVP/CAC/SPM/ACN | 3.x、4.x、6.1 | approved-for-implementation |
| G-07 保留/删除 | Hei（A）；数据治理/基础设施（R） | RS/legal hold/receipt schema | 6.6 | approved-for-implementation |
| G-08 灾备 | Hei（A）；技术/基础设施（R） | DRP/拓扑/runbook/RPO/RTO | 2.8b、6.5 | approved-for-implementation |
| G-09 智能发布 | Hei（A）；模型治理/公平复核（R） | SGP/阈值/基线/分群/人工门 | 8.1—8.4 | approved-for-implementation |

## 12 个 readiness 阻塞项关闭状态

| # | 状态 | 关闭证据 | 后续非 DoR 验收 |
|---|---|---|---|
| 1 权威基线矛盾 | closed | AUTH、权威优先级与委托决策基线统一 | 发布清单对账 |
| 2 ID/追踪不可靠 | closed | FR-1—62、BR-1—12、NFR-1—34 与唯一追踪矩阵 | 持续 ID lint |
| 3 MVP/依赖冲突 | closed | P0 课表/校历/设备、P1 源、17 source/11 dependency 与组合算子冻结 | Epic 2 契约测试 |
| 4 对象/状态冲突 | closed | QualityEligibility→RuleEvaluation→AdmissionDecision→Candidate→Clue 与 Transfer/delivery 正交 | Story 状态机测试 |
| 5 移动离线安全 | closed | NFR-34、AD-26、UX/7.x 一致禁止持久业务缓存 | 7.2c 存储扫描 |
| 6 性能证据边界 | closed | PP/AP-1.0.0 与 2s/3s/5s 用户起止事件冻结 | 1.1c、2.8a、3.7、7.2c 实测 |
| 7 外部决策/契约 | closed | DEC-001—018 全部 closed；RFP 七角色/星号字段/fixture、CAC 单 clueId、SPM academicCalendarProjection、ACN 与 G-01—09 approved-for-implementation | 各绑定 Story 契约/沙箱测试 |
| 8 生产运行基线 | closed | PAB/RS/DRP/PP/AP 与 AD-27/28 完整 | 1.1d、6.5、6.6 Release DoD |
| 9 Story 前向依赖 | closed | owner/contributor、phase/enabler、2.8a/b、5.5、6.5、6.6 已重分配 | 调度器校验 dependsOn |
| 10 拒绝路径冒充 FR-21 | closed | ECON-012 RC-1.0.0 approved；4.1a 治理/发布资格、4.1b E2E 分离 | 4.1a/b 样本、quality、publish/canary |
| 11 关键 FR E2E 缺口 | closed | FR-4/21/22/23/24/40/51/55/57/59、DR 均有专属正负/边界/隐私/更正 AC；学业三依赖、专项日历投影与观察 owner 明确 | 对应 Story 测试执行 |
| 12 Story/AC 不可实施 | closed | 88 Story/335 AC、14 个拆分、36 个七路径 Story 均有确定输入；RFP 字段 oracle、CAC clue owner、SPM 日历投影、ACN 与多场景发布责任显式 | Sprint 中逐 Story DoD |

## FR-1—FR-62 端到端追踪

`AC-x-*` 表示该 owner Story 下全部互斥 AC；contributor 可为前置 phase/enabler，也可为后置消费、hardening 或发布 conformance。contributor 列不隐含执行顺序；只有 Epics 中的 `dependsOn`/`readyWhen` 建立依赖，contributor 永不把自身 coverage 标为 full。

| FR | PRD | UX surface | Architecture | owner Story / AC | contributor | approved baseline / DoD evidence | coverage / ready |
|---|---|---|---|---|---|---|---|
| FR-1 | §6.1 | unified-shell/会话恢复 | AD-8/12/17/28 | 1.2 / AC-1.2-HAPPY | 1.1c,1.1d | G-02/G-05 | full / ready |
| FR-2 | §6.1 | 身份/撤权状态 | AD-2/8/17/24/25 | 1.6c / AC-1.6c-HAPPY | 1.6a,1.6b | G-02 | full / ready |
| FR-3 | §6.1 | 角色化首页 | AD-8/11/17 | 1.7 / AC-1.7-HAPPY | 1.6a,1.6b,1.6c | G-01/G-02/G-05；RFP matrix/WORKITEM-A/fixture | full / ready |
| FR-4 | §6.1 | 公共待办深链 | AD-7/13/20/24 | 5.5 / AC-5.5-* | 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.1,5.2d | G-04 | full / ready |
| FR-5 | §6.2 | 无权限/对象范围 | AD-8/12 | 1.7 / AC-1.7-HAPPY | 1.8 | G-02；RFP scope/action oracle | full / ready |
| FR-6 | §6.2 | sensitive-field/导出 | AD-9/10/13/27 | 3.14c / AC-3.14c-* | 1.8 / AC-1.8-HAPPY；3.14a,3.14b | G-02/G-07/DEC-004；RFP C/M/H + R5 星号字段封闭全集 oracle | full / ready |
| FR-7 | §6.2 | 任务期授权 | AD-8/9/10/13/23/27 | 3.14c / AC-3.14c-* | 1.8 / AC-1.8-HAPPY；3.14a,3.14b | G-02/G-07/DEC-004；purpose/fieldAllowlist/[start,end) | full / ready |
| FR-8 | §6.2 | 审计检索 | AD-3/10/16/24/27 | 1.5 / AC-1.5-HAPPY | 1.3,1.4,3.14c | G-07 | full / ready |
| FR-9 | §6.3 | 数据源目录 | AD-4/5/13 | 2.1 / AC-2.1-HAPPY | 2.4 | G-03/DEC-012 | full / ready |
| FR-10 | §6.3 | 标识异常 | AD-4/25 | 2.2 / AC-2.2-HAPPY | 2.1 | G-03 | full / ready |
| FR-11 | §6.3 | data-quality-panel | AD-5/11/16/22 | 2.3 / AC-2.3-HAPPY | 2.1 | G-03 | full / ready |
| FR-12 | §6.3 | 熔断/恢复 | AD-5/6/13/23/25 | 3.2 / AC-3.2-HAPPY | 2.4,2.5a,2.5b,2.5c | G-03/G-06/DEC-004 | full / ready |
| FR-13 | §6.3 | 数据批次/重算 | AD-4/5/13/25 | 2.2 / AC-2.2-HAPPY | 2.3 | G-03 | full / ready |
| FR-14 | §6.4 | rule-lifecycle-panel | AD-3/6/13 | 3.1a / AC-3.1a-HAPPY | 3.1b | G-06 | full / ready |
| FR-15 | §6.4 | 三类规则状态 | AD-3/6/23 | 3.1c / AC-3.1c-* | 3.1a,3.1b | G-06/DEC-004 | full / ready |
| FR-16 | §6.4 | evidence-chain | AD-4/6 | 3.3a / AC-3.3a-HAPPY | 2.2 | G-06 | full / ready |
| FR-17 | §6.4 | 排除/白名单 | AD-5/6/23 | 3.3b / AC-3.3b-* | 3.3c | G-03/G-06/DEC-004 | full / ready |
| FR-18 | §6.4 | score/priority status | AD-6/18 | 3.6 / AC-3.6-HAPPY | 3.1c | G-05/G-06 | full / ready |
| FR-19 | §6.4 | 工作台 Top-k | AD-6/11/19/22 | 3.7 / AC-3.7-* | 3.6 | G-06 | full / ready |
| FR-20 | §6.5 | UJ-1/候选门 | AD-4/5/6/18/19 | 3.4 / AC-3.4-* | 2.1,3.2,3.3b,3.3c | G-03/G-04/G-06 | full / ready |
| FR-21 | §6.5 | UJ-2/经济证据链 | AD-4/5/6/18/19 | 4.1b / AC-4.1b-* | 4.1a | G-03/G-06/DEC-005 | full / ready |
| FR-22 | §6.5 | 毕业季专项 | AD-4/6/18/19 | 4.4b / AC-4.4b-* | 4.1b,4.2,4.3,4.4a | G-03/G-06；SPM graduation 2-of-3 + academicCalendarProjection + 显式 clueId Observation | full / ready |
| FR-23 | §6.5 | 夜间作息专项 | AD-4/6/18/21 | 4.2 / AC-4.2-* | 3.5,4.1a | G-03/G-06；边界/隐私/更正 | full / ready |
| FR-24 | §6.5 | 入学/考试专项 | AD-4/6/18 | 4.4c / AC-4.4c-* | 4.2,4.3,4.4a | G-03/G-06；SPM 2-of-2/academicCalendarProjection/半开窗口 | full / ready |
| FR-25 | §6.6 | Candidate 初审 | AD-3/5/6/19/20 | 3.5 / AC-3.5-* | 3.4 | G-04/G-06 | full / ready |
| FR-26 | §6.6 | Clue 详情 | AD-4/6/11/19 | 3.6 / AC-3.6-HAPPY | 3.5 | G-05/G-06 | full / ready |
| FR-27 | §6.6 | evidence-chain/comparisonMode | AD-4/11/19 | 3.6 / AC-3.6-HAPPY | 3.5 | G-05/G-06 | full / ready |
| FR-28 | §6.6 | explanation-feedback/规则复盘 | AD-2/4/6/11/18/24 | 6.3c / AC-6.3c-HAPPY | 3.6,6.3a,6.3b | G-05/G-06 | full / ready |
| FR-29 | §6.6 | Clue 状态守卫 | AD-3/19/20 | 3.9d / AC-3.9d-* | 3.5,3.8,3.9a | G-06 | full / ready |
| FR-30 | §6.7 | workload-summary | AD-11/19/22 | 3.7 / AC-3.7-WORKBENCH-METRICS | 3.5 | G-06 | full / ready |
| FR-31 | §6.7 | task-table/Top-k | AD-6/11/19/22 | 3.7 / AC-3.7-HAPPY | 3.6 | G-06 | full / ready |
| FR-32 | §6.7 | action-form | AD-3/10/18/19 | 3.8 / AC-3.8-* | 3.6 | G-05/G-06；CAC completion/dueAt | full / ready |
| FR-33 | §6.7 | 持续观察/复查 | AD-3/19/20 | 3.9a / AC-3.9a-* | 3.8 | G-06；CAC 单 clueId Observation/唯一键/successor | full / ready |
| FR-34 | §6.7 | 权威责任转移 | AD-2/3/8/19/20/24 | 3.9b / AC-3.9b-* | 1.6b,1.6c,3.9d | G-02 | full / ready |
| FR-35 | §6.8 | 一人一档 | AD-4/8/9/11 | 3.10 / AC-3.10-HAPPY | 1.8 | G-05/G-07 | full / ready |
| FR-36 | §6.8 | timeline | AD-4/11/20/24/25 | 5.3 / AC-5.3-HAPPY | 3.10,3.6,3.8 | G-04/G-05 | full / ready |
| FR-37 | §6.8 | 身份/标签治理 | AD-3/4/5/6/8/9/23 | 4.5c / AC-4.5c-* | 4.5a,4.5b | G-01/G-03/G-06/DEC-004 | full / ready |
| FR-38 | §6.8 | 标签反馈/争议 | AD-2/4/5/6/11/24 | 4.6 / AC-4.6-* | 4.5c | G-03/G-06 | full / ready |
| FR-39 | §6.9 | transfer-work-order | AD-3/8/9/10/20/23 | 5.1 / AC-5.1-* | 1.9,3.8 | G-04/DEC-004 | full / ready |
| FR-40 | §6.9 | TransferOrder/delivery-status | AD-3/7/20/24 | 5.2d / AC-5.2d-* | 5.2a,5.2b,5.2c,5.5 | G-04 | full / ready |
| FR-41 | §6.9 | UJ-4/timeline | AD-3/7/20/24 | 5.3 / AC-5.3-HAPPY | 5.2c | G-04 | full / ready |
| FR-42 | §6.9 | delivery-status | AD-7/12/20/24 | 5.4 / AC-5.4-HAPPY | 5.2d | G-04 | full / ready |
| FR-43 | §6.10 | 学院总览 | AD-9/11/21/24 | 3.11 / AC-3.11-HAPPY | 3.7,3.10 | G-05 | full / ready |
| FR-44 | §6.10 | 单事项督办 | AD-3/11/20/21 | 3.12 / AC-3.12-HAPPY | 3.11,5.5 | G-04 | full / ready |
| FR-45 | §6.10 | leader cockpit | AD-9/11/21/24 | 6.1 / AC-6.1-HAPPY | 3.11,6.2 | G-04/G-05 | full / ready |
| FR-46 | §6.10 | 周月报/导出 | AD-9/10/11/13/21/24/27 | 6.4 / AC-6.4-* | 3.13,5.5 | G-04/G-07 | full / ready |
| FR-47 | §6.10 | 规则运营复盘 | AD-2/6/11/24 | 6.3c / AC-6.3c-HAPPY | 6.3a,6.3b | G-06 | full / ready |
| FR-48 | §6.11 | structured explanation | AD-4/6/11/18 | 3.6 / AC-3.6-HAPPY | 3.1c,3.3a | G-05/G-06 | full / ready |
| FR-49 | §6.11 | suggestion-card | AD-6/8/15/18/23 | 8.2 / AC-8.2-* | 8.1 | G-09 | full / ready |
| FR-50 | §6.11 | 自然语言问数 | AD-8/9/11/18/21 | 8.3 / AC-8.3-* | 8.1,6.4 | G-04/G-09 | full / ready |
| FR-51 | §6.11 | strategy gate | AD-6/15/18/23/27 | 8.1 / AC-8.1-* | 6.3c,6.5,6.6 | G-07/G-09/DEC-004 | full / ready |
| FR-52 | §6.11 | governance-dialog/rollback | AD-6/15/18/23 | 8.4 / AC-8.4-* | 8.1,8.2,8.3 | G-09/DEC-004 | full / ready |
| FR-53 | §6.12 | 角色隔离运行面板 | AD-13/14/16/24 | 2.6b / AC-2.6b-HAPPY | 2.6a | G-01 | full / ready |
| FR-54 | §6.12 | state-panel/恢复 | AD-7/13—16/24/25 | 6.5 / AC-6.5-* | 2.7a,2.7b,2.7c,2.8a,2.8b | G-08 | full / ready |
| FR-55 | §6.13 | mobile-task-card | AD-7—10/12/17/20/21/26 | 7.2c / AC-7.2c-* | 7.1,7.2a,7.2b | G-01/G-02/G-04/G-05 | full / ready |
| FR-56 | §6.14 | 工作纪实 | AD-2/4/9/11/21 | 4.8 / AC-4.8-HAPPY | 2.1,1.8 | G-03/G-06 | full / ready |
| FR-57 | §6.15 | 学业关怀 | AD-4/6/11/18 | 4.3 / AC-4.3-* | 2.1,3.5,4.1a | G-03/G-06；ACADEMIC/TIMETABLE/CALENDAR required + ACN/sealed/更正 | full / ready |
| FR-58 | §6.16 | 跨类别合证 | AD-4/6/19/23 | 4.7 / AC-4.7-* | 4.2,4.3,4.4b | G-06/DEC-004 | full / ready |
| FR-59 | §6.17 | 任务/结果回写 | AD-7/8/9/20/24 | 5.5 / AC-5.5-* | 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.2d | G-04 | full / ready |
| FR-60 | §6.17 | 报表/运营发布 | AD-7/9/11/21/24 | 6.4 / AC-6.4-* | 5.5,6.3b | G-04/G-07 | full / ready |
| FR-61 | §6.17 | governance-action | AD-3/10/11/21/23/24 | 6.2 / AC-6.2-* | 6.1,5.5 | G-04/DEC-004 | full / ready |
| FR-62 | §6.17 | delegation-grant | AD-2/3/8/19/20/24 | 3.9c / AC-3.9c-* | 1.7,1.8,3.9b | G-01/G-02/DEC-004 | full / ready |

## BR-1—BR-12 追踪

| BR | UX surface | Architecture | owner Story / AC | approved baseline / DoD evidence |
|---|---|---|---|---|
| BR-1 | evidence/action/suggestion | AD-18/23 | 3.5,3.8,8.2,8.3 | G-05/G-09/DEC-004 |
| BR-2 | Candidate 前置门 | AD-5/19 | 3.4 / AC-3.4-* | G-03/G-04/G-06 |
| BR-3 | Candidate/Clue 状态 | AD-3/19 | 3.5,3.9d | G-06 |
| BR-4 | action-form/移动核实 | AD-3/18/19 | 3.8,3.9a,3.9d,5.3,7.2a | G-05/G-06；CAC |
| BR-5 | 质量/规则/业务状态 | AD-6/19/20 | 2.5a,3.1c,3.9d | G-03/G-06 |
| BR-6 | transfer/delivery | AD-7/20/24 | 5.2d,5.3 | G-04 |
| BR-7 | 责任转移/delegation | AD-2/8/19/20 | 3.9b,3.9c | G-02/DEC-004 |
| BR-8 | 公共任务 | AD-7/20/24 | 5.5 / AC-5.5-* | G-04 |
| BR-9 | Top-k/workload-summary | AD-6/11/19/22 | 3.7 / AC-3.7-* | G-06 |
| BR-10 | governance-dialog | AD-3/8/23 | Epics“七路径验收登记”中各高风险 Story 的 HAPPY/AUTH/ILLEGAL/BOUNDARY/DEPENDENCY/IDEMPOTENCY/CONCURRENCY AC，并绑定具体 actionType | DEC-004/G-01 |
| BR-11 | 驾驶舱/纪实/问数 | AD-9/18/21/26 | 2.6b,3.11,4.5a,4.8,6.1,8.3 | G-05 |
| BR-12 | 日历/专项/168h/到访 | AD-6/19/22 | 2.1,4.3,4.4a/c,4.7,4.8 | G-03/G-06 |

## NFR-1—NFR-34 证据追踪

证据 owner 的详细职责见 PRD §9.9；下表绑定最终 Story、UX/AD 和 Gate。

| NFR | owner Story / AC | UX/measurement | Architecture | approved baseline / DoD evidence |
|---|---|---|---|---|
| NFR-1 | 2.6b / AC-2.6b-HAPPY | capacity profile | AD-14/22/28 | G-01/DEC-017 |
| NFR-2 | 3.7 / AC-3.7-HAPPY | ui.content-ready | AD-11/17/22 | G-05/DEC-017 |
| NFR-3 | 6.4 / AC-6.4-* | chart/table + async export | AD-11/13/17/22 | G-04/G-05/G-07 |
| NFR-4 | 3.4 / AC-3.4-* | 入库→Candidate→待办时间戳 | AD-5/13/19/20/22 | G-03/G-04/G-06 |
| NFR-5 | 2.8a / AC-2.8a-HAPPY | natural-month SLI | AD-14/16/22 | G-01/DEC-017 |
| NFR-6 | 6.5 / AC-6.5-* | DR exercise | AD-14—16/24/28 | G-08 |
| NFR-7 | 2.7a / AC-2.7a-HAPPY | state-panel | AD-1/5/13—16/18 | G-03/G-04/G-08 |
| NFR-8 | 2.7c / AC-2.7c-HAPPY；1.1d contributor | retry/reconcile；发布提升幂等/对账/回退 | AD-3/7/13/15/23/24 | G-04/G-06/G-09 |
| NFR-9 | 2.3 / AC-2.3-HAPPY | quality panel | AD-5/22 | G-03 |
| NFR-10 | 3.2 / AC-3.2-HAPPY | fuse negative test | AD-5/6 | G-03/G-06 |
| NFR-11 | 3.4 / AC-3.4-* | evidence quality snapshot | AD-4/19 | G-03/G-06 |
| NFR-12 | 6.6 / AC-6.6-* | 全数据类 retention/legal hold/watermark/DeletionReceipt/backup expiry | AD-10/13/27 | G-07 |
| NFR-13 | 1.8 / AC-1.8-HAPPY；1.1d contributor | sensitive-field/key evidence；CI/store/signing identity 边界 | AD-9/10/15/27 | G-01/G-07 |
| NFR-14 | 3.14b / AC-3.14b-HAPPY | input/projection tests | AD-8/9/12/24 | G-02/G-07 |
| NFR-15 | 1.5 / AC-1.5-HAPPY | audit ledger | AD-10/16/24/27 | G-07 |
| NFR-16 | 3.14b / AC-3.14b-HAPPY | zero successful bypass | AD-8—10/23 | G-02/G-07/DEC-004 |
| NFR-17 | 1.2 / AC-1.2-HAPPY | Web/WCAG | AD-17/21/22/28 | G-02/G-05 |
| NFR-18 | 7.1 / AC-7.1-HAPPY | App WebView | AD-17/21/26/28 | G-02/G-05 |
| NFR-19 | 7.1 / AC-7.1-HAPPY | visual tokens/viewports | AD-21/28 | G-05 |
| NFR-20 | 7.1 / AC-7.1-HAPPY | brand hierarchy | AD-17/21/28 | G-05 |
| NFR-21 | 7.1 / AC-7.1-HAPPY | non-color status | AD-17/21/28 | G-05 |
| NFR-22 | 7.2c / AC-7.2c-* | keyboard/focus/live region | AD-9/11/12/17/21/28 | G-05 |
| NFR-23 | 8.2 / AC-8.2-* | Voice and Tone | AD-18/21 | G-05/G-09 |
| NFR-24 | 2.6b / AC-2.6b-HAPPY | trace association | AD-7/13/16/24 | G-01/DEC-017 |
| NFR-25 | 3.1c / AC-3.1c-* | rule config/rollback | AD-3/6/13/15 | G-06/DEC-004 |
| NFR-26 | 6.4 / AC-6.4-* | metric catalog/version | AD-11/21/24 | G-04/G-07 |
| NFR-27 | 1.6c / AC-1.6c-HAPPY | identity latency/watermark | AD-2/8/17/20/24 | G-02 |
| NFR-28 | 3.1c,4.1a,4.4c,4.7 / AC-*-* | rule/data/SPM gate | AD-5/6/23 | G-03/G-06/DEC-004 |
| NFR-29 | 7.2c / AC-7.2c-* | committedAt→ui.state-observed | AD-7/17/22/24/26 | G-01/G-02/G-05/DEC-017 |
| NFR-30 | 1.2 / AC-1.2-HAPPY | exact browser matrix | AD-17/22/28 | G-05 |
| NFR-31 | 7.1 / AC-7.1-HAPPY | exact WebView/375px | AD-17/21/22/26/28 | G-02/G-05 |
| NFR-32 | 1.1c / AC-1.1c-HAPPY | PerformanceProfileVersion | AD-14/17/22/28 | G-01/DEC-017 |
| NFR-33 | 1.1d / AC-1.1d-HAPPY（final owner） | release manifest/SBOM + UX/token/brand + RFP/CAC/SPM/ACN | AD-6/12/15/23/27/28 | G-01/G-05/G-06 及各发布 Gate |
| NFR-34 | 7.2c / AC-7.2c-* | storage scan/offline/logout | AD-8—10/17/21/26 | G-02/G-05 |

## ID 迁移与校验规则

- 无连字符 `FR1`、`BR1`、`NFR1` 等均为 validation error；正式命名空间只有 FR-1—62、BR-1—12、NFR-1—34。
- 旧 `NFR1—NFR25` 存在语义错配，不建立全局机械别名；按 PRD §9.9 的迁移实例和本矩阵重新绑定。
- Story ID 后缀是稳定拆分 ID；被拆父 Story 不再作为可排期项。
- owner Story 唯一；contributor 不得把 coverage 标为 full。未知需求 ID、重复 Story/AC ID、缺 metadata/Given/When/Then 均阻断基线发布。

## 当前结论

FR/BR/NFR 的规格追踪已闭合，12 个报告阻塞项全部 closed，DEC-001—018 全部 closed，G-01—G-09 全部 `approved-for-implementation`。项目可进入实现与 Story 验收。运行证据仍严格按 owner Story 产生：契约、性能、可用性、灾备、删除、可访问性、供应链或 canary 任一失败时，对应 Story/Release 保持未完成，禁止把委托批准解释为实测通过。
