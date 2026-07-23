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

### DEFER-3：生产归档与跨域销毁证明保持不可达

- **证据**：capability manifest 固定 `productionArchiveEnabled=false`、`archiveAdapter=unbound`、`deletionReceiptRuntimeIssuable=false`；本 Story 仅以 test-only immutable fixture adapter 验收。
- **后续 owner**：基础设施 owner 绑定经批准的独立 object-lock/WORM adapter；Story 6.6 在所有消费者、读模型、对象、索引、缓存和 35 日备份水位确认后签发真实跨域 `DeletionReceipt`。
- **关闭影响**：不阻断 audit-domain / non-production `RetentionExecution`；不得声明生产 WORM、legal hold 或销毁回执已经运行。
