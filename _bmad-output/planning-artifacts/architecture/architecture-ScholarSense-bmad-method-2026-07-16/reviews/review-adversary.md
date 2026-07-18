# 架构脊柱对抗复审

## Verdict

**通过。** 上轮 C-1..C-3、H-1..H-3 均已形成能让独立 Epic 得出相同实现边界的规则；未发现残余 Critical 或 High。当前仅有 3 个 Medium 措辞/运行契约细化项，不阻断架构定稿或 Epic 拆分。

## 复审范围

只复测上轮六个阻断接缝：Candidate/Clue、审计原子性、事件契约、读模型、Job fencing、主体更正。方法仍为构造两个逐字遵守 AD 的下游实现，判断它们是否还能在事实所有权、完成判据或消费语义上不兼容。

## 已关闭发现

### C-1 Candidate → Clue 所有权与提交协议 — 已关闭

- AD-2 的实体所有权表将 Candidate、Clue、EvidenceSnapshot、CareAction、Observation、TaskLink 全部唯一归属 `clue-care`。
- AD-19 固定 `CareCase` 为同时拥有 Candidate 与 Clue 的聚合；`signal-evaluation` 只发布 RuleEvaluation。
- 接纳在同一聚合事务内原子标记 Candidate accepted 并创建唯一 Clue，以返回 Clue ID 为完成判据；拒绝与合并也只有该命令入口。
- 因此，原先“事件驱动跨模块创建 Clue”和“先创建 Clue 再回写 Candidate”两种不兼容实现已被排除。

### C-2 审计与业务原子性 — 已关闭

- AD-10 明确每个业务模块在自身事务内写 append-only `LocalAuditFact` outbox，本地审计提交失败时关键命令整体失败。
- `audit-operations` 仅以 auditId 幂等归集，并唯一拥有集中 AuditLedger、归档确认和检索投影；这被明确声明为 AD-2 的唯一审计基础设施协作规则。
- 敏感读取还固定为“审计事实持久提交后才返回数据”，消除了读操作只能事后异步审计的分歧。
- 因此，本地合规事实与集中账本的权威层次、失败行为和重放路径已唯一。

### C-3 事件载荷、演进与处理语义 — 已关闭

- AD-24 将 `contracts/events` 固定为 schema 权威，并规定集成事件为自足事实快照而非 ID 通知。
- 信封包含 schemaVersion、aggregateType/Id/Version；owner 单写、版本单调；重复幂等、旧版本拒绝覆盖、间隙暂停补拉、毒消息隔离均已锁定。
- 同 major 只兼容加字段，删除/改义升 major 并双读；更正/撤销追加新事实事件。
- 因此，上轮完整快照与回查通知、投递顺序与聚合顺序、原位改义与版本升级之间的分歧已被排除。

### H-1 读模型 owner、构建与撤权 — 已关闭

- AD-24 固定各模块拥有本域读模型、`reporting` 拥有跨域读模型；构建只消费版本化事实事件。
- 每来源保存 watermark，重建追平后原子切换。
- 授权与字段投影必须在读取/下载时按当前策略执行，禁止持久化明文授权结果。
- AD-25 又规定更正事件触发本域/跨域读模型、缓存和未完成导出失效，并以消费者 watermark 与对账作为完成判据。
- 因此，预先固化授权结果和请求时动态授权两种不兼容路径已不再同时合规。

### H-2 Job 状态机与 fencing — 已关闭

- AD-13 固定作业由触发业务模块拥有，状态为 queued→running→succeeded/failed/cancelled，重试递增 attemptNo。
- 租约使用单调 fencingToken；checkpoint、业务写入和结果发布均必须校验当前 token，取消/超时后的旧 worker 不得提交。
- 结果先写不可变临时对象，再与 succeeded 状态原子发布。
- 因此，旧 worker 与新 attempt 并发提交、不同 Epic 自定义终态或直接覆盖结果的问题已被封闭。

### H-3 主体合并、拆分与更正 — 已关闭

- AD-4 固定 StudentRef 为永不复用的内部 ID，外部标识带来源、类型、有效期和映射版本。
- 合并、拆分、更正只能经命令追加事件；历史证据保留产生当时的 StudentRef，不迁移；读模型通过 alias/redirect 表达当前关联。
- AD-25 固定 supersedesId、reason、effectiveAt、lineageId 及下游级联：事实标记、评估 supersede、线索追加更正/人工复核、候选沿 lineageId 去重、读模型/导出失效重建。
- 因此，历史迁移与历史稳定、重新生成候选与 lineage 去重等冲突实现已被排除。

## 残余发现

### Medium M-1 集中审计 sequence 与本地重放顺序的术语仍可更精确

AD-10 同时提到集中 AuditLedger 的连续 sequence，以及归集失败后“按 sequence 重放”，但 LocalAuditFact 在进入集中 writer 前是否已有 producer-local sequence 未明确。两个实现可能分别按 occurredAt/auditId 或模块本地序号重放；它们不会导致事实丢失（auditId 幂等与集中 writer 可兜底），但会影响缺口告警和可复现实验。

**建议：** 将集中 `ledgerSequence` 与本地 `producerSequence` 分名；若本地不要求连续，则改为“按未归集集合重放，由集中 writer 分配 ledgerSequence”。

### Medium M-2 “事件间隙补拉”的端口和不可恢复判据未固定

AD-24 要求 aggregateVersion 出现间隙时暂停并补拉，但未说明补拉的是缺失事件、当前聚合快照还是重放流，也未规定 source 已归档/保留期外时的失败状态。不同消费者可形成不同恢复适配器，不过不会改变正常路径的事实语义。

**建议：** 固定一个 `EventReplayPort` 语义：按 aggregateId + version range 请求事实事件；不可补齐时进入 blocked/dead-letter 并禁止推进该聚合 watermark。

### Medium M-3 split 场景的 alias/redirect 不是天然一对一

AD-4 已锁定历史不迁移，足以关闭原 High；但一个旧 StudentRef 拆成多个当前 StudentRef 时，单数 `redirect` 容易被实现为一对一。两个读模型可能分别展示旧档案入口或要求人工选定当前主体。

**建议：** 将关系显式定义为 `SubjectLink(fromRef, toRef, relationType, effectiveAt)`，其中 relationType 至少包含 alias、merged-into、split-into；split-into 允许一对多且禁止自动选择当前主体。

## 严重度汇总

| 严重度 | 数量 | 结论 |
| --- | ---: | --- |
| Critical | 0 | 无阻断项 |
| High | 0 | 无阻断项 |
| Medium | 3 | 可作为清晰度修补，不阻断定稿 |
| Low | 0 | 本轮未扩展检查 |

## 通过条件判定

已满足：任意两个下游 Epic 仅凭当前 spine，均能对以下问题给出一致答案：谁写 Candidate/Clue/Audit/StudentRef；接纳何时完成；事件如何排序、演进与更正；撤权后读模型如何处理；旧 worker 如何被 fencing；主体更正后历史归属何处。
