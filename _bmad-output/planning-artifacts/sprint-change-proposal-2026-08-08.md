---
title: "Sprint Change Proposal：Story 2.3 可执行质量口径与 QualitySnapshot 治理闭环"
date: "2026-08-08"
mode: batch
status: approved
scopeClassification: moderate
triggerStory: "2.3"
triggerStoryKey: "2-3-封账批次并计算质量快照"
recommendedApproach: direct-adjustment
accountableOwner: Hei
decisionId: "DEC-019"
authorityRef: "AUTH-2026-08-08-001"
approvedBy: Hei
approvedAt: "2026-08-09T10:02:22+0800"
mappingAddendumApprovedAt: "2026-08-09T10:32:53+0800"
materializationAddendumApprovedAt: "2026-08-09T12:00:59+0800"
---

# Sprint Change Proposal：Story 2.3 可执行质量口径与 QualitySnapshot 治理闭环

## 1. Issue Summary

### 1.1 触发问题

Story 2.3 Task 0.1 的只读 readiness 审计证明：现有批准链确认了质量目标和阈值意图，但不能唯一生成可执行质量策略。

- `QG-1.0.0` 只有 10 个数值门和 17 条自由文本 override；其 schema 没有结构化 metric、formula、numerator、denominator、zero-denominator、rounding、comparison-stage、window、cutoff、timezone 或 applicability。
- PRD FR-11 要求这些语义必须保存，NFR-9 只给出 99.5% / 98% / 99% 目标，二者不能替代算法定义。
- DCC 提供业务键、schema 和自由文本 SLO，但没有把 17 个来源的到达 lane、时间对、窗口和分母物化为执行配置。
- RFP 已批准 R6 在 owned-source 范围读取 `QualitySnapshot`，但 field-projection 1.0.0 没有该对象，也不能表达嵌套 metric 叶路径。
- RS 已批准“报表与运营快照 2 年”，但没有把 `QualitySnapshot` 明确映射到该类别，也没有冻结起算点、删除动作和 owner-local deletion-result handoff。

readiness probe 因而正确失败：

```text
READINESS_FAIL
- QG-1.0.0 has no non-empty structured metrics[]
- QG-1.0.0 sourceOverrides are free text
```

如果继续开发，formula、分母、边界、零分母、舍入、字段分类和保留起算点都只能由开发者猜测；这违反 Story 2.3 的 fail-closed 条件，也会使 Java、SQL、UI 和审计证据产生多个合理但不等价的实现。

### 1.2 问题类型

- [x] 实施期发现的受控基线不完备
- [x] 已批准“阈值意图”被误表述为“算法可直接实现”
- [x] 跨 PRD / Epic / Architecture / UX / contract 的一致性缺口
- [N/A] 新增产品能力
- [N/A] 缩减 MVP
- [N/A] 改变 Epic 目标或业务优先级

### 1.3 触发证据

1. Task 0 gap/decision 表：`_bmad-output/implementation-artifacts/2-3-task-0-gap-decision-2026-08-08.md`。
2. `contracts/data-catalog/qg-1.0.0.json`：没有结构化 `metrics[]`，source override 全为自由文本。
3. PRD FR-11 / NFR-9：要求保存公式证据，但没有给出执行算法。
4. Architecture AD-5：声称 `qualityGateVersion` 冻结完整公式语义，与 QG 实体不一致。
5. RFP / field-projection：R6 可读 `QualitySnapshot`，但投影 binding 1.0.0 无该对象。
6. RS / AD-27：仅有类别级期限和通用删除原则，没有 owner-local 结果契约。
7. runtime parity：生产授权目录给 R6 增加了冻结 RFP 未批准的 `Job/data-quality.read`，现有 checker 没有捕获 contract ↔ runtime 漂移。

## 2. Impact Analysis

### 2.1 Epic 影响

| Epic / Story | 影响 | 结论 |
|---|---|---|
| Epic 2 / 2.3 | Task 0 先发布获批的 executable decision profile、projection successor 和 retention handoff；之后才可实现批次与快照 | 修改 readyWhen/AC/实施基线，不改变 Story 目标 |
| 2.4、2.5a—c | 继续消费 2.3 的 snapshot/quality facts；不得只凭旧 `qualityGateVersion` 文本推断 eligible | 保持顺序，通过 2.3 依赖传递新前置 |
| 2.6a | trace/metric 标签须带 profile version + digest，禁止高基数原始值 | 小幅补充 readyWhen/证据字段 |
| 3.2、3.4 | RuleEvaluation/Candidate 只能绑定已验证 snapshot；复制自足证据而非依赖可删除 owner 行 | 补充消费契约，不改变 owner |
| 4.1a 及后续场景 | 生产资格必须引用同一 profile/snapshot digest | 补充发布门，不重排 |
| Epic 1 | 不重开 Story 1.7；在 2.3 的 contract parity guard 中恢复 RFP 权威，移除 R6 的未批准 Job 能力 | 纠正运行漂移，不扩权 |
| Epic 3—8 其他 Story | 无直接范围变化 | 保持现状 |

无需新增、删除、拆分或重排 Epic/Story；`sprint-status.yaml` 不发生结构性变更，Story 2.3 保持 `in-progress`，Task 1—7 继续 blocked，直到 Task 0 checker 通过。

### 2.2 PRD 影响

产品范围和质量目标不变。需要纠正的是“门槛已批准”和“算法已完备”之间的混淆：

- FR-11 增加 `QualityMetricDecisionProfileVersion` + digest 的执行绑定与技术失败语义。
- NFR-9 保留 99.5% / 98% / 99% 数值，不用目标值反推分母、舍入或适用性。
- NFR-12 明确 `QualitySnapshot` 的 2 年映射及自足下游证据。
- NFR-33 把新增 successor/lock 纳入未来 release baseline；历史 v1—v4 manifest 不原位改写。
- §15 将“全部参数已冻结”改为“批准目标已冻结；执行派生必须由受控 successor 明确”。

### 2.3 Architecture 影响

- AD-5 需要把阈值 catalog 与 executable profile 分开，并明确质量失败和技术不可评估是不同结果。
- AD-9 需要采用闭合 leaf-path 投影，而不是开放递归 JSON；R6 只读取 assessed `QualitySnapshot`，不读取 `DataBatch` 或技术 `Job`。
- AD-27 需要冻结 `QualitySnapshot` 的 retention 起算点、owner 删除结果和 audit-operations 最终回执边界。
- AD-28 / G-03 增加 profile、projection、retention contract 的 successor 版本与摘要。

不需要改变模块化单体、六边形边界或领域所有权，也不需要新建跨模块写路径。

### 2.4 UX 影响

不新增页面，也不重做视觉系统；只补足 `data-quality-panel` 的可信状态语义：

- 分开显示 `passed`、`quality-failed`、`not-applicable`、`evaluation-error`；后者不得写成质量失败或 0。
- 每项指标显示 formula/profile version、digest 短标识、窗口、cutoff、分子/分母、阈值、运算符和含边界语义。
- R6 仅见 owned-source assessed snapshot；receiving/sealed/worker retry 属 R7 技术表面。
- 趋势跨 profile digest 时断开或分段，不把不同口径连成同一序列。

### 2.5 测试、CI、数据与发布影响

- 新增 schema/instance/fixture/lock checker，并把 boundary、±1、zero denominator、source override、timezone/cutoff 作为 Java/SQL 共用 vectors。
- 禁止 JSON binary float；canonical JSON、摘要、旧 DCC/QG/RFP/RS/field-projection 文件 raw-byte 不变均须检查。
- field projection 新增 additive 1.1.0；不修改 1.0.0。
- release 只在未来 V5 successor 登记新 controlled inputs；V1—V4 和历史 source inventory 保持字节不变。
- 合同、Java、PostgreSQL、前端和 release checker 任一失败都阻止 Story 2.3 完成。

## 3. Path Forward Evaluation

### Option 1：直接补强现有基线与 Story（推荐）

- 可行性：高
- 工作量：中
- 风险：中；主要风险由共同 vectors、digest lock 和 parity checker 控制
- 时间影响：规划/批准约 1—2 个工作日；Story 2.3 的实施仍按现有 8—12d 校准，不新增 Epic
- 结果：保留全部产品目标，同时消除开发者猜测和跨实现漂移

### Option 2：回滚已完成的数据目录/授权工作

- 可行性：低
- 工作量：高
- 风险：高
- 结论：不可取。DCC、QG 阈值意图、RFP 和 RS 仍是有效上游输入；回滚不能补齐公式和 handoff。

### Option 3：缩减 MVP，删除质量趋势/快照/留存

- 可行性：技术上可行，但产品上不可接受
- 工作量：中高
- 风险：高
- 结论：不可取。质量门是 Candidate/Clue fail-closed 的核心前置，删减会破坏 FR-11/12 与 NFR-9/10/11。

### 推荐决定

采用 Option 1：发布 `DEC-019 / QMDP-1.0.0` 作为 `AUTH-2026-07-17-001` 的受控执行派生，并创建 additive contract successors；阈值目标不变，历史受控文件不改写。

## 4. Approved Normative Decision Profile

本节不是实现建议，而是 Hei 已明确批准的执行语义。Task 0 必须逐字物化为 `QMDP-1.0.0`、schema、fixtures 和 lock；若仍不能唯一生成，继续 HALT。

### 4.1 版本、数值与 canonicalization

| 项 | 批准值 |
|---|---|
| decision/profile | `DEC-019 / QMDP-1.0.0`；approvalRef=`AUTH-2026-08-08-001` |
| threshold representation | 非负整数 `thresholdNumerator/thresholdDenominator`；百分比统一换算为 basis points（100%=10000） |
| stored display value | `valueBasisPoints = HALF_UP(numerator × 10000 / denominator)`，整数、`valueScale=0` |
| pass/fail comparison | 在舍入前用整数交叉相乘；ratio `>=`、duration `<=`、violation count `=0`，全部 inclusive |
| binary floating point | 禁止进入 contract、计算、比较、snapshot 和 digest material |
| windows | `[windowStartAt, windowEndAt)`；数据库/事件为 UTC，业务日历与 schedule 使用 IANA `Asia/Shanghai` 并同时保存绝对 `cutoffAt` |
| freshness window | 以当前 sealed batch 的 `cutoffAt` 结束的滚动 30×24h delivery ledger；日历 horizon 例外见 source table |
| canonical material | `SCHOLARSENSE-CANONICAL-JSON-1.0.0`：UTF-8、对象键排序、拒绝重复键/浮点/非规范时间；摘要 `sha256:` |
| policy identity | snapshot 必存 profile version+digest、QG version+digest、source schema version+digest、formula version、manifest digest |
| unknown/mismatch | profile/schema/version/digest/clock/manifest 缺失或不匹配属于 `evaluation-error`；批次保持 `sealed`，不写 quality-failed snapshot，不发布事实 |

### 4.2 共同 metric 与分母

| metricId / FR-11 类别 | numerator / denominator | 默认门槛与语义 |
|---|---|---|
| `PRIMARY_KEY_COMPLETENESS_BP` / 主键完整性 | manifest 原始记录中 DCC businessKeys 全部存在且 schema-valid 的记录数 / manifest declared record count | `>=9950`；source group override 可替换该字段组阈值 |
| `P0_SUBJECT_MAPPING_BP` / 主键完整性支撑门 | 需要主体映射且唯一解析到 StudentRef 的 P0 记录数 / 需要主体映射的 P0 记录数 | `>=9950`；不含主体的来源 N/A |
| `REQUIRED_FIELD_VALIDITY_BP` / 覆盖率支撑门 | 所有非 override required 字段均 schema-valid 的记录数 / manifest declared record count | `=10000`；显式 source field-group 使用其 override 门槛 |
| `VALID_RECORD_RATE_BP` / 覆盖率支撑门 | 通过 schema、业务区间和用途约束的记录数 / manifest declared record count | `>=9950` |
| `CORE_FIELD_COVERAGE_BP` / 覆盖率 | valid core-field cells / expected core-field cells（record count × profile 中闭合 fieldSet） | `>=9800`；source override 可提高，不得降低至 common 以下，除非本提案显式列出 |
| `FRESHNESS_WITHIN_SLO_BP` / 新鲜度 | 30 日 ledger 中按 manifest/schedule 应到且在 lane SLA 内到达的 delivery units / 应到 delivery units | `>=9900`；event/change lane 的 unit=record，full/reconcile/batch lane 的 unit=scheduled partition |
| `UNRESOLVED_INTERVAL_CONFLICT_COUNT` / 连续性 | 未解决区间冲突数 | `=0` |
| `DUPLICATE_BUSINESS_KEY_COUNT` / 连续性 | 同 source + business key + effective version 的重复数 | `=0` |
| `VERSION_REGRESSION_COUNT` / 连续性 | 同 business key 上 sourceVersion/effective version 倒退数 | `=0` |
| `SOURCE_CONTINUITY_GATE` / 连续性 | source table 中全部适用 count/chain/calendar gate 的 AND | 任一适用成员失败即失败；不把成员平均成分数 |
| `SCHEMA_ALLOWLIST_COMPATIBILITY_BP` | 可由批准 schema 识别且无未知字段的记录数 / manifest declared record count | `=10000` |
| `FORBIDDEN_FIELD_COUNT` | 规范化前 payload 中命中 profile 禁止字段/内容类别的数量 | `=0` |

source override 优先级固定为：只替换本表明确指向的 field group / gate / threshold；其余 common hard gates 继续适用。`not-econ-hit-evidence` 和 `not-student-evaluation-feature` 是用途/消费约束，不伪造成数值 metric。

### 4.3 applicability、空分母和总体结果

1. `applicable=false` 只能由 profile 的闭合 predicate 得出，结果为 `not-applicable`，不计入总体 AND；运行时或 UI 不得自行推断。
2. `applicable=true && denominator=0` 是 `evaluation-error/QUALITY_POLICY_ZERO_DENOMINATOR`，批次保持 sealed；不得当作 pass、fail 或 0%。
3. 仅当 provider 提交签名 `NO_ACTIVITY` manifest 且 profile lane 明确允许无活动时，record metrics 可 N/A；该 manifest 自身仍是 freshness 的应到 delivery unit。scheduled full/reconcile/batch lane 不允许以 NO_ACTIVITY 跳过。
4. `SRC-P1-OFFCAMPUS-001` 的 overlap 集合为空时，其 overlap-disambiguation gate 明确 N/A。
5. 总体 `quality-passed` 要求：无 evaluation-error、至少一个 hard gate 适用、全部适用 hard gates 通过。有效评估低于门槛才写 `quality-failed` snapshot。

### 4.4 17 source 结构化 override

| sourceId | 闭合 field/gate 定义 | freshness lanes（全部 `Asia/Shanghai`，边界 inclusive） |
|---|---|---|
| `SRC-P0-STUDENT-001` | group `{studentRef,effectiveFrom,effectiveTo}` valid `>=9950`；同 studentRef effective interval conflict `=0` | incremental record `receivedAt-sourceUpdatedAt<=4h`；daily full partition `arrivalAt<=06:00` |
| `SRC-P0-RESPONSIBILITY-001` | active authority evidence 未映射 `=0`；revocation duration `<=15m`；manifest reconcile `>=9990` | incremental authority fact `<=15m`；daily full partition `<=06:00` |
| `SRC-P0-ACCOMMODATION-001` | concurrent interval conflict `=0`；core cells `{studentRef,accommodationType,campusCode,effectiveFrom,effectiveTo}` `>=9950` | change `receivedAt-sourceUpdatedAt<=60m`；daily full `<=06:00` |
| `SRC-P0-CARD-001` | joint group `{eventId,subjectRef,categoryCode,amountMinor,currency}` `>=9950`；每个 correction/reversal 必须形成单根、无环、无缺口链 | transaction `receivedAt-occurredAt<=15m`；settlement partition `D+1 06:00` |
| `SRC-P0-CAMPUS-ACCESS-001` | eventId duplicate `=0`；joint group `{deviceId,direction,occurredAt}` `>=9990` | event `receivedAt-occurredAt<=5m`；daily reconcile `<=06:00` |
| `SRC-P0-DORM-ACCESS-001` | eventId duplicate `=0`；`buildingCode` 能映射到批准 building catalog `>=9990` | event `receivedAt-occurredAt<=5m`；daily reconcile `<=06:00` |
| `SRC-P0-DEVICE-001` | `{deviceId,locationCode,appliesToSourceId}` mapping `=10000`；每个 heartbeat gap `>15m` 必须由覆盖该半开 gap 的 explicit fault interval 解释 | heartbeat `arrival-heartbeatAt<=5m`；fault fact `arrival-effectiveFrom<=10m` |
| `SRC-P0-LEAVE-001` | joint group `{filingType,effectiveFrom,effectiveTo,approvalState,sourceVersion}` `>=9990`；同 filingId effective interval conflict `=0` | approval/revocation `receivedAt-sourceUpdatedAt<=15m`；daily reconcile `<=06:00` |
| `SRC-P0-CALENDAR-001` | 以 cutoff 的上海 localDate 为 today，在 `[today-90d,today+181d)` 每日恰有一个 current dayType，expected day denominator 固定 271 | normal projection 必须至少提前 7d；emergency correction `arrival-effectiveAt<=60m` |
| `SRC-P0-TIMETABLE-001` | joint group `{activityState,campusCode,locationCode,enrollmentState,effectiveAt}` `>=9950`；同 DCC business key version regression `=0` | cancellation/reschedule `receivedAt-sourceUpdatedAt<=30m`；daily full `<=06:00` |
| `SRC-P1-OFFCAMPUS-001` | 与 P0 accommodation effective interval 重叠的记录中 `p0AccommodationDisambiguation` 有批准枚举值 `=10000`；无 overlap 时 N/A | change `arrival-effectiveFrom<=60m`；daily reconcile `<=06:00` |
| `SRC-P1-NETWORK-001` | 规范化前 payload 的 URL/domain/content/free-text 字段或值类别命中数 `=0`；manifest partition reconcile `=10000` | complete partition `D+1 08:00`；30 日 on-time partitions `>=9900` |
| `SRC-P1-ACADEMIC-001` | manifest reconcile `=10000`；batch seal/correction 链单根、无环、无缺口且 correction 直接 supersede 前一版 | sealed batch `D+1 08:00`；correction `arrival-correctionEffectiveAt<=4h` |
| `SRC-P1-CARE-LIST-001` | joint group `{purpose,evidenceRef,effectiveFrom,effectiveTo,approvalVersion}` `=10000` | change `<=60m`；revoke/expiry `<=15m`，时间对由 manifest 的 sourceOccurredAt/receivedAt 提供 |
| `SRC-P1-PSYCH-DEID-001` | joint group `{category,purpose,effectiveFrom,effectiveTo}` 命中批准 allowlist `=10000`；diagnosis/body/free-text 命中数 `=0` | batch partition `D+1 08:00` |
| `SRC-P1-AID-001` | joint group `{purpose,effectiveFrom,effectiveTo,approvalVersion}` `=10000`；`not-econ-hit-evidence` 由 consumer-purpose deny contract 验证 | batch partition `D+1 08:00`；revoke `<=4h` |
| `SRC-P1-WORK-VISIT-001` | schema `required` 闭合集合 joint completeness `>=9950`；`not-student-evaluation-feature` 由 consumer-purpose deny contract 验证 | submission partition `D+1 08:00`；30 日 on-time partitions `>=9900` |

任何 source schema 没有直接提供 freshness 时间对时，sealed manifest 必须提供 `sourceOccurredAt/scheduledDueAt/receivedAt/laneId`；manifest 缺字段是 evaluation-error，不得用 DB 当前时间补值。

### 4.5 QualitySnapshot 字段投影 successor

发布 `FIELD-PROJECTION-POLICY-BINDING-1.1.0`，保持 1.0.0 字节不变。1.1.0 使用闭合 leaf-path grammar：只允许显式 top-level 路径及 `metricResults[].<leaf>`；不允许开放递归 object、任意 JSON Pointer 或未知字段透传。

| 字段类 | QualitySnapshot 闭合字段 |
|---|---|
| B | `snapshotId,batchId,sourceId,assessedBatchStatus,overallResult,observationWindow.startAt,observationWindow.endAt,cutoffAt,evaluatedAt,watermark,metricResults[].metricId,metricResults[].result,metricResults[].applicable,metricResults[].numerator,metricResults[].denominator,metricResults[].valueBasisPoints,metricResults[].unit,metricResults[].operator,metricResults[].thresholdNumerator,metricResults[].thresholdDenominator,metricResults[].boundary` |
| E | `metricResults[].reasonCode,impactScopeCodes[]` |
| G | `sourceOwnerRef,approvalRef,effectiveAt,retentionScheduleVersion` |
| T | `qualityMetricDecisionProfileVersion,qualityMetricDecisionProfileDigest,qualityGateVersion,qualityGateDigest,metricResults[].formulaId,metricResults[].formulaVersion,canonicalizationProfile,manifestDigest,sourceSchemaVersion,sourceSchemaDigest,immutableHash,traceId,lineageId,supersedesSnapshotId,aggregateVersion` |
| I/C/S/N | 空集；QualitySnapshot 不保存学生标识、联系方式、敏感正文或自然语言正文 |

- R6 在 RFP 已批准的 owned-source `QualitySnapshot/data-quality.read` 下，对 B/E/G/T 为 clear；scope 必须由 snapshot→source owner 的持久关系判定。
- 未知字段、未知 path、path 超集全部 H/omit；序列化前后都要 parity check。
- R7 继续通过 `Job` 查看技术作业；R6 的未批准 `Job/data-quality.read` runtime 条目必须删除并加入 contract ↔ runtime parity guard。
- 不增加 `DataBatch` object/action，也不增加人工 seal/pass/publish action。

### 4.6 Retention 与 deletion-result handoff

| 项 | 批准值 |
|---|---|
| object mapping | owner `QualitySnapshot` → RS“报表与运营快照” |
| retention start | `evaluatedAt`；passed/failed 一致，避免依赖不存在的 publishedAt |
| retention period/action | 2 年后删除 owner snapshot、metric rows、owner read model/cache/object副本；不以匿名化替代删除 |
| downstream evidence | Candidate/Clue 创建时复制自足最小 `EvidenceSnapshot`，按其自身“结案后 3 年” schedule；不得依赖 owner snapshot 行长期存在 |
| eligibility | `evaluatedAt+2y` 到期、无 legal hold、所有 required consumer watermark 达标、下游自足复制 ack 完成；任一不满足为 blocked |
| owner result | ingestion-quality 发布 owner-local `quality-snapshot-deletion-result.v1`；result=`completed|partial|blocked|failed`，带 scheduleVersion、scope digest、watermark、legal-hold、online/object/cache 结果、backup expiry due、trace/idempotency/supersession |
| final receipt | 仅 audit-operations 汇总各 owner/consumer/backup 结果后生成最终 `DeletionReceipt`；现有 conformance-only receipt 不得冒充生产 owner result |
| backups | owner result 记录 `backupExpiryDueAt`；最终 receipt 等待基础设施备份生命周期证据，不要求 ingestion-quality 伪造物理清除 |

### 4.7 Approved QMDP identity and authority mapping addendum

Hei 于 2026-08-09T10:32:53+08:00 明确回复“批准以上映射”，批准以下值作为 `DEC-019 / AUTH-2026-08-08-001` 的规范性补充；本节与 §4.1—§4.6 共同构成可直接物化的 QMDP 决策输入：

| 项 | 批准值 |
|---|---|
| common metric formula identity | `formulaId = QMDP-1.0.0/<metricId>`；`formulaVersion = 1.0.0` |
| source-specific gate formula identity | `formulaId = QMDP-1.0.0/<sourceId>/<gateId>`；`formulaVersion = 1.0.0` |
| profile/common owner | `Hei`，对应本提案 `accountableOwner` |
| source metric owner | 对应 DCC-1.0.0 source 的 `responsibleRole`；`sourceId` 是稳定绑定键，`ownerDepartment/ownerName` 仅作批准证据而不替代该 role |
| profile effectiveAt | `2026-08-09T10:02:22+08:00`，即本提案获批时刻 |
| evidenceRef | `story://2.3/DEC-019/AUTH-2026-08-08-001` |

任何实现不得改写上述映射、把 source owner 改成运行时当前 UI actor，或给 source gate 使用未登记的 formula identity。未来更改需要新的 QMDP successor 和批准记录。

### 4.8 Approved source-key successor and materialization addendum

Hei 于 2026-08-09T12:00:59+08:00 明确回复“批准推荐解阻包”，批准以下值作为 `DEC-019 / AUTH-2026-08-08-001` 的第二项规范性补充。本节关闭 Task 0.2 RED 阶段发现的 source-key 冲突与机械编码缺口；§4.1—§4.8 共同构成可直接物化且无需开发者推断的 Task 0 决策输入。

#### 4.8.1 Additive DCC/source-schema successors

| 项 | 批准值 |
|---|---|
| catalog successor | 新增 `DCC-1.1.0`，`effectiveAt=2026-08-09T12:00:59+08:00`；完整保留 17-source 闭合集与 QG-1.0.0 阈值绑定，不原位修改 `DCC-1.0.0`、旧 source schema 或旧 lock |
| `SRC-P0-RESPONSIBILITY-001` | 新增 `RESPONSIBILITY-AUTHORITY-V2-2.1.0` schema successor；在原闭合字段集上新增并 required `relationId`，格式为小写 UUIDv7；`DCC-1.1.0` 的 businessKeys 保持 `{relationId,sourceVersion}` 并指向该 successor |
| `SRC-P1-NETWORK-001` | source schema `NETWORK-AGGREGATE-1.0.0` 保持字节不变；仅在 `DCC-1.1.0` 把 businessKeys 修正为 `{subjectRef,windowStartsAt}`，与已批准闭合 schema 精确一致 |
| `SRC-P1-CARE-LIST-001` | 新增 `CARE-LIST-SLICE-1.1.0` schema successor；在原闭合字段集上新增并 required `listFactId`，格式为小写 UUIDv7；`DCC-1.1.0` 的 businessKeys 保持 `{listFactId}` 并指向该 successor |
| migration semantics | successor 只约束其生效后按 DCC-1.1.0 seal 的批次；历史 DCC-1.0.0 批次、payload、摘要与证据不回写，不把 `relationEvidenceRef/evidenceRef` alias 成新 identity |

#### 4.8.2 QMDP-1.0.0 closed materialization conventions

| 项 | 批准值 |
|---|---|
| count metric rational form | count/violation gate 使用 `numerator=<rawCount>, denominator=1`；unit=`count`；通过条件仍为 inclusive `=0` |
| duration unit | contract、fixture、计算与 snapshot 的最小单位统一为整数 `millisecond`；阈值中的 minute/hour/day 必须在 profile 内预换算为整数毫秒，禁止运行时自由选择单位 |
| `SOURCE_CONTINUITY_GATE` | numerator=通过的适用成员数，denominator=全部适用成员数，unit=`member-count`；适用成员逐项 AND，`numerator=denominator` 才通过；denominator=0 依 §4.3 为 evaluation-error |
| P0 subject mapping scope | 精确 source 集为 `SRC-P0-STUDENT-001`、`SRC-P0-ACCOMMODATION-001`、`SRC-P0-CARD-001`、`SRC-P0-CAMPUS-ACCESS-001`、`SRC-P0-DORM-ACCESS-001`、`SRC-P0-LEAVE-001`、`SRC-P0-TIMETABLE-001`；其余来源该共同 metric 为 N/A |
| `NO_ACTIVITY` | QMDP-1.0.0 的全部 lane 均 `allowNoActivity=false`；任何签名 NO_ACTIVITY manifest 均不能令 record metric N/A，后续放宽必须发布 QMDP successor |
| source gate identity | 首个受控 policy 实例按每个 source 的闭合 `sourceGates[]` 顺序和显式 `gateId` 锁定；gateId 一旦进入 canonical digest 不得重命名或复用，公式 identity 严格使用 §4.7 模式 |
| purpose constraints | `not-econ-hit-evidence` 与 `not-student-evaluation-feature` 只能进入 `nonMetricConstraints[]`，不得出现在数值 metric、总体 hard-gate AND 或 basis-point 分母中 |
| source field-group mapping | §4.4 的显式 source field group 统一映射到 `CORE_FIELD_COVERAGE_BP`；`SRC-P1-WORK-VISIT-001` 的 schema-required 闭合集映射到 `REQUIRED_FIELD_VALIDITY_BP`；其他 common metrics 只能按 §4.2 的闭合公式和 profile applicability 使用 |

任何实现不得用 alias 隐藏历史矛盾、把 required identity 降为 optional、把毫秒改成浮点秒、允许未获批 NO_ACTIVITY lane、将用途约束伪造成质量分数，或自行扩大 P0 mapping source 集。未来更改必须使用 additive DCC/source-schema/QMDP successor 并取得新的批准记录。

## 5. Detailed OLD → NEW Change Proposals

以下变更已获 Hei 明确批准，可进入正式基线更新/实施 handoff；本提案批准本身不等于相关文档、合同、代码或运行证据已完成。

### 5.1 PRD

#### FR-11

**OLD：**

> 每个 source/dependency 质量门以稳定 `qualityGateVersion` 保存公式、量纲、比较运算符、阈值、含边界语义、适用区间、owner、批准/生效证据；缺批准版本时不得推断 production eligible。

**NEW：**

> 每个 source/dependency 质量门同时绑定阈值 catalog `qualityGateVersion` 与获批 `QualityMetricDecisionProfileVersion + digest`。profile 必须结构化保存公式/版本、分子/分母、量纲、比较运算符、阈值、含边界、零分母、精度/舍入/比较阶段、窗口/cutoff/timezone、适用性、owner 与批准/生效证据；未知、缺失或摘要不匹配时属于技术不可评估，批次保持 sealed，不得写 quality-failed、发布事实或推断 production eligible。每次规则/线索计算记录所用 snapshot/profile/digest。

#### NFR-9

**OLD：**

> [假设 A-2] P0 主体标识完整率≥99.5%，核心场景字段覆盖率≥98%，数据新鲜度达标率≥99%。

**NEW：**

> [假设 A-2] 数值目标保持不变；目标只定义阈值，不定义公式。生产计算必须按获批 QMDP 的精确整数/有理数语义、适用性和共同 vectors 执行，禁止从百分比或自由文本反推分母、舍入、边界或零分母行为。

#### NFR-12 / NFR-33 / §15

**OLD：** `QualitySnapshot` 无实体级 retention 映射；§15 声称全部参数已冻结且可直接实现。

**NEW：**

> QualitySnapshot 自 evaluatedAt 起保留 2 年并由 ingestion-quality 发布 owner-local deletion result；Candidate/Clue 保存自足 EvidenceSnapshot 并按结案后 3 年保留。QMDP、field-projection successor、snapshot retention/deletion-result contracts 进入未来 release baseline。`AUTH-2026-07-17-001` 继续冻结目标和阈值意图；执行派生若未由受控 profile 唯一确定，必须 correct-course，不得由开发者静默补值。

### 5.2 `epics.md`

#### G-03 / FR-11 evidence

**OLD：**

> G-03 approved baseline 为 `DCC/QG/QRP`；FR-11 证据为“质量公式、门槛和批次样例”。

**NEW：**

> G-03 增加 `QMDP-1.0.0 + digest`、QualitySnapshot field-projection 1.1.0 和 retention/deletion-result successor；FR-11 证据必须含结构化 policy、共同 vectors、Java/SQL exact-result、digest lock 和 runtime parity。

#### Story 2.3 readyWhen / AC

**OLD readyWhen：**

> 批次状态机、质量公式和截止时间已版本化

**NEW readyWhen：**

> DEC-019/QMDP-1.0.0、field-projection 1.1.0、QualitySnapshot retention/deletion-result contract 已获批并通过 schema/semantic/digest checker；旧 DCC/QG/RFP/RS/field-projection 1.0.0 字节不变。

**OLD AC：** 只描述两条状态路径、failed 不发布和快照可下钻。

**NEW AC 增量：**

> 增加 AC-2.3-EXECUTABLE-CONTRACT：Java/SQL 共用 boundary/±1/zero/N/A/timezone/source-override vectors；只有有效计算低于门槛写 quality-failed，profile/manifest/clock/digest 故障保持 sealed；snapshot/profile/digest/manifest/原始分子分母不可变；R6/R7 分面和 retention handoff 通过 checker。

#### 下游 precondition

**OLD：** 2.4/3.2/3.4/4.1a 只引用 `QualityEligibility`、G-03 或旧 QG。

**NEW：**

> 2.4 及所有发布/消费 Story 必须拒绝未知 snapshot/profile version 或 digest mismatch；3.4 的 Candidate/Clue 复制自足 evidence；4.1a 的 canary/production 记录同一 profile digest。2.5a—c 通过对 2.4 的依赖继承该门，不重复定义算法。

### 5.3 Architecture

#### 架构总述

**OLD：**

> 本文中的静态协议、版本和阈值均可直接实现。

**NEW：**

> 已批准阈值和协议目标可进入实现；涉及计算、投影或留存的执行语义必须由其受控 executable profile/successor 唯一确定。缺失或摘要不匹配时 fail closed 并 correct-course。

#### AD-5

**OLD：** 单一 `qualityGateVersion` 被描述为冻结全部公式语义。

**NEW：** 阈值 catalog 与 `QualityMetricDecisionProfileVersion+digest` 双绑定；精确比较在舍入前完成；质量不通过与 evaluation-error 分离；只有 assessed snapshot 可进入 passed/failed，technical error 留在 sealed；snapshot 业务不可变但服从 retention。

#### AD-9

**NEW 增量：** QualitySnapshot 采用 field-projection 1.1.0 的闭合 leaf paths；不开放递归 JSON；R6 仅 owned-source assessed snapshot，R7 仅技术 Job；contract/runtime parity 是启动门。

#### AD-27 / AD-28 / G-03

**NEW 增量：** 明确 evaluatedAt+2y、owner-local result→audit receipt、EvidenceSnapshot copy-on-create，并将 QMDP/projection/retention successors 纳入未来 release manifest。

### 5.4 UX `DESIGN.md` / `EXPERIENCE.md`

#### `data-quality-panel`

**OLD：** 显示质量指标、依赖、恢复策略和 fused/recovering/eligible，但未区分计算错误、N/A 与质量失败，也未要求公式摘要。

**NEW：**

> 每个 assessed snapshot 显示 profile/version/digest 短标识、窗口/cutoff、分子/分母、单位、阈值、运算符、含边界和 reason/impact；passed、quality-failed、not-applicable、evaluation-error 使用不同文字状态。evaluation-error 不显示 snapshot 数值或质量失败，receiving/sealed/worker retry 只在 R7 技术表面；趋势跨 profile digest 分段。

#### UJ-5 失败路径

**NEW 增量：**

> 质量值真实低于门槛时进入质量修复；profile/manifest/time/digest 不可用时进入技术重试，不允许通过降低门槛、补 0、重算当前值或手工 pass 继续。

### 5.5 Contracts、checker 与 Story 2.3 Task 0 handoff

批准后 Task 0 物化以下最小包：

1. `contracts/ingestion-quality/batch-quality/metric-definition.schema.json`
2. `executable-quality-policy.schema.json` + `executable-quality-policy-1.0.0.json`
3. `batch-quality-lifecycle.schema.json` + `batch-quality-lifecycle-1.0.0.json`
4. `quality-snapshot-retention.schema.json` + `quality-snapshot-retention-1.0.0.json`
5. valid/invalid metric vectors，覆盖 boundary、±1、zero denominator、N/A、17 source override、UTC/Shanghai cutoff
6. `contracts/events/ingestion-quality/quality-snapshot-deletion-result.schema.json` + valid fixture
7. field-projection binding/schema/fixture/lock additive 1.1.0
8. batch-quality contract lock（排除 lock 自身）
9. `scripts/check_ingestion_batch_contracts.py`、unit/mutation tests、`verify_core.sh` 接入
10. RFP JSON ↔ Java runtime parity guard，并移除 R6 未批准 Job entry

Task 0 checker 通过前不得创建 batch calculator、DDL、OpenAPI、事件实现或 UI。

## 6. Correct-Course Checklist Status

| Checklist item | 状态 | 结果 |
|---|---|---|
| 1.1 识别触发 Story | [x] | Story 2.3 Task 0.1 |
| 1.2 定义核心问题 | [x] | 阈值已批但执行语义不唯一 |
| 1.3 收集证据 | [x] | gap 表、probe、contract/runtime 审计齐备 |
| 2.1 评估当前 Epic | [x] | Epic 2 可完成，无需重构 |
| 2.2 确定 Epic 修改 | [x] | 补 G-03、2.3 和下游消费门 |
| 2.3 检查未来 Epic | [x] | 3/4 的 snapshot/profile 消费需补门 |
| 2.4 是否新增/删除 Epic | [N/A] | 不需要 |
| 2.5 是否重排 Story | [N/A] | 不需要 |
| 3.1 PRD 冲突 | [x] | FR-11、NFR-9/12/33、§15 有明确 OLD→NEW |
| 3.2 Architecture 冲突 | [x] | AD-5/9/27/28、G-03 有明确 OLD→NEW |
| 3.3 UX 冲突 | [x] | data-quality-panel/UJ-5 状态语义补强 |
| 3.4 其他制品 | [x] | contracts、checker、release、open decision 均已识别 |
| 4.1 Direct adjustment | [x] | 可行，推荐 |
| 4.2 Rollback | [N/A] | 不解决问题且风险高 |
| 4.3 MVP review | [N/A] | 产品目标不变 |
| 4.4 选择路径 | [x] | Option 1 |
| 5.1 Issue Summary | [x] | 已完成 |
| 5.2 Impact Analysis | [x] | 已完成 |
| 5.3 Recommended approach | [x] | 已完成 |
| 5.4 Detailed change proposals | [x] | 已完成 |
| 5.5 Handoff plan | [x] | 见下节 |
| 6.1 完整性检查 | [x] | 提案覆盖业务、架构、UX、合同、留存和发布 |
| 6.2 准确性/一致性检查 | [x] | 阈值不变、历史合同不改、无扩权/越界 owner |
| 6.3 用户明确批准 | [x] | Hei 于 2026-08-09 回复 Continue |
| 6.4 更新 sprint-status | [N/A] | 无新增/删除/重排，当前 in-progress 状态不改 |
| 6.5 执行 handoff | [x] | 已路由至 Product Owner / Architect / Developer；见下节 |

## 7. Handoff Plan

### 7.1 变更分类

**Moderate**：不改变产品战略、MVP 或 Epic 顺序，但横跨 PRD、Epic、Architecture、UX 和多个受控 contracts，需 Product Owner / Architect / Developer 协同落地。

### 7.2 Handoff 执行顺序

1. Product Owner：本提案已标为 approved；下一步登记 `DEC-019 / AUTH-2026-08-08-001` 并更新 `open-decisions.md`。
2. PM/Architect：按 §5 的 OLD→NEW 更新 PRD、Epics、Architecture、UX controlled baseline successor；历史版本保留。
3. Developer：仅执行 Story 2.3 Task 0 contracts/checkers，先取得全绿 readiness evidence。
4. Developer：Task 0 通过后恢复 Task 1—7；不得把本提案批准当成运行测试通过。
5. Release owner：在未来 V5 successor 登记新 inputs；V1—V4 不变。

### 7.3 成功标准

- `QMDP-1.0.0` 能从本提案唯一生成，schema/semantic/digest/mutation checker 全绿。
- Java 与 PostgreSQL 对同一 vectors 产生完全相同的 exact result、reason 和 digest。
- old DCC/QG/RFP/RS/field-projection 1.0.0 与 release V1—V4 raw bytes 不变。
- R6 只能读取 owned-source assessed QualitySnapshot；R6 Job 漂移被移除；无 DataBatch/人工 pass 扩权。
- quality-failed、evaluation-error 和 N/A 在状态机、事件、API、UI、审计中一致。
- retention 从 evaluatedAt 起 2 年，owner result 与 audit final receipt 边界可自动验证。
- Task 0 readiness checker 通过后才允许 Story 2.3 Task 1 开始。

## 8. Approval

当前状态：**已由 Hei 批准（2026-08-09，含 §4.7 identity/authority mapping 与 §4.8 source-key/materialization addenda）**

批准语义：

> 批准采用 Option 1，并批准第 4 节作为 `DEC-019 / QMDP-1.0.0` 的规范性执行语义；允许按第 5 节更新 PRD、Epics、Architecture、UX 和受控 successor，并按第 7 节执行 handoff。旧 DCC/QG/RFP/RS/field-projection 1.0.0 与 release V1—V4 不得原位改写；Task 0 checker 未通过前，Story 2.3 Task 1—7 保持 blocked；提案批准不得冒充运行证据。

批准记录：Hei 在 Batch 提案展示后明确回复 `Continue`。该回复完成本工作流的用户批准门，并使 `AUTH-2026-08-08-001` 成为本提案所载 `DEC-019` 的 accountable approval reference。

补充批准记录：Hei 于 2026-08-09T10:32:53+08:00 明确回复“批准以上映射”，关闭 `formulaId/formulaVersion`、owner、`effectiveAt` 与 `evidenceRef` 四项剩余映射缺口。

第二项补充批准记录：Hei 于 2026-08-09T12:00:59+08:00 明确回复“批准推荐解阻包”，批准 additive `DCC-1.1.0`、两个 required UUIDv7 source-schema successor、Network business-key 修正及 §4.8.2 的 QMDP closed encoding conventions。

## 9. Workflow Execution Log

- 2026-08-08：Story 2.3 Task 0.1 readiness probe 失败，确认 QG/DCC 自由文本不能唯一生成 executable policy；Task 1—7 fail closed。
- 2026-08-08：完成 PRD、Epics、Architecture、UX、contracts、runtime parity 与 retention 影响审查，生成 Batch Sprint Change Proposal。
- 2026-08-09：Hei 明确回复 `Continue`，批准 Option 1、DEC-019/QMDP-1.0.0 规范性语义和 Moderate handoff。
- 2026-08-09：Hei 明确回复“批准以上映射”，批准 §4.7 并解除 Story 2.3 Task 0.2 的剩余规划阻塞。
- 2026-08-09：Task 0.2 RED 阶段发现三项 DCC/source-schema key 冲突与机械编码缺口；Hei 明确回复“批准推荐解阻包”，批准 §4.8 并恢复 Task 0.2 TDD。
- Sprint/backlog：未新增、删除、拆分、重排或重编号 Epic/Story；`sprint-status.yaml` 无需结构性变更，Story 2.3 保持 `in-progress`。
- Handoff：Product Owner 登记决策并维护 planning baseline successor；Architect 更新 AD-5/9/27/28 与 G-03；Developer 先完成 Story 2.3 Task 0 contracts/checkers，checker 全绿后才恢复 Task 1—7；Release owner 只在未来 V5 successor 登记新 inputs。
