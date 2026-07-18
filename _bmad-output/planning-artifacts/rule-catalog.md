---
title: 学林知微版本化规则目录（PRD 附录 D）
status: approved
version: 1.1.0
effective_date: 2026-07-17
updated: 2026-07-17-final-readiness-closure
owner: Hei（A）；学工业务负责人（R）
authorityRecordId: AUTH-2026-07-17-001
runtimeEvidenceStatus: pending-story-execution
---

# 附录 D：版本化规则目录

本目录是场景规则设计、发布和验收的受控权威源，版本为 `RuleCatalogVersion=RC-1.0.0`。PRD、Architecture、UX 与 Epic/Story 只引用 `ruleId + version`，不得复制或自行推断阈值。Hei 依据 `AUTH-2026-07-17-001` 批准规则业务语义、公式、量纲、含边界阈值、排除顺序和责任角色，因此规则实现无外部决策阻塞。当前 `runtimeStatus=inactive`、`qualityStatus=fused` 仅表示依赖和发布 Story 尚未实测；在相应 DoD 通过前不得进入 canary/production。

规则状态必须正交保存：`governanceStatus` 表达治理成熟度，`runtimeStatus` 表达实际运行阶段，`qualityStatus` 表达当前依赖质量资格。三个字段不得互相替代；尤其不得以“目录已 review/approved”推断规则正在运行，也不得把质量熔断写入线索业务状态。

## 最低规则集

| ruleId | 场景 | version | 指标/量纲 | 窗口与阈值 | 排除/白名单 | 数据依赖 | 最低边界样本 | owner | governanceStatus | runtimeStatus | qualityStatus | implementation / release eligibility |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ACC-SAFE-001 | 住宿安全—连续无活动 | 1.0.0 | `lastActivityAt=max(校门/宿舍最后有效事件)`；小时 | 扣除批准排除区间后连续无活动 `>=24h且<48h` 为 II 级，`>=48h` 为 III 级；含边界；48h 更新同一 Candidate | 优先排除住宿→请假/实习→假期/校外→课表；设备故障属于质量熔断 | required all-of：`DEP-P0-CAMPUS-ACCESS-001`、`DEP-P0-DORM-ACCESS-001`、`DEP-P0-ACCOMMODATION-001`、`DEP-P0-LEAVE-001`、`DEP-P0-CALENDAR-001`、`DEP-P0-TIMETABLE-001`、`DEP-P0-DEVICE-001` | 正/反、24h/48h 等值及前后 1 秒、各排除、任一缺源/熔断、乱序更正各≥2 | Hei（A）；学工/保卫数据负责人（R） | approved | inactive | fused | ready / gated-by-DoD |
| ACC-SAFE-002 | 住宿安全—异常晚归晚出 | 1.0.0 | 本地日 pattern：入宿 `[00:00,05:00)`，且次日首次出宿 `>=11:00` 并存在上午批准教学活动 | Asia/Shanghai 滚动 14 日；pattern day `>=3且<5` 为 II，`>=5` 为 III；3/5 含边界 | 住宿→请假/实习→假期/校外→课表；设备故障质量熔断 | required all-of：`DEP-P0-DORM-ACCESS-001`、`DEP-P0-ACCOMMODATION-001`、`DEP-P0-LEAVE-001`、`DEP-P0-CALENDAR-001`、`DEP-P0-TIMETABLE-001`、`DEP-P0-DEVICE-001` | 正/反、3/5 等值、跨日/DST 不适用、各排除、缺源/熔断、乱序更正各≥2 | Hei（A）；学工/保卫数据负责人（R） | approved | inactive | fused | ready / gated-by-DoD |
| ECON-012 | 经济行为待核实—本人消费变化 | 1.0.0 | 每个 eligible campus day 的有效餐次交易笔数；personal-baseline | 当前 28 日 vs 紧邻此前 56 日；基线有效日≥28、当前≥14、基线≥1.0 次/日、当前≤基线 50%、绝对下降≥0.5 次/日，连续 7 个日评估点；全部含边界 | 退款/冲正/撤销不计；同商户同餐次 30m 重复归一；依次排除请假→实习→假期→离校；资助身份不作命中证据 | required all-of：`DEP-P0-CONSUMPTION-001`、`DEP-P0-LEAVE-001`、`DEP-P0-CALENDAR-001`、`DEP-P1-OFFCAMPUS-001` | 零消费、14/28 日、50%/0.5 等值及前后、各排除、重复/冲正、乱序更正、缺源/熔断各≥2 | Hei（A）；资助/一卡通负责人（R） | approved | inactive | fused | ready / gated-by-DoD |
| NIGHT-001 | 夜间作息—连续变化 | 1.0.0 | `[00:00,05:00)` 时长/流量/区域汇总；personal-baseline | 每夜活动≥180min，7 夜≥4 夜，且相对此前 28 个 eligible nights≥2.0 倍；基线有效夜≥14；含边界 | 依次排除实习→假期→离校；禁止 URL、域名和内容字段 | required all-of：`DEP-P1-NETWORK-001`、`DEP-P0-LEAVE-001`、`DEP-P0-CALENDAR-001`、`DEP-P1-OFFCAMPUS-001` | 单夜、4/7、180min、2.0 倍等值及前后、各排除、禁止字段、缺源/熔断各≥2 | Hei（A）；学工/信息中心负责人（R） | approved | inactive | fused | ready / gated-by-DoD |
| ACADEMIC-001 | 学业节点关怀 | 1.0.0 | 已封账权威节点事实；node-fact | 仅 sealed/effective 且 `careReviewRequired=true` 或 nodeCode∈ACN-1.0.0；去重 `studentId+nodeCode+term+batchVersion` | 缓考进行中、未封账、撤销、未知 code、重复事实；禁止从分数/排名自行推断 | required all-of：`DEP-P1-ACADEMIC-001`、`DEP-P0-TIMETABLE-001`、`DEP-P0-CALENDAR-001` | 七个 ACN code、allowlist 空、封账前后、缓考中/后、撤销/重算、跨批次、节点/日历缺失、乱序更正各≥2 | Hei（A）；教务/学工负责人（R） | approved | inactive | fused | ready / gated-by-DoD |
| CORROBORATE-001 | 跨类别合证 | 1.0.0 | 独立 approved categoryId 数；absolute-threshold `>=2` | 参与正式线索 occurredAt 最大最小差 `<=168h`；168h 含边界；优先级不高于参与线索最高级 | 只引用 admitted 且未失效/噪声关闭的正式线索；不同 ruleId 不自动算独立类别 | 所有参与 RuleVersion 满足发布资格且上游事件可对账 | 1/2 类、恰 168h/前后 1 秒、乱序、撤销、参与线索失效各≥2 | Hei（A）；学工业务负责人（R） | approved | inactive | fused | ready / gated-by-DoD |

`ready / gated-by-DoD` 表示公式和验收参数足以实现，但质量、匿名样本、canary 和 D4 尚须 Story 实测；它不等于 runtime `production`。测试前 `qualityStatus=fused`，系统必须拒绝生成生产 RuleEvaluation/Candidate。

规则可通过 `careActionPolicyRef=CAC-1.0.0` 引用更严格的动作截止，但 RC 不得新增 CareAction 类别、completionKind 或 Clue/Observation 状态迁移；这些只由 CAC 定义。

## SPM-1.0.0 期限专项矩阵（非规则）

`SeasonalProgramMatrixVersion=SPM-1.0.0` 只构建期限专项只读投影，不是第七条 RuleVersion，也不增加下列 11 个 dependencyId。直连 source 只检查 approved/effective 合同、`SourceQualitySnapshot.status=eligible` 和 DCC 水位，不具有 runtime/production 状态；只有 RuleVersion 使用 approved/production/eligible。

SPM 的 `academicCalendarProjection` 由教务数据 owner 以 `SRC-P1-ACADEMIC-001 + SRC-P0-CALENDAR-001` 生成，不新增 sourceId/dependencyId；schema 固定为 `termId,termStartAt,examWindowStartAt,resultsSealedAt,graduationTermStartAt,graduationReviewSealedAt,notApplicableReasons,sourceVersions,projectionVersion,sealedAt,effectiveAt` 且 `projectionVersion=SPM-1.0.0`。Admission 只额外要求 termStartAt，Exam 要求 examWindowStartAt/resultsSealedAt，Graduation 要求 graduationTermStartAt/graduationReviewSealedAt；非适用字段可 null，但必须标 `NOT_APPLICABLE_FOR_PROGRAM`。两源合同/eligible/水位、投影 sealed/effective、termId 唯一或当前 program 必填/适用时序任一失败即 required failure；来源更正必须生成新 sealed projection，不原位改写。

| programId | required 控制输入 | optional 独立信号 | entry operator | 窗口 |
|---|---|---|---|---|
| ADMISSION-ADAPT-001 | STUDENT、RESPONSIBILITY、academicCalendarProjection | NIGHT-001；purpose=`ADMISSION_ADAPTATION` 的 PSYCH-DEID | 2-of-2 | 新生首学期 `[termStartAt,termStartAt+42自然日)` |
| EXAM-CARE-001 | STUDENT、RESPONSIBILITY、academicCalendarProjection | NIGHT-001、ACADEMIC-001 | 2-of-2 | `[examWindowStartAt-14日,resultsSealedAt+7日)` |
| GRADUATION-CARE-001 | STUDENT、RESPONSIBILITY、academicCalendarProjection | ECON-012、NIGHT-001、ACADEMIC-001 | 2-of-3 | `[graduationTermStartAt,graduationReviewSealedAt+7日)` |

required 失败阻断受影响专项新投影；optional 缺失标 degraded，不能解释为 0/否/正常。仅 eligible 且 affirmative 的独立 signalCategory 计数，同一学业类别多个节点只计一类；单信号不形成专项优先项或学生结论。专项唯一键为 `programId+programVersion+subjectRef+windowId`，只引用既有事实/Candidate/Clue并沿 lineage 更正，不创建或复制领域对象。心理仅允许 opaque ref/category/purpose/有效期/approvalVersion，网络只消费 NIGHT-001 聚合输出。

`AcademicCareNodeSetVersion=ACN-1.0.0` 固定：`MIDTERM_CARE_REVIEW`、`FINAL_CARE_REVIEW`、`DEFERRED_EXAM_RESULT_CARE_REVIEW`、`COURSE_COMPLETION_CARE_REVIEW`、`PHYSICAL_FITNESS_CARE_REVIEW`、`THESIS_MILESTONE_CARE_REVIEW`、`GRADUATION_REVIEW_CARE`；未知 code fail closed。

## P0/P1 数据依赖与逐源合同

| dependencyId | sourceId | 生产最低合同 | owner | due / 决策 | 当前 qualityStatus |
|---|---|---|---|---|---|
| `DEP-P0-CAMPUS-ACCESS-001` | `SRC-P0-CAMPUS-ACCESS-001` | 事件 ID、主体映射、校门/设备、方向、发生/接收时间、源版本、更正/撤销、补数水位、质量阈值和对账 | Hei（A）；保卫校门数据负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P0-DORM-ACCESS-001` | `SRC-P0-DORM-ACCESS-001` | 事件 ID、主体映射、楼栋/设备、方向、发生/接收时间、去重、更正/撤销、源版本、补数水位、质量阈值和对账 | Hei（A）；宿舍门禁数据负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P0-ACCOMMODATION-001` | `SRC-P0-ACCOMMODATION-001` | 学生—校内宿舍或校外住宿备案、校区/楼栋、有效区间、变更版本、来源时间、冲突处理、SLO、质量门和对账 | Hei（A）；宿管数据负责人 + 学工住宿备案负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P0-LEAVE-001` | `SRC-P0-LEAVE-001` | 请假/实习/离返校类型、起止时间、审批状态、撤销/更正、源版本和对账 | Hei（A）；学工请假/实习数据负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P0-CALENDAR-001` | `SRC-P0-CALENDAR-001` | BusinessCalendarVersion、适用区间、节假日/调休、生效版本、边界样本、SLO 与对账 | Hei（A）；校历数据负责人（R） | approved 2026-07-17 / DEC-012 closed、G-03/G-06 | fused |
| `DEP-P0-CONSUMPTION-001` | `SRC-P0-CARD-001` | 消费事件 ID、主体映射、商户/餐次类别、金额量纲、发生/接收时间、退款/冲正/撤销、源版本、补数水位、质量阈值和对账 | Hei（A）；一卡通数据负责人（R） | approved 2026-07-17 / DEC-012 closed；ECON-012 仍属首期场景 | fused |
| `DEP-P0-TIMETABLE-001` | `SRC-P0-TIMETABLE-001` | 学生/学期/教学班、课程活动起止时间、校区/地点、选课有效状态、停调课/取消、`effectiveAt`、`sourceUpdatedAt`、源版本、补数水位和更正链；必须提供 owner、SLO、质量门、契约测试与对账 | Hei（A）；教务课表数据负责人（R） | approved 2026-07-17 / DEC-012 closed、G-03 | fused |
| `DEP-P0-DEVICE-001` | `SRC-P0-DEVICE-001` | 设备 ID、位置、适用门禁源、在线/故障区间、心跳、维护窗口、源版本、SLO、质量门和对账 | Hei（A）；保卫设备负责人 + 宿管设备负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P1-OFFCAMPUS-001` | `SRC-P1-OFFCAMPUS-001` | FR-20 P0 校外住宿备案之外的离校/校外状态类型、有效区间、审批、撤销/更正、与 P0 住宿备案去重、源版本、SLO、质量门和对账 | Hei（A）；学工离校备案负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P1-NETWORK-001` | `SRC-P1-NETWORK-001` | 主体映射、会话时间/时长/流量/区域的最小汇总、禁止内容字段证明、源版本、SLO、质量门和对账 | Hei（A）；信息中心网络数据负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |
| `DEP-P1-ACADEMIC-001` | `SRC-P1-ACADEMIC-001` | 学业节点、学期/批次、封账、有效状态、更正链、学业日历版本、源版本、SLO、质量门和对账 | Hei（A）；教务学业数据负责人（R） | approved 2026-07-17 / DEC-012 closed | fused |

`DEP-P0-TIMETABLE-001` 是 FR-20 的 P0-MVP 依赖，不得因完整教务场景后置而省略。RuleDependencyRegistry 必须逐项保存 `sourceId`、`dependencyId`、required/optional、组合算子及版本；ACC-SAFE-001 对校门与宿舍门禁采用 required `all-of`，ACC-SAFE-002 只要求宿舍门禁但同样是其余排除依赖的 required `all-of`。任何 required 依赖缺合同、未达质量门或 `qualityStatus=fused/recovering` 时，不得产生新的 `RuleEvaluation`、`CandidateAdmissionDecision`、Candidate、Clue 或公共待办；不得因另一个门禁源 eligible 推断组合 eligible。界面只能向具权治理/运维角色显示依赖与熔断原因。测试 fixture 可显式提供版本和值，但不得把 fixture 当生产批准证据。

## 每个规则版本的必填字段

- 适用人群、场景与 purpose；
- 数据依赖及对应 `qualityGateVersion`（公式、量纲、比较运算符、阈值与含边界语义）；
- 指标公式、量纲、窗口、比较运算符与阈值；
- 排除条件、白名单约束及优先顺序；
- 关怀等级、初审/核实时限、解释模板；
- 业务去重键、版本兼容与回退版本；
- 命中、不命中、阈值等值、样本不足、乱序/更正等边界样本；
- 业务 owner、数据 owner、复核人、生效日期与证据链接。
- `governanceStatus`、`runtimeStatus`、`qualityStatus` 及每次状态迁移的 actor、reason、time、version；
- 对应 QueuePolicyVersion、BusinessCalendarVersion 或 WorkVisitPolicyVersion（适用时）。

## 生命周期与门禁

三类状态分别遵循以下状态机，不得拼成一条生命周期：

- 治理状态 `governanceStatus`：`draft → review → approved → retired`；任一未退休版本在证据不完整时可进入 `blocked`，解除 blocked 必须记录原因和新证据。`production` 不是治理状态。
- 运行状态 `runtimeStatus`：`inactive → canary → production → fused/disabled → rolled-back`。`review`/`approved` 不是运行状态。
- 质量状态 `qualityStatus`：`eligible | fused | recovering`。它表达依赖资格，不是规则治理状态，也不是 Candidate/Clue 业务状态。

申请 canary 必须同时满足：`governanceStatus=approved`、规则具有效日期和具名批准证据、匿名正反/等值/异常样本齐全、所有依赖 `qualityStatus=eligible`、高风险动作矩阵已 approved/effective 且 `rule.publish` 通过执行时重检。进入 production 还必须通过 canary 观察、影响与容量护栏及 maker/checker 审批。任一条件缺失均 fail closed。

`governanceStatus=blocked` 与 runtime `canary/production` 的组合非法；系统必须拒绝并审计。已经处于 production 的已批准版本因数据质量问题可出现 `runtimeStatus=fused + qualityStatus=fused`，这只表示暂停新计算，既有 Candidate/Clue 仍可见且历史不得改写。恢复时先进入 `qualityStatus=recovering`，完成样本重算、影响预览、观察和高风险恢复审批后才可回到 eligible/production。

全部六条当前均为 `governanceStatus=approved + runtimeStatus=inactive + qualityStatus=fused`，可进入实现但不得用于生产。Story 3.1a—3.4 负责 ACC-SAFE-001/002；Story 4.1a 负责 ECON-012、NIGHT-001、ACADEMIC-001 的匿名边界包、全部 required 依赖质量、D4 `rule.publish`、canary 与 production 证据；Story 4.7 在上游正式线索可对账后负责 CORROBORATE-001 的相同发布七路径。只有所属发布 Story 通过后，该 RuleVersion 才可转为 eligible/production 并被 4.1b、4.2、4.3 或 4.7 的场景链路消费。此前不得创建或展示相应生产 Candidate/合证，也不得把信号写成学生结论。

规则目录的任何变更均创建新版本；不得原位覆盖已被候选、线索或报告引用的历史版本。
