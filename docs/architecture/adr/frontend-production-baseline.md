# ADR：FrontendProductionBaselineVersion FPB-1.0.0

- Status: approved
- Owner: frontend-performance-owner
- Approved by: Hei
- Effective at: 2026-07-19T00:00:00+08:00
- Authority: AUTH-2026-07-17-001 / PAB-1.0.0 / UXB-1.0.0
- Production root: `frontend/`
- Registry: `https://registry.npmjs.org/`

## 决策

生产前端采用 Vite/Vue/TypeScript 单 SPA，base 固定为 `/scholarsense/`。所有 npm/Node 命令必须经 `_bmad/scripts/with_pab_toolchain.sh` 运行；Node `24.18.0`、npm `11.16.0` 分别由工具链入口、`engines`、`packageManager` 与环境 manifest 固定，不作为 npm 依赖。

唯一生产 lock 是 `frontend/package-lock.json`。依赖声明禁止范围、tag 和隐式全局工具；lock 中 npm 包必须来自本 ADR 批准 registry 并包含 integrity。`.npmrc` 仅允许 `registry`、`package-lock`、`save-exact`、`audit`、`fund`，凭据只能由执行环境注入。

## PAB 直接项

| npm 名称 | 精确值 | 用途 |
|---|---:|---|
| `vite` | `8.1.5` | Rolldown 生产构建与开发服务器 |
| `vue` | `3.5.40` | UI 运行时与 SFC |
| `typescript` | `npm:@typescript/typescript6@6.0.2` | 唯一 TypeScript 6 编译器 alias |
| `vue-tsc` | `3.3.7` | Vue SFC 类型检查 |
| `vue-router` | `4.6.4` | SPA 路由 |
| `pinia` | `3.0.4` | 当前进程易失 UI 状态 |
| `element-plus` | `2.14.3` | Web 组件基线，按组件导入 |
| `@tanstack/vue-query` | `5.101.2` | 仅服务端状态的内存 query cache |
| `echarts` | `6.1.0` | 图表核心，按能力注册 |
| `vue-echarts` | `8.0.1` | Vue/ECharts 适配 |
| `vitest` | `4.1.10` | 单元测试 |
| `@playwright/test` | `1.61.1` | 固定 baseline 浏览器测试入口 |
| `axe-core` | `4.12.1` | baseline 无障碍自动检查 |

## Companion 暂准入（安全发布批准归 Story 1.1d）

| npm 名称 | 精确版本 | 来源与 license | 用途与兼容证据 | 结论 |
|---|---:|---|---|---|
| `@vitejs/plugin-vue` | `6.0.8` | npm 官方包，MIT | Vue SFC 编译；peer 声明支持 Vite 8 与 Vue 3.2.25+，本组合执行 typecheck/test/build | provisional-for-build；Story 1.1d security gate pending |
| `@types/node` | `24.13.3` | DefinitelyTyped npm 包，MIT | 为 Vite/Vitest/Playwright 配置提供 Node 24 类型；不改变运行时 | provisional-for-build；Story 1.1d security gate pending |

上述两项只因精确来源、用途、license 标识与兼容性重放满足本 Story 的本地构建条件而暂准进入 lock；本 ADR 不作漏洞、安全或发布准入批准。Story 1.1d 必须完成安装脚本、SBOM、漏洞/许可证策略及供应链门后才能给出最终安全发布结论；若拒绝或替换任一 companion，必须发布新的 FPB/manifest 版本并重跑清洁验证。

`axios`、`@element-plus/icons-vue`、`prettier` 及原型其他直接项均为 rejected：当前启动面不需要，且不得因原型曾安装而进入生产 lock。TypeScript 只能解析为 `node_modules/typescript` 下的 `@typescript/typescript6@6.0.2`。`@typescript/typescript6@6.0.2` 自带的 `@typescript/old` fallback 原始范围会漂移到后续 6.x，因此唯一批准 override 将其精确锁为 `npm:typescript@6.0.2`；任何其他 override、5.x/7.x 或非 6.0.2 compiler 均使基线失败。

vue-tsc 3.3.7 不能解析 `@typescript/typescript6` 发布包的 `require("@typescript/old/lib/tsc.js")` shim。`frontend/scripts/vue-tsc6.mjs` 因此先 fail-fast 核验 alias 与 fallback 都是 6.0.2，再调用 vue-tsc 公开 `run()` 并直指同一份 `@typescript/old/lib/tsc.js`。这是受控的组合适配，不是第二个编译器版本。

Element Plus 2.14.3 的全包声明及其 VueUse 传递声明在 TypeScript 6 下包含本启动面未使用组件的库类型误报；`tsconfig.app.json` 因此批准 `skipLibCheck: true`，但保持项目源码 `strict`、`isolatedModules`、`verbatimModuleSyntax` 和 vue-tsc SFC 检查。这个豁免仅跳过已锁定依赖的 `.d.ts` 内部检查，不跳过业务/启动源码类型检查；依赖升级时必须重新评估。

## DEFER-1 与构建预算

原型主 JavaScript `1,810.51 kB`（gzip `592.49 kB`）只作为风险输入，不是生产预算。生产启动面按 `vite build` 的 minified 输出测量，文件大小使用字节数；gzip 使用 Node `zlib.gzipSync(..., { level: 9 })`。预算固定为：

- 初始入口 JavaScript：每文件 raw `≤250000` bytes、gzip `≤90000` bytes；全部初始入口 raw 合计 `≤350000` bytes。
- 异步 JavaScript：每文件 raw `≤600000` bytes、gzip `≤200000` bytes。
- CSS：每文件 raw `≤120000` bytes、gzip `≤25000` bytes。

超限时 `frontend/scripts/check-build-budget.mjs` 非零退出；禁止仅调整 Vite warning threshold 消音。Router、Pinia、Vue Query 留在启动路径；Element Plus 与 ECharts 能力按组件/模块导入并由 Vite 拆包。后续业务页必须路由懒加载，图表与非首屏复杂控件不得回灌主入口。

## 状态、离线与宿主边界

Query key 只允许 `[domain, resource, params]`。Vue Query 只保存当前进程的服务端状态；Pinia 只保存易失 UI 草稿。业务对象、授权投影与草稿不得写入 localStorage、sessionStorage、IndexedDB、Cache Storage、Service Worker 或文件系统。

断网只显示不含对象信息的网络状态并禁止提交。登出、换号、刷新、WebView 销毁或宿主会话失效时清空 query 与草稿；恢复后重新认证、重算授权、刷新 `aggregateVersion`，由用户显式重试。该启动面不实现 SSO、深链、业务 API 或客户端授权。

## 升级、回退与漂移

依赖、toolchain、registry、lock、ADR、manifest、浏览器矩阵或预算任何变化都必须创建新版本并重跑两次清洁验证；不得原位覆盖旧证据。回退必须同时恢复 package、lock、ADR/manifest 引用和构建摘要。静态批准不代表 1.1d 的 SBOM、签名、漏洞/许可证或制品提升已经通过。
