# Story 1.1c 验证记录

- 执行时间：2026-07-19T03:17:30+08:00
- Story 基线：`NO_VCS`（当前工作区不是 Git 仓库）
- 结论：代码审查补丁、统一回归、双清洁离线重放与四条受控 Web 品牌制品 preflight 均通过；学校 App owner 基线按用户批准为 `not-applicable`。品牌 preflight 仍分类为 `optional-brand-preflight-not-formal-report`，本记录不构成门户业务、真机/WebView、容量压测、月度可用性或发布供应链正式报告。

## 固定工具链与执行环境

| 项目 | 实际值 |
|---|---|
| OS | macOS 26.5.2 build 25F84, arm64 |
| Java | 25.0.3 |
| Maven Wrapper | 3.9.16 |
| Node | 24.18.0 |
| npm | 11.16.0 |
| npm registry | `https://registry.npmjs.org/` |
| Playwright | 1.61.1 |
| 固定本地 baseline 浏览器 | Chromium 149.0.7827.55 |
| baseline executable SHA-256 | `11e393326c7d20a7c56641a7c65def33ea9c280da3b0b74cf8563b07989a0ee3` |

所有正式 npm/Node 证据均通过 `_bmad/scripts/with_pab_toolchain.sh` 运行；本机默认 Node 23/npm 10 的输出未用于验收。

## 统一回归结果

命令：`./scripts/verify.sh`

| 层级 | 结果 |
|---|---|
| 后端 Maven/架构/迁移/授权拒绝/幂等/并发/双角色 smoke | 36/36 通过 |
| 1.1a 审计回归 | 145/145 通过 |
| Python 标准库、契约、schema、漂移、污染与清单回归 | 72/72 通过 |
| 前端单元测试 | 27/27 通过（4 个测试文件；每次重放） |
| TypeScript/Vue 类型检查 | 通过；精确 TypeScript 6.0.2 |
| Vite 生产构建 | 通过；入口 raw 165557 bytes，预算门通过 |
| Playwright/axe baseline | 20 通过，4 个按 project 设计跳过；0 失败（每次重放） |
| 静态守卫 | `frontend-structure`、`frontend-baseline`、`contract-seeds`、`production-pollution` 全部 PASS |

浏览器 baseline 覆盖 1440×900、1366×768、375×812 的 `/scholarsense/` base path、路由/资源、console/network error、端别 token、命中区、横向 reflow、键盘、焦点、live region、非颜色状态、图表等价表格、axe/对比度、reduced motion、真实 CSS `zoom: 2` 及独立 320px reflow 边界。它只使用无业务 API fixture，不声称真实门户或业务页已验收。

规范化源/配置/lock 清单为 212 个文件，SHA-256=`e77beda80745237f9171f735a50e7d8cfe136a4ff8096e82b65c485c1a43761e`；纳入 `.vue/.css/.html/.ts/.mjs/.npmrc/package-lock.json`，排除 `node_modules/dist/coverage/playwright-report/test-results/target`。

## 双清洁离线重放硬门

`scripts/verify_frontend.sh --offline` 在同一次命令中创建两个独立工作区，分别执行 `npm ci --offline → schema/manifest gate → typecheck → unit → build → Playwright/axe`，随后用 `cmp` 比较摘要；任何差异以 `FRONTEND_REPRODUCIBILITY_DRIFT` 非零退出。本次两次摘要完全相同：

| 摘要 | 重放 1 | 重放 2 |
|---|---|---|
| replay 源码 SHA-256 | `f0beb98a3e61a3d172f86a76b288ff16b51ba848d870227e8c17f0054c8e534c` | 相同 |
| `package-lock.json` SHA-256 | `92a4bd6376de161f75dd48179742135b1f32651a63c4c92c81b4d8a5ede5b667` | 相同 |
| `npm ls --all --json` SHA-256 | `0fc4e2aef3fa7ae56d9f9372bbb40d4495b5f48e6a241f420fcb1a10a7d550e2` | 相同 |
| 规范化构建树 SHA-256 | `8bd0b6a065dcb863b7f1db58a41aecba32e1d43f8e0f8b284d2232b7b9469f8c` | 相同 |
| 前端单测 | 27/27 | 27/27 |
| Playwright/axe | 20 通过、4 跳过 | 20 通过、4 跳过 |

root `package.json.scripts` 由 checker 精确白名单，`npm ci` 显式使用 `--ignore-scripts`；每个 replay 在安装前、安装后和完整套件后比较源码摘要，并对副本运行 baseline/structure gate、绑定批准 lock。验证结束后 `frontend/` 未留下 `node_modules/dist/coverage/playwright-report/test-results`。依赖安装脚本、companion 安全批准、SBOM、漏洞/许可证、provenance、签名与制品提升均继续由 Story 1.1d 的发布门负责，当前 lock 只作 `provisional-for-build`。

## 独立品牌制品 preflight

环境 manifest `TEST-ENV-1.0.0` 内容摘要为 `1361a3f0fdeb6e747c5c1f6bfc682863d2a96240cd2136ec23c2370e52593fbb`，原始文件 SHA-256 为 `d2ff1b070eea3bb33dab665e3e9cf28f7e2253adf6116d4929553783a7242196`。每次 preflight 对同一次读取固定这两个摘要，再运行版本 checker，随后校验目标 OS、不可变下载 URL、实际 artifact SHA、实际 executable SHA、完整版本与品牌，删除旧 `dist`、从当前源码重建，并在 1440×900 执行状态/标题/console/network smoke。四条记录均在 `macOS 26.5.2 build 25F84 arm64` 通过：

| 品牌记录 | artifact SHA-256 | executable SHA-256 | 结果 |
|---|---|---|---|
| Chrome current `150.0.7871.124` mac-arm64 zip | `36c8b5fe04c08a418a172206bb392600ec1550941bde6af2d4353df21db87a47` | `22ddf33cec88bbfd181588eb3da31250a65ba8ebfdb6efcd2694a36275697284` | PASS |
| Chrome previous `149.0.7827.155` mac-arm64 zip | `135b697c49a375025ba6540a9d963d803d0b80b01f497c77ef5fd8296e4f36c7` | `e9c22e6eb15fc062f58202f8fbebbe1e6e2d30211a9d4739a5593e986e7bf01d` | PASS |
| Edge current `150.0.4078.65` universal pkg | `68929c051651b056123369874fe5f6bea0a268500e6c506f6922b2d539a2fd86` | `d82cb159d44fecd4e7263b7d20b55e9ca46f0c18485eb0bcdf63b635bd9664bb` | PASS |
| Edge previous `149.0.4022.98` universal pkg | `0165f110a529d2ed8ce98ed82ef4b19c39ae6b0485b88ccd5797e710f6b9b9d5` | `e7da6f1bf1824324bcdd44ad75f87fc40d02bd848a10b5020bd52518133648af` | PASS |

Chrome 制品来自 [Chrome for Testing known-good versions](https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json)，Edge 制品来自 [Microsoft Edge Enterprise update API](https://edgeupdates.microsoft.com/api/products?view=enterprise)；完整不可变 URL 固定在环境 manifest。上述结果是受控制品 preflight，不是 1.1d 的发布矩阵报告或 1.2 的门户业务验收。本机另有未批准的普通 Chrome `150.0.7871.115`，它仍不得代替 manifest 记录。

学校 App owner 基线依用户 2026-07-19 的明确决定全部跳过、不适用。环境 manifest 以 `applicability: not-applicable`、决策 ID `USER-2026-07-19-SCHOOL-APP-NA` 和 `runtimeEvidenceClaim: none` 记录；没有伪造 iOS/Android、WKWebView/WebView、宿主或设备版本，也没有声称真机通过。

## 代码审查 RED→GREEN

- 四份 manifest 现由其 Draft 2020-12 schema 实际验证；`$schema` 为必填，schema 与同版本 manifest 语义摘要均固定，重算实例自摘要不能隐藏 sampling、状态边界或浏览器来源漂移。
- package、lock、schema 与 manifest 的所有层级都拒绝重复 JSON key，避免不同解析器对同一字节流产生 first-wins/last-wins 差异。
- lock 每个非根包都必须同时具有批准 registry 的 `resolved` 与非空 `integrity`；同时删除二者的负例已转绿。
- telemetry 严格校验字段类型/格式/敏感值、有限时间、不同 client、服务端同步时钟和最大 100ms 偏差；collector 采用单次 descriptor 快照并拒绝未知字段，避免 getter/Proxy TOCTOU 和记录后变异。
- query key 参数递归复制/冻结；联网恢复证明绑定 account/session/connection generation/command/version，生命周期撤销且仅可显式重试一次。
- 污染扫描覆盖 `.mjs/.cjs/.mts/.cts/.html/.css`；环境变量别名/展开/动态键、合法空白/comment trivia 和非静态 dynamic import 均 fail-closed。
- PP/AP schema、公式和 fixture 已验证，但没有生成容量达标结果或月度可用性数字。

## 下游交接

| Story/owner | 仍需负责的正式证据 |
|---|---|
| 1.1d | CI、正式品牌矩阵、companion/安装脚本安全批准、SBOM、漏洞/许可证、provenance、签名与发布制品提升 |
| 1.2 | 门户 SSO、真实应用壳、Chrome/Edge 业务与最终 Web/WCAG 报告 |
| 2.6a / 2.6b | 真实全链路遥测、运行面板、告警和隐私负例 |
| 2.8a | 业务运行证据消费与后续验收边界，引用 PP/AP 版本而非复制 profile |
| 3.7 | 工作台真实数据、容量与用户感知性能验证 |
| 7.x | 移动业务交付边界；当前学校 App owner 基线为 N/A，不产生真机验收任务或通过声明 |
