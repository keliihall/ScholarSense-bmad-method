# ScholarSense 生产工程骨架

本仓库交付 Java 25 / Spring Boot 4.1.0 后端和 Story 1.1c 批准的 Vue 3 / Vite 8 生产前端基线，包括九个业务模块边界、公共 envelope、环境配置契约、精确 npm lock、性能 profile 与确定性浏览器/无障碍 fixture。

## 快速验证

在仓库根目录执行：

```bash
./scripts/bootstrap.sh
./scripts/verify.sh
```

`bootstrap.sh` 验证固定工具链、Maven Wrapper、结构/配置/前端基线，并在隔离临时目录预热精确 npm lock 和 Playwright Chromium 缓存；它不安装全局 npm 包，也不复制原型依赖。`verify.sh` 会清理后端生成物，运行全部后端与 Python 回归，再在两个新的隔离目录分别离线执行前端安装、类型检查、单测、构建预算、三 viewport 浏览器和 axe 无障碍基线；lock、依赖树或构建摘要任一不一致都会失败。临时 `node_modules`、`dist` 与测试报告不会进入源树。

## 生产边界

- `backend/`：固定 Maven Wrapper 的模块化单体入口；业务模块只通过 `.api` 跨模块依赖。
- `frontend/`：Story 1.1c 批准的精确 Vue/Vite 生产启动面；`package-lock.json` 是受控生产输入，业务对象和授权投影只允许进程内存。
- `contracts/`：非密运行配置、OpenAPI 错误 envelope 与 CloudEvents envelope 种子，不含业务操作。
- `deploy/base/`：同一后端 JAR 的 `web-api` / `worker` 角色与探针种子，不是生产编排。
- `scripts/`：标准库结构、合同、污染与可重复验证工具。

`docs/input/原型/frontend/**` 始终是只读迁移输入，不是生产依赖。

## 已知交接边界

- Story 1.1d：Git 来源、CI、SBOM、provenance、签名、漏洞/许可证运行证据和制品提升。
- Story 1.2：门户 SSO、真实业务应用壳与 Web/WCAG 正式验收。
- Story 7.x：移动宿主业务验收；学校 App owner 基线已由用户在 1.1c 明确批准为不适用。

本骨架的静态 Gate 与 smoke 结果不代表这些后续证据已经通过。
