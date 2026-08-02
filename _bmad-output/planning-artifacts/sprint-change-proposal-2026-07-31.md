---
title: "Sprint Change Proposal：Story 1.6c 撤权传播范围与下游激活责任纠偏"
date: "2026-07-31"
mode: batch
status: approved
scopeClassification: moderate
triggerStory: "1.6c"
triggerStoryKey: "1-6c-发布撤权-更正与失效传播事实"
recommendedApproach: direct-adjustment
accountableOwner: Hei
approvedBy: Hei
approvedAt: "2026-07-31"
---

# Sprint Change Proposal：Story 1.6c 撤权传播范围与下游激活责任纠偏

## 1. Issue Summary

### 1.1 触发问题

Story 1.6c 的受控 `AC-1.6c-HAPPY` 要求失效事实同时传播到授权、任务、导出和移动会话，并推进所有下游水位；但当前仓库只有 `identity-access` 的身份、组织、责任关系与 current-scope 能力：

- `cluecare`、`collaboration`、`reporting`、`signalevaluation`、`subjectregistry` 当前只有 `package-info.java` 骨架；
- 尚不存在可真实 apply 的公共任务、TransferOrder、ExportJob 或移动端服务端投影；
- 真实责任转移、导出撤销、公共任务生命周期和移动验收已分别由 3.9b、3.14c、5.5、7.2c 拥有；
- 1.7 的完整 role/object/action/scope 授权器也排在 1.6c 之后。

若保持 AC 字面范围并立即开发，只能二选一：

1. 在 1.6c 越权提前实现多个未来领域，破坏 Epic 顺序、模块所有权和既有估算；
2. 用 fixture、planned consumer 或 producer ack 冒充不存在的业务 apply，制造虚假运行证据。

两者均违反受控基线关于唯一写所有权、真实运行证据、消费者 apply 后才可 ack，以及 contributor 不得冒充 owner 完成的规则。

### 1.2 问题类型

- [x] 实施期发现的技术与计划顺序冲突
- [x] Story AC 超出当前可执行依赖
- [x] 运行证据责任需要分阶段归属
- [N/A] 新增产品需求
- [N/A] 战略或 MVP 目标变化

### 1.3 证据

1. `epics.md` 将 3.9b、3.14c、5.5、7.2c 分别设为责任转移、导出失效、公共任务和移动端的真实 owner。
2. `epics.md` 同时规定 Story 只能依赖更早 Story，contributor 不得自行宣称 FR 完成。
3. Architecture AD-2、AD-7、AD-8、AD-24、AD-25 要求模块唯一写所有权、业务 apply 后确认、下一请求即时重算，以及更正水位最终收敛。
4. PRD FR-2 的产品结果是权威身份/责任关系同步与无有效关系时禁止访问；PRD NFR-27 另要求更正与撤权可追踪到下游水位。
5. UX 已规定 Web/WebView 共用服务端状态、对象撤权后下一请求立即失效；移动端不是天然独立的服务端消费者。

## 2. Impact Analysis

### 2.1 Epic 影响

| Epic | 影响 | 结论 |
|---|---|---|
| Epic 1 | 1.6c 只应交付 producer、current-scope 即时 fail-closed、消费者注册/激活协议和当前真实消费者；1.7 接续完整授权 | 修改 Story/证据描述，不改变 Epic 顺序 |
| Epic 3 | 3.9b 承担 Candidate/Clue/workItem 责任转移的真实 apply；3.14c 承担 ExportJob/文件撤销的真实 apply | 增补激活、回放、水位与对账门 |
| Epic 5 | 5.5 承担公共任务撤销/关闭的真实 apply 与双方水位 | 增补失效事件消费与 apply 证据 |
| Epic 7 | 7.2c 承担撤权后的移动读取拒绝、易失状态清除和真机/跨端证据 | 明确默认不登记独立 mobile consumer |
| Epic 2/4/6/8 | 无直接范围变化 | 保持现状 |

Epic 顺序无需调整，也无需新增 Epic。

### 2.2 PRD 影响

PRD 产品范围不变：

- FR-2 仍由身份域交付权威身份/组织/责任关系的同步、更正、撤销和当前授权失效；
- NFR-27 仍要求 15 分钟生效、每日对账与撤权下游水位；
- task/export/mobile 的真实业务效果仍由其既有 owner Story 交付。

因此不修改 PRD 正文，只修正实施基线中“规划 owner”和“运行证据 owner”的表达。

### 2.3 Architecture 影响

Architecture 的目标态不变。AD-7、AD-8、AD-24、AD-25 已支持本次纠偏：

- identity-access 发布版本化事实，不跨模块写表；
- 当前授权即时 fail closed，不等待异步消费者；
- 消费者在本模块业务 apply 后才推进自己的水位；
- 新消费者激活前必须回放并对账。

无需新建 ADR；只需在 Story 基线把“目标态”投影为可执行的分阶段交付。

### 2.4 UX 影响

无页面或交互范围变化。现有 UX 已要求：

- 对象授权失效后下一请求立即拒绝；
- Web 与 WebView 共用服务端 API 和唯一业务状态；
- 登出、换号、撤权后清除当前进程易失状态；
- 断网不读取持久业务缓存。

7.2c 继续负责移动真机和跨端运行证据。

### 2.5 测试、CI 与运行影响

- 1.6c 必须提供事件 schema、lineage/supersedes、消费者注册、连续水位、gap/backfill/reconciliation 的 conformance harness。
- `planned/not-installed` consumer 必须明确 `runtimeEvidenceClaim=none`，不得进入完成分母或被测试伪推进。
- 后续 owner Story 激活 consumer 时，必须补充本模块 inbox、业务 apply、apply record、watermark、consumer-applied fact 和回放/对账测试。
- 当前全量 Maven、PostgreSQL、合同锁、迁移锁和 sandbox 门保持不变。

## 3. Path Forward Evaluation

### Option 1：直接调整现有 Story（推荐）

- 可行性：高
- 实施工作量：中
- 风险：中低
- 时间影响：1.6c 合同与 producer 工作量增加，但避免提前实现四个未来领域
- 结果：保持产品范围与架构目标，运行证据由真实 owner 在激活时产生

### Option 2：回滚已完成 Story

- 可行性：低
- 实施工作量：高
- 风险：高
- 结论：不可取。1.6a/1.6b 已交付的身份、责任关系与动态 fail-closed 是本次纠偏所需基础，回滚不能解决未来消费者不存在的问题。

### Option 3：缩减 PRD/MVP

- 可行性：技术上可行，但无必要
- 实施工作量：中高
- 风险：高
- 结论：不可取。FR-2、NFR-27 以及未来 task/export/mobile 能力仍有明确业务价值；问题是证据时序，不是产品目标错误。

### 推荐决定

采用 Option 1：**1.6c 交付 producer/current-scope + 消费者激活协议；真实 task/export/transfer/mobile 运行证据由各 owner Story 在安装或启用时产生。**

完成语义：

1. 1.6c 可证明当前 identity-access 范围的即时失权和所有“当前版本适用、required、active”消费者的连续水位。
2. `planned/not-installed` 不进入 1.6c 的完成分母，必须显示 `runtimeEvidenceClaim=none`。
3. 任一未来 consumer 在 owner Story 未完成回放、业务 apply、水位和 reconciliation 前不得转为 active/required。
4. mobile 默认是共享服务端 API 的验证 surface，不是独立 consumer；只有未来出现真实 server-side mobile projection 时才登记消费者。
5. 1.6c 不宣称 task/export/mobile 已产生运行副作用，也不以 conformance fixture 关闭这些 owner Story 的运行证据。

## 4. Detailed Change Proposals

以下变更在本提案获 Hei 明确批准后执行。

### 4.1 `epics.md`：补充分阶段消费者激活规则

**位置：** `## 实施与验收规则`

**OLD：**

> 每个 FR 只有一个最终端到端 owner Story；contributing Story 可是更早的前置/phase，也可是更晚的下游消费、hardening 或发布 conformance。contributor 只表示跨 Story 关联，不自行形成执行依赖、不得宣称该 FR 已完成；只有 `dependsOn` 与 `readyWhen` 定义顺序/门槛。

**NEW：**

> 每个 FR 只有一个规划 owner Story；contributing Story 可是更早的前置/phase，也可是更晚的下游消费、hardening 或发布 conformance。涉及尚未安装消费者的跨模块传播时，producer Story 只可完成当前适用 active/required 消费者和激活协议，`planned/not-installed` 必须标记 `runtimeEvidenceClaim=none`。未来消费者的业务 apply、水位、回放与对账由其 owner Story 产生，未完成前不得激活，也不得用 fixture 或 producer ack 冒充运行证据。contributor 不自行形成执行依赖；只有 `dependsOn` 与 `readyWhen` 定义顺序/门槛。

**理由：** 保留单一规划 owner，同时阻止不存在的消费者被计为已完成。

### 4.2 `epics.md`：修正 Story 1.6c 的 readyWhen 与 AC

**OLD `readyWhen`：**

> 更正、撤权、lineage、supersedes 与下游水位契约通过乱序测试

**NEW `readyWhen`：**

> 更正、撤权、lineage、supersedes、消费者 lifecycle/activation、连续水位与乱序/gap/backfill 合同通过批准 checker；Task 0 的受控范围提案已获 Hei 批准

**OLD `AC-1.6c-HAPPY`：**

> **Given** 权威源发布更正、撤销或无效关系版本<br>
> **When** 失效事实传播到授权、任务、导出和移动会话<br>
> **Then** 发布带 lineage 与 supersedes 的更正/撤权事实，并推进所有下游失效水位<br>
> **And** 下一次敏感请求 fail closed，历史事实不改写且异常可按 traceId 对账。

**NEW `AC-1.6c-HAPPY`：**

> **Given** 权威源发布更正、撤销、过期或无效的账号、角色、组织/任职或责任关系版本<br>
> **When** identity-access 原子提交 current projection、失效事实、业务 outbox 与审计<br>
> **Then** 发布带 lineage、直接 supersedes、可选 cause 和每聚合连续 aggregateVersion 的不可变事实；下一次敏感 current-scope 请求立即 fail closed，不等待 relay、远程 apply 或 reconciliation<br>
> **And** 只有当前发布版本适用、required 且 active 的真实消费者在本模块完成业务 apply 后推进连续水位；planned/not-installed 消费者保持 `runtimeEvidenceClaim=none`，历史事实不改写且异常可按 traceId 对账。

**新增 `AC-1.6c-DOWNSTREAM-ACTIVATION`：**

> **Given** task、transfer、export 或未来 server-side mobile projection 尚未由 owner Story 安装<br>
> **When** 运行 1.6c 完成判定<br>
> **Then** 这些消费者不得进入完成分母、不得由 producer 或 fixture 伪推进，也不得生成业务 apply 通过证据<br>
> **And** owner Story 激活消费者前必须从批准起点回放，原子提交本模块 inbox、业务 apply、apply record、自有 watermark 与 consumer-applied fact，并通过 gap/乱序/reconciliation；mobile 若仅共用服务端 API，则只登记 surface verification。

**理由：** 分离当前授权即时失效、异步收敛和未来业务副作用。

### 4.3 `epics.md`：修正 FR-2 与 NFR-27 证据归属

**FR-2 OLD：**

> owner Story `1.6c`；contributing Story `1.6a,1.6b`

**FR-2 NEW：**

> planning owner Story `1.6c`；identity/current-scope contributors `1.6a,1.6b,1.7`；downstream activation evidence `3.9b,3.14c,5.5,7.2c`

**NFR-27 OLD：**

> `1.6a,1.6b,1.6c` — 身份同步、责任对账和撤权水位

**NFR-27 NEW：**

> base producer/current-scope evidence `1.6a,1.6b,1.6c`；真实消费者激活证据 `1.7,3.9b,3.14c,5.5,7.2c` — 每个发布版本只计算当前适用 active/required consumer，未来 consumer 激活前必须回放与对账

**理由：** FR-2 产品 owner 不变，但 NFR-27 运行证据随真实消费者出现而扩展。

### 4.4 `epics.md`：给未来 owner Story 增加激活门

#### Story 1.7

在 `readyWhen` 与 `AC-1.7-HAPPY` 增加：

> 复用 1.6c 的 current-scope/invalidation fence；若建立新的授权读模型消费者，必须登记 owner、required/applicable、起始水位，并完成回放与 reconciliation。完整 RFP 授权仍由 1.7 自身验收，不能由 1.6c 冒充。

#### Story 3.9b

在 `readyWhen` 增加：

> `responsibility.changed` v1 兼容映射、消费者激活起点、Candidate/Clue/workItem apply 与水位合同通过乱序/gap/backfill 测试。

在 `AC-3.9b-HAPPY` 增加：

> clue-care 在同一事务提交 inbox、责任转移、任务更新 outbox、apply record 与自有 watermark；随后发布 consumer-applied fact，identity-access 仅观察 ack，不写 clue-care 水位。

#### Story 3.14c

在 `readyWhen` 增加：

> reporting/export consumer 已从批准起点回放并通过撤权水位、文件撤销、下载重检和 reconciliation。

在 `AC-3.14c-HAPPY/AUTH-DENIAL/CONCURRENCY` 增加：

> export apply 与文件 revoke/下载拒绝完成后才推进 reporting 自有 watermark；producer send、对象存储操作开始或 fixture 不算 apply。

#### Story 5.5

在 `readyWhen` 增加：

> 公共任务消费者完成激活回放，五类真实生产者的撤权/关闭 apply、水位和双方对账通过。

在 `AC-5.5-HAPPY/PRODUCER-CONFORMANCE` 增加：

> 外部任务实际撤销/关闭并在公共任务域提交 apply record 后才推进该 consumer 水位；发送成功、HTTP 2xx 或 producer-observed ack 不算业务 apply。

#### Story 7.2c

在 `readyWhen` 增加：

> consumer registry 将 mobile 标记为 `surface-verification`，除非已有批准的独立 server-side projection；共享 API 的撤权证据由下一请求 deny、易失状态清除、重新认证/授权和真机测试构成。

在 `AC-7.2c-AUTH-DENIAL` 增加：

> 不允许仅凭 producer watermark 宣称移动端已清理；必须在目标 WebView/宿主验证下一读写拒绝、对象数据与进程内草稿清除。

### 4.5 `requirements-traceability.md`

同步 4.3 的 FR-2、NFR-27 行，并在 Story 1.6c 的验收说明中引用：

- `AC-1.6c-HAPPY`
- `AC-1.6c-DOWNSTREAM-ACTIVATION`
- `runtimeEvidenceClaim=none`
- 未来 owner 的 activation/backfill/reconciliation 证据

不把 future Story 改成 1.6c 的执行前置。

### 4.6 Story 1.6c 实施记录

提案批准后，仅在 Story 文件允许修改的区域执行：

1. Task 0 对应 checkbox 标记完成；
2. Dev Agent Record → Debug Log 引用本提案路径、批准人、日期和批准范围；
3. Completion Notes 记录 planning owner 与 runtime evidence owner 分离；
4. Change Log 记录受控范围批准；
5. Story 与 sprint status 从 `ready-for-dev` 转为 `in-progress`。

不在 Dev Story 工作流中擅自改写 Story 的非允许章节。

## 5. Checklist Results

| Checklist | 状态 | 结果 |
|---|---|---|
| 1. Trigger and context | [x] | 触发 Story、技术限制、证据已确认 |
| 2. Epic impact | [x] | Epic 顺序保持；1/3/5/7 增补证据门 |
| 3. Artifact conflicts | [x] | PRD/Architecture/UX 目标不变；Epics/traceability 需改 |
| 4. Path forward | [x] | 选择 Direct Adjustment；不回滚、不缩减 MVP |
| 5. Proposal components | [x] | 问题、影响、建议、精确改动、handoff 已完成 |
| 6. Final review/handoff | [x] | Hei 已明确批准推荐方案；可更新基线并进入实施 |

## 6. Implementation Handoff

### 6.1 范围分类

**Moderate**

理由：不改变产品战略或架构范式，但需要同步调整 Epics、追踪矩阵、Story 实施记录和多个未来 owner 的验收门。

### 6.2 责任分工

| 角色 | 责任 |
|---|---|
| Hei（Accountable） | 批准或驳回本提案；确认分阶段运行证据边界 |
| Developer | 批准后更新 Epics/traceability，执行 1.6c producer/current-scope、合同、迁移、测试与证据 |
| 后续 owner Story | 在 1.7、3.9b、3.14c、5.5、7.2c 激活真实消费者或 surface verification，提供业务 apply、水位与 E2E |
| Code Review | 使用不同 LLM/审查者验证未伪造 planned consumer、未跨模块写表、未把 send/ack 当 apply |

### 6.3 成功标准

1. 1.6c 的合同锁、正反 fixture、乱序/gap/backfill checker 全部通过。
2. 当前授权在本地提交后下一请求立即 deny，不等待异步链路。
3. 只有真实 active/required consumer 计入传播完成。
4. planned/not-installed 全部可见且 `runtimeEvidenceClaim=none`。
5. 后续 consumer 未回放/对账前无法激活。
6. FR-2 planning owner 与 NFR-27 运行证据责任在 Epics/traceability 中一致。
7. 全量 Maven、PostgreSQL clean/upgrade、合同/迁移 lock、sandbox 与 release verification 通过。

## 7. Approval

当前状态：**已由 Hei 批准（2026-07-31）**

批准语义：

> 批准采用“1.6c producer/current-scope + 后续 owner 激活”的直接调整方案；允许按第 4 节更新规划产物与 Story 实施记录，并继续实施 Story 1.6c。不得把 planned/not-installed、fixture、transport ack 或 producer-observed ack 作为 future task/export/mobile 的真实业务 apply 证据。

批准记录：Hei 在 Batch 提案展示后明确回复“批准”。本提案据此允许更新规划基线、Story Dev Agent Record 与变更日志，并继续实施 Story 1.6c。

## 8. Workflow Execution Log

- 2026-07-31：Hei 明确批准推荐方案。
- 2026-07-31：`epics.md` 升级为 2.1.2，补充分阶段消费者激活规则、1.6c AC 和未来 owner 激活门。
- 2026-07-31：`requirements-traceability.md` 升级为 2.1.2，登记本提案并同步 FR-2/NFR-27 证据责任。
- Sprint/backlog：未新增、删除或重编号 Epic/Story，因此无需结构性修改 `sprint-status.yaml`；Story 1.6c 的状态转换由 Dev Story 工作流在实施开始时执行。
- Handoff：Developer 继续执行 Story 1.6c；1.7、3.9b、3.14c、5.5、7.2c 在各自 owner Story 承担真实 consumer activation 或 surface verification。
