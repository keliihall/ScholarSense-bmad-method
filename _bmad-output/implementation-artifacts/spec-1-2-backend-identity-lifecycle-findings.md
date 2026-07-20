---
title: 'Story 1.2 后端身份生命周期 findings #3/#5/#7'
type: 'bugfix'
created: '2026-07-20T08:45:00+08:00'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: 'a0c8a9cba10d963c41623d27a8480dbbbddea393'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/1-2-接入统一门户-sso-与可恢复应用壳.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** host bootstrap 只有不可达的兑换路径，refresh 依赖调用方提供 bearer，远端登出 outbox 未装配，导致 iframe 接入、token 轮换与 account switch 状态机失效。

**Approach:** 在现有 BFF、JDBC store 和 outbox 内接通一次性 bootstrap、服务端 custody 驱动的 IdP refresh 与远端登出调度；KMS/IdP 成功只来自显式 runtime port。

## Boundaries & Constraints

**Always:** HTTP Origin 证明 application 同源请求，portal origin 另与冻结配置、browser session、audience、一次性记录精确匹配；refresh token 仅服务端解密并清零；并发 refresh 先锁共享 session；拒绝写最小审计；account switch 首响 pending 且无 OAuth，confirmed 后更新原幂等结果，重放才返回 OAuth。

**Ask First:** 新增浏览器 bearer、放宽 origin、持久化明文 token，或修改前端、Story、sprint-status。

**Never:** 不伪造 IdP/KMS 成功，不用内存代替 shared store，不覆盖其他代理对 store/command 的并发改动。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Behavior | Error Handling |
|---|---|---|---|
| Bootstrap | 已认证、精确 origins/binding/audience | 签发后首次兑换成功 | 漂移统一拒绝；重复为 already-used |
| Refresh | 当前版本、encrypted custody | 单次 IdP rotation，原子更新 digest/custody，无 token 响应 | KMS/IdP/DB fail closed 并审计 |
| 并发 refresh | 两节点竞争同一 session | 仅一方调用 provider 并提交 | 另一方版本拒绝并审计 |
| Account switch | revoke/outbox 已提交 | pending 无 OAuth；confirmed 后重放返回 OAuth | provider 失败持久重试 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/IdentityAccessConfiguration.java` -- runtime port、服务与 scheduler 装配。
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/` -- bootstrap/refresh BFF 与时间触发适配器。
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/` -- custody、refresh、remote logout 状态机。
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/` -- JDBC lock/read/update 与 KMS decrypt。
- `backend/src/main/resources/db/migration/identity-access/V000001__identity-access__session_boundary.sql` -- bootstrap/outbox/确认持久字段。
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/` -- 一次性、并发、审计、pending→confirmed 回归。

## Tasks & Acceptance

**Execution:**
- [x] 接通绑定 browser session、配置 portal origin/audience 的 bootstrap 签发/消费。
- [x] 接通 custody 读取/解密、provider refresh、无 bearer 的 BFF endpoint 和 session 行锁。
- [x] 装配 remote logout processor/scheduler，confirmed 后推进 account-switch 幂等结果。
- [x] 增加定向测试并合并验证 store/command 并发修改。

**Acceptance Criteria:**
- Given 精确 origins 与同一 browser session，when 签发并兑换，then 首次成功且重放/漂移失败。
- Given encrypted custody，when refresh，then 浏览器不提供/接收 bearer，并发只产生一次有效 provider rotation。
- Given account switch pending，when 首响，then 无 OAuth；when provider confirmed 且同请求重放，then 才返回 OAuth 且不重复撤销。

## Spec Change Log

## Design Notes

bootstrap 将 application Origin 与 portal 身份证明分层。Refresh 在数据库行锁内读取 custody 后调用 provider；外部成功与数据库提交不伪装成分布式原子事务，失败保持 fail closed。

## Verification

**Commands:**
- `backend/mvnw -f backend/pom.xml -Dtest='HostBootstrapServiceTest,SessionRefreshServiceTest,RemoteLogoutProcessorTest,SessionCommandServiceTest' test` -- 定向通过。
- `backend/mvnw -f backend/pom.xml test` -- 后端回归通过。
- `git diff --check` -- 补丁格式通过。
