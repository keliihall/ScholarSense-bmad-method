---
baseline_commit: NO_VCS
---

# Story 1.1c：批准生产前端与性能 Profile ADR

Status: done

> 本文已完成开发上下文，不表示浏览器、WebView、真机、完整容量压测、月度可用性或供应链证据已经通过。静态 Gate/ADR 批准与运行验证必须分开陈述。

## Story

作为前端与性能负责人，
我希望冻结可复现的生产前端组合和 `PerformanceProfileVersion`，
以便后续构建、宿主集成、浏览器/WebView 验收和性能证据都使用同一份精确、可机器校验、不会静默漂移的基线。

## 业务与交付价值

- 本 Story 是 Epic 1 的 enabler，直接承接 NFR-1/NFR-32 的 profile 基线，并为 NFR-17/30/31/33 与 AD-22/28 提供版本/环境 contributor；Web 门户与最终 Web/WCAG owner 仍是 1.2，移动 WebView 业务验收 owner 是 7.1/7.x，发布报告与供应链 owner 是 1.1d，FR-1 的最终 owner 仍是 1.2。
- 它承接 `1.1a 资产/原型边界 → 1.1b 生产工程骨架`，并为 1.1d 的 CI/供应链门、1.2 的门户应用壳、2.6a/2.6b 的遥测与运行面板、3.7 的工作台性能及 7.x 的移动验收提供统一基线。
- 交付不是“可运行的演示页”，而是精确 lock、可构建生产启动面、版本化 ADR/manifest、漂移门和可重放验证。

## 实施前置门槛

开始修改生产前端前，必须同时满足：

1. `sprint-status.yaml` 与 Story 文件中 1.1b 均为 `done`，且 `1-1b-verification.md` 的最新交接结论可读。
2. G-01/G-05 仍为 `approved-for-implementation`，`PAB/PP/AP/UXB-1.0.0` 与 `AUTH-2026-07-17-001` 仍有效；不得把其重新降级为“等外部审批”。
3. 所有 npm/Node 命令通过 `_bmad/scripts/with_pab_toolchain.sh` 执行，并 fail-fast 核对 Node `24.18.0` 与 npm `11.16.0`；不得使用默认 Node 23/npm 10 或 Node 26 Current 的结果冒充 PAB 证据。
4. `docs/input/原型/frontend/**` 继续只读；任何从原型采用的交互/结构都须逐项记录，不得复制原型 lock、`node_modules`、`dist`、mock 身份、客户端角色授权、本机端点或微前端/微服务假设。
5. 目标学校 App 实机不是本 Story 的隐式开工/本地验证依赖；但完成前必须从 App owner 获得并机器校验精确的目标 iOS/Android、WebView/WKWebView、宿主与设备基线。若实验室实机已可用，可执行 preflight 并如实记录；1.1d/7.x 的正式报告仍不得用桌面 Chromium 或本 Story 的 fixture 代替。

## Acceptance Criteria

### AC-1.1c-HAPPY：批准一致且可复现的前端/性能基线

**Given** PAB/PP/AP/UXB-1.0.0 已批准、1.1b 交接完成且环境 manifest 可建立  
**When** 前端与性能负责人评审 `frontend-production-baseline` 与 `PerformanceProfileVersion` ADR  
**Then** 冻结受支持依赖组合、生产 lock、浏览器/WebView、宿主、设备、视口、网络、冷热缓存、数据分布、场景窗口、采样点、时钟与失败归因  
**And** ADR、lock、manifest、验证脚本和证据记录的版本/摘要互相一致，不含“最新”“常用”“正常网络”等不可复现值。

### AC-1.1c-BASELINE-LOCK：精确依赖、批准额外项与生产 lock

**Given** PAB-1.0.0 已冻结前端主线  
**When** 生成并校验 `frontend/package.json` 与唯一 `package-lock.json`  
**Then** Node `24.18.0` 与 npm `11.16.0` 作为工具链约束，由 `_bmad/scripts/with_pab_toolchain.sh`、`packageManager: "npm@11.16.0"`、严格 `engines.node` 与环境 manifest 共同锁定，不得写入 `dependencies/devDependencies`；PAB npm 直接项精确锁定为 Vite `8.1.5`、Vue `3.5.40`、`@typescript/typescript6` `6.0.2`、vue-tsc `3.3.7`、Vue Router `4.6.4`、Pinia `3.0.4`、Element Plus `2.14.3`、TanStack Vue Query `5.101.2`、ECharts `6.1.0`、vue-echarts `8.0.1`、Vitest `4.1.10`、Playwright `1.61.1`、axe-core `4.12.1`；`engines`/`packageManager` 与依赖声明均不得使用 `^`/`~`/标签/范围  
**And** `@vitejs/plugin-vue`、`@types/node`等构建必需但 PAB 未列的 companion，必须在 ADR 中逐项记录精确版本、来源、用途、license 标识、兼容证据与 `provisional-for-build` 结论后才可暂准进入 lock；漏洞、安全及最终发布准入由 Story 1.1d 负责，在其门禁完成前不得宣称正式安全批准；`axios`、`@element-plus/icons-vue`、`prettier` 等原型额外项默认移除/拒绝，不得因原型已安装而自动批准  
**And** TypeScript 6 的安装表示法必须由 ADR 明确（例如可被 peer consumer 解析的精确 npm alias `npm:@typescript/typescript6@6.0.2`），并让 Vite、vue-tsc、Vitest 共用唯一 `6.0.2` 编译器；不得由 peer/transitive 依赖静默引入 `typescript` 5.x/7.x  
**And** ADR 关闭 `DEFER-1`：以原型主 JS `1,810.51 kB`（gzip `592.49 kB`）仅作风险输入，为生产启动面明确可机器校验的入口/异步 chunk 预算、测量口径、拆包策略和超限失败规则；不得通过单纯提高 Vite warning threshold 消音  
**And** 只允许唯一 `frontend/package-lock.json`，拒绝 `npm-shrinkwrap.json`、pnpm/yarn lock 或第二份 npm lock；如存在 `.npmrc`，只允许 ADR 批准的 registry、`package-lock=true` 等非秘密设置，拒绝 `_authToken`、`_password`、username、明文认证及用户主目录/cache/userconfig 路径，凭据只能由运行环境注入  
**And** 两个清洁目录使用同一受控 registry 与预热后离线 cache 执行 `npm ci`、typecheck、unit test 和 production build 均成功，依赖树、lock SHA-256 与规范化构建输出摘要相同；缺 lock、lock 漂移、范围版本、未批准 registry/override、离线缺包或隐式全局工具时必须失败。

### AC-1.1c-FRONTEND-COMPATIBILITY：真实生产启动面与既有边界兼容

**Given** `frontend/` 当前只有 1.1b 建立的无依赖边界骨架  
**When** 在不复制原型业务实现的前提下建立 Vite/Vue/TypeScript 生产启动面  
**Then** 存在可 typecheck/test/build/serve 的最小 Vue SPA，生产 base 为 `/scholarsense/` 且静态资源对宿主路径可恢复，不提前实现 1.2 的 SSO/深链业务  
**And** Vite 8/Rolldown、Vue SFC、TypeScript 6、vue-tsc、Router、Pinia、Element Plus、Vue Query、ECharts 的 import/build/runtime smoke 通过，TypeScript 6 弃用的 `moduleResolution=node`、`baseUrl` 等原型配置不得进入新基线  
**And** 保持 `app/domains/components/shared`、九个 domain 公开入口、client env allowlist、禁止持久业务缓存及原型防污染规则  
**And** query key 固定为 `[domain, resource, params]`；Vue Query 仅管理服务端状态，Pinia 仅保存当前进程易失 UI 状态；query cache 与草稿不得落入 localStorage、sessionStorage、IndexedDB、Cache Storage、Service Worker 或文件系统  
**And** 断网时只显示无对象信息的网络状态，不读取缓存业务对象、不提交命令；登出、换号、刷新、WebView 销毁或宿主会话失效时清空内存 query/草稿，恢复后必须重新认证、重算授权、拉取最新 `aggregateVersion` 并由用户显式重试；无权限字段不得进入 JSON、DOM、缓存或无障碍树  
**And** 现有 `PREMATURE_PRODUCTION_LOCK` 守卫被替换为“生产 lock 必须存在且符合批准 ADR”，对动态 import、环境变量多语法、跨 domain 内部访问和持久化绕过的 1.1b 回归全部保留。

### AC-1.1c-PROFILE-MANIFEST：可机器校验的 PP/AP/环境清单

**Given** PP-1.0.0、AP-1.0.0 与 AD-22 已批准  
**When** 生成版本化 ADR、JSON Schema 和受控 manifest  
**Then** PP 精确冻结 `50,000` 在校生、`5,000,000` 事件/日、`60` 日热窗（约 3 亿事件）、`1,000` 峰值并发活跃会话，以及 `15m ramp + 15m warm-up + 60m steady + 15m peak`  
**And** 角色组合为 `70% 辅导员 / 15% 学院 / 5% 协同 / 5% 校级领导 / 5% 治理与运维`，请求组合为 `35% 工作台/列表 / 25% 详情 / 15% 筛选/看板 / 10% 状态命令 / 10% 任务/工单 / 5% 报表/导出提交`  
**And** 生产同构数据分布、desktop `4-core/8GB`、mobile `4-core/4GB`、校园网 `20/10Mbps, RTT 50ms, loss 0.1%`、移动网 `10/5Mbps, RTT 100ms, loss 1%`、冷/热缓存各 `50%`、NTP 偏差 `≤100ms`、数据分布/场景窗口/采样点/失败样本策略均为 schema 必填  
**And** AP 引用 `Asia/Shanghai`、每日 `07:00—23:00`、校园有线/校园 Wi-Fi/外部移动网三探针；每个探针每 60 秒依次执行 `SSO 后首页 → 列表 → 详情 → 批准的安全幂等命令/read-back`，冻结每步成功判定与超时，任一步错误即该探针该分钟失败；至少 2/3 探针正确才算 good minute，月度 SLI=`good eligible minutes / eligible minutes`，采样缺口计 bad、计划维护/外部依赖仍计分母并单列、完整自然月目标 `≥99.9%`；本 Story 仅冻结契约/fixture，不伪造完整自然月证据。

### AC-1.1c-BROWSER-WEBVIEW：精确浏览器、宿主、设备与视口矩阵

**Given** NFR-17/30/31 要求可复现的 Web 与移动验收基线  
**When** 冻结测试环境 manifest  
**Then** 环境 manifest 用完整版本、OS、安装源与摘要冻结 Chrome/Edge 当前稳定 major 和前一 major，覆盖 `1440×900` 与 `1366×768`；确定性本地 baseline smoke 不得被描述为品牌浏览器或前一 major 的正式运行报告  
**And** 移动 manifest 冻结学校统一 App 完整宿主版本、iOS 版本 + WKWebView 引擎定位信息、Android 版本 + WebView 完整版本、目标设备型号/CPU/内存与 `375px` 视口预期；fixture 机器校验这些值，真实无横滚/业务真机验收仍归 7.1/7.x  
**And** 任一浏览器/WebView/宿主/设备/镜像 digest 变化都使旧证据失效并要求新 manifest/version，不得原位覆盖  
**And** 以 2026-07-19 官方发布快照作为候选 oracle：Chrome `150.0.7871.124/.125` 与前一 major 149，Edge `150.0.4078.65` 与 `149.0.4022.98`；实施时将批准的目标完整版本冻结进 manifest，实验室可用时另做 preflight，官网快照、channel 名或 preflight 都不是 1.1d 的正式发布报告。
**And** 最终可重放矩阵以 `macOS 26.5.2 build 25F84 arm64` 为精确目标，冻结 Chrome for Testing `150.0.7871.124`/`149.0.7827.155` mac-arm64 zip 与 Edge `150.0.4078.65`/`149.0.4022.98` universal pkg 的不可变下载 URL、镜像 SHA-256 和解包后 executable SHA-256；preflight 必须逐项匹配，不得用发布页摘要代替制品摘要。

### AC-1.1c-SLI-ATTRIBUTION：用户感知采样与失败归因契约

**Given** AD-22 禁止用网关响应时间代替用户感知 SLI  
**When** 定义前端性能采样原语和最小可执行 fixture  
**Then** `ui.content-ready` 只在必需数据、当前授权投影、关键控件和可访问名称完成时发出，骨架屏/空容器不算完成；工作台/列表/详情 P95 目标 `≤2s`  
**And** 筛选从用户操作到图表、等价表格和辅助技术反馈全部更新，P95 目标 `≤3s`；跨端从服务端 `committedAt` 到另一在线端应用同一或更高 `aggregateVersion` 并发出 `ui.state-observed`，P95 目标 `≤5s`  
**And** 诊断分段至少区分 host/navigation、gateway、query、serialization、network、parse、render/main-thread、accessibility-ready、cache-state，失败样本保留且标明归因，不从分母静默删除  
**And** 增加 p75 `LCP≤2.5s`、`INP≤200ms`、`CLS≤0.1` guardrail，并保证采样 payload 不含学生明文、证据正文、token、密钥或授权结果。

### AC-1.1c-UX-ACCESSIBILITY：版本化 UXB 契约与基线验证面

**Given** UXB-1.0.0 已批准且 `DESIGN.md`/`EXPERIENCE.md` 是受控双脊柱  
**When** 生产启动面和测试 manifest 引入主题、视口与无障碍基线  
**Then** Web 主色 `#AF251B`/hover `#C53227`/pressed `#A7180D` 与移动主色 `#D03D37` 分开 token，未批准蓝绿色/移动辅助色不进入语义色板；字体栈为 `PingFang SC, SF Pro, Source Han Sans SC, sans-serif`，采用 4px spacing、2/4/8px 圆角，校徽透明边界外暂留直径 1/4 净空且不裁切/重绘/加阴影  
**And** 断点/栅格为 `0—767 mobile/4 栏 / 768—1023 tablet/8 栏 / ≥1024 desktop/12 栏`，mobile gutter 为 10px/24px、主操作目标 `≥44×44 CSS px`，desktop 常规控件高 36px且独立图标/行内动作命中区 `≥40×40 CSS px`，`375px` 与 200% 缩放无关键内容丢失或页面级横滚；上述产品 token/假设不得冒充学校 VI 原文  
**And** baseline smoke 至少覆盖 axe、对比度、键盘/可见焦点、非颜色唯一编码、图表等价表格契约、live region、`prefers-reduced-motion` 和 200% 缩放；该 smoke 只批准前端基线兼容性，不得声称 1.2/3.x/7.x 未实现页面已通过最终 WCAG/视觉/真机验收。

### AC-1.1c-CROSSCUTTING：通用服务端安全语义不回归

**Given** 本 enabler 不新增业务聚合、生产审批 API 或数据库写路径  
**When** 执行完整回归与基线生成负例  
**Then** 1.1b 的 test-only 命令契约继续证明服务端授权拒绝、依赖/审计失败、同键重放、异哈希冲突、过期 `aggregateVersion` 和并发竞争语义均通过，失败时状态/副作用不变且含 `traceId`  
**And** 若实现写文件生成器，则同一输入必须幂等，并对异版本、并发写、未批准项或局部失败以非零退出且原子地不留半成品；若采用受控静态文件，则 checker 必须确定性验证 schema、版本、摘要和漂移，不要求额外自建发布 API/生成服务  
**And** 如实施者选择新建运行时审批/发布 API，则必须另外实现并测试服务端授权、原子审计、幂等和乐观并发；不得用前端隐藏或 no-op 成功代替。

### AC-1.1c-EVIDENCE-BOUNDARY：真实证据、漂移门与下游交接

**Given** 1.1d、1.2、2.6a/2.6b、3.7、7.x 分别拥有供应链、真实宿主、遥测、工作台与移动最终证据  
**When** 归档本 Story 验证记录并准备交接  
**Then** 记录实际工具/依赖/浏览器/WebView/宿主版本、命令、退出码、测试计数、lock/依赖树/构建摘要、设备/网络/缓存条件、失败样本和未决项  
**And** `scripts/verify.sh` 统一运行后端回归、现有 Python 守卫、`npm ci`、typecheck、unit、build、baseline browser/axe smoke、manifest/schema/漂移负例和污染扫描，并在结束时不把 `node_modules/dist/coverage/playwright-report` 交付进源树  
**And** 本 Story 不声称 SBOM、SLSA provenance、签名、漏洞/许可证门、CI 制品提升（1.1d）、门户/SSO 真实流（1.2）、完整自然月可用性（2.8a）、最终工作台容量（3.7）或移动业务真机（7.x）已通过。

## Tasks / Subtasks

- [x] Task 0：执行开工门槛与现状复核（AC: HAPPY）
  - [x] 核验 1.1b `done`、最新验证记录、G-01/G-05/PAB/PP/AP/UXB 批准状态与精确 Node/npm 工具链门。
  - [x] 重放 1.1b 前端结构/合同/污染守卫，记录当前文件和必须保留的行为。
  - [x] 从 Web/App owner 获取 Chrome/Edge 与学校 App iOS/Android WebView/WKWebView、宿主和目标设备完整支持版本，冻结进 manifest；实验室二进制/实机可用时做 preflight，不把缺少 1.1d/7.x 的正式运行报告设为本 Story 本地验证硬门。（用户 2026-07-19 明确批准学校 App owner 基线全部跳过/不适用；以受控 N/A 决策而非伪造版本记录）

- [x] Task 1：先建立失败的基线/lock/manifest 契约测试（AC: BASELINE-LOCK, PROFILE-MANIFEST, BROWSER-WEBVIEW, CROSSCUTTING）
  - [x] 为必需 PAB 版本、精确版本语法、未批准 companion、lock 缺失/漂移/多 lock、registry/override、TypeScript 编译器唯一性及 `.npmrc` 凭据/本机路径编写 RED 测试。
  - [x] 为 PP/AP/UXB/浏览器/WebView/宿主/设备/网络/缓存/数据/采样/归因的 schema 必填、枚举、精度、百分比总和与漂移负例编写 RED 测试；AP fixture 覆盖四步事务、逐步超时、任一步失败、2/3 和月度公式。
  - [x] 修改现有 `PREMATURE_PRODUCTION_LOCK` 测试为“批准 lock 必需”，先确认旧逻辑会对当前 Story 产物产生预期失败。

- [x] Task 2：批准生产前端 ADR 并生成精确 lock（AC: BASELINE-LOCK, CROSSCUTTING）
  - [x] 编写版本化 `frontend-production-baseline` ADR，列出 PAB 直接项、必需 companion 决议、原型额外项处置、升级/回退/漂移规则、owner、approvedBy、effectiveAt 和证据链。
  - [x] 对账 `deferred-work.md` 的 DEFER-1，为入口/异步 chunk 批准字节预算、测量口径、拆包/懒加载策略与 fail threshold；不得复制原型大 bundle 或只调高警告阈值。
  - [x] 在精确工具链下建立 `package.json`/唯一 `package-lock.json`，把 Node/npm 建模为工具链而非 npm 依赖，验证每个直接项无范围、唯一 TypeScript 6.0.2 alias 解析、integrity/registry 符合 ADR，并拒绝其他 lock。
  - [x] 使用两个清洁工作区运行安装/类型/单测/构建，比对依赖树、lock 与规范化输出摘要；测试结束后不留生成树。

- [x] Task 3：建立最小可构建的 Vue 生产启动面（AC: FRONTEND-COMPATIBILITY, UX-ACCESSIBILITY）
  - [x] 增加最小 `index.html`、Vue 入口/根组件、Vite/Vitest/TypeScript/Playwright 配置，base 受控为 `/scholarsense/`，不提前实现业务页、SSO 或假 API。
  - [x] 执行 Vite 8/Rolldown + Vue/Router/Pinia/Vue Query/Element Plus/ECharts 最小集成 smoke，确认 TypeScript 6 配置无弃用项或隐式旧编译器。
  - [x] 用无业务 API 的 fixture 固化 query key、内存状态所有权、断网禁读/禁写、登出/换号/刷新/WebView 销毁清理及恢复后重新认证/授权/版本刷新/显式重试契约；不得提前定义业务 OpenAPI path。
  - [x] 将 UXB 端别 token、字体、spacing、圆角、断点和 reduced-motion 契约放入受控扩展位，不复制 mockup 的固定 1400px/42px/非语义动作。

- [x] Task 4：实现 Performance Profile、Availability 与环境 manifest（AC: PROFILE-MANIFEST, BROWSER-WEBVIEW）
  - [x] 建立 `PP-1.0.0` ADR/schema/manifest，精确写入容量、窗口、角色/请求混合、数据分布、设备、网络、缓存、时钟、采样和归因。
  - [x] 建立 `AP-1.0.0` 参照 manifest，锁定业务窗、三探针、`SSO 后首页→列表→详情→安全幂等命令/read-back`、逐步判定/超时、采样/good-minute/`good eligible minutes / eligible minutes`/缺口规则，但不生成伪月度结果。
  - [x] 建立 Web 当前+前一 major 及目标 App iOS/Android WebView/宿主/设备的精确 manifest，保存捕获日期、安装源、完整版本和 digest，对任一“最新/current”占位符 fail。（App 部分依用户指令以可审计 `not-applicable` 决策取代版本伪造）

- [x] Task 5：建立性能采样原语与失败归因 fixture（AC: SLI-ATTRIBUTION）
  - [x] 定义 `ui.content-ready`、filter-ready 与 `ui.state-observed` 的必填字段、起止点、时钟/版本条件、重复采样语义和隐私 allowlist。
  - [x] 单测骨架屏不得提前发 ready、只有网关响应不算完成、旧 aggregateVersion 不发 observed、失败样本不丢弃、采样 payload 不含敏感值。
  - [x] 为 host/gateway/query/network/parse/render/a11y/cache 分段与 LCP/INP/CLS guardrail 提供可执行最小 fixture，不用静态壳跑分声称完整应用达标。

- [x] Task 6：执行确定性 baseline smoke 与可选环境 preflight（AC: BROWSER-WEBVIEW, UX-ACCESSIBILITY）
  - [x] 在固定的本地 Playwright baseline 二进制上对 1440×900/1366×768/375px 运行构建、路由、资源、base-path、console-error、端别 token、命中区与无横滚 fixture smoke；不得把结果命名为品牌/真机正式报告。
  - [x] 实验室精确 Chrome/Edge 当前+前一 major 或目标 iOS/Android 宿主 WebView 可用时，通过独立命令执行 preflight 并记录 actual binary/OS/宿主/设备；不可用时仍机器校验批准 manifest，正式发布/门户/移动业务报告分别交给 1.1d/1.2/7.x。（Chrome/Edge 当前及前一 major 四条受控制品 preflight 均通过；App 按用户批准不适用）
  - [x] 运行 axe/对比度/键盘/焦点/live-region/等价表格/reduced-motion/200% 缩放的基线 fixture 套件，将尚无业务页可验收的项明确交给后续 owner。

- [x] Task 7：执行完整回归、可复现重放与交接（AC: CROSSCUTTING, EVIDENCE-BOUNDARY 及前述全部 AC）
  - [x] 将确定性的前端 install/typecheck/unit/build/baseline browser/a11y 与 ADR/manifest/schema/漂移负例纳入 `scripts/bootstrap.sh`/`scripts/verify.sh`；品牌浏览器/WebView preflight 使用独立显式命令，不成为普通本地验证的隐式外部依赖。
  - [x] 重放服务端授权拒绝、依赖/审计失败、同键同/异 payload、过期 `aggregateVersion` 与并发竞争回归；静态文件 checker 验证幂等/漂移，如采用生成器再验证原子写与失败无半成品。（本 Story 未采用生成器）
  - [x] 扩展 `normalized_manifest.py` 及其回归，使 `.vue/.css/.html` 与前端配置/生产 lock 参与文本规范化和确定性清单，同时继续排除 `node_modules/dist/coverage/playwright-report/target`。
  - [x] 在两个清洁工作区以同一受控 registry 预热 cache 后离线重放完整套件，确认 lock/依赖树/规范化输出一致，且生成物未进入源树/清单。
  - [x] 生成 1.1c 验证记录，对账 1.1d/1.2/2.6a/2.6b/2.8a/3.7/7.x 下游 owner，不将未运行或未实现的证据写成通过。

### Review Findings

- [x] [Review][Patch] (High) 按用户决定由 Story 1.1c 冻结精确 Web 品牌矩阵 OS 与浏览器二进制/镜像 digest，并让 Gate 校验实际制品而非发布说明 URL 的自摘要 [`contracts/performance/test-environment-1.0.0.json:6`, `scripts/check_frontend_baseline.py:429`]
- [x] [Review][Patch] (High) 按用户决定把 companion 安全批准移交 Story 1.1d：修改冲突 AC/ADR，并将当前 `@vitejs/plugin-vue`、`@types/node` lock 状态标为 provisional，不能在 1.1d 完成前宣称正式安全批准 [`docs/architecture/adr/frontend-production-baseline.md:35`]
- [x] [Review][Patch] (High) 让声明的 JSON Schema 能接受并实际校验当前 Manifest；四份 schema 的 `additionalProperties: false` 会拒绝实例中的 `$schema`，现有 checker 从未执行 schema validation [`contracts/performance/frontend-baseline.schema.json:6`]
- [x] [Review][Patch] (High) 封闭同版本 Manifest 语义漂移；sampling 值、`stateBoundary` 和浏览器来源可在重算自摘要后保持同一版本并通过 Gate [`scripts/check_frontend_baseline.py:113`]
- [x] [Review][Patch] (High) 缺失 `resolved` 与 `integrity` 时必须失败；当前同时删除二者可绕过 registry/integrity 检查 [`scripts/check_frontend_baseline.py:209`]
- [x] [Review][Patch] (High) 把双清洁重放摘要比较变成统一 Gate 的硬断言；当前只运行一次并打印摘要 [`scripts/verify_frontend.sh:56`]
- [x] [Review][Patch] (Medium) 品牌预检必须从批准 Manifest 读取品牌/版本并构建当前源码；当前信任调用者的 `expectedVersion` 且直接预览既有 `dist` [`frontend/scripts/run-brand-preflight.mjs:8`]
- [x] [Review][Patch] (High) 遥测隐私门必须校验允许字段的类型、格式和值；当前敏感明文可藏在 `surface`/`traceId` 等允许字段中原样通过 [`frontend/src/app/performance/performance-events.ts:147`]
- [x] [Review][Patch] (Medium) 拒绝非有限 `observedAt`；当前 `NaN`/`Infinity` 可生成成功样本和非有限 duration [`frontend/src/app/performance/performance-events.ts:186`]
- [x] [Review][Patch] (High) `ui.state-observed` 必须证明另一在线端和服务端同步时钟；当前提交端自身即可生成成功样本 [`frontend/src/app/performance/performance-events.ts:72`]
- [x] [Review][Patch] (Medium) 恢复联网后必须在重认证、重授权、版本刷新和显式重试完成前继续禁止命令；当前 `online=true` 即返回 allowed [`frontend/src/app/state/volatile-client-state.ts:44`]
- [x] [Review][Patch] (High) 污染扫描必须覆盖本 Story 新增的 `.mjs`、`.html`、`.css` 等生产文本类型；当前这些文件会被静默跳过 [`scripts/check_production_pollution.py:19`]
- [x] [Review][Patch] (High) client env allowlist 必须 fail-closed 处理别名/动态访问；`const env = import.meta.env` 可绕过检测 [`scripts/check_frontend_structure.py:110`]
- [x] [Review][Patch] (Medium) 跨域守卫必须拒绝变量模板动态 import；Vite 可执行的 ``import(`../${target}/internal/secret`)`` 当前不产生 finding [`scripts/check_frontend_structure.py:47`]
- [x] [Review][Patch] (Medium) 用真实 200% zoom/text scaling 替换 320px 窄视口 proxy，覆盖固定高度裁切等缩放缺陷 [`frontend/tests/baseline/baseline.spec.ts:103`]
- [x] [Review][Patch][Follow-up] (High) 遥测清洗与 collector 使用单次 descriptor 快照、顶层字段 allowlist 及深冻结，拒绝 getter/Proxy 二次读取和记录后变异 [`frontend/src/app/performance/performance-events.ts`]
- [x] [Review][Patch][Follow-up] (Medium) readiness/online 运行时值必须是严格 boolean，不得用字符串 truthiness 生成成功样本 [`frontend/src/app/performance/performance-events.ts`]
- [x] [Review][Patch][Follow-up] (Medium) query key params 必须递归防御性复制/冻结并拒绝环、非有限数值和非普通对象 [`frontend/src/app/state/volatile-client-state.ts`]
- [x] [Review][Patch][Follow-up] (High) 联网恢复证明绑定 account/session/connection generation/command/version，生命周期撤销且一次性消费，禁止跨会话或旧版本复用 [`frontend/src/app/state/volatile-client-state.ts`]
- [x] [Review][Patch][Follow-up] (Medium) 品牌 preflight 在读取 manifest 前运行固定 checker，防止篡改 manifest 后重算自摘要绕过批准 oracle [`frontend/scripts/run-brand-preflight.mjs`]
- [x] [Review][Patch][Follow-up] (High) 精确白名单 root scripts、禁止 install lifecycle，并在每个 replay 上校验副本、批准 lock 及安装前/后/全套后的规范化源码摘要，阻断确定性双重篡改 [`scripts/verify_frontend.sh`]
- [x] [Review][Patch][Follow-up] (Medium) package、lock、schema 与 manifest 的所有层级拒绝重复 JSON key，消除 first-wins/last-wins parser differential [`scripts/check_frontend_baseline.py`]
- [x] [Review][Patch][Follow-up] (High) client env 词法守卫识别合法空白和 block/line comment trivia，拒绝 `import.meta /*...*/ . env` 等旁路 [`scripts/check_frontend_structure.py`]
- [x] [Review][Patch][Follow-up] (High) 品牌 preflight 对单次读取的 manifest 原始文件 SHA 与版本内容 SHA 双重固定，再运行 checker，关闭校验/读取间 TOCTOU [`frontend/scripts/run-brand-preflight.mjs`]
- [x] [Review][Patch][Follow-up] (Low) 规范化清单在检查 symlink 前先排除 `node_modules` 等生成目录，既允许正常 `.bin` 链接又继续拒绝源码树 symlink [`scripts/normalized_manifest.py`]

## Dev Notes

### 当前仓库与必须保留的行为

- 当前工作区不是 Git 仓库，不存在可验证的 commit/分支/远端历史；开发时应记录 `baseline_commit: NO_VCS`，不得伪造 Git/provenance 情报。
- `frontend/` 当前只有 1.1b 的所有权骨架：`src/app/{router,theme,config}`、9 个 kebab-case domain 公开 `index.ts`、`components`、`shared` 和 client env allowlist；没有 `package.json`、lock、Vue 入口、页面或 bundle。
- `scripts/check_frontend_structure.py` 会拒绝任何 lock，本 Story 必须更新该文件及 `scripts/tests/test_check_frontend_structure.py`，不能只新增 lock。
- `scripts/check_production_pollution.py` 会拒绝源树中的 `frontend/node_modules`/`dist`；`normalized_manifest.py` 排除生成物但应纳入生产 lock。安装/构建需在临时或可靠清理的工作区运行。
- `scripts/verify.sh` 当前只运行后端、Python 和静态前端守卫，本 Story 要将真实 npm/类型/单测/构建/基线浏览器验证纳入统一入口。
- 1.1b 累计修复的前端守卫绕过（动态 import、模板字面量/注释、环境变量点号/括号/解构/optional-chain、持久缓存）必须保留为回归套件。
- `deferred-work.md` 的 DEFER-1 记录原型主 JS chunk 为 `1,810.51 kB`、gzip `592.49 kB`，owner 正是 1.1c；该数字不是生产预算，必须在 ADR 中给出新启动面的批准预算与失败门，不能仅提高 Vite warning threshold。

### 技术基线与兼容性护栏

| 领域 | 批准基线/不变量 |
|---|---|
| 工具链 | Node 24.18.0 LTS / npm 11.16.0；必须通过 `_bmad/scripts/with_pab_toolchain.sh` |
| 构建/框架 | Vite 8.1.5 / Vue 3.5.40 / `@typescript/typescript6` 6.0.2 / vue-tsc 3.3.7 |
| 路由/状态/查询 | Vue Router 4.6.4 / Pinia 3.0.4 / TanStack Vue Query 5.101.2；Pinia 只存当前进程 UI 状态 |
| 组件/图表 | Element Plus 2.14.3 / ECharts 6.1.0 / vue-echarts 8.0.1 |
| 测试 | Vitest 4.1.10 / Playwright 1.61.1 / axe-core 4.12.1 |
| 宿主/base | 独立 SPA + host adapter；生产入口 `/scholarsense/`；同一 HTTPS SPA 供门户与 WebView |
| 离线/持久化 | localStorage、sessionStorage、IndexedDB、Cache Storage、Service Worker、持久 Pinia 均不得存业务对象/授权投影 |

- Vite 8 已用 Rolldown 替换旧 Rollup/esbuild 组合，必须验证原型 Vite 5 配置/插件的兼容假设，不得盲复制。Vite 8 官方 Node 最低要求为 20.19+ 或 22.12+，PAB Node 24.18.0 满足条件，但这不代替组合实测。
- TypeScript 6 将 `types` 默认为空、弃用 `moduleResolution=node` 和 `baseUrl`；Vite 应用应采用可证明的 `moduleResolution=bundler`/显式 types/path 方案。PAB 使用 `@typescript/typescript6` 而官方通用安装名为 `typescript`，必须冻结并测试 peer-consumer 映射，不得静默替换基线。
- Playwright 每个版本绑定特定浏览器二进制，并支持 `chrome`/`msedge` 品牌 channel；channel 名本身不是可复现版本，前一 major 需独立受控二进制/镜像。Playwright 镜像版本必须与测试包匹配并以 digest 固定。

### 预期文件范围

下列为建议落点；开发时如现状变化，必须先完整读取再决定 NEW/UPDATE：

**UPDATE（必须完整读取并保留现有行为）**

- `frontend/README.md`：从“无依赖骨架”交接为生产基线用法。
- `frontend/src/app/router/index.ts`、`frontend/src/app/theme/index.ts`、`frontend/src/app/config/index.ts`：保留集中扩展位，只增加启动面需要的最小集成。
- `scripts/check_frontend_structure.py`、`scripts/tests/test_check_frontend_structure.py`：将 premature lock 逻辑升级为批准 lock 守卫，保留所有边界/污染负例。
- `scripts/check_production_pollution.py` 及其测试：把无后缀 `.npmrc` 纳入扫描，拒绝认证字段、明文凭据和用户主目录/cache/userconfig 路径，只允许 ADR 批准的非秘密 npm 设置。
- `scripts/bootstrap.sh`、`scripts/verify.sh`：纳入前端真实安装/类型/单测/构建/基线验证，不删除既有后端/Python/污染守卫。
- `scripts/normalized_manifest.py`、`scripts/tests/test_delivery_quality.py`：让 `.vue/.css/.html` 与前端配置/生产 lock 参与规范化摘要，继续拒绝符号链接逃逸并排除生成物。
- `README.md`、`.gitignore`：更新产品前端用法和生成物边界，确保生产 lock 被跟踪、`node_modules/dist/coverage/playwright-report` 不被跟踪。

**NEW（候选）**

- `frontend/package.json`、`frontend/package-lock.json`、`frontend/.npmrc`、`frontend/index.html`、`frontend/tsconfig*.json`、`frontend/vite.config.ts`、`frontend/vitest.config.ts`、`frontend/playwright.config.ts`。
- `frontend/src/main.ts`、`frontend/src/App.vue`、`frontend/src/app/performance/**`、`frontend/tests/{unit,baseline}/**`。
- `docs/architecture/adr/frontend-production-baseline.md`、`docs/architecture/adr/performance-profile-pp-1.0.0.md`（若仓库最终选择其他 ADR 目录，需在 README/manifest 中唯一声明）。
- `contracts/performance/frontend-baseline.schema.json`、`contracts/performance/performance-profile.schema.json`、`contracts/performance/test-environment.schema.json` 及对应版本化 manifest。
- `scripts/check_frontend_baseline.py`、`scripts/tests/test_check_frontend_baseline.py`（或对现有守卫做职责清晰的等价扩展）。
- `_bmad-output/implementation-artifacts/1-1c-verification.md`（仅在真实执行后记录证据）。

### 测试要求

- **单元**：基线/lock/manifest 解析器、百分比/数值/枚举不变量、版本漂移、TypeScript 唯一性、query key/内存状态/生命周期清理、采样原语、ready/observed 边界、隐私 allowlist。
- **集成**：精确工具链 `npm ci → typecheck → unit → build`，Vite/Vue/Router/Pinia/Vue Query/Element Plus/ECharts 最小组合，base path/静态资源，现有结构/污染/合同守卫。
- **端到端**：固定 Playwright baseline 二进制上的 1440×900/1366×768/375px、console/network/resource/base-path、键盘/焦点/axe/对比度/200%/reduced-motion 为本地必跑；精确 Chrome/Edge 当前+前一 major、目标 iOS/Android WebView 通过独立 preflight 入口运行并交给 1.1d/1.2/7.x 形成正式报告。
- **可复现性**：两个清洁工作区使用同一受控输入；比较依赖树、lock SHA-256、规范化 bundle 摘要和验证结果；离线/cache 重放的先决条件必须记录。
- **负例**：范围版本、缺 lock、未批准依赖/registry/override、双 TypeScript、“最新”环境值、浏览器/WebView/宿主/profile 漂移、百分比不为 100%、采样缺口被删除、骨架屏提前 ready、敏感遥测、断网读取/提交、生命周期后残留内存、无权限字段进入 DOM/无障碍树、原型/持久缓存/跨 domain 绕过。
- **完整回归**：后端、1.1a 审计、1.1b Python/架构/迁移/合同/双角色 smoke 与前端新套件全部通过，才可勾选 Task。

### 前一 Story 情报

- 1.1b 已完成生产工程骨架并在最终回归中记录后端 36/36、1.1a 审计 145/145、Python 38/38 通过，两次清洁验证的 172 文件摘要相同。
- 早期曾因 1.1a 未完成和工具链不符两次 HALT，后通过显式 PAB 工具链入口解除；1.1c 必须复用该模式，不得用本机默认版本。
- 1.1b 前端与污染守卫经过多轮对抗审查；本 Story 修改 lock/构建逻辑时不得回退这些防绕过能力。
- 1.1b 故事正文仍有少量历史状态句（称 1.1a in-progress 或自身 review）；当前权威状态以 Story 头部与 `sprint-status.yaml` 的 `done` 为准。

### 最新技术信息（核验日期：2026-07-19）

- Node.js 官方将 `24.18.0` 标为 Krypton LTS，发布包携带 npm `11.16.0`；与 PAB 一致。
- Vite `8.1.5`、Vue `3.5.40`、Vitest `4.1.10`、Playwright `1.61.1` 均可在官方项目/npm 注册元数据中确认；实施仍以 PAB 精确值而非 latest tag 为 oracle。
- `@vitejs/plugin-vue` `6.0.8` 是核验日的官方 Vue SFC 插件候选，但不在 PAB 列表中；必须由本 Story ADR 显式批准和兼容测试后才能进入生产 lock，不可仅因“官方插件”就自动接纳。
- Chrome/Edge 官方发布快照支持将 major 150/149 作为当前/前一 major 候选；实验室必须另存实际完整版本和二进制/镜像 digest。Android WebView 可独立更新，iOS WKWebView 随 OS/WebKit；因此二者都不能只记“与 Chrome 一致”或“iOS WebView”。

### Project Structure Notes

- 生产前端继续遵守与后端领域对齐的 `frontend/src/domains/<kebab-case>`，跨域只从 `index.ts` 导入；Vue 组件 PascalCase。
- `src/app` 拥有组合根、router、theme、config 和全局 provider；`components` 无业务所有权；`shared` 只容纳技术中立原语。
- 业务查询/草稿只存当前进程内存；不得建 Service Worker 或持久业务缓存。
- ADR 与 performance contracts 必须有唯一目录/版本命名和 schema 权威源；后续 Story 只引用，不各自复制一份 profile。

### Project Context Reference

- 仓库中未发现 `project-context.md`；本 Story 的实施规则来自受控 PRD/Architecture/UX/Epics、1.1a/1.1b 交接，以及当前代码和守卫。若开发前新增 `project-context.md`，实施者必须先完整读取并对冲突做显式对账，不得静默覆盖本文基线。

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story-1.1c批准生产前端与性能-Profile-ADR`]
- [Source: `_bmad-output/planning-artifacts/epics.md#NFR-覆盖矩阵`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#91-性能与容量`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#95-可访问性与体验一致性`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#97-首期验收基线表`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-17--跨端共享服务端状态`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-22--ADOPTED-质量容量与时延验收包络`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-26--客户端持久化与离线边界`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-28--生产制品与供应链基线`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#生产技术基线-PAB-100`]
- [Source: `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md#9-PABPPAP-100`]
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md`]
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md`]
- [Source: `_bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md`]
- [Source: `_bmad-output/implementation-artifacts/1-1b-建立模块化生产工程骨架.md#File-List`]
- [Source: `_bmad-output/implementation-artifacts/1-1b-verification.md#后续-Story-交接矩阵`]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md#DEFER-1原型生产构建主-chunk-超过-Vite-默认提示阈值`]
- [Node.js 24.18.0 LTS](https://nodejs.org/en/blog/release/v24.18.0)
- [Vite 8 发布与 Node 支持](https://vite.dev/blog/announcing-vite8)
- [Vite 支持版本](https://vite.dev/releases)
- [TypeScript 6.0 发布及破坏性变化](https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/)
- [Playwright 浏览器与品牌 channel](https://playwright.dev/docs/browsers)
- [Playwright 镜像版本匹配](https://playwright.dev/docs/docker)
- [Chrome 2026 发布记录](https://chromereleases.googleblog.com/2026/)
- [Microsoft Edge Stable 发布记录](https://learn.microsoft.com/zh-cn/deployedge/microsoft-edge-relnote-stable-channel)
- [Android WebView 版本特性](https://developer.chrome.com/docs/webview)
- [Apple WKWebView](https://developer.apple.com/documentation/webkit/wkwebview)

## Dev Agent Record

### Agent Model Used

Codex (GPT-5)

### Debug Log References

- 2026-07-19T00:51:38+08:00：开工基线记录为 `NO_VCS`，Story 与 sprint 状态已转为 `in-progress`。
- 2026-07-19：Task 0 前两项已通过；1.1b Story 与 sprint 均为 `done`，最新验证记录可读，G-01/G-05 为 `approved-for-implementation`，PAB/PP/AP/UXB 已关闭批准。
- 2026-07-19：精确工具链检查通过：JDK 25.0.3、Node 24.18.0、npm 11.16.0、Maven 3.9.16；前端/交付守卫 20/20 通过，`frontend-structure`、`contract-seeds`、`production-pollution` 独立检查均 PASS。
- 2026-07-19：官方 Web 快照已核验 Chrome 150.0.7871.124/.125、Chrome 149.0.7827.196/.197、Edge 150.0.4078.65、Edge 149.0.4022.98；仓库中仍无学校 App owner 批准的完整 iOS/Android 宿主、WebView/WKWebView 与目标设备基线，Task 0 第三项保持未完成。
- 2026-07-19：本地实验室 preflight 只发现 Chrome 150.0.7871.115 与 Edge 150.0.4078.65；未发现 Xcode/Simulator 设备或 Android `adb`，且本地 Chrome 不等于批准候选快照，故不记为正式或目标 App 运行证据。
- 2026-07-19：用户明确批准“学校 App owner 基线：全部跳过，不适用”。后续环境 manifest 必须以可机器校验的 `not-applicable` 状态和本决策日期记录，不得填造 App/WebView/WKWebView/设备版本，不得声称真机验收通过。
- 2026-07-19：GREEN 首轮发现 vue-tsc 3.3.7 无法识别 `@typescript/typescript6` 的转发 shim；已以 fail-fast 适配器直指精确锁定的 `@typescript/old@6.0.2` 编译核心。Element Plus 未使用组件的声明内部 TS6 误报以受控 `skipLibCheck` 隔离，项目源码仍执行 strict vue-tsc。
- 2026-07-19：Playwright baseline 首轮 16 通过、3 失败、2 跳过，失败均为 Element Plus info alert 对比度 2.8:1；修正受控主题 token 后为 19 通过、2 个按 viewport 设计跳过。
- 2026-07-19：Edge 150.0.4078.65 品牌预检通过；Chrome 本机 150.0.7871.115 与批准候选 150.0.7871.125 不同，独立预检以 `BRAND_VERSION_DRIFT` 正确失败，未冒充目标版本证据。固定 Chromium baseline 与本机 Edge/Chrome 二进制 digest 已捕获供最终验证记录使用。
- 2026-07-19：统一 `verify.sh` 通过：后端 36/36、1.1a 审计 145/145、Python 56/56、前端单测 18/18、Playwright/axe 19 通过且 2 个设计跳过；静态守卫、类型、构建和预算全绿。
- 2026-07-19：两个独立清洁前端工作区离线重放一致：lock=`92a4bd...b667`、依赖树=`0fc4e2...50e2`、构建=`8bd0b6...9f8c`；212 文件规范化清单摘要均为 `919c9e...e52`，源树未留下前端生成目录。
- 2026-07-19：代码审查后统一 `verify.sh` 通过：后端 36/36、审计 145/145、Python 72/72；双清洁重放每次前端单测 27/27、Playwright/axe 20 通过/4 跳过，replay 源码=`f0beb98a...534c`、lock=`92a4bd...b667`、依赖树=`0fc4e2...50e2`、构建=`8bd0b6...9f8c` 硬比较一致。
- 2026-07-19：Chrome for Testing 150.0.7871.124/149.0.7827.155 与 Edge 150.0.4078.65/149.0.4022.98 四条批准记录均在目标 OS 上完成 artifact SHA、executable SHA、完整版本、品牌和当前源码清洁构建 preflight，分类保持 `optional-brand-preflight-not-formal-report`。

### Implementation Plan

- 以 Python 标准库守卫作为 ADR/lock/schema/manifest 的独立 fail-closed 门，先建立必败回归，再引入受控文件。
- 在精确 PAB 工具链下建立最小 Vite/Vue 启动面，前端业务状态仅存内存，性能采样使用显式 allowlist。
- 以固定 Playwright baseline 运行浏览器/无障碍 fixture，与品牌浏览器、门户、真机和正式发布证据严格分界。
- 将所有本地确定性检查纳入 `scripts/verify.sh`，并在两个清洁工作区中对比 lock、依赖树和规范化构建摘要。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- 2026-07-19：已合并 Epics/PRD/Architecture/UX、1.1a/1.1b 交接、现有代码/守卫与官方版本核验；本记录仅表示 Story 开发上下文已就绪。
- 2026-07-19：独立 checklist 复核后的 4 项 High 与 4 项 Medium 已全部关闭；无剩余 Critical/High/Medium。
- Task 0 已完成：开工/工具链/批准状态与 1.1b 守卫重放通过；Web 候选版本已核验，学校 App owner 基线按用户明确决定记为不适用。
- Task 1 已完成：baseline/lock/schema/manifest 守卫实际执行 Draft 2020-12 schema，并固定 schema 与同版本 manifest 语义摘要；缺 `resolved`/`integrity`、重算自摘要漂移和浏览器制品篡改负例均通过。完整当前回归为后端 36/36、1.1a 145/145、Python 72/72。
- Task 2 已完成：FPB-1.0.0 冻结直接项、暂准构建 companion、TypeScript alias/override、registry/lock 与 chunk 预算；companion 最终安全发布批准明确移交 1.1d。两次离线清洁重放由统一脚本硬门执行并要求摘要一致。
- Task 3 已完成：最小 Vue SPA、`/scholarsense/` base、PAB 组合、易失状态/生命周期清理与 UXB token 已实现；11/11 前端单测、strict 源码类型检查、Vite 构建和预算门通过，入口 raw 165557 bytes，图表异步 chunk raw 497640 bytes。
- Task 4 已完成：PP/AP/环境与内容摘要全部由实际 schema/checker 锁定；Web 精确冻结目标 OS、Chrome 150/149 与 Edge 150/149 的不可变 URL、artifact/executable SHA，App 按用户批准以可机器校验 N/A 记录，未生成月度或真机伪证据。
- Task 5 已完成：ready/filter/observed 原语、九段归因、失败保留、重复幂等/冲突、严格隐私 allowlist、有限时间、跨 client/服务端时钟与 Web Vitals 单样本 guardrail 已实现；collector/getter/Proxy 与恢复凭证旁路负例均纳入 27/27 前端单测。
- Task 6 已完成：固定 Playwright Chromium 在 1440×900、1366×768、375px 上执行 24 项，20 通过、4 项按 project 设计跳过；覆盖真实 CSS 200% zoom 与独立 320px reflow。四条 Chrome/Edge 当前及前一 major 制品 preflight 全部通过，学校 App 基线按用户批准保持 N/A。
- Task 7 已完成：统一验证入口覆盖后端、审计、72 项 Python 契约与两次隔离前端离线全套；脚本硬比较两个清洁工作区的 replay 源码、lock、依赖树与构建摘要并一致。验证记录只陈述实际执行结果，并将 CI/供应链、门户正式报告、真实遥测/性能与学校 App N/A 边界交给对应下游 owner。

### File List

- `_bmad-output/implementation-artifacts/1-1c-批准生产前端与性能-profile-adr.md`（更新：基线、Task 0、调试记录与实施计划）
- `_bmad-output/implementation-artifacts/sprint-status.yaml`（更新：Story 审查完成并同步为 `done`）
- `scripts/check_frontend_baseline.py`（新增：ADR/lock/schema/manifest 独立漂移守卫）
- `scripts/tests/test_check_frontend_baseline.py`（新增：基线、lock、PP/AP/环境与 N/A 决策正负契约）
- `scripts/check_frontend_structure.py`、`scripts/tests/test_check_frontend_structure.py`（更新：生产 lock 必需且替代 lock 必败）
- `frontend/package.json`、`frontend/package-lock.json`、`frontend/.npmrc`（新增：精确 PAB 依赖、唯一 TypeScript 6.0.2 与 npm 生产 lock）
- `docs/architecture/adr/frontend-production-baseline.md`、`docs/architecture/adr/performance-profile-pp-1.0.0.md`（新增：FPB/PP/AP 批准决策、预算与证据边界）
- `contracts/performance/*.schema.json`、`contracts/performance/*-1.0.0.json`（新增：前端基线、PP、AP 与测试环境受控契约）
- `_bmad-output/implementation-artifacts/deferred-work.md`（更新：DEFER-1 预算与失败门处置）
- `frontend/index.html`、`frontend/src/main.ts`、`frontend/src/App.vue`（新增：生产 SPA 入口与最小启动面）
- `frontend/{tsconfig.json,tsconfig.app.json,tsconfig.node.json,vite.config.ts,vitest.config.ts,playwright.config.ts}`（新增：TypeScript 6/Vite/Vitest/Playwright 受控配置）
- `frontend/scripts/{vue-tsc6.mjs,check-build-budget.mjs}`（新增：TypeScript alias 适配与构建预算门）
- `frontend/src/app/router/index.ts`、`frontend/src/app/config/index.ts`、`frontend/src/app/theme/{index.ts,tokens.ts,styles.css}`（更新/新增：路由、base 与 UXB 契约）
- `frontend/src/app/state/*.ts`、`frontend/src/app/views/*.vue`（新增：内存状态、断网/恢复契约与无业务 fixture）
- `frontend/tests/unit/*.test.ts`（新增：依赖、状态与 UXB 单测）
- `scripts/check_production_pollution.py`、`scripts/tests/test_delivery_quality.py`（更新：只允许固定 Playwright loopback，并扫描 `.npmrc` 凭据）
- `frontend/src/app/performance/performance-events.ts`、`frontend/tests/unit/performance-events.test.ts`（新增：用户感知采样、归因、幂等与隐私契约）
- `frontend/tests/baseline/baseline.spec.ts`（新增：三 viewport、资源/路由、交互、axe 与 reflow 确定性基线）
- `frontend/scripts/run-brand-preflight.mjs`（新增：显式品牌浏览器精确版本预检，不进入普通 baseline）
- `frontend/src/app/theme/styles.css`、`frontend/index.html`（更新：修复 info 状态对比度并以内联 favicon 消除品牌预检 404）
- `scripts/verify_frontend.sh`（新增：精确工具链下的隔离预热与离线前端完整验证）
- `scripts/bootstrap.sh`、`scripts/verify.sh`（更新：接入前端基线守卫、缓存预热及离线全套）
- `scripts/normalized_manifest.py`、`scripts/tests/test_delivery_quality.py`（更新：纳入前端文本/配置/lock 并排除测试生成物）
- `README.md`、`frontend/README.md`、`.gitignore`（更新：生产前端用法、证据边界与生成物规则）
- `_bmad-output/implementation-artifacts/1-1c-verification.md`（新增：真实回归、可复现摘要、品牌漂移、App N/A 与下游交接记录）

## Change Log

- 2026-07-19：创建 Story 1.1c 完整开发上下文，状态设为 `ready-for-dev`。
- 2026-07-19：开始 Story 1.1c 实施并转为 `in-progress`；完成 Task 0–6，学校 App owner 基线按用户决定记为不适用。
- 2026-07-19：完成 Task 7、全量回归与双清洁离线重放，Story 转为 `review`。
- 2026-07-19：完成代码审查并应用全部 15 项原始补丁与 10 项跟进加固；全量回归、双清洁重放和四条品牌制品 preflight 全部通过，Story 转为 `done`，companion 安全发布批准保留给 Story 1.1d。
- 2026-07-19：代码审查按用户决定将 companion 安全发布批准移交 Story 1.1d；当前两项仅为 `provisional-for-build`。
