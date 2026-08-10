---
story_id: "2.3"
task: "0.1"
status: "resolved-for-task-0-implementation"
audited_at: "2026-08-08T16:03:43+0800"
resolved_at: "2026-08-09T10:32:53+0800"
reopened_at: "2026-08-09T10:55:20+0800"
materialization_resolved_at: "2026-08-09T12:00:59+0800"
baseline_commit: "acfd76d3e8b5bcddec2019d41c2aac0fb04ff0a7"
authority_record: "AUTH-2026-07-17-001"
---

# Story 2.3 Task 0：可执行质量口径 gap/decision 表

## 结论

当前批准链足以证明 `DCC-1.0.0`、`QG-1.0.0`、`RFP-1.0.0` 和 `RS-1.0.0` 可以进入实现，但不足以唯一生成 Story 2.3 要求的可执行质量策略、QualitySnapshot 字段投影 successor 和完整删除结果 handoff。

这命中 Story 的 fail-closed 条件：Task 0.2—0.5 与 Task 1—7 暂停；不得由开发者选择公式、分母、边界、零分母、舍入、字段分类或保留起算点。需要先通过 correct-course 发布可引用且获批的 derivation/decision profile。

### 2026-08-09 approved proposal 复核增量

`_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-08.md` 已由 Hei 批准为 `DEC-019 / AUTH-2026-08-08-001`。该提案关闭了数值表示、比较阶段、共同 metric 的分子/分母、17 source 结构化 override、QualitySnapshot 字段分类以及 retention/deletion-result 的主体语义，但仍不能逐字生成 Story 要求的完整 QMDP：

| 必填规范值 | 已批准输入 | 仍不唯一的候选 |
|---|---|---|
| 每项 `formulaId/formulaVersion` | 提案冻结 `metricId` 和公式语义，并要求未来保存 formula version | 未给 formula ID、version 值或从 metric/profile 推导它们的批准映射规则 |
| metric/source `owner` | DCC 同时提供 accountable owner、owner department、owner name、responsible role；提案只要求 snapshot→source owner | 未冻结 QMDP 应使用哪一 owner 字段，也未给 metric-level owner |
| profile `effectiveAt` | 提案有 document date、`approvedAt` 和 `approvalRef` | 未批准哪一时间作为 QMDP 生效时间；不得把 `approvedAt` 静默等同 `effectiveAt` |
| profile/metric `evidenceRef` | 提案有 `authorityRef`、`approvalRef` 和自身文件路径 | 未批准 evidenceRef 的值或映射；CARE-LIST 的业务 `evidenceRef` 不是 profile evidence |

Story 的 AC-2.3-EXECUTABLE-CONTRACT、Task 0.1、Task 0.5 与提案自身 §4 均要求：这些字段无法从批准输入唯一确定时继续 fail closed。因此 Task 0.1 的审计证据已更新，但 readiness 仍为 `READINESS_FAIL`；Task 0.2—0.5 与 Task 1—7 不启动。

### 2026-08-09 用户批准与解阻

Hei 已明确回复“批准以上映射”，并将其登记为 Sprint Change Proposal §4.7：

- common metric：`QMDP-1.0.0/<metricId>`，formula version `1.0.0`；
- source gate：`QMDP-1.0.0/<sourceId>/<gateId>`，formula version `1.0.0`；
- profile/common owner 为 Hei，source metric owner 为对应 DCC `responsibleRole`；
- profile `effectiveAt=2026-08-09T10:02:22+08:00`；
- `evidenceRef=story://2.3/DEC-019/AUTH-2026-08-08-001`。

四项剩余映射缺口至此关闭。Task 0.2—0.5 可以按顺序启动；Task 1—7 仍只在 Task 0 checker 全绿后解锁。

### Task 0.2 RED 阶段发现的 source-key 合同冲突

Task 0.2 编写失败测试并尝试把 `PRIMARY_KEY_COMPLETENESS_BP` 绑定到冻结 DCC/source schema 时，发现至少三项 DCC `businessKeys` 无法同时满足“字段存在”和“schema-valid”：

| sourceId | DCC business key | 冻结 source schema | 冲突 |
|---|---|---|---|
| `SRC-P0-RESPONSIBILITY-001` | `relationId` | 只有 `relationEvidenceRef` | `relationId` 不在 schema，且 `additionalProperties=false` |
| `SRC-P1-NETWORK-001` | `windowStart` | 字段为 `windowStartsAt` | 名称不一致，且 `additionalProperties=false` |
| `SRC-P1-CARE-LIST-001` | `listFactId` | 只有 `evidenceRef` | `listFactId` 不在 schema，且 `additionalProperties=false` |

旧 DCC 与 source schema 均为受控历史输入，Story 禁止原位改写。DEC-019/§4.7 尚未批准上述 alias 或 source-schema/DCC successor；若 checker 自行映射，将把开发者猜测伪装成批准语义。因此 Task 0.2 再次 fail closed，所有未完成 policy/schema/test 草案均已撤回且未保留。

同次物化审计还识别出需要在最小补充中明确冻结的编码语义：count metric 的 denominator=`1`、duration 最小单位=`millisecond`、`SOURCE_CONTINUITY_GATE` 使用 passing/applicable member count、P0 subject-mapping 精确 source 集、所有 lane 的 `NO_ACTIVITY` 策略，以及 source gateId 首版冻结规则。这些应与三项 alias 一次批准，避免反复 correct-course。

### 2026-08-09 推荐解阻包批准与再次解阻

Hei 已明确回复“批准推荐解阻包”，并将精确值登记为 Sprint Change Proposal §4.8：

- 发布 additive `DCC-1.1.0`，旧 `DCC-1.0.0`、旧 source schema 与旧 lock 保持字节不变；
- Responsibility 新增 required UUIDv7 `relationId` 的 `RESPONSIBILITY-AUTHORITY-V2-2.1.0` schema successor；
- Network 只在 DCC successor 中将 business key 修正为 `{subjectRef,windowStartsAt}`，原 schema 不变；
- Care List 新增 required UUIDv7 `listFactId` 的 `CARE-LIST-SLICE-1.1.0` schema successor；
- count denominator=`1`、duration unit=`millisecond`、continuity passing/applicable member count、七个 P0 subject-mapping sources、全部 lane `allowNoActivity=false`、首版显式 gateId 锁、用途约束非 metric，以及 source field-group→common metric 映射全部冻结。

Task 0.2 的规划阻塞至此关闭，可从 RED 测试恢复合同物化；Task 0.3—0.5 继续按顺序执行，Task 1—7 仍须等待 Task 0 总 checker 全绿。本批准是规范输入，不冒充 schema、digest、测试或运行证据。

## 已审计的受控输入

| 输入 | 已唯一确定的内容 | 不能提供的内容 |
|---|---|---|
| `contracts/data-catalog/dcc-1.0.0.json` | 17 个 source、业务键、schema/version、owner、自由文本 SLO、reconciliation/backfill 元数据 | 逐 metric 公式、分子/分母、等值边界、零分母、精度、舍入、批次 cutoff |
| `contracts/data-catalog/qg-1.0.0.json` | 10 个 common hard-gate 数值、17 条 source override 文本 | 结构化 metric 定义；override 仍是不可执行自由文本 |
| `contracts/data-catalog/quality-gate.schema.json` | `qualityGateVersion` 及两个 object 的外形 | metric 字段、结构化 override 和可执行语义约束 |
| `delegated-decision-baseline-2026-07-17.md` §5 | freshness 的滚动 30 日说明、逐源 SLO/硬门、共同阈值 | 批次级 numerator/denominator、窗口样本资格、cutoff、timezone、rounding/comparison stage |
| PRD FR-11 / NFR-9 | 必须计算四类指标；99.5% / 98% / 99% 目标；必须保存公式证据 | 如何计算、何时计算、零分母与 N/A、逐源适用性 |
| `RFP-1.0.0` | R6 对 owned-source `QualitySnapshot` 的 `data-quality.read`；无 `DataBatch` 授权 | QualitySnapshot DTO 的字段全集和逐字段 B/I/C/S/E/N/G/T 分类 |
| field-projection binding 1.0.0 | fail-closed、mask/hidden/unknown 语义和已有对象投影 | `QualitySnapshot` object schema；现有版本不得原位改写 |
| `RS-1.0.0` | “报表与运营快照”2 年、Candidate/Clue/证据快照结案后 3 年、legal hold/watermark 总原则 | QualitySnapshot 的明确实体映射、起算时间、failed 快照处理、delete/匿名化选择、owner-local deletion-result wire contract |

权威位置：

- `contracts/data-catalog/qg-1.0.0.json:3-33`
- `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md:114-142`
- `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md:192-205`
- `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md:356-364`
- `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md:978-982`
- `contracts/field-projection/field-projection-policy-binding-1.0.0.json:75-146`

## 四类 FR-11 指标缺口

| metric | 已批准事实 | 阻止执行的未决语义 |
|---|---|---|
| 主键完整性 | P0 主体映射目标 ≥99.5%；部分 source override 给出字段组和阈值 | “主键有效”“主体映射”“required-field validity”的边界关系；逐源 numerator/denominator；重复/隔离记录是否进分母；零分母；批次或滚动窗；比较前后舍入 |
| 连续性 | conflict/duplicate/version-regression 为 0；部分源要求 chain complete、每日恰一 dayType | 连续性的统一 formulaId；gap 的时间/序列粒度；chain-complete 的可执行谓词；N/A 与 hard-failure；窗口、cutoff 和修正版本处理 |
| 覆盖率 | required-field 100%、core-field ≥98%；部分源有 99.5%/99.9%/100% override | 逐源 required/core 字段冻结全集；按 record、field-cell、subject、device 或 partition 计数；多字段合并规则；零分母和 rounding |
| 新鲜度 | SLO 内到达 ≥99%；baseline 说明 freshness 为滚动 30 日达标率；逐源 SLO 文本存在 | “到达”的权威时间对；eligible observation 分母；批次评估与滚动 30 日如何同时保存；D+1/每日 06:00/提前 7 日语义；timezone/DST/cutoff；迟到更正如何计数 |

共同未决项：`formulaId/formulaVersion`、`unit`、明确 operator 和 inclusive/exclusive boundary、`denominatorZeroBehavior`、`valueScale`、`roundingMode`、`comparisonStage`、canonical material、source applicability、metric owner、approval/effectiveAt/evidenceRef。

## 17 个 source 的逐项对账

下表保留已批准 override 原文；“缺口”不是否定阈值，而是说明该文本不能被运行时安全解析成唯一公式。

| sourceId | 已批准 override | 仍不唯一的关键点 |
|---|---|---|
| `SRC-P0-STUDENT-001` | `primary/effective-valid>=99.5%;conflict=0` | primary 与 effective-valid 的组合方式、记录分母、区间冲突的窗口 |
| `SRC-P0-RESPONSIBILITY-001` | `unmapped-active=0;revocation<=15m;reconcile>=99.9%` | active/revocation/reconcile 分别映射哪类 metric、时间起点和分母 |
| `SRC-P0-ACCOMMODATION-001` | `concurrent-conflict=0;core>=99.5%` | 同时点粒度、core 字段全集、按 record 或 field-cell 计数 |
| `SRC-P0-CARD-001` | `event/subject/meal/unit>=99.5%;reversal-chain-complete` | 四字段合并公式、冲正链完整谓词、修正记录分母 |
| `SRC-P0-CAMPUS-ACCESS-001` | `eventId-unique;device/direction/time>=99.9%` | unique 的计数语义、三字段合并、重复记录隔离方式 |
| `SRC-P0-DORM-ACCESS-001` | `eventId-unique;building-map>=99.9%` | unique 与 building-map 的分母、无适用事件时行为 |
| `SRC-P0-DEVICE-001` | `device-map=100%;heartbeat-gap>15m-explicit-fault` | 设备全集、gap 起止、fault 后是否从 freshness 分母剔除 |
| `SRC-P0-LEAVE-001` | `type/interval/status/version>=99.9%;conflict=0` | 四字段合并、interval 有效性、版本/冲突的分组窗口 |
| `SRC-P0-CALENDAR-001` | `today-90d..today+180d exactly-one-dayType` | today/cutoff/timezone、窗口长度、缺日/重叠的 metric 与分母 |
| `SRC-P0-TIMETABLE-001` | `activity/location/enrollment/effectiveAt>=99.5%;version-regression=0` | 四字段合并、选课覆盖全集、版本倒退的业务键粒度 |
| `SRC-P1-OFFCAMPUS-001` | `p0-accommodation-overlap-disambiguated=100%` | overlap 候选全集、无 overlap 时 N/A 或 pass、修正窗口 |
| `SRC-P1-NETWORK-001` | `url/domain/content-fields=0` | 禁止字段扫描范围；完整分区/arrival 的 numerator、cutoff |
| `SRC-P1-ACADEMIC-001` | `manifest-reconcile=100%;seal/correction-chain-complete` | manifest 分母、chain-complete 谓词、seal 后 D+1 cutoff |
| `SRC-P1-CARE-LIST-001` | `purpose/source/interval/approval=100%` | 四字段合并、有效期窗、撤销/过期到达计算 |
| `SRC-P1-PSYCH-DEID-001` | `allowlist/purpose/interval=100%;diagnosis/body/free-text=0` | allowlist 分母、禁止内容检测范围、批次 D+1 cutoff |
| `SRC-P1-AID-001` | `purpose/interval/approval=100%;not-econ-hit-evidence` | 三字段合并；最后一项是用途约束而非数值 metric 的编码 |
| `SRC-P1-WORK-VISIT-001` | `completeness>=99.5%;not-student-evaluation-feature` | completeness 字段全集/分母；用途约束如何与质量结果分离 |

## 投影与保留的附加 planning defects

1. `RFP-1.0.0` 已批准 R6 owned-source `QualitySnapshot` read，因此不需要也不应增加 `DataBatch` 或人工 seal/pass/publish action；但没有批准 QualitySnapshot 的精确字段全集与逐字段分类。`sourceId`、`watermark`、`policyVersion`、`formulaVersion`、`result`、`reason`、`impactScope` 等存在 B/E/G/T 多种合理分类，开发者不得自选。
2. field-projection 1.0.0 的版本和 object enum 已冻结；未来应创建 additive successor，而不是修改历史文件。
3. RS 只冻结类别和年限。仍需批准 QualitySnapshot retention 起算点（`evaluatedAt`、`passedAt` 或 `publishedAt`）、failed 快照没有 `publishedAt` 时的规则、delete 与不可逆匿名化选择。
4. 现有 audit-retention receipt 仅用于 conformance，不能充当 ingestion-quality owner-local deletion-result。仍需冻结 result 的 scope/action/watermark/legal-hold/partial/blocked/idempotency/supersession/backup 字段及事件版本。
5. 现有生产授权目录与冻结 RFP 存在待收口的 parity 缺口：`RoleFieldPolicyCatalog.java:247-256` 给 R6 增加了 owned-source `Job/data-quality.read`，而 `role-field-policy-rfp-1.0.0.json:94-103` 的 R6 objectClasses 不含 `Job`。现有 checker 没有捕获 contract↔runtime 漂移；Task 0.3 的 successor/checker 必须先决定并验证唯一权威，不能借机扩大 `DataBatch` 权限。
6. 当前 field-projection schema 与 Java runtime 只接受 `string/integer/boolean/timestamp` 叶值，不能直接表达 QualitySnapshot 的嵌套 metric 数组和精确 decimal。需要批准采用展平叶字段、受控子对象，还是新增 recursive value type；在此之前不能由实现者自行改变投影语法。

## 最小解阻决策包

correct-course 产物至少需要：

1. 一个带 authority/approval/effectiveAt 的 `QualityMetricDecisionProfileVersion`，逐 source/metric 冻结 Story AC 所列全部字段。
2. 精确 rational、整数 basis-point 或规范 decimal-string 选择；禁止 binary floating point；明确 canonical JSON material。
3. 对 boundary、±1、zero denominator、N/A、UTC/Asia/Shanghai、半开窗、cutoff、source override 的 expected vectors，可供 Java/SQL 共同消费。
4. QualitySnapshot 精确 DTO 字段全集及 B/I/C/S/E/N/G/T 分类；确认 I/C/S/N 应为空还是存在批准例外。
5. QualitySnapshot retention 实体映射、起算点、failed/passed/published 分支，以及 owner-local deletion-result → audit-operations receipt handoff。
6. 明确该 profile 是 `AUTH-2026-07-17-001` 的受控 derivation/successor，或给出新的批准记录；不能仅把 Story 派生文字当作批准 oracle。

## Fail-closed 验证

对当前 `QG-1.0.0` 执行结构化 readiness probe，结果符合预期地失败：

```text
READINESS_FAIL
- QG-1.0.0 has no non-empty structured metrics[]
- QG-1.0.0 sourceOverrides are free text
```

因此没有创建 executable policy、schema/fixture/lock、field-projection successor、生产代码、DDL 或 UI，也没有修改任何历史受控合同。
