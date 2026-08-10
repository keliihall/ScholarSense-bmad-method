---
title: "Sprint Change Proposal：Story 2.3 QualitySnapshot 确定性哈希收口"
date: "2026-08-09"
mode: batch
status: approved
scopeClassification: minor
triggerStory: "2.3"
triggerStoryKey: "2-3-封账批次并计算质量快照"
recommendedApproach: direct-adjustment
accountableOwner: Hei
decisionId: "DEC-019"
addendumId: "DEC-019-QSHM-ADDENDUM"
authorityRef: "AUTH-2026-08-09-001"
approvedBy: "Hei"
approvedAt: "2026-08-09T19:24:55+08:00"
---

# Sprint Change Proposal：Story 2.3 QualitySnapshot 确定性哈希收口

## 1. Issue Summary

### 1.1 触发问题

Story 2.3 的 Task 0 和 Task 1 已完成；Task 2 设计审计在创建 `QualitySnapshot` 前发现两个仍不能由现有受控输入唯一回答的问题：

1. `immutableHash` 没有精确的材料字段、自排除规则、数组顺序、null/optional 规则和时间规范化规则。
2. Story 文本要求每项指标另存 `rawCount`，但 QMDP 只对 count gate 使用了“`numerator=<rawCount>`”这一计算描述；已冻结的 42-field QualitySnapshot 投影没有独立 `rawCount`，ratio、duration 和 composite 也没有获批的独立字段语义。

这不是现有实现缺陷，而是实现前发现的最后一个规范缺口。当前 canonical profile 的 self-digest 规则只允许移除 schema 明示的 `contentSha256`，不能据此自行排除 `immutableHash`；同时，若把 `snapshotId/evaluatedAt/traceId` 等运行字段计入内容摘要，同一冻结业务输入在不同重试中会产生不同结果。

因此 Task 2 暂停。开发者不得自行选择 hash 字段、排序方式或 `rawCount` 含义。

### 1.2 问题类型

- [x] 实施前发现的窄范围受控语义缺口
- [x] 可复现性与跨 Java/PostgreSQL 一致性风险
- [x] Story 内部字段要求与已冻结投影不一致
- [N/A] 新增产品能力
- [N/A] 改变 MVP、Epic 目标或用户流程

### 1.3 证据

- Story 2.3 AC-QUALITY-CALCULATION 要求同 manifest/watermark/cutoff/policy 重放得到同一 immutable hash，并同时列出 `rawCount`。
- `SCHOLARSENSE-CANONICAL-JSON-1.0.0` 只定义 canonical bytes，明确禁止推断 self-digest exclusions。
- `FIELD-PROJECTION-POLICY-BINDING-1.1.0` 冻结 42 个 QualitySnapshot leaf path，包含 numerator/denominator/value，但没有 `rawCount`。
- `QMDP-1.0.0` 已冻结 ratio/count/duration/composite-and 的 numerator/denominator；只有 count gate 明确 `numerator=<rawCount>, denominator=1`。
- 当前尚未创建 `QualitySnapshot` 生产类、表或事件，因此可以无回滚地直接收口。

## 2. Impact Analysis

### 2.1 Epic 与 Story

| 对象 | 影响 | 结论 |
|---|---|---|
| Epic 2 | 目标、顺序和范围不变 | 可按原计划完成 |
| Story 2.3 Task 0 | 已完成的 QMDP、投影、retention 和 readiness 证据不重开 | 保持完成 |
| Story 2.3 Task 1 | 批次/事实/lineage 不依赖 snapshot hash 字段表 | 保持完成 |
| Story 2.3 Task 2 | 开始实现前物化本提案的 hash profile，并删除独立 `rawCount` 要求 | 窄范围调整 |
| 2.4、2.5a—c | 只校验并传递 snapshotId/hash/profile；不重新定义 hash | 继承合同 |
| 3.4、4.3 | 下游 EvidenceSnapshot 保存 snapshotId、immutableHash 与 QMDP version/digest | 不改变流程 |
| 6.6 | retention scope 继续绑定 snapshotId + immutableHash | 不改变 owner/时序 |

无需新增、删除、拆分、重排或重编号 Epic/Story；`sprint-status.yaml` 无结构性变化。

### 2.2 PRD、Architecture 与 UX

- **PRD：无需修改。** FR-11/NFR-9 已要求公式、分子/分母、批次原始数与版本证据，没有要求每个 metric 持久化一个语义重复的 `rawCount` 字段。
- **Architecture：无需修改。** AD-2/4/5/24 已覆盖 ingestion-quality 唯一 owner、不可变证据、版本化质量门和事件完整性；精确 hash preimage 属合同层机械规则。
- **UX：无需修改。** `data-quality-panel` 已使用 numerator/denominator/value；`immutableHash` 保持技术证据，不新增用户流程或主界面字段。

### 2.3 测试、合同与发布

- 新增 additive `QSHM-1.0.0` hash profile、schema、golden/negative vectors 和 lock/checker。
- 既有 QMDP-1.0.0、field-projection 1.1.0、retention、release V1—V4 与历史 raw bytes 不修改。
- Java 与 PostgreSQL 必须消费同一 canonical material vectors；任一字段覆盖、顺序、null、时间或 digest 漂移均 fail closed。
- 未来 release successor 登记 QSHM；本提案批准不等于 Java/PostgreSQL 运行证据已通过。

## 3. Path Forward Evaluation

### Option 1：直接补充 DEC-019，并移除冗余 rawCount（推荐）

- 可行性：高
- 工作量：低，预计 0.5—1 个工作日增量
- 风险：低；通过闭合材料表、golden bytes、mutation 和 Java/PostgreSQL parity 控制
- 优点：保留现有 42-field 投影，不产生第二套原始计数语义

### Option 2：新增持久化 rawCount 与投影 successor

- 可行性：中
- 工作量：中
- 风险：中；必须为 ratio/duration/composite/N/A 新增未经批准的独立语义，并长期校验它与 numerator/denominator 一致
- 结论：不推荐。没有新增业务信息，却扩大 DB、DTO、事件、投影和 hash 表面。

### Option 3：由开发者在代码中自行选择 hash 字段

- 可行性：表面可行
- 风险：高；Java/SQL/事件重放会形成多个合理但不等价的摘要
- 结论：拒绝，违反 Story 2.3 fail-closed 条件。

回滚 Task 0/1 不能补齐该语义；缩减 MVP 或删除 immutable hash 会削弱 FR-11/NFR-9/NFR-11，不采用。

## 4. Approved Normative Addendum

Hei 于 2026-08-09T19:24:55+08:00 明确回复“批准提案”。本节据此成为 `DEC-019` 的规范性 §4.9，并由新的 `AUTH-2026-08-09-001` 独立批准；不得把旧 `AUTH-2026-08-08-001` 伪装为已覆盖本补充。

### 4.1 Profile identity 与算法

| 项 | 提议值 |
|---|---|
| profile | `QSHM-1.0.0`（QualitySnapshot Hash Material Profile） |
| domainTag | `scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1` |
| canonicalization | `SCHOLARSENSE-CANONICAL-JSON-1.0.0` |
| algorithm | `SHA-256` over canonical UTF-8 bytes |
| stored form | lowercase `sha256:<64 hex>` |
| self exclusion | `immutableHash` 不进入 material；只排除 §4.2 明列字段，不推断其他排除项 |
| unknown/missing | 任一材料字段缺失、额外、重复、非法或摘要不匹配均为 technical evaluation error；批次保持 `sealed`，不创建 snapshot |

### 4.2 Exact hash material

canonical material 是一个 `additionalProperties=false` 的对象，必须包含以下字段：

| 分组 | 精确字段 |
|---|---|
| domain separation | `domainTag`, `hashProfileVersion`（固定 `QSHM-1.0.0`）、`hashProfileDigest`（由 additive lock 绑定） |
| batch/result | `batchId`, `sourceId`, `assessedBatchStatus`, `overallResult` |
| frozen time/watermark | `observationWindow.startAt`, `observationWindow.endAt`, `cutoffAt`, `watermark` |
| metric evidence | `metricResults[]`，字段见 §4.3 |
| impact | `impactScopeCodes[]` |
| governance/retention | `sourceOwnerRef`, `approvalRef`, `effectiveAt`, `retentionScheduleVersion` |
| controlled inputs | `qualityMetricDecisionProfileVersion`, `qualityMetricDecisionProfileDigest`, `qualityGateVersion`, `qualityGateDigest`, `canonicalizationProfile`, `manifestDigest`, `sourceSchemaVersion`, `sourceSchemaDigest` |
| correction lineage | `lineageId`, `supersedesSnapshotId`（根 snapshot 固定显式 `null`） |

以下字段明确不进入 material：

- `snapshotId`：持久对象身份，不是评估内容；
- `evaluatedAt`：有效提交时间和 retention 起算点，不是冻结计算输入；
- `traceId`：单次执行关联身份；
- `aggregateVersion`：CAS/事件并发身份，由事务边界独立验证；
- `immutableHash`：self field；
- audit/outbox/event/storage metadata。

排除只服务“相同冻结业务输入跨 retry 得到相同内容摘要”；被排除字段仍必须由 DB immutability、CAS、审计和事件契约保护，不能更新或伪造。

### 4.3 Metric material、顺序与 N/A

每个 `metricResults[]` 元素恰含：

`metricId, formulaId, formulaVersion, result, applicable, numerator, denominator, valueBasisPoints, unit, operator, thresholdNumerator, thresholdDenominator, boundary, reasonCode`

规则：

1. 输出数组先按 QMDP `commonMetrics[]` 原序放入当前 source 的 `applicableCommonMetricIds` 精确选中项，再按当前 source 的 `sourceGates[]` 原序放入全部 source gate；每个 formula 恰出现一次。
2. 由入选 metric/gate 的运行时闭合 predicate 得出的 N/A 项仍必须包含；source profile 未选中的 common metric 不进入 snapshot。不得依赖调用方 Map/SQL row 的自然顺序。缺项、额外项、未知 formula、重复 formulaId 或顺序无法由 policy 重建均 fail closed。
3. `applicable=false` 固定为 `numerator=0, denominator=0, valueBasisPoints=null, result=not-applicable, reasonCode=null`；它不进入 overall AND。
4. `applicable=true && denominator=0` 仍是 `QUALITY_POLICY_ZERO_DENOMINATOR` technical error，不创建 snapshot/hash。
5. nullable 字段必须始终出现并显式写 JSON `null`；不得把 missing 当作 null。

### 4.4 `rawCount` closure

`rawCount` 不成为独立 DB、domain snapshot、DTO、event、projection 或 hash 字段。其原有文字含义按 calculation kind 由既有规范操作数完整表达：

| kind | 唯一规范原始证据 |
|---|---|
| ratio | `numerator=通过/有效单位数`, `denominator=适用总单位数`；批次原始记录总数仍在 sealed manifest |
| count | `numerator=原始计数`, `denominator=1`；原 §4.8 的 `<rawCount>` 是 numerator 的描述性别名 |
| duration | `numerator=整数毫秒`, `denominator=1` |
| composite-and | `numerator=通过成员数`, `denominator=适用成员数` |
| not-applicable | `applicable=false, numerator=0, denominator=0` |

这样保留 FR-11/NFR-9 所需的“公式、分子分母、批次原始数”证据，同时避免两套可漂移的计数真相；field-projection 1.1.0 无需 successor。

### 4.5 Canonical arrays、null 与时间

1. `impactScopeCodes[]` 必须唯一，并按 Unicode code point 升序；重复 code fail closed，空集合编码为 `[]`。
2. hash material 内的 `valueBasisPoints`、`reasonCode`、`supersedesSnapshotId` 等 nullable 字段必须显式存在；无值编码为 JSON `null`。根 snapshot 的 `supersedesSnapshotId` 固定为 `null`；successor 必须绑定直接前驱 snapshot ID。
3. 进入 hash material 的 instant 先解析为同一 `Instant`，要求不高于微秒精度，并统一编码为 UTC、固定六位微秒的 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`；等价的 `Z` 与 `+08:00` 输入得到相同 bytes。更高精度、无 offset、默认系统时区或解析歧义均 fail closed。
4. 对象 key 按 Unicode code point 升序；无字符串外空白；UTF-8 无 BOM；只允许 IEEE-754 safe-range 整数，禁止 binary float 和 negative zero。

### 4.6 Required conformance evidence

至少冻结并执行：

- exact material schema、profile instance、golden canonical UTF-8 hex + expected SHA-256；
- 同业务输入只改变 `snapshotId/evaluatedAt/traceId/aggregateVersion` 时 hash 不变；
- 每个 included top-level/metric field 单项变化时 hash 改变，其中必须覆盖根 `supersedesSnapshotId=null` 与 successor 直接前驱变化；
- 调用方 metric/impact 输入乱序时，由受控顺序重建后 hash 相同；
- missing/extra/duplicate formula、duplicate impact、null→missing、self inclusion 全部拒绝；
- UTC/`+08:00` 同 instant 同 hash，边界相邻微秒不同 hash；
- Java 与 PostgreSQL 18.4 对同一 golden vectors 产生完全相同 canonical bytes 和 lowercase digest；
- loader 在 startup、evaluate 前和 final commit 前验证 QSHM version+digest，未知或漂移 fail closed。

`hashProfileVersion/hashProfileDigest` 是 snapshot 内部技术绑定，可进入 DB 与受控事件，但仍由 field-projection 1.1.0 按 unknown-path=omit 隐藏；不因此新增 R6 字段或 field-projection successor。

## 5. Detailed OLD → NEW Change Proposals

### 5.1 Story 2.3 AC-QUALITY-CALCULATION

**OLD：**

> 每个适用指标保存 `numerator/denominator/rawCount/value/unit/operator/threshold/boundary/result/reason`，结果可由同一批次 manifest、水位、cutoff 和 policy version 重放到同一 immutable hash。

**NEW：**

> 每个适用指标保存 `numerator/denominator/value/unit/operator/threshold/boundary/result/reason`；`rawCount` 不作为独立字段，四类原始证据按 DEC-019 §4.9/QSHM-1.0.0 的操作数映射表达。结果由同一冻结业务输入按 QSHM exact material、policy order、显式 null 和 canonical time 重放到同一 immutable hash。

### 5.2 Task 2

- 新增 2.0：在 calculator 前物化 QSHM schema/profile/golden/negative vectors/lock/checker；批准前不得实施。
- 2.1：fail-closed loader 同时验证 QMDP 与 QSHM version+digest。
- 2.2：`QualityMetricResult` 不新增 `rawCount`；保存闭合 numerator/denominator/value evidence。
- 2.3：严格按 QSHM 构建 material；snapshot + outcome 仍在 owner 本地事务原子提交。
- 2.4：增加 self-exclusion、included-field mutation、order/null/time、Java/PostgreSQL canonical bytes/hash parity。

### 5.3 下游 handoff

- 2.4 只验证并传递 `snapshotId + immutableHash + QMDP/QSHM version/digest`，不自行重算材料语义。
- 3.4/4.3 的 EvidenceSnapshot 保存上述引用和自足最小证据。
- 6.6 继续使用 snapshotId + immutableHash 绑定 retention scope；不把 hash 当作删除运行证据。

无需修改 PRD、`epics.md`、Architecture spine、DESIGN/EXPERIENCE 或 sprint backlog 结构。

## 6. Correct-Course Checklist Status

| Checklist item | 状态 | 结果 |
|---|---|---|
| 1.1 识别触发 Story | [x] | Story 2.3 Task 2 设计边界 |
| 1.2 定义核心问题 | [x] | hash material 与 rawCount 不唯一 |
| 1.3 收集证据 | [x] | Story、canonical profile、QMDP、42-field projection 已核验 |
| 2.1 当前 Epic 可完成 | [x] | Epic 2 目标与顺序不变 |
| 2.2—2.5 Epic/Story 结构影响 | [N/A] | 无新增、删除、拆分、重排 |
| 3.1 PRD 冲突 | [N/A] | 既有 FR/NFR 已由 numerator/denominator/manifest 满足 |
| 3.2 Architecture 冲突 | [N/A] | 属合同层机械闭合 |
| 3.3 UX 冲突 | [N/A] | 无新表面或用户流程 |
| 3.4 其他制品 | [x] | Story 2.3、QSHM contracts/checker、future release successor |
| 4.1 Direct adjustment | [x] | 可行且推荐 |
| 4.2 Rollback | [N/A] | 无收益，且 Task 0/1 不受影响 |
| 4.3 MVP review | [N/A] | 产品目标不变 |
| 4.4 选择路径 | [x] | Option 1 |
| 5.1—5.5 Proposal | [x] | 问题、影响、规范 mapping、OLD→NEW、handoff 齐备 |
| 6.1 完整性检查 | [x] | 包含材料、顺序、null、时间、自排除、跨实现证据 |
| 6.2 一致性检查 | [x] | 不改 QMDP/投影/retention 历史文件，无扩权/越界 owner |
| 6.3 用户明确批准 | [x] | Hei 于 2026-08-09T19:24:55+08:00 明确回复“批准提案” |
| 6.4 sprint-status | [N/A] | 无 backlog 结构变化 |
| 6.5 handoff | [x] | 已路由至 Developer，按 Task 2.0→2.4 执行 |

## 7. Handoff Plan

### 7.1 变更分类

**Minor / Direct Adjustment**：只闭合 Story 2.3 Task 2 的合同与文字，不改变产品战略、MVP、Epic 顺序、架构边界或 UX。

### 7.2 获批执行顺序

1. Product Owner：已把本提案第 4 节登记为 DEC-019 §4.9，并创建 `AUTH-2026-08-09-001` approval record。
2. Developer：先修改 Story 2.3 的 AC/Task 2，再以 TDD 物化 QSHM contracts/checker；不得改写历史 QMDP/field-projection/retention/release 文件。
3. Developer：QSHM checker 全绿后恢复 Task 2.1—2.4；Task 3—7 仍按原顺序。
4. Architect：只复核 domain-separated hash profile，无需重规划架构。
5. UX：无需动作。

### 7.3 成功标准

- 相同冻结业务输入跨 retry、trace、提交时间和 metric 输入顺序得到同一 hash。
- 每个 hash-covered 字段变化都改变 hash；missing/extra/duplicate/self inclusion 均拒绝。
- Java/PostgreSQL canonical bytes 与 SHA-256 完全一致。
- `rawCount` 不进入任何持久化或传输模型，numerator/denominator 与 sealed manifest 仍提供完整原始证据。
- QSHM 未知或 digest mismatch 时批次保持 sealed，不产生 quality-failed snapshot。

## 8. Approval

当前状态：**已由 Hei 批准（2026-08-09T19:24:55+08:00）**。

批准语义：

> 批准采用 Option 1；批准第 4 节成为 DEC-019 §4.9 / QSHM-1.0.0 的规范性补充；批准按第 5—7 节修改 Story 2.3 与 additive contracts/checker。旧 QMDP、DCC、QG、field-projection、retention 与 release V1—V4 不得原位改写；该批准不得冒充 Java/PostgreSQL 或运行证据已通过。

本次获批前，Story 2.3 Task 2 一直保持 HALT；获批后只从新增 Task 2.0 的合同边界恢复，Task 0/1 的已完成结果不回滚。

批准记录：Hei 在完整 Batch 提案展示后明确回复“批准提案”。该回复完成本工作流的用户批准门，使 `AUTH-2026-08-09-001` 成为 `DEC-019 §4.9 / QSHM-1.0.0` 的 accountable approval reference，并授权按第 5—7 节实施；它不构成任何 Java/PostgreSQL 或运行证据。

## 9. Workflow Execution Log

- 2026-08-09：Story 2.3 Task 2 设计审计发现 immutable hash material 与 `rawCount` 残余歧义，按 fail-closed 条件暂停实现。
- 2026-08-09：完成 PRD、Epic、Architecture、UX、下游 handoff 与合同影响审查，分类为 Minor / Direct Adjustment，并生成本 Batch 提案。
- 2026-08-09T19:24:55+08:00：Hei 明确回复“批准提案”，批准 Option 1、DEC-019 §4.9 / QSHM-1.0.0、删除独立 `rawCount` 及 additive contract/checker handoff。
- Sprint/backlog：未新增、删除、拆分、重排或重编号 Epic/Story；Story 2.3 保持 `in-progress`。
- Handoff：Developer 先完成 Story 2.3 Task 2.0 QSHM contracts/checker，再按 2.1—2.4 恢复实现；Task 0/1 不回滚，历史受控合同不改写。
