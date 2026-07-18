---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
  - course-correction-2026-07-16
  - course-correction-2026-07-17
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md
  - _bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md
  - _bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/rule-catalog.md
  - _bmad-output/planning-artifacts/high-risk-action-matrix.md
  - _bmad-output/planning-artifacts/open-decisions.md
  - _bmad-output/planning-artifacts/requirements-traceability.md
  - _bmad-output/planning-artifacts/sprint-change-proposal-2026-07-17.md
  - _bmad-output/planning-artifacts/implementation-readiness-report-2026-07-17-2.md
  - _bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md
  - _bmad-output/planning-artifacts/app-applicability-baseline-2026-07-19.md
status: controlled-baseline
baselineVersion: "2.1.1"
updated: 2026-07-19-story-1.1d-applicability-alignment
requirementsBaseline:
  functional: FR-1–FR-62
  businessRules: BR-1–BR-12
  nonFunctional: NFR-1–NFR-34
externalGateStatus: approved-for-implementation
implementationReadiness: ready
runtimeEvidenceStatus: pending-story-execution
---

# ScholarSense-bmad-method - Epic 与 Story 受控基线 v2.1.1

## 实施与验收规则

- 本文是 2026-07-17 最终 readiness 闭环与 `AUTH-2026-07-17-001` 批准的 Epics/Stories 实施基线；保留 8 个 Epic，范围为 FR-1–FR-62、BR-1–BR-12、NFR-1–NFR-34。
- 每个 FR 只有一个最终端到端 owner Story；contributing Story 可是更早的前置/phase，也可是更晚的下游消费、hardening 或发布 conformance。contributor 只表示跨 Story 关联，不自行形成执行依赖、不得宣称该 FR 已完成；只有 `dependsOn` 与 `readyWhen` 定义顺序/门槛。
- Story 只能依赖更早 Story 或已冻结基线。DEC-001—018 已 closed，G-01—09 已 approved-for-implementation；不存在外部 DoR 阻塞。契约、性能、可用性、恢复、删除、视觉/无障碍、供应链与 canary 证据由对应 Story/DoD 真实产生，失败时 Story 不得完成、Release 不得提升。
- type 取 enabler、feature、integration、governance、hardening 或 release；dependsOn 是执行顺序；readyWhen 是开工或验收门槛；estimate 是相对实施估算，不构成日历承诺。
- 高风险动作必须引用 HRAP-1.0.0；动作未映射、该 actionType 的运行测试未通过、审批过期或版本漂移时默认 deny。D4 必须验证不同自然人 maker/checker。
- 所有报表和明细导出均为异步作业；所有移动写操作首期仅在线执行，禁止持久化离线业务数据。
- 通用 Definition of Done 仅是底线：服务端授权、审计、幂等/并发、错误路径、可观测性、无障碍、隐私、依赖失败与回退均通过。它不能替代任何 Story 的专属 AC，也不能把未写明的 happy、auth denial、非法状态、边界、依赖失败、幂等或并发路径视为已验收。

本次纠偏将下列 36 个高风险命令或用户/治理聚合状态迁移 Story 纳入“七路径验收登记”，每个均显式具备 `HAPPY`、`AUTH-DENIAL`、`ILLEGAL-STATE`、`BOUNDARY`、`DEPENDENCY-FAILURE`、`IDEMPOTENCY`、`CONCURRENCY` AC：2.5a、2.5b、2.5c、3.1b、3.1c、3.3b、3.4、3.5、3.8、3.9a、3.9b、3.9c、3.9d、3.14a、3.14c、4.1a、4.1b、4.5c、4.6、4.7、5.1、5.2a、5.2b、5.2c、5.2d、5.5、6.2、6.4、6.5、6.6、7.2a、7.2b、7.2c、8.1、8.2、8.4。只读投影、纯计算或不拥有聚合迁移的 contributor 仍须满足专属 GWT 与通用 DoD，但不冒充上述登记项。

## Epic 与 FR 结果边界

| Epic | 用户结果 | 主要最终 FR |
|---|---|---|
| Epic 1 | 可信门户、身份、授权与审计基础 | FR-1–FR-3、FR-5、FR-8 |
| Epic 2 | 可信数据运营、可观测性与阶段恢复 | FR-9–FR-13、FR-53；FR-54 的前置 |
| Epic 3 | 住宿安全首个生产闭环与授权代办 | FR-14–FR-20、FR-25–FR-27、FR-29–FR-35、FR-43–FR-44、FR-48、FR-62；FR-28/FR-36 phase |
| Epic 4 | 多场景、标签、合证与工作纪实 | FR-21–FR-24、FR-37–FR-38、FR-56–FR-58 |
| Epic 5 | 跨部门协同与公共任务/结果回写 | FR-4、FR-36、FR-39–FR-42、FR-59 |
| Epic 6 | 校级治理、复盘、报告、运营回写与 RC 恢复 | FR-28、FR-45–FR-47、FR-54、FR-60–FR-61 |
| Epic 7 | 学校统一 App 在线轻量处置 | FR-55 |
| Epic 8 | 先门禁、后灰度、可回退的智能增强 | FR-49–FR-52 |

## 实现准入 Gate 与运行证据登记

| Gate | 名称 | approved DoR baseline | accountable / Responsible | 运行证据 owner | 状态 |
|---|---|---|---|---|---|
| G-01 | 权威附件与供应链 | AUTH、PAB/PP/AP/RFP | Hei（A）；制品/测试/SRE（R） | 1.1c、1.1d、2.8a | approved-for-implementation |
| G-02 | 宿主、SSO 与授权 | HIP/ISP/RFP-1.0.0 + RFP-FIXTURE-1.0.0 | Hei（A）；门户/身份/权限（R） | 1.2、1.6a—c、1.7、1.8、7.1/7.2c | approved-for-implementation |
| G-03 | 数据、课表与质量恢复 | DCC/QG/QRP、17 源/11 dependency | Hei（A）；逐源 owner（R） | Epic 2、3.2—3.4、6.5 | approved-for-implementation |
| G-04 | 公共任务、工单与回写 | PIC/TSP/MPP-1.0.0 | Hei（A）；公共能力/协同（R） | 1.9、5.1—5.5、6.4 | approved-for-implementation |
| G-05 | 前端与 UI | UXB/PAB/PP/AP/CTV-1.0.0 | Hei（A）；UX/前端/App/品牌（R） | 1.1c/d、1.2、7.x | approved-for-implementation |
| G-06 | 规则、证据、队列、校历、动作与专项 | RC/ES/QP/BC/WVP/CAC/SPM/ACN-1.0.0 | Hei（A）；规则/业务/校历（R） | 3.x、4.x、6.1 | approved-for-implementation |
| G-07 | 保留与删除 | RS-1.0.0、legal hold/receipt schema | Hei（A）；数据治理/基础设施（R） | 6.6 | approved-for-implementation |
| G-08 | 灾备 | DRP-1.0.0、拓扑/runbook/RPO/RTO | Hei（A）；技术/基础设施（R） | 2.8b、6.5 | approved-for-implementation |
| G-09 | 智能发布 | SGP-1.0.0、阈值/基线/分群/人工门 | Hei（A）；模型治理/公平复核（R） | 8.1—8.4 | approved-for-implementation |

> Gate 批准解决的是“按什么参数实现”。上表运行证据必须真实产生；未通过的 Story 仍为未完成，不能把 Gate 批准写成测试通过。

## FR → Story 追踪矩阵

| FR | owner Story | contributing Story | approved baseline / DoD evidence |
|---|---|---|---|
| FR-1 | 1.2 | 1.1c,1.1d | G-02/G-05：SSO、宿主、浏览器与 UI 证据 |
| FR-2 | 1.6c | 1.6a,1.6b | G-02：权威身份、责任关系、撤权传播与对账 |
| FR-3 | 1.7 | 1.6a,1.6b,1.6c | G-01/G-02/G-05：七角色对象/展开后 action/scope 矩阵、WORKITEM-A、RFP fixture 与角色化 UI 基线 |
| FR-4 | 5.5 | 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.1,5.2d | G-04：公共任务全生命周期契约与真实生产者测试 |
| FR-5 | 1.7 | 1.8 | G-02：对象/动作/scope predicate、冲突算法与 RoleFieldPolicyVersion |
| FR-6 | 3.14c | 1.8,3.14a,3.14b | G-02/G-07/DEC-004：RFP 八类 C/M/H、R5 星号字段封闭全集、RetentionScheduleVersion 与敏感导出门禁 |
| FR-7 | 3.14c | 1.8,3.14a,3.14b | G-02/G-07/DEC-004：任务 purpose/fieldAllowlist、`[startAt,endAt)`、撤权、删除收据与下载重检 |
| FR-8 | 1.5 | 1.3,1.4,3.14c | G-07：审计保留、legal hold 与销毁证明 |
| FR-9 | 2.1 | 2.4 | G-03/DEC-012：17 个 P0/P1 逐源合同、全局唯一 sourceId、规则消费源 dependency 映射与课表/校历证据 |
| FR-10 | 2.2 | 2.1 | G-03：标识映射 owner 与异常样例 |
| FR-11 | 2.3 | 2.1 | G-03：质量公式、门槛和批次样例 |
| FR-12 | 3.2 | 2.4,2.5a,2.5b,2.5c | G-03/G-06/DEC-004：质量门、QualityRecoveryPolicyVersion、恢复授权与事件契约 |
| FR-13 | 2.2 | 2.3 | G-03：历史窗口与受控重算证据 |
| FR-14 | 3.1a | 3.1b | G-06：RuleVersion 与受控枚举 |
| FR-15 | 3.1c | 3.1a,3.1b | G-06/DEC-004：规则生命周期、发布/回退审批和矩阵证据 |
| FR-16 | 3.3a | 2.2 | G-06：基线窗口与样本不足策略 |
| FR-17 | 3.3b | 3.3c | G-03/G-06/DEC-004：排除、`whitelist.create-or-change` 与版本化边界样本 |
| FR-18 | 3.6 | 3.1c | G-05/G-06：解释组件与等级口径 |
| FR-19 | 3.7 | 3.6 | G-06：QueuePolicyVersion、K、容量与稳定排序 |
| FR-20 | 3.4 | 2.1,3.2,3.3b,3.3c | G-03/G-04/G-06：逐源组合、课表切片、公共任务与 ACC-SAFE 生产规则 |
| FR-21 | 4.1b | 4.1a | G-03/G-06/DEC-005：RC/DCC/QG/QRP 已批准；4.1a/4.1b 验证依赖、样本、D4 与 canary |
| FR-22 | 4.4b | 4.1b,4.2,4.3,4.4a | G-03/G-06：SPM 毕业 2-of-3、academicCalendarProjection、三场景生产资格与显式参与 clueId 的 7 日观察 |
| FR-23 | 4.2 | 3.5,4.1a | G-03/G-06：NIGHT-001、P1 网络逐源合同与规则发布证据 |
| FR-24 | 4.4c | 4.2,4.3,4.4a | G-03/G-06：SPM 入学/考试 2-of-2、academicCalendarProjection schema、required/optional、隐私和半开窗口 |
| FR-25 | 3.5 | 3.4 | G-04/G-06：Candidate 状态机与公共任务 |
| FR-26 | 3.6 | 3.5 | G-05/G-06：结构化证据组件与规则证据语义 |
| FR-27 | 3.6 | 3.5 | G-05/G-06：解释模板与质量快照 |
| FR-28 | 6.3c | 3.6,6.3a,6.3b | G-05/G-06：ExplanationFeedback 三维提交、版本化原因与规则运营聚合 |
| FR-29 | 3.9d | 3.5,3.8,3.9a | G-06：业务状态、日历与关闭守卫 |
| FR-30 | 3.7 | 3.5 | G-06：工作台状态和队列口径 |
| FR-31 | 3.7 | 3.6 | G-06：Top-k 策略证据 |
| FR-32 | 3.8 | 3.6 | G-05/G-06：CAC 六类完成谓词、dueAt 来源、核实表单和合法迁移 |
| FR-33 | 3.9a | 3.8 | G-06：单 clueId 观察唯一键、窗口与复查策略 |
| FR-34 | 3.9b | 1.6b,1.6c,3.9d | G-02：责任关系、撤权与接收人证据 |
| FR-35 | 3.10 | 1.8 | G-05/G-07：最小档案字段策略 |
| FR-36 | 5.3 | 3.10,3.6,3.8 | G-04/G-05：真实 TransferEvent 时间线与可访问呈现 |
| FR-37 | 4.5c | 4.5a,4.5b | G-01/G-03/G-06/DEC-004：身份/标签逐源合同、来源冲突与 `bulk-governance.execute` |
| FR-38 | 4.6 | 4.5c | G-03/G-06：争议所用权威来源合同与恢复计算规则 |
| FR-39 | 5.1 | 1.9,3.8 | G-04/DEC-004：转介契约、目标授权、沙箱与 `transfer.submit` |
| FR-40 | 5.2d | 5.2a,5.2b,5.2c,5.5 | G-04：业务状态与 TransferOrder.deliveryStatus 契约 |
| FR-41 | 5.3 | 5.2c | G-04：TransferEvent 乱序、撤销和通知 |
| FR-42 | 5.4 | 5.2d | G-04：外部工单签名、回调和对账 |
| FR-43 | 3.11 | 3.7,3.10 | G-05：指标 UI、授权下钻和性能证据 |
| FR-44 | 3.12 | 3.11,5.5 | G-04：单事项督办任务、接收人异常和公共任务契约；批量治理不属于本 Story |
| FR-45 | 6.1 | 3.11,6.2 | G-04/G-05：MetricPublicationPolicyVersion、聚合口径、匿名阈值和 UI |
| FR-46 | 6.4 | 3.13,5.5 | G-04/G-07：协同事实、报表口径与保留 |
| FR-47 | 6.3c | 6.3a,6.3b | G-06：真值窗口、分群和护栏 |
| FR-48 | 3.6 | 3.1c,3.3a | G-05/G-06：结构化解释和模板版本 |
| FR-49 | 8.2 | 8.1 | G-09：建议策略取得 initial cohort 资格 |
| FR-50 | 8.3 | 8.1,6.4 | G-04/G-09：授权 MetricPublication 口径库和拒答策略 |
| FR-51 | 8.1 | 6.3c,6.5,6.6 | G-07/G-09/DEC-004：完整 Release 基线、StrategyGatePolicyVersion、`strategy.publish`、阈值、基线和审批 |
| FR-52 | 8.4 | 8.1,8.2,8.3 | G-09/DEC-004：`strategy.rollback`、稳定基线和回退证据 |
| FR-53 | 2.6b | 2.6a | G-01：可观测基线、角色隔离和 PerformanceProfileVersion |
| FR-54 | 6.5 | 2.7a,2.7b,2.7c,2.8a,2.8b | G-08：生产 DRPlan、演练窗口和一致性集 |
| FR-55 | 7.2c | 7.1,7.2a,7.2b | G-01/G-02/G-04/G-05：宿主 WebView、移动 token、共用契约与跨端装置 |
| FR-56 | 4.8 | 2.1,1.8 | G-03/G-06：工作纪实逐源合同、WorkVisitPolicyVersion、时区和边界样本 |
| FR-57 | 4.3 | 2.1,3.5,4.1a | G-03/G-06：ACADEMIC-001 required ACADEMIC/TIMETABLE/CALENDAR、ACN 七节点、sealed 批次与发布证据 |
| FR-58 | 4.7 | 4.2,4.3,4.4b | G-06/DEC-004：CORROBORATE-001 发布七路径、BusinessCalendarVersion 与 168h 边界样本 |
| FR-59 | 5.5 | 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.2d | G-04：任务/结果回写契约、最小字段和水位 |
| FR-60 | 6.4 | 5.5,6.3b | G-04/G-07：运营域契约、匿名阈值和质量门 |
| FR-61 | 6.2 | 6.1,5.5 | G-04/DEC-004：leader-action.record 和复查任务 |
| FR-62 | 3.9c | 1.7,1.8,3.9b | G-01/G-02/DEC-004：RoleFieldPolicyVersion、代办动作范围与 temporary-grant issue/revoke 门禁 |

## BR 覆盖矩阵

| BR | 验收 Story | 不变量 |
|---|---|---|
| BR-1 | 3.5,3.8,8.2,8.3 | 线索非结论；重大行动由具权人员人工决定 |
| BR-2 | 3.4 | 仅对已通过质量门的 RuleEvaluation 执行 CandidateAdmissionDecision；其先于 Candidate 和时钟 |
| BR-3 | 3.5,3.9d | Candidate 三分支与截止时钟连续性 |
| BR-4 | 3.8,3.9a,3.9d,5.3,7.2a | CAC 完成谓词、Observation、协同确认和关闭守卫 |
| BR-5 | 2.5a,3.1c,3.9d | 业务、超期、质量、治理和运行状态正交 |
| BR-6 | 5.2d,5.3 | TransferOrder 业务状态与 TransferOrder.deliveryStatus 正交 |
| BR-7 | 3.9b,3.9c | 权威责任转移与授权代办分离 |
| BR-8 | 5.5 | 公共任务业务键唯一、更新、关闭与对账 |
| BR-9 | 3.7 | QueuePolicyVersion、K、容量与稳定排序 |
| BR-10 | 2.5b,2.5c,3.1b,3.1c,3.3b,3.9c,3.14a,3.14c,4.5c,5.1,6.2,8.1,8.4 | 高风险未映射默认 deny 并重检 |
| BR-11 | 2.6b,3.11,4.5a,4.8,6.1,8.3 | 各用途读模型隔离且不得自动惩戒 |
| BR-12 | 2.1,4.3,4.4a,4.7,4.8 | BusinessCalendarVersion、时区与含边界规则 |

## NFR 覆盖矩阵

| NFR | 证据 Story | 验收主题 |
|---|---|---|
| NFR-1 | 1.1c,2.6b | 目标容量与事件规模 |
| NFR-2 | 3.7 | ui.content-ready 工作台 P95 |
| NFR-3 | 3.11,3.14a,6.1,6.4 | 看板 P95 与全部异步导出 |
| NFR-4 | 3.4 | Candidate 与公共任务时延 |
| NFR-5 | 2.8a,6.5 | 连续月度可用性 SLI |
| NFR-6 | 2.8b,6.5 | RPO 与 RTO 的批准演练 |
| NFR-7 | 2.7a,8.2 | 故障隔离与人工闭环 |
| NFR-8 | 2.7c（final owner）；1.1d 等（contributors） | 业务幂等、重试、灰度、对账和回退；1.1d 只覆盖发布提升侧 |
| NFR-9 | 2.3 | P0 质量门槛 |
| NFR-10 | 2.4,2.5a,3.2 | 质量熔断不得降门槛 |
| NFR-11 | 2.3,3.4 | Candidate 与线索质量快照 |
| NFR-12 | 6.6（owner）；1.5,3.14c,6.4,6.5（contributors） | 全数据类 RetentionSchedule、legal hold、消费者 watermark、实际 DeletionReceipt 和备份过期 fail closed |
| NFR-13 | 1.8（final owner）；1.1d（供应链 contributor） | 业务敏感字段传输/存储/密钥；1.1d 只覆盖 CI/store/signing identity 边界 |
| NFR-14 | 1.8,3.14b | 服务端输入与字段投影 |
| NFR-15 | 1.3,1.4,1.5 | 原子、防篡改审计 |
| NFR-16 | 1.7,1.8,3.14b | 越权、明文导出与绕审计为零 |
| NFR-17 | 1.2 | Web 门户、视口与 WCAG |
| NFR-18 | 7.1 | 统一移动入口 |
| NFR-19 | 1.2,7.1 | 版本化视觉基准 |
| NFR-20 | 1.2,7.1 | 品牌层级和批准校徽 |
| NFR-21 | 3.6,3.7,7.1 | 状态不得只靠颜色 |
| NFR-22 | 1.2,3.7,7.1,7.2c | 键盘、焦点、等价表格与 live region |
| NFR-23 | 3.6,4.1b,8.2 | 中性、可解释、非诊断语言 |
| NFR-24 | 1.4,2.6a,2.6b | 全链路 traceId 且遥测最小化 |
| NFR-25 | 3.1a,3.1c | 规则配置版本化且无需代码发布 |
| NFR-26 | 3.13,6.3a,6.4 | 唯一 metricId 与口径版本 |
| NFR-27 | 1.6a,1.6b,1.6c | 身份同步、责任对账和撤权水位 |
| NFR-28 | 3.1b,3.1c,4.1a,4.7 | 规则与数据验收门 |
| NFR-29 | 7.2c | committedAt 到 ui.state-observed P95 |
| NFR-30 | 1.2 | 可复现 Edge/Chrome 验收矩阵 |
| NFR-31 | 7.1 | iOS/Android WebView 与 375px |
| NFR-32 | 1.1c,2.6b,3.7 | 冻结 PerformanceProfileVersion |
| NFR-33 | 1.1d（final owner）；1.1c,3.8,4.4c,8.1（contributors） | 含 UX/token/brand/CAC/SPM/ACN/RFP 的统一版本化发布基线 |
| NFR-34 | 7.1,7.2a,7.2c | 禁止持久化离线业务数据 |

## Epic 1：可信门户接入与可问责授权

### Story 1.1a：审计生产资产与供应链候选

**type:** enabler  
**dependsOn:** none  
**readyWhen:** AUTH/PAB-1.0.0 已批准；现有仓库、部署平台与制品来源可读取并由本 Story 审计  
**estimate:** 2d  
**status:** planned

作为实施团队，我希望识别可复用生产资产和原型边界。

**需求覆盖：** M0; AD-28

#### AC-1.1a-HAPPY

**Given** AUTH/PAB-1.0.0 已批准且仓库、部署平台与制品来源可读取  
**When** 实施团队执行本 Story 的主流程  
**Then** 形成带 owner、版本、生效日期和证据链接的资产清单，Vite 原型不得被默认为生产基线  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.1b：建立模块化生产工程骨架

**type:** enabler  
**dependsOn:** 1.1a  
**readyWhen:** G-01/PAB-1.0.0 的运行时、数据库与制品线已批准  
**estimate:** 3d  
**status:** planned

作为实施团队，我希望获得可重复构建的生产工程骨架。

**需求覆盖：** M0; AD-1; AD-2; AD-28

#### AC-1.1b-HAPPY

**Given** G-01/PAB-1.0.0 的依赖、运行时、数据库和制品线已批准  
**When** 实施团队执行本 Story 的主流程  
**Then** 后端遵循 domain 到 application 到 adapters 的依赖方向，前端按业务域组织且环境配置隔离  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.1c：批准生产前端与性能 Profile ADR

**type:** enabler  
**dependsOn:** 1.1b  
**readyWhen:** PAB/PP/AP/UXB-1.0.0 已批准；本 Story 冻结精确 lock 与测试环境 manifest  
**estimate:** 3d  
**status:** planned

作为前端与性能负责人，我希望冻结可复现的生产前端组合和 PerformanceProfileVersion。

**需求覆盖：** NFR-1; NFR-17; NFR-30; NFR-31; NFR-32; NFR-33; AD-22; AD-28

#### AC-1.1c-HAPPY

**Given** PAB/PP/AP/UXB-1.0.0 已批准且环境 manifest 可建立  
**When** 评审 frontend-production-baseline 与 PerformanceProfileVersion ADR  
**Then** 冻结受支持依赖组合、锁文件与 Web 浏览器/设备/网络/冷热缓存/数据分布/采样点/失败归因；App/WebView 对 1.1c/1.1d 按 `AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 保存受控 N/A，不填造版本  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.1d：固化 CI、供应链与质量门

**type:** enabler  
**dependsOn:** 1.1c  
**readyWhen:** PAB-1.0.0 已定义 lock/SBOM/provenance/签名/扫描/制品提升 DoD，可开始本地可复现构建合同；受信 CI、store、attestation/signing、正式 Web 证据与真实 promotion 须先由 `CISB-1.0.0` 冻结实际平台，缺失时不得将整体 Story 推进到 review/done  
**estimate:** 5d  
**status:** planned

作为发布工程师，我希望让每个生产制品可复现、可追溯、可阻断。

**需求覆盖：** NFR-33（本 Story 最终 owner）；NFR-8、NFR-13（仅发布供应链 contributor，最终 owner 分别为 2.7c、1.8）；AD-28

**受控适用性裁决：** `AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 仅将 1.1c/1.1d 的 App/WebView 基线与运行报告裁定为 `not-applicable`；1.1d 必须生成 Web 浏览器/视觉/无障碍发布报告，并在 ReleaseManifest 中保留 AAB 版本、决策 ID 与 `runtimeEvidenceClaim=none`。NFR-31 和 7.1/7.x 的真实 App/WebView/真机 owner 不变，未来启用须发布新基线版本。

#### AC-1.1d-HAPPY

**Given** 1.1c 的生产基线 ADR 已批准且供应链证据齐备  
**When** 发布工程师运行 CI 与制品提升  
**Then** CI 完成构建、测试、依赖与漏洞检查、SBOM、签名和制品提升，任一必需证据失败即阻断  
**And** 同一提交可重复制得同摘要制品，并保存批准基线和完整证据链接。

### Story 1.2：接入统一门户 SSO 与可恢复应用壳

**type:** integration  
**dependsOn:** 1.1d  
**readyWhen:** HIP/ISP/RFP/UXB/PAB-1.0.0 已批准；本 Story 负责真实宿主/SSO/深链/浏览器契约测试  
**estimate:** 5d  
**status:** planned

作为授权用户，我希望从统一门户进入并在重认证后返回原目标。

**需求覆盖：** FR-1; NFR-17; NFR-19; NFR-20; NFR-30

#### AC-1.2-HAPPY

**Given** HIP/ISP/RFP/UXB/PAB-1.0.0 已批准且目标沙箱可连接  
**When** 授权用户执行本 Story 的主流程  
**Then** 认证、续期、登出、换号、深链恢复和宿主降级均按契约验收且不重复索要密码  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.3：建立模块内原子审计事实

**type:** enabler  
**dependsOn:** 1.1d  
**readyWhen:** G-01 已批准，审计 schema 与可信时间源合同已由 Architecture 冻结  
**estimate:** 3d  
**status:** planned

作为合规负责人，我希望让关键命令和敏感读取均可追责。

**需求覆盖：** FR-8（phase/enabler）; NFR-15

#### AC-1.3-HAPPY

**Given** G-01 已批准且审计 schema 与可信时间源合同已冻结  
**When** 合规负责人执行本 Story 的主流程  
**Then** 业务提交或敏感返回与 LocalAuditFact、outbox 原子完成，审计失败时命令失败或内容不返回  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.4：归集不可变审计账本并检测篡改

**type:** enabler  
**dependsOn:** 1.3  
**readyWhen:** 账本序列、previousHash 和重放契约完成评审  
**estimate:** 3d  
**status:** planned

作为审计运维人员，我希望发现审计缺口、重复和篡改。

**需求覆盖：** FR-8（phase/enabler）; NFR-15; NFR-24

#### AC-1.4-HAPPY

**Given** 账本序列、previousHash 和重放契约完成评审  
**When** 审计运维人员执行本 Story 的主流程  
**Then** 按 auditId 幂等归集，禁止更新删除，并对序列缺口、哈希不一致和积压发出可关联告警  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.5：提供授权审计检索、归档与销毁证明

**type:** feature  
**dependsOn:** 1.4  
**readyWhen:** G-07/RS-1.0.0、legal hold 与 DeletionReceipt schema 已批准；本 Story 使用合成 fixture 实现审计域，不前置要求 Story 6.6 的实际 receipt  
**estimate:** 3d  
**status:** planned

作为授权审计人员，我希望检索、归档并证明保留或销毁。

**需求覆盖：** FR-8; NFR-12（审计记录域 contributor）; NFR-15

#### AC-1.5-HAPPY

**Given** approved RetentionScheduleVersion、legal hold 规则、DeletionReceipt schema 与合成/匿名 fixture 可用  
**When** 授权审计人员检索只读记录并在 fixture 上演练审计域归档/销毁  
**Then** 只返回授权范围记录，归档/销毁执行事实引用批准版本并产出可供汇总的审计域 RetentionExecution 证据  
**And** 实际跨域 DeletionReceipt 只能由 Story 6.6 在全部 owner/消费者 watermark/对象/备份执行完成后签发，本 Story 不伪造该退出证据。

### Story 1.6a：同步权威身份与组织

**type:** enabler  
**dependsOn:** 1.2  
**readyWhen:** G-02/HIP/ISP/RFP-1.0.0 已批准；本 Story 负责连接身份沙箱并形成对账样例  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望及时获得正确账号、角色和组织。

**需求覆盖：** FR-2（身份/组织 phase）; NFR-27（phase）

#### AC-1.6a-HAPPY

**Given** G-02/HIP/ISP/RFP-1.0.0 已批准且身份沙箱可连接  
**When** 辅导员执行本 Story 的主流程  
**Then** 账号、角色和组织增量在 15 分钟内生效，并保存来源版本、水位、作业状态和 traceId  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.6b：同步责任关系并执行每日对账

**type:** feature  
**dependsOn:** 1.6a  
**readyWhen:** 责任关系契约、接收人有效性和全量对账样例通过测试  
**estimate:** 3d  
**status:** planned

作为授权管理员，我希望责任学生与学院归属可对账且无接收人可见。

**需求覆盖：** FR-2（责任关系 phase）; BR-7; NFR-27（phase）

#### AC-1.6b-HAPPY

**Given** 权威责任关系版本和每日全量对账样例可用  
**When** 授权管理员执行本 Story 的主流程  
**Then** 更新责任关系读模型并按来源水位完成每日对账；无有效接收人进入学院异常队列  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 1.6c：发布撤权、更正与失效传播事实

**type:** feature  
**dependsOn:** 1.6b  
**readyWhen:** 更正、撤权、lineage、supersedes 与下游水位契约通过乱序测试  
**estimate:** 3d  
**status:** planned

作为授权管理员，我希望错误映射、过期关系和撤销在下一次请求即时失效。

**需求覆盖：** FR-2（full）; BR-7; NFR-27（full）

#### AC-1.6c-HAPPY

**Given** 权威源发布更正、撤销或无效关系版本  
**When** 失效事实传播到授权、任务、导出和移动会话  
**Then** 发布带 lineage 与 supersedes 的更正/撤权事实，并推进所有下游失效水位  
**And** 下一次敏感请求 fail closed，历史事实不改写且异常可按 traceId 对账。

### Story 1.7：提供角色化首页与组合对象授权

**type:** feature  
**dependsOn:** 1.6c  
**readyWhen:** RFP-1.0.0 的七角色对象/动作/scope 表、冲突算法、字段表与 RFP-FIXTURE-1.0.0 已批准  
**estimate:** 5d  
**status:** planned

作为平台用户，我希望只访问职责允许的入口和对象。

**需求覆盖：** FR-3; FR-5; NFR-16

#### AC-1.7-HAPPY

**Given** RFP-FIXTURE-1.0.0 的七主体、CASE-A/B、R2 对 CASE-A 的 WORKITEM-A、TRANSFER-A/B、REPORT-COL-A/SCHOOL、RULE-1、DQ-A/B、JOB-1 和固定 serverNow  
**When** 对每个 roleId、objectClass、scopeRelation、actionId 及多角色/未知 action 组合执行表驱动授权  
**Then** R1—R7 逐行得到委托基线 §3.3 的确定 allow/deny oracle；R2 仅通过 WORKITEM-A 对 CASE-A 执行最小 `care.read`，无督办 workItem 时 deny；R1+R2 对同 object/action 取适用字段最严，R1+R7 中无 care 权的 R7 不降级 R1，含字面 `/` 的 actionId、未知 `care.delete`、缺 policy 或职责冲突均 deny  
**And** DelegationGrant 恰在 start 生效、恰在 end 失效，关系/objectVersion 并发变化在提交前重检；越界与不存在使用同 status/code/envelope，失败不泄露对象存在性并保存最小拒绝审计。

### Story 1.8：执行字段级投影与任务期敏感授权

**type:** feature  
**dependsOn:** 1.7  
**readyWhen:** G-02/G-07、RFP/RS-1.0.0 的八类字段矩阵、purpose allowlist 与 RFP-FIXTURE-1.0.0 已批准并可引用  
**estimate:** 5d  
**status:** planned

作为承担任务的用户，我希望只在授权期看到最小必要信息。

**需求覆盖：** FR-6（在线投影 phase）; FR-7（任务期授权 phase）; NFR-13; NFR-14; NFR-16

#### AC-1.8-HAPPY

**Given** RFP-FIXTURE-1.0.0 的七角色与 B/I/C/S/E/N/G/T 期望表，TRANSFER-A 的 fieldAllowlist=`studentContactPhone,requestedServiceCode,referralSummary,resultSummary`、R5 星号字段封闭全集、R6 owned-source 例外及全局禁止字段  
**When** 服务端在列表、详情、API、异步导出、DOM、缓存与无障碍树执行 C/M/H 投影，并在序列化/下载前重检 policy/scope/objectVersion/keyVersion  
**Then** clear 仅含批准最小 schema；R5 对 TRANSFER-A 仅四个 allowlist 字段 clear，`studentContactEmail/referralReasonCode/supplementRequestText`、全集外字段和 TRANSFER-B 全部 hidden；masked 使用与原值长度无关的固定替代，hidden 同时省略键、值和长度，诊断/咨询正文、原始证据、网络内容与密钥值始终 hidden  
**And** purpose 失配、授权恰在 end、策略/密钥/IAM 不可用或并发撤权均 fail closed，不缓存旧权限、不降级明文且各输出渠道 oracle 一致。

### Story 1.9：建立公共任务、消息与回写适配器契约

**type:** integration  
**dependsOn:** 1.1d  
**readyWhen:** G-04/PIC/TSP/MPP-1.0.0 已批准；本 Story 负责实现 OpenAPI/事件、DeliveryRecord、认证、幂等、乱序和对账测试  
**estimate:** 5d  
**status:** planned

作为集成团队，我希望让后续真实生产者使用稳定公共契约。

**需求覆盖：** FR-4（phase/enabler）; FR-59（phase/enabler）; NFR-8

#### AC-1.9-HAPPY

**Given** G-04/PIC/TSP/MPP-1.0.0 已批准且目标沙箱可连接  
**When** 集成团队执行本 Story 的主流程  
**Then** 合成事件通过创建、更新、关闭、撤销、重试、乱序和双方 ID 水位对账；每条 DeliveryRecord 保存 aggregateType/id、channel、contractVersion，非转介聚合不出现无归属 `deliveryStatus`；本 Story 不创建领域对象  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。


## Epic 2：可信数据运营与安全连续性

### Story 2.1：管理 P0/P1 数据源目录与最小课表校历切片

**type:** feature  
**dependsOn:** 1.1d  
**readyWhen:** DEC-012/DCC/QG/QRP-1.0.0 的 17 个 sourceId、Responsible 角色、schema/SLO/质量门/补数/对账合同已冻结；本 Story 负责逐源契约测试和证据 URI  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望明确 P0/P1 数据口径、责任与依赖。

**需求覆盖：** FR-9; BR-12

#### AC-2.1-HAPPY

**Given** DEC-012 的 17 个 P0/P1 源分别具备具名 owner、schema、SLO、质量门、补数/对账、契约测试和 evidenceUri  
**When** 数据责任人发布目录版本  
**Then** 目录恰含 DEC-012 的 17 个当前规划源且 sourceId 全局唯一、稳定不复用；每个源保存 purpose、版本与责任，每个规则消费源映射稳定 dependencyId，非规则消费源明确用途隔离；任一必填缺失或契约不兼容时该目录版本标记 invalid/not-publishable  
**And** 交付 FR-20 所需的实习/请假、校外住宿、门禁/设备、有效课表与校历 P0 最小切片，且不得用 fixture 代替生产证据。

### Story 2.2：关联主体、保留历史窗口并受控重算

**type:** feature  
**dependsOn:** 2.1  
**readyWhen:** DCC/QG-1.0.0 的主体映射、历史窗口、lineage 和重算去重策略已批准  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望隔离歧义身份并安全重算有效窗口。

**需求覆盖：** FR-10; FR-13

#### AC-2.2-HAPPY

**Given** 主体映射、历史窗口、lineage 和重算去重策略获批  
**When** 数据责任人执行本 Story 的主流程  
**Then** 唯一映射关联永不复用 StudentRef，歧义隔离；修复后只重算仍有效窗口且不改写历史线索  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.3：封账批次并计算质量快照

**type:** feature  
**dependsOn:** 2.1  
**readyWhen:** 批次状态机、质量公式和截止时间已版本化  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望按批次判断数据是否可发布。

**需求覆盖：** FR-11; NFR-9

#### AC-2.3-HAPPY

**Given** 批次状态机、质量公式和截止时间已版本化  
**When** 数据责任人执行本 Story 的主流程  
**Then** 批次只允许 `receiving→sealed→quality-passed→published` 或 `receiving→sealed→quality-failed`；quality-failed 不得 published，质量快照可下钻并随候选保存  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.4：登记规则依赖并发布质量可用性事实

**type:** enabler  
**dependsOn:** 2.3  
**readyWhen:** RuleDependencyRegistry 与单调事件版本契约通过测试  
**estimate:** 3d  
**status:** planned

作为数据责任人，我希望向规则域表达 eligible、fused 与 recovering。

**需求覆盖：** FR-12（phase/enabler）; NFR-10

#### AC-2.4-HAPPY

**Given** RuleDependencyRegistry 与单调事件版本契约通过测试  
**When** 数据责任人执行本 Story 的主流程  
**Then** 乱序或间隙暂停推进并补拉；本 Story 不创建 RuleEvaluation、Candidate 或 Clue  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-2.4-COMPOSITION

**Given** DEC-012 中每个被 RuleVersion 消费的 sourceId 已映射到稳定 dependencyId，非规则消费源只保留 sourceId/purpose 合同，且 RuleVersion 声明 required/optional 与组合算子  
**When** ACC-SAFE-001 出现校门 eligible 但宿舍门禁 fused，或 ACC-SAFE-002 出现宿舍门禁/校历/课表/设备任一 required 依赖 fused/recovering/missing  
**Then** required all-of 组合不得发布 eligible；只有每个 required 依赖均 eligible 且版本连续时才发布对应 QualityEligibility=eligible  
**And** 事实保存 source/dependency version、组合算子、失败成员和水位，供 G-03 契约测试复现。

### Story 2.5a：判定质量熔断并冻结新产出

**type:** feature  
**dependsOn:** 2.4,1.9  
**readyWhen:** G-04/PIC-1.0.0 与 QG-1.0.0、场景门槛和依赖图已批准  
**estimate:** 3d  
**status:** planned

作为数据责任人，我希望在质量失败时阻止错误事实继续扩散。

**需求覆盖：** FR-12（熔断 phase）; FR-4（phase）; FR-59（phase）; BR-5; NFR-10

#### AC-2.5a-HAPPY

**Given** 场景门槛与依赖图已发布  
**When** 数据责任人执行本 Story 的主流程  
**Then** 发布 fused 事实与唯一质量异常任务 outbox，禁止新的 RuleEvaluation、Candidate 和正式线索，既有事实保持可见且不改写业务状态  
**And** 展示受影响规则/范围和 `TaskDelivery.status`；FR-4/FR-59 最终验收仍归 Story 5.5。

#### AC-2.5a-AUTH-DENIAL

**Given** 调用方不是获准的数据质量服务或数据责任人  
**When** 尝试发布或解除 fused 事实  
**Then** 服务端拒绝且不改变 QualityEligibility  
**And** 只记录最小拒绝审计，不泄露受影响学生。

#### AC-2.5a-ILLEGAL-STATE

**Given** 批次未 sealed、质量判定未完成或依赖图版本缺失  
**When** 尝试把规则标记为 fused 或 eligible  
**Then** 拒绝非法迁移且不发布可消费资格  
**And** DataBatch 与既有规则状态保持不变。

#### AC-2.5a-BOUNDARY

**Given** 质量值恰好等于已发布熔断阈值及其相邻上下样本  
**When** 执行质量门判定  
**Then** 按 DEC-012 逐源合同及 RuleVersion 引用的 `qualityGateVersion`/含边界运算符得到唯一可复现结果  
**And** 保存输入批次、公式、阈值和比较结果。

#### AC-2.5a-DEPENDENCY-FAILURE

**Given** 公共质量任务或通知适配器不可用  
**When** 质量门判定为 fused  
**Then** fused 事实仍与 outbox 原子提交并立即冻结新产出  
**And** 外部失败仅进入 `TaskDelivery.status=pending|retrying`，不伪装任务已确认。

#### AC-2.5a-IDEMPOTENCY

**Given** 同一 batchId、dependencyVersion 和 fuse businessKey 重放  
**When** 再次处理失败判定  
**Then** 返回原 fused 版本与质量任务 ID  
**And** 不重复发布事件、任务或通知。

#### AC-2.5a-CONCURRENCY

**Given** 判定期间批次水位或依赖图版本变化  
**When** 旧 aggregateVersion 提交 fused 结果  
**Then** 乐观锁拒绝并按最新水位重算  
**And** 不形成混合版本 QualityEligibility。

### Story 2.5b：执行证据化质量恢复

**type:** governance  
**dependsOn:** 2.5a  
**readyWhen:** G-03/QRP-1.0.0 与 HRAP-1.0.0 的 `quality-fuse.recover` D4 已批准  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望在完整性和样本验证后恢复规则消费。

**需求覆盖：** FR-12（恢复审批 phase）; BR-10; NFR-8

#### AC-2.5b-HAPPY

**Given** approved QualityRecoveryPolicyVersion 明确质量阈值、consecutivePassedBatches、observationDuration、样本重算集合/预期、latestActionableAt、审批角色和失败回退  
**When** 具权人员申请恢复  
**Then** 依次满足质量阈值、连续通过批次、样本重算预期、影响预览和 D4 双人复核后进入 recovering  
**And** 只保留满足 `recoveryCompletedAt ≤ latestActionableAt` 的仍可行动窗口；完成恢复晚于 latestActionableAt 的窗口直接失效且不补发，eligible 必须等待 Story 2.5c 的完整观察期。

#### AC-2.5b-AUTH-DENIAL

**Given** 申请人无 quality-fuse.recover 权限或审批过期  
**When** 提交恢复  
**Then** 返回不泄露细节的拒绝并保持 fused  
**And** 记录 actor、policyVersion、对象版本和 traceId。

#### AC-2.5b-ILLEGAL-STATE

**Given** 质量仍 failed 或规则依赖仍不合格  
**When** 尝试跳过 recovering  
**Then** 拒绝迁移且不发布 eligible  
**And** 显示缺失证据。

#### AC-2.5b-BOUNDARY

**Given** 连续通过批次数、质量值或 latestActionableAt 恰在策略边界  
**When** 执行恢复审批与样本重算  
**Then** 按 QualityRecoveryPolicyVersion 的含边界定义得到可复现结果  
**And** 任一低于或晚于允许边界的结果保持 fused。

#### AC-2.5b-DEPENDENCY-FAILURE

**Given** 样本重算、审批或公共任务依赖不可用  
**When** 恢复流程执行  
**Then** 保持 fused/recovering 且创建可见失败  
**And** 不得降低门槛继续生产。

#### AC-2.5b-IDEMPOTENCY

**Given** 同一 recoveryRequestId 重放  
**When** 再次提交  
**Then** 返回原结果且不重复审批或事件  
**And** 审计关联同一业务键。

#### AC-2.5b-CONCURRENCY

**Given** 恢复期间矩阵版本或质量水位变化  
**When** 提交最终确认  
**Then** 以乐观锁拒绝并返回最新版本  
**And** 用户必须重新预览影响。

### Story 2.5c：执行恢复观察、回退与质量任务收敛

**type:** hardening  
**dependsOn:** 2.5b,1.9  
**readyWhen:** QRP/PIC/HRAP-1.0.0 已批准；前序 2.5a/2.5b 的实现测试通过  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望恢复后的质量持续受观察并在恶化时回退。

**需求覆盖：** FR-12（恢复观察 phase）; FR-4（phase）; FR-59（phase）; NFR-8; NFR-10

#### AC-2.5c-HAPPY

**Given** 2.5b 已进入 recovering 且同一 QualityRecoveryPolicyVersion 的 observationDuration、失败回退和公共任务契约可用  
**When** 数据责任人执行本 Story 的主流程  
**Then** 连续观察满 observationDuration 且质量持续达标后进入 eligible 并关闭唯一质量任务；任一条件失败立即回到 fused 且更新原任务  
**And** 外部失败只改变 `TaskDelivery.status`，不补发失效工作；FR-4/FR-59 最终验收仍归 Story 5.5。

#### AC-2.5c-AUTH-DENIAL

**Given** 操作者不在 QualityRecoveryPolicyVersion 的最终确认角色内  
**When** 尝试结束 recovering 或关闭质量任务  
**Then** 拒绝且保持 recovering/fused 现状  
**And** 不发布 eligible 或任务完成事件。

#### AC-2.5c-ILLEGAL-STATE

**Given** observationDuration 未满、连续批次中断或样本重算未通过  
**When** 尝试从 recovering 直接进入 eligible  
**Then** 拒绝迁移；失败条件成立时回到 fused  
**And** 原质量任务保持打开并记录具体未满足条件。

#### AC-2.5c-BOUNDARY

**Given** 观察时长、连续通过批次或 latestActionableAt 恰在策略边界  
**When** 评估最终恢复资格  
**Then** 按同一 QualityRecoveryPolicyVersion 的含边界定义作出唯一结果  
**And** 超出 latestActionableAt 的窗口不补发。

#### AC-2.5c-DEPENDENCY-FAILURE

**Given** 质量任务关闭、通知或对账适配器不可用  
**When** 本地恢复判定提交  
**Then** 本地 QualityEligibility 保持真实结果且 outbox 持久重试  
**And** `TaskDelivery.status=failed` 不得回滚 eligible 或冒充外部已关闭。

#### AC-2.5c-IDEMPOTENCY

**Given** 同一 recoveryId 和最终观察水位重放  
**When** 再次提交 eligible 或 fused 结果  
**Then** 返回原状态与原质量任务更新版本  
**And** 不重复关闭任务、发布事件或补发工作。

#### AC-2.5c-CONCURRENCY

**Given** 最终提交前新批次跌破质量门或策略版本变化  
**When** 旧版本尝试进入 eligible  
**Then** 质量失败/新策略胜出并拒绝旧提交  
**And** 规则保持 fused 或重新进入受控 recovering。

### Story 2.6a：贯通全链路 traceId 与技术遥测

**type:** enabler  
**dependsOn:** 1.1c,2.3  
**readyWhen:** 日志、指标、trace 和事件字段字典已冻结  
**estimate:** 3d  
**status:** planned

作为运维人员，我希望跨 HTTP、作业、批次、评估和外部调用定位异常。

**需求覆盖：** FR-53（phase/enabler）; NFR-24

#### AC-2.6a-HAPPY

**Given** 日志、指标、trace 和事件字段字典已冻结  
**When** 运维人员执行本 Story 的主流程  
**Then** 所有关键链路使用同一 traceId，遥测不含学生明文、证据正文或密钥  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.6b：验收角色隔离运行面板、告警与隐私负例

**type:** hardening  
**dependsOn:** 2.6a,1.7  
**readyWhen:** 运维角色包、字段投影、PerformanceProfileVersion 与各生产者版本化合成契约夹具可用  
**estimate:** 5d  
**status:** planned

作为运维和数据责任人，我希望定位首次时间、最新状态和影响范围且不越权读取业务明细。

**需求覆盖：** FR-53（full）; BR-11; NFR-24; NFR-32

#### AC-2.6b-HAPPY

**Given** 运维角色包、字段投影、关键路径测试 Profile 与版本化合成契约夹具已冻结  
**When** 运维和数据责任人执行本 Story 的主流程  
**Then** 技术状态与学生对象采用不同读模型和权限，告警包含 traceId、首次时间、最新状态、影响范围和 runbook  
**And** 无权角色、敏感日志、跨角色深链和证据正文泄漏负例全部通过，且不泄露对象存在性。

### Story 2.7a：呈现依赖降级与可执行能力边界

**type:** feature  
**dependsOn:** 2.6a  
**readyWhen:** DCC/QG/QRP-1.0.0 的依赖健康合同与降级矩阵已批准  
**estimate:** 3d  
**status:** planned

作为业务用户，我希望在依赖故障时知道数据时间和仍可执行动作。

**需求覆盖：** FR-54（降级 phase）; NFR-7

#### AC-2.7a-HAPPY

**Given** 依赖健康契约与降级矩阵获批  
**When** 业务用户执行本 Story 的主流程  
**Then** 页面不把旧数据冒充实时结果，明确不可用能力并保留既有人工工作台和任务处理  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.7b：执行持久重试、幂等与恢复筛选

**type:** hardening  
**dependsOn:** 2.7a  
**readyWhen:** 幂等键、fencingToken、水位和业务时效规则已测试  
**estimate:** 5d  
**status:** planned

作为运维人员，我希望恢复后只处理仍有效且未完成的工作。

**需求覆盖：** FR-54（重试 phase）; NFR-8

#### AC-2.7b-HAPPY

**Given** 幂等键、fencingToken、水位和业务时效规则已测试  
**When** 运维人员执行本 Story 的主流程  
**Then** 持久重试按业务时效过滤，重放不产生重复线索、转介、通知或指标记账  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.7c：交付对账与生产者契约套件

**type:** enabler  
**dependsOn:** 2.7b,1.9  
**readyWhen:** 业务键、双方 ID、水位、乱序、部分失败和 fencingToken 夹具通过评审  
**estimate:** 5d  
**status:** planned

作为集成团队，我希望每个后续生产者复用同一降级、对账和恢复测试套件。

**需求覆盖：** FR-54（phase）; NFR-7; NFR-8

#### AC-2.7c-HAPPY

**Given** 候选、质量、转介、导出和指标生产者注册契约夹具  
**When** 运行重复、乱序、间隙、部分失败、超时和恢复测试  
**Then** 每个生产者按业务键与水位收敛且输出可复现对账报告  
**And** 本 enabler 不提前宣称后续生产者或 FR-54 已端到端完成。

### Story 2.8a：持续计算业务时段自然月可用性 SLI

**type:** hardening  
**dependsOn:** 2.6b  
**readyWhen:** G-01/AP-1.0.0 已批准；本 Story 负责启动并采集完整自然月 SLI  
**estimate:** 3d  
**status:** planned

作为技术负责人，我希望用连续数据证明月度可用性。

**需求覆盖：** NFR-5

#### AC-2.8a-HAPPY

**Given** approved AvailabilityPolicyVersion 冻结 Asia/Shanghai 业务时段、分子分母、计划维护、外部依赖和采样缺口  
**When** 技术负责人执行本 Story 的主流程  
**Then** 自然月 SLI 按 policyVersion 连续计算并保留原始证据，单次 DR 演练不得替代 NFR-5  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 2.8b：在预发布环境演练阶段性恢复

**type:** enabler  
**dependsOn:** 2.7b  
**readyWhen:** G-08/DRP-1.0.0 的故障域、备份/WAL、密钥、演练环境和回切计划已批准  
**estimate:** 5d  
**status:** planned

作为技术负责人，我希望在 RC 前验证恢复步骤和一致性集。

**需求覆盖：** FR-54（phase/enabler）; NFR-6

#### AC-2.8b-HAPPY

**Given** G-08/DRP-1.0.0 的故障域、备份/WAL、密钥和回切计划已批准  
**When** 技术负责人执行本 Story 的主流程  
**Then** 预发布演练只记录 Epic 2 当期 Batch、QualityEligibility、outbox 与基础设施的实际 RPO/RTO、序列、水位、密钥和外部 ID 对账；最终 RC 验收归 Story 6.5  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。


## Epic 3：首个生产关怀闭环——住宿安全

### Story 3.1a：编辑规则并执行匿名正反测试

**type:** feature  
**dependsOn:** 2.1  
**readyWhen:** G-06/RC/ES/QP/BC/WVP-1.0.0 与匿名样本词典已批准；本 Story 实现不可变 RuleVersion 与样本包  
**estimate:** 5d  
**status:** planned

作为学工管理人员，我希望编辑不可变规则版本并用匿名样本证明其行为。

**需求覆盖：** FR-14（full）; NFR-25; NFR-28

#### AC-3.1a-HAPPY

**Given** G-06/RC-1.0.0 的规则 schema、受控枚举、Responsible 角色和匿名样本词典已批准  
**When** 学工管理人员执行本 Story 的主流程  
**Then** 阈值、窗口、人群、组合、排除、等级、时限和模板形成不可原位修改版本，并保存匿名正反结果；治理态只取 draft/review/approved/blocked/retired  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.1b：执行 canary 与 production 发布

**type:** feature  
**dependsOn:** 3.1a,2.4  
**readyWhen:** RC-1.0.0 与 HRAP-1.0.0 `rule.publish` D4 已批准；本 Story 负责匿名样本和发布/拒绝测试以满足规则 Release DoD  
**estimate:** 5d  
**status:** planned

作为规则治理人员，我希望规则只在证据门通过后进入 canary 或 production。

**需求覆盖：** FR-15（发布 phase）; BR-10; NFR-8; NFR-28

#### AC-3.1b-HAPPY

**Given** 匿名正反测试、依赖质量、审批和生效日期全部通过  
**When** 具权人员执行 canary 或 production 发布  
**Then** 仅当治理态 approved 时把运行态从 inactive 推进到 canary/production，并保存申请、复核、差异、范围和生效日  
**And** 质量态 eligible/fused/recovering 与治理态、运行态分栏返回且正交。

#### AC-3.1b-AUTH-DENIAL

**Given** 申请人无 rule.publish 权限或缺双人复核  
**When** 申请发布  
**Then** 默认 deny 且规则不生效  
**And** 审计 actor、policyVersion 和 traceId。

#### AC-3.1b-ILLEGAL-STATE

**Given** 规则 blocked、无生效日或依赖不合格  
**When** 尝试发布  
**Then** 拒绝状态跳转  
**And** 不得创建 RuleEvaluation。

#### AC-3.1b-BOUNDARY

**Given** 匿名样本恰在阈值、窗口开始或结束边界  
**When** 评审发布  
**Then** 结果按 RuleVersion 的含边界定义可复现  
**And** 越界样本得到相反结果。

#### AC-3.1b-DEPENDENCY-FAILURE

**Given** 质量事实、审批或目录服务不可用  
**When** 发布  
**Then** 保持原稳定版本  
**And** 禁止本地默认替代。

#### AC-3.1b-IDEMPOTENCY

**Given** 同一 publish commandId 重放  
**When** 再次提交  
**Then** 返回同一版本和事件  
**And** 不重复开放 canary。

#### AC-3.1b-CONCURRENCY

**Given** 审批后规则或矩阵版本变化  
**When** 执行发布  
**Then** 返回版本冲突并要求重新复核  
**And** 旧证据不得跨版本复用。

### Story 3.1c：回退规则并证明历史不改写

**type:** governance  
**dependsOn:** 3.1b,2.4  
**readyWhen:** RC/HRAP-1.0.0、生效日期和回退合同已批准；前序 3.1a/3.1b 通过  
**estimate:** 5d  
**status:** planned

作为规则治理人员，我希望异常时回到稳定规则版本且不改写历史。

**需求覆盖：** FR-15（full）; BR-5; BR-10; NFR-8; NFR-28

#### AC-3.1c-HAPPY

**Given** 当前规则异常且批准的稳定版本存在  
**When** 具权人员执行 rule.rollback  
**Then** 新计算使用稳定版本、运行态记录 rolled-back，并保存原因、范围、操作者和恢复验证  
**And** 历史线索仍引用原 RuleVersion；治理态、运行态和质量态三类状态保持正交。

#### AC-3.1c-AUTH-DENIAL

**Given** 无 rule.rollback 权限或审批过期  
**When** 申请回退  
**Then** 默认 deny 且当前版本不变  
**And** 审计拒绝原因。

#### AC-3.1c-ILLEGAL-STATE

**Given** 稳定版本缺失、不可用或与批准记录不一致  
**When** 尝试回退  
**Then** 拒绝状态跳转  
**And** 新计算保持安全隔离。

#### AC-3.1c-BOUNDARY

**Given** 回退恰在新版本生效边界或 canary 扩大时  
**When** 执行回退  
**Then** 按服务端时间和单调版本阻止新的异常版本计算  
**And** 在途结果明确记录实际 RuleVersion。

#### AC-3.1c-DEPENDENCY-FAILURE

**Given** 配置分发、审批服务或稳定版本探针不可用  
**When** 回退  
**Then** 保持原稳定版本  
**And** 禁止以本地默认代替。

#### AC-3.1c-IDEMPOTENCY

**Given** 同一 commandId 重放  
**When** 再次回退  
**Then** 返回同一版本和事件  
**And** 不生成重复版本。

#### AC-3.1c-CONCURRENCY

**Given** 发布和回退并发或审批后矩阵版本变化  
**When** 执行回退命令  
**Then** 版本冲突并要求重新复核  
**And** 历史版本不改写。

### Story 3.2：连接生产规则与质量门

**type:** integration  
**dependsOn:** 3.1c,2.5a  
**readyWhen:** 规则依赖和 QualityEligibility 事件契约通过乱序、间隙测试  
**estimate:** 3d  
**status:** planned

作为规则治理人员，我希望确保熔断期间不产生新评估。

**需求覆盖：** FR-12（full）; NFR-10

#### AC-3.2-HAPPY

**Given** 规则依赖和 QualityEligibility 事件契约通过乱序、间隙测试  
**When** 规则治理人员执行本 Story 的主流程  
**Then** 只有 eligible 版本可发布 RuleEvaluation，recovering 观察完成前不补发失效工作  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.3a：计算并解释个人基线

**type:** feature  
**dependsOn:** 2.2,3.1a  
**readyWhen:** RC/ES-1.0.0 的基线窗口和样本不足策略已批准  
**estimate:** 5d  
**status:** planned

作为规则治理人员，我希望展示个人历史区间和偏移。

**需求覆盖：** FR-16

#### AC-3.3a-HAPPY

**Given** 基线窗口和样本不足策略获批  
**When** 规则治理人员执行本 Story 的主流程  
**Then** 保存窗口、样本量、正常区间、当前值、偏移和版本，样本不足明确降级或不产出  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.3b：计算场景排除并治理有期限白名单

**type:** feature  
**dependsOn:** 2.1,3.3a  
**readyWhen:** DCC/QG/RC/BC/HRAP-1.0.0 的课表校历、排除和白名单合同已批准  
**estimate:** 5d  
**status:** planned

作为数据责任人，我希望在候选门禁前排除合理情形。

**需求覆盖：** FR-17（full）; FR-20（排除 phase）; BR-10

#### AC-3.3b-HAPPY

**Given** DCC/RC/BC/HRAP-1.0.0 的课表校历、排除规则和 `whitelist.create-or-change` D4 已批准  
**When** 具权 maker/checker 创建或修改有期限白名单  
**Then** 保存影响预览、对象范围、理由、起止时间、RuleVersion、matrixVersion 和批准证据，并在 CandidateAdmissionDecision 前与请假、实习、假期、校外住宿、设备故障及有效教学活动一起计算  
**And** 历史判定不改写。

#### AC-3.3b-AUTH-DENIAL

**Given** 操作者无白名单治理权限、maker 与 checker 相同或矩阵仍 pending/deny  
**When** 调用 `whitelist.create-or-change`  
**Then** 返回拒绝且不创建/修改白名单  
**And** 记录 matrixVersion、actor 和拒绝原因。

#### AC-3.3b-ILLEGAL-STATE

**Given** 白名单缺理由、结束早于开始、对象不在 RuleVersion 范围或已撤销  
**When** 尝试批准、生效或再次修改  
**Then** 返回非法状态且版本不变  
**And** 不影响任何 CandidateAdmissionDecision。

#### AC-3.3b-BOUNDARY

**Given** 事件恰在白名单、假期或教学活动的开始/结束时刻  
**When** 计算排除  
**Then** 按 BusinessCalendarVersion 与冻结的含边界词典得到唯一结果  
**And** 相邻边界样本产生预期相反结果。

#### AC-3.3b-DEPENDENCY-FAILURE

**Given** required 的校历、课表、住宿、请假、门禁设备或适用白名单版本缺失/fused/recovering  
**When** 计算受影响规则  
**Then** 规则不得被判 eligible 且不创建 RuleEvaluation 或 Candidate  
**And** 治理界面显示具体 sourceId/dependencyId 和失败水位。

#### AC-3.3b-IDEMPOTENCY

**Given** 同一 whitelistCommandId 与对象/版本重放  
**When** 再次提交  
**Then** 返回原白名单版本和审批结果  
**And** 不重复样本重算、事件或审计事实。

#### AC-3.3b-CONCURRENCY

**Given** 审批期间 RuleVersion、对象范围或白名单 aggregateVersion 变化  
**When** 旧预览提交  
**Then** 返回 409 并要求基于最新影响范围重新审批  
**And** 不产生混合版本白名单。

### Story 3.3c：固化排除与白名单版本化边界样本

**type:** hardening  
**dependsOn:** 3.3b  
**readyWhen:** RC/BC/HRAP-1.0.0 的 RuleVersion、白名单时效与含边界词典已批准  
**estimate:** 3d  
**status:** planned

作为规则治理人员，我希望正反与恰好边界样本固定排除、白名单和教学活动行为。

**需求覆盖：** FR-17（边界 hardening）; FR-20（边界 phase）; BR-12; NFR-28

#### AC-3.3c-HAPPY

**Given** 恰在白名单开始/结束、教学活动开始/结束和场景窗口边界的冻结样本  
**When** 用批准版本执行计算与重放  
**Then** 每个边界结果与词典一致，越界样本得到相反结果且保存版本和时间源  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.4：生成住宿安全门禁判定、Candidate 与唯一任务

**type:** feature  
**dependsOn:** 3.2,3.3c,1.9  
**readyWhen:** G-03/G-04/G-06 已批准；前序 Story 已使 ACC-SAFE RuleVersion 达到 approved/production/eligible  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望只接收经过质量、课表、排除和去重的住宿安全候选。

**需求覆盖：** FR-4（Candidate 生产者 phase）; FR-20（full）; BR-2; NFR-4; NFR-11

#### AC-3.4-HAPPY

**Given** ACC-SAFE governanceStatus=approved、runtimeStatus=production、qualityStatus=eligible，课表有效且排除未命中  
**When** RuleEvaluation 到达  
**Then** 对已通过质量门的 RuleEvaluation 执行排除、白名单和去重，保存 admitted 的 CandidateAdmissionDecision，再原子创建唯一 Candidate、时钟和 outbox  
**And** 在 NFR-4 窗口内确认公共任务。

#### AC-3.4-AUTH-DENIAL

**Given** 规则/服务身份无生产资格或数据质量未通过  
**When** 请求候选门禁  
**Then** 只保存授权拒绝或 QualityEligibility/fused 事实，不创建 RuleEvaluation 或 CandidateAdmissionDecision  
**And** 不发布 candidate.generated、不启动时钟或任务。

#### AC-3.4-ILLEGAL-STATE

**Given** 已通过质量门的 RuleEvaluation 命中排除、白名单或重复键  
**When** 评估到达  
**Then** 保存 rejected CandidateAdmissionDecision、版本化原因和审计，但不创建 Candidate  
**And** 不进入辅导员队列、不启动业务时钟或公共任务。

#### AC-3.4-BOUNDARY

**Given** 事件恰在有效教学活动边界或同一去重窗口  
**When** 执行门禁  
**Then** 按 BusinessCalendarVersion 和含边界规则得到唯一判定  
**And** 边界样本结果可重放。

#### AC-3.4-DEPENDENCY-FAILURE

**Given** 公共任务系统不可用  
**When** 本地 admitted 候选提交  
**Then** 本地事实提交且对应 `TaskDelivery.status=pending|retrying`  
**And** 不得冒充外部已接收。

#### AC-3.4-IDEMPOTENCY

**Given** 同一 eventId 或业务键重放  
**When** 再次执行  
**Then** 返回原 Candidate 和任务 ID  
**And** 不重置时钟。

#### AC-3.4-CONCURRENCY

**Given** 规则、质量、排除或去重水位在提交前变化  
**When** 原版本事务提交  
**Then** 乐观锁拒绝并按最新水位重算  
**And** 不产生半成品 Candidate 或旧水位 CandidateAdmissionDecision。

### Story 3.5：初审 Candidate 并原子创建正式线索

**type:** feature  
**dependsOn:** 3.4  
**readyWhen:** Candidate 状态机、合并目标约束和 Clue 原子事务已测试  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望接纳、拒绝或合并候选。

**需求覆盖：** FR-25; BR-3

#### AC-3.5-HAPPY

**Given** Candidate 待初审且当前用户有责任权限  
**When** 选择接纳  
**Then** 同一 Clue 事务创建唯一 Clue 并更新原公共任务  
**And** 原截止时钟保持。

#### AC-3.5-AUTH-DENIAL

**Given** 用户已撤权或无对象范围  
**When** 尝试接纳、拒绝或合并  
**Then** 统一拒绝且不泄露对象是否存在  
**And** 保留本地易失草稿。

#### AC-3.5-ILLEGAL-STATE

**Given** Candidate 已拒绝、合并或接纳  
**When** 提交另一终态  
**Then** 返回当前终态和 aggregateVersion  
**And** 历史不改写。

#### AC-3.5-BOUNDARY

**Given** 合并目标不存在、无权或已终态  
**When** 选择合并  
**Then** 拒绝并要求重新选择  
**And** 不创建悬空关系。

#### AC-3.5-DEPENDENCY-FAILURE

**Given** 公共任务更新失败  
**When** 本地初审提交  
**Then** 本地事实有效且对应 `TaskDelivery.status` 可见重试  
**And** 不得回滚已创建 Clue。

#### AC-3.5-IDEMPOTENCY

**Given** 同一 requestId 重放  
**When** 再次初审  
**Then** 返回原响应  
**And** 不重复创建 Clue。

#### AC-3.5-CONCURRENCY

**Given** 另一用户先完成初审  
**When** 提交旧 aggregateVersion  
**Then** 返回 409、最新操作者、时间和版本  
**And** 保留草稿用于人工比较。

### Story 3.6：展示结构化证据、分数、等级与解释

**type:** feature  
**dependsOn:** 3.5  
**readyWhen:** CTV/ES/UXB/PAB-1.0.0 已批准；本 Story 负责组件视觉与可访问性测试  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望区分事实、规则判断、排序和建议。

**需求覆盖：** FR-18; FR-26; FR-27; FR-28（提交 phase）; FR-48; NFR-23

#### AC-3.6-HAPPY

**Given** CTV/ES/UXB/PAB-1.0.0 已批准且目标组件测试环境可用  
**When** 辅导员执行本 Story 的主流程  
**Then** 展示 ruleId/version、质量、来源、排除和模板版本；personal-baseline 显示基线/变化，absolute-threshold 显示阈值/比较符，node-fact 显示节点/批次，非适用字段以 null + notApplicableReason 呈现；分数、关怀等级和身份排序分栏且使用非诊断语言  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-3.6-EXPLANATION-FEEDBACK

**Given** 解释明确分栏展示事实、规则判断和非强制建议  
**When** 用户分别提交证据足够/不足、解释清晰/不清、建议有用/无用/未使用  
**Then** 保存带 ruleVersion、templateVersion 和版本化负向原因的 ExplanationFeedback 事实  
**And** 提交失败不改变 Clue 状态、规则分数或关怀等级，并显示可重试失败。

### Story 3.7：提供工作台与可解释稳定 Top-k

**type:** feature  
**dependsOn:** 3.6  
**readyWhen:** G-06/QP-1.0.0 的容量 10、K 公式与稳定 tie-breaker 已批准  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望在容量内优先处理时效事项。

**需求覆盖：** FR-19; FR-30; FR-31; BR-9; NFR-2

#### AC-3.7-HAPPY

**Given** QP-1.0.0 的容量 10、K 公式和稳定 tie-breaker 已批准  
**When** 辅导员执行本 Story 的主流程  
**Then** 候选与正式队列分区，保存完整排序键和策略版本，按 ui.content-ready 证明 P95 不超过 2 秒  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-3.7-WORKBENCH-METRICS

**Given** 当前统计日的 workload-summary、辅导员责任范围、本人待办和 approved QueuePolicyVersion 已加载  
**When** 工作台显示候选待初审、正式线索待核实、处理中、今日已跟进、超期和今日核实容量六项数字  
**Then** 每项数字均可进入同口径清单，清单逐项对账且默认不包含他人责任学生/待办  
**And** workload-summary 显示统计日、本人范围、今日核实容量数值/来源/适用日期和 QueuePolicyVersion；缺少批准容量来源时不得生成生产 Top-k。

### Story 3.8：提交核实结果与关怀行动

**type:** feature  
**dependsOn:** 3.6  
**readyWhen:** UXB/CTV/CAC/BC/QP/TSP-1.0.0 的六类 CareAction、完成谓词、dueAt 来源、合法迁移和关怀文案已批准；普通动作不借用高风险矩阵  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望一次记录联系、核实和行动。

**需求覆盖：** FR-29（核实 phase）; FR-32（full）; BR-4

#### AC-3.8-HAPPY

**Given** CAC-1.0.0 六个 categoryCode 的 planned/in-progress/completed fixture、唯一 completionKind、匹配 evidenceRef 与 dueAt 来源均已加载  
**When** 当前责任辅导员参数化提交属实/噪声/待观察及 CONVERSATION、ACADEMIC_SUPPORT、FINANCIAL_AID_SUPPORT、SAFETY_CHECK、COLLABORATION_REFERRAL、CONTINUED_OBSERVATION  
**Then** 属实先进入处理中；前五类只有匹配 completed 动作才赋予显式已跟进资格，观察原子创建 in-progress 动作、有效 Observation 与唯一复查任务，噪声带原因关闭  
**And** 每个完成事实保存 catalog/completion/evidence/dueAt 来源与版本；Transfer 只有原 Clue 责任人确认已回填结果时才形成 `transfer-result-confirmed`，其余工单/投递状态不冒充完成。

#### AC-3.8-AUTH-DENIAL

**Given** 用户责任、代办或字段权限已失效  
**When** 提交核实结果或关怀动作  
**Then** 服务端默认 deny 且不泄露对象存在性  
**And** 未提交内容只保留为当前进程易失草稿。

#### AC-3.8-ILLEGAL-STATE

**Given** Clue 已关闭，请求跳过处理中/Observation 守卫，category/version/completionKind 未知，evidenceRef 不匹配，或用 transfer submit/接收/delivery confirmed/关闭冒充动作完成  
**When** 提交状态迁移  
**Then** 返回当前状态、合法后继集合或 `CARE_ACTION_NOT_ALLOWED`  
**And** 不创建动作、不迁移 Clue，不修改分数、等级、dueAt 或业务时钟。

#### AC-3.8-BOUNDARY

**Given** 必填字段恰为空/达长度上限，completedAt 恰等于或晚于 dueAt，或 Observation reviewAt 恰为开始后 1/30 自然日  
**When** 服务端校验  
**Then** 等于 dueAt 计按时、晚于 dueAt 仍可具权完成但永久 wasOverdue；1/30 日边界允许、范围外拒绝，工作日按 BC 计算  
**And** 客户端与 API 使用同一错误码/字段定位，责任转移、代办与重放均不重置 dueAt。

#### AC-3.8-DEPENDENCY-FAILURE

**Given** CAC、BC、QP、TSP 或完成证据依赖不可用，或公共任务/通知非关键下游不可用  
**When** 本地核实与动作事务提交  
**Then** 无法确定类别/dueAt/完成谓词时 fail closed；仅非关键投递失败时本地合法事实保持有效且 `TaskDelivery.status` 可见重试  
**And** 不使用默认目录/截止，不宣告动作或下游成功。

#### AC-3.8-IDEMPOTENCY

**Given** 同一 commandId 重放  
**When** 再次提交核实或动作  
**Then** 返回原结果  
**And** 不重复状态迁移、任务或通知。

#### AC-3.8-CONCURRENCY

**Given** 另一端已提交更新  
**When** 基于旧 aggregateVersion 提交  
**Then** 返回 409、最新操作者、时间和版本  
**And** 保留易失草稿供人工比较。

### Story 3.9a：聚合同类证据并生成唯一观察复查

**type:** feature  
**dependsOn:** 3.8  
**readyWhen:** CAC/BC-1.0.0 的 Observation 单一 clueId 所有权、`[startsAt,reviewAt)`、1—30 日边界、`clueId+catalogVersion+startsAt+reviewAt` 唯一键、successor 和复查 taskKey 已版本化  
**estimate:** 3d  
**status:** planned

作为辅导员，我希望持续观察而不复制线索。

**需求覆盖：** FR-29（观察 phase）; FR-33（full）

#### AC-3.9a-HAPPY

**Given** CAC/BC-1.0.0、一个当前可见未终态 clueId、有效 `[startsAt,reviewAt)` Observation、CONTINUED_OBSERVATION in-progress 动作和唯一 `clueId+observationId+reviewAt` taskKey  
**When** 期内/恰在 reviewAt 的新证据到达、到期复查或辅导员延长  
**Then** Observation 只绑定所选 Clue，期内证据聚合该 Clue，边界证据进入复查；到期只生成一个任务且不自动关闭，延长完成旧 Observation 并原子创建 successor 与新唯一 taskKey  
**And** 复查仅可选择关闭、延长或回到处理中，保留历史超期、catalogVersion 和全部窗口链。

#### AC-3.9a-AUTH-DENIAL

**Given** 操作者不是当前责任人且无有效 DelegationGrant  
**When** 尝试设置/延长观察或完成复查  
**Then** 拒绝且不改变 Clue 或复查任务  
**And** 不泄露新增证据内容。

#### AC-3.9a-ILLEGAL-STATE

**Given** Clue 已关闭/合并，或当前状态不允许进入待观察  
**When** 提交观察命令  
**Then** 返回当前终态与 409，状态不变  
**And** 不创建复查任务。

#### AC-3.9a-BOUNDARY

**Given** 新证据或调度时钟恰在观察窗口结束时刻  
**When** 聚合证据并触发复查  
**Then** `[startsAt,reviewAt)` 只归属旧窗口，恰在 reviewAt 的证据只归属复查阶段  
**And** 1/30 日边界可复现，范围外拒绝且每个 Observation 最多一个可对账复查 taskKey。

#### AC-3.9a-DEPENDENCY-FAILURE

**Given** 公共任务或通知适配器不可用  
**When** 本地观察到期  
**Then** Clue 与唯一复查 outbox 原子提交  
**And** `TaskDelivery.status=pending|retrying`，不伪装外部已创建。

#### AC-3.9a-IDEMPOTENCY

**Given** 同一 observationId、证据 eventId 或到期调度重放  
**When** 再次聚合/触发  
**Then** 返回原 Clue 与复查 taskKey  
**And** 不重复证据、任务或提醒。

#### AC-3.9a-CONCURRENCY

**Given** 另一端已关闭 Clue 或更新观察窗口  
**When** 旧 aggregateVersion 提交到期结果  
**Then** 乐观锁拒绝并按最新状态重算  
**And** 关闭终态胜出且无孤儿复查任务。

### Story 3.9b：消费权威责任变化并转移未完成事项

**type:** integration  
**dependsOn:** 1.6c,3.8  
**readyWhen:** G-02/ISP/RFP/DCC-1.0.0 的责任关系、来源版本、接收人和异常队列合同已批准  
**estimate:** 3d  
**status:** planned

作为原/新责任辅导员，我希望权威责任关系变化后未完成事项自动、可追溯地转移，而不被误作临时代办。

**需求覆盖：** FR-34; BR-7

#### AC-3.9b-HAPPY

**Given** 权威源发布更高 sourceVersion 的 `responsibility.changed` 且新责任人有效  
**When** 平台消费该不可变事实  
**Then** 未完成 Candidate、Clue 和同一 workItemKey 转给新责任人，保存旧/新责任人、来源版本、生效时间和原截止，并通知双方  
**And** 不创建 DelegationGrant、不重置时钟，已关闭对象只保留历史责任链。

#### AC-3.9b-AUTH-DENIAL

**Given** 事件来自未受信适配器、签名/来源版本无效或超出组织契约  
**When** 尝试变更权威责任  
**Then** 拒绝并审计来源  
**And** 原责任、授权、任务和截止不变。

#### AC-3.9b-ILLEGAL-STATE

**Given** 新责任人失效、无有效接收人，或事件试图把 DelegationGrant 当权威关系  
**When** 应用责任变化  
**Then** 不设置隐式接收人，未完成事项进入学院异常队列  
**And** 终态 Clue 不重新打开、不改写历史。

#### AC-3.9b-BOUNDARY

**Given** 责任变化恰在截止、工作日或原关系失效边界  
**When** 计算新责任与时效  
**Then** 原截止和曾超期事实保持不变  
**And** 使用 approved BusinessCalendarVersion、sourceEffectiveAt 和服务端时间。

#### AC-3.9b-DEPENDENCY-FAILURE

**Given** 权威身份、责任目录或公共任务依赖不可用  
**When** 消费责任变化  
**Then** 持久重试且不提交部分转移；无法确定接收人时进入异常队列  
**And** 不把未知接收人设为责任人或提前关闭原任务。

#### AC-3.9b-IDEMPOTENCY

**Given** 同一 eventId/sourceVersion 重放  
**When** 再次消费  
**Then** 返回原转移事实  
**And** 不重复更新任务或通知。

#### AC-3.9b-CONCURRENCY

**Given** 多个责任变化或撤权事实乱序到达  
**When** 按来源版本和 aggregateVersion 仲裁  
**Then** 只应用最新合法关系，旧版本被拒绝并记录水位  
**And** 历史责任链完整保留。

### Story 3.9c：授予、撤销并执行有期限授权代办

**type:** governance  
**dependsOn:** 1.7,3.8  
**readyWhen:** RFP/HRAP-1.0.0 的代办授权人、动作、对象/字段范围、时区和 D4 审批已批准  
**estimate:** 5d  
**status:** planned

作为具权人员，我希望在不改变权威责任人的前提下委托指定动作。

**需求覆盖：** FR-62; BR-7

#### AC-3.9c-HAPPY

**Given** draft DelegationGrant 的授权人具权、受托人有效，对象/动作/字段范围是 approved RoleFieldPolicyVersion 与授权人当前权限的最小交集，且 temporary-grant.issue 通过当前 HighRiskActionPolicy  
**When** draft 进入 pending-approval 并由具权人员批准为 active  
**Then** 保存 grantId、对象、允许动作、允许字段投影、RoleFieldPolicyVersion、起止、理由、审批版本和 matrixVersion  
**And** 原责任人和截止时钟不变，代办动作审计实际操作者、权威责任人和 grantId。

#### AC-3.9c-PENDING-CANCEL

**Given** DelegationGrant 处于 pending-approval  
**When** 具权申请人取消并提供 reason  
**Then** 进入 cancelled 终态并记录 actor/reason  
**And** 幂等取消不重复生成事件且永不形成 active 权限。

#### AC-3.9c-AUTH-DENIAL

**Given** 授权人无 grant 权限、受托人无基础角色、请求字段/动作越过 RFP-1.0.0，或 actionType 未映射/运行测试未通过/执行批准过期  
**When** 创建、批准或使用代办  
**Then** temporary-grant.issue 实际 D0/deny 且不得进入 active  
**And** 历史深链不得读取对象，越界字段的键、值和长度均不返回。

#### AC-3.9c-ILLEGAL-STATE

**Given** 请求动作超出 grant、责任关系失效，或 grant 已 expired/revoked/cancelled  
**When** 执行代办动作或尝试非法状态迁移  
**Then** 返回明确拒绝且状态不变  
**And** 下一次请求立即失权并记录拒绝审计。

#### AC-3.9c-BOUNDARY

**Given** active grant 的请求发生在开始时刻、结束时刻或结束后  
**When** 按批准时区和服务端时钟校验时效  
**Then** 开始边界按 policy 判定；达到结束边界时原子执行 active→expired 并只发布一个过期事件，结束后所有代办动作拒绝  
**And** 过期不重置业务时钟，幂等调度不重复事件。

#### AC-3.9c-DEPENDENCY-FAILURE

**Given** HighRiskActionPolicy、RoleFieldPolicyVersion、时钟、审批或责任版本不可用  
**When** 校验 temporary-grant.issue/revoke 或代办动作  
**Then** fail closed  
**And** 不得缓存扩大权限。

#### AC-3.9c-IDEMPOTENCY

**Given** active grant 首次收到具权 temporary-grant.revoke 及其幂等重放  
**When** 授权人、当前权威责任人或权限管理员用批准原因撤销  
**Then** 首次立即进入 revoked 并产生一个不可变事件，重放返回当前 revoked 状态  
**And** 不重复发事件；other 原因必须包含说明，保存执行时 matrixVersion。

#### AC-3.9c-CONCURRENCY

**Given** 撤销和代办动作或两个批准基于同一旧版本并发  
**When** 以 aggregateVersion 仲裁  
**Then** 仅一个命令成功，其余返回 409 与最新状态  
**And** 撤销一经生效，所有后续动作立即失败且历史事件不可变。

### Story 3.9d：完成关怀闭环与 FR-4 待办生命周期

**type:** feature  
**dependsOn:** 3.9a,3.9b,3.9c  
**readyWhen:** 业务状态、超期、质量和责任维度的正交模型及公共任务状态契约通过测试  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望在观察、转移和代办中保持同一 Clue 闭环并更新原公共任务。

**需求覆盖：** FR-29（full）; FR-4（phase）; BR-3; BR-4; BR-5; BR-8

#### AC-3.9d-HAPPY

**Given** 业务状态、超期、质量、责任和公共任务状态契约通过迁移测试  
**When** 辅导员执行本 Story 的主流程  
**Then** 合法状态迁移不重置截止；已跟进要求 CAC completed 动作，待观察要求有效 Observation/in-progress 动作/唯一任务，关闭为有守卫终态；更正只追加新事实并按业务键更新/关闭原公共任务  
**And** 外部失败只改变 `TaskDelivery.status`；FR-4 跨生产者最终验收仍归 Story 5.5。

#### AC-3.9d-AUTH-DENIAL

**Given** 用户无当前 Clue 的责任、协同或有效代办权限  
**When** 提交状态迁移  
**Then** 默认 deny  
**And** 不修改业务状态、时钟或公共任务。

#### AC-3.9d-ILLEGAL-STATE

**Given** 请求从待核实直接到已跟进、处理中直接关闭、从终态回开，或无匹配 CAC completed 动作/有效 Observation 即迁移  
**When** 提交迁移  
**Then** 拒绝并返回合法后继状态；待观察到期不得自动关闭，必须由复查命令选择关闭/延长/回处理中  
**And** 质量 fused、动作超期或业务超期不得被误作业务状态。

#### AC-3.9d-BOUNDARY

**Given** 当前时间恰在首次核实、观察复查或工作日截止边界  
**When** 计算超期和迁移  
**Then** 按 BusinessCalendarVersion 独立更新超期标记  
**And** 业务状态、证据质量和原截止均不被重置。

#### AC-3.9d-DEPENDENCY-FAILURE

**Given** 公共任务或通知依赖不可用  
**When** 本地合法迁移提交  
**Then** 本地 Clue 事实有效且对应 `TaskDelivery.status` 可见重试  
**And** 不宣告外部任务已更新。

#### AC-3.9d-IDEMPOTENCY

**Given** 同一 transition commandId 重放  
**When** 再次提交  
**Then** 返回原状态与事件  
**And** 不重复任务、提醒或时钟变更。

#### AC-3.9d-CONCURRENCY

**Given** 观察、转移或关闭基于同一旧版本并发  
**When** 服务端仲裁  
**Then** 仅一个命令成功，其余返回最新操作者、时间和 aggregateVersion  
**And** 所有草稿可人工比较且历史不覆盖。

### Story 3.10：展示任务最小档案与真实时间线

**type:** feature  
**dependsOn:** 3.6,1.8  
**readyWhen:** 字段投影、来源时间和事件分类可用  
**estimate:** 3d  
**status:** planned

作为关怀人员，我希望理解上下文且不固化噪声。

**需求覆盖：** FR-35（full）; FR-36（当前事实 phase）

#### AC-3.10-HAPPY

**Given** 字段投影、来源时间和事件分类可用  
**When** 关怀人员执行本 Story 的主流程  
**Then** 只显示当前已存在的机器信号、人工核实和关怀行动及来源更新时间，噪声不成为属实历史  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.11：提供学院运行总览与授权下钻

**type:** feature  
**dependsOn:** 3.7,3.10  
**readyWhen:** MPP/RFP/UXB-1.0.0 的指标定义、授权范围与等价表格已批准  
**estimate:** 5d  
**status:** planned

作为学院管理人员，我希望定位本学院积压和超期。

**需求覆盖：** FR-43; BR-11; NFR-3

#### AC-3.11-HAPPY

**Given** 指标定义、授权范围与等价表格获批  
**When** 学院管理人员执行本 Story 的主流程  
**Then** 汇总与明细对账并显示口径、周期、截止和质量，禁止越权下钻，筛选到 ui.content-ready P95 不超过 3 秒  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.12：发起并跟踪学院督办闭环

**type:** feature  
**dependsOn:** 3.11,1.9  
**readyWhen:** G-04/PIC/TSP-1.0.0 的督办责任、截止和公共任务合同已批准  
**estimate:** 3d  
**status:** planned

作为学院管理人员，我希望把临期、超期和无责任事项转为可跟踪督办。

**需求覆盖：** FR-44

#### AC-3.12-HAPPY

**Given** 督办责任、截止和公共任务契约可用  
**When** 学院管理人员执行本 Story 的主流程  
**Then** 针对一个临期、超期或无责任事项创建唯一持久督办；无接收人进入异常队列，重复提交不制造第二个任务  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.13：生成当前事实基础周报

**type:** feature  
**dependsOn:** 3.11  
**readyWhen:** metricId、口径版本、周期与截止已冻结  
**estimate:** 3d  
**status:** planned

作为学院管理人员，我希望按统一口径复盘当前闭环。

**需求覆盖：** FR-46（基础周报 phase）; NFR-26

#### AC-3.13-HAPPY

**Given** metricId、口径版本、周期与截止已冻结  
**When** 学院管理人员执行本 Story 的主流程  
**Then** 截止后 2 小时内生成新增、闭环、按时、噪声、超期和质量；缺少协同事实不得伪造零值  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.14a：提交导出申请、审批与最小范围快照

**type:** feature  
**dependsOn:** 1.8,3.11  
**readyWhen:** RFP/RS/HRAP-1.0.0 与 `sensitive-export.create` D3 已批准  
**estimate:** 3d  
**status:** planned

作为授权用户，我希望异步申请最小必要导出。

**需求覆盖：** FR-6（导出 phase）; FR-7（导出 phase）; NFR-3

#### AC-3.14a-HAPPY

**Given** approved RoleFieldPolicyVersion、RetentionScheduleVersion、导出分类及 sensitive-export.create 门禁齐备  
**When** 授权用户执行本 Story 的主流程  
**Then** 所有报表和明细导出只创建异步申请，保存 matrixVersion、审批、范围、权限快照、文件期限、保留版本和审计  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-3.14a-MATRIX-DENY

**Given** `sensitive-export.create` 未映射/运行测试未通过，或 RFP/RS policyVersion 缺失/漂移  
**When** 请求 sensitive-export.create  
**Then** 实际 D0/deny，不创建 ExportJob、不生成文件  
**And** 显示 matrixVersion/deny reason，输入只保留为当前进程草稿。

#### AC-3.14a-AUTH-DENIAL

**Given** 用户无对象/字段/导出动作权限，或审批人与申请人职责冲突  
**When** 请求 `sensitive-export.create`  
**Then** 服务端拒绝且不泄露对象存在性  
**And** 不创建 ExportJob、审批或文件占位。

#### AC-3.14a-ILLEGAL-STATE

**Given** 范围为空/越界、导出分类缺失、审批已过期或 RetentionScheduleVersion 失效  
**When** 尝试提交或批准导出  
**Then** 返回非法状态并保留当前进程草稿  
**And** 不持久化可执行作业。

#### AC-3.14a-BOUNDARY

**Given** 对象、字段、时间窗或文件期限恰在批准范围边界  
**When** 计算最小申请快照  
**Then** 只纳入 RoleFieldPolicyVersion 和 RetentionScheduleVersion 共同允许的边界项  
**And** 越界相邻样本被拒绝并可复现。

#### AC-3.14a-DEPENDENCY-FAILURE

**Given** 审批、字段策略、保留策略或审计提交依赖不可用  
**When** 创建导出申请  
**Then** fail closed 且不创建 ExportJob  
**And** 当前进程草稿不包含不可见字段或服务端返回数据。

#### AC-3.14a-IDEMPOTENCY

**Given** 同一 exportRequestId 与范围快照重放  
**When** 再次提交  
**Then** 返回原申请/审批 ID 和状态  
**And** 不重复创建作业、审批或审计事实。

#### AC-3.14a-CONCURRENCY

**Given** 申请预览后授权、对象版本、保留策略或矩阵版本变化  
**When** 旧快照提交  
**Then** 返回 409/门禁拒绝并要求重新预览  
**And** 不创建混合版本申请。

### Story 3.14b：异步生成字段投影文件并呈现作业状态

**type:** feature  
**dependsOn:** 3.14a  
**readyWhen:** RFP/RS-1.0.0 已批准，前序 1.8/3.14a 的字段投影与导出作业测试通过  
**estimate:** 5d  
**status:** planned

作为授权用户，我希望异步生成最小字段文件并准确看到 queued、running、succeeded、failed 或 cancelled 状态。

**需求覆盖：** FR-6（导出 phase）; FR-7（导出 phase）; NFR-14; NFR-16

#### AC-3.14b-HAPPY

**Given** RoleFieldPolicyVersion、RetentionScheduleVersion、审批和当前授权投影测试通过  
**When** 异步 worker 生成文件或发生依赖失败  
**Then** 生成采用申请快照与当前授权交集并单调更新作业状态，失败可重试但不得伪装完成  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 3.14c：下载重检、撤销、更正失效与全程审计

**type:** hardening  
**dependsOn:** 3.14b,1.4  
**readyWhen:** RFP/RS/HRAP-1.0.0 的 download D2、撤权水位、receipt schema 和重试合同已批准  
**estimate:** 5d  
**status:** planned

作为安全负责人，我希望让撤权后的作业和文件立即失效。

**需求覆盖：** FR-6（full）; FR-7（full）; FR-8（导出 contributor）

#### AC-3.14c-HAPPY

**Given** 导出完成、RetentionScheduleVersion 仍有效、用户仍有当前对象与字段权限且 sensitive-export.download 门禁通过  
**When** 请求下载  
**Then** 按当前授权与申请快照交集返回有效文件  
**And** 记录 matrixVersion、下载审计和保留版本。

#### AC-3.14c-AUTH-DENIAL

**Given** 用户撤权、换号、字段策略收窄、RS policyVersion 缺失/漂移，或 download actionType 未验证/执行时 deny  
**When** 请求下载  
**Then** 文件立即失效并统一拒绝  
**And** 不得返回键、长度或旧签名链接。

#### AC-3.14c-ILLEGAL-STATE

**Given** 作业 failed、revoked、expired 或 deletion-pending  
**When** 请求下载  
**Then** 拒绝非法状态  
**And** 显示可操作恢复路径。

#### AC-3.14c-BOUNDARY

**Given** 文件恰到 expiresAt 或 retention 删除边界  
**When** 请求下载  
**Then** 按批准时区和含边界策略执行  
**And** 生成 DeletionReceipt 时不得再下载。

#### AC-3.14c-DEPENDENCY-FAILURE

**Given** 对象存储、密钥、审计或授权服务不可用  
**When** 生成或下载  
**Then** fail closed 并保持可重试状态  
**And** 不得伪装完成。

#### AC-3.14c-IDEMPOTENCY

**Given** 同一 exportRequestId 重放  
**When** 再次创建  
**Then** 返回原作业  
**And** 不重复生成文件。

#### AC-3.14c-CONCURRENCY

**Given** 生成期间发生撤权  
**When** 作业提交文件  
**Then** 撤权水位胜出且文件不可下载  
**And** 保留双方水位和审计。


## Epic 4：多场景关怀与受治理学生上下文

### Story 4.1a：完成首期多场景规则资格与发布门禁

**type:** governance  
**dependsOn:** 3.1c,2.4  
**readyWhen:** DEC-005/RC/DCC/QG/QRP/HRAP-1.0.0 已批准；ECON-012、NIGHT-001、ACADEMIC-001 的公式、窗口、排除、逐源合同、匿名边界包和 `rule.publish` D4 输入可执行  
**estimate:** 8d  
**status:** planned

作为规则治理人员，我希望让经济、夜间作息和学业三条首期规则各自经过可复现资格、canary 与 production 门禁，避免场景 Story 依赖无人负责的运行前置。

**需求覆盖：** FR-21（发布 phase）; FR-23（发布 phase）; FR-57（发布 phase）; BR-10; NFR-28

#### AC-4.1a-HAPPY

**Given** ECON-012、NIGHT-001、ACADEMIC-001 均为 governanceStatus=approved，具名 owner/批准/生效证据、匿名正反与等值样本齐备，且各自 required 依赖均 qualityStatus=eligible  
**When** 不同自然人 maker/checker 逐条执行 `rule.publish`，先完成受限 canary、容量/噪声/质量观察和影响复核，再申请 production  
**Then** 每条规则独立保存决策快照、RuleVersion、依赖/质量水位、canary 范围与结果、matrixVersion 和 production 生效事实；只有该规则全部门通过时才进入 runtimeStatus=production  
**And** 4.1b、4.2、4.3 分别只在其绑定规则同时为 approved/production/eligible 后允许生成生产 RuleEvaluation/Candidate，单条失败不伪装为其他规则失败或成功。

#### AC-4.1a-AUTH-DENIAL

**Given** 申请人无规则治理权限、maker/checker 不是不同自然人、审批过期，或 `rule.publish` 的 actionType/运行测试/执行时授权未通过  
**When** 尝试批准、canary 或 production 任一规则  
**Then** 该动作 D0/deny，目标规则保持原 governance/runtime/quality 状态且其他规则不受影响  
**And** 不创建灰度资格、RuleEvaluation、Candidate 或成功证据。

#### AC-4.1a-ILLEGAL-STATE

**Given** 目标规则任一公式/量纲/阈值/排除顺序/依赖合同/owner/日期/evidenceUri 缺失，或 governanceStatus≠approved、qualityStatus≠eligible  
**When** 尝试批准或发布  
**Then** 该候选版本保持 blocked/review 或 inactive/fused，并按 ruleId 列出逐字段缺口；已批准 RC-1.0.0 不被原位改写  
**And** 不允许用 fixture、口头确认或另一规则的证据替代生产资格。

#### AC-4.1a-BOUNDARY

**Given** 三条规则各自最低样本、窗口、持续期、阈值、时间边界及相邻样本  
**When** 按 RC-1.0.0 执行匿名正反/等值/异常验收与 canary 资格计算  
**Then** 每个样本按所属 RuleVersion 的量纲、比较运算符和含边界语义得到唯一结果  
**And** 输入、期望、实际、RuleVersion、qualityGateVersion 与时间源均可重放。

#### AC-4.1a-DEPENDENCY-FAILURE

**Given** 任一目标规则的 required source/dependency 缺 owner/SLO/质量门/契约证据，或状态为 missing/fused/recovering  
**When** 评审该规则 canary/production 资格  
**Then** 仅该规则保持 qualityStatus=fused/recovering 且 runtime-ineligible，不生成其生产 RuleEvaluation/Candidate  
**And** 系统不得用默认值、optional 源或其他规则的 eligible 状态替代失败依赖。

#### AC-4.1a-IDEMPOTENCY

**Given** 同一 ruleId、RuleVersion、decisionId/publish commandId 和证据水位重放  
**When** 再次提交批准、canary 或 production  
**Then** 返回原决策快照、发布阶段和事件  
**And** 不重复批准、开放 cohort、发布事件或生效记录。

#### AC-4.1a-CONCURRENCY

**Given** 评审/发布期间公式、依赖水位、RuleVersion、matrixVersion 或 canary 证据发生变化  
**When** 基于旧 aggregateVersion 提交  
**Then** 返回 409 并要求仅对目标 ruleId 重新执行边界包与影响预览  
**And** 不形成混合版本 RuleVersion 或跨规则复用证据。

### Story 4.1b：端到端验收隐性经济压力变化

**type:** feature  
**dependsOn:** 4.1a,3.5  
**readyWhen:** G-03/G-06 已批准；前序 4.1a 已产出 ECON-012 的 approved/production/eligible 与 canary/发布证据  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望审慎核实相对本人历史的消费变化。

**需求覆盖：** FR-21

#### AC-4.1b-HAPPY

**Given** ECON-012 governanceStatus=approved、runtimeStatus=production、qualityStatus=eligible 且批准/生效证据齐备  
**When** 满足正式规则窗口  
**Then** 生成可初审候选并展示餐均、历史区间、样本、下降和排除  
**And** 使用中性非诊断文案。

#### AC-4.1b-AUTH-DENIAL

**Given** ECON Candidate 已存在，但请求人不是当前责任辅导员、无有效 DelegationGrant，或字段授权已撤销/过期  
**When** 对经济对象请求 RFP 的 `care.read` 或执行 `candidate.review` 初审  
**Then** 服务端以不泄露对象存在性的统一语义拒绝，Candidate、公共任务和 ECON-012 治理/运行/质量状态均不改变  
**And** 只保存最小拒绝审计；`rule.publish` 权限与拒绝路径仅由 Story 4.1a 验收。

#### AC-4.1b-ILLEGAL-STATE

**Given** 质量 fused 或规则停用  
**When** 新事件到达  
**Then** 不生成新事实  
**And** 既有候选只显示质量状态。

#### AC-4.1b-BOUNDARY

**Given** 下降幅度、样本量或结算时间恰在阈值  
**When** 计算规则  
**Then** 按冻结版本的含边界样本得出结果  
**And** 结果可由样本复现。

#### AC-4.1b-DEPENDENCY-FAILURE

**Given** 消费、请假、校历或离校/校外状态任一 required 数据依赖缺失、fused 或 recovering  
**When** 执行规则  
**Then** QualityEligibility 保持 fused/recovering，不生成 RuleEvaluation、Candidate 或公共任务 outbox  
**And** 不得用默认值生产。

#### AC-4.1b-TASK-DELIVERY-FAILURE

**Given** 数据与业务门均已通过，但公共任务适配器不可用  
**When** admitted Candidate 与任务 outbox 在本地事务提交  
**Then** Candidate 保持真实已提交状态，对应 `TaskDelivery.status=pending|retrying`  
**And** 不显示外部任务已确认，也不重复 Candidate。

#### AC-4.1b-IDEMPOTENCY

**Given** 同一学生规则窗口重算  
**When** 再次计算  
**Then** 不重复 Candidate  
**And** 保留 lineage。

#### AC-4.1b-CONCURRENCY

**Given** 规则版本在计算和提交间变化  
**When** 提交结果  
**Then** 旧版本事务拒绝或明确归属原版本  
**And** 不得混合版本证据。

### Story 4.2：识别隐私保护的夜间作息变化

**type:** feature  
**dependsOn:** 4.1a,3.5  
**readyWhen:** 前序 4.1a 已产出 NIGHT-001 的 approved/production/eligible 与 canary/发布证据，最小化网络汇总合同通过  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望用最小化汇总识别持续变化。

**需求覆盖：** FR-23; BR-11

#### AC-4.2-HAPPY

**Given** NIGHT-001 approved/production/eligible，前 28 个 eligible nights 中基线有效夜≥14，当前 7 夜中恰有 4 夜活动≥180 分钟且为基线≥2.0 倍，排除均不命中  
**When** 消费仅含会话时间、时长、流量和接入区域的最小聚合并执行规则与 CandidateAdmissionDecision  
**Then** 产生唯一 RuleEvaluation、admitted Candidate 与任务 outbox，EvidenceSnapshot 显示个人基线、4/7、时长/倍数、窗口、排除、质量和 RuleVersion  
**And** 不采集或输出 URL、domain、app、search、chat、报文内容或原始网络标识，使用中性非诊断文案。

#### AC-4.2-BOUNDARY-NON-MATCH

**Given** 参数化 fixture 分别为恰 4/7、180 分钟、2.0 倍、14 个基线夜，以及 3/7、179m59s、低于 2.0、13 个基线夜、单夜或任一批准排除命中  
**When** 按 NIGHT-001 含边界语义计算  
**Then** 四个等值门同时满足才命中；任一非匹配 fixture 均不产生 RuleEvaluation、Candidate 或任务  
**And** 每个输入、预期、实际、时区、RuleVersion 和 qualityGateVersion 可独立重放。

#### AC-4.2-PRIVACY-FAIL-CLOSED

**Given** 网络批次或 schema 出现 URL、domain、应用、搜索、聊天、报文内容或其他禁止字段  
**When** 合同校验或规则消费  
**Then** 拒绝该批次并使 NIGHT-001 对应质量资格 fused，不创建新 RuleEvaluation/Candidate/Clue  
**And** 审计只记录禁止字段 code/count/hash，不复制敏感内容。

#### AC-4.2-DEPENDENCY-AUTH-FAILURE

**Given** NETWORK/LEAVE/CALENDAR/OFFCAMPUS 任一 required dependency missing/fused/recovering，或请求人无当前 Candidate 对象权限  
**When** 计算规则或读取结果  
**Then** 依赖失败不评估、不用默认值；越权使用与不存在相同的拒绝 envelope  
**And** 既有对象只显示当前具权质量摘要，不泄露禁止字段或不可见 Candidate 存在性。

#### AC-4.2-IDEMPOTENCY-CORRECTION

**Given** 同一学生、NIGHT-001、窗口和 source watermark 重放，或权威源随后发布更正/撤销  
**When** 重算并提交  
**Then** 同一去重键只产生一次 Evaluation/Candidate；更正产生带 supersedes/lineage 的新 Evaluation 并触发现有对象复核  
**And** 历史事实不原位改写，不重复公共任务或通知。

### Story 4.3：生成学业成长关怀候选

**type:** feature  
**dependsOn:** 4.1a,2.1,3.5  
**readyWhen:** 前序 4.1a 已产出 ACADEMIC-001 的 approved/production/eligible 与 canary/发布证据，DCC/BC-1.0.0 学业批次与日历合同通过  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望按关键学业节点收到可追溯候选。

**需求覆盖：** FR-57; BR-12

#### AC-4.3-HAPPY

**Given** ACADEMIC-001 approved/production/eligible，批次 sealed/effective，且 `careReviewRequired=true` 或 nodeCode 命中 ACN-1.0.0 的期中、期末、缓考后、课程完成、体测、论文或毕业关怀 code  
**When** 对七类参数化节点执行 node-fact 规则和 CandidateAdmissionDecision  
**Then** 每个 affirmative 事实只生成一个可初审 Candidate，保存 term、batchVersion、nodeCode、RuleVersion、calculatedAt 与质量快照  
**And** 个人变化、同群参照和身份排序调整分栏展示，不从分数/排名自行推断关怀结论。

#### AC-4.3-NON-MATCH-BOUNDARY

**Given** fixture 分别为未封账、撤销、重复、未知/非 allowlist、缓考进行中，以及缓考后新 sealed 批次  
**When** 按 ACADEMIC-001 与 ACN-1.0.0 评估  
**Then** 前五类均不触发；缓考后 sealed/effective 且命中 careReview 条件的新批次可触发  
**And** 批次/节点/生效边界、拒绝原因和预期结果可复现。

#### AC-4.3-DEPENDENCY-AUTH-FAILURE

**Given** ACADEMIC/TIMETABLE/CALENDAR 合同或质量资格缺失/fused/recovering，或请求人无当前 Candidate 对象/字段权限  
**When** 计算或读取学业关怀结果  
**Then** 依赖失败不生成 RuleEvaluation/Candidate，越权不泄露对象或敏感学业字段存在性  
**And** 不以空值、旧批次、同群排名或默认日历替代。

#### AC-4.3-IDEMPOTENCY-CORRECTION

**Given** 相同 `studentId+nodeCode+term+batchVersion` 重放，或 sealed 批次发布更正/撤销  
**When** 再次评估并提交  
**Then** 同一键只产生一次 Candidate；更正以 superseding fact 与新批次版本重算并触发复核  
**And** 不原位覆盖历史、不重复任务，也不把不同批次误作同一事实。

### Story 4.4a：定义版本化专项与参与规则

**type:** enabler  
**dependsOn:** 3.1c  
**readyWhen:** SPM/BC/QP/RFP-1.0.0 的三类专项、人群、半开窗口、required/optional、academicCalendarProjection schema/owner/质量门、说明、排序与可见性已批准  
**estimate:** 3d  
**status:** planned

作为学工管理人员，我希望从已交付规则组合期限专项。

**需求覆盖：** FR-24（phase/enabler）; BR-12

#### AC-4.4a-HAPPY

**Given** SPM-1.0.0 三类专项的人群、`[startAt,endAt)`、required/optional、由 ACADEMIC+CALENDAR 源生成的 academicCalendarProjection schema/owner/质量门、说明、排序和 BusinessCalendarVersion 获批  
**When** 学工管理人员执行本 Story 的主流程  
**Then** 专项只建只读投影并引用既有规则/Candidate/Clue，不复制领域对象；保存 program/version/window、term/source/projectionVersion/sealed 状态和输入状态，按 academicCalendarProjection 的半开边界开启/关闭且历史可审计  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 4.4b：运行毕业季多类信号合证专项

**type:** feature  
**dependsOn:** 4.4a,4.1b,4.2,4.3  
**readyWhen:** SPM-1.0.0 已批准，ECON-012/NIGHT-001/ACADEMIC-001 均具生产资格，毕业 STUDENT/RESPONSIBILITY/academicCalendarProjection required 控制输入合格  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望在毕业季联合查看独立经济、作息与学业信号。

**需求覆盖：** FR-22（full）

#### AC-4.4b-HAPPY

**Given** GRADUATION-CARE-001 的 STUDENT/RESPONSIBILITY 合格，academicCalendarProjection 两源 eligible、水位达标且 sealed/effective/适用字段与时序合法，毕业窗口生效，且 ECON-012/NIGHT-001/ACADEMIC-001 中至少 2 个 eligible affirmative 独立类别存在，含论文/延毕/毕业节点 fixture  
**When** 构建并显示毕业季专项优先队列  
**Then** 以 `programId+version+subjectRef+windowId` 形成唯一只读投影，只引用上游对象；不同信号分栏，不加总分数、不改等级，同一学业类别只计一次  
**And** 展示 SPM/RuleVersion、窗口、参与类别、质量与 degraded 状态，并按 RFP 隐藏不可见信号。

#### AC-4.4b-SINGLE-SIGNAL-NON-CONCLUSION

**Given** 毕业窗口内只有 ECON、NIGHT 或 ACADEMIC 任一单一 affirmative 类别，或多个节点仍属于同一 ACADEMIC 类别  
**When** 计算 2-of-3 进入条件  
**Then** 原 Candidate/Clue 保持原生队列与状态，不形成毕业专项优先投影  
**And** 不产生“毕业风险”、学生结论、新分数、重复 Candidate 或公共任务。

#### AC-4.4b-OBSERVATION-BOUNDARY

**Given** 毕业专项引用多个 Clue，当前责任辅导员显式选择其中一个当前可见、未终态 clueId 启动 7 自然日 Observation，且该 Clue 新证据分别在 end 前 1 秒、恰在 end、end 后到达  
**When** 以 `sourceContextRef=programId+programVersion+windowId` 调用 Clue 观察命令并到期复查  
**Then** Observation 仅绑定所选 clueId，唯一键为 `clueId+catalogVersion+startsAt+reviewAt`；`[startsAt,end)` 内证据进入该 Clue，恰在/晚于 end 的证据进入复查阶段，到期只生成一个 `clueId+observationId+reviewAt` taskKey  
**And** 缺选择、多选、隐藏/非参与/终态 Clue 均 fail closed；专项不拥有 Observation、不复制 Clue，延长按 CAC 创建 successor 并保留历史窗口链。

#### AC-4.4b-QUALITY-AUTH-FAILURE

**Given** 任一 required 控制输入不合格，RuleVersion 非 production/eligible、水位超 SLO，或当前用户无任一参与对象权限  
**When** 创建或读取毕业专项投影  
**Then** required 失败阻断新投影；不可见参与信号不泄露存在性且不以陈旧/默认数据替代  
**And** optional 缺失仅标 degraded，不能显示为 0、否或正常。

#### AC-4.4b-IDEMPOTENCY-CORRECTION

**Given** 同一 program key 和参与集合重放，或上游更正/撤销/噪声关闭  
**When** 重建专项投影  
**Then** 返回或更新同一投影，沿 lineage 重算参与类别并保存原因  
**And** 不重复 Candidate、Clue、观察任务或提醒，历史版本不可变。

### Story 4.4c：运行版本化入学与考试季专项矩阵

**type:** feature  
**dependsOn:** 4.4a,4.2,4.3  
**readyWhen:** DCC/RC/BC/SPM/ACN/RFP-1.0.0 的入学/考试 STUDENT/RESPONSIBILITY/academicCalendarProjection required 输入、投影 schema/owner/两源质量门、optional 独立信号、人群、半开窗口、隐私 purpose 与有效期已批准  
**estimate:** 5d  
**status:** planned

作为学工管理人员，我希望按明确的必需/可选输入矩阵组织入学和考试关怀。

**需求覆盖：** FR-24（full）; BR-12

#### AC-4.4c-ADMISSION-HAPPY

**Given** ADMISSION-ADAPT-001 的 STUDENT/RESPONSIBILITY 合格，academicCalendarProjection 的 ACADEMIC/CALENDAR 两源合同/eligible/水位合格且投影 sealed/effective/term 唯一并含 termStartAt，学生为首学期新生，NIGHT-001 affirmative 且 PSYCH-DEID purpose=`ADMISSION_ADAPTATION`、有效/已批准  
**When** 在 `[termStartAt,termStartAt+42自然日)` 计算 2-of-2  
**Then** 形成唯一入学适应只读投影，只保存心理 opaque ref/category/purpose/validity/approvalVersion 与 NIGHT 聚合引用  
**And** 禁止诊断、量表分值、咨询事实/正文、自由文本和网络原始内容，不创建/复制 Candidate 或 Clue。

#### AC-4.4c-EXAM-HAPPY

**Given** EXAM-CARE-001 的 STUDENT/RESPONSIBILITY 合格，academicCalendarProjection schema 完整、两源 eligible/水位达标、sealed/effective 且 examWindowStartAt/resultsSealedAt 时序合法，NIGHT-001 与命中 ACN-1.0.0 的 ACADEMIC-001 均 affirmative/production/eligible  
**When** 在 `[examWindowStartAt-14日,resultsSealedAt+7日)` 计算 2-of-2  
**Then** 形成唯一考试季投影，显示 term、batch、node、窗口、两个类别、质量与 SPM/ACN/RuleVersion  
**And** 只引用既有上游对象，不把学业节点与夜间信号加总成分数或学生结论。

#### AC-4.4c-REQUIRED-INPUT-FAILURE

**Given** STUDENT/RESPONSIBILITY 任一 required source contract 未 approved/effective、SourceQualitySnapshot 非 eligible、watermark 超 DCC SLO，或 academicCalendarProjection 两源/当前 program 必填字段/版本/封账/生效/term 唯一/适用时序任一失败  
**When** 计算受影响入学/考试专项  
**Then** 阻断该范围的新专项投影并逐项显示失败输入  
**And** source 不使用未定义的 production 状态，不以 optional/默认日历替代 required。

#### AC-4.4c-OPTIONAL-DEGRADATION

**Given** required 输入合格但 NIGHT、PSYCH-DEID 或 ACADEMIC optional 信号缺失/不合格  
**When** 构建专项视图  
**Then** 专项定义保持可见但标 degraded，缺失项显示不可用且不计 affirmative  
**And** 不把缺失解释为 0、否、正常或完整覆盖；未达到 2-of-2 时不形成专项优先投影。

#### AC-4.4c-SINGLE-SIGNAL-PRIVACY-AUTH

**Given** 仅一个 affirmative 独立信号，心理字段超 purpose allowlist，或当前用户无参与对象/字段权限  
**When** 尝试生成或读取专项投影  
**Then** 单信号不生成专项优先项/学生结论；超范围字段 fail closed，越权与不存在使用同一拒绝语义  
**And** 原 Candidate/Clue 状态和任务不变，审计不复制心理/网络敏感内容。

#### AC-4.4c-BOUNDARY-IDEMPOTENCY-CORRECTION

**Given** 输入发生在 start 前 1 秒、恰 start、end 前 1 秒、恰 end，或同一 program key 重放/上游更正撤销  
**When** 按 Asia/Shanghai `[startAt,endAt)` 构建或重建  
**Then** start 含、end 不含且每个输入只归属一个窗口；相同 key 返回同一投影，更正沿 lineage 更新并保留历史  
**And** 到期停止新进入，不重复 Candidate、Clue、专项行、任务或通知。

### Story 4.5a：治理身份等级及其排序用途

**type:** governance  
**dependsOn:** 1.8  
**readyWhen:** DCC/RC/RFP-1.0.0 的身份等级逐源合同、purpose、可见角色、有效期和排序场景已批准  
**estimate:** 3d  
**status:** planned

作为治理人员，我希望身份等级只在批准场景调整排序而不改写规则分数或关怀等级。

**需求覆盖：** FR-37（身份等级 phase）; BR-11

#### AC-4.5a-HAPPY

**Given** 身份等级来源、用途、可见角色、有效期和允许排序场景获批  
**When** 治理人员执行本 Story 的主流程  
**Then** 身份等级只影响批准的学业/安全 Top-k，经济场景不应用，且不得改写原始分数、关怀等级或形成惩戒  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 4.5b：定义并治理有期限业务标签

**type:** governance  
**dependsOn:** 4.5a  
**readyWhen:** DCC/RC/RFP-1.0.0 的标签逐源合同、purpose、敏感级、可见角色、有效期和字段策略已批准  
**estimate:** 3d  
**status:** planned

作为治理人员，我希望业务标签有明确用途、最小可见范围和到期时间。

**需求覆盖：** FR-37（业务标签 phase）; BR-11

#### AC-4.5b-HAPPY

**Given** 标签来源、用途、敏感级、可见角色和有效期获批  
**When** 治理人员执行本 Story 的主流程  
**Then** 标签只能服务声明用途，不得进入惩戒、评优或未批准规则，并与运行、工作纪实读模型隔离  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 4.5c：处理来源冲突、审核、生效与失效

**type:** governance  
**dependsOn:** 4.5a,4.5b  
**readyWhen:** DCC/RC/RFP/HRAP-1.0.0 的 `bulk-governance.execute` D4、来源优先级和失效传播合同已批准  
**estimate:** 5d  
**status:** planned

作为治理人员，我希望身份与标签的来源冲突、审核、批量生效、过期和撤销受控且可追溯。

**需求覆盖：** FR-37（full）; BR-10

#### AC-4.5c-HAPPY

**Given** 来源、用途、有效期、审批证据及 approved/effective `bulk-governance.execute` 齐备  
**When** 批量推进待审核、生效、过期与撤销  
**Then** 执行时重检 matrixVersion、逐对象授权与 aggregateVersion；状态不可跳转，来源冲突按批准优先级处理，历史版本不可覆盖且失效传播到规则消费  
**And** 保存影响预览、逐对象成功/失败和审计。

#### AC-4.5c-AUTH-DENIAL

**Given** 用户无审核/批量动作权限、审批过期，或 `bulk-governance.execute` 未映射/运行测试未通过  
**When** 提交批量生效、过期或撤销  
**Then** 当前实际 D0/deny  
**And** 不改变任何对象状态。

#### AC-4.5c-ILLEGAL-STATE

**Given** 请求跳过待审核、恢复已失效版本或来源冲突未解决  
**When** 推进生命周期  
**Then** 拒绝非法迁移  
**And** 返回当前版本和缺失证据。

#### AC-4.5c-BOUNDARY

**Given** 有效期恰在开始、结束或批量截止边界  
**When** 计算生效/失效  
**Then** 按批准时区与含边界规则得到可复现结果  
**And** 结束后下一次规则消费立即失效。

#### AC-4.5c-DEPENDENCY-FAILURE

**Given** HighRiskActionPolicy、审批、来源目录或失效传播依赖不可用  
**When** 执行 `bulk-governance.execute`  
**Then** fail closed 并报告逐对象失败  
**And** 不以部分成功冒充全量完成。

#### AC-4.5c-IDEMPOTENCY

**Given** 同一批量 commandId 重放  
**When** 再次执行  
**Then** 返回原逐对象结果  
**And** 不重复生效、撤销或审计事件。

#### AC-4.5c-CONCURRENCY

**Given** 来源更正与审核基于同一旧版本并发  
**When** 提交  
**Then** 旧版本返回冲突并要求重新预览  
**And** 历史来源和人工决策均保留。

### Story 4.6：处理用户争议并暂停或恢复计算

**type:** feature  
**dependsOn:** 4.5c  
**readyWhen:** DCC/RC/QRP-1.0.0 的争议来源、责任、时限、暂停和恢复条件已批准  
**estimate:** 3d  
**status:** planned

作为治理人员，我希望暂停错误影响且保留证据。

**需求覆盖：** FR-38

#### AC-4.6-HAPPY

**Given** 用户报告错误、过期或不适用且争议责任人、处理时限和恢复条件获批  
**When** 治理人员执行本 Story 的主流程  
**Then** 争议期间可暂停计算且不静默删除；解决记录处理结果、采用来源和生效时间，满足条件后才恢复  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-4.6-AUTH-DENIAL

**Given** 操作者既不是争议责任人也无对象治理权限  
**When** 尝试暂停、裁决或恢复计算  
**Then** 服务端拒绝且争议/标签/规则状态不变  
**And** 不泄露争议材料内容。

#### AC-4.6-ILLEGAL-STATE

**Given** 争议未调查完成、采用来源未记录，或对象已撤销/终态  
**When** 尝试恢复计算或重复解决  
**Then** 返回非法状态与缺失守卫  
**And** 不恢复依赖规则消费。

#### AC-4.6-BOUNDARY

**Given** 争议暂停/恢复生效时刻恰在 RuleVersion 窗口或标签有效期边界  
**When** 计算可参与窗口  
**Then** 按批准时区和含边界规则只归属一个状态  
**And** 历史已产出事实不被改写。

#### AC-4.6-DEPENDENCY-FAILURE

**Given** 权威来源核验、通知或规则失效传播依赖不可用  
**When** 处理争议  
**Then** 保持暂停/待处理且显示具体失败  
**And** 不以超时自动判定恢复或删除对象。

#### AC-4.6-IDEMPOTENCY

**Given** 同一 disputeCommandId 与裁决版本重放  
**When** 再次暂停、解决或恢复  
**Then** 返回原争议状态和裁决事实  
**And** 不重复发布失效/恢复事件。

#### AC-4.6-CONCURRENCY

**Given** 数据 owner 更正来源与治理人员裁决基于同一旧版本并发  
**When** 提交争议结果  
**Then** 旧版本返回 409 并要求重看最新来源  
**And** 不形成来源与裁决混合版本。

### Story 4.7：计算七日跨类别独立线索合证

**type:** governance  
**dependsOn:** 4.2,4.3,4.4b  
**readyWhen:** BC/RC/HRAP-1.0.0 与 CORROBORATE-001 含边界 168h 规则已批准；前序场景已产生可对账正式线索，`rule.publish` D4 可执行  
**estimate:** 8d  
**status:** planned

作为规则治理人员和辅导员，我希望先证明 CORROBORATE-001 的 canary/production 资格，再安全解释七日内独立类别共同变化。

**需求覆盖：** FR-58; BR-10; BR-12

#### AC-4.7-HAPPY

**Given** CORROBORATE-001 governanceStatus=approved，参与 RuleVersion 均具生产资格，上游正式线索可对账，且匿名/生产影子样本、质量门与 D4 maker/checker 齐备  
**When** 具权人员先完成 canary 观察并执行 `rule.publish` 进入 production，再对同一学生两类独立正式线索计算合证  
**Then** 仅在 CORROBORATE-001 runtimeStatus=production、qualityStatus=eligible 后产生合证优先级，并展示参与线索、类别、时间、质量、RuleVersion 与 matrixVersion  
**And** 不相加规则分数、不改写各自关怀等级，参与线索失效/噪声关闭时重算留因。

#### AC-4.7-AUTH-DENIAL

**Given** 操作者无 `rule.publish` 权限、maker/checker 相同、审批过期，或当前用户无任一参与线索对象权限  
**When** 尝试发布 CORROBORATE-001 或读取/执行合证  
**Then** 发布或对象访问默认 deny，规则保持原状态且不泄露另一条线索存在性  
**And** 不创建合证事实、任务或成功证据。

#### AC-4.7-ILLEGAL-STATE

**Given** CORROBORATE-001 非 approved/production/eligible、参与对象不是 admitted 正式线索、类别不独立，或任一线索已噪声关闭/失效  
**When** 尝试产生或保留合证  
**Then** 拒绝新合证或撤销现有合证优先级并追加原因事实  
**And** 不修改参与 Clue 的业务状态、分数或等级。

#### AC-4.7-BOUNDARY

**Given** 两条独立线索 occurredAt 的差值分别为 168h 前 1 秒、恰 168h、168h 后 1 秒，并包含 1/2 类样本  
**When** 按 UTC 计算并以 Asia/Shanghai 展示  
**Then** 小于等于 168h 且类别数至少 2 才命中；大于 168h 或仅 1 类不命中  
**And** 结果、时间源、BusinessCalendarVersion 与 RuleVersion 可重放。

#### AC-4.7-DEPENDENCY-FAILURE

**Given** 任一参与 RuleVersion 失去生产资格、上游事件存在水位间隙/乱序未收敛，或发布/审批依赖不可用  
**When** 评估 canary/production 或计算合证  
**Then** CORROBORATE-001 保持 runtime-ineligible/fused，或暂停合证并显示具体失败成员  
**And** 不以陈旧读模型、单一类别或默认时间替代。

#### AC-4.7-IDEMPOTENCY

**Given** 同一 publish commandId，或同一学生、RuleVersion、参与线索集合与窗口重放  
**When** 再次发布或计算  
**Then** 返回原发布记录与同一合证事实  
**And** 不重复开放 production、创建合证、任务或提醒。

#### AC-4.7-CONCURRENCY

**Given** 发布期间规则/矩阵版本变化，或计算提交前参与线索被关闭、撤销或更正  
**When** 基于旧 aggregateVersion 提交  
**Then** 返回 409/门禁拒绝并按最新规则与参与集合重算  
**And** 不形成混合版本合证或覆盖历史。

### Story 4.8：提供用途隔离的进宿舍工作纪实

**type:** feature  
**dependsOn:** 2.1,1.8  
**readyWhen:** DCC/WVP/BC-1.0.0 的工作纪实合同、时区、5m 去重/最短停留和 4h 最大配对已批准  
**estimate:** 5d  
**status:** planned

作为授权管理人员，我希望查看周月工作纪实而不形成绩效结论。

**需求覆盖：** FR-56; BR-11; BR-12

#### AC-4.8-HAPPY

**Given** approved WorkVisitPolicyVersion、时区和最大配对时长齐备  
**When** 授权管理人员执行本 Story 的主流程  
**Then** 同人同楼按策略去重和配对，跨日跨楼不配对、单边记异常；结果不进入学生规则或绩效排名  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。


## Epic 5：跨部门关怀协同闭环

### Story 5.1：从正式线索发起最小必要转介

**type:** feature  
**dependsOn:** 3.8,1.9  
**readyWhen:** PIC/TSP/RFP/HRAP-1.0.0 的目标授权、工单合同和 `transfer.submit` D1 已批准  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望向适当部门发送完整但最小化转介。

**需求覆盖：** FR-39（full）; BR-10

#### AC-5.1-HAPPY

**Given** 正式 Clue 处理中、目标授权有效、approved TransferSlaPolicyVersion 与当前 HighRiskActionPolicy 可用  
**When** transfer.submit 执行时重检通过  
**Then** 原子创建唯一 TransferOrder、原 Clue 关联、dueAt、SLA policyVersion 和最小字段投影  
**And** 保存执行时 matrixVersion，并通过 outbox 投递而不提前宣告对方接收。

#### AC-5.1-AUTH-DENIAL

**Given** 用户无对象/目标范围、目标部门无接收权限，或 transfer.submit 未获授权  
**When** 提交转介  
**Then** 默认 deny 且不创建 TransferOrder  
**And** 不写“已发送”或 confirmed，保留易失草稿。

#### AC-5.1-ILLEGAL-STATE

**Given** Clue 非处理中/已终态、必填缺失，或 HighRiskActionPolicy pending/未映射  
**When** 提交转介  
**Then** 实际 D0/deny 且状态不变  
**And** 返回缺失字段或门禁证据而不持久化工单。

#### AC-5.1-BOUNDARY

**Given** dueAt 恰在工作日、节假日或 SLA 时限边界  
**When** 按 TransferSlaPolicyVersion 计算  
**Then** dueAt 与 policyVersion 原子保存且可由冻结时钟复现  
**And** 后续补充、退回或责任变化不重置 dueAt。

#### AC-5.1-DEPENDENCY-FAILURE

**Given** 高风险策略、SLA 策略、目标目录或工单依赖不可用  
**When** 尝试提交  
**Then** fail closed；若本地事务尚未提交则不创建工单，已提交后的外部故障只置 `TransferOrder.deliveryStatus=pending`  
**And** 两种路径均不得显示已接收。

#### AC-5.1-IDEMPOTENCY

**Given** 同一 transferRequestId 或业务键重放  
**When** 再次提交  
**Then** 返回原 TransferOrder、dueAt 和 policyVersion  
**And** 不重复工单、任务或通知。

#### AC-5.1-CONCURRENCY

**Given** Clue、目标授权、SLA 或矩阵版本在预览后变化  
**When** 基于旧 aggregateVersion 提交  
**Then** 返回 409 或门禁拒绝并要求重新预览  
**And** 不创建混合版本 TransferOrder。

### Story 5.2a：接收并处理授权工单

**type:** feature  
**dependsOn:** 5.1  
**readyWhen:** RFP/PIC/TSP-1.0.0 的目标角色、接收动作和临时字段投影已批准  
**estimate:** 3d  
**status:** planned

作为协同人员，我希望接收授权范围内的工单并进入处理中。

**需求覆盖：** FR-40（接收 phase）; BR-6

#### AC-5.2a-HAPPY

**Given** 目标角色、接收动作和临时字段投影策略获批  
**When** 协同人员执行本 Story 的主流程  
**Then** 接收后建立只覆盖当前工单的授权并合法进入处理中，记录操作者、时间和下一责任人  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-5.2a-AUTH-DENIAL

**Given** 用户不属于目标部门、不是当前可接收人或任务期字段授权已失效  
**When** 尝试接收 TransferOrder  
**Then** 服务端拒绝且业务状态保持待接收  
**And** 不返回未授权证据字段或建立任务期授权。

#### AC-5.2a-ILLEGAL-STATE

**Given** 工单不在待接收，或只有 `TransferOrder.deliveryStatus=confirmed` 而无业务接收命令  
**When** 尝试进入处理中  
**Then** 返回 409 与当前业务状态  
**And** confirmed 不得替代具权接收。

#### AC-5.2a-BOUNDARY

**Given** 接收发生在 dueAt 恰好到达的服务端时刻  
**When** 提交接收命令  
**Then** 合法接收仍可进入处理中，但超期标记按 TransferSlaPolicyVersion 独立计算并永久保留  
**And** 不重置 dueAt。

#### AC-5.2a-DEPENDENCY-FAILURE

**Given** RoleFieldPolicyVersion、身份/责任关系或审计提交不可用  
**When** 尝试接收  
**Then** fail closed 且不改变业务状态  
**And** 不降级为前端隐藏或缓存授权。

#### AC-5.2a-IDEMPOTENCY

**Given** 同一 acceptCommandId 由同一接收人重放  
**When** 再次提交  
**Then** 返回原处理中版本和授权范围  
**And** 不重复事件、通知或授权记录。

#### AC-5.2a-CONCURRENCY

**Given** 两名可接收人基于同一待接收版本并发接收  
**When** 提交命令  
**Then** 仅一个成功，另一方收到 409 与最新责任人  
**And** 不形成双责任人或重复通知。

### Story 5.2b：请求补充、接收补充与退回转介

**type:** feature  
**dependsOn:** 5.2a  
**readyWhen:** 工单合法迁移和补充材料最小化 schema 已测试  
**estimate:** 3d  
**status:** planned

作为协同人员，我希望在一个工单中推进协同处理。

**需求覆盖：** FR-40（处理 phase）; BR-6

#### AC-5.2b-HAPPY

**Given** 工单合法迁移、补充材料最小化 schema 与 `resubmit` 守卫已测试  
**When** 分别执行待接收→退回、处理中→待补充、处理中→退回、待补充→处理中、待补充→退回，以及仍具权发起方的退回→待接收 `resubmit`  
**Then** 六条迁移均成功并保存操作者、时间、理由和下一责任人；重提保留同一 transferId、目标部门和完整历史，等待目标方重新接收  
**And** 所有迁移保留原 dueAt；仅 TransferSlaPolicyVersion 明确允许时才保存暂停区间、原因与 actor。

#### AC-5.2b-AUTH-DENIAL

**Given** 用户既不是当前工单责任人，也不是退回态仍具权发起方  
**When** 请求补充、退回、补齐或重提  
**Then** 服务端拒绝且状态/材料不变  
**And** 不泄露补充内容或目标部门内部说明。

#### AC-5.2b-ILLEGAL-STATE

**Given** 请求为待接收→待补充、退回→处理中/已回填，或非退回态调用 `resubmit`  
**When** 提交迁移  
**Then** 返回 409 与 FR-40 合法出边  
**And** 不改变状态、dueAt、责任人或 `TransferOrder.deliveryStatus`。

#### AC-5.2b-BOUNDARY

**Given** 补充/重提发生在 dueAt 或批准 pause interval 的开始/结束边界  
**When** 重新计算 SLA  
**Then** 按 TransferSlaPolicyVersion 只计入一次暂停区间并保留原 dueAt  
**And** 临期/超期标记不因重提清零。

#### AC-5.2b-DEPENDENCY-FAILURE

**Given** 补充材料服务、通知或目标部门投递不可用  
**When** 本地合法迁移提交  
**Then** 状态事实与 outbox 原子保存，外部失败进入 pending/retrying  
**And** 不伪装对方已收到补充或已重新接收。

#### AC-5.2b-IDEMPOTENCY

**Given** 同一 supplement/return/resubmit commandId 重放  
**When** 再次提交  
**Then** 返回原 TransferOrder 版本和原 transferId  
**And** 不重复材料、状态事件或通知。

#### AC-5.2b-CONCURRENCY

**Given** 目标责任人退回与发起方补充/重提基于同一旧版本并发  
**When** 提交命令  
**Then** 仅合法先提交者成功，另一方收到 409 与最新状态  
**And** 不出现退回态已被跳过或材料丢失。

### Story 5.2c：回填结果并按守卫关闭业务工单

**type:** feature  
**dependsOn:** 5.2b  
**readyWhen:** PIC/RFP-1.0.0 的结果类别、最小摘要和敏感文本策略已批准  
**estimate:** 3d  
**status:** planned

作为协同人员，我希望回填结构化结果并在满足守卫后独立关闭业务工单。

**需求覆盖：** FR-40（回填/关闭 phase）; FR-41（结果 phase）

#### AC-5.2c-HAPPY

**Given** 结果类别、最小摘要和敏感文本策略获批  
**When** 协同人员执行本 Story 的主流程  
**Then** 回填至少保存处理结果、后续建议、是否需发起人继续行动和必要摘要，缺必填不得进入已回填；满足守卫后 TransferOrder 独立关闭且不关闭原 Clue  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-5.2c-AUTH-DENIAL

**Given** 用户不是当前工单责任人或任务期授权已撤销/过期  
**When** 尝试回填或关闭  
**Then** 服务端拒绝且 TransferOrder/Clue 均不改变  
**And** 不返回未授权结果字段。

#### AC-5.2c-ILLEGAL-STATE

**Given** 结果必填缺失、工单不在处理中，或未进入已回填便请求关闭  
**When** 提交回填/关闭  
**Then** 返回字段错误或 409，并保持原状态  
**And** 不生成虚假 TransferEvent 或关闭原 Clue。

#### AC-5.2c-BOUNDARY

**Given** 回填/关闭提交时刻恰在 dueAt 或授权失效时刻  
**When** 服务端按统一时钟校验  
**Then** 按含边界策略决定命令资格，并独立永久记录是否曾超期  
**And** 成功也不重置/覆盖原 dueAt。

#### AC-5.2c-DEPENDENCY-FAILURE

**Given** 原线索回写、通知或公共任务适配器不可用  
**When** 本地回填/关闭合法提交  
**Then** TransferOrder 事实与回写 outbox 原子保存，外部 `TransferOrder.deliveryStatus` 可失败  
**And** 不把外部失败写成业务回退或原 Clue 已关闭。

#### AC-5.2c-IDEMPOTENCY

**Given** 同一 resultCommandId 或 closeCommandId 重放  
**When** 再次提交  
**Then** 返回原结果/关闭版本  
**And** 不重复 TransferEvent、通知或 Clue 回写。

#### AC-5.2c-CONCURRENCY

**Given** 两端并发回填，或关闭与退回基于同一旧版本  
**When** 提交命令  
**Then** 仅一个合法迁移成功，其余返回 409 与最新结果  
**And** 不形成混合结果或双重终态。

### Story 5.2d：验收通知、投递失败、乱序与对账

**type:** feature  
**dependsOn:** 5.2c  
**readyWhen:** Story 5.2c 的关闭事实、业务状态与 `TransferOrder.deliveryStatus` 正交模型及对账夹具通过测试  
**estimate:** 5d  
**status:** planned

作为集成负责人，我希望通知和投递失败不冒充业务状态且双方最终对账。

**需求覆盖：** FR-40（full）; FR-41（phase）; BR-6; NFR-8

#### AC-5.2d-HAPPY

**Given** Story 5.2c 已提交结果或关闭业务工单  
**When** 通知、公共任务和外部工单投递并执行水位对账  
**Then** 业务状态与 `TransferOrder.deliveryStatus` 分栏收敛，原 Clue 保持独立状态  
**And** pending、retrying、failed 或 confirmed 均不回滚本地事实；confirmed 仅表示外部系统确认投递，不能把待接收自动变为处理中或显示业务人员已接收。

#### AC-5.2d-AUTH-DENIAL

**Given** 用户任务期授权过期或无 close 权限  
**When** 尝试关闭  
**Then** 统一拒绝并撤销字段访问  
**And** 保留草稿但不得持久化敏感副本。

#### AC-5.2d-ILLEGAL-STATE

**Given** 迟到通知试图倒退业务状态或 `TransferOrder.deliveryStatus` 水位  
**When** 消费通知  
**Then** 拒绝倒退并返回当前状态与水位  
**And** 不得自动关闭原线索。

#### AC-5.2d-BOUNDARY

**Given** 业务工单恰在关闭、撤销或下一责任人切换边界，且外部投递仍 pending  
**When** 生成通知并执行对账  
**Then** 业务终态只提交一次，`TransferOrder.deliveryStatus` 继续独立推进  
**And** 边界时刻、aggregateVersion 和双方水位完整保存。

#### AC-5.2d-DEPENDENCY-FAILURE

**Given** 外部工单、通知或公共任务不可用  
**When** 投递本地结果/关闭事实  
**Then** 本地事实有效且 `TransferOrder.deliveryStatus=pending|retrying|failed`  
**And** 失败不回滚业务关闭。

#### AC-5.2d-IDEMPOTENCY

**Given** 同一 eventId 或业务键重放  
**When** 再次投递或对账  
**Then** 返回原双方 ID、状态与水位  
**And** 不重复通知。

#### AC-5.2d-CONCURRENCY

**Given** 本地新版本与外部回调交错  
**When** 以 aggregateVersion 和水位仲裁  
**Then** 单调收敛或返回可见对账冲突  
**And** 草稿可人工合并。

### Story 5.3：将协同结果返回原线索并由辅导员确认

**type:** integration  
**dependsOn:** 5.2c,3.9d  
**readyWhen:** TransferEvent 乱序、重复和撤销契约已测试  
**estimate:** 3d  
**status:** planned

作为发起辅导员，我希望在原线索决定继续行动、观察或闭环。

**需求覆盖：** FR-41（full）; FR-36（full）; BR-6

#### AC-5.3-HAPPY

**Given** TransferEvent 乱序、重复和撤销契约已测试  
**When** 发起辅导员执行本 Story 的主流程  
**Then** 真实 TransferEvent 按事件时间、来源、操作者、结果版本进入原 Clue 时间线并通知发起人；工单/投递状态均不自动关闭 Clue 或完成 CareAction  
**And** 仅原 Clue 当前责任人确认已回填结果后生成一次 CAC `transfer-result-confirmed`，再显式选择继续处理、观察或已跟进；撤权/重复/并发不重复完成事件。

### Story 5.4：对接既有部门工单系统

**type:** integration  
**dependsOn:** 5.2d  
**readyWhen:** PIC/TSP-1.0.0 已批准且目标工单沙箱可连接；本 Story 负责签名、回调和对账证据  
**estimate:** 5d  
**status:** planned

作为协同人员，我希望避免在两个系统重复录入。

**需求覆盖：** FR-42

#### AC-5.4-HAPPY

**Given** PIC/TSP-1.0.0 已批准且外部工单沙箱可连接  
**When** 协同人员执行本 Story 的主流程  
**Then** 保存双方 ID，重复或乱序回调幂等，故障时只标 `TransferOrder.deliveryStatus` 且恢复后按业务键对账  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 5.5：验收公共任务全生命周期与关怀结果回写

**type:** integration  
**dependsOn:** 1.9,2.5c,3.4,3.9d,3.12,5.2d  
**readyWhen:** PIC-1.0.0 已批准，前序真实生产者 Story 已通过契约/沙箱/SLO/乱序/对账测试  
**estimate:** 8d  
**status:** planned

作为平台集成负责人，我希望让质量异常、Candidate、正式线索、督办和转介共享任务及结果摘要端到端一致。

**需求覆盖：** FR-4（full）; FR-59（full）; BR-8

#### AC-5.5-HAPPY

**Given** Candidate、正式线索、督办或转介发生可共享状态变化  
**When** outbox 投递  
**Then** 按业务键更新唯一外部任务并回写最小化结果摘要  
**And** 终态关闭或撤销且双方水位一致。

#### AC-5.5-PRODUCER-CONFORMANCE

**Given** 质量、Candidate、Clue、督办和 TransferOrder 五类真实生产者  
**When** 分别触发新建、临期、超期、一次升级、聚合更新、完成、拒绝、合并、撤权和关闭  
**Then** 每类生产者均更新同一 workItemKey 对应任务，且终态关闭/撤销或合并重定向符合契约  
**And** 每次回写仅包含本地聚合 ID、aggregateVersion、eventId、状态、发生时间、结果类别、traceId 和契约版本，不默认包含自由文本或证据正文。

#### AC-5.5-AUTH-DENIAL

**Given** 调用方身份、签名或字段范围无效  
**When** 创建、更新或查询任务  
**Then** 默认 deny 且不泄露学生对象  
**And** 记录安全审计。

#### AC-5.5-ILLEGAL-STATE

**Given** 迟到事件试图将外部终态回退或 aggregateVersion 倒退  
**When** 消费事件  
**Then** 拒绝或隔离并保持高版本状态  
**And** 产生可见对账异常。

#### AC-5.5-BOUNDARY

**Given** 任务恰好进入终态、撤权或截止升级边界  
**When** 同步状态  
**Then** 每个业务键只关闭/撤销一次且升级只提醒一次  
**And** 边界依据 BusinessCalendarVersion。

#### AC-5.5-DEPENDENCY-FAILURE

**Given** 公共平台超时、拒绝或部分成功  
**When** 处理 outbox  
**Then** 本地事实不回滚，对应 `TaskDelivery.status` 显示 pending/retrying/failed  
**And** 不得向用户宣告外部成功。

#### AC-5.5-IDEMPOTENCY

**Given** 同一 eventId、业务键或回调重放  
**When** 再次处理  
**Then** 返回相同双方 ID 和水位  
**And** 不创建重复任务或结果。

#### AC-5.5-CONCURRENCY

**Given** 本地新版本与外部回调交错  
**When** 执行对账  
**Then** 按 aggregateVersion、水位和单调状态收敛  
**And** 冲突进入人工异常队列而不覆盖历史。


## Epic 6：校级治理与规则持续改进

### Story 6.1：提供只读聚合领导驾驶舱

**type:** feature  
**dependsOn:** 3.11  
**readyWhen:** MPP/UXB/RFP-1.0.0 的指标 purpose、n=10/20 suppression 和禁止个体下钻已批准  
**estimate:** 5d  
**status:** planned

作为学校领导，我希望查看全校态势与学院差异而不读取个体。

**需求覆盖：** FR-45; BR-11; NFR-3

#### AC-6.1-HAPPY

**Given** approved MetricPublicationPolicyVersion、指标用途和禁止个体下钻策略获批  
**When** 学校领导执行本 Story 的主流程  
**Then** 只消费聚合读模型，显示口径、policyVersion、周期、质量和等价表格；低于最小分组显示 suppressed 而非 0，不按单指标排名或形成自动惩戒  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 6.2：记录并复查改进计划与资源调配行动

**type:** governance  
**dependsOn:** 6.1,5.5  
**readyWhen:** MPP/HRAP-1.0.0 的 `leader-action.record` D1、指标口径和责任方目录已批准  
**estimate:** 5d  
**status:** planned

作为学院管理人员和学校领导，我希望从汇总态势创建受治理行动并比较效果。

**需求覆盖：** FR-61; BR-10; BR-11

#### AC-6.2-HAPPY

**Given** 授权汇总指标与口径版本可用  
**When** 具权人员记录改进或资源行动  
**Then** 创建 planned GovernanceAction，保存依据、责任方、措施、目标、截止、基线和复查周期  
**And** 合法推进 planned→active→completed|cancelled，到期创建唯一 pending 复查任务。

#### AC-6.2-MATRIX-DENY

**Given** `leader-action.record` 未映射、运行测试未通过、policyVersion 漂移或执行审批证据缺失  
**When** 提交 leader-action.record  
**Then** 实际 D0/deny，只允许查看草稿与缺失证据  
**And** 不持久化 GovernanceAction、不生成成功状态或复查任务。

#### AC-6.2-AUTH-DENIAL

**Given** 用户无 `leader-action.record` 或对应 GovernanceAction/ReviewTask 处理权限  
**When** 创建、启动、完成、取消行动或提交复查  
**Then** 默认 deny  
**And** 不得由驾驶舱前端绕过。

#### AC-6.2-ILLEGAL-STATE

**Given** 行动试图直接改变学生状态/标签/等级、形成自动惩戒，或执行非法 GovernanceAction 状态跳转  
**When** 提交  
**Then** 拒绝并提示用途边界  
**And** 不产生下游学生事件。

#### AC-6.2-BOUNDARY

**Given** 截止或复查时刻恰在工作日边界，或复查结果为 metric-changed  
**When** 调度复查  
**Then** 按 BusinessCalendarVersion 只创建一次  
**And** reviewResult 只取 improved、not-improved、data-insufficient、metric-changed；metric-changed 不与原基线直接比较。

#### AC-6.2-DEPENDENCY-FAILURE

**Given** 指标服务不可用，或依据切片 stale、suppressed、质量不合格  
**When** 创建 GovernanceAction 或提交效果复查  
**Then** fail closed，不创建行动；已有 ReviewTask 保持其真实 pending 状态且不写 reviewResult  
**And** 页面显示数据截止、MetricPublicationPolicyVersion 和不可比较原因。

#### AC-6.2-TASK-DELIVERY-FAILURE

**Given** GovernanceAction/ReviewTask 本地事实可提交，但公共任务适配器不可用  
**When** 创建或更新复查任务  
**Then** GovernanceAction 与 ReviewTask 保持真实业务状态，对应 `TaskDelivery.status=pending|retrying|failed`  
**And** 不把任何来源聚合状态写成 pending，也不宣告外部任务已确认。

#### AC-6.2-REVIEW

**Given** ReviewTask 处于 pending，当前责任方或具权治理人员可处理，且同一 metricId/口径版本的复查数据可用  
**When** 提交 improved、not-improved、data-insufficient 或 metric-changed 之一及证据  
**Then** ReviewTask 原子迁移为 reviewed，保存 reviewResult、actor、reviewedAt、metric/version 和证据引用  
**And** metric-changed 只记录口径变化，不与原基线直接比较或伪造改善结论。

#### AC-6.2-IDEMPOTENCY

**Given** 同一 actionRequestId 或 reviewCommandId 重放  
**When** 再次提交行动或复查  
**Then** 返回原 GovernanceAction/ReviewTask 结果  
**And** 不重复行动、复查任务、reviewResult 或投递记录。

#### AC-6.2-CONCURRENCY

**Given** 责任方/目标或 pending ReviewTask 被另一端并发修改/复查  
**When** 保存旧 aggregateVersion  
**Then** 返回 409 与最新 GovernanceAction/ReviewTask 版本  
**And** 仅一个状态迁移/reviewResult 成功，保留用户易失草稿。

### Story 6.3a：构建规则效果口径与核实真值集

**type:** enabler  
**dependsOn:** 3.8,4.6  
**readyWhen:** SGP/MPP-1.0.0 的真值窗口、metricId、样本分群和隐私阈值已批准  
**estimate:** 3d  
**status:** planned

作为规则治理人员，我希望得到可复现的规则效果输入。

**需求覆盖：** FR-47（phase/enabler）; NFR-26

#### AC-6.3a-HAPPY

**Given** 真值窗口、metricId、样本分群和隐私阈值获批  
**When** 规则治理人员执行本 Story 的主流程  
**Then** 按规则版本关联 14 日内核实结果，区分命中、有效、噪声、排除、熔断和反馈  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 6.3b：计算分群效果与不确定区间

**type:** feature  
**dependsOn:** 6.3a  
**readyWhen:** 本科/研究生、年级和特殊时间段分群定义已冻结  
**estimate:** 3d  
**status:** planned

作为规则治理人员，我希望识别不同人群的效果与噪声差异。

**需求覆盖：** FR-47（分析 phase）

#### AC-6.3b-HAPPY

**Given** 本科/研究生、年级和特殊时间段分群定义已冻结  
**When** 规则治理人员执行本 Story 的主流程  
**Then** 分群样本不足仅显示区间和数据不足，不得伪造通过或扩大范围  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 6.3c：创建校准任务并闭环解释反馈

**type:** governance  
**dependsOn:** 6.3b,5.5  
**readyWhen:** 校准 owner、证据、截止和护栏触发器已配置  
**estimate:** 5d  
**status:** planned

作为学工管理人员，我希望将复盘结果转成可跟踪校准工作。

**需求覆盖：** FR-47（full）; FR-28（full）

#### AC-6.3c-HAPPY

**Given** 校准 owner、证据、截止和护栏触发器已配置  
**When** 学工管理人员执行本 Story 的主流程  
**Then** 按规则/模板版本聚合 Story 3.6 的三维 ExplanationFeedback 与噪声原因，并创建唯一校准任务  
**And** 正负/未使用、版本化原因、提交失败不入样本和分群结果均可对账；触发 PRD 护栏时暂停扩大并保留证据。

### Story 6.4：发布全量周月报与版本化运营指标

**type:** integration  
**dependsOn:** 5.5,6.3b,3.13  
**readyWhen:** PIC/MPP/RS-1.0.0 的运营域、发布与保留合同已批准  
**estimate:** 8d  
**status:** planned

作为授权管理人员，我希望获得含协同成效的报告并向数智学工回写汇总指标。

**需求覆盖：** FR-46（full）; FR-60（full）; NFR-3; NFR-26

#### AC-6.4-HAPPY

**Given** 协同、核实、质量和指标事实均达截止水位，且 approved MetricPublicationPolicyVersion/RetentionScheduleVersion 可引用  
**When** 生成周月报并发布运营指标  
**Then** 在 2/4 小时内产出且携带 metricId、口径版本、MetricPublicationPolicyVersion、周期、截止、质量和来源水位  
**And** 双方按周期和版本对账。

#### AC-6.4-AUTH-DENIAL

**Given** 请求者无报告范围或运营域发布权限  
**When** 查看、导出或发布  
**Then** 服务端拒绝并不泄露切片存在性  
**And** 审计请求范围。

#### AC-6.4-ILLEGAL-STATE

**Given** MetricPublicationPolicyVersion 缺失、质量不合格，或切片低于 policy 最小分组  
**When** 生成或发布  
**Then** policy 缺失/质量不合格时 release-ineligible；低于最小分组时只输出 suppressed 且不发布切片  
**And** 缺失、不可用、suppressed 与真实零值分别表达，suppressed 不得显示为 0。

#### AC-6.4-BOUNDARY

**Given** 切片样本恰等于 MetricPublicationPolicyVersion 的最小分组数或截止水位  
**When** 计算指标  
**Then** 按批准策略一致纳入或抑制  
**And** 保存边界证据。

#### AC-6.4-DEPENDENCY-FAILURE

**Given** 报表作业或运营域不可用  
**When** 执行  
**Then** 异步作业可重试且不阻塞页面  
**And** 不得以零值掩盖缺失。

#### AC-6.4-IDEMPOTENCY

**Given** 同一周期、metricId 和版本重放  
**When** 再次发布  
**Then** 不重复记账  
**And** 返回原水位。

#### AC-6.4-CONCURRENCY

**Given** 计算期间口径或来源水位变化  
**When** 提交结果  
**Then** 旧快照拒绝或明确版本化  
**And** 不同口径不得直接比较。

### Story 6.5：执行发布候选环境全量恢复与回切验收

**type:** release  
**dependsOn:** 2.8a,2.8b,5.5,6.4  
**readyWhen:** G-08/DRP-1.0.0、演练窗口/环境和完整一致性集已批准；本 Story 产出实际 RPO/RTO/回切/对账证据以满足 Release DoD  
**estimate:** 8d  
**status:** planned

作为技术负责人，我希望在发布候选环境证明 RPO、RTO、回切和业务连续性。

**需求覆盖：** FR-54（full）; NFR-6; NFR-8; NFR-5（仅校验不被 DR 替代，月度证据 owner 为 2.8a）

#### AC-6.5-HAPPY

**Given** 批准 DRPlan、RC 环境和一致性集齐备  
**When** 按故障域执行全量恢复与回切  
**Then** 证明 RPO 不超过 15 分钟、RTO 不超过 2 小时并完成 Candidate、Clue、TransferOrder、ExportJob、序列、水位、密钥和外部 ID 对账  
**And** 验证后才开放写流量。

#### AC-6.5-AUTH-DENIAL

**Given** 操作者、审批或演练窗口无效  
**When** 尝试切换或回切  
**Then** 默认 deny  
**And** 记录高风险审计。

#### AC-6.5-ILLEGAL-STATE

**Given** 备份、WAL、密钥版本或目标拓扑与计划漂移  
**When** 开始恢复  
**Then** 中止并保持写流量关闭  
**And** 不得缩小一致性集。

#### AC-6.5-BOUNDARY

**Given** 实际 RPO 或 RTO 恰等于目标  
**When** 评估演练  
**Then** 按小于等于边界通过，超出即失败  
**And** 原始时间证据不可手工改写。

#### AC-6.5-DEPENDENCY-FAILURE

**Given** 对象存储、密钥、数据库或外部对账失败  
**When** 恢复执行  
**Then** 保持受控隔离并记录未完成项  
**And** 不得宣告可恢复。

#### AC-6.5-IDEMPOTENCY

**Given** 同一演练步骤或回调重放  
**When** 重复执行  
**Then** 不重复写入业务事实  
**And** 使用 fencingToken。

#### AC-6.5-CONCURRENCY

**Given** 回切时有旧主写入或重试积压  
**When** 开放写流量  
**Then** 先完成 fencing、去重和水位收敛  
**And** 冲突失败关闭。

### Story 6.6：验收跨域保留、legal hold 与销毁回执

**type:** release  
**dependsOn:** 1.5,3.14c,6.4,6.5  
**readyWhen:** G-07/RS-1.0.0、legal hold、消费者/对象/备份清单和删除演练环境已批准；本 Story 产出实际 watermark/DeletionReceipt/备份过期证据以满足 Release DoD  
**estimate:** 8d  
**status:** planned

作为数据治理负责人，我希望证明所有生产数据类都按批准期限保留、受 legal hold 保护，并可形成真实跨域销毁回执。

**需求覆盖：** NFR-12（full）; NFR-15（retention contributor）

#### AC-6.6-HAPPY

**Given** 每个生产数据类均绑定 approved RetentionScheduleVersion、具名 owner、活跃/归档/删除期限、备份过期和无冲突 legal hold  
**When** 在发布候选环境执行删除/匿名化、对象清理与备份过期演练  
**Then** 各 owner 发布带 scheduleVersion、scope、结果和 watermark 的执行事实；所有消费者、读模型、对象存储与备份生命周期达到目标后才签发实际 DeletionReceipt  
**And** 必须保留的不可变合规审计事实不被改写。

#### AC-6.6-AUTH-DENIAL

**Given** 操作者不是对应数据 owner/数据治理执行人，或批准策略/执行授权已过期  
**When** 请求删除、匿名化或签发 DeletionReceipt  
**Then** 服务端拒绝且任何数据类、水位和回执状态不变  
**And** 记录最小拒绝审计。

#### AC-6.6-ILLEGAL-STATE

**Given** RetentionScheduleVersion 缺失、legal hold 生效、消费者清单不完整或仍有未确认 watermark  
**When** 尝试执行删除或把 RetentionExecution 标为 completed  
**Then** fail closed，不删除受 hold 数据且不得签发成功 DeletionReceipt  
**And** 返回逐 owner/消费者的未满足守卫。

#### AC-6.6-BOUNDARY

**Given** 活跃期、归档期、删除期、legal hold 结束或备份过期恰在策略边界  
**When** 以批准时区和服务端时钟评估资格  
**Then** 按 RetentionScheduleVersion 的含边界定义只进入一个阶段  
**And** 相邻前后样本及备份过期结果可复现。

#### AC-6.6-DEPENDENCY-FAILURE

**Given** 任一消费者、读模型、对象存储、密钥服务或备份生命周期无法确认删除/不可恢复  
**When** 汇总 RetentionExecution  
**Then** 保持 partial/failed 并列出未确认 watermark  
**And** 不得用“删除方案”或主表已删冒充实际 DeletionReceipt。

#### AC-6.6-IDEMPOTENCY

**Given** 同一 retentionExecutionId、scheduleVersion 和 scope 重放  
**When** 再次执行或汇总  
**Then** 返回原逐域结果和同一 DeletionReceipt（若已完成）  
**And** 不重复删除、匿名化或签发回执。

#### AC-6.6-CONCURRENCY

**Given** 删除执行期间新增 legal hold、对象版本或消费者水位变化  
**When** 基于旧版本提交完成  
**Then** 旧提交被拒绝并重新计算 scope/hold/watermark  
**And** 新 hold 优先且不形成混合版本成功回执。


## Epic 7：移动端在线轻量关怀处置

### Story 7.1：提供统一 App 在线移动壳与最小摘要

**type:** feature  
**dependsOn:** 1.2,3.6  
**readyWhen:** HIP/ISP/RFP/UXB/PAB-1.0.0 的宿主、WebView、375px、深链和移动 token 合同已批准  
**estimate:** 5d  
**status:** planned

作为移动中的责任人员，我希望从学校 App 在线查看待办、线索与证据摘要。

**需求覆盖：** FR-55（在线查看 phase）; NFR-18; NFR-19; NFR-20; NFR-31; NFR-34

#### AC-7.1-HAPPY

**Given** HIP/ISP/RFP/UXB/PAB-1.0.0 已批准且目标 WebView/375px 环境可用  
**When** 移动中的责任人员执行本 Story 的主流程  
**Then** 单列展示对象、事项、截止和主动作，不创建独立导航；不得持久化离线业务对象或授权投影  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### Story 7.2a：移动核实、结果记录与持续观察

**type:** feature  
**dependsOn:** 7.1,3.8,3.9a  
**readyWhen:** 移动核实字段、合法迁移、进程内草稿和清除时机已测试  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望在线完成联系、核实、结果记录与持续观察且不把未确认操作当成功。

**需求覆盖：** FR-55（辅导员命令 phase）; BR-4; NFR-34

#### AC-7.2a-HAPPY

**Given** 移动核实、结果、观察命令与进程内草稿策略已测试  
**When** 辅导员执行本 Story 的主流程  
**Then** 服务端确认后才显示成功；属实、噪声、待观察和复查使用同一 Clue 版本，未提交表单只保留在当前进程  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-7.2a-AUTH-DENIAL

**Given** 移动会话失效、责任关系已转移或 DelegationGrant 已撤销  
**When** 提交核实、结果或观察命令  
**Then** 服务端拒绝且不显示成功  
**And** 立即清空对象数据并只保留无对象信息的登录提示。

#### AC-7.2a-ILLEGAL-STATE

**Given** Clue 已关闭/合并或请求迁移不在 PRD 合法出边  
**When** 从移动端提交  
**Then** 返回 409 与最新状态  
**And** 不创建动作、复查任务或通知。

#### AC-7.2a-BOUNDARY

**Given** 提交发生在观察窗口、工作日截止或授权到期边界  
**When** 服务端按 Asia/Shanghai 与批准版本校验  
**Then** 得到与 Web 相同的唯一含边界结果  
**And** 超期/授权结果不由设备本地时钟决定。

#### AC-7.2a-DEPENDENCY-FAILURE

**Given** 网络在提交前或响应前中断  
**When** 用户尝试核实/观察  
**Then** 不进入离线队列、不显示成功，进程内草稿可见  
**And** 恢复后重新认证、拉取最新版本并由用户显式重试。

#### AC-7.2a-IDEMPOTENCY

**Given** 响应丢失后用户以同一 commandId 显式重试  
**When** 服务端已处理原命令  
**Then** 返回原 Clue/任务结果  
**And** 不重复状态事件、任务或通知。

#### AC-7.2a-CONCURRENCY

**Given** Web 已更新同一 Clue，而移动端持有旧 aggregateVersion  
**When** 移动端提交  
**Then** 返回 409、最新操作者/时间/版本并保留易失草稿  
**And** 用户比较后才能重新提交。

### Story 7.2b：移动接收、处理、补充与回填协同工单

**type:** feature  
**dependsOn:** 7.1,5.2c  
**readyWhen:** RFP/PIC/TSP/UXB-1.0.0 的移动字段投影、任务期授权和 Web 共用合同已批准  
**estimate:** 5d  
**status:** planned

作为协同处置人员，我希望在线接收、进入处理中、请求补充和回填结果。

**需求覆盖：** FR-55（协同命令 phase）; BR-6

#### AC-7.2b-HAPPY

**Given** 协同移动字段投影、任务期授权和 TransferSlaPolicyVersion 获批  
**When** 协同处置人员执行受支持动作  
**Then** 接收、处理中、请求补充和回填与 Web 使用相同 API、业务状态、dueAt/policyVersion 和 `TransferOrder.deliveryStatus`；confirmed 不替代业务接收  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-7.2b-AUTH-DENIAL

**Given** 移动会话失效、用户不是当前工单责任人或任务期授权已撤销  
**When** 尝试接收、处理、补充或回填  
**Then** 服务端拒绝且业务状态不变  
**And** 设备立即清空工单字段与进程内草稿。

#### AC-7.2b-ILLEGAL-STATE

**Given** 请求不符合 FR-40 合法迁移，或仅 `TransferOrder.deliveryStatus=confirmed` 而未业务接收  
**When** 移动端提交状态命令  
**Then** 返回 409 与当前业务状态  
**And** 不把投递确认显示为人员已接收。

#### AC-7.2b-BOUNDARY

**Given** 命令发生在 dueAt、pause interval 或任务期授权到期边界  
**When** 服务端按 TransferSlaPolicyVersion 校验  
**Then** 与 Web 得到相同结果并永久保留曾超期标记  
**And** 接收、补充或重提不重置 dueAt。

#### AC-7.2b-DEPENDENCY-FAILURE

**Given** 网络、字段策略或公共任务/回写适配器不可用  
**When** 执行协同命令  
**Then** 网络/授权前置失败时不提交；本地事实已提交而外部失败时只更新 `TransferOrder.deliveryStatus`  
**And** 不进入离线写队列或冒充外部确认。

#### AC-7.2b-IDEMPOTENCY

**Given** 响应丢失后同一 transfer commandId 重放  
**When** 服务端已处理原命令  
**Then** 返回原 TransferOrder/TransferEvent 版本  
**And** 不重复状态迁移、材料、通知或回写。

#### AC-7.2b-CONCURRENCY

**Given** Web 与移动基于同一旧 TransferOrder 版本并发处理  
**When** 两端提交不同命令  
**Then** 仅合法先提交者成功，另一端收到 409 和最新状态  
**And** 保留未提交易失草稿供人工比较。

### Story 7.2c：验收跨端幂等、并发、撤权与网络恢复

**type:** hardening  
**dependsOn:** 7.2a,7.2b,1.6c  
**readyWhen:** PP/AP/HIP/ISP/PIC/UXB-1.0.0 已批准，在线跨端装置、可信时钟和网络恢复 fixture 可用  
**estimate:** 8d  
**status:** planned

作为产品验收人员，我希望证明两类角色、四类轻量命令在 Web 与移动共享唯一状态且离线不泄漏。

**需求覆盖：** FR-55（full）; NFR-29; NFR-34

#### AC-7.2c-COMMAND-COVERAGE

**Given** 辅导员与协同处置人员均具有效在线授权  
**When** 分别执行快速核实、结果记录、持续观察和协同处理  
**Then** 四类命令均由服务端确认后显示成功且另一端收敛同一 aggregateVersion  
**And** 复杂配置和驾驶舱不进入移动端。

#### AC-7.2c-HAPPY

**Given** Web 或移动端提交事务并记录 committedAt  
**When** 另一在线端接收状态  
**Then** 应用同一或更高 aggregateVersion、呈现可操作状态并发出 ui.state-observed  
**And** P95 不超过 5 秒。

#### AC-7.2c-AUTH-DENIAL

**Given** 责任撤销、代办到期、登出或换号  
**When** 下一次读取或提交  
**Then** 立即失权并清除进程内草稿  
**And** 历史深链不得读取对象。

#### AC-7.2c-ILLEGAL-STATE

**Given** 断网或离线缓存试图提交业务对象  
**When** 用户操作  
**Then** 禁止后台持久化和自动发送  
**And** 恢复网络后重新认证、授权并显式重试。

#### AC-7.2c-BOUNDARY

**Given** 观测恰在 5 秒或 grant 结束边界  
**When** 计算 SLI 或权限  
**Then** 按服务端同步时钟和含边界策略判定  
**And** 冻结设备、宿主和网络 Profile。

#### AC-7.2c-DEPENDENCY-FAILURE

**Given** WebSocket、轮询或公共任务依赖失败  
**When** 等待同步  
**Then** 显示连接和数据时间，不宣告完成  
**And** 恢复后拉取最新 aggregateVersion。

#### AC-7.2c-IDEMPOTENCY

**Given** 同一 requestId 从两端重放  
**When** 处理提交  
**Then** 只产生一个业务事实  
**And** 两端收敛同一版本。

#### AC-7.2c-CONCURRENCY

**Given** 两端基于同一旧版本提交不同动作  
**When** 服务端仲裁  
**Then** 一个成功，另一个返回最新操作者、时间和版本  
**And** 保留易失草稿供比较。


## Epic 8：受治理且可回退的智能增强

### Story 8.1：执行策略发布关卡并建立初始 cohort

**type:** release  
**dependsOn:** 6.3c,6.5,6.6  
**readyWhen:** SGP/HRAP-1.0.0 的阈值、比较基线、D4、initial cohort 与回退合同已批准  
**estimate:** 8d  
**status:** planned

作为治理人员，我希望在任何智能建议曝光前证明策略资格。

**需求覆盖：** FR-51; BR-10; NFR-33

#### AC-8.1-HAPPY

**Given** StrategyGatePolicyVersion、比较基线、数据质量和人工复核齐备  
**When** 申请 initial cohort  
**Then** 按策略验证 Precision@K、Recall@K、噪声和分群后只开放批准 cohort  
**And** 发布记录链接完整证据。

#### AC-8.1-AUTH-DENIAL

**Given** 申请人无 strategy.publish 权限或审批过期  
**When** 申请发布  
**Then** 默认 deny  
**And** 不创建灰度资格。

#### AC-8.1-ILLEGAL-STATE

**Given** 阈值、K、比较基线、分群规则或证据链接缺失  
**When** 评审  
**Then** 拒绝且保持策略未发布  
**And** 不得采用隐藏默认值。

#### AC-8.1-BOUNDARY

**Given** 每个比较分群有效样本恰等于 StrategyGatePolicyVersion 的 minSample 或低于 minSample  
**When** 计算关卡  
**Then** 等于边界时按策略评估；不足只报区间、不作通过结论  
**And** K 取目标角色周期内可处理容量。

#### AC-8.1-DEPENDENCY-FAILURE

**Given** 真值集、质量或审批服务不可用  
**When** 执行关卡  
**Then** 保持 strategy release-ineligible  
**And** 不得沿用过期证据。

#### AC-8.1-IDEMPOTENCY

**Given** 同一策略版本申请重放  
**When** 再次评审  
**Then** 返回原关卡记录  
**And** 不重复开放 cohort。

#### AC-8.1-CONCURRENCY

**Given** 评审期间策略或门禁版本变化  
**When** 提交批准  
**Then** 版本冲突并要求重新评审  
**And** 旧证据不跨版本复用。

### Story 8.2：灰度提供非强制关怀建议

**type:** feature  
**dependsOn:** 8.1  
**readyWhen:** 初始 cohort 已获资格且建议字段、反馈原因和禁用动作已测试  
**estimate:** 5d  
**status:** planned

作为辅导员，我希望获得可编辑建议而不让模型替我决定。

**需求覆盖：** FR-49; BR-1; NFR-23

#### AC-8.2-HAPPY

**Given** 初始 cohort 已获资格且建议字段、反馈原因和禁用动作已测试  
**When** 辅导员执行本 Story 的主流程  
**Then** 建议明确标注且可采纳、编辑、拒绝、反馈，不得自动发送、改等级、转介或关闭线索；服务失败不影响人工闭环  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-8.2-FEEDBACK

**Given** 建议已显示且关联 strategyVersion  
**When** 用户采纳、编辑、拒绝或选择版本化反馈原因  
**Then** 保存用户实际动作、编辑差异、原因和版本  
**And** 反馈不得自动成为学生事实、规则真值或高影响动作。

#### AC-8.2-AUTH-DENIAL

**Given** 用户不在获批 cohort、对象已撤权或策略资格失效  
**When** 请求建议  
**Then** 默认 deny 且不泄露建议或对象存在性  
**And** 稳定规则与人工闭环继续可用。

#### AC-8.2-ILLEGAL-STATE

**Given** 建议试图自动发送、改等级、转介、关闭 Clue 或调用高风险动作  
**When** 服务端校验输出/工具请求  
**Then** 阻断整个高影响动作  
**And** 记录策略违规而不改变业务状态。

#### AC-8.2-BOUNDARY

**Given** 请求恰在 strategyVersion 生效/失效或 cohort 纳入/移出边界  
**When** 加载或提交建议反馈  
**Then** 按服务端有效区间只使用一个明确版本；边界外不生成新建议  
**And** 已展示历史建议只能作为带原 strategyVersion 的只读历史，不可触发高影响动作。

#### AC-8.2-DEPENDENCY-FAILURE

**Given** 增强服务超时、拒绝或返回无效 schema  
**When** 获取建议  
**Then** 显示增强不可用并回到结构化解释  
**And** 不阻断核实、观察或协同流程。

#### AC-8.2-IDEMPOTENCY

**Given** 同一反馈 requestId 重放  
**When** 再次提交  
**Then** 返回原反馈事实  
**And** 不重复统计或创建动作。

#### AC-8.2-CONCURRENCY

**Given** cohort 资格撤销或策略回退与建议请求并发  
**When** 提交反馈或加载建议  
**Then** 按最新 strategyVersion 拒绝旧请求或明确标注历史版本  
**And** 不把旧建议用于新高影响动作。

### Story 8.3：安全查询已定义汇总指标

**type:** feature  
**dependsOn:** 8.1,6.4  
**readyWhen:** MPP/SGP/RFP-1.0.0 的授权口径库、汇总读模型和拒答策略已批准  
**estimate:** 5d  
**status:** planned

作为授权管理人员，我希望用自然语言理解已定义运行指标。

**需求覆盖：** FR-50; BR-1; BR-11

#### AC-8.3-HAPPY

**Given** 授权口径库、MetricPublicationPolicyVersion、汇总读模型和拒答策略获批  
**When** 授权管理人员执行本 Story 的主流程  
**Then** 只返回已定义汇总指标及口径、policyVersion、筛选、时间、质量和来源；个体、越权、敏感或无法理解的问题明确拒答  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

#### AC-8.3-RESULT-STATES

**Given** 查询结果分别为真实零值、数据缺失、质量不可用或低于最小分组  
**When** 生成回答  
**Then** 分别显示 zero、missing、unavailable 或 suppressed，suppressed 不显示为 0 且不提供个体下钻  
**And** 保存 MetricPublicationPolicyVersion 与拒答原因。

#### AC-8.3-AUTH-DENIAL

**Given** 请求包含个体、未授权切片或试图推断 suppressed 分组  
**When** 执行问数  
**Then** 服务端拒答且不泄露对象或小分组存在性  
**And** 记录授权范围与审计结果。

#### AC-8.3-DEPENDENCY-FAILURE

**Given** 指标口径、质量或汇总读模型不可用/陈旧  
**When** 执行问数  
**Then** 显示 unavailable 与数据截止时间，不生成看似成功的答案  
**And** 恢复后由用户显式重试。

### Story 8.4：回退智能策略到稳定规则基线

**type:** hardening  
**dependsOn:** 8.2,8.3  
**readyWhen:** SGP/HRAP-1.0.0 的稳定基线、回退触发器和恢复验证方案已批准  
**estimate:** 5d  
**status:** planned

作为治理人员，我希望异常时恢复可信基线且不中断人工工作。

**需求覆盖：** FR-52; BR-10; NFR-8

#### AC-8.4-HAPPY

**Given** 已发布策略触发批准回退条件  
**When** 具权人员执行回退  
**Then** 新请求切换到稳定规则基线  
**And** 人工工作台、历史线索和未完成任务不中断。

#### AC-8.4-AUTH-DENIAL

**Given** 无 strategy.rollback 权限或审批无效  
**When** 请求回退  
**Then** 默认 deny  
**And** 保存拒绝审计。

#### AC-8.4-ILLEGAL-STATE

**Given** 稳定基线缺失、不可用或与发布记录不一致  
**When** 尝试回退  
**Then** 中止并保持安全隔离  
**And** 不得切到未知版本。

#### AC-8.4-BOUNDARY

**Given** 请求恰在 cohort 扩大或策略生效边界  
**When** 回退  
**Then** 按单调版本和服务端时间阻止新策略请求  
**And** 在途结果明确版本。

#### AC-8.4-DEPENDENCY-FAILURE

**Given** 增强服务、配置分发或验证探针失败  
**When** 回退  
**Then** 人工闭环保持可用并继续验证稳定基线  
**And** 不得改写历史建议。

#### AC-8.4-IDEMPOTENCY

**Given** 同一 rollback commandId 重放  
**When** 再次执行  
**Then** 返回原回退记录  
**And** 不重复分发。

#### AC-8.4-CONCURRENCY

**Given** 发布和回退并发  
**When** 按策略 aggregateVersion 仲裁  
**Then** 回退优先进入安全稳定基线或冲突失败关闭  
**And** 记录双方版本。

## 实施顺序与释放约束

1. Epic 1 先完成生产供应链、宿主、身份、审计、授权和公共适配器契约。
2. Epic 2 只发布数据质量与运行事实；在 Epic 3 门禁通过前不得创建规则评估、Candidate 或 Clue。
3. Epic 3 先交付规则治理与质量连接，再交付 CandidateAdmissionDecision、Candidate、Clue 和闭环；公共任务最终验收推迟到 Story 5.5。
4. Epic 4 中 ECON-012、NIGHT-001、ACADEMIC-001、CORROBORATE-001 的 RC-1.0.0 均已批准，当前 runtime inactive/fused；Story 4.1a 负责前三条规则的资格/canary/production，Story 4.7 负责合证规则的资格/canary/production；任一规则在依赖、样本、D4 与 canary 未通过前不得被对应场景用于生产。
5. Epic 5 先形成真实协同生产者，再由 Story 5.5 统一验收 FR-4/FR-59。
6. Epic 6 在真实协同事实之后验收 FR-46/FR-60，并由 Story 6.5 在发布候选环境完成 FR-54/DR 最终验收。
7. Epic 7 只允许在线移动处理；Story 7.2c 是 FR-55 的跨端最终验收。
8. Epic 8 必须先通过 Story 8.1 的 initial cohort 发布关卡，再开放建议或自然语言查询；任何关卡失败均可由 Story 8.4 回到稳定规则基线。

## 完成判定

- FR-1–FR-62 每项只有一个 owner Story，owner 的所有专属 AC 均通过；只有被 owner 的 `dependsOn`/`readyWhen` 明列为前置的 contributor 必须先通过，后置 hardening/conformance 按其自身 Story 与发布 Gate 验收。
- BR-1–BR-12 与 NFR-1–NFR-34 的指定证据 Story 产生可复现证据；仅口头确认或通用 DoD 不算完成。
- G-01—G-09 已进入 approved-for-implementation 并记录 Hei、版本、生效日期与证据 URI；`closed` 仅用于 DEC。运行证据由表内 Story/Release owner 产生，不回写成 Gate 外部审批。
- 任何 Story/Release DoD 未通过、关键 AC 未覆盖、基线/证据版本漂移或 traceability 对账失败时，发布状态保持 NOT READY。
