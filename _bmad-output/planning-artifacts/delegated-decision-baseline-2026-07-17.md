---
title: 学林知微委托决策与实现准入基线
status: approved
version: 1.1.0
revision: final-readiness-closure
authorityRecordId: AUTH-2026-07-17-001
approvedBy: Hei
approvalRole: 项目总负责人与委托决策人
approvalDate: 2026-07-17
effectiveDate: 2026-07-17
implementationReadiness: ready
runtimeEvidenceStatus: pending-story-execution
supersedes: 外部 owner 待确认清单及 DEC-001—DEC-018 的临时默认值
---

# 学林知微委托决策与实现准入基线

## 1. 授权、边界与证据诚实性

2026-07-17，Hei 明确授权：所有未关闭项按行业通用做法闭环；无法由既有材料唯一确定的事项由执行代理自行裁决、确认并记录；最终关闭全部实现阻塞项。本记录把该授权解释为 **项目实现基线的最终产品/技术决策权**，覆盖 PRD、Architecture、UX、Epics/Stories 及其治理附件。

本基线关闭的是 Definition of Ready（DoR）阻塞，不伪造尚未运行的系统、沙箱、压测、恢复、删除、可访问性或 canary 证据：

- 静态业务/技术/UX/治理参数由本文件批准并立即生效，DEC-001—DEC-018 状态均为 `closed`，G-01—G-09 状态均为 `approved-for-implementation`。
- 需要代码或环境才能产生的证据由下游 Story/Definition of Done（DoD）生成。没有通过时，相应 Story 不得完成、Release Candidate 不得提升；但不得再次把这种未来执行证据当作启动实现的前置外部批准。
- Hei 是本版本的具名批准人和最终 accountable owner；表中的领域角色是实施期 Responsible/Checker。D4 动作在运行时仍必须由不同身份的 maker/checker 执行，Hei 的基线批准不替代该职责分离。
- 如真实校方制度、合同或适用法律给出更严格要求，采用更严格值并创建新版本；不得原位改写本版本或历史证据。

## 2. 全部决策关闭登记

| decisionId | 批准版本/裁决 | 运行验证归属 | 状态 |
|---|---|---|---|
| DEC-001 | `HostIntegrationProfileVersion=HIP-1.0.0`：独立 SPA + host adapter，`/scholarsense/` 相对路由；同一 HTTPS 页面供门户与 WebView；不绑定特定微前端框架 | Story 1.2、7.1 的宿主沙箱、深链、登出、恶意消息与 WebView 测试 | closed |
| DEC-002 | `IdentitySessionPolicyVersion=ISP-1.0.0`：OIDC Authorization Code + PKCE/BFF，令牌仅在服务端；每请求重算授权 | Story 1.6a—1.6c、7.1 的续期、登出、换号、撤权与 CSRF/会话测试 | closed |
| DEC-003 | `RoleFieldPolicyVersion=RFP-1.0.0`：七角色对象/动作/范围矩阵、八类字段投影、合并/冲突算法与 RFP-FIXTURE-1.0.0 冻结；未映射默认拒绝、无模拟换角 | Story 1.7、1.8、3.14b、7.x 的 API/UI 正负向投影测试 | closed |
| DEC-004 | `HighRiskActionPolicyVersion=HRAP-1.0.0`，13 个 actionType 的 D1—D4 映射见高风险动作矩阵；未知动作 D0 | 各动作 owner Story 的七路径、幂等、并发及 maker/checker 测试 | closed |
| DEC-005 | `RuleCatalogVersion=RC-1.0.0`，六条规则的公式、量纲、含边界阈值和排除顺序见规则目录 | Story 3.1a—3.4、4.1a—4.7 的匿名样本、依赖质量、发布/回退测试 | closed |
| DEC-006 | `UXBaselineVersion=UXB-1.0.0`：自定义移动主操作触控目标至少 44×44 CSS px | Story 7.1/7.2c 真机、键盘与可访问性测试 | closed |
| DEC-007 | UXB-1.0.0：0—767 mobile、768—1023 tablet/narrow、≥1024 desktop | Story 1.2、7.1 容器和视觉回归 | closed |
| DEC-008 | UXB-1.0.0：现有校徽 PNG 为受控制品；净空≥直径 1/4；控件高 mobile 44、desktop 36；4/8/12 栅格；圆角 2/4/8 | Story 1.2、7.1 视觉快照与品牌清单 | closed |
| DEC-009 | UXB-1.0.0：禁用未确认辅助色；状态不得仅靠颜色 | Story 7.x 非颜色状态测试 | closed |
| DEC-010 | `CareTerminologyVersion=CTV-1.0.0`：“I/II/III 级核实优先级 + 绝对截止时间”；禁止风险、诊断、失联等自动结论 | Story 3.6、3.8、7.2a 的 API/UI/report fixture 一致性测试 | closed |
| DEC-011 | `PublicIntegrationContractVersion=PIC-1.0.0`：OpenAPI 3.1 + 版本化事件 + transactional outbox + 双 ID + 独立 DeliveryRecord + 幂等/签名/乱序/对账 | Story 1.9、5.1—5.5、6.4 的 provider/consumer 契约与沙箱测试 | closed |
| DEC-012 | `DataContractCatalogVersion=DCC-1.0.0`、`QualityGateVersion=QG-1.0.0`、`QualityRecoveryPolicyVersion=QRP-1.0.0`；17 源 SLO 与 11 dependency 映射冻结 | Epic 2、Story 3.2—3.4、6.5 的契约、补数、对账、恢复演练 | closed |
| DEC-013 | `DRPlanVersion=DRP-1.0.0`：跨故障域 HA、WAL≤5m、每日全备、35 日备份、对象版本/复制、KMS、季度恢复演练；RPO≤15m/RTO≤2h | Story 2.8b 设施实现；Story 6.5 RC 级切换、回切和全域一致性演练 | closed |
| DEC-014 | `ProductionArtifactBaselineVersion=PAB-1.0.0`：JDK 25 LTS、Spring Boot 4.1.0、PostgreSQL 18.4、Node 24.18.0 LTS、Vite 8.1.5、TypeScript 6.0.2 compatibility、Vue 3.5.40；其余精确版本见 §9 | Story 1.1c/1.1d 的锁文件、可复现构建、digest、SBOM、签名、漏洞/许可证与浏览器矩阵 | closed |
| DEC-015 | `RetentionScheduleVersion=RS-1.0.0`：分类保留、legal hold、消费者水位、备份过期和 DeletionReceipt 见 §8 | Story 6.6 的实际删除/匿名化/备份过期演练 | closed |
| DEC-016 | `ES/QP/BC/WVP/CAC/SPM/ACN-1.0.0`：证据、队列、校历、到访、关怀动作、季节专项与学业节点验收词典冻结 | Story 3.7—3.9、4.2—4.4c、4.8、5.3、6.1 的参数化边界测试 | closed |
| DEC-017 | `PerformanceProfileVersion=PP-1.0.0`、`AvailabilityPolicyVersion=AP-1.0.0`；50k 学生、5m 事件/日、1000 并发和固定请求组合 | Story 1.1c、2.6a、2.8a、3.7、7.2c 的原始性能/SLI 证据 | closed |
| DEC-018 | `StrategyGatePolicyVersion=SGP-1.0.0`：效果、噪声、分群、置信、人工复核、canary 与回退门见 §10 | Story 8.1—8.4 的离线评测、canary、人工复核和回退演练 | closed |

所有决策的批准人=`Hei`、批准日期=`2026-07-17`、生效日期=`2026-07-17`、证据 URI=本文件锚点及其规范性附件。状态 `closed` 仅表示参数与职责已冻结，不表示运行验证已通过。

## 3. 宿主、身份与字段授权基线

### 3.1 HIP-1.0.0

- 生产入口为 `/scholarsense/`，静态资源使用相对 base；PC 门户默认以精确 `frame-ancestors` allowlist 的跨源 iframe 集成，WebView 直接打开同一 HTTPS SPA。首版不引入 qiankun/wujie。深链只携带 opaque `workItemId`/route state，不在 URL 放令牌、学生标识或敏感字段。
- host adapter 只接受版本化 JSON envelope：`schemaVersion,eventType,messageId,correlationId,issuedAt,nonce,payload`；严格 origin allowlist、消息 schema、5 分钟重放窗和一次性 nonce。未知事件、未知字段、过期或重复 nonce 均拒绝并审计。
- iframe sandbox 最小权限为 `allow-scripts allow-forms allow-same-origin`，默认禁止顶层导航、弹窗和任意下载；跨源 iframe/WebView 只通过 adapter 通信。bridge 禁止传 access/refresh token、CAS ticket、授权决定或学生敏感数据；只允许 60 秒、单次使用、audience 绑定的 opaque bootstrap code 由后端兑换。
- WebView 禁用调试、file/content URL 访问与 mixed content，启用安全浏览；native bridge 按方法/参数 schema 白名单。移动深链使用 Android Verified App Links/iOS Universal Links，生产不以自定义 scheme 为默认入口。
- 事件最小集合：`host.ready`、`auth.changed`、`navigate.requested`、`logout.requested`、`theme.changed`；请求须在 5 秒内 ack 或显式失败，重放不产生重复导航/登出。

### 3.2 ISP-1.0.0

- 使用 OIDC Authorization Code + PKCE S256 和 BFF；校验 state、nonce、issuer、audience、签名、`exp/nbf/iat`，允许时钟偏差 60 秒。
- access token 5 分钟；会话空闲 15 分钟、绝对 8 小时，过期前 5 分钟提醒；refresh token 服务端保存、单次使用并轮换，重用即撤销 token family。
- 浏览器只持有 `__Host-` 前缀、Secure、HttpOnly、SameSite=Lax、Path=/ 的会话 cookie；所有非安全方法校验 Origin/Referer 与 CSRF token。
- 登出清 BFF 会话并调用 IdP end-session/revocation；换号必须完整登出后重新认证。责任关系/撤权增量 15 分钟内生效；服务端每请求以当前权威关系、对象版本和政策版本重算，前端声明不授予权限。

### 3.3 RFP-1.0.0

- 角色集合固定为七类岗位；一个主体可有多个有效角色，但只形成一个当前身份上下文和单一默认首页。稳定角色 ID 为：`R1-COUNSELOR`、`R2-COLLEGE-MANAGER`、`R3-STUDENT-AFFAIRS`、`R4-SCHOOL-LEADER`、`R5-COLLABORATOR`、`R6-DATA-OWNER`、`R7-PLATFORM-OPS`。
- 对象/动作只采用下表 allowlist；未列对象、动作或 scope relation 默认 deny。表内斜杠是同一 namespace 的逐项展开简写而非 actionId 字符：例如 `transfer.read/accept/process` 精确展开为 `transfer.read`、`transfer.accept`、`transfer.process`，`aggregate.read/export` 展开为 `aggregate.read`、`aggregate.export`；实现 manifest 与审计只能保存展开后的单一 actionId，字面含 `/` 的请求一律 deny。HRAP-1.0.0 仍独立决定高风险动作的确认/复核等级，RFP allow 不能绕过 HRAP。跨策略映射固定为：`delegation.issue/revoke→temporary-grant.issue/revoke`、`whitelist.change→whitelist.create-or-change`、`bulk.execute→bulk-governance.execute`、Rule 对象的 `governance.publish/rollback→rule.publish/rollback`、Strategy 对象的同动作→`strategy.publish/rollback`、`governance-action.record→leader-action.record`；`sensitive-export.create/download`、`quality-fuse.recover`、`transfer.submit` 同名映射。需要 HRAP 但不在此映射或 HRAP 表中的动作按 D0 deny。

| roleId | 对象与 scope predicate | 允许 actionId |
|---|---|---|
| R1-COUNSELOR | 当前权威责任学生或有效 DelegationGrant 内的 Candidate/Clue/CareAction/Observation/Task；本人发起的 Transfer | `care.read`、`candidate.review`、`clue.follow-up`、`observation.manage`、`transfer.submit`、`transfer.read`、`transfer.resubmit`；`delegation.issue/revoke` 仅在自身基础范围且 HRAP 通过 |
| R2-COLLEGE-MANAGER | 本学院汇总；本学院显式督办 workItem 的最小个案投影 | `aggregate.read/export`、`care.read`、`governance-action.record/manage`；最小化由字段矩阵而非另造 actionId 实现 |
| R3-STUDENT-AFFAIRS | 全校规则/标签/专项/治理对象与汇总；个案仅限显式治理 workItem | `governance.read/edit/review/publish/rollback`、`whitelist.change`、`bulk.execute`、`aggregate.read/export`、`governance-action.record/manage`、`audit.search-business-metadata`、经 HRAP 的 `sensitive-export.create/download` |
| R4-SCHOOL-LEADER | 校级/学院汇总投影；禁止个体下钻 | `aggregate.read/export`、`governance-action.record/manage` |
| R5-COLLABORATOR | 当前分配且处于有效任务窗的 TransferOrder 及其最小 purpose 投影 | `care.read`、`transfer.read/accept/process/request-supplement/fill-result/close`；最小化由 purpose 字段 allowlist 实现 |
| R6-DATA-OWNER | ownerBinding 匹配的 source/dependency/QualitySnapshot/恢复任务 | `data-quality.read/repair/reconcile`、经 HRAP 的 `quality-fuse.recover`、`platform.read-source`、owned-source 范围内经 HRAP 的 `sensitive-export.create/download` |
| R7-PLATFORM-OPS | 技术运行、作业、投递、遥测与已批准配置；无业务对象/业务导出 | `platform.read/diagnose/retry/reconcile`、`role-binding.apply-approved`、`audit.search-technical-metadata`；禁止自授业务权 |

- 字段类固定为：`B` 基础 ID/状态/时间/版本、`I` 学生直接身份、`C` 联系方式、`S` 敏感关怀属性、`E` 结构化证据/基线/排除、`N` 人工自由文本、`G` 治理汇总、`T` 技术/审计。投影值仅 `C=clear`、`M=masked`、`H=hidden`：

| roleId | B | I | C | S | E | N | G | T |
|---|---|---|---|---|---|---|---|---|
| R1-COUNSELOR | C | C | C | C | C | C | C | M |
| R2-COLLEGE-MANAGER | C | M | H | H | M | H | C | M |
| R3-STUDENT-AFFAIRS | C | M | H | M | M | H | C | M |
| R4-SCHOOL-LEADER | C | H | H | H | H | H | C | M |
| R5-COLLABORATOR | C | C | C* | C* | M | C* | H | M |
| R6-DATA-OWNER | C | M† | H | H | C | H | C | C |
| R7-PLATFORM-OPS | C | H | H | H | H | H | M | C |

`*` 只允许 TransferOrder 的 purpose/field allowlist 明列字段，否则为 H；其可列字段全集固定为 C*=`studentContactPhone|studentContactEmail`、S*=`referralReasonCode|requestedServiceCode`、N*=`referralSummary|supplementRequestText|resultSummary`。TransferOrder 必须保存 `purposeCode + fieldAllowlist + policyVersion`，实际明文字段=`字段全集 ∩ fieldAllowlist ∩ 当前 purpose/对象授权`；未知字段、空 purpose 或超出全集均 H。诊断/咨询正文、原始证据、网络内容、第三方联系方式与密钥值即使误入 allowlist 也全局 H。`†` 仅 owned-source 的 SubjectMappingException 修复 purpose 可 clear，否则 masked/hidden。`clear` 也只包含已批准的最小 schema；masked 使用与原值长度无关的固定替代格式；hidden 必须从 JSON、导出、缓存、DOM 和无障碍树同时省略键、值及长度。

- 判定顺序固定：校验 effective RFP 版本 → 选择 scope predicate 成立且 action 匹配的角色包 → 合并 allow → 应用 applicable explicit deny/职责分离冲突 → 仅对实际授权同一 object/action 的角色包按 `H > M > C` 取字段最严。无该 object/action 权限的角色包不参与字段降级；组织、责任、有效任务和 DelegationGrant 是可替代 scope anchor，不要求全部同时存在。
- DelegationGrant 的结果=`基础授权 ∩ grant 对象/动作/字段`，有效窗为 `[startAt,endAt)`；它永不扩权。R7 自授业务权、D4 maker=checker、未知角色/对象/动作/字段、缺 RFP/IAM/时钟/密钥或职责冲突全部 fail closed。每次序列化和下载前重检 policy、scope relation、purpose、objectVersion；越界对象与不存在对象使用相同 status/code/envelope。
- `RFP-FIXTURE-1.0.0` 冻结 serverNow 和七个主体，并包含 `CASE-A`（COL-A/R1 责任）、`CASE-B`（COL-B）、`WORKITEM-A`（R2 对 CASE-A 的显式学院督办 scope）、`TRANSFER-A`（R5 分配且 `[start,end)`，purposeCode=`CROSS_DEPARTMENT_CARE`，fieldAllowlist=`studentContactPhone,requestedServiceCode,referralSummary,resultSummary`）、`TRANSFER-B`（未分配）、`REPORT-COL-A`、`REPORT-SCHOOL`、`RULE-1`、`DQ-A`（R6 owned）、`DQ-B`、`JOB-1`。确定 oracle：R1 CASE-A allow/CASE-B deny；R2 仅通过 WORKITEM-A 对 CASE-A 执行最小 `care.read`，无 workItem 的 CASE-A 与 CASE-B 均 deny；R3 RULE-1 allow/CASE-A deny；R4 REPORT-SCHOOL allow/CASE-A deny；R5 TRANSFER-A 在 start 含、end 不含，且只对四个 fixture allowlist 字段 clear，`studentContactEmail/referralReasonCode/supplementRequestText` 与全集外字段 H，TRANSFER-B deny；R6 DQ-A allow/DQ-B deny；R7 JOB-1 allow/CASE-A deny；R1+R2 对 WORKITEM-A/CASE-A 的同 object/action 字段取最严，R1+R7 中无 care 权的 R7 不降级 R1；未知 `care.delete`、未知字段或缺 RFP version 均 deny/H。
- 默认首页按“待处理责任任务 > 治理/质量任务 > 汇总态势 > 系统管理”选择；禁止 UI 模拟换成另一身份。每个响应、导出与拒绝审计保存 policyVersion、适用 rolePackage、scope anchor 和字段投影摘要。

## 4. 公共集成 PIC-1.0.0

- 同步 API 用 OpenAPI 3.1.2；异步事件采用 CloudEvents 1.0.2 + JSON Schema 2020-12，type=`scholarsense.<domain>.<aggregate>.<event>.v1`，兼容变更只加 optional 字段，破坏性变更新 major。
- 所有外发通过 transactional outbox；业务聚合 ID 与外部 ID 分离。DeliveryRecord 主键=`aggregateType+aggregateId+channel+contractVersion`，技术状态仅 `pending|retrying|confirmed|failed`，不得改写业务状态。
- 命令要求 `Idempotency-Key`，保留 90 日；同 key 同 payload 返回原结果，同 key 不同 payload 返回 409。CloudEvent `source+id` 唯一，id=UUIDv7，wire payload≤64 KiB；aggregateType/id/version、contractVersion、correlation/causationId 位于 data。消费者按 source+id 去重；同聚合只接受 watermark+1，旧版视为重复、gap 暂停并对账。
- 服务间使用 mTLS + 短期工作负载身份；回调用时间戳、nonce、body digest 签名，允许 5 分钟偏差。失败指数退避并进入 DLQ；每 15 分钟增量对账、每日全量对账。
- `TransferSlaPolicyVersion=TSP-1.0.0`：urgent 2 小时接收/24 小时反馈，normal 1 个工作日接收/3 个工作日反馈；退回待补充默认不暂停，批准的 pause interval 必须持久化。
- `MetricPublicationPolicyVersion=MPP-1.0.0`：普通分组 n≥10、敏感/交叉分组 n≥20；低于阈值 suppression，禁止通过总计/分项差分反推，百分比同时显示分子/分母/口径版本。

## 5. 数据合同 DCC-1.0.0 与质量门 QG-1.0.0

下表 freshness 的 `99%≤` 表示滚动 30 日达标率；对账为生产 DoD，而不是本次批准已实测达标。

| sourceId | Responsible role | freshness / 对账 SLO | 特殊硬门 |
|---|---|---|---|
| SRC-P0-STUDENT-001 | 学籍主数据 owner | 增量 99%≤4h；每日 06:00 前全量 | 主键/有效区间有效≥99.5%；冲突=0 |
| SRC-P0-RESPONSIBILITY-001 | 组织身份 + 学工责任 owner | 增量 99%≤15m；每日 06:00 全量 | 活跃关系未映射=0；撤权/换号≤15m；对账≥99.9% |
| SRC-P0-ACCOMMODATION-001 | 宿管 + 住宿备案 owner | 变更 99%≤60m；每日 06:00 | 同时点未解决住宿冲突=0；核心字段≥99.5% |
| SRC-P0-CARD-001 | 一卡通 owner | 交易 99%≤15m；D+1 06:00 结算对账 | 事件/主体/餐次/量纲≥99.5%；退款冲正链完整 |
| SRC-P0-CAMPUS-ACCESS-001 | 保卫校门 owner | 事件 99%≤5m；每日 06:00 | eventId 唯一；设备/方向/时间≥99.9% |
| SRC-P0-DORM-ACCESS-001 | 宿管/保卫门禁 owner | 事件 99%≤5m；每日 06:00 | eventId 唯一；楼栋映射≥99.9% |
| SRC-P0-DEVICE-001 | 保卫 + 宿管设备 owner | heartbeat 99%≤5m；故障 99%≤10m | 设备映射 100%；无心跳>15m 显式 fault |
| SRC-P0-LEAVE-001 | 学工请假/实习 owner | 审批/撤销 99%≤15m；每日 06:00 | 类型/区间/状态/版本≥99.9%；冲突=0 |
| SRC-P0-CALENDAR-001 | 校历 owner | 正常提前≥7d；紧急更正≤60m | today-90d..today+180d 每日恰一 dayType |
| SRC-P0-TIMETABLE-001 | 教务课表 owner | 停调课 99%≤30m；每日 06:00 | 活动/地点/选课/effectiveAt≥99.5%；版本倒退=0 |
| SRC-P1-OFFCAMPUS-001 | 学工离校备案 owner | 变更 99%≤60m；每日 06:00 | 与 P0 住宿重叠 100% 消歧 |
| SRC-P1-NETWORK-001 | 信息中心网络 owner | 完整分区 D+1 08:00；到达≥99% | URL/域名/内容字段出现数=0 |
| SRC-P1-ACADEMIC-001 | 教务学业 owner | sealed batch 后 D+1 08:00；更正≤4h | manifest 对账 100%；封账/更正链完整 |
| SRC-P1-CARE-LIST-001 | 学工关爱名单 owner | 变更≤60m；撤销/过期≤15m | purpose/来源/有效期/审核覆盖 100% |
| SRC-P1-PSYCH-DEID-001 | 心理中心 + 隐私复核 | 批次 D+1 08:00 | 允许类别/purpose/有效期 100%；诊断/正文/自由文本=0 |
| SRC-P1-AID-001 | 资助 owner | 批次 D+1 08:00；撤销≤4h | purpose/有效期/审核链 100%；不作为 ECON 命中证据 |
| SRC-P1-WORK-VISIT-001 | 辅导员运营 owner | 提交后 D+1 08:00；到达≥99% | 完整率≥99.5%；禁止进入学生研判特征 |

共同门：schema allowlist/兼容率=100%；禁止字段=0；进入计算记录的 required-field validity=100%，全源有效记录率≥99.5%；P0 主体映射≥99.5%，核心字段≥98%；安全/授权关键源对账≥99.9%，其他≥99.5%；SLO 内到达≥99%；未解决区间冲突、重复业务键、版本倒退=0。Q1/Q2 硬失败立即 fused；其余门连续两个评估窗失败或一次低于 95% 即 fused。单主体歧义只隔离该主体。

11 个稳定 dependencyId 与现有规则目录映射不变，全部为 required `all-of`；任一 missing/fused/recovering、版本不匹配或未知 dependencyId 均使规则依赖 fused，另一个源不得替代。

`QRP-1.0.0`：流式源连续 3 批通过 + 60 分钟观察；日批源连续 2 批通过 + 24 小时观察；从 `max(lastKnownGoodWatermark, now-90d)` 补数并全量对账；分层重算 `max(100 个 subject-window, 不足时全部)` 且预期 mismatch=0；D4 maker/checker 后才从 recovering 变 eligible。任一失败或 24 小时内复发回 fused。超过各 RuleVersion 的 `latestActionableAt` 只修历史证据，不补发过时候选。

## 6. ES/QP/BC/WVP/CAC/SPM/ACN-1.0.0

- ES 支持 `personal-baseline|absolute-threshold|node-fact`。共同字段包括 evidenceId、subjectRef、source/dependency/rule/version、metric/fact、occurred/observed/ingestedAt、window、exclusions、quality snapshot、unit、timezone、lineageRunId、schemaVersion、immutableHash；不适用字段必须 `null + notApplicableReason`。禁止自由文本、心理咨询正文、网络内容和原始身份字段。
- QP：默认每名责任辅导员每工作日人工核实容量=10，可由版本化排班覆盖；`K=min(capacityRemaining, eligibleCount)`。排序固定 `III>II>I, dueAt ASC, generatedAt ASC, candidateId(UUIDv7) ASC`；缺字段 fail closed。初审 III/II/I=2h/8h/2 工作日，首次核实=24h/48h/5 工作日。候选 P95>容量 120% 或积压连续 2 日增长即停止扩大规则/canary。
- BC：Asia/Shanghai、日区间 `[00:00,next 00:00)`，dayType=`workday|weekend|statutory-holiday|makeup-workday|school-holiday|emergency-closure`，覆盖 today-90d..today+180d；缺日、重叠版本、历史原位修改均 fail closed。
- WVP：同卡同楼同方向 5 分钟去重；最短有效停留 5 分钟，最长配对 4 小时；同本地日一次 IN 配其后首个 OUT；跨日/跨楼/缺边/超过 4h 只记 anomaly，不计次数；工作纪实不得进入学生研判 feature。

### 6.1 CAC-1.0.0

`CareActionCatalogVersion=CAC-1.0.0` 的动作状态固定为 `planned → in-progress → completed|cancelled`；允许普通动作同次补录 `planned→completed`，转介与观察必须先进入 in-progress，completed/cancelled 为终态。未知 categoryCode、catalogVersion 或 completionKind 返回 `CARE_ACTION_NOT_ALLOWED`，不创建动作、不迁移 Clue。普通动作不借用 HRAP；`COLLABORATION_REFERRAL` 的 `transfer.submit` 仍独立通过 HRAP D1。

| categoryCode | PRD 类别 | 唯一 completionKind / 完成谓词 | dueAt 来源 | 获得的 Clue 迁移资格 |
|---|---|---|---|---|
| CONVERSATION | 谈心谈话 | `conversation-session-recorded`；当前具权人员记录已实际完成的联系会谈，未联系成功不算 | `min(Clue.firstVerificationDueAt,createdAt+1工作日)`；QP/BC | 当前责任人可显式 `处理中→已跟进`；不自动迁移 |
| ACADEMIC_SUPPORT | 学业帮扶 | `academic-support-delivered`；已交付具体计划或资源，禁止从成绩/排名推断 | `createdAt+5工作日`；CAC/BC | 同上 |
| FINANCIAL_AID_SUPPORT | 资助支持 | `aid-resource-connected`；已连接获批资源或申请渠道，不把资助审批伪作完成 | `createdAt+3工作日`；CAC/BC | 同上 |
| SAFETY_CHECK | 安全核查 | `safety-check-recorded`；完成批准范围的事实核查，不产生危险/失联等自动结论 | `min(Clue.firstVerificationDueAt,createdAt+1自然日)` | 同上 |
| COLLABORATION_REFERRAL | 协同转介 | `transfer-result-confirmed`；匹配 Transfer 已回填且原 Clue 当前责任人确认结果 | `TransferOrder.feedbackDueAt`；TSP | 仅确认后可显式已跟进；submit/接收/delivery confirmed/工单关闭均不算完成 |
| CONTINUED_OBSERVATION | 持续观察 | `observation-review-completed`；完成本轮复查并选择 close/extend/resume-processing | `Observation.reviewAt`；开始后 1—30 自然日 | 启动有效 Observation + in-progress 动作 + 唯一复查任务即可进入待观察；到期不自动关闭 |

每个 `care.action_completed` 保存 `careActionId,clueId,categoryCode,catalogVersion,completionKind,completionEvidenceRef,dueAt,dueAtSourceType,dueAtSourceRef,dueAtPolicyVersion,completedAt,actorId,aggregateVersion`。有效候选截止取最早值；人工只可缩短。工作日缺 BC 时拒绝创建；`completedAt==dueAt` 按时、`>` 为超期但仍允许具权完成并永久保存 wasOverdue。责任转移、代办、重放不重置 dueAt。

状态守卫固定：属实先进入处理中；`处理中→已跟进` 必须有匹配当前 Clue 的 completed CareAction 且由当前责任人显式提交；`处理中/待核实→待观察` 必须原子创建 `[startsAt,reviewAt)` 的 Observation、CONTINUED_OBSERVATION in-progress 动作和唯一复查任务。每个 Observation 必须且只绑定一个 `clueId`；若从多 Clue 专项投影发起，操作者必须显式选择一个当前可见、未终态且参与该投影的 Clue，专项只保存 `sourceContextRef=programId+programVersion+windowId`，不得成为 Observation owner。缺选择或候选超过一个时返回 `OBSERVATION_CLUE_REQUIRED`；选择隐藏、非参与或终态 Clue 时 fail closed。唯一键固定 `clueId+catalogVersion+startsAt+reviewAt`，复查任务键固定 `clueId+observationId+reviewAt`，同一 Clue/窗口跨专项重放返回原对象。恰在 reviewAt 的证据进入复查阶段；延长须完成旧 Observation 并原子创建 successor 与新唯一 taskKey。`待核实→已跟进`、`处理中→已关闭` 非法；噪声可带原因关闭；待观察只能经复查命令关闭或延长。

### 6.2 SPM-1.0.0 与 ACN-1.0.0

`SeasonalProgramMatrixVersion=SPM-1.0.0` 是期限专项只读投影合同，不是第七条规则，也不新增 11 个 RuleDependencyRegistry 项。直连 source 不使用 runtime/production 状态：required source 必须 contract approved/effective、最新 `SourceQualitySnapshot.status=eligible` 且 watermark 在 DCC SLO；只有 RuleVersion 才检查 approved/production/eligible。

`academicCalendarProjection` 是 SPM-1.0.0 的版本化 required 控制投影，不是新增 sourceId/dependencyId。它由教务数据 owner 以 `SRC-P1-ACADEMIC-001 + SRC-P0-CALENDAR-001` 生成，schema 固定为 `termId,termStartAt,examWindowStartAt,resultsSealedAt,graduationTermStartAt,graduationReviewSealedAt,notApplicableReasons,sourceVersions,projectionVersion,sealedAt,effectiveAt`，`projectionVersion=SPM-1.0.0`，所有时间使用 Asia/Shanghai。共同必填为 termId/sourceVersions/projectionVersion/sealedAt/effectiveAt；program-specific 必填为 Admission=`termStartAt`，Exam=`examWindowStartAt+resultsSealedAt`，Graduation=`graduationTermStartAt+graduationReviewSealedAt`。非本 program 字段允许 null，但必须有受控 `notApplicableReasons[field]=NOT_APPLICABLE_FOR_PROGRAM`，不得当作零或缺失。只有两源合同 approved/effective、质量 eligible、水位满足 DCC SLO，投影自身 sealed/effective、termId 唯一，且适用字段分别满足 `examWindowStartAt<=resultsSealedAt`、`graduationTermStartAt<=graduationReviewSealedAt` 时才合格；当前 program 必填缺失、非适用 null 无原因、重叠有效版本、来源更正未封账或适用时序非法均视为 required failure。

| programId | 窗口 / 人群 | required 控制输入 | optional 独立信号 | 进入专项投影 |
|---|---|---|---|---|
| ADMISSION-ADAPT-001 | 新生首学期；`[termStartAt,termStartAt+42自然日)` | STUDENT、RESPONSIBILITY、academicCalendarProjection | NIGHT-001；purpose=`ADMISSION_ADAPTATION` 的 PSYCH-DEID | 2-of-2 |
| EXAM-CARE-001 | 期中/期末/缓考后；`[examWindowStartAt-14日,resultsSealedAt+7日)` | STUDENT、RESPONSIBILITY、academicCalendarProjection | NIGHT-001；ACADEMIC-001 | 2-of-2 |
| GRADUATION-CARE-001 | 毕业学期；`[graduationTermStartAt,graduationReviewSealedAt+7日)` | STUDENT、RESPONSIBILITY、academicCalendarProjection | ECON-012；NIGHT-001；ACADEMIC-001 | 2-of-3 |

required 失败只阻断受影响专项的新投影；optional 缺失标 degraded，不得当作 0、否或正常。只有 eligible 且 affirmative 的独立 signalCategory 计数，同一学业类别的多个节点只计一类；单信号仅留在原 Candidate/Clue 队列，不形成专项优先项或学生结论。专项不创建/复制 RuleEvaluation、Candidate 或 Clue；投影唯一键=`programId+programVersion+subjectRef+windowId`，Asia/Shanghai 半开区间 `[startAt,endAt)`，更正沿 lineage 更新同一投影并保留历史。

心理输入只允许 opaque evidenceRef、category、purpose、有效期和 approvalVersion，禁止诊断、量表分值、咨询事实/正文与自由文本；网络只消费 NIGHT-001 聚合输出。专项读取仍执行 RFP-1.0.0，不可见的参与信号不得泄露其存在性。

`AcademicCareNodeSetVersion=ACN-1.0.0` 固定允许节点：`MIDTERM_CARE_REVIEW`、`FINAL_CARE_REVIEW`、`DEFERRED_EXAM_RESULT_CARE_REVIEW`、`COURSE_COMPLETION_CARE_REVIEW`、`PHYSICAL_FITNESS_CARE_REVIEW`、`THESIS_MILESTONE_CARE_REVIEW`、`GRADUATION_REVIEW_CARE`。仅 sealed/effective 批次且 `careReviewRequired=true` 或 nodeCode 在该 allowlist 时可由 ACADEMIC-001 评估；未封账、缓考进行中、撤销、未知 code 或从分数/排名自行推断均不得生成 Candidate。去重键固定 `studentId+nodeCode+term+batchVersion`，更正以 superseding fact 处理。

## 7. DRP-1.0.0

- PostgreSQL 主/备跨故障域同步/准同步复制；WAL 连续归档且 archive lag≤5m，每日全备、每日恢复校验，备份保留 35 日并加密、不可变、异域复制。
- 对象存储启用 versioning、跨故障域复制和生命周期；KMS 密钥与备份分离，密钥恢复顺序写入 runbook。恢复顺序：身份/授权→主数据→业务库→outbox/投递→对象→搜索/缓存重建→全域对账。
- 目标 RPO≤15m、RTO≤2h；季度至少一次恢复演练，半年至少一次故障域切换/回切。Story 6.5 必须用 RC 数据规模验证 Candidate、Clue、Transfer、Delivery、Export、Audit 水位一致；未通过不得发布。

## 8. RS-1.0.0

| 数据类 | 活跃/保留期 | 到期动作 |
|---|---|---|
| 原始接入暂存/拒绝记录 | 30 日 / 180 日 | 删除；仅保留不可逆聚合质量指标 |
| 规范化源事实、RuleEvaluation、AdmissionDecision | 1 年 | 删除或不可逆匿名化，保留聚合趋势 |
| Candidate、Clue、证据快照、CareAction、Transfer、标签治理 | 结案后 3 年 | 删除/不可逆匿名化；审计保存 receipt |
| 报表与运营快照 | 2 年 | 删除；不可反推的小样本受控聚合可续存 |
| 导出制品 | 7 日或申请更短有效期 | 自动删除，下载链接立即失效 |
| 幂等/投递去重记录 | 90 日 | 删除摘要 payload，保留必要 key/hash |
| 安全与业务审计 | 3 年 | legal hold 除外，到期生成 DeletionReceipt |
| 数据库/对象备份 | 35 日 | 加密过期销毁；不得用备份恢复已过删除期限数据 |

Legal hold 由 dataGovernance owner 创建，必须含 purpose、scope、authority、start/end/reviewAt；只暂停命中对象，到期自动复核。删除作业先检查全部消费者 watermark，再执行在线、索引、缓存、对象与备份生命周期；DeletionReceipt 保存 policyVersion、范围/hash、各消费者水位、执行者、时间、结果和不可删除原因。Story 6.6 必须实测，未通过不得发布。

## 9. PAB/PP/AP-1.0.0

- PAB 精确版本：JDK 25 LTS、Spring Boot 4.1.0、PostgreSQL 18.4、Node 24.18.0 LTS、npm 11.16.0、Vite 8.1.5、Vue 3.5.40、`@typescript/typescript6` 6.0.2、vue-tsc 3.3.7、Vue Router 4.6.4、Pinia 3.0.4、Element Plus 2.14.3、TanStack Vue Query 5.101.2、ECharts 6.1.0、vue-echarts 8.0.1、Vitest 4.1.10、Playwright 1.61.1、axe-core 4.12.1。CI 构建镜像、依赖、浏览器镜像用 digest 固定。Node 26 Current、TypeScript 7.0（Vue/Volar 兼容链未冻结）和预览工具不进入本基线。
- 发布必须有 lockfile、可复现构建、SLSA provenance、CycloneDX/SPDX SBOM、制品签名、critical/high 漏洞处置、许可证 allowlist、浏览器/WebView/视觉/无障碍报告；Story 1.1d 未通过不得提升制品。
- PP 数据规模=50,000 在校生、5,000,000 事件/日、60 日热窗（约 3 亿事件）、1,000 并发活跃会话；15m ramp、15m warm-up、60m steady、15m peak。角色组合 70% 辅导员、15% 学院、5% 协同、5% 校级领导、5% 治理/运维；请求组合 35% 工作台/列表、25% 详情、15% 筛选/看板、10% 状态命令、10% 任务/工单、5% 报表/导出提交。
- 性能环境：生产同构拓扑和数据分布；Edge 当前稳定与前一 major、Chrome 当前稳定与前一 major、目标 App WebView；desktop 4-core/8GB、mobile 4-core/4GB；校园网络 20/10Mbps、RTT 50ms、loss 0.1%，移动网络 10/5Mbps、RTT 100ms、loss 1%；冷/热缓存各 50%；时钟 NTP 偏差≤100ms。UI SLI 必须按 AD-22 的用户起止事件，不用网关时延替代；附加 p75 LCP≤2.5s、INP≤200ms、CLS≤0.1 guardrail。
- AP：Asia/Shanghai 每日 07:00—23:00 业务窗口；校园有线、校园 Wi-Fi、外部移动网络三个独立探针每 60 秒执行 SSO 后首页、列表、详情、安全幂等命令/read-back，至少 2/3 正确才算 good minute。月度 SLI=`good eligible minutes / eligible minutes`；计划维护和外部依赖仍计分母并单列，采样缺口计 bad；目标≥99.9%。NFR-5 必须以完整自然月证据验收；DR 演练不能替代。

技术选择依据采用当前官方支持状态：[Node.js releases](https://nodejs.org/en/about/previous-releases)、[Vite supported versions](https://main.vite.dev/releases)、[Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)、[PostgreSQL 18.4 release](https://www.postgresql.org/docs/release/18.4/)、[TypeScript 6.0](https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/)。

## 10. SGP-1.0.0

- ground truth 只取 14 日内完成的人工核实结果；overall 样本≥1000，每个比较分群≥200；truth coverage overall≥90%、分群≥85%，不足为 inconclusive，不得合并冒充通过。
- Precision@K 的 Wilson 95% CI 下界≥0.70，且相对稳定规则基线的单侧 95% CI 下界≥-0.02；Recall@K 相对基线的单侧 95% CI 下界≥-0.02。
- noise rate 的 95% CI 上界≤0.20，且相对基线增幅上界≤0.02；可比较分群 Precision/Recall 最大绝对差≤0.10，且各分群同时满足非劣门。
- 差异置信用固定 seed 的分层 bootstrap 10,000 次。100% 学生级建议须人工确认，不得自动改变 Candidate、Clue、标签、资助或纪律状态。
- initial canary≤5% eligible reviewers 且≤100 人，至少 14 日并有≥200 次已复核交互；连续两个完整窗口全部通过、D4 maker/checker 后才扩大。
- 任一越权/敏感信息泄漏/自动业务动作、质量 fused，或 noise>0.25、分群差>0.15、Precision 下界<0.65 连续两个日窗，立即停用并回退稳定规则基线。

## 11. Gate 关闭与后续验收

| Gate | 实现准入状态 | 已批准静态证据 | 仍需 Story/Release 生成的证据（非 DoR 阻塞） |
|---|---|---|---|
| G-01 权威附件/发布基线 | approved-for-implementation | 本文件、冲突优先级、PAB/PP/AP/RFP | 1.1c/1.1d、2.8a 的构建、供应链、性能和月度 SLI |
| G-02 宿主/SSO/授权 | approved-for-implementation | HIP/ISP/RFP + RFP-FIXTURE | 1.2、1.6、1.7、1.8、7.1 沙箱、矩阵与负向契约测试 |
| G-03 数据/质量恢复 | approved-for-implementation | DCC/QG/QRP、17 源/11 dependency | Epic 2/3 的逐源契约、补数、对账、恢复实测 |
| G-04 任务/工单/回写 | approved-for-implementation | PIC/TSP/MPP | 1.9、5.x、6.4 provider/consumer 沙箱与对账 |
| G-05 前端/UI | approved-for-implementation | UXB/PAB/PP/AP/CTV | 1.1c/d、1.2、7.x 真机、视觉、无障碍和性能报告 |
| G-06 规则/证据/队列/校历/动作/专项 | approved-for-implementation | RC/ES/QP/BC/WVP/CAC/SPM/ACN | 3.x/4.x 合成边界、动作状态、专项矩阵、质量与 canary/publish 测试 |
| G-07 保留/删除 | approved-for-implementation | RS、legal hold 与 receipt schema | Story 6.6 实际删除、watermark、备份过期、receipt |
| G-08 灾备 | approved-for-implementation | DRP、拓扑、runbook 与目标 | Story 6.5 实际 RPO/RTO、回切、全域对账 |
| G-09 智能发布 | approved-for-implementation | SGP 阈值、基线、分群与人工门 | Story 8.1—8.4 离线评测、canary、复核、回退 |

结论：全部 18 个 DEC 和 9 个实现准入 Gate 已关闭/批准，外部 owner 证据不再阻塞排期或开工。所有运行证据均有唯一 Story/DoD owner；任何实际失败都阻止 Story 完成或发布，不回写为“已通过”。
