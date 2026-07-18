# Finalize Reconcile：既有技术现实对账

## 1. 对账范围与结论

对照以下输入审查当前 `ARCHITECTURE-SPINE.md`：

- `docs/input/原型/frontend/` 与其 `package-lock.json`；
- `docs/input/文档/需求列表.md` 的早期技术项；
- `reviews/extract-reality.md`；
- 当前架构脊柱草稿。

**总体判断：基本通过，但有 2 项高优先级和 3 项中优先级需要在 Finalize 前修正或澄清。** Spine 没有把原型中的 mock 登录、前端路由守卫、页面按钮成功提示、规则阈值、`/scholarsense/` 路径、qiankun/无界、Nacos、Spring Cloud Alibaba、国密/AES/TDE、Prometheus/Grafana/SkyWalking直接 ratify 为生产实现；这些主要历史候选已正确进入 Deferred 或被抽象为端口。

但是，部分规则和图以 `[ADOPTED]` 或无标签口吻表达了材料并不能证明的生产现实，容易在最终稿中被误读为 brownfield 既成事实。

## 2. 正确处理项

| 检查项 | Spine 处理 | 判断 |
| --- | --- | --- |
| 原型登录、换角、路由守卫 | AD-8 明确前端守卫不是安全边界；Deferred 不继承模拟换角 | 正确，未 ratify mock 安全实现。 |
| 原型保存、导出、审计、熔断、回退等成功提示 | Spine 以服务端命令、持久作业、审计/outbox 重新定义生产语义 | 正确，没有把消息提示当成已实现能力。 |
| qiankun / 无界 | 门户宿主协议 Deferred | 正确，未锁历史候选。 |
| CAS/JWT/session/token exchange | 身份传播 Deferred | 基本正确，未锁具体令牌机制。 |
| Nacos / Spring Cloud Alibaba | 未进入固定栈；配置抽象为校级配置端口 | 正确。 |
| SM2/SM3/SM4、AES、TDE、KMS 产品 | CryptoPort + Deferred | 正确，绑定安全结果而非历史算法候选。 |
| Prometheus/Grafana/SkyWalking | 未进入固定栈 | 正确。 |
| 原型规则阈值、KPI、推送频率、mock 状态 | 未被固化 | 正确。 |
| 前端依赖版本 | 明确按现有 lockfile ratify，并单列实际解析版本 | 有证据；但见下文关于 Axios 与升级策略的中优先级说明。 |

## 3. 发现与优先级

### 高优先级

#### R-H1：校方“网关”被无标签写成既定认证事实

- **位置：** AD-8 Rule 的“学校 SSO/网关提供认证事实”；运行拓扑中的 `统一身份 / 网关`。
- **问题：** 最终 PRD 明确绑定学校统一身份和统一门户，但“网关签发内部短时令牌贯通各微服务”只来自早期 F-002，未被最终 PRD 继续绑定。`extract-reality.md` 已要求身份传播方式等待校方规范。虽然 Deferred 正确说明不绑定 CAS/JWT/session/token exchange，但 AD-8 和图仍把“网关”与 SSO 并列成无条件现状。
- **风险：** 下游可能误以为校方已有承担认证事实传播的统一 API 网关，并据此跳过身份适配边界或强制采用早期网关方案。
- **应处置：** 将无标签事实收敛为“校方身份/宿主适配器提供认证事实”，或显式标为 `[ASSUMPTION]`；网关是否存在及其职责留在 Deferred。

#### R-H2：多个 `[ADOPTED]` 标签把设计机制误标为既有现实

- **位置：** 尤其 AD-10 的“业务事务同提交到 append-only audit/outbox”、AD-11 的“命令模型是业务事实源/读模型可重建”、AD-16 的“OpenTelemetry/OTLP 接口”；AD-4 的“不可变规范事件”也属于同类边界。
- **问题：** PRD 绑定的是审计防篡改、口径可追溯、traceId、外部事实/证据快照等结果；当前仓库没有后端、outbox、事件规范、CQRS 读模型或 OTel 实现。它们可以是 Fast path 架构决策，但不能因 `[ADOPTED]` 被解释为现有现实已经 settled。
- **风险：** Finalize 后 `[ADOPTED]` 会混淆“用户/输入已决策约束”与“架构草稿选择”，削弱现实追溯，也可能让 reviewer 误判无需验证。
- **应处置：** 保留必要规则时，移除这些机制层面的 `[ADOPTED]`，或只把 PRD 已绑定的结果写为 adopted、把实现机制写为普通 AD / `[ASSUMPTION]`。这不是否定方案本身，而是纠正证据等级。

### 中优先级

#### R-M1：拓扑图容易被读成已存在生产部署

- **位置：** “运行与部署拓扑”中的 SSO/网关、PostgreSQL HA、对象存储、双 web-api、双 worker、校级 OBS 连接。
- **问题：** AD-14 已标 `[ASSUMPTION]`，固定版本说明也称后端和生产数据栈为 assumption seed；但拓扑图本身没有 assumption 标识，且 `extract-reality.md` 明确当前材料不足以 ratify 后端、存储、消息或部署拓扑。
- **风险：** 独立阅读图时，会把设计 seed 当成 brownfield 发现。
- **应处置：** 在图标题/图注明确“[ASSUMPTION] seed，非现状盘点”；校方真实平台未核验前不要使用“现有”措辞。

#### R-M2：OpenTelemetry/OTLP 被固定为 `[ADOPTED]` 产品契约

- **位置：** AD-16、固定版本栈 `OpenTelemetry Protocol 1.9.0`。
- **问题：** 最终 PRD只绑定 traceId 与接入校级日志监控；早期候选是 Prometheus/Grafana/SkyWalking，现有材料未提供校级 OTLP 兼容证据。选择 OTel 可以是架构建议，但不是 adopted reality。
- **风险：** 若校级平台只接受既有 agent/日志协议，固定 OTLP 会产生未验证的适配成本；且与“具体可观测产品未绑定”的 reality 结论不一致。
- **应处置：** 改为普通 AD 或 `[ASSUMPTION]`，以 ObservabilityPort/适配器保持校级协议可替换；取得校级接入规范后再固定协议版本。

#### R-M3：Axios 被按 lockfile ratify，但代码未实际使用

- **位置：** 固定版本栈及“前端版本按现有 package-lock.json ratify”。
- **问题：** Axios 确实是 lockfile 依赖，但扫描未发现任何实际调用；它只能被 ratify 为“已安装依赖”，不能被 ratify 为“已形成代码惯例或必需 HTTP 客户端”。Vue、Vite、Router、Pinia、Element Plus、ECharts 有实际代码证据，Axios 的证据等级较弱。
- **风险：** 把未使用依赖变成长期固定栈，扩大无必要约束。
- **应处置：** 将 Axios 从必须固定的 ratified stack 移至 seed/Deferred，或明确“仅 ratify 版本锁存在，不代表必须使用”。

### 低优先级

#### R-L1：缺少“M0 未核验”的显式防误读声明

- **位置：** Spine 全局。
- **问题：** 早期清单声称 M0 已完成，但最终 PRD与 reality sweep 均要求核验；Spine 虽未直接复述“M0 已完成”，也没有明确声明当前只看到前端原型、生产底座状态未知。
- **风险：** 交接时可能再次把原型仓库等同于完整 brownfield 工程。
- **应处置：** 在结构种子或 Deferred 增加一句现实声明：生产工程/CI/基础设施是否另有仓库必须在实施前核验。

#### R-L2：原型前端目录不能被误认作已完成正式迁移

- **位置：** “最小源码树”把正式前端组织为 `app/domains/components/shared`，与原型实际目录 `views/components/mock/stores` 不同。
- **问题：** 这是合理的目标 seed，但应保持“目标源码树”身份；目前标题“最小源码树”尚可，未直接称既存结构。
- **风险：** 较低；主要是交接语义。
- **应处置：** 可在图注说明“目标 seed；现有原型尚未迁移”。

## 4. 是否错误 ratify 原型/mock/早期候选

结论分三类：

1. **没有错误 ratify 的原型 mock：** 角色选择登录、前端权限、local mock 数据、按钮成功提示、演示阈值/KPI、页面状态和微前端注释均未被当作生产能力。
2. **没有直接错误 ratify 的早期命名技术：** Nacos、Spring Cloud Alibaba、qiankun/无界、具体国密/AES/TDE、Prometheus/Grafana/SkyWalking均未被锁死。
3. **存在证据等级错误：** “网关提供认证事实”、OTLP、outbox/CQRS/不可变事件等被无标签或 `[ADOPTED]` 表述，超出了 brownfield 现实能证明的范围。它们可以继续作为架构选择，但必须与“既有现实”分开标注。

## 5. Finalize 前建议处置顺序

1. 先处理 R-H1、R-H2，消除“历史网关约束”和“设计机制被标为 adopted reality”的证据混淆。
2. 再处理 R-M1、R-M2，使部署图和可观测协议与 `[ASSUMPTION]` seed 的定位一致。
3. 处理 R-M3，避免未使用 Axios 因 lockfile 存在而成为永久不变量。
4. 以 R-L1、R-L2 增加两句现实边界说明，防止实施交接误把原型/M0 当作已完成生产工程。

完成上述处置后，本 reality lens 可判定通过；无需因本轮对账重新设计模块边界或推翻“模块化单体 + 六边形架构”。
