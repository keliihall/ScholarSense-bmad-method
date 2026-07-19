# ScholarSense 生产工程骨架

本仓库交付 Java 25 / Spring Boot 4.1.0 后端和 Story 1.1c 批准的 Vue 3 / Vite 8 生产前端基线，包括九个业务模块边界、公共 envelope、环境配置契约、精确 npm lock、性能 profile 与确定性浏览器/无障碍 fixture。

## 快速验证

在仓库根目录执行：

```bash
./scripts/bootstrap.sh
./scripts/verify.sh
```

`bootstrap.sh` 验证固定工具链、Maven Wrapper、结构/配置/前端基线，并在隔离临时目录预热精确 npm lock 和 Playwright Chromium 缓存；它不安装全局 npm 包，也不复制原型依赖。`verify.sh` 先运行非递归 `verify-core`，清理后端生成物并完成后端、Python、前端离线回归，再在两个 clean Git root 中重放生产构建并比较最终制品和 BuildManifest 摘要。lock、依赖树、源码或构建摘要任一不一致都会失败；本地重放输出位于临时目录并在退出时删除。

## 发布与证据边界

本地 `verify.sh` 不签名、不生成受信 provenance，也不提升制品。受信发布只能从受保护 `main` 手动启动 `protected-release` 工作流；它按不可变 URI 顺序生成候选制品、SBOM/扫描、artifact attestation/签名、精确 macOS 正式 Web 证据、ReleaseManifest、manifest 外置签名和 EvidenceIndex，经独立复验后才允许 protected environment 审批与 digest-only 提升。回退使用独立 `protected-rollback` 工作流重新验证历史 ledger 指向的既有 digest，不重建或改写历史。

正式 Web 门直接验证候选 store 中冻结的前端归档；源码预览、普通 hosted runner 或本地品牌预检均不能替代其证据。动态发布输出只存在于 GHCR、GitHub attestation/ledger 或忽略的 `release-out/`，不得提交到源码树。

## 生产边界

- `backend/`：固定 Maven Wrapper 的模块化单体入口；业务模块只通过 `.api` 跨模块依赖。
- `frontend/`：Story 1.1c 批准的精确 Vue/Vite 生产启动面；`package-lock.json` 是受控生产输入，业务对象和授权投影只允许进程内存。
- `contracts/`：非密运行配置、OpenAPI 错误 envelope 与 CloudEvents envelope 种子，不含业务操作。
- `deploy/base/`：同一后端 JAR 的 `web-api` / `worker` 角色与探针种子，不是生产编排。
- `scripts/`：标准库结构、合同、污染与可重复验证工具。

`docs/input/原型/frontend/**` 始终是只读迁移输入，不是生产依赖。

## 已知交接边界

- Story 1.2：门户 SSO、真实业务应用壳与 Web/WCAG 正式验收。
- Story 7.x：移动宿主业务验收；学校 App owner 基线已由用户在 1.1c 明确批准为不适用。

本骨架的静态 Gate 与 smoke 结果不代表这些后续证据已经通过。
