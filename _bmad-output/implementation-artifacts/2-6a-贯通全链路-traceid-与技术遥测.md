---
story_id: "2.6a"
story_key: "2-6a-贯通全链路-traceid-与技术遥测"
epic: "2"
title: "贯通全链路 traceId 与技术遥测"
type: "enabler"
status: "done"
depends_on:
  - "1.1c"
  - "2.3"
requirements:
  - "FR-53（phase/enabler；最终 owner 为 Story 2.6b）"
  - "NFR-24（基础能力；最终证据由 Story 2.6b 闭合）"
ready_when: "日志、指标、trace 和事件字段字典已冻结"
source_estimate: "3d"
baseline_commit: "48b981d2295e8e2f17d26a3d0695548d6388f7fa"
baseline_tree: "aeed79abf664e7e29920e059968fefb07f27dedd"
working_tree_baseline: "上述 Git 基线 + 当前未提交的完整 Story 2.5c 候选实现；不得 checkout/reset 或按 HEAD 重建"
created_at: "2026-08-15T08:57:53+0800"
---

# Story 2.6a：贯通全链路 traceId 与技术遥测

## Status

done

## Story

作为运维人员，我希望跨 HTTP、作业、批次、评估和外部调用定位异常。

## 业务价值

本 Story 建立 Epic 2 的技术可观测底座：让一次受控操作在同步请求、持久作业、数据批次、代表性评估、transactional outbox、事件消费和受信外调之间保持可关联的 W3C trace 上下文，并用最小化、可枚举的技术字段说明“在哪里失败、失败属于哪类、关联到哪次因果链”。它为 Story 2.6b 的角色隔离运行面板和最终隐私验收、Story 2.7a 的降级事实消费提供可信输入，但不代替这些下游 Story。

Epic 2 只发布数据质量与运行事实。在 Epic 3 的门禁通过前，不得为证明 trace 链路而创建生产 `RuleEvaluation`、`Candidate` 或 `Clue`。

## 范围与边界

### 本 Story 包含

- 冻结日志、指标、trace、事件四类遥测的字段字典、基数分类、隐私拒绝清单、W3C 传播规则、采样规则和受信边界。
- 贯通 HTTP 入站/出站、Spring Security、统一错误响应、审计、持久作业、批次、代表性质量/规则评估、outbox、事件消费和自定义 `java.net.http.HttpClient` 外调。
- 让同一因果链中的错误响应、审计事实、结构化日志、span 与事件携带同一 `traceId`，并防止各 Controller 各自解析 header 导致分叉。
- 为授权拒绝、可见失败、审计、幂等 replay、`aggregateVersion` 冲突提供自动化或可复现的 trace 证据。
- 更新受控运行配置、供应链锁、SBOM/provenance 和 release evidence，使新增遥测依赖与导出配置可重复验证。

### 本 Story 不包含

- Story 2.6b 拥有的角色隔离运行面板、告警展示、runbook、跨角色深链负例和 FR-53/NFR-24 最终验收。
- Story 2.7a 的依赖降级 UI 与可执行能力边界，Story 2.7b 的业务重试/fencing/时效策略，Story 2.7c 的全部生产者对账套件。
- Story 2.8a 的完整自然月 99.9% SLI，Story 2.8b/6.5 的灾备与最终恢复演练。
- 以 `traceId` 作为 metric label/tag、将学生标识或业务正文写进 baggage、日志、span attribute 或事件扩展字段。
- 为演示链路新建平行的审计、作业、outbox、指标真相源，或改写既有不可变业务快照的创建 trace。

## Acceptance Criteria

### 规范性验收标准（来自 Epic 基线，逐字保留）

#### AC-2.6a-HAPPY

**Given** 日志、指标、trace 和事件字段字典已冻结

**When** 运维人员执行本 Story 的主流程

**Then** 所有关键链路使用同一 traceId，遥测不含学生明文、证据正文或密钥

**And** 服务端授权、失败可见、审计、幂等和 aggregateVersion 冲突路径必须通过自动化或可复现验收。

> 注：2.6a 只有上述规范性 HAPPY AC，不在需登记七路径基线 AC 的 Story 清单中。以下条目是为实现与验证该 AC 而派生的工程验收护栏，不是新增的产品基线 AC。

### 派生实施验收护栏

#### IA-1：字段字典先冻结

- 在编码传播逻辑前，以机器可校验合同冻结日志、指标、span/trace 和事件字段；每个字段必须声明名称、类型、语义、来源、是否必填、允许值、基数级别、敏感级别、保留/导出规则与 owner。
- 字典必须区分“对象创建 trace”“当前操作 trace”“父上下文/链接”，不得假设所有名为 `traceId` 的历史字段天然相等，也不得改写已参与 hash 的不可变快照。
- 字典中的 trace/metric 证据必须绑定冻结的 `PerformanceProfileVersion` 及 digest；二者是低基数受控常量，不得被调用方任意填写，也不得就地改写 `PP-1.0.0`。
- 字典必须冻结 W3C `traceparent` 的合法性、无效/全零处理、受信入站策略、受信外调 allowlist、sampling、无 baggage/受控 baggage 政策和兼容期 header 规则。
- 若字段字典、隐私拒绝清单或传播信任边界仍有歧义，开发必须停止并发起 correct-course；不得用代码事实反向替代“Ready When”。

#### IA-2：HTTP、安全、错误与审计使用唯一当前上下文

- 合法受信 `traceparent` 被继承；缺失、非法、全零或不受信上下文按冻结策略创建干净 root，并仅记录低基数原因码。
- Spring Security、Controller、统一错误 envelope（`code/message/traceId/fieldErrors[]`）、授权审计和业务审计从同一当前 trace source 读取 `traceId`，禁止各层再次独立生成。
- 授权拒绝、校验失败、冲突和未预期异常都返回/记录可关联的 `traceId`；日志不得出现请求 body、query、Authorization、Cookie、学生明文或证据正文。

#### IA-3：持久作业、批次与代表性评估保持因果链

- 入队/落库时持久化冻结合同要求的 trace 上下文；worker claim 后提取上下文并为本次 attempt 创建 child span，不能只依赖易失 ThreadLocal/MDC。
- 同一因果链的调度、claim、处理、最终化与失败记录可用同一 `traceId` 关联；每次 attempt 使用独立合法 span，且不改变既有幂等、source lock、fence 和失败优先语义。
- 代表性评估使用已有质量判定/合同测试装置证明传播，不创建尚未获准的生产 `RuleEvaluation`、`Candidate` 或 `Clue`。
- 自定义 executor/async 边界显式传播 context；测试同时证明上下文不会泄漏到下一任务。

#### IA-4：outbox 与事件传播真实 W3C 上下文

- owner-local 事务将业务事实、审计、outbox 与 trace 上下文原子提交；遥测导出失败不得回滚或改写业务真相。
- producer 写入合法非全零 `traceparent`；consumer 校验 trace-id 与事件 `traceId` 的合同绑定，提取后创建 consumer span。
- 不再把由 traceId 截取而来的确定性伪 spanId 当作真实 parent；既有合法 fixture 需通过 additive successor/兼容 reader 演进，不能破坏性覆盖历史合同。
- duplicate、old、gap、poison、idempotent replay 与 `aggregateVersion` conflict 路径均产生低基数、可关联的技术遥测，且不绕过 inbox/watermark/幂等/单调版本规则。

#### IA-5：外部调用显式传播且守住信任边界

- 当前自定义 `java.net.http.HttpClient` 调用由统一 instrumentation/injector 包装，在受信目标上注入 W3C `traceparent` 并产生 client span；不得因为 Spring 自动配置只覆盖 builder 而出现假覆盖。
- 保持现有 mTLS、Basic auth、超时、重定向、签名、重试和响应校验行为；不得记录证书材料、Authorization、Cookie、签名输入或响应正文。
- 对非 allowlist 目标不传播内部上下文/baggage；旧 `X-ScholarSense-Trace-Id` 如仍被对端依赖，必须定义可测试的双写兼容期和废弃路径，不能静默替换。

#### IA-6：日志、指标、trace 与隐私合同一致

- 结构化日志固定字段为 `timestamp, level, service, module, traceId, event, code`；额外字段必须在 allowlist 中，字段名与 code/state 使用稳定低基数词汇。
- metric 只使用字典批准的低基数 tags；严禁把 `traceId`、studentId、batchId、URL/query 或任意对象 ID 当作 tag。指标与 sampled trace 的关联使用 exemplar/trace link，而不是高基数 label。
- span 的低/高基数 attributes 分开治理；禁止学生明文、证据正文、密钥、token、授权 header 和未批准 baggage。
- conformance 测试 profile 可使用 100% sampling 取得确定证据；生产 sampling 必须由版本化受控配置决定，不得硬编码 100%。未采样 trace 仍须传播合法 trace context。

#### IA-7：运行与失败模式可控

- `web-api` 与 `worker` 两种运行角色只启用其所需入口/执行器，但使用同一字段合同与 trace kernel。
- 生产 exporter endpoint、协议、超时、sampling 和资源标识进入受控配置；缺失/非法的强制生产配置 fail fast，开发/测试默认值不得冒充生产值。
- collector/exporter 暂时不可用时，业务事务继续遵守原有正确性语义；遥测缓冲有界、不无限阻塞，故障通过健康状态/低基数计数可见且不递归刷日志。
- 所有 timestamp 使用 UTC；生产依赖授时同步，测试使用受控时钟而非 wall-clock sleep。

#### IA-8：证据可重复且不越界

- 自动化或可复现证据覆盖 HTTP、作业、批次、代表性评估、outbox/consumer、外调，以及规范 AC 点名的授权、失败、审计、幂等和 `aggregateVersion` 冲突。
- 隐私负例扫描日志、导出 span、metrics scrape/export 与事件 fixture，证明学生明文、证据正文、密钥/token 不出现；测试不能只检查字段名称而忽略值。
- 新依赖由 Spring Boot 4.1.0 依赖管理，更新 backend lock、SBOM、provenance、离线重放和 release successor；不得引入游离版本。
- Story 完成声明只覆盖 2.6a 基础能力；最终角色隔离面板、完整 PerformanceProfileVersion、自然月 SLI、DR 和所有后续生产者证据保持 `runtimeEvidenceClaim=none` 或等价的未声明状态。

## Tasks / Subtasks

- [x] **Task 0：冻结可观测字段字典与演进策略（IA-1，阻塞后续任务）**
  - [x] 新增版本化、机器可校验的 observability 合同目录，至少包含 log、metric、span/trace、event trace-context、privacy denylist 和 trust-boundary 六部分。
  - [x] 为每个字段登记类型、语义、owner、必填性、基数、敏感级别、允许值、保留与导出策略；提供 schema/checker、valid fixtures 和逐类 invalid fixtures。
  - [x] 绑定 `PerformanceProfileVersion` 与 digest，验证合同 self/digest、未知 signal/field/label/value、PP 漂移和高基数字段 fail closed。
  - [x] 明确 `objectCreationTraceId`、当前 operation `traceId`、`traceparent` 与 span link 的语义；冻结历史 immutable snapshot 不改写、事件合同 additive successor、reader 兼容矩阵。
  - [x] 冻结受信 ingress/egress、无效 header、sampling、baggage、旧 `X-ScholarSense-Trace-Id` 兼容/废弃规则；未获批则 HALT/correct-course。

- [x] **Task 1：建立共享 trace/observation kernel 与受控运行配置（IA-1、IA-6、IA-7）**
  - [x] 在 `backend/pom.xml` 采用 Spring Boot 4.1.0 管理的 OpenTelemetry starter；根据冻结 exporter 选择补充受管理的 metrics registry，不手写版本。
  - [x] 在 shared technical kernel 提供 current trace source、W3C extractor/injector、trusted-target policy、ObservationPort 与 privacy-safe attribute builder；业务模块依赖 port，不直接依赖 exporter SDK。
  - [x] 收敛 `W3cTraceId`：保留兼容解析但消除各入口独立随机生成；对 invalid/all-zero/untrusted 使用统一策略，确保 traceId 为非全零 32 位小写十六进制。
  - [x] 配置精确结构化 JSON 字段、MDC allowlist、UTC、资源 `service/module`、sampling、OTLP endpoint/timeout、bounded export 与 `web-api`/`worker` 角色。
  - [x] 扩展 versioned runtime config schema、示例环境和 startup validation；禁止示例 secret、localhost exporter 或测试 sampling 被默认为生产事实。

- [x] **Task 2：贯通 HTTP、安全、错误与审计（IA-2、IA-6）**
  - [x] 为所有 HTTP ingress 建立一次性 extraction/root 创建与 response correlation；让 security filters、controllers、exception handlers 和 audit 从当前 context 读取。
  - [x] 移除/迁移 audit-operations、identity-access、ingestion-quality、subject-registry 等 Controller 的重复 `traceparent` 手工解析，避免同请求生成多个 traceId。
  - [x] 保持 AD-12 错误 envelope；验证 2xx/4xx/401/403/409/5xx 响应、结构化日志和审计记录中的 traceId 一致。
  - [x] 复用现有审计写入与按 traceId 检索能力；不新建平行审计账本，不放宽服务端对象级/字段级授权。

- [x] **Task 3：贯通 durable job、批次、评估、outbox 与事件消费（IA-3、IA-4）**
  - [x] 为 scheduled/manual trigger、durable queue、claim/attempt、finalization 建立持久 trace context 与 child span；覆盖 web-api 入队、worker 执行和纯 worker 调度入口。
  - [x] 在 `spring.task.execution` 或自定义 executor 上启用/安装 context propagation，并以复用线程测试证明无串 trace。
  - [x] 以 DataBatch/QualitySnapshot 与现有质量资格/恢复链作为真实 representative path；保持 source lock、fence、idempotency、失败优先和 owner transaction 不变量。
  - [x] 演进 `DataBatchCanonicalOutboxFactory` 与 strict decoder：producer 写真实合法 traceparent，consumer 接受同 trace-id 下的任意合法非全零 parent span，而非要求确定性伪 span。
  - [x] 用 additive event contract/fixture 覆盖 producer、consumer、duplicate、gap、poison、replay 和 `aggregateVersion` conflict；禁止回查 latest 补历史事实。

- [x] **Task 4：贯通受信外部调用（IA-5）**
  - [x] 盘点全部自定义 `java.net.http.HttpClient` 适配器及现有 proprietary trace header，统一接入 wrapper/injector，而不是只依赖 Spring 的 auto-configured builders。
  - [x] 对 mTLS、Basic auth、签名与普通 HTTP 各选择至少一条真实适配器路径做 conformance；保持 timeout/redirect/retry/签名输入不变。
  - [x] 测试受信 allowlist 注入、非受信目标不泄漏、旧 header 兼容、外部 4xx/5xx/timeout 与本地 trace 关联。

- [x] **Task 5：交付指标、结构化日志、隐私与全链路验收（AC-2.6a-HAPPY，IA-2—IA-8）**
  - [x] 复用/收敛 `IdentitySyncObservabilityPort`、`MicrometerIdentitySyncObservabilityAdapter`、`MicrometerAuditMetricSink`，避免每模块创建不同命名与标签策略。
  - [x] 对 HTTP、job、batch、evaluation、outbox、event consumer、external client 定义低基数 counters/timers 与 span names；使用 exemplar/link 关联 sampled trace，测试禁止 `traceId` tag。
  - [x] 生成一次完整因果链的可复现 trace bundle，断言相同 traceId、不同合法 spanId、正确 parent/consumer 关系和固定日志字段。
  - [x] 自动化验证授权拒绝、可见失败、审计、幂等 replay、并发 `aggregateVersion` 冲突以及 exporter outage；不得用 mock 成功或手写 fixture 冒充真实链路事实。
  - [x] 对 log、trace export、metric export/scrape、event fixture 做值级敏感数据扫描，覆盖学生明文、证据正文、密钥、token、Authorization、Cookie 与 baggage。

- [x] **Task 6：供应链、发布与证据收敛（IA-8）**
  - [x] 更新 backend dependency lock、SBOM、provenance、runtime-config bundle、release manifest/evidence 的 additive successor，并保留 predecessor 只读。
  - [x] 执行 backend `clean verify`、PostgreSQL 18.4 clean+upgrade/actual-login/raw-DML/concurrency、合同 checker、BMad/project scripts，以及两次 clean offline deterministic release replay。
  - [x] 验证新增 telemetry 依赖、配置与 exporter 在 `web-api`/`worker` 两角色下启动；验证 collector 不可用时业务真相不回滚、队列不无界。
  - [x] 生成 Story 2.6a verification artifact，逐项映射 AC、字段字典版本、证据命令与限制；显式保留 2.6b/2.7/2.8 范围未声明。

### Review Findings

- [x] [Review][Patch] [High] 按用户决策将生产 OTLP endpoint 改为每环境版本化的精确 HTTPS URI，并保留严格 URI 校验 [backend/src/main/java/cn/edu/suda/scholarsense/runtime/ObservabilityRuntimeProfile.java:66]
- [x] [Review][Patch] [High] 按用户决策仅在版本化可信代理 socket 来源与代理注入的精确来源身份同时通过时继承入站 trace，并要求代理清除客户端伪造 header [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceTrustBoundaryFilter.java:42]
- [x] [Review][Patch] [High] 按用户决策由获批的版本化 authority profiles 生成精确 egress host allowlist，允许明确的 dev/test sandbox，并让 stage/prod 拒绝 placeholder [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/ObservabilityKernelConfiguration.java:25]
- [x] [Review][Patch] [High] 按用户决策在现有 durable job 记录上新增向后兼容的完整 `traceparent`，入队时原子持久化并由 worker 创建真实 child；旧记录走显式 clean-root 兼容路径 [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/JdbcSubjectWindowRecomputeStore.java:126]
- [x] [Review][Patch] [High] 按用户决策冻结 V1，新增可识别的 V2 type/schema；successor reader 先双读，消费者通过兼容验收后再切换 producer，且不双发业务事件 [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCanonicalOutboxFactory.java:27]
- [x] [Review][Patch] [High] 非 allowlist 外调仍会转发调用方提供的 `tracestate` [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClient.java:122]
- [x] [Review][Patch] [High] DataBatch `traceparent` 未保证来自真实 PRODUCER child span，且 outbox span 被标成 INTERNAL [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCanonicalOutboxFactory.java:252]
- [x] [Review][Patch] [High] consumer/job/client 的 duplicate、gap、poison、conflict 与失败遥测仍硬编码 `outcome=success` [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/QualityEligibilityEventConsumer.java:75]
- [x] [Review][Patch] [High] 结构化日志允许任意 token 充当 event/code，默认还会产生合同外 `runtime.event` 并可承载学生标识 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/ScholarSenseStructuredLogFormatter.java:23]
- [x] [Review][Patch] [High] 原始 Throwable 被直接交给 span exporter，异常消息/URL/SQL/证书信息可绕过隐私 allowlist [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/MicrometerObservationPort.java:142]
- [x] [Review][Patch] [High] exporter outage 测试没有触发或观察导出失败，运行时也没有合同要求的健康状态/失败计数 [backend/src/test/java/cn/edu/suda/scholarsense/runtime/ScholarSenseApplicationSmokeTest.java:75]
- [x] [Review][Patch] [High] “真实全链路”测试手工创建 server/consumer span、使用内存 work，并故意走非受信外调，未覆盖声明的生产链路 [backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/FullTraceBundleConformanceTest.java:62]
- [x] [Review][Patch] [High] OpenTelemetry starter 传递启用 OTLP metrics exporter，但受控配置只治理 traces，metrics endpoint/timeout/enabled 仍走隐式默认值 [backend/src/main/java/cn/edu/suda/scholarsense/ScholarSenseApplication.java:54]
- [x] [Review][Patch] [High] ReleaseManifest/EvidenceIndex V10 仍以 V9 OCI media type 发布 [./.github/workflows/release.yml:474]
- [x] [Review][Patch] [Medium] 全局 `LOCKS` 升到 backend lock v2，导致 V1—V9 assembly 也不再引用其原有 v1 lock [release/assembly.py:210]
- [x] [Review][Patch] [Medium] 入站 wrapper 将所有多值 HTTP header 折叠为第一个值 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceTrustBoundaryFilter.java:91]
- [x] [Review][Patch] [Medium] 100—999ms exporter timeout 经 `toSeconds()` 变成 `0s` [backend/src/main/java/cn/edu/suda/scholarsense/ScholarSenseApplication.java:89]
- [x] [Review][Patch] [Medium] test runtime 默认 sampling=0.1，与冻结 conformance profile 的 1.0 不一致 [backend/src/main/java/cn/edu/suda/scholarsense/runtime/ObservabilityRuntimeProfile.java:92]
- [x] [Review][Patch] [Medium] observability checker 只校验场景名称/字段，不校验 expectedAction、版本与 cursor 语义 [scripts/check_observability_contract.py:225]
- [x] [Review][Patch] [Medium] W3C parser 的负向判断只拒绝小写 `ff`，会接受非法大写 `FF` version [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/W3cTraceContextCodec.java:15]
- [x] [Review][Patch] [Low] FailSafe scope 每次 context 读取失败都会生成不同 child spanId，单个 operation 的上下文不稳定 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/FailSafeObservationPort.java:28]
- [x] [Review][Patch] [High] 字段字典及 checker 未要求 IA-1 规定的逐字段 `source`，且额外日志字段、metric labels 与 span attributes 只有名称而无完整元数据 [contracts/observability/observability-contract.schema.json:64]
- [x] [Review][Patch] [High] 低基数属性守卫只拒绝七个硬编码 canary，任意其他学号或对象 ID 可作为批准 key 的值进入 metric tags [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/SafeObservationAttributes.java:52]
- [x] [Review][Patch] [Medium] W3C decoder 会把合同外的大写或非 `00` traceparent 当作可信上下文继承，而非按 invalid clean-root [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/W3cTraceContextCodec.java:15]
- [x] [Review][Patch] [Medium] durable recompute 的 checkpoint/complete 失败只标错外层 attempt，已关闭的 evaluate/finalize/publish 子 span 仍保留 `outcome=success` [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/SubjectWindowRecomputeProcessor.java:67]
- [x] [Review][Patch] [High] `outbox.publish` PRODUCER span 在 payload 构造阶段即按 success 结束，后续重验、版本冲突或 owner transaction 回滚仍留下虚假发布遥测 [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCanonicalOutboxFactory.java:312]
- [x] [Review][Patch] [High] “真实全链路”证据使用手工 server span、内存 durable probe、改写 V1 fixture 与 lambda transaction，未经过声明的 JDBC/owner transaction/outbox 生产路径 [backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/FullTraceBundleConformanceTest.java:78]
- [x] [Review][Patch] [Medium] identity metric 白名单接受并由生产调用方传入 `feedId`，适配器却从未映射它，多个 feed 被静默合并为同一时间序列 [backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/MicrometerIdentitySyncObservabilityAdapter.java:408]
- [x] [Review][Patch] [Medium] retention job 异常分支仅设置 error，初始 `outcome=success` 未改写，失败会污染成功率指标 [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/QualitySnapshotRetentionScheduler.java:51]
- [x] [Review][Patch] [Medium] `flush()` 成功或并发成功 export 会无条件清除 exporter 降级状态，即使尚无一次后续 export 证明 collector 已恢复 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/MonitoringSpanExporter.java:25]
- [x] [Review][Patch] [High] V10 observability release gate 只校验证据 ID 存在，不绑定其 kind 与 backend subject，交换 evidence 语义后仍可通过发布门 [release/manifests.py:879]
- [x] [Review][Patch] [High] egress allowlist 只检查首跳 URI；启用自动重定向的受支持 HttpClient 会把已注入 trace headers 转发到未授权主机 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClient.java:137]
- [x] [Review][Patch] [High] 既有 egress 修复仍将调用方传入的任意 URI 当作“获批版本化 authority profile”，stage/prod 仅校验 HTTPS 形状即会传播内部 trace [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClientFactory.java:27]
- [x] [Review][Patch] [High] Finding 22 新增的 span attribute 字典冻结 `scholarsense.*` 名称，但运行时仍原样导出 `module/operation/outcome/aggregateVersion`，字典与真实信号不一致 [backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/MicrometerObservationPort.java:134]
- [x] [Review][Patch] [High] V2 decoder 允许 batch 谱系与 snapshot 策略的 `effectiveAt` 各自独立，但内部事件丢失 snapshot 时间并在 consumer 中重新强制两者相等，合法 V2 事件会被判 poison [backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/QualityEligibilityEventConsumer.java:520]
- [x] [Review][Patch] [High] Finding 12/27 的生产全链路证据仍未闭合：bundle 仍以手工 server parent 和内存 probe 生成，PostgreSQL IT 只证明分段 owner/outbox/decoder，没有一条因果链穿过 HTTP、durable JDBC job、command service、consumer 与受信外调 [backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/FullTraceBundleConformanceTest.java:66]

## Dev Notes

### 实施前必读：真实工作树基线

- 当前分支 `main` 的 HEAD 是 `48b981d2295e8e2f17d26a3d0695548d6388f7fa`，tree 是 `aeed79abf664e7e29920e059968fefb07f27dedd`。
- 当前工作树另外包含尚未提交的完整 Story 2.5c 候选实现：54 个 tracked modified、66 个 untracked，共 120 个 dirty files。依赖清单 `backend/pom.xml`、`frontend/package.json`、`frontend/package-lock.json` 当前未改。
- Story 2.5c 的 detached verification candidate `b253433a9e2406fea0b69e29695b8279f3092f23` 不被当前 branch 包含；不得把它当作可 checkout 的交付基线。
- 开发 2.6a 必须以“HEAD + 当前 2.5c 工作树”为真实输入。禁止 `checkout/reset/clean`、按 HEAD 重建或覆盖用户修改；实施前先重新采集 status 与 hash，并把 2.6a 变更和既存 dirty 集合分开记录。
- 既存不一致（2.5c front matter `in-progress`、正文/sprint 为 `done`，File List 漏列一个测试）不是本 Story 的授权修复范围。

### 架构不变量

- 继续使用 modular monolith + hexagonal 方向 `domain <- application <- adapters`；shared 仅容纳技术 kernel，不把业务模型移进 shared。
- AD-12 错误 envelope 保持 `code, message, traceId, fieldErrors[]`；AD-10 审计事实保留 traceId；AD-13 durable jobs、AD-14 `web-api/worker` 角色、AD-16 全链路 observability、AD-24 event envelope 必须一起满足。
- event envelope 保留 `eventId/schemaVersion/aggregateType/aggregateId/aggregateVersion/occurredAt/traceId/producer/self-contained payload`，trace 演进不得削弱 duplicate/out-of-order/gap 语义。
- 业务 owner transaction 中不做网络 I/O。遥测导出是提交后的技术 side effect，不能决定业务 commit/rollback，也不能改变 `QualityEligibility`、episode/task 或 outbox 的唯一真相。
- 所有生产 timestamp 用 UTC；测试用 trusted/manual clock，禁止 wall-clock sleep 冒充生产时长证据。

### 当前代码事实与已知缺口

- `backend/pom.xml` 已有 Actuator/Micrometer 基础，但没有 Micrometer Tracing/OpenTelemetry OTLP tracing starter，也没有已选择的 OTLP/Prometheus metrics registry；新增依赖必须由 Boot 管理并进入受控 lock/release。
- `cn.edu.suda.scholarsense.shared.trace.W3cTraceId` 能解析 W3C header 或生成 32 位 traceId，但多个 Controller 各自调用它，security/error/audit/controller 可能在同一请求中分叉；`fallbackSeed` 当前没有实际作用。
- `DataBatchCanonicalOutboxFactory` 目前从 traceId 截取字符构造确定性 spanId，`StrictDataBatchQualityEventDecoder` 要求这个精确值；这只是历史事件相关基线，不是真实 span parent。必须用 additive successor + 兼容 reader 演进。
- 现有 published fixture 中 immutable snapshot 的创建 `traceId` 可与发布 operation 的事件 `data.traceId` 不同。字段字典必须明确两者语义，不能强制回写历史 snapshot 或破坏 hash。
- 已有 `IdentitySyncObservabilityPort`、`MicrometerIdentitySyncObservabilityAdapter` 与 `MicrometerAuditMetricSink` 可复用，但命名、标签和 privacy 规则尚未形成全局冻结合同。
- 外调大量使用自定义 `java.net.http.HttpClient`，部分是 mTLS，部分发送 `X-ScholarSense-Trace-Id`；Spring 的 `RestClient.Builder`/`WebClient.Builder` 自动 instrumentation 不会覆盖这些调用。
- 已知 legacy header 发送点至少包括 `HttpIdentityAuthoritySourceAdapter`、`HttpResponsibilityAuthoritySourceAdapter` 与 `HttpResponsibilityFullSnapshotSourceAdapter`；`QualityWorkerProviderAdapters` 的业务请求也携带 traceId。盘点必须覆盖这些路径并保持对端回显/校验、mTLS 与错误映射。
- `ScholarSenseApplication`、`RuntimeConfiguration`、`contracts/config/runtime-config.schema.json` 与示例环境目前没有完整 platform observability 配置；任何新增生产配置都必须 versioned、可校验、可 fail-fast。
- 前端 `frontend/src/app/performance/performance-events.ts` 已有 telemetry allowlist/sanitizer 和 `traceId` allowlist。2.6a 没有新增 UI 的理由；若触及前端遥测，必须复用该治理机制。

### 关键实现判断

- 使用 Spring Boot 4.1.0 的 `spring-boot-starter-opentelemetry` 作为优先 seed，并经项目 release lock 固化；不要同时手工拼接多个不兼容 tracer/SDK。
- Micrometer Observation 的低基数 key-values 会进入 metrics 和 traces，高基数 key-values 只进入 traces。即便如此，高基数值也必须受字典与隐私 allowlist 限制。
- traceId 不进入 metric tags。需要从指标跳到 trace 时使用 exemplar/trace link；测试 profile 100% sampling 仅用于确定性验证。
- Micrometer Tracing 可把 trace/span 放入 MDC，但项目要求的固定 JSON schema仍需显式 include/exclude/rename/customizer，不能接受 logger 默认输出的任意 MDC。
- 自动 HTTP client 传播只适用于自动配置的 builders；当前 custom Java client 必须显式 instrument。对外传播受 trust allowlist 限制，绝不传播内部 baggage/PII/secret。
- 生产 exporter 不可用是 observability degradation，不是业务正确性失败；需要有界、可见、非递归的失败处理。

### 前序 Story 经验

- Story 2.3 已建立非全零 W3C trace/event 基线，并修过全零 span、Java/SQL 规则不一致、outbox/snapshot 绑定与重放时钟漂移；2.6a 必须继承其合同测试而非推倒重来。
- Story 2.5a—2.5c 已建立 owner-local 原子事务、source lock/fence、durable worker、idempotent replay、outbox 与失败优先语义。instrumentation 必须旁路观察这些事实，不得为了 trace 改变锁序、重试次数、claim 生命周期或响应恢复顺序。
- 2.5c review 证明 worker 不能伪造 required-member、对账、样本、SLO 证据。2.6a 的 trace 验收同样必须观察真实执行路径，不能用漂亮但脱离业务事实的 fixture 冒充。
- 现有 PostgreSQL 测试使用 scoped cleanup、固定 claim priority 和确定 surefire 顺序隔离共享 outbox 历史；新增并发/worker 测试应沿用该模式。

### 建议文件落点（开发时以当前树复核为准）

- **NEW** `contracts/observability/**`：versioned field dictionary、schemas、valid/invalid fixtures、compatibility matrix。
- **NEW/UPDATE** `scripts/check_observability_contract.py` 及对应测试；接入现有 contracts/release checker 聚合。
- **UPDATE** `backend/pom.xml`、`contracts/release/backend-lock-*.json`、SBOM/provenance/release successor。
- **UPDATE** `backend/src/main/java/cn/edu/suda/scholarsense/shared/trace/**`；可新增 shared observation ports/adapters，但不得引入业务 owner。
- **UPDATE** `ScholarSenseApplication`、`RuntimeConfiguration`、`contracts/config/runtime-config.schema.json`、示例环境与 deploy 配置。
- **UPDATE** 各 inbound controller/security/error/audit integration 中的手工 trace 解析点。
- **UPDATE** durable job/scheduler/executor、DataBatch outbox factory、strict event decoder 和代表性 external client adapters。
- **UPDATE/NEW** backend unit/integration/PostgreSQL tests、event contract fixtures、release verification artifact。
- 默认 **不改 frontend UI**；只有字典明确要求浏览器遥测兼容时才更新现有 performance telemetry allowlist 与测试。
- 默认 **不新增数据库迁移或 telemetry 业务表**；现有 job/batch/outbox 已有持久 traceId。若实现认为需要新表，先证明规范 AC 无法通过既有 owner 数据与技术 exporter 达成，并走 correct-course。

### 测试与完成门

- 单元：W3C valid/missing/invalid/all-zero/untrusted、attribute allowlist/denylist、metric tag cardinality、structured JSON exact keys、custom client injector。
- 集成：同一请求覆盖 security → controller → error/audit；HTTP → durable job → batch/evaluation → outbox → consumer → trusted external；线程复用不串 context。
- PostgreSQL 18.4：clean+upgrade、actual-login、raw-DML、owner transaction、idempotent replay、并发 aggregateVersion conflict；clean/upgrade fingerprint 一致。
- 隐私：对完整值而非仅字段名做 negative corpus 扫描，包含中文学生姓名/学号样本、证据正文片段、API key/token/cert/Authorization/Cookie。
- 运行：`web-api`、`worker` 分别启动；collector 正常/超时/拒绝/离线；有界队列、无递归日志、业务事务不受 exporter 影响。
- release：两次 clean offline deterministic replay byte-equivalent；新增依赖锁、SBOM、provenance、合同版本与证据互相对账。
- 任一关键 AC、字段合同、隐私负例、基线版本或 traceability 对账失败时，不得将 Story/Release 标为 done。

### 规划估算风险

规划基线估算为 `3d`，但本 Story 横跨依赖、运行配置、HTTP、安全、durable jobs、事件、外调、隐私与 release。开发在 Task 0 冻结字典后应重新核对容量；若 3d 无法在不删减传播、隐私、供应链或证据门的前提下完成，应通过 correct-course 做 additive 拆分，不得静默降低 AC。

### 官方技术来源（实现时复核当前锁定版本）

- Spring Boot 4.1 Tracing：<https://docs.spring.io/spring-boot/4.1/reference/actuator/tracing.html>
- Spring Boot 4.1 Observability：<https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html>
- Spring Boot 4.1 Metrics：<https://docs.spring.io/spring-boot/4.1/reference/actuator/metrics.html>
- Spring Boot 4.1 Structured Logging：<https://docs.spring.io/spring-boot/4.1/reference/features/logging.html>
- OpenTelemetry Context Propagation：<https://opentelemetry.io/docs/concepts/context-propagation/>

### Project Structure Notes

- 遵守现有包根 `cn.edu.suda.scholarsense` 与模块边界；业务模块通过 port 使用 observability，OpenTelemetry/Micrometer 实现留在 adapter/configuration。
- 不创建第二套 `TraceId` 值对象、第二个全局 MeterRegistry 或按模块分叉的字段字典。
- predecessor migration、event schema、config schema、release manifest/evidence 保持只读；演进新增 successor 并提供兼容 checker。
- 预计文件落点是导航而非授权覆盖清单。开发开始时必须重新 `rg` 当前树并完整阅读将更新的文件，尤其要包含未提交的 Story 2.5c 内容。

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.6a（约 L746-L763）]
- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.6b（约 L765-L782）]
- [Source: _bmad-output/planning-artifacts/epics.md#Epic-2 release constraint（约 L3635-L3649）]
- [Source: _bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#FR-53（约 L798-L805）]
- [Source: _bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#NFR-24（约 L1002-L1005）]
- [Source: _bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#audit traceId（约 L324-L331）]
- [Source: _bmad-output/planning-artifacts/requirements-traceability.md#FR-53/NFR-24（约 L155、L185-L212）]
- [Source: _bmad-output/planning-artifacts/architecture/ARCHITECTURE-SPINE.md#AD-10/12/13/14/16/24]
- [Source: _bmad-output/implementation-artifacts/2-3-封账批次并计算质量快照.md]
- [Source: _bmad-output/implementation-artifacts/2-5c-执行恢复观察-回退与质量任务收敛.md]
- [Source: backend/src/main/java/cn/edu/suda/scholarsense/shared/trace/W3cTraceId.java]
- [Source: backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCanonicalOutboxFactory.java]
- [Source: backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/StrictDataBatchQualityEventDecoder.java]
- [Source: contracts/config/runtime-config.schema.json]
- [Source: backend/pom.xml]

## Dev Agent Record

### Agent Model Used

OpenAI Codex（GPT-5）

### Implementation Plan

- 按 Story 任务顺序执行 red-green-refactor：先冻结合同，再建立 shared kernel，随后依次贯通 HTTP、持久作业/事件、外调和端到端证据。
- 将新增 observability 依赖限定在 shared technical kernel 和 adapter/configuration；业务模块只依赖 port，不改变 owner transaction、幂等、fence 或版本单调语义。
- 使用受控时钟、内存 exporter/指标 registry 和本地 HTTP fixture 生成可重复证据，最后收敛 release successor 及两次离线重放。

### Debug Log References

- 2026-08-15：采集 `git rev-parse HEAD`/tree 与 `git status --short`，确认 Story 2.5c 既存脏工作树保持不动。
- 2026-08-15：Task 0 RED 阶段以缺失 `scripts.check_observability_contract` 如期失败；GREEN 阶段 6 个合同单测及 checker 全部通过。
- 2026-08-15：首次全量 Python 合同回归 601 例中发现两个环境性失败（未设 `JAVA_HOME`、已忽略生成目录污染扫描）；以锁定 JDK/Node 并可恢复隔离生成目录复验 `test_delivery_quality` 18/18 通过。
- 2026-08-15：Task 1 RED 覆盖缺失 kernel/runtime 及架构白名单；GREEN 后定向架构 13/13、后端全量 1115/1115 通过。
- 2026-08-15：Task 2 RED/GREEN 覆盖受信/非受信 ingress、legacy bridge、baggage deny、6 类 HTTP outcome 与真实嵌入式服务器；后端全量 1127/1127 通过。
- 2026-08-15：Task 3 RED/GREEN 覆盖持久 job trace 恢复、claim/fence/checkpoint/finalize/outbox 父子 span、线程复用无泄漏、事件任意合法 parent span 及 2.0 兼容结果矩阵；后端全量 `mvn test` 通过。
- 2026-08-15：Task 4 盘点 5 条自定义 Java HTTP 适配器，统一通过 `TrustedHttpClient`；定向 conformance 与后端全量 `mvn test` 通过。
- 2026-08-15：Task 5 使用真实 OpenTelemetry SDK 导出 7-span 因果链，固定 OBS-1 指标名称/低基数标签，并通过故障矩阵、值级隐私扫描、合同 checker 及后端全量 `mvn test`。
- 2026-08-15：Task 6 先以真实新 JAR 触发旧 backend lock runtime drift RED；GREEN 后 lock v2 固定 101 个 runtime 依赖并保持 v1 原始摘要不变。release v10、runtime bundle、SBOM OTel purl 与 verifier/workflow 同步升级。
- 2026-08-15：最终后端 `clean verify` 为 1147/1147；PostgreSQL 18.4 clean+upgrade、BMad/project/合同检查通过；detached candidate `1a0b975b942fd972abd26b5649cf8bb66f041496` 两次 clean offline release replay 得到相同 artifact set `2360a8cb50236511d77032bb4d1d1b8b20fece88f1599433e62c1c399558d7f6`。
- 2026-08-15：Review Patch 4—21 逐项完成 RED/GREEN；新增 V22/V23 持久 trace/event V2 迁移、真实 producer/consumer/client span、动态 outcome、异常脱敏、exporter 健康计数、metrics 受控配置和 release v10/v1-v9 兼容修补。
- 2026-08-15：最终回归为后端 1157/1157、BMad 145/145、项目 clean-root 611/611、release 105/105 + 补充 20/20；PostgreSQL 18.4 inventory=23 clean/upgrade PASS。
- 2026-08-15：最终 detached candidate `43a2986fec71d064e59d4f49ea44e73a37f8aac6` 两次 clean offline release replay 得到相同 artifact set `53b09044515da9558320d2f6aec59868fd4e4a62b4bb60ab57d9d363e3353e0d`。
- 2026-08-15：Review Patch 22 RED 证明字典缺少 `source`；GREEN 后主字段、日志扩展字段、metric labels 与 span attributes 均具备完整元数据，observability 9/9 及项目级 Python 回归通过（两项环境门以锁定 JDK/Node 和可恢复生成目录隔离复验通过）。
- 2026-08-15：Review Patch 23 RED 证明合法 metric key 可承载未登记学号/UUID/对象 ID；GREEN 后改为逐 key 冻结值域并收紧 `aggregateVersion`，定向及后端全量回归通过。
- 2026-08-15：Review Patch 24 RED 证明大写 trace-id 和 `01` version 会被继承；GREEN 后 decoder 仅接受小写 `00-<traceId>-<spanId>-00|01`，兼容 helper 回归与后端 1158/1158 全量通过。
- 2026-08-15：Review Patch 25 RED 分别复现 checkpoint 与 complete 失败时子 span 缺少 failure/error；GREEN 后 evaluate、finalize、publish 在失败传播前正确标错，定向及后端全量回归通过。
- 2026-08-15：Review Patch 26 RED 证明 Factory 直接结束 producer span；GREEN 后 publication scope 保持到重验与 owner transaction 结果，覆盖 denied、rollback failure、aggregateVersion conflict 及真实 span traceparent，定向与后端全量回归通过。
- 2026-08-16：Review Patch 27 RED 证明原“真实全链路”仍是手工拓扑；GREEN 改为 topology-only 隐私证据，并新增 typed factory → PostgreSQL owner transaction → persisted outbox → strict decoder 的实际生产路径。该路径同时暴露并修复 V2 outbox 约束与两个不同语义 `effectiveAt` 的错误跨字段相等要求；PostgreSQL 18.4 clean/upgrade inventory=24 通过。
- 2026-08-16：Review Patch 28—32 分别以缺失 feed 维度、失败 retention outcome、flush/并发误恢复、V10 evidence 语义交换和自动 redirect 外泄负例进入 RED；GREEN 后定向测试全部通过。
- 2026-08-16：最终回归通过：后端 `1169/1169`、BMad `145/145`、release `105/105`、供应链组合 `20/20`、隔离 delivery-quality `19/19`、PostgreSQL 18.4 inventory=24；两个 clean/offline 根各自完成 `611/611` 项目回归与前端 `177 passed / 35 skipped`，artifact set 均为 `7b0372a3a73d28e492377a667a139c565e6a8bb873f06cadc0f4a5fc73a481ca`。
- 2026-08-16：Review Patch 33—36 复验新增 4 项 finding：egress 改为仅接受冻结 authority profile 或 dev/test loopback sandbox profile；span attribute 导出改用字典名；V2 保留 batch/snapshot 独立 `effectiveAt`；PostgreSQL 18.4 新增一条跨越受信 ingress、durable JDBC job、`DataBatchCommandService`、owner transaction/outbox、strict decoder、JDBC consumer 与受信外调的真实因果链。
- 2026-08-16：修复后回归通过：后端 `1168/1168`、PostgreSQL 18.4 `190/190`、BMad `145/145`、release `105/105`、供应链组合 `20/20`、隔离 delivery-quality `19/19`；当前工作树项目回归仅因既有 `frontend/dist`/`node_modules` 生成目录失败 1 项，隔离树同门已通过。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Task 0：交付 `OBS-1.0.0` 六部分字段合同、PP digest/self digest 绑定、W3C/信任/采样/兼容规则、有效全链路 fixture 与 8 类 fail-closed 负例；合同回归通过。
- Task 1：接入 Boot 受管 OpenTelemetry starter，交付统一 W3C/current-trace/observation/privacy kernel、精确 JSON 日志、有界 OTLP/sampling 配置及 4 环境 versioned runtime profiles；后端全量 1115 例通过。
- Task 2：建立 HTTP trust-boundary/correlation filters 与单一 `HttpTraceContext`，统一安全拒绝、Controller、异常 envelope、日志和审计 traceId，并保留 OBS-1.x legacy 响应头。
- Task 3：复用现有 job/batch/outbox 持久 `traceId`，打通 scheduled/manual 入口、durable worker 父子 span、DataBatch 真实 `traceparent`、consumer span 与加法 2.0 兼容契约；保持 fence、幂等与 owner transaction 语义。
- Task 4：交付 exact-host HTTPS allowlist 管治的 `TrustedHttpClient`，所有自定义 Java HTTP 适配器在保留 mTLS/Basic/签名/timeout/redirect 语义的同时生成 client span 并双写 W3C/legacy trace header。
- Task 5：将计数器/耗时指标收敛为 `scholarsense.operation.total` 与 `scholarsense.operation.duration`；SDK topology bundle 证明受控父子关系并承担隐私扫描，生产持久段由后续 PostgreSQL owner/outbox 证据闭合；exporter 失败不改变业务结果。
- Task 6：交付 backend lock v2、受摘要约束的四环境 observability runtime bundle、release manifest/evidence v10 additive successor 和双 clean 离线确定性发布复演；两角色在不可达 collector 下仍保持业务真相且队列上限为 2048。
- 验证边界：完成声明仅覆盖 Story 2.6a 本地可执行可观测闭环；2.6b、2.7、2.8、生产部署与 production-duration evidence 均保持未声明。
- Review Patch 1：将 stage/prod trace exporter 收敛到分环境冻结的 `suda.edu.cn` 精确 HTTPS URI，严格拒绝 `.invalid`、跨环境、端口及 URI 附加成分；定向测试、observability 合同回归和后端全量测试通过。
- Review Patch 2：将 ingress 信任收敛为 `TRUSTED-INGRESS-1.0.0` 的精确代理 socket + 代理注入身份双因子，直连或单因子请求一律清除传播 header；合同、定向测试与后端全量回归通过。
- Review Patch 3：从版本化 identity/responsibility/quality authority runtime profiles 构建精确 origin 信任策略；dev/test 仅允许显式 loopback sandbox，stage/prod 仅允许无占位域名的 HTTPS 端点。合同回归与后端 1150 例全量回归通过。
- Review Patch 4—5：用 append-only V22/V23 将完整 W3C parent 原子持久化，并冻结 V1、双读 V1/V2、单发 V2；旧 durable 记录明确走 clean-root，PostgreSQL owner/fence 不变量保持不变。
- Review Patch 6—11：非信任外调清除全部传播状态；outbox 使用真实 PRODUCER child；consumer/job/client 记录受控 outcome；日志 event/code 严格枚举；span 不再接收原始 Throwable；真实失败 exporter 通过计数器与 `DEGRADED` health 可见。
- Review Patch 12—13：全链路证据改用实际 worker、V2 strict decoder/consumer、受信 HTTP client 与 durable parent，并为 trace/metrics 分别冻结 endpoint、enabled 和毫秒级 timeout。
- Review Patch 14—21：修正 v10 OCI media type、V1—V9/v10 lock 选择、多值 header、亚秒 timeout、test 100% sampling、场景语义 checker、大小写 `FF` 拒绝与 FailSafe context 稳定性。
- 最终发布门补充了仅限精确 dev/test loopback 片段的污染扫描例外，并以同文件 rogue endpoint 负例证明仍 fail closed；双 clean/offline artifact set 字节一致。
- Review Patch 22：字典 schema 现强制每个字段声明 `source`，并将日志扩展字段、metric labels 与 span attributes 从纯名称清单升级为可闭合校验的完整元数据目录；checker、self digest 与 runtime bundle digest 同步更新。
- Review Patch 23：`SafeObservationAttributes` 不再依赖字符形状和少量 canary，而是对每个低基数 key 校验冻结值域；字典同步登记允许值，任意学号、UUID 和对象 ID 均 fail closed。
- Review Patch 24：W3C decoder 收紧为冻结的 canonical version-00 小写格式；大写、未批准 version 或 flags 不再被标准化后继承，而是进入 `INVALID` clean-root。
- Review Patch 25：durable recompute 在 checkpoint 异常上标错 `batch.evaluate`，在 complete 异常上同时标错 `job.finalize`/`outbox.publish`，再由外层 attempt 保留原有 fail/fence 分类。
- Review Patch 26：`DataBatchCanonicalOutboxFactory` 返回可持有的 publication scope，`DataBatchCommandService` 使其跨越 payload、合同/授权重验与 owner transaction；仅当 owner 接受时标 success，replay 标 duplicate，拒绝/冲突/回滚安全标错。
- Review Patch 27：把 SDK 手工 span 测试明确降级为 topology-only 隐私 bundle；新增真实 `DataBatchCanonicalOutboxFactory`、PostgreSQL owner function、持久 outbox 与严格 V2 decoder 证据。V24 加法迁移允许 V2 event type，decoder 保持批次谱系与质量策略两个 `effectiveAt` 的独立语义。
- Review Patch 28：identity sync 指标将 `feedId` 映射为受控 `dependency` 标签，不再把 source 误当 feed；不同 authority feed 不再静默合并。
- Review Patch 29：retention scheduler 仅在成功完成后写 `outcome=success`，异常路径在记录 error 前显式写 failure。
- Review Patch 30：exporter 只有后续非空成功 export 才能恢复；flush/空 export 不清除降级，且完成状态串行化并按 export 序号阻止旧成功覆盖新失败。
- Review Patch 31：V10 release gate 将 `backend-provenance`/`backend-sbom-cyclonedx` 同时绑定到精确 evidence kind 与 backend artifact subject，语义或 subject 交换均 fail closed。
- Review Patch 32：受治理 `TrustedHttpClient` 拒绝启用 JDK 自动 redirect 的 delegate，防止首跳注入的 trace headers 被转发到 allowlist 外主机。
- Review Patch 33：`TrustedHttpClientFactory` 的 stage/prod 信任集只能从类型化、版本化 identity/responsibility authority profile 导出；原始 URI 仅能进入 dev/test 的精确 loopback sandbox profile。
- Review Patch 34：`SafeObservationAttributes` 将内部低基数 key 显式映射为 `service.name` 与 `scholarsense.*` span attributes，指标标签仍保持既有合同键。
- Review Patch 35：`UpstreamQualityEvent` 新增并传递 `snapshotEffectiveAt`，strict decoder 与 consumer 分别校验 batch lineage 和 snapshot policy 时间，不再强制跨语义相等。
- Review Patch 36：将生产全链路证据收敛到 `DataBatchAtomicEvidencePostgreSqlIT` 的实际 PG18.4 路径，生成 `production-full-trace-bundle-1.0.0.json`；删除只扫描测试源文本的伪证据合同测试。

### File List

- `_bmad-output/implementation-artifacts/2-6a-贯通全链路-traceid-与技术遥测.md`
- `_bmad-output/implementation-artifacts/2-6a-verification.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `contracts/observability/observability-contract.schema.json`
- `contracts/observability/observability-contract-1.0.0.json`
- `contracts/observability/event-trace-context-compatibility-1.0.0.json`
- `contracts/observability/fixtures/valid/full-signal-chain-1.0.0.json`
- `contracts/observability/fixtures/invalid/unknown-signal.json`
- `contracts/observability/fixtures/invalid/unknown-field.json`
- `contracts/observability/fixtures/invalid/unknown-label.json`
- `contracts/observability/fixtures/invalid/unknown-value.json`
- `contracts/observability/fixtures/invalid/performance-profile-drift.json`
- `contracts/observability/fixtures/invalid/high-cardinality-label.json`
- `contracts/observability/fixtures/invalid/sensitive-value.json`
- `contracts/observability/fixtures/invalid/untrusted-egress.json`
- `scripts/check_observability_contract.py`
- `scripts/tests/test_observability_contracts.py`
- `backend/pom.xml`
- `backend/src/main/java/cn/edu/suda/scholarsense/ScholarSenseApplication.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/runtime/ObservabilityRuntimeProfile.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/runtime/RuntimeConfiguration.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TelemetryExportMonitor.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/MonitoringSpanExporter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/SpanExporterMonitoringBeanPostProcessor.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/trace/W3cTraceId.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ScholarSenseObservabilityPropertiesTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/architecture/ArchitectureRules.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/architecture/ModuleStructureTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/runtime/ObservabilityRuntimeProfileTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/runtime/RuntimeConfigurationTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/TelemetryExportMonitorTest.java`
- `backend/src/main/resources/db/migration/ingestion-quality/V000022__ingestion-quality__durable_trace_context_v2.sql`
- `backend/src/main/resources/db/migration/ingestion-quality/V000023__ingestion-quality__data_batch_event_trace_v2.sql`
- `backend/src/main/resources/db/migration/ingestion-quality/V000024__ingestion-quality__data_batch_outbox_event_v2.sql`
- `contracts/config/observability-runtime.schema.json`
- `contracts/config/observability-runtime-dev-1.0.0.json`
- `contracts/config/observability-runtime-test-1.0.0.json`
- `contracts/config/observability-runtime-stage-1.0.0.json`
- `contracts/config/observability-runtime-prod-1.0.0.json`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceContext.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceCorrelationFilter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceTrustBoundaryFilter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedIngressAllowlist.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/auditoperations/adapters/inbound/`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/inbound/`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/DataSourceCatalogController.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/subjectregistry/adapters/inbound/SubjectMappingController.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/runtime/ScholarSenseApplicationSmokeTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceCorrelationFilterTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/HttpTraceTrustBoundaryFilterTest.java`
- `contracts/events/ingestion-quality/data-batch-quality-trace-context.schema.json`
- `contracts/events/ingestion-quality/data-batch-quality-trace-context-contract-2.0.0.json`
- `contracts/events/ingestion-quality/fixtures/ordering/data-batch-quality-trace-context-outcomes-2.0.0.json`
- `contracts/events/ingestion-quality/fixtures/invalid/data-batch-quality-trace-context-trace-id-drift-2.0.0.json`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCanonicalOutboxFactory.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCommandService.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/UpstreamQualityEvent.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/QualityEligibilityEventConsumer.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/SubjectWindowRecomputeProcessor.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/StrictDataBatchQualityEventDecoder.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/QualitySnapshotRetentionScheduler.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/SubjectWindowRecomputeProcessorTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/DataBatchCommandServiceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/trace/W3cTraceIdTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/QualitySnapshotRetentionSchedulerTest.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClientFactory.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpIdentityAuthoritySourceAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpResponsibilityAuthoritySourceAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpResponsibilityFullSnapshotSourceAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpRemoteIdentityProviderClient.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/QualityWorkerProviderAdapters.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/architecture/CustomHttpClientObservabilityTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/TrustedHttpClientTest.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/MicrometerIdentitySyncObservabilityAdapter.java`
- `backend/src/main/java/cn/edu/suda/scholarsense/auditoperations/adapters/outbound/MicrometerAuditMetricSink.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/application/FullTraceBundleConformanceTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/adapters/DataBatchAtomicEvidencePostgreSqlIT.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/adapters/inbound/StrictDataBatchQualityEventDecoderTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/Story25cQualityFinalizationJdbcBoundaryContractTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/MicrometerIdentitySyncObservabilityAdapterTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpIdentityAuthoritySourceAdapterTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/identityaccess/adapters/outbound/HttpRemoteIdentityProviderClientTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/QualityWorkerProviderAdaptersTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/FailSafeObservationPortTest.java`
- `backend/src/test/java/cn/edu/suda/scholarsense/shared/observability/ObservabilityPrivacySurfaceTest.java`
- `contracts/config/observability-runtime-bundle.schema.json`
- `contracts/config/observability-runtime-bundle-1.0.0.json`
- `scripts/generate_observability_runtime_bundle.py`
- `contracts/release/backend-lock-2.schema.json`
- `contracts/release/backend-lock-2.0.0.json`
- `scripts/generate_backend_lock_v2.py`
- `scripts/tests/test_backend_lock_v2.py`
- `contracts/release/release-manifest-10.schema.json`
- `contracts/release/evidence-index-10.schema.json`
- `scripts/generate_release_v10_contracts.py`
- `scripts/tests/test_release_v10.py`
- `release/assembly.py`
- `release/backend_lock.py`
- `release/build_release.py`
- `release/generate_manifests.py`
- `release/generate_sbom.py`
- `release/manifests.py`
- `release/verifier.py`
- `scripts/assemble-release-manifest-input.py`
- `scripts/check_backend_lock.py`
- `scripts/check_release_contracts.py`
- `scripts/check_release_manifests.py`
- `scripts/check_sbom.py`
- `scripts/prepare_locked_maven_plugin.py`
- `scripts/verify-release.sh`
- `scripts/tests/test_release_build.py`
- `scripts/tests/test_release_workflows.py`
- `scripts/tests/test_sbom.py`
- `scripts/check_production_pollution.py`
- `scripts/tests/test_delivery_quality.py`
- `.github/workflows/release.yml`

## Change Log

- 2026-08-15：创建 Story 2.6a，状态设为 `ready-for-dev`；冻结规范 AC 与实现边界，记录当前未提交 Story 2.5c 工作树基线。
- 2026-08-15：完成 Task 0，冻结 `OBS-1.0.0` 可观测字段、隐私、W3C 传播、信任边界、采样与事件兼容合同，并新增 fail-closed checker/fixtures/tests。
- 2026-08-15：完成 Task 1，建立 shared observability kernel、Boot-managed OpenTelemetry、结构化日志与受控的分环境 OTLP/sampling 运行配置。
- 2026-08-15：完成 Task 2，将 HTTP ingress、Spring Security、AD-12 错误、结构化日志与既有审计检索收敛到单一 request trace context。
- 2026-08-15：完成 Task 3，打通持久 job/batch/outbox/consumer trace，以真实 W3C parent span 替代确定性伪 span，并用 additive 2.0 契约保留旧 fixture 只读。
- 2026-08-15：完成 Task 4，将全部自定义 Java HTTP 适配器收敛到受信 wrapper，完成 mTLS、Basic、签名和普通 JSON 路径的传播/失败 conformance。
- 2026-08-15：完成 Task 5，收敛指标/标签，交付真实 SDK 全链路 bundle、故障矩阵与隐私扫描。
- 2026-08-15：完成 Task 6，交付 backend lock/runtime bundle/release v10 additive successors、SBOM/provenance 绑定、两角色 exporter 故障验证和可复现验证工件；Story 状态转为 `review`。
- 2026-08-15：处理 Review Patch 1，冻结 stage/prod 精确 OTLP trace endpoint 并移除生产占位域名。
- 2026-08-15：处理 Review Patch 2，以版本化代理 socket 与代理身份双重校验替代可伪造 Host 信任。
- 2026-08-15：处理 Review Patch 3，改为从获批 authority runtime profiles 导出精确 egress origin，并分环境封闭 sandbox、HTTPS 及 placeholder 规则。
- 2026-08-15：处理 Review Patch 4—13，补齐 durable full traceparent、事件 V2、传播清理、真实 span/outcome、隐私安全错误、exporter 健康与 traces/metrics 双通道受控配置。
- 2026-08-15：处理 Review Patch 14—21，修复 release media type/lock 兼容、多值 header、亚秒 timeout、采样、场景语义校验、W3C version 与 FailSafe context 稳定性。
- 2026-08-15：完成 1157 例后端、PostgreSQL 18.4、合同/BMad/project 全回归及最终双 clean/offline release replay；Story 转为 `review`。
- 2026-08-15：处理 Review Patch 22，补齐所有可观测字段的来源与日志/metric/span 表面的完整元数据，并重绑合同及运行时 bundle digest。
- 2026-08-15：处理 Review Patch 23，将低基数属性收紧为逐 key 冻结值域，阻断未登记学号与对象 ID 进入 metric tags。
- 2026-08-15：处理 Review Patch 24，仅继承冻结的小写 W3C version-00 traceparent，合同外输入改走 invalid clean-root。
- 2026-08-15：处理 Review Patch 25，让 durable checkpoint/complete 失败正确更新子 span 的 outcome/error，消除虚假成功遥测。
- 2026-08-15：处理 Review Patch 26，将 outbox PRODUCER span 延长至重验和 owner transaction 结果，正确分类 replay、拒绝、冲突与回滚。
- 2026-08-16：处理 Review Patch 27—32，补齐真实 PostgreSQL owner/outbox 证据、identity feed 维度、retention 失败 outcome、exporter 并发恢复、V10 evidence 语义绑定与 redirect 防泄漏。
- 2026-08-16：以 detached verification candidate `3daf75afa5357956625df7d1c46d1f85e50faf24` 完成两次 clean/offline 确定性发布复演；所有任务和 Review Patch 已完成，Story 转为 `review`。
- 2026-08-16：完成 Review Patch 33—36，关闭 egress profile、span 命名、V2 `effectiveAt` 与真实生产全链路四项高优先级 finding；回归门通过后 Story 转为 `done`。
