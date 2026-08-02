---
title: '1.6c 失效传播并发、作业与运行证据加固'
type: 'bugfix'
created: '2026-08-01'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'c7cdf02e29745887259fbb4abd6bc3b4db651a96'
context:
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 高扇出 OFFSET 会在并发变更时漏发/重复，且 recovery cause 被过滤；expiry 未绑定调度事实并存在 READ COMMITTED TOCTOU；SLO、lag、隔离作业指标和沙箱可能展示无数据库证据的成功。

**Approach:** 使用持久 keyset 游标与作业绑定字段，在同一事务提交点锁定并条件校验 expiry；让 SLO、指标和沙箱只从受影响 scope 回读及实际 PostgreSQL 同 trace 结果生成。

## Boundaries & Constraints

**Always:** 动态 current-scope 是即时 deny 第一证据；impact/expiry 每批原子提交 fact/outbox/audit/cursor；到期可 catch-up；指标无高基数标签；证据沿用同一 traceId；保存现有改动。

**Ask First:** 需要新增表、引入消息中间件、改变公开事件合同或扩大到未来 task/export/mobile 真实消费者时暂停。

**Never:** 修改 Story、sprint-status、V000008、IdentitySyncConfiguration、AccessInvalidationPublisherService、V2 contract/sync、consumer/transport、scope query；不得使用 mutable OFFSET、刷新时间伪装 age 或常量 PASS。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 高扇出并发分页 | 页间早序 relation 变化 | 从持久 last lineage key 继续 | 冲突按现有策略重试/隔离 |
| recovery fan-out | SOURCE_CORRECTION identity/org cause | 基于当前 scope/identity 生成 CORRECTED 或 REVALIDATED | 不得被三类 deny reason 过滤为 0 |
| 正常/过期 catch-up | effectiveTo 到达或服务停机后恢复 | 领取绑定 event/version/effectiveTo/reason 的作业并追加一次 EXPIRED | stale job 原子完成但不追加 |
| expiry 并发更正 | 检查后 relation/head 被新版本替换 | relation/head 行锁与条件在提交点阻止旧 expiry 生效 | 失败重试；旧绑定最终判 stale |
| identity/org 级联 | 账号/R1/学院失效 | 受影响 student scope 回读 INVALID 才计入 15m 成功 | 空、VALID、版本不符或超时进入分母 |
| 长期 pending | reconciliation 周期刷新 | lag 仍从事实发生/首次 pending 时间增长 | 查询失败返回 NaN，不回退到刷新时间 |
| 作业隔离 | impact/expiry quarantined | poison 与各自 backlog 均包含该作业 | 无静默消失 |
| 沙箱证据 | PostgreSQL E2E 完成 | 从测试查询的同 trace 行生成 | 缺文件、trace 分裂或未达标则失败 |

</frozen-after-approval>

## Code Map

- `.../JdbcAccessInvalidationImpactResolver.java`, `.../AccessInvalidationImpactWorker.java` -- keyset 与 recovery fan-out。
- `.../JdbcAccessInvalidationJobRepository.java`, `.../AccessInvalidationExpiryWorker.java` -- cursor、expiry 绑定/锁。
- `.../IdentitySyncService.java` -- cascade scope SLO 回读。
- `.../MicrometerIdentitySyncObservabilityAdapter.java` -- age、poison/backlog。
- `.../AccessInvalidationPostgreSqlIT.java`, `scripts/run_access_invalidation_sandbox_tests.py` -- 同 trace E2E 证据。

## Tasks & Acceptance

**Execution:**
- [x] 移除 OFFSET，持久化 keyset；SOURCE_CORRECTION 生成 CORRECTED/REVALIDATED。
- [x] 绑定 expiry event/version/effectiveTo/reason，提交前锁 relation/head 并条件追加。
- [x] cascade SLO 只接受受影响 scope 的 INVALID 回读。
- [x] lag 用 fact occurred_at；quarantined job 纳入 poison/backlog。
- [x] PostgreSQL IT 输出同 trace 查询结果，sandbox 严格校验。

**Acceptance Criteria:**
- Given mutable current rows, when 分页且页间变化, then 无 OFFSET 且 keyset 单调；SOURCE_CORRECTION 不被过滤并输出正确变化类型。
- Given 已调度 expiry 与并发更新, when worker 尝试提交, then 只有 event/version/effectiveTo/reason 全部仍匹配的作业可追加 EXPIRED。
- Given 停机跨过 effectiveTo, when worker 恢复, then due job 被 catch-up；stale job 不追加。
- Given identity/org 撤权, when 记录 15m SLO, then 每个受影响 responsibility scope 的 INVALID read-back 为成功唯一依据。
- Given 长期 pending 与 quarantined jobs, when 抓取指标, then age 持续增长且隔离作业仍可见。
- Given sandbox command 成功, when 打开 evidence, then 每一步值来自同次 PostgreSQL 测试查询且 traceId 完全一致。

## Design Notes

impact job 新增可空 `cursor_lineage_id`，查询用 `access_lineage_id > cursor`。expiry 复用 `cause_event_id/cursor_value/due_at/reason_code` 绑定 event/version/effectiveTo/reason，追加前 `FOR UPDATE` relation current 与 lineage head。生产者/配置由父任务接线。

## Verification

**Commands:**
- `./backend/mvnw -q -f backend/pom.xml -Dtest=AccessInvalidationWorkersTest,IdentitySyncServiceTest,MicrometerIdentitySyncObservabilityAdapterTest test` -- 定向单测通过。
- `scripts/run_audit_postgresql_tests.sh` -- PostgreSQL 18.4 clean/upgrade、并发与 same-trace E2E 通过。
- `python3 -B scripts/run_access_invalidation_sandbox_tests.py --evidence <temp>` -- 仅在真实 E2E 输出有效时生成证据。

## Suggested Review Order

**发布与因果边界**

- 从已提交权威变更发布 cause，并持久化后续 impact/expiry 工作。
  [`AccessInvalidationPublisherService.java:95`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/AccessInvalidationPublisherService.java#L95)

- 每页事务先锁定并校验当前 cause，再原子推进 keyset。
  [`AccessInvalidationImpactWorker.java:52`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/AccessInvalidationImpactWorker.java#L52)

- 历史 role 绑定按账号与学院精确解析旧、新受影响 scope。
  [`JdbcAccessInvalidationImpactResolver.java:39`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcAccessInvalidationImpactResolver.java#L39)

**作业绑定与并发提交**

- Expiry enqueue 校验事件、版本、时点、原因与幂等绑定。
  [`JdbcAccessInvalidationJobRepository.java:68`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcAccessInvalidationJobRepository.java#L68)

- Impact cause-head 锁阻止旧作业覆盖较新的恢复事件。
  [`JdbcAccessInvalidationJobRepository.java:289`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcAccessInvalidationJobRepository.java#L289)

- Impact enqueue 对同 jobId 的不同不可变绑定 fail-closed。
  [`JdbcAccessInvalidationRepository.java:106`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcAccessInvalidationRepository.java#L106)

- 到期 worker 在提交点验证绑定，并隔离 stale lease failure。
  [`AccessInvalidationExpiryWorker.java:52`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/AccessInvalidationExpiryWorker.java#L52)

- DDL 保存 append-only role 绑定历史并定义持久作业状态。
  [`V000008__identity-access__access_invalidation_v1.sql:149`](../../backend/src/main/resources/db/migration/identity-access/V000008__identity-access__access_invalidation_v1.sql#L149)
  [`V000008__identity-access__access_invalidation_v1.sql:1168`](../../backend/src/main/resources/db/migration/identity-access/V000008__identity-access__access_invalidation_v1.sql#L1168)

**级联、隔离与可观测性**

- Cascade SLO 逐个验证精确 lineage、事件版本与 INVALID 回读。
  [`IdentitySyncService.java:452`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/IdentitySyncService.java#L452)

- 受影响 scope 查询限定 current、trusted、active 与有效时间窗。
  [`JdbcIdentityCascadeScopeReadBackAdapter.java:29`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcIdentityCascadeScopeReadBackAdapter.java#L29)

- 启动时验证 producer/consumer 数据库主体与变更权限分离。
  [`AccessInvalidationDatabaseRoleVerifier.java:7`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/AccessInvalidationDatabaseRoleVerifier.java#L7)

- Poison、backlog 与 propagation age 均保留 quarantined/pending 事实。
  [`MicrometerIdentitySyncObservabilityAdapter.java:233`](../../backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/MicrometerIdentitySyncObservabilityAdapter.java#L233)

**执行证据与回归测试**

- PostgreSQL IT 执行完整 publisher 到 reconcile 同 trace 链。
  [`AccessInvalidationPostgreSqlIT.java:1802`](../../backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/AccessInvalidationPostgreSqlIT.java#L1802)

- Sandbox 重算时间窗并拒绝缺失、分裂或伪造证据。
  [`run_access_invalidation_sandbox_tests.py:82`](../../scripts/run_access_invalidation_sandbox_tests.py#L82)
