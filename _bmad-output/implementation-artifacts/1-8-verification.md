# Story 1.8 Verification

## 结论

**PASS — review follow-up 已完成。** Story 1.8 的 12/12 review finding 已关闭；audit 1.4 后继证据持久化、前端 `surface-forbidden` 门禁与候选 File List 纠正均在隔离候选通过最高层门禁。Story 与 sprint 已按代码审查工作流标记为 `done`；本记录不构成生产发布或提升声明。

## Committed candidate provenance

| 项目 | 值 |
|---|---|
| candidate commit | `a14f88cd578685c114b8714c73473ccfc0503492` |
| tree | `a12c8cfbaaccb3e1f4edd5249a661d04ab4c875c` |
| parent / 原工作区 HEAD | `3b811064fb24063532f90df8b532c1c4464e4e2b` |
| retained ref | `refs/codex/review-candidates/story-1.8` |
| Git tree manifest | 1,768 files；`git ls-tree -r --full-tree` SHA-256=`e056ae478f15eae6b6962457a8391b0c51e9069226c45868b6a5c29134d6112c` |
| candidate worktree | detached、tracked status clean |
| 原分支 / index | `main` HEAD 未移动；index SHA-256 前后均为 `9e46f4d5f66fabd79918606eedee2153fe8b7e2234f27abfc6691427937333c4` |

候选由 alternate index + `git commit-tree` 冻结，未把用户工作区的既有改动加入当前分支或暂存区。v5 在 v3 的 9 条修复上收口 3 条 follow-up：V000009 将 11 字段 audit 1.4 上下文与冻结 v1 fact 同事务落库；敏感搜索只接受 `ready|degraded`；候选内 File List 已指向真实 `api` 路径。v4 `0694819c` 首轮门禁发现另一全局迁移计数锁仍为 8，已在 v5 更新为 9 并从头复验；当前 retained ref 只指向上表 v5。

本报告和机器摘要是运行结束后生成的 post-run record，按定义不包含在它们所引用的候选 commit 中；它们不声称自引用 provenance，候选身份由 commit/tree/ref 和日志 digest 共同对账。

## 命令与日志台账

执行环境为 macOS 26.5.2 arm64、Asia/Shanghai；JDK 25.0.3、Maven 3.9.16、Node 24.18.0、npm 11.16.0、Python 3.14.6、PostgreSQL 18.4、Git 2.50.1。

| 命令 | 时间（+08:00） | 退出 | 日志 bytes | 日志 SHA-256 |
|---|---|---:|---:|---|
| `./scripts/bootstrap.sh` | 12:27:42—12:27:54 | 0 | 58,109 | `be00c9c6e9901b5266939c87a4de15cedb9dbaef107c728d5a20ccc3ec34af3c` |
| `./scripts/verify.sh` | 12:29:04—12:42:03 | 0 | 447,445 | `acabe44d801873eca328fa40548d4ef7cbb72668cf270107449000a776dcd3b1` |
| 最终日志 + 后端产物 canary 扫描 | 12:44—12:45 | 0 | 87 | `21e666cbd9a558b19c254c886e106974d055e106353b5550577310e6f815ca54` |

日志保存在本机临时证据目录，以上 digest 是本 Story 的持久对账记录；任何不同字节不得复用本结论。

## 验收结果

| 面 | 不重复累计的结果 |
|---|---|
| Maven / Java | 468 tests，0 failure / error / skip；架构边界、合同、授权、TOCTOU、crypto、audit rollback 与 9-migration 全局锁全绿 |
| Python / checker | `_bmad/scripts/tests` 145、`scripts/tests` 299，全部通过；authorization、field-projection、audit、release、workflow、production-pollution checker 全绿 |
| PostgreSQL | 18.4；专项 70 tests 全绿；clean 与 V000001—V000009 legacy upgrade inventory 相同，`migration=V000009` |
| 前端 | 每个隔离 replay：9 files / 70 Vitest，通过 typecheck 与 build budget `initialRawTotal=197834` |
| Playwright / axe | 每个隔离 replay：113 passed；15 项为既有、明示的非适用设计矩阵，不是 Story 条件性跳过 |
| 可复现 release | 两个 clean release attempt 完全一致；`artifactSet=9cd8929f5bbdd4999e99ff0edaca9f08f00078db214a70a486aafa20a3fa86ca` |

`verify.sh` 内部会再次执行 core，并在两个 clean clone 中各执行 core；表格只报告单次套件规模，不把重复复验虚增为测试数量。

## PostgreSQL 与浏览器边界

- PostgreSQL schema fingerprint：`bccf4f372d54ecbe5d8a92d9b42074ffe836cedbbd97440aa9e755c705bf9099`；inventory summary `85|1380|1765|229|0|0`。V000009 仅在既有审计行增加 nullable `authorization_decision_context`；不新增持久化字段 crypto、consumer 或 watermark。历史行保持 null，N-1 应用可继续省略该列写入。
- 真实 R3/R7 audit search 已覆盖精确字段、参数化恶意输入、冻结快照翻页、并发隔离、审计失败 rollback 和本地 metadata-only audit evidence；恶意原文在 local fact/outbox 中零命中。
- 四个 TEST-ENV 制品的既有 36/36 audit-search 底线证据仍有效；v5 的状态门禁单测已对 `loading|surface-forbidden|authorization-unavailable` 逐一拒绝，并在候选固定 Playwright Chromium 的 375/768/1366/1440 四项目中纳入 113 passed 全量结果。

| profile | artifact SHA-256 | executable SHA-256 |
|---|---|---|
| Chrome 150.0.7871.124 | `36c8b5fe04c08a418a172206bb392600ec1550941bde6af2d4353df21db87a47` | `22ddf33cec88bbfd181588eb3da31250a65ba8ebfdb6efcd2694a36275697284` |
| Chrome 149.0.7827.155 | `135b697c49a375025ba6540a9d963d803d0b80b01f497c77ef5fd8296e4f36c7` | `e9c22e6eb15fc062f58202f8fbebbe1e6e2d30211a9d4739a5593e986e7bf01d` |
| Edge 150.0.4078.65 | `68929c051651b056123369874fe5f6bea0a268500e6c506f6922b2d539a2fd86` | `d82cb159d44fecd4e7263b7d20b55e9ca46f0c18485eb0bcdf63b635bd9664bb` |
| Edge 149.0.4022.98 | `0165f110a529d2ed8ce98ed82ef4b19c39ae6b0485b88ccd5797e710f6b9b9d5` | `e7da6f1bf1824324bcdd44ad75f87fc40d02bd848a10b5020bd52518133648af` |

## 隐私、安全与 release binding

- 最终聚合扫描覆盖候选 stdout/stderr 日志、Surefire reports、classes 与生产 JAR：21,157 个载荷、157,305,976 bytes、20,091 个 archive member、5 类 canary，零命中。每个前端 replay 的 16 个 dist 文件另由门禁原位扫描并零命中。扫描器同时强制跨嵌套归档的 100,000 成员与 1 GiB 累计解压上限。
- 授权负例、并发失效、KMS/clock/IAM/RFP 不可用、审计失败与数据库 rollback 均 fail closed：成功越权读取 `0`、明文输出 `0`、审计绕过 `0`。
- RFP、RFP fixture、FieldProjection contract lock、authorization audit 1.4 与 RS-1.0.0 仍为 controlled inputs。ReleaseManifest fixture canonical SHA-256 为 `e225537cacc67e29e820846f0673984e8c054a738c453ef0eb54049eaf6617a3`，EvidenceIndex fixture 已按该 subject 重绑定并通过 lifecycle checker。
- fixture 中的 OCI URI、占位 digest/size 不是本 Story 的真实生产证据；没有签名、OCI push、promotion 或生产 KMS/算法部署声明。

## Runtime ownership handoff

- NFR-13：Story 1.8 final-owner 运行证据已关闭。
- FR-6 / FR-7：本 Story 关闭在线字段投影与任务期授权 contributor 范围；full owner 仍为 Story 3.14c。
- NFR-14：在线输入、参数化访问和字段投影范围通过；异步导出输入/文件投影仍由 Story 3.14b 验收。
- NFR-16：Story 1.8 当前投影面零绕过通过；导出与全局零绕过最终证据仍由 Story 3.14b 验收。
- `export-job-download`、`transfer-task`、`mobile-projection` 继续保持 `pending-story-execution + runtimeEvidenceClaim=none`；没有 ExportJob、文件下载、Transfer/Task 或移动端运行能力。

机器可读摘要见 `evidence/1-8-field-projection-candidate-summary.json`。
