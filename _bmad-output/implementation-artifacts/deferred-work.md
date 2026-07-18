# Deferred Work

## Story 1.1a 收敛/关闭审计（2026-07-18）

### DEFER-1：原型生产构建主 chunk 超过 Vite 默认提示阈值

- **证据**：`npm run build -- --outDir /tmp/scholarsense-story-1-1a-build-20260718-closure` 退出码 0；主 JavaScript chunk 为 1,810.51 kB（gzip 592.49 kB），Vite 提示超过 500 kB。
- **分类理由**：这是现有 Vite 5 / Vue 3 原型的性能提示，不导致构建或测试失败；Story 1.1a 只审计资产与供应链候选，明确不得实施生产前端、架构升级或性能优化。
- **后续 owner**：Story 1.1c（生产前端组合与性能 profile ADR）；按批准的 profile 决定是否拆包、调整 chunk 策略或接受阈值。
- **关闭影响**：不阻断 Story 1.1a；不得据此声称生产性能或制品提升已通过。
- **Story 1.1c 处置（2026-07-19）**：已由 `FPB-1.0.0` 批准可机器检查的入口/异步 chunk raw+gzip 预算、测量口径、按组件/路由拆包策略和超限非零退出规则。原型数字仅保留为风险输入；本处置不表示完整业务页容量或 1.1d 制品提升已通过。
