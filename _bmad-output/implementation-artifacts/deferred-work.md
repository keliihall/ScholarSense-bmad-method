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
