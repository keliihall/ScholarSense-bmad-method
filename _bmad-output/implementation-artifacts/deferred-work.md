# Deferred Work

## Story 1.1a 收敛/关闭审计（2026-07-18）

### DEFER-1：原型生产构建主 chunk 超过 Vite 默认提示阈值

- **证据**：`npm run build -- --outDir /tmp/scholarsense-story-1-1a-build-20260718-closure` 退出码 0；主 JavaScript chunk 为 1,810.51 kB（gzip 592.49 kB），Vite 提示超过 500 kB。
- **分类理由**：这是现有 Vite 5 / Vue 3 原型的性能提示，不导致构建或测试失败；Story 1.1a 只审计资产与供应链候选，明确不得实施生产前端、架构升级或性能优化。
- **后续 owner**：Story 1.1c（生产前端组合与性能 profile ADR）；按批准的 profile 决定是否拆包、调整 chunk 策略或接受阈值。
- **关闭影响**：不阻断 Story 1.1a；不得据此声称生产性能或制品提升已通过。
- **Story 1.1c 处置（2026-07-19）**：已由 `FPB-1.0.0` 批准可机器检查的入口/异步 chunk raw+gzip 预算、测量口径、按组件/路由拆包策略和超限非零退出规则。原型数字仅保留为风险输入；本处置不表示完整业务页容量或 1.1d 制品提升已通过。

## Story 1.5 生产激活延期（2026-07-23）

### DEFER-2：审计搜索生产授权保持关闭

- **证据**：`AUDIT-RETENTION-CAPABILITY-1.0.0` 固定 `conformanceVerified=true`、`productionAuthorizationEnabled=false`；production authorization port 对 R3/R7 全部 fail closed。
- **后续 owner**：Story 1.6/1.7 接入权威 role/scope、撤权并完成“下一请求重检”E2E 后，才可通过新版 capability manifest 激活。
- **关闭影响**：不阻断 Story 1.5 conformance；不得把 RFP fixture 或客户端角色当作生产授权。
- **运行时组合澄清（2026-08-04）**：Story 1.7/1.8 已在当前组合运行时交付权威 RFP 授权与下一请求重检；基础 `AUDIT-RETENTION-CAPABILITY-1.0.0` 仍是不可原位改写的历史基线，其中 `productionAuthorizationEnabled=false` 只描述基础 capability，不能覆盖后继组合能力，也不能被解释为全环境生产已启用。实际启用仍以候选、环境和不可变运行证据为准。

### DEFER-3：生产归档与跨域销毁证明保持不可达

- **证据**：capability manifest 固定 `productionArchiveEnabled=false`、`archiveAdapter=unbound`、`deletionReceiptRuntimeIssuable=false`；本 Story 仅以 test-only immutable fixture adapter 验收。
- **后续 owner**：基础设施 owner 绑定经批准的独立 object-lock/WORM adapter；Story 6.6 在所有消费者、读模型、对象、索引、缓存和 35 日备份水位确认后签发真实跨域 `DeletionReceipt`。
- **关闭影响**：不阻断 audit-domain / non-production `RetentionExecution`；不得声明生产 WORM、legal hold 或销毁回执已经运行。

## Epic 1 生产证据边界（2026-08-04）

- Story 1.2 的真实学校门户、IdP、生产 KMS/共享数据库、冻结正式浏览器以及独立签名 host/SSO 运行证据仍是独立开放项；本仓库 mock、fixture、同源测试页和限定豁免不构成替代证据。
- Story 1.9 已合入 `main@9ab3369`；其历史 `runtimeClaim=none` 合同证据不构成真实业务 consumer apply 或目标环境提升声明。
- Story 2.1 的本地 DCC/QG fixture、checker 和 sandbox 只证明合同实现；逐源 `published` 资格必须另有获准目标运行、不可变 URI、候选 subject/digest 与清理结果。
- Story 6.6 继续拥有全域保留、legal hold、备份水位和跨域销毁回执最终验收；Epic 1 `done` 不表示生产 WORM 或全域销毁已经完成。

## Story 2.2 FR-13 分阶段关闭（2026-08-06）

### DEFER-4：主体更正下游生产 consumer 激活

- **受控合同**：`SUBJECT-DEFERRED-CONSUMERS-1.0.0` 与 `SUBJECT-CONSUMERS-1.0.0`。
- **本 Story 关闭范围**：主体映射、历史窗口、映射更正重算请求、ingestion-quality 作业编排与 owner conformance；本地 fixture 只证明 contract conformance。
- **后续 owner**：Story 3.4 的 `signal-evaluation` 必须追加 successor evaluation；Story 3.5 的 `clue-care` 必须追加证据更正/人工复核并沿用 Candidate lineage 去重；Story 3.14c 负责更正水位上的导出失效。
- **证据边界**：上述 owner consumer 在各自 Story 激活并绑定真实候选前均保持 `runtimeEvidenceClaim=none`；不得用本 Story fixture 冒充生产 apply、水位收敛或 FR-13 runtime full。

## Story 2.4 质量资格分阶段关闭（2026-08-10）

### DEFER-5：质量资格目标部署、恢复与规则消费激活

- **本 Story 关闭范围**：RuleDependencyRegistry、owner-local QualityEligibility/cursor/inbox/backfill/quarantine/current/history/audit/outbox、R6 全成员授权只读投影，以及 duplicate/old/gap/poison/backfill conformance。
- **尚未激活的目标输入**：eligibility consumer/relay/retention 的独立登录与 mTLS、权威 workload authorization、trusted-time、质量事件 subscription/publisher、真实 backfill/retention capability；additive runtime/roles v3 固定为 `deployment-input-required`，`runtimeEvidenceClaim=none`。
- **后续 owner**：Story 2.5a-c 安装具权恢复、熔断任务与收敛流程；Story 3.2 安装 signal-evaluation consumer，并以真实 QualityEligibility 阻止新的 RuleEvaluation/Candidate/Clue；生产持续时长与最终运行闭环由相应 owner/Release DoD 验收。
- **证据边界**：schema/fixture、PostgreSQL 行、outbox 或本地 clean replay 只证明可复现实现，不构成生产部署、真实 consumer apply、恢复审批、backfill/retention 激活或“熔断后新正式线索数=0”的运行声明。

## Story 2.5a 质量熔断与任务分阶段关闭（2026-08-11）

### DEFER-6：公共目标激活、恢复执行、规则消费与生产持续时长

- **本 Story 关闭范围**：owner-local fuse latch/episode/RecoveryTask、eligibility/audit/outbox 原子提交、PIC 1.1.0 quality-task intent、独立 TaskDelivery relay、source-owned 审计查询、desktop 只读投影，以及 duplicate/gap/poison/CAS/concurrency/64 KiB conformance。
- **尚未激活的目标输入**：PIC 1.1.0 真实 quality-task target 与 receipt provider、受保护 mTLS/工作负载凭据、恢复命令/审批/观察窗、Story 3.2 signal-evaluation consumer、Story 5.5 final public apply 和生产持续时长采集；runtime/roles v4 与 release v7 均固定为 `deployment-input-required`、`runtimeEvidenceClaim=none`。
- **已完成边界（Story 2.5b，2026-08-13）**：已安装 QRP evidence validation、真实 sample provider、identity-access-owned D4/15m durable execution lease、literal `quality-fuse.recover` action capability，以及 ingestion-quality 单写入者 `fused -> recovering` 正向闭环；该边界的 `runtimeEvidenceClaim=story-2.5b-executable-closure`，不得再记为 `none`。
- **后续 owner**：Story 2.5c 负责完整 observation、复发回退、`recovering -> eligible` 与 episode/task 收敛；Story 3.2 以真实 QualityEligibility 阻止新的 RuleEvaluation/Candidate/Clue；Story 5.5 完成公共任务最终 apply/回写；Release DoD 在目标环境收集生产持续时长与独立运行证据。这四项继续 `runtimeEvidenceClaim=none`。
- **证据边界**：本地 fixture、PostgreSQL task/outbox/receipt 行、transport `confirmed`、deployment profile、两次 clean replay 或 UI 投影只证明实现与制品可复现，不构成目标平台受理、质量修复、恢复完成、下游业务 apply、生产部署或“熔断后新正式线索数=0”的运行声明。
