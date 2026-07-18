# Reviewer Gate：技术版本与现实依据

## 复审 Verdict（2026-07-16）

**FAIL：仍有 1 项 High。** 上一轮的主要整改基本完成：冷启动 seed 已与架构不变量分层；Vite 5 已明确禁止提升为生产基线；OpenAPI 已更新到 3.1.2；前端家族不再在 seed 表中锁生产 patch；query-state 具体库已 Deferred；官方核验日期、来源和实施前复核门槛均已补齐。

唯一残余 High 是 Deferred 中仍写“**首版沿用 lock 中 ECharts 5.6.0**”。这再次从原型 lockfile 推导了一个生产精确版本，与同文“前端不固定生产版本”“原型快照不得直接提升为生产基线”冲突。删除该生产承诺或改为“ECharts 5/6 与匹配 vue-echarts 版本在生产化验证后选择”，即可通过本镜头。

本轮只核验并报告，不修改 `ARCHITECTURE-SPINE.md`。

## 复审整改核验

| 整改项 | 结果 | 复审说明 |
| --- | --- | --- |
| 冷启动 seed 分层 | PASS | 标题已改为“冷启动技术 Seed”，明确不是架构不变量，并要求实施前通过校方平台、驱动、漏洞、浏览器/WebView 与构建工具矩阵。 |
| 移除 Vite 5 生产固定 | PASS | Vite 5 只保留为原型快照，并明确已退出支持线、禁止直接提升为生产基线；这与 [Vite 当前支持线](https://vite.dev/releases) 一致。 |
| OpenAPI 3.1.2 | PASS | 已采用当前 3.1 修订，官方来源指向 [OAS 3.1.2](https://spec.openapis.org/oas/v3.1.2.html)。 |
| 前端家族不锁生产 patch | **PARTIAL / HIGH** | seed 表已移除前端 patch，但 Deferred 又固定“首版沿用 ECharts 5.6.0”，仍有一处从原型锁生产 patch。 |
| query-state 库 Deferred | PASS | AD-17 固定缓存键、失效、重试、并发行为，具体库 Deferred；Deferred 表也给出了选择与验证重访条件。 |
| 官方核验日期/来源 | PASS | 已记录 2026-07-16 及 OpenJDK、Spring、PostgreSQL、OAI、Vite 官方一手来源，并要求实施前重新核验。 |

### RV-1（高）：ECharts 仍从原型 lockfile 被固定为“首版”生产版本

- **位置：** Deferred → `ECharts 6.x 升级`：`首版沿用 lock 中 ECharts 5.6.0`。
- **冲突：** “冷启动技术 Seed”正文明确“前端只绑定 AD-17 的技术家族，不在本 spine 固定生产版本”，且“现有原型快照”只证明原型事实；Deferred 的“首版沿用”却把 ECharts 5.6.0 提升成了生产选择。
- **风险：** 该措辞绕过了同表要求的受支持版本、漏洞、WebView、视觉和无障碍回归，也锁定了必须与 ECharts 版本配套选择的 vue-echarts 代际。
- **修复：** 改为“ECharts 与 vue-echarts 作为配套版本族，在生产化时选择仍受支持且通过视觉/无障碍/WebView 回归的组合；原型 5.6.0/7.0.3 仅作迁移输入”。若确需首版采用 5.6.0/7.0.3，则必须把它记录为显式生产决定并附生产化验证证据，不能仅以 lockfile 为依据。

## 残余 Critical / High

- Critical：0
- High：1（RV-1）

---

## 初审记录（修复前，保留用于追溯）

## 发现

### V-1（高）：`固定版本栈` 将原型 lockfile 误塑造成生产决定

- **证据：** Spine 表格固定 Vue 3.5.38、TypeScript 5.9.3、Vite 5.4.21、Vue Router 4.6.4、Pinia 2.3.1、Element Plus 2.14.2、ECharts 5.6.0、vue-echarts 7.0.3；紧随其后的正文又声明它们是 `[ASSUMPTION]` seed，且“只证明原型可解析，不代表生产选型已被上游采纳”。本地 `package-lock.json` 确实逐项锁定这些版本，但 PRD 和现实对账均明确原型不是生产工程或生产选型证明。
- **问题：** “固定”与“未采纳、可替换”是两个相反的契约信号。下游若只读取表格，会把原型快照当成必须复刻的生产矩阵。
- **要求：** 将生产已选版本、候选 seed、原型观察值分层；至少把前端精确 patch 版本移出“固定”语义。若决定沿用，应以独立的生产化验证（受支持的 Node 版本、构建、测试、漏洞、浏览器/WebView 兼容、视觉与无障碍回归）将其提升为正式决定。

### V-2（高）：Vite 5.4.21 已不在官方支持版本范围

- **证据：** Vite 官方当前只对 `8.1` 发常规补丁，并向 `7.3` 和 `8.0` 回移重要修复与安全补丁；Vite 5 不在支持集合中。[Vite Releases](https://vite.dev/releases)
- **影响：** 即使原型当前能构建，把 Vite 5 固定为新生产基线会承担无上游安全修复的风险。它也会连带约束 Node 与 Vue 插件版本。
- **要求：** 生产化时选择官方受支持的 Vite 线并验证 `@vitejs/plugin-vue`、Node、TypeScript、Vue 和校方 WebView；或者明确 Vite 5 仅为“原型重现版本”，不得进入生产版本基线。

### V-3（中）：OpenAPI 3.1.1 有效但不是当前 3.1 修订版，且不宜无验证跳到 3.2

- **证据：** OAI 当前列出 3.2.0、3.1.2、3.1.1 等版本，并说明同一 minor 内 patch 不改变 feature set、工具应兼容整个 3.1.x；3.1.2 是当前 3.1 patch，3.2.0 是最新 minor。[OAS 版本索引](https://spec.openapis.org/oas/)；[OAS 3.2.0](https://spec.openapis.org/oas/v3.2.0.html)
- **判断：** 3.1.1 不是错误，也不需要仅为“最新”升级到 3.2；真正应绑定的是经过代码生成器、校验器、文档与网关验证的 `3.1` feature line。若必须精确 pin，应采用 3.1.2 并验证完整工具链。
- **要求：** 将表中 `3.1.1` 改为“OpenAPI 3.1（schema 使用当前 3.1 修订）”，或明确记录为什么工具链必须锁 3.1.1。

### V-4（中）：前端组合内部相容，但整体并非当前生产基线

- **本地证据：** lockfile 和已安装包声明显示：Vue Router 4.6.4 要求 Vue `^3.5.0`；Pinia 2.3.1 要求 Vue `^3.5.11`；Element Plus 2.14.2 要求 Vue `^3.3.7`；vue-echarts 7.0.3 要求 ECharts `^5.5.1` 及 Vue `^3.1.1`。当前锁定的 Vue 3.5.38、ECharts 5.6.0 满足这些范围。代码也实际使用 Vue、Router、Pinia、Element Plus、ECharts 与 vue-echarts，故它们不是“仅安装未使用”。
- **当前性：** 官方仓库当前稳定发布已至少包括 Vue 3.5.40、Element Plus 2.14.3；Vue Router 已提供 5 的迁移路径；Pinia 已发布 4；TypeScript 已发布 6.0；ECharts 6 与 vue-echarts 8 已形成新组合。参见 [Vue releases](https://github.com/vuejs/core/releases)、[Element Plus releases](https://github.com/element-plus/element-plus/releases)、[Vue Router 5 迁移](https://router.vuejs.org/guide/migration/v4-to-v5)、[Pinia releases](https://github.com/vuejs/pinia/releases)、[TypeScript 6.0](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-6-0.html)、[vue-echarts 8 migration](https://github.com/ecomfe/vue-echarts#migration-to-v8)。
- **判断：** 不应机械追新到所有最新 major；应先选一组仍受支持、迁移风险可控的生产矩阵。ECharts 5.6 + vue-echarts 7 可作为短期保守组合，但需单独确认安全维护与 WebView/browser 支持；ECharts 6 + vue-echarts 8 必须成对验证，Spine 当前对此判断正确。

### V-5（中）：AD-17 固定“查询缓存管理服务端状态”，但版本表与现有原型均没有对应实现

- **证据：** 原型依赖中没有 TanStack Query、SWRV 或等价查询缓存库；Pinia 只证明原型使用了客户端 store。Spine 把“服务端状态由查询缓存管理”设为强制规则，却没有给出受现实支持的 starter/库或自行实现契约。
- **影响：** 下游可各自选择不兼容缓存键、失效、重试和并发策略；也可能误以为原型 Pinia 已承担该能力。
- **要求：** 要么选择并验证一个 Vue 3 兼容的查询缓存实现并纳入生产 seed，要么将 AD-17 降到技术无关的缓存行为契约并把具体库 Deferred。不得从原型依赖推导此能力已存在。

### V-6（低）：Java 25 + Spring Boot 4.1.0 基础兼容成立，但完整生产兼容矩阵尚未成立

- **证据：** JDK 25 于 2025-09-16 GA，OpenJDK 页面说明其为多数发行商的 LTS；Spring Boot 4.1.0 官方系统要求为 Java 17–26，故 Java 25 在框架支持范围内。[OpenJDK 25](https://openjdk.org/projects/jdk/25/)；[Spring Boot 4.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- **边界：** Spring 官方同时提示第三方项目可能有额外/更高要求。当前仓库没有可见后端 starter、构建文件、JDBC 驱动、迁移工具或校方 JDK 镜像证据，因此只能证明“基础可行”，不能证明校方生产平台、驱动与全套依赖已兼容。
- **要求：** 保留 `[ASSUMPTION]` 与实施前兼容矩阵门槛；不要把此组合标成既有现实或已验证 starter。

### V-7（低）：PostgreSQL 18.4 是当前 18 主版本的现行 minor，选择本身成立

- **证据：** PostgreSQL 18.4 于 2026-05-14 发布；官方版本策略把 18.4 列为 18 的当前 minor，并建议始终运行所选 major 的当前 minor。[PostgreSQL 18.4 release notes](https://www.postgresql.org/docs/release/18.4/)；[PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/)
- **边界：** 这仍不能证明校方托管数据库已提供 18，或 HA、备份、扩展、驱动、迁移工具与监控已经适配。
- **要求：** 继续作为候选生产 seed 并由校方平台兼容矩阵确认；固定 major 后，生产补丁策略应跟随 18.x 当前 minor，而不是永久锁死 18.4。

## 逐项结论

| 项目 | 版本真实性/当前性 | 相容性 | 证据等级与结论 |
| --- | --- | --- | --- |
| Java 25 LTS | 当前可用 LTS | Boot 4.1 官方支持 | 可作候选 seed；校方 JDK 发行版待核验 |
| Spring Boot 4.1.0 | 当前稳定版之一 | Java 25 基础兼容 | 可作候选 seed；第三方生态/平台未验证 |
| PostgreSQL 18.4 | 18 当前 minor | 与 Boot 的实际驱动栈未验证 | 可作候选 seed；minor 应滚动维护 |
| Vue 3.5.38 | lockfile 真实，落后当前 patch | 本地组合满足 peer range | 原型现实，不等于生产决定 |
| TypeScript 5.9.3 | lockfile 真实，6.0 已发布 | 原型能解析 | 可保守沿用但须生产化验证 |
| Vite 5.4.21 | lockfile 真实，已退出官方支持线 | 原型能解析 | 不应作为新生产固定基线 |
| Vue Router 4.6.4 | lockfile 真实，5 已发布 | 要求 Vue ^3.5，满足 | 原型现实；生产升级与否需决策 |
| Pinia 2.3.1 | lockfile 真实，已有 4 | 要求 Vue ^3.5.11，满足 | 原型现实；只应用于会话 UI 状态是新的架构决定 |
| Element Plus 2.14.2 | lockfile 真实，当前 patch 为 2.14.3 | 要求 Vue ^3.3.7，满足 | 小 patch 可更新，但仍需视觉/a11y 回归 |
| ECharts 5.6.0 | lockfile 真实，已有 6 | vue-echarts 7 要求 ^5.5.1，满足 | 保守组合可行；6/8 应成对迁移 |
| vue-echarts 7.0.3 | lockfile 真实，已有 8 | 与 Vue 3.5/ECharts 5.6 相容 | 原型现实；不得单独升 8 而不升 ECharts 6 |
| OpenAPI 3.1.1 | 有效但非当前 3.1 patch | 工具链未给出 | 建议绑定 3.1 feature line/3.1.2，不盲升 3.2 |

## 门禁结论

Finalize 前至少应闭合 V-1、V-2：消除“固定生产栈”与“原型/assumption seed”的矛盾，并移除 Vite 5 作为新生产固定基线。V-3 与 V-5 应同时澄清，否则 API 工具链和前端服务端状态策略仍可能由下游各自猜测。其余项可保留为有明确重访条件的 seed。
