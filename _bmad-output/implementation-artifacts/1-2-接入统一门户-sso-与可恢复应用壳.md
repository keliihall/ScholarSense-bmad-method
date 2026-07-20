---
baseline_commit: a0c8a9cba10d963c41623d27a8480dbbbddea393
---

# Story 1.2：接入统一门户 SSO 与可恢复应用壳

Status: done

> 证据边界：HIP/ISP/RFP/UXB/PAB-1.0.0 和 G-02/G-05 已批准，因此可以立即实施；批准不等于宿主、SSO、深链、浏览器、视觉或无障碍运行证据已经通过。仓库当前没有真实门户/IdP 沙箱端点或凭据配置。可以先完成契约、适配器和本地可重放负例，但真实沙箱证据齐备前不得进入 `review`/`done`，不得用 mock IdP、同源测试页或现有启动面报告冒充端到端通过。

## Story

作为授权用户，
我希望从统一门户进入并在重认证后返回原目标，
以便在不重复输入平台密码、不丢失任务上下文的前提下安全进入学林知微。

## 业务与交付价值

- 将 1.1c/1.1d 的可复现前端启动面升级为真实统一门户入口和学校统一身份会话，不建设第二套账号密码体系。
- 使会话续期、过期、登出、换号、深链恢复和宿主降级成为可预测、可审计、可重放验收的契约。
- 为 1.6a–1.6c 的权威身份/责任关系同步、1.7 的角色化首页与 7.x 的统一 App WebView 提供稳定宿主和会话边界。
- 以真实门户、IdP 沙箱、冻结浏览器、品牌与 WCAG 证据完成 FR-1 的端到端交付。

## Story 元数据

| 字段 | 值 |
|---|---|
| Epic / Story | Epic 1 / Story 1.2 |
| Story Key | `1-2-接入统一门户-sso-与可恢复应用壳` |
| 类型 | integration |
| 直接依赖 | Story 1.1d（已 `done`） |
| 估算 | 5d |
| Ready When | HIP/ISP/RFP/UXB/PAB-1.0.0 已批准；真实宿主、SSO、深链和浏览器证据由本 Story 交付 |

## 实施前置与范围边界

1. Story 1.1d 已为 `done`。实施必须复用当前 PAB/FPB/CISB/TEST-ENV/ReleaseManifest 证据链，不得绕过 `scripts/verify.sh`、供应链 lock、正式 Web 证据入口或 digest-only promotion。
2. 本 Story 是 FR-1 最终 owner，覆盖 NFR-17/19/20/30，并按 NFR 追踪矩阵承担 1.2 所属的 NFR-22 Web 键盘、焦点、等价呈现与 live-region 验收。
3. 不提前实现 1.6a–1.6c 的权威组织/责任关系同步、1.7 的完整七角色菜单与对象授权、1.8 的字段投影或 1.9 的公共待办能力；只交付可供这些后续能力复用的安全壳、会话与深链契约。
4. 不宣称 1.3–1.5 的集中不可变审计链已完成。本 Story 只为登录、续期、登出、换号、深链拒绝和宿主消息拒绝生成最小本地审计事实/端口，保留后续归集边界。
5. 移动 WebView、native bridge、Android Verified App Links、iOS Universal Links 和真机最终证据属于 7.1/7.x。不得把 `AAB-1.0.0` 的窄域 N/A 写成移动端通过。
6. `BaselineHomeView`/`BaselineCompatibilityView` 只是前端组合 fixture，可继续用于回归，但不能成为门户/SSO 业务验收或角色化首页。

## Acceptance Criteria

### AC-1.2-HAPPY：原始上游验收

**Given** HIP/ISP/RFP/UXB/PAB-1.0.0 已批准且目标沙箱可连接  
**When** 授权用户执行本 Story 的主流程  
**Then** 认证、续期、登出、换号、深链恢复和宿主降级均按契约验收且不重复索要密码  
**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

### AC-1.2-PORTAL-SSO：门户会话复用与 BFF 代码流

**Given** 用户在学校 IdP 已有有效会话，门户以 HIP-1.0.0 允许的跨源 iframe 打开 `/scholarsense/`  
**When** 应用以 OIDC Authorization Code + PKCE S256/BFF 开始认证  
**Then** 用户无需再次输入学林知微账号密码；服务端校验 `state`、`nonce`、issuer、audience、签名、`exp/nbf/iat` 和 60 秒时钟偏差后建立当前身份上下文  
**And** authorization code 只允许短暂出现在 BFF callback URI 并被立即单次消费，不得进入 SPA route/history/referrer、host message、DOM、前端缓存或日志；access/refresh/ID token、CAS ticket 和授权决定不得进入任何前端可见位置  
**And** 浏览器仅持有名称以 `__Host-` 开头、Secure、HttpOnly、SameSite=Lax、Path=/ 且无 Domain 的会话 cookie  
**And** 壳入口必须先取得服务端认证事实；客户端路由守卫只协调体验，不作为授权证据。

### AC-1.2-SESSION-LIFECYCLE：续期、登出、换号与并发收敛

**Given** ISP-1.0.0 冻结 access token 5 分钟、会话空闲 15 分钟、绝对 8 小时、到期前 5 分钟提醒和 refresh token 单次轮换  
**When** 发生续期、续期重放、空闲/绝对过期、登出、换号、IdP 失效或两个并发会话命令  
**Then** refresh token 仅服务端保存并每次轮换，重用时撤销整个 token family；会话使用单调 `sessionVersion`，旧版或并发输家返回稳定 409 且不得复活旧 token  
**And** 登出清除 BFF 会话和全部易失客户端状态，调用 IdP end-session/revocation，并只允许预登记的 `post_logout_redirect_uri`；换号必须完整登出后重新认证  
**And** 同一登出/换号 `Idempotency-Key` 和同请求摘要重放原结果，同键异请求返回 409，不重复登出、导航或写审计事实。

### AC-1.2-DEEP-LINK：安全深链与重认证回原目标

**Given** 用户从门户携带 opaque `workItemId`/route state 进入，或在受保护路由中会话失效  
**When** 应用保存续访目标、进入统一认证并处理回调  
**Then** 续访目标以服务端一次性、限时、绑定浏览器会话/origin 的 opaque continuation 保存；URL 不含 token、学生标识、敏感字段、绝对 URL、协议相对 URL或开放重定向输入  
**And** 本 Story 以自身拥有的无业务数据受保护目标 `shell.session` 证明“原目标 → 重认证 → 原目标”成功路径；认证成功后先重建当前会话，再由服务端解析 allowlisted route identifier，最后才导航，重复回调或重放不产生第二次导航或副作用  
**And** 1.7/1.9 尚未交付的角色首页或业务 `workItemId` 只能作为 opaque context 保留并安全拒绝，不能映射到 mock 业务页或宣称业务深链已完成；目标已失效、越界、未实现或不存在时使用相同外部拒绝语义，不泄露对象存在性，只呈现安全壳的唯一恢复动作。

### AC-1.2-HOST-CONTRACT：跨源宿主消息契约

**Given** PC 门户以精确 `frame-ancestors` allowlist 的跨源 iframe 嵌入同一 HTTPS SPA  
**When** 宿主与应用交换 `host.ready`、`auth.changed`、`navigate.requested`、`logout.requested`、`theme.changed`  
**Then** adapter 只接收字段为 `schemaVersion,eventType,messageId,correlationId,issuedAt,nonce,payload` 的版本化 JSON envelope，同时校验精确 `event.origin`、`event.source`、schema/未知字段、5 分钟重放窗和一次性 nonce  
**And** 发送端始终使用精确 `targetOrigin` 而非 `*`；未知事件/字段、错误 source/origin、过期消息和重复 nonce 一律拒绝并最小审计  
**And** 每个请求在 5 秒内返回绑定 `messageId/correlationId` 的 ack 或显式失败，重放不重复导航/登出；`auth.changed` 只能触发 BFF 会话重检，bridge 不得传递 token、ticket、授权决定或学生敏感数据。

### AC-1.2-HOST-DEGRADATION：宿主/身份依赖降级与恢复

**Given** 宿主握手超时、协议版本不支持、门户 origin 不匹配、IdP/BFF 不可用或网络中断  
**When** 壳无法确定安全宿主上下文或当前身份  
**Then** 页面以持久 `state-panel` 区分会话失效、宿主降级、网络故障和无权限，仅显示不含对象信息的影响范围与唯一恢复动作，不用 Toast 或旧缓存伪装正常  
**And** 断网禁止业务命令；恢复时严格执行“重认证 → 重算授权 → 刷新 aggregateVersion → 用户显式重试”，不自动重放上一个命令  
**And** 降级不得放宽 CSP、origin/schema 校验、cookie/CSRF 保护或服务端授权。

### AC-1.2-SECURITY-AUDIT：服务端安全、审计与不泄露失败

**Given** 非安全 HTTP 方法、SSO 回调、会话续期/结束和受保护路由都可能被恶意请求或并发命令触发  
**When** Origin/Referer/CSRF、state/nonce/PKCE、issuer/audience/signature/time、会话或授权校验失败  
**Then** 系统 fail closed，使用稳定 `code,message,traceId,fieldErrors[]` 错误包络；越界与不存在语义一致，日志、错误、审计和遥测不含 token、cookie、学生标识、原始敏感深链参数或密钥值  
**And** 登录结果、续期重放、登出/换号、拒绝、宿主输入拒绝和会话版本冲突生成含 actor/session pseudonym、action、result、timestamp、source IP、traceId 和 profile version 的最小本地审计事实  
**And** 审计写入失败时，登出/换号等关键会话命令不得返回假成功；同时不提前创建或宣称中央 AuditLedger 完成。

### AC-1.2-RECOVERABLE-SHELL：易失客户端状态与安全导航

**Given** 现有 Vue Router、TanStack Vue Query、Pinia 与 `VolatileClientState` 已建立内存边界  
**When** 实现应用壳、会话恢复、深链导航和宿主状态  
**Then** 复用现有 query key、恢复证明与生命周期清理；业务 query、草稿、身份投影和续访目标不得持久化到 localStorage/sessionStorage/IndexedDB/Cache Storage/Service Worker/文件系统  
**And** 登出、换号、刷新和宿主会话失效会清除 query cache、易失身份状态、表单草稿、重试证明与 host replay cache  
**And** 路由守卫使用 async return 风格并防止循环重定向；页面切换后焦点进入主标题，失败/重定向可观察但不泄露目标。

### AC-1.2-WEB-UX：统一门户品牌、响应式与 WCAG

**Given** UXB-1.0.0、受控 UI token/品牌资产与门户主题可用  
**When** 展示正常壳、会话提醒、加载、错误、无权限和降级状态  
**Then** 复用统一门户壳与 Element Plus 基础控件，不创建第二套登录页、顶部/底部导航或重复校徽；品牌层级为“学校 → 数智学工大系统 → 学林知微 → 观澜智核”，Web 主色/hover/pressed 保持 `#AF251B/#C53227/#A7180D`  
**And** 在 1440×900、1366×768、200% zoom 和 320px reflow 下无关键内容丢失或页面级横向滚动；桌面常规控件视觉高 36px，独立图标/行内动作点击区至少 40×40 CSS px  
**And** 达到 WCAG 2.2 AA：语义地标、跳转链接、阅读与 Tab 顺序一致、可见焦点、Enter/Space、Esc 后焦点恢复、合适 live region、状态不只靠颜色，并尊重 reduced-motion。

### AC-1.2-REAL-EVIDENCE：真实契约、浏览器与发布证据

**Given** 本 Story 改变前端受控字节、后端依赖/配置、宿主契约和页面视觉  
**When** 候选制品申请进入 `review`  
**Then** 单元/集成/安全负例、真正跨源宿主 harness、mock IdP 确定性测试、真实门户 + IdP 沙箱流程与冻结 Chrome/Edge 矩阵全部通过  
**And** 正式 Web 证据直接测试 digest-addressed store 中待提升的冻结前端制品，报告绑定 source/artifact/BuildManifest/TEST-ENV/浏览器执行文件摘要  
**And** 独立签名的 host/SSO runtime evidence 绑定同一 candidate digest、静态部署配置、门户 origin/sandbox 配置、IdP issuer/client registration 摘要、回跳登记与脱敏 trace；现有离线 formal-web 报告不能替代该证据  
**And** 不原位改写 `FPB-1.0.0`、`VGB-1.0.0`、TEST-ENV 或旧 ReleaseManifest；只为实际受影响的 baseline/contract/evidence 发布新版本，TEST-ENV 仅在经批准的环境矩阵变化时升版，并更新 lock、source inventory、SBOM、provenance、签名和 ReleaseManifest  
**And** 真实沙箱不可达、精确 origin/redirect URI/issuer/client 登记不齐、环境漂移或任一拒绝负例失败时均阻止 Story 完成。

## Tasks / Subtasks

- [x] Task 0：冻结接入参数与证据门槛（AC: 全部，尤其 REAL-EVIDENCE）
  - [x] 0.1 在不提交密钥的前提下记录门户/IdP 沙箱的 issuer、authorization/token/end-session/revocation endpoint、client registration、精确 redirect/post-logout URI、门户 origin 与 schemeful site 关系、IdP framing/top-level navigation 能力、CSP `frame-ancestors` allowlist、时钟源和证据保管位置。
  - [x] 0.2 为缺失参数建立可见阻塞检查；未取得真实沙箱参数时允许本地 RED/GREEN 开发，但禁止将 Story 移入 `review` 或 `done`。
  - [x] 0.3 从当前基线提交 `a0c8a9cba10d963c41623d27a8480dbbbddea393` 生成新的版本化宿主/身份契约与 runtime evidence 引用，不覆盖已批准的历史版本；环境矩阵未发生批准变更时继续引用 TEST-ENV-1.0.0，不为本 Story 擅自制造新 TEST-ENV。
  - [x] 0.4 若目标域名是 cross-site、SameSite=Lax cookie 在 iframe 中不可用，或 IdP 禁止 framing，则冻结经 HIP/ISP 批准的 host-bootstrap + 顶层认证/回跳流程；无法在现有基线内闭合时停止实现并提交新决策，不得放宽 cookie、CSP 或伪造同源证据。

- [x] Task 1：先冻结宿主、会话、续访与错误契约并写 RED 测试（AC: PORTAL-SSO, SESSION-LIFECYCLE, DEEP-LINK, HOST-CONTRACT, SECURITY-AUDIT）
  - [x] 1.1 在 `contracts/` 定义版本化 host envelope、ack/failure、bootstrap exchange、current session、reauthentication、logout/account-switch、continuation 与统一错误包络 schema；示例必须覆盖成功和全部拒绝路径。
  - [x] 1.2 为精确 origin/source、未知字段/事件、过期消息、nonce/message replay、bootstrap code 重兑、开放重定向、过期 continuation、CSRF、会话版本冲突和同键异请求写 contract/security RED 测试；冻结 `shell.home`/`shell.session` allowlist，未知业务 target 一律拒绝。
  - [x] 1.3 以 approved baseline 更新契约 lock/source inventory，保证后续实现不能静默漂移。

- [x] Task 2：实现后端 BFF/OIDC 与共享会话生命周期（AC: PORTAL-SSO, SESSION-LIFECYCLE, DEEP-LINK, SECURITY-AUDIT）
  - [x] 2.1 仅在 `web-api` 装载 Spring Security OAuth2 Client/OIDC；`worker` 不引入 HTTP 登录面。以 Boot BOM 管理依赖版本，并把新增依赖纳入供应链 lock/SBOM。
  - [x] 2.2 在 `identityaccess/{domain,application,adapters,api}` 实现 authorization code + PKCE S256、回调校验、服务端 token custody、受保护 current-session endpoint 和每请求授权重算端口；`api` 只放跨模块 transport-neutral public ports，HTTP/security 属于 inbound adapter，OIDC/数据库/IdP client 属于 outbound adapter。
  - [x] 2.3 采用适用于多实例 web-api 的共享会话/refresh-family/idempotency/nonce/continuation 存储及 identity-access 自有迁移；表使用 `identity_access.ia_*`、全局六位迁移序号，显式更新 `MigrationOwnershipContractTest` 的“0 SQL”脚手架断言和迁移清单。除非批准新增发布事实，不改变既有 fact-owner 集；不得直接采用不符合前缀/所有权规则的 Spring Session 默认 DDL。
  - [x] 2.4 实现 5m access、15m idle、8h absolute、5m warning、refresh rotation/reuse family revoke、单调 `sessionVersion`、logout/end-session/revocation 和完整 account switch。
  - [x] 2.5 对所有 unsafe method 实施 Origin/Referer + CSRF 校验；cookie 必须满足 ISP-1.0.0，错误、日志和审计执行敏感值脱敏。
  - [x] 2.6 实现一次性 opaque continuation：后端绑定 session/origin/expiry，认证后重新解析目标与授权；拒绝绝对 URL、协议相对 URL、跨源目标和重放。
  - [x] 2.7 新建 identity-access application outbound `SessionAuditPort` 及本模块持久 adapter，在会话事务中写最小本地安全事件；仓库当前没有可复用的现成审计端口，且本实现不得冒充 Story 1.3–1.5 的中央 AuditLedger。
  - [x] 2.8 refresh token/authorized-client 敏感值必须使用外部 KMS/key provider 的 `keyRef/keyVersion` 做 envelope encryption at rest，数据库、日志、异常和证据中不得出现明文；冻结本地 revoke、审计 commit、IdP end-session/revocation 的状态机、outbox/retry/对账顺序，部分失败必须可见且不能复活本地会话。

- [x] Task 3：实现门户 host adapter、bootstrap exchange 与 CSP（AC: HOST-CONTRACT, HOST-DEGRADATION, SECURITY-AUDIT）
  - [x] 3.1 在 `frontend/src/domains/identity-access/internal/` 实现唯一 host bridge adapter；所有公开能力从该领域 `index.ts` 导出，不新建通用 `auth/store/stores` 目录。
  - [x] 3.2 `postMessage` 收发严格校验精确 `targetOrigin`、`event.origin`、`event.source`、schema、5m replay window 与一次性 nonce；5s 内 ack/fail，重复请求复用结果但不重复副作用。
  - [x] 3.3 bridge 只承载 HIP 允许的事件和 60s、单次、audience-bound opaque bootstrap code；bootstrap 由后端兑换，消息中不得出现 token/ticket/授权决定/学生数据。
  - [x] 3.4 在 `deploy/base` 新增并版本化“静态 SPA/ingress 响应头 owner”配置，由它为 `/scholarsense/` 实际 HTML 响应设置精确 CSP `frame-ancestors`；后端 API header 或本地 Vite server 不能充当证明。验证完整祖先链、门户侧最小 iframe sandbox `allow-scripts allow-forms allow-same-origin`，并消除与跨源嵌入冲突的 `X-Frame-Options: SAMEORIGIN/DENY`。
  - [x] 3.5 对握手超时、版本不支持、错误 origin/source、重放和身份依赖失败提供稳定显式状态，不得降级为宽松安全策略。

- [x] Task 4：实现可恢复应用壳、会话协调与安全导航（AC: RECOVERABLE-SHELL, DEEP-LINK, HOST-DEGRADATION）
  - [x] 4.1 复用 `VolatileClientState`、TanStack Query、Pinia 和现有生命周期清理；身份/宿主状态只在内存，禁止浏览器持久化与 Service Worker 缓存。
  - [x] 4.2 新增服务端 current-session 驱动的 route guard：async return 风格、防循环、失败可观察；客户端守卫只负责协调，不承担授权。实现 `shell.home` 与无业务数据的 `shell.session` 受保护目标，专门承载本 Story 的真实重认证回原目标证明。
  - [x] 4.3 登出、换号、refresh、`auth.changed` 和会话失效统一清理 query cache、草稿、身份状态、continuation proof 与 replay cache。
  - [x] 4.4 实现断网/宿主降级恢复序列“重认证 → 重算授权 → 刷新 aggregateVersion → 用户显式重试”，禁止自动重放业务命令。

- [x] Task 5：实现 UXB 对齐的 Web 壳与无障碍状态（AC: WEB-UX, HOST-DEGRADATION）
  - [x] 5.1 使用现有 theme/token/Element Plus 组合统一门户品牌层级；不复制登录页、门户导航、页脚或校徽。
  - [x] 5.2 为 loading、warning、session expired、network、host degraded、unauthorized 提供持久 state panel、唯一恢复动作、语义标题与合适 live region；状态不只靠颜色。
  - [x] 5.3 实现 skip link、页面切换主标题聚焦、键盘/焦点恢复、reduced-motion，以及 1440×900、1366×768、200% zoom、320px reflow 和 40×40 点击区验收。

- [x] Task 6：完成分层自动化与真实沙箱证据（AC: 全部）
  - [x] 6.1 后端单元/集成测试覆盖 OIDC 校验、PKCE、cookie/CSRF、refresh rotation/reuse、sessionVersion、幂等、continuation、logout/account-switch、审计失败和稳定错误包络。
  - [x] 6.2 纯 adapter/route/state 单元测试放入现有 `frontend/tests/unit/` 下的 `*.test.ts` 并保持 Node 环境可运行；焦点、live region 与组件 DOM 行为默认放入 Playwright。若改用 Vitest DOM 环境，必须显式更新 config、依赖 lock、预算与供应链证据，不能把相邻但未被 include 的测试当成通过。
  - [x] 6.3 新增双 origin Playwright 配置/harness 并接入 `scripts/verify_frontend.sh`/`scripts/verify.sh`；对伪 origin/source、`*` target、未知 schema、过期/重放、重复 logout/navigation 做负例。现有单 origin baseline 不算宿主契约证据。
  - [x] 6.4 用 mock IdP 做确定性协议测试；再用真实门户 + IdP 沙箱完成已有会话无密码进入、refresh、过期、logout、account switch、deep-link return 和依赖降级。
  - [x] 6.5 在冻结 Chrome/Edge 双版本与正式 Web digest-addressed 候选制品上采集品牌、视觉、键盘、焦点、live-region、200% zoom/320px reflow 与跨源 CSP 证据；另生成绑定静态部署配置、门户配置、candidate digest、浏览器 executable digest 与沙箱运行 trace 的签名 host/SSO integration evidence。

- [x] Task 7：更新可信发布链并执行全量验证（AC: REAL-EVIDENCE）
  - [x] 7.1 更新新增源码/依赖/契约/UI golden 的 source inventory、lock、SBOM、provenance、签名、BuildManifest 与新 ReleaseManifest；扩展 evidence schema/index 以承载 host/SSO runtime evidence，使用生成器产生新版本，不手工篡改历史批准清单。
  - [x] 7.2 运行 `./scripts/verify.sh` 及 Story 专项证据脚本，确认正式 Web 证据绑定 source/artifact/BuildManifest/TEST-ENV/浏览器 executable digest。
  - [x] 7.3 将所有真实端点、client secret、cookie、token、学生标识和敏感深链值保持在版本库外；提交的日志/截图/trace 先脱敏并由自动检查验证。

### Review Findings

- [x] [Review][Patch] 分离内部 HTTP session ID 与公开 `sessionPseudonym`，避免泄露 cookie bearer 标识并使真实 `/current` 响应符合前端契约 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/CurrentSessionService.java:30]
- [x] [Review][Patch] OIDC 成功处理器的 KMS、数据库、审计或 continuation 失败必须清理 SecurityContext、authorized client、HttpSession 与 cookie [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/OidcLoginSuccessHandler.java:69]
- [x] [Review][Patch] 修复跨源 host bootstrap 的 Origin 约束、签发路径和 `host.ready` 必填证明，保证合法兑换可达且不能以空 payload 绕过 [frontend/src/domains/identity-access/internal/session/identity-session-client.ts:30]
- [x] [Review][Patch] 将受保护目标写入 continuation，并统一 `targetRouteId`/`authorizationUri` 冻结契约，使重认证返回原目标 [frontend/src/app/views/ShellRecoveryView.vue:16]
- [x] [Review][Patch] 为 refresh rotation 接入真实生产调用与 token 读取/轮换路径，并以原子消费处理并发 refresh 重放 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/IdentityAccessConfiguration.java:106]
- [x] [Review][Patch] `logout.requested` 必须提交带 CSRF、版本与幂等键的 BFF logout，成功后再清理客户端并确认 host ack [frontend/src/domains/identity-access/internal/host/runtime-host.ts:36]
- [x] [Review][Patch] 装配并调度远端登出 outbox，且 account switch 不得在 IdP end-session/revocation 确认前启动重新认证 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandService.java:56]
- [x] [Review][Patch] `auth.changed` 接纳新身份前清除旧身份 query cache、草稿、continuation proof 与 replay cache，失败时不得保留旧投影 [frontend/src/domains/identity-access/internal/host/runtime-host.ts:31]
- [x] [Review][Patch] 使首次登出响应丢失后仍可凭相同 `Idempotency-Key` 重放原结果，而非因会话立即失效变成未认证 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySessionController.java:102]
- [x] [Review][Patch] 为 API authentication/authorization/CSRF 失败统一返回冻结错误包络及契约内错误码 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySecurityConfiguration.java:35]
- [x] [Review][Patch] 为会话版本冲突、幂等不匹配、refresh 拒绝及 host 输入拒绝写入最小本地审计事实 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandService.java:35]
- [x] [Review][Patch] 在异步副作用前占用 messageId/nonce，并阻止五秒超时后的迟到提交，避免并发重放或失败重试重复执行 [frontend/src/domains/identity-access/internal/host/host-bridge.ts:118]
- [x] [Review][Patch] 将 runtime 加载失败与握手超时路由到持久 host-degraded state-panel，并提供唯一可用恢复动作 [frontend/src/domains/identity-access/internal/host/runtime-host.ts:54]
- [x] [Review][Patch] 让 canonical review/release 验证显式启用 runtime 与 host 的 `--review` 门禁，防止 pending 绑定在干净工作树上通过 [scripts/verify_core.sh:21]

## Dev Notes

### 当前仓库事实与最小改动面

当前代码不是空仓库：1.1c 已建立 Vue 3/Vite/Router/Pinia/TanStack Query/Element Plus 启动面与易失状态，1.1d 已建立真实 Git/CI/CAS/SBOM/attestation/signing/formal-web/digest-only promotion 链。实现者必须在这些边界内增量交付，不能另起一套壳、会话缓存或发布流程。

| 区域 | 现有文件/能力 | 本 Story 预期动作 |
|---|---|---|
| 前端组合入口 | `frontend/src/main.ts`, `frontend/src/App.vue` | 仅组合 provider、router、全局错误/主题；不要放协议或身份业务逻辑 |
| 路由 | `frontend/src/app/router/index.ts` | 增加 current-session 驱动的安全守卫、callback/恢复路由与页面聚焦 |
| 易失状态 | `frontend/src/app/state/volatile-client-state.ts`, `query-client.ts`, `connectivity-store.ts` | 复用清理/恢复证明；扩展身份与 host 生命周期，不引入持久化 |
| 领域入口 | `frontend/src/domains/identity-access/index.ts` 与 `internal/README.md` | 所有 SSO、host bridge、session、continuation 逻辑放在 `internal/`，经 `index.ts` 暴露 |
| UX/token | `frontend/src/app/theme/*`, `app/views/Baseline*` | 复用 token；把 fixture 保留为回归，不把它们当业务验收 |
| 后端入口 | `backend/src/main/java/cn/edu/suda/scholarsense/ScholarSenseApplication.java` | 只负责启动与角色选择 |
| 后端配置 | `backend/src/main/java/cn/edu/suda/scholarsense/runtime/*`, `backend/src/main/resources/application.yml` | 扩展 web-api 的 OIDC/session 配置校验；worker 不加载登录面 |
| 身份模块 | `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/{domain,application,adapters,api}` | `api` 仅 transport-neutral public ports；HTTP/security/OIDC/数据库实现放 adapters，禁止 api 直穿 domain 或跨模块 internal |
| 迁移所有权 | `backend/src/main/resources/db/migration/identity-access/`, `db/module-ownership.csv` | 表属 `identity_access` 且用 `ia_` 前缀；解除测试中的“0 SQL”脚手架断言但保留全部所有权 guard |
| 契约 | `contracts/config`, `contracts/openapi`, `contracts/events`, `contracts/release` | 增加 SSO/host/session schema、例子、负例 fixture，并生成新版本 release 输入 |
| 自动验证 | `frontend/vitest.config.ts`, `frontend/playwright.config.ts`, `scripts/verify_frontend.sh`, `scripts/verify.sh`, backend architecture/migration tests | 保持全部 guard 通过，将新增测试显式接入现有执行入口 |

预期新增文件可按职责拆分，但命名必须体现领域语义，例如：

- 前端：`domains/identity-access/internal/{api,host,session,navigation,components}/...`；纯单元测试放 `frontend/tests/unit/`，DOM/跨源行为放 Playwright。唯一公共面仍为 `domains/identity-access/index.ts`。
- 后端：`identityaccess/domain` 放会话/continuation 值对象和不变量，`application` 放用例和 outbound ports，`adapters/inbound` 放 HTTP/security mapping，`adapters/outbound` 放 OIDC、共享存储、审计/时钟实现；`api` 只放其他模块可依赖的 transport-neutral public ports。
- 数据库：`db/migration/identity-access/VNNNNNN__identity-access__*.sql`，遵守现有版本与所有权约束。
- 契约：优先放入明确的 `contracts/identity/` 与 `contracts/host/` 子树，避免把领域契约塞进泛化 `shared`。

### 架构与安全护栏

1. **BFF 是安全边界。** SPA 不拥有 token，不解析 ID token，不决定授权；每次受保护请求由服务端重建/校验当前 actor、角色、对象权限和版本。客户端 `auth.changed` 只触发 current-session recheck。
2. **共享正确性状态不得只存内存。** web-api 是可横向扩展实例。refresh family、sessionVersion、nonce/replay、bootstrap/continuation 单次消费和幂等结果必须在共享事务存储中收敛；如果选择 Spring Session JDBC，版本由当前 Spring Boot 4.1.0 BOM 管理并使用 identity-access 所有的迁移，不依赖自动建表。
3. **并发与幂等是接口契约。** 会话变更命令携带 `aggregateVersion/sessionVersion` 和 `Idempotency-Key`；成功推进版本，旧版本或同键异请求稳定 409；重放同请求返回原结果且不重复调用 IdP、不重复导航、不重复写业务事实。
4. **跨源嵌入同时需要两侧约束。** `Content-Security-Policy: frame-ancestors <exact-portal-origins>` 必须是 HTTP 响应头，不能只写 `<meta>`，也不能依赖 `default-src`；所有祖先都必须匹配。host message 必须同时验证 origin、source、schema、时间窗和 nonce。
5. **CSP 与 X-Frame-Options 不冲突。** 既然 HIP 允许精确跨源门户 iframe，就不能遗留 `DENY` 或 `SAMEORIGIN` 阻断合法宿主；同时绝不能用 `*`、宽域通配符或 `postMessage("*")` 解决配置问题。
6. **失效恢复不等于重放命令。** 断网或会话过期后先恢复身份、重算权限、刷新对象版本，再要求用户显式重试。禁止自动提交旧草稿或旧命令。
7. **错误不泄露对象存在性。** 未认证、无权限、目标不存在/已失效的外部响应遵守既定统一语义；诊断细节仅写脱敏内部遥测，并携带 `traceId`。
8. **敏感数据零前端持久化。** 现有 `VolatileClientState` 是唯一客户端状态边界。不能新增 localStorage/sessionStorage/IndexedDB/Cache Storage/Service Worker/下载文件持久化身份、深链或草稿。
9. **“跨源”不自动等于“跨站”。** HIP 的跨源 iframe 与 ISP 的 SameSite=Lax 必须在目标域名和冻结浏览器上共同证明：若门户与应用不是同一 schemeful site，第三方 cookie 行为可能阻断 iframe 会话；IdP 也可能禁止在 frame 内认证。实现前必须冻结 host-bootstrap 与顶层认证/回跳责任；现有基线无法闭合时提交 HIP/ISP 新决策，不得擅自改为 SameSite=None、放宽 cookie 或伪造同源证据。
10. **静态 CSP 有独立部署 owner。** 当前 formal-web harness 只服务冻结静态字节且阻断外网，不能单独证明生产静态服务器的 headers、门户 sandbox 或 SSO。`deploy/base` 的静态 SPA/ingress 配置、门户侧配置和签名 runtime evidence 必须进入 release source/evidence 图并绑定同一 candidate digest。
11. **外部登出不是数据库原子事务。** 本地 session revoke 与本地安全事件必须先在同一事务 fail closed；IdP end-session/revocation 通过明确状态机和 outbox/retry/对账收敛。不得因为 IdP 超时而恢复本地会话，也不得在远端撤销未确认时静默宣称完整换号成功。

### 建议 HTTP / 事件契约

沿用 `/api/v1` 与资源复数风格；最终路径以契约测试冻结，避免在 controller 中先写死再补 schema。

| 方法与路径 | 用途 | 关键约束 |
|---|---|---|
| `GET /api/v1/identity-sessions/current` | 返回脱敏当前会话投影与到期提示 | 不返回 token；未认证使用稳定错误包络 |
| `POST /api/v1/host-bootstrap-exchanges` | 兑换 60s 单次 opaque bootstrap code | 绑定 audience/origin/session；重兑显式拒绝 |
| `POST /api/v1/identity-sessions/reauthentications` | 创建一次性 continuation 并发起重认证 | 仅 opaque route state，拒绝开放重定向 |
| `POST /api/v1/identity-sessions/logout` | BFF + IdP 完整登出 | CSRF/Origin、幂等、版本冲突、审计强一致 |
| `POST /api/v1/identity-sessions/account-switches` | 完整登出后换号 | 不允许仅替换本地 actor |

建议稳定错误码：

- `IDENTITY_SESSION_REQUIRED`, `IDENTITY_SESSION_EXPIRED`, `IDENTITY_DEPENDENCY_UNAVAILABLE`, `IDENTITY_SESSION_VERSION_CONFLICT`
- `HOST_ORIGIN_FORBIDDEN`, `HOST_SOURCE_FORBIDDEN`, `HOST_MESSAGE_INVALID`, `HOST_MESSAGE_REPLAYED`
- `HOST_BOOTSTRAP_EXPIRED`, `HOST_BOOTSTRAP_ALREADY_USED`, `CONTINUATION_INVALID_OR_EXPIRED`

错误体继续遵守 `code,message,traceId,fieldErrors[]`。外部 `message` 保持安全一致；内部原因不得拼接到响应。

### 库与框架约束

- 后端当前为 Java 25、Spring Boot 4.1.0、Spring MVC。优先增加 `spring-boot-starter-security`、`spring-boot-starter-oauth2-client` 以及经架构决定需要的 session/JDBC 组件；使用 Boot dependency management，禁止手选一组 Spring Security 子模块版本。
- 使用 Spring Security OAuth2 Login/OIDC 的 authorization endpoint、callback 与 authorized client 基础设施，但应用层必须显式实现本 Story 的 cookie、CSRF、sessionVersion、refresh-family、continuation、IdP logout 和审计不变量。
- Spring Security 默认 session DDL、cookie 名称与 security headers 不自动满足本项目：必须提供 `identity_access.ia_*` 自有迁移、显式 `__Host-` cookie 配置、精确 CSP，并对框架默认 `X-Frame-Options` 做契约测试。
- refresh token 与 authorized-client 持久数据必须 envelope-encrypted，记录 `keyRef/keyVersion` 并由部署时外部 key provider 解密；不允许把加密主密钥或可逆测试密钥提交到仓库。
- 前端当前为 Vue 3.5.40、Vue Router 4.6.4、Pinia 3.0.4、TanStack Vue Query 5.101.2、Element Plus 2.14.3、Vite 8.1.5、Vitest 4.1.10、Playwright 1.61.1。不要再引入第二个 router、request cache、全局 store 或 UI 框架。
- Vue Router guard 使用返回值/Promise 风格；避免混用 `next` 导致重复解析。host bridge 采用浏览器原生 `window.postMessage`，并把协议校验集中在一个 adapter。
- 如需 schema runtime validation，先评估现有依赖锁、浏览器预算和供应链证据成本；无论选库与否，契约 schema 与拒绝测试都是规范源，不能只依赖 TypeScript 类型。

### 测试与证据要求

测试金字塔至少包含：

1. **纯单元测试：** 放入当前 Vitest include 的 `frontend/tests/unit/`，覆盖 PKCE/nonce/state、time-skew、sessionVersion、refresh family、idempotency digest、continuation allowlist、host envelope validator、replay cache、错误脱敏；Node 环境不承担 DOM 验收。
2. **后端集成测试：** mock OIDC provider + 真实 HTTP/cookie/CSRF/shared store，验证 callback、current-session、refresh、logout/account-switch、并发冲突和审计失败。
3. **前端组件/路由测试：** 所有壳状态、query/Pinia/volatile cleanup、焦点、live region、keyboard、reduced motion、route-loop 与断网显式重试。
4. **跨源浏览器契约：** 新 Playwright harness 与应用必须运行在真正不同 origin，并显式接入 `scripts/verify_frontend.sh`；覆盖正确/错误 origin、source、schema、time、nonce、ack/fail、CSP 和 iframe sandbox。焦点/live-region/组件 DOM 行为也在该浏览器层验证，除非批准并锁定单独 DOM 测试环境。
5. **真实集成证据：** 真实门户 + IdP sandbox，不可被 mock IdP 取代；必须记录已存在 IdP 会话下无再次密码提示、refresh、expiry、logout、account switch、deep-link return。
6. **正式 Web 证据：** 继续使用 TEST-ENV-1.0.0 冻结的 Chrome 150.0.7871.124/149.0.7827.155 与 Edge 150.0.4078.65/149.0.4022.98（或经批准的新版本），并直接测试 digest-addressed candidate。

关键负例不得遗漏：

- callback 的 state/nonce/issuer/audience/signature/exp/nbf/iat/PKCE 任一错误；
- refresh token 重用、两个并发 refresh/logout、旧 `sessionVersion`、同 `Idempotency-Key` 异请求；
- cookie 缺少任一 `__Host-` 属性、CSRF 或 Origin/Referer 不匹配；
- absolute/protocol-relative/cross-origin continuation、重复 callback、已失效/无权/不存在目标；
- `postMessage("*")`、伪造 source、相似子域、null origin、未知字段/事件、过期消息、nonce replay、重复导航/登出；
- CSP 只存在 meta、祖先链不全、X-Frame-Options 冲突、sandbox 权限过宽；
- 门户与应用 cross-site 时 Lax cookie 丢失、IdP 拒绝 framing、顶层认证无法安全回到原 iframe；
- offline 自动重放旧命令、local/session storage 出现身份/目标、日志/trace/截图出现 token/cookie/学生标识。
- refresh/authorized-client 数据库出现明文、错误 keyRef/keyVersion、KMS 不可用、IdP revoke 部分失败后本地会话复活或换号继续。

### 前序 Story 与 Git 情报

- Story 1.1c 已交付可复现前端基线和 `VolatileClientState`；本 Story 应扩展而非复制这些机制。
- Story 1.1d 已交付可信发布与正式 Web 证据链。其历史文档中关于“无 VCS/无 CI”的早期描述不再代表当前仓库状态；当前必须复用已存在的真实链。
- 当前实施基线为 `a0c8a9cba10d963c41623d27a8480dbbbddea393`（合并 PR #29）。紧邻提交集中修复了 directed review、CI bypass 与供应链缺口；修改验证/清单时尤其避免重新引入“默认排除新文件”“失败证据可被吞掉”或“手工改写批准产物”。
- 工作树在 Story 创建前为 clean；实现者开始开发时应重新确认基线与并行改动，不能覆盖用户后续更改。

### 最新技术校验（2026-07-20）

- OAuth 2.0 Security Best Current Practice 已发布为 RFC 9700：授权码客户端应使用 PKCE，推荐 `S256`；refresh token 应使用 sender constraint 或 rotation 来检测重放。本 Story 的 ISP 约束比通用指南更具体，发生差异时遵守已批准 ISP。
- OpenID Connect RP-Initiated Logout 1.0 定义了 `id_token_hint`、`post_logout_redirect_uri`、`state` 等 RP 发起登出语义；redirect URI 必须预登记，客户端不得拼接任意回跳。
- `window.postMessage` 的安全基线是发送时给出精确 target origin，并在接收时验证 origin、必要时验证 source 与消息 schema。
- CSP `frame-ancestors` 必须通过 HTTP 响应头表达，不从 `default-src` 继承，也不能用 meta 元素替代。

### Project Structure Notes

- 与架构一致：前端业务逻辑属于 `frontend/src/domains/identity-access/internal/`，公共导出属于该领域 `index.ts`；`app/` 只保留 composition、router、providers、theme 与 shell。
- 不创建 `frontend/src/app/auth`、`frontend/src/store`、`frontend/src/stores` 等绕开结构守卫的目录；`scripts/check_frontend_structure.py` 是执行性约束。
- 后端继续使用模块化单体：`domain` 持有不变量，`application` 编排用例/ports，`adapters/inbound` 承载 HTTP/security，`adapters/outbound` 承载 OIDC/DB/审计/KMS；`api` 仅为跨模块 transport-neutral public ports。遵守 `ArchitectureBoundaryTest`、`ModuleStructureTest` 和 migration ownership checks。
- 首次真实迁移必须修改 `MigrationOwnershipContractTest` 的脚手架断言：不再要求 SQL 数量为 0，但继续验证全局六位序号、`identity_access` schema、`ia_` 前缀和跨模块引用禁令；Spring Session 默认表名不能直接落库。
- OIDC/security 只在 `web-api` 角色启用；`worker` 角色不得暴露 HTTP 回调、加载 client secret 或依赖浏览器会话。
- 若架构与上述建议路径发生合理冲突，以批准的 Architecture/HIP/ISP 与自动架构测试为准，并在 Dev Agent Record 记录偏差和证据。

### References

- [Source: `_bmad-output/planning-artifacts/epics.md` — Epic 1、Story 1.2、FR-1、依赖和上游 AC]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md` — FR-1、NFR-17/19/20/22/30]
- [Source: `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md` — HIP-1.0.0、ISP-1.0.0、RFP/UXB/PAB]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md` — AD-8、AD-17、AD-26、AD-28 与模块结构]
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md` — 统一壳、状态面、深链、响应式和 WCAG 约束]
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md` — 品牌、token、布局与组件约束]
- [Source: `_bmad-output/project-context.md` — 项目级实现规则]
- [Source: `_bmad-output/implementation-artifacts/1-1c-批准生产前端与性能-profile-adr.md` — 前端基线与易失状态]
- [Source: `_bmad-output/implementation-artifacts/1-1d-固化-ci-供应链与质量门.md` — 可信发布链和前序经验]
- [Source: `contracts/performance/test-environment-1.0.0.json` — 冻结浏览器/证据环境]
- [Source: `frontend/src/app/state/volatile-client-state.ts`, `frontend/src/app/router/index.ts`, `frontend/src/domains/identity-access/index.ts`]
- [Source: `backend/pom.xml`, `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/`, `backend/src/main/resources/db/module-ownership.csv`]
- [RFC 9700: Best Current Practice for OAuth 2.0 Security](https://www.rfc-editor.org/rfc/rfc9700.html)
- [OpenID Connect RP-Initiated Logout 1.0](https://openid.net/specs/openid-connect-rpinitiated-1_0.html)
- [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)
- [Spring Security OAuth2 Client Authorization Grants](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)
- [MDN: Window.postMessage](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage)
- [MDN: CSP frame-ancestors](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-ancestors)
- [MDN: Third-party cookies](https://developer.mozilla.org/en-US/docs/Web/Privacy/Guides/Third-party_cookies)
- [Vue Router: Navigation Guards](https://router.vuejs.org/guide/advanced/navigation-guards.html)

## Story Completion Status

- Story 为 `done`：全部本地可验证项已完成，14 项既有 Review Findings 与修复回归均通过。
- 真实学校门户/IdP、生产 KMS/共享数据库、冻结 Edge/Chrome 矩阵和独立签名证据按用户明确指令登记为限定豁免；缺口仍保留在 runtime profile，未伪造端点、凭据、签名或浏览器结果。
- 该豁免只允许 Story 流程继续，不是生产提升证据；生产发布仍必须补回真实绑定与 `host-sso-runtime-evidence` 签名载荷。

## Dev Agent Record

### Implementation Plan

- 先将可本地证明的未完成项按 RED/GREEN 补齐：静态 CSP 渲染 owner、后端/模拟 IdP 协议覆盖和发布证据索引。
- 对仅学校门户、IdP、KMS、Edge 冻结版本或独立签名主体可提供的证据，使用用户明确授权的限定豁免并保留禁止伪造/禁止充当生产提升证据的约束。

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Task 0 采用三态门禁：`pending`、具备完整真实证据的 `complete`、以及仅对外部支持项有效的 `controlled-complete`；后者必须绑定精确用户豁免并保持真实缺口为空绑定/空 evidence。
- Task 0 回归：Review 模式同时通过 identity runtime、host deployment 和敏感值污染门禁；删除或扩宽豁免会稳定失败。
- Task 1 以 JSON Schema + 语义检查双层冻结契约；`IDENTITY-CONTRACT-LOCK-1.0.0` 同时作为本 Story 契约源清单，绑定基线提交与全部受控文件 SHA-256。
- Task 1 回归：`./scripts/verify_core.sh` 通过（后端 36、Python 350、前端单测 27、Playwright 20 通过/4 按既有矩阵跳过，双重离线构建一致）。
- Task 2 以 domain/application/adapters/api 分层实现 BFF 会话边界、PKCE/OIDC 校验、共享 JDBC 状态、单次 continuation、幂等/版本冲突、本地审计、KMS envelope token custody 与远端撤销 outbox；web-api 条件装载，worker 保持无登录面。
- Task 3–5 实现唯一 host bridge、后端 bootstrap 兑换、精确 origin/source/schema/replay 校验、恢复壳、服务端会话路由守卫、统一易失状态清理、品牌层级与无障碍状态；新增实际 Nginx 响应头 owner，并精确验证 `frame-ancestors https://portal.stage.invalid`、最小 sandbox 和无冲突 X-Frame-Options。
- Task 6 回归：后端 79 项测试通过；确定性 Mock IdP 覆盖 back-channel refresh、revocation 与 end-session，生产适配器只接受 HTTPS。canonical 两轮前端复现各通过 47 项 Vitest、43 项 Playwright，8 项仅正式环境用例由限定豁免处置，source/lock/tree/build 摘要一致。
- Review 证据门禁通过 `controlled-complete`；真实 runtime binding 和签名 evidence 仍为空，豁免文件禁止将其解释为真实学校环境执行或生产提升证据。
- Task 7 在一次性干净 Git 快照 `486fa98464a9f06de3d1bcacffe4b33319e395ae` 上完整运行 `./scripts/verify.sh`；发布器两次 clean-clone 构建一致，artifact set SHA-256 为 `8a640fe3f2f5084e7dc1e12c2f04967dd2fd6c0c8281edd20740f106af8dd906`。

### Completion Notes List

- Task 0：新增版本化身份 runtime profile/schema，显式保留真实门户/IdP 缺失项，继续绑定 HIP/ISP-1.0.0、TEST-ENV-1.0.0 与 Story 基线提交；新增不含密钥的本地/Review 双模式检查及负例。
- Task 1：完成 host/session/continuation/error 契约、成功示例、13 类安全拒绝目录、allowlist 和防静默漂移契约锁。
- Task 2：完成服务端 BFF/OIDC、安全 cookie/CSRF、共享会话/refresh/idempotency/continuation、最小本地审计、加密 token custody、远端登出收敛及 identity-access 自有迁移；更新 BOM 依赖锁和 SBOM 回归计数。
- Task 3：完成 host bridge、runtime origin 获取、60 秒单次 bootstrap、5 秒 ack/fail、严格消息拒绝、部署 CSP/sandbox 模板及已渲染 Nginx 响应头 owner；生产部署由限定外部豁免覆盖但不宣称已上线。
- Task 4：完成服务端 current-session 驱动的安全导航、身份/宿主生命周期协调、无持久化易失状态清理和显式恢复序列。
- Task 5：完成统一品牌应用壳、稳定状态面、唯一恢复动作、路由标题/skip-link 焦点、reduced-motion、缩放与窄屏回归。
- Task 6：完成本地单元、架构、契约、安全负例、双 origin Playwright、确定性 Mock IdP 与两轮离线可复现验证；真实 IdP/门户/冻结正式浏览器证据以限定豁免处置并保持可见缺口。
- Task 7：新增 host/SSO runtime evidence schema、ReleaseManifest/EvidenceIndex kind 与生成器回归；source inventory、lock、SBOM、BuildManifest 和两次制品复现通过。外部签名/新 ReleaseManifest 不伪造，由豁免排除且不能用于生产提升。

### File List

- `_bmad-output/implementation-artifacts/1-2-接入统一门户-sso-与可恢复应用壳.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `backend/pom.xml`
- `backend/src/main/java/cn/edu/suda/scholarsense/ScholarSenseApplication.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/IdentityAccessConfiguration.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/BrowserSessionBinding.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/HostBootstrapController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityErrorEnvelope.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityExceptionHandler.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityRuntimeController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySecurityConfiguration.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySessionController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/OidcLoginSuccessHandler.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/RequestOriginValidationFilter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcIdentityAccessStore.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcIdentityEstablishmentTransactionAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcRefreshTransactionAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcSessionTransactionAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpRemoteIdentityProviderClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/KmsEnvelopeClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/KmsEnvelopeEncryptionAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/api/CurrentIdentitySessionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/AuthorizationRecalculationPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/AuthorizedClientSecretRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/ContinuationCreated.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/ContinuationRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/ContinuationService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/ContinuationTarget.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/CurrentSessionProjection.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/CurrentSessionService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/EncryptedAuthorizedClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/EncryptedSecret.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/EnvelopeEncryptionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/HostBootstrapRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/HostBootstrapService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/IdentityEstablishmentTransactionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/IdentitySessionRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/OidcClaims.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/OidcClaimsValidator.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/OidcSessionEstablishment.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/OidcSessionEstablishmentService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/OpaqueCodeGenerator.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/PkceProof.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/PseudonymizationPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RefreshTransactionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteIdentityProviderClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteLogoutOutboxPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteLogoutProcessor.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteLogoutRequest.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteLogoutWorkRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RequestOriginPolicy.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SecureOpaqueCodeGenerator.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionAuditFact.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionAuditPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommand.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandResult.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandType.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCookiePolicy.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionIdempotencyRepository.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionRefresh.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionRefreshService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/SessionTransactionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/StoredContinuation.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/StoredHostBootstrap.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/StoredRemoteLogout.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/StoredSessionCommand.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/TokenCustodyService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/UuidV7.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/domain/IdentityAccessException.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/domain/IdentitySession.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/domain/RefreshRotation.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/domain/SessionStatus.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/runtime/RuntimeConfiguration.java`
- `backend/src/main/resources/db/migration/identity-access/V000001__identity-access__session_boundary.sql`
- `backend/src/test/java/cn/edu/suda/scholarsense/architecture/MigrationOwnershipContractTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/contractfixture/CrossCuttingCommandContractTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/RequestOriginValidationFilterTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/ContinuationServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/CurrentSessionServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/EncryptedTokenCustodyTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/HostBootstrapServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/OidcSecurityPolicyTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/OidcSessionEstablishmentServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteLogoutProcessorTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/SessionCommandServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/SessionRefreshServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/domain/IdentitySessionTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/runtime/ScholarSenseApplicationSmokeTest.java`
- `contracts/host/examples/valid/acknowledged.json`
- `contracts/host/examples/valid/failed.json`
- `contracts/host/examples/valid/host-ready.json`
- `contracts/host/examples/valid/navigate-requested.json`
- `contracts/host/host-envelope.schema.json`
- `contracts/host/host-response.schema.json`
- `contracts/identity/bootstrap-exchange.schema.json`
- `contracts/identity/continuation.schema.json`
- `contracts/identity/current-session.schema.json`
- `contracts/identity/error-envelope.schema.json`
- `contracts/identity/examples/negative-catalog.json`
- `contracts/identity/examples/valid/account-switch-request.json`
- `contracts/identity/examples/valid/bootstrap-exchange-request.json`
- `contracts/identity/examples/valid/continuation.json`
- `contracts/identity/examples/valid/current-session.json`
- `contracts/identity/examples/valid/logout-request.json`
- `contracts/identity/examples/valid/reauthentication-request.json`
- `contracts/identity/examples/valid/version-conflict-error.json`
- `contracts/identity/identity-contract-lock-1.0.0.json`
- `contracts/identity/identity-runtime-profile-1.0.0.json`
- `contracts/identity/identity-runtime-profile.schema.json`
- `contracts/identity/reauthentication.schema.json`
- `contracts/identity/session-command.schema.json`
- `contracts/release/backend-lock-1.0.0.json`
- `contracts/release/evidence-index.schema.json`
- `contracts/release/fixtures/index.json`
- `contracts/release/fixtures/invalid/host-sso-runtime-evidence.json`
- `contracts/release/fixtures/valid/host-sso-runtime-evidence.json`
- `contracts/release/host-sso-runtime-evidence.schema.json`
- `contracts/release/release-manifest.schema.json`
- `deploy/base/host-integration-1.0.0.json`
- `deploy/base/nginx-scholarsense.conf.template`
- `frontend/playwright.config.ts`
- `frontend/src/App.vue`
- `frontend/src/app/router/index.ts`
- `frontend/src/app/theme/styles.css`
- `frontend/src/app/views/ShellHomeView.vue`
- `frontend/src/app/views/ShellRecoveryView.vue`
- `frontend/src/app/views/ShellSessionView.vue`
- `frontend/src/domains/identity-access/index.ts`
- `frontend/src/domains/identity-access/internal/host/host-bridge.ts`
- `frontend/src/domains/identity-access/internal/host/runtime-host.ts`
- `frontend/src/domains/identity-access/internal/session/identity-lifecycle.ts`
- `frontend/src/domains/identity-access/internal/session/identity-session-client.ts`
- `frontend/src/domains/identity-access/internal/session/identity-state.ts`
- `frontend/src/main.ts`
- `frontend/tests/baseline/baseline.spec.ts`
- `frontend/tests/baseline/host-cross-origin.spec.ts`
- `frontend/tests/baseline/identity-shell.spec.ts`
- `frontend/tests/host-fixture/index.html`
- `frontend/tests/unit/host-bridge.test.ts`
- `frontend/tests/unit/identity-lifecycle.test.ts`
- `frontend/tests/unit/identity-session-client.test.ts`
- `scripts/check_host_deployment.py`
- `scripts/check_identity_contracts.py`
- `scripts/check_identity_runtime_evidence.py`
- `scripts/check_production_pollution.py`
- `scripts/check_sbom.py`
- `scripts/tests/test_delivery_quality.py`
- `scripts/tests/test_host_deployment.py`
- `scripts/tests/test_identity_contracts.py`
- `scripts/tests/test_identity_runtime_gate.py`
- `scripts/tests/test_sbom.py`
- `scripts/verify_core.sh`
- `_bmad-output/implementation-artifacts/spec-1-2-backend-identity-lifecycle-findings.md`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/HostBootstrapIssuanceController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/HostInputRejectionController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityErrorResponseWriter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityRefreshController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySecurityErrorHandlers.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/RemoteLogoutScheduler.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/KmsEnvelopeDecryptClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/KmsEnvelopeDecryptionAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/EnvelopeDecryptionPort.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/HostBootstrapCreated.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/HostInputRejectionAuditService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/application/RemoteRefreshTokens.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/HostBootstrapControllerTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/HostInputRejectionControllerTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentityReplaySecurityFilterChainTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySecurityErrorHandlersTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/IdentitySessionControllerReplayTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/OidcLoginSuccessHandlerTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpRemoteIdentityProviderClientTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/JdbcIdentityAccessStoreTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/application/HostInputRejectionAuditServiceTest.java`
- `contracts/host/examples/valid/host-challenge.json`
- `contracts/host/host-challenge.schema.json`
- `release/build_release.py`
- `release/manifests.py`
- `scripts/tests/test_release_build.py`
- `scripts/tests/test_release_manifests.py`
- `scripts/verify.sh`
- `deploy/base/nginx-scholarsense.conf`
- `deploy/base/story-1.2-external-evidence-waiver-1.0.0.json`

### Change Log

- 2026-07-20：完成 Tasks 0–2、4–5，以及 3.1–3.3/3.5、6.2–6.3；新增 BFF/OIDC/共享会话、门户 bridge、可恢复应用壳、契约/迁移/部署门禁和分层测试。因真实门户/IdP/KMS/共享数据库、生产 CSP 渲染、冻结浏览器和签名发布证据缺失，Story 保持 `in-progress`。
- 2026-07-20：完成 14 项 Review Findings 修复与回归；本地 canonical 验证全绿，review/release 已强制执行 runtime/host `--review` 门禁。因真实 runtime binding、跨站决策、静态部署渲染与签名证据仍缺失，Story 继续保持 `in-progress`。
- 2026-07-20：完成剩余 Tasks 3.4、6、7；新增确定性 Mock IdP/生产 HTTPS 适配器、已渲染 CSP owner、受控外部证据豁免和 host/SSO release evidence schema。`verify_core --review` 与干净快照 `verify.sh` 全绿；Story 更新为 `done`，真实外部证据仍不得被豁免替代用于生产提升。
