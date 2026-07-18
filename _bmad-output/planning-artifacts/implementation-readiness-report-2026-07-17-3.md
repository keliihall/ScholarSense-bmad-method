---
stepsCompleted:
  - step-01-document-discovery
  - step-02-prd-analysis
  - step-03-epic-coverage-validation
  - step-04-ux-alignment
  - step-05-epic-quality-review
  - step-06-final-assessment
inputDocuments:
  - prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md
  - architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md
  - ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md
  - ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md
  - epics.md
  - delegated-decision-baseline-2026-07-17.md
  - open-decisions.md
  - high-risk-action-matrix.md
  - rule-catalog.md
  - requirements-traceability.md
assessmentBaseline: 2.1.0
authorityRecordId: AUTH-2026-07-17-001
overallReadiness: READY
openReadinessBlockers: 0
runtimeEvidenceStatus: pending-story-execution
---

# Implementation Readiness Assessment Report

**Date:** 2026-07-17
**Project:** ScholarSense-bmad-method

## Document Discovery

### Primary normative documents

| Type | Selected document | Size | Modified |
|---|---|---:|---|
| PRD | `prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md` | 95,862 bytes | 2026-07-17 20:24 +0800 |
| Architecture | `architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md` | 52,528 bytes | 2026-07-17 20:24 +0800 |
| Epics & Stories | `epics.md` | 163,856 bytes | 2026-07-17 20:24 +0800 |
| UX behavior | `ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md` | 33,301 bytes | 2026-07-17 20:23 +0800 |
| UX visual system | `ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md` | 18,030 bytes | 2026-07-17 20:23 +0800 |

### Normative companions

`delegated-decision-baseline-2026-07-17.md`、`open-decisions.md`、`high-risk-action-matrix.md`、`rule-catalog.md`、`requirements-traceability.md`。

### Duplicate and missing-file resolution

- 未发现 whole + sharded 重复；规划目录不存在候选 PRD、Architecture、Epic 或 UX 的 `index.md` 分片入口。
- `reviews/`、`reconcile-*`、`extract-*`、PRD addendum 和旧 readiness 报告均为历史/分析输入，不参与当前规范裁决。
- 四类必需文档齐全；UX 的 EXPERIENCE 与 DESIGN 分别约束行为和视觉，是互补脊柱而非重复版本。

## PRD Analysis

### Functional Requirements

下表逐项提取 PRD §6 的全部正式功能需求陈述；各 FR 标题下的“可验收结果”仍是同一需求的规范组成部分，不因本表提取而降级。

| ID | 名称 | 完整需求陈述 |
|---|---|---|
| FR-1 | 统一入口与单点登录 | 所有用户可从数智学工大系统统一门户进入学林知微，并复用学校统一身份认证状态。 |
| FR-2 | 权威身份与责任关系同步 | 平台可按学校权威源同步账号、角色、组织树和辅导员—责任学生关系。 |
| FR-3 | 角色化首页 | 用户登录后进入与其职责匹配的首页、菜单和待办。 |
| FR-4 | 公共消息与待办联动 | 平台可将新候选、临期、超期、转介和质量异常发送到数智学工公共消息与待办能力。 |
| FR-5 | 角色与数据范围控制 | 平台可同时依据角色、组织、责任学生和协同任务限制数据访问。 |
| FR-6 | 字段级展示与脱敏 | 平台可按字段、角色和任务配置明文、脱敏或不可见状态。 |
| FR-7 | 敏感信息按任务授权 | 授权角色仅在存在责任关系或有效协同任务时查看敏感信息。 |
| FR-8 | 全链路审计 | 平台记录登录、查看、检索、导出、跟进、转介、规则变更、白名单、下钻、熔断恢复和越权尝试。 |
| FR-9 | 数据源目录 | 学工管理人员和数据责任人可查看每个数据源的责任部门、业务口径、主键、更新频率、覆盖范围和依赖规则。 |
| FR-10 | 主体标识关联 | 平台可通过学校既定映射将学号与一卡通、校园网、住宿和门禁标识关联到同一学生。 |
| FR-11 | 数据质量指标 | 平台按数据源计算主键完整性、连续性、覆盖率和新鲜度，并展示趋势与异常范围。 |
| FR-12 | 自动熔断与恢复 | 当数据源跌破已发布质量门槛时，平台自动熔断依赖规则；数据恢复后由授权人员确认恢复。 |
| FR-13 | 历史数据与重算 | 平台保留足以支持个人 28/56 日基线和规则复盘的历史窗口，并支持受控重算。 |
| FR-14 | 规则配置 | 学工管理人员可配置阈值、持续时间、变化幅度、时间窗、适用人群、组合条件、排除条件、等级、时限和说明模板。 |
| FR-15 | 规则生命周期 | 规则以三类正交状态管理：治理状态 `draft/review/approved/blocked/retired`，运行状态 `inactive/canary/production/fused/disabled/rolled-back`，质量状态 `eligible/fused/recovering`。草稿与测试属于治理/验证活动，不能被当成运行状态；任何页面、事件和 API 均须分栏返回三类状态。 |
| FR-16 | 个人基线 | 平台可按场景使用个人 28/56 日、前月、前学期或相应历史区间形成基线。 |
| FR-17 | 场景排除与白名单 | 平台在计算前应用请假、实习、假期、校外住宿、设备故障等排除条件，并支持有期限、有原因的白名单。 |
| FR-18 | 规则分数与关怀等级 | 平台按已发布规则计算规则分数并映射 I、II、III 关怀等级。 |
| FR-19 | 单线索优先级与 Top-k | 平台可综合单条线索的关怀等级、时效和数据可信度形成基础优先级；候选初审队列和正式线索 Top-k 队列分别按该优先级排序。 |
| FR-20 | 住宿安全场景 | 平台可识别连续无宿舍/校门活动、夜不归宿或异常晚归晚出，并结合请假、住宿、课表和设备状态排除。 |
| FR-21 | 隐性经济压力场景 | 平台可识别相对本人历史的持续低消费或消费显著下降，并结合有效餐次、请假、离校等条件排除。 |
| FR-22 | 毕业季关怀场景 | 平台可在毕业季综合学业、论文、延毕、消费和作息等独立信号，形成专项优先队列。 |
| FR-23 | 夜间作息场景 | 平台可基于校园网会话时间、时长、流量和接入区域等汇总数据识别相对个人基线的作息变化。 |
| FR-24 | 季节与群体专项 | 学工管理人员可基于既有规则组合配置入学适应、考试季、毕业季等有期限的专项队列。 |
| FR-25 | 候选线索生成 | 平台对已通过数据质量门的 RuleEvaluation 先执行排除、白名单和去重判定；只有 admitted 的 CandidateAdmissionDecision 才可原子创建去重 Candidate、发布 `candidate.generated` 并创建唯一公共待办。 |
| FR-26 | 正式线索字段 | 正式线索包含学生、类别、场景、规则、关怀等级、规则分数、当前工作优先级、独立线索数、数据可信度、生成时间、截止时间、状态、解释和建议。 |
| FR-27 | 结构化证据链 | 每条正式线索按 approved EvidenceSchemaVersion 提供数据源、指标、当前值/事实、comparisonMode、相应比较依据、时间窗、排除结果和质量快照；只有 personal-baseline 模式要求基线与变化幅度，绝对阈值或节点事实不得伪造个人基线。 |
| FR-28 | 可读解释与建议 | 平台依据结构化证据和规则模板生成可读解释，并提供非强制性的关怀建议。 |
| FR-29 | 线索时限与状态 | 正式线索按待核实、处理中、已跟进、待观察、已关闭管理；超期是可与非终态并存的时效标记。 |
| FR-30 | 工作台概览 | 辅导员可分别查看候选待初审、正式线索待核实、处理中、今日已跟进、超期和今日核实容量。 |
| FR-31 | Top-k 任务队列 | 辅导员可按当前工作优先级、截止时间、类别、状态和学生筛选任务。 |
| FR-32 | 核实反馈 | 辅导员可记录联系情况、核实结果、噪声原因、关怀方式、是否转介、持续观察和备注。 |
| FR-33 | 持续观察 | 辅导员可为需继续关注的学生设置观察周期、关注点和复查时间。 |
| FR-34 | 权威责任转移 | 在权威责任关系变化时，平台可将未完成 Candidate、正式线索和待办转移给新责任人并保留交接记录；有期限授权代办由 FR-62 独立管理。 |
| FR-35 | 学生基础档案 | 授权用户可查看学生基础信息、学籍状态、责任辅导员及与当前任务相关的联系方式。 |
| FR-36 | 动态信号与历史时间线 | 平台按学业、经济、安全展示个人基线、历史线索、核实结果和关怀动作时间线。 |
| FR-37 | 身份等级与业务标签治理 | 平台分别管理身份等级与业务标签，记录来源、用途、可见角色、生效时间、失效时间和审核状态。 |
| FR-38 | 标签使用反馈 | 用户可报告标签过期、错误或不适用于当前任务，由责任部门处理。 |
| FR-39 | 发起转介 | 辅导员可从正式线索向心理、资助、教务、保卫或学院发起转介。 |
| FR-40 | 转介状态流转 | 转介支持待接收、处理中、待补充、已回填、已关闭和退回状态。 |
| FR-41 | 处理结果回填 | 协同处置人员可回填处理结果、后续建议和是否需要发起人继续行动。 |
| FR-42 | 集成式转介 | 当目标部门使用既有业务系统时，学林知微可通过学校既定接口交换工单状态和结果。 |
| FR-43 | 学院成长总览 | 学院管理人员可查看本学院新增、待核实、处理中、超期、闭环率、噪声率和场景分布。 |
| FR-44 | 超期督办 | 学院管理人员可对临期、超期和无责任人的事项发起督办并跟踪结果。 |
| FR-45 | 领导驾驶舱 | 学校领导可查看全校汇总指标、趋势、场景分布、学院对比和资源压力提示。 |
| FR-46 | 周报与月报 | 平台可按统一口径生成周报/月报，包含新增、闭环、按时率、噪声、超期、协同成效和数据质量。 |
| FR-47 | 规则运营复盘 | 学工管理人员可按规则、场景、学院和人群查看命中、有效、噪声、排除、熔断和反馈分布。 |
| FR-48 | 结构化解释基线 | 首期使用规则模板与结构化证据生成解释，任何解释均可回溯到规则版本和证据。 |
| FR-49 | 增强建议灰度 | 平台可在授权范围内灰度生成关怀路径或谈心建议，并明确标注为建议。 |
| FR-50 | 自然语言问数 | 授权管理人员可使用自然语言查询已定义的汇总指标和态势。 |
| FR-51 | 模型/策略发布关卡 | 增强策略发布前必须通过数据质量、效果、分群表现和人工业务复核。 |
| FR-52 | 稳定基线与回退 | 平台保留稳定规则基线，并可将增强策略一键回退。 |
| FR-53 | 运行状态监控 | 运维和数据责任人可查看数据任务、规则计算、消息、接口和关键用户路径的运行状态。 |
| FR-54 | 业务连续性处置 | 平台在依赖系统暂时不可用时可保留任务、重试幂等操作并向用户说明降级状态。 |
| FR-55 | 移动端轻量处置 | 辅导员和协同处置人员可从学校统一移动入口查看待办、线索摘要与证据摘要，并完成快速核实、结果记录、持续观察或协同处理。 |
| FR-56 | 进宿舍工作统计 | 授权用户可查看辅导员进出责任学生宿舍楼栋的周度和月度工作统计，该统计只服务工作纪实与运营参考。 |
| FR-57 | 学业关怀场景 | 平台可按期中、期末、缓考、课程完成、体测、论文和毕业节点生成学业关怀候选，并在毕业季与其他独立信号合证。 |
| FR-58 | 跨类别独立线索合证 | 平台可在首期完整上线阶段识别同一学生在 7 日内出现的两类独立线索，并提升候选或正式线索的合证优先级。 |
| FR-59 | 数智学工任务与关怀结果回写 | 平台向数智学工公共任务/流程能力回写 Candidate、正式线索、督办和转介的共享任务状态，以及经字段最小化的关怀/协同结果摘要。 |
| FR-60 | 运营指标发布与回写 | 平台向数智学工运营域发布经授权的版本化汇总运营指标，不回写个体明细。 |
| FR-61 | 改进计划与资源调配闭环 | 学院管理人员和学校领导可从汇总态势创建改进要求或资源调配行动，并跟踪后续效果。 |
| FR-62 | 授权代办 | 具权人员可在不改变权威责任关系的情况下，对指定对象和动作授予有期限的代办权限。 |

**FR inventory:** 62/62，编号连续、无重复、无缺号。

### Non-Functional Requirements

- **NFR-1 目标容量：** 规划包络至少支持 50,000 名在校生、每日数百万条事件及最高 1,000 峰值并发用户；正式容量结论必须引用 approved PerformanceProfileVersion 中的精确事件数、并发与角色/请求组合。
- **NFR-2 可操作内容时延：** 工作台、线索列表和详情从用户导航/操作开始，到必需数据、当前授权投影、关键控件和可访问名称完成并发出 `ui.content-ready` 为止，在 approved PerformanceProfileVersion 下 P95≤2 秒；骨架屏不算完成。
- **NFR-3 看板与导出：** 看板从筛选动作开始，到图表、等价表格和辅助技术反馈全部更新为止 P95≤3 秒；所有报表/明细导出均采用异步作业，不阻塞交互请求。
- **NFR-4 场景处理时延：** [假设 A-5] 安全类事件完成平台入库后 30 分钟内完成 Candidate 创建，`candidate.generated` 后 5 分钟内确认责任待办；学业按关键成绩节点计算；经济类至少每日增量更新、每月完成正式规则结算。
- **NFR-5 月度可用性：** approved AvailabilityPolicyVersion 定义的 `Asia/Shanghai` 业务时段自然月可用性≥99.9%，由其分子/分母、采样缺口和维护/外部依赖单列规则下的连续 SLI 数据证明；单次 DR 演练不能代替该证据。
- **NFR-6 恢复目标：** [假设 A-1] 核心业务数据与任务状态 RPO≤15 分钟，核心服务 RTO≤2 小时，仅由按批准 DRPlan 执行的恢复演练证明。
- **NFR-7 故障隔离：** 单一数据源、生成式能力或报表任务故障不得阻断统一身份、人工工作台和既有任务处理。
- **NFR-8 可恢复操作：** 规则、策略和集成操作必须支持幂等、持久重试、灰度、对账和回退。
- **NFR-9 质量门槛：** [假设 A-2] P0 主体标识完整率≥99.5%，核心场景字段覆盖率≥98%，数据新鲜度达标率≥99%。
- **NFR-10 质量熔断：** 不满足已发布场景门槛时必须熔断，禁止通过降低门槛静默继续产出 RuleEvaluation、Candidate 或正式线索。
- **NFR-11 证据质量快照：** 每条 Candidate 和正式线索保存质量快照、规则版本、数据窗口和数据截止时间。
- **NFR-12 分类、保留与销毁：** 按学校批准的数据分类分级、保留删除和密码应用方案建设；生产发布必须引用 RetentionScheduleVersion。缺少批准版本时，生产业务数据、导出和备份 fail closed，不得自行采用默认期限。
- **NFR-13 传输、存储与密钥：** 全链路传输保护，敏感字段按批准方案加密存储，密钥集中管理、版本化且不进入日志或业务事件。
- **NFR-14 输入输出安全：** 所有外部输入在服务端校验，数据访问使用参数化查询，输出按当前对象与字段授权投影并避免泄露不可见字段的键或长度。
- **NFR-15 审计完整性：** 审计记录防篡改并至少保留 6 个月；全网时间同步，关键事件使用统一时间口径；返回敏感数据前必须持久化读取审计。
- **NFR-16 零容忍安全结果：** 越权访问、无授权明文导出和绕过审计的关键业务操作应为零。
- **NFR-17 Web 一致性：** Web 管理端适配统一门户和已批准 UI 规范，支持发布基线声明的桌面视口、浏览器和 WCAG 2.2 AA。
- **NFR-18 移动一致性：** 移动轻量任务适配统一移动端设计规范；模块不创建独立底部导航或与学校入口竞争的品牌壳层。
- **NFR-19 视觉基准：** Web 以 1440×900 px 基准校验并覆盖 1366×768，品牌主色 `#AF251B`；移动以 375 px 宽基准校验，主色 `#D03D37`；未批准的蓝绿科技色不得替代学校品牌主色。
- **NFR-20 品牌层级：** 层级固定为“学校 → 数智学工大系统 → 学林知微 → 观澜智核”；校徽使用批准源文件，等比、完整、无裁切、无重绘，不重复堆叠。
- **NFR-21 非颜色唯一编码：** 等级、状态、错误和质量不得只靠颜色表达；关键操作具有文字标签、图标/结构和明确反馈。
- **NFR-22 可操作无障碍：** 表格和表单支持键盘、清晰焦点、错误摘要、筛选回显、空状态和焦点恢复；图表提供等价表格；状态变化使用适当 live region；200% 缩放无关键内容丢失，并尊重 reduced-motion。
- **NFR-23 关怀性语言：** 页面文案使用中性、可解释、非诊断语言，避免污名化、恐慌或把信号描述为学生结论。
- **NFR-24 全链路关联：** 关键链路贯通 traceId，日志、指标和告警可关联到 HTTP、作业、数据批次、规则评估、outbox 和外部调用，且技术遥测不含学生明文或证据正文。
- **NFR-25 配置可维护性：** 规则与产品配置不依赖应用代码发布即可完成受控、版本化、可回退变更。
- **NFR-26 指标治理：** 关键指标具有唯一 metricId、定义、负责人、计算周期和版本；报告口径变更可追溯且不同版本不得直接比较。
- **NFR-27 身份同步：** 身份、组织和责任关系从权威源版本可见到授权生效≤15 分钟；全量对账每日完成一次，更正与撤权可追踪到下游水位。
- **NFR-28 规则与数据验收门：** 数据质量门使用 A-2；每个规则场景引用 `rule-catalog.md` 中的 `ruleId + version`，期限专项引用 SPM-1.0.0，学业节点引用 ACN-1.0.0。blocked、无生效日期、依赖合同未关闭或矩阵输入不合格时不得进入对应生产验收/专项投影。
- **NFR-29 跨端状态同步：** 从服务端事务提交的 `committedAt`，到另一在线客户端应用同一或更高 aggregateVersion、呈现可操作状态并发出 `ui.state-observed`，P95≤5 秒；使用服务端同步时钟。异步公共消息遵循 A-5。
- **NFR-30 Web 验收矩阵：** 覆盖 1440×900 基准、1366×768 最低桌面视口及发布基线声明的 Edge/Chrome 版本；版本不得以“最新”作为不可复现条件。
- **NFR-31 移动验收矩阵：** 覆盖学校统一 App 发布基线声明的 iOS/Android WebView、宿主版本与 375 px 宽基准，无横向页面滚动。
- **NFR-32 性能证据 Profile：** 压测采用目标 1000 并发与每日数百万事件，并冻结 PerformanceProfileVersion、设备、浏览器/WebView、宿主、网络、冷热缓存、数据分布、场景窗口、采样点和失败归因。网关时延只作诊断，不替代 NFR-2/NFR-3/NFR-29。
- **NFR-33 发布基线：** 生产依赖、浏览器/WebView、UI token、规则、QueuePolicy、BusinessCalendar、WorkVisitPolicy、CareActionCatalog、SeasonalProgramMatrix、AcademicCareNodeSet、RetentionSchedule、RoleFieldPolicy、StrategyGatePolicy、QualityRecoveryPolicy、EvidenceSchema、PerformanceProfile、AvailabilityPolicy、TransferSlaPolicy、MetricPublicationPolicy、公共契约和受控枚举均进入同一版本化发布基线，保存生效日期与证据。
- **NFR-34 禁止持久化离线业务数据：** 首期禁止在 localStorage、IndexedDB、Cache Storage、Service Worker、持久化 Pinia 或文件系统保存待办、学生对象、证据、Candidate、Clue、工单摘要或授权投影。断网时只允许当前进程内未提交表单状态；登出、换号、刷新或 WebView 结束即清除。恢复网络后必须重新认证、重算授权和刷新 aggregateVersion，才能读取或提交。

**NFR inventory:** 34/34，编号连续、无重复、无缺号；每项均有 PRD §9.9 的运行证据 owner 与最低证据定义。

### Additional Requirements and Constraints

#### Business invariants

| ID | 完整约束 |
|---|---|
| BR-1 | 线索而非结论；重大行动必须由具权人员人工决定并审计。 |
| BR-2 | 门禁判定先于 Candidate；拒绝判定不发布 `candidate.generated`、不启动时钟或待办。 |
| BR-3 | Candidate 只可接纳、拒绝或合并；接纳与责任变化不重置截止时钟。 |
| BR-4 | 属实核实必须进入处理中；完成受控关怀动作后才可进入已跟进/待观察；关闭有守卫且为终态。 |
| BR-5 | 业务状态、超期、证据质量、规则治理状态、规则运行状态和规则质量状态正交存储。 |
| BR-6 | TransferOrder 业务状态与 `TransferOrder.deliveryStatus` 正交；工单与原线索互不自动关闭。 |
| BR-7 | 权威责任转移与有期限授权代办是不同对象，二者均不改写历史或重置时钟。 |
| BR-8 | 公共待办按业务键唯一；更新原事项、升级只提醒一次、终态关闭并可对账。 |
| BR-9 | Top-k 必须引用已批准的 QueuePolicyVersion，明确定义 K、容量、完整排序与稳定 tie-breaker。 |
| BR-10 | 高风险动作未映射时默认 deny；执行时重检授权、策略、审批和对象版本。 |
| BR-11 | 驾驶舱、工作纪实、标签、线索和技术运行面板用途隔离，不得自动用于惩戒或资源剥夺。 |
| BR-12 | 工作日、时区、节假日与含边界规则必须引用 BusinessCalendarVersion；缺失时不得推断。 |

#### Versioned acceptance dictionaries and operating boundaries

- 准入词典已冻结为 `QP-1.0.0`、`BC-1.0.0`、`WVP-1.0.0`、`RS-1.0.0`、`RFP-1.0.0` + `RFP-FIXTURE-1.0.0`、`CAC-1.0.0`、`SPM-1.0.0`、`ACN-1.0.0`、`SGP-1.0.0`、`QRP-1.0.0`、`PP-1.0.0`、`AP-1.0.0`、`TSP-1.0.0`、`MPP-1.0.0` 及 approved EvidenceSchemaVersion/RuleVersion；缺版本或运行证据失败时 fail closed。
- MVP、首期完整上线和后续增强边界分别在 PRD §7 给出；首期明确无学生端、无独立移动壳、无自动重大不利决定、无网络内容采集。
- 必需集成包括统一身份/门户、权威组织责任源、17 个数据源合同、协同工单、公共消息待办、任务/结果/指标回写及校级可观测性；静态合同已批准，sandbox/契约测试/对账证据由对应 Story 产生。
- 成功指标为 SM-1—SM-15，反向约束为 SM-C1—SM-C9；试点扩大同时受有效线索率、候选有效产出率、分群伤害、容量与积压约束。
- 12 个实现准入阻塞项、DEC-001—018 与 G-01—09 的裁决以 `AUTH-2026-07-17-001` 为权威；运行证据仍受 Story/Release DoD 约束，未被静态批准伪装为实测通过。

### PRD Completeness Assessment

- 正式需求集合完整：62 FR、34 NFR、12 BR 均具有稳定 ID，功能范围、非目标、分期、指标、外部依赖与证据责任齐备。
- 验收条件总体可测试，涉及阈值、状态、边界、幂等、授权、失败后果与证据 owner 的口径均已版本化。
- 发现 1 处文字级残留：UJ-1 仍用“G-03/G-06 已关闭/未关闭”描述运行资格，和当前“Gate 已批准进入实现、运行证据由 Story 产生”的二层语义不一致。该项在本轮一致性修订中关闭，不构成新外部依赖。

## Epic Coverage Validation

### Epic FR Coverage Extracted

| Epic | 最终交付 FR |
|---|---|
| Epic 1 | FR-1—FR-3、FR-5、FR-8 |
| Epic 2 | FR-9—FR-13、FR-53；并为 FR-54 提供前置 |
| Epic 3 | FR-14—FR-20、FR-25—FR-27、FR-29—FR-35、FR-43—FR-44、FR-48、FR-62；并为 FR-28/FR-36 提供 phase |
| Epic 4 | FR-21—FR-24、FR-37—FR-38、FR-56—FR-58 |
| Epic 5 | FR-4、FR-36、FR-39—FR-42、FR-59 |
| Epic 6 | FR-28、FR-45—FR-47、FR-54、FR-60—FR-61 |
| Epic 7 | FR-55 |
| Epic 8 | FR-49—FR-52 |

每个 FR 恰有一个最终 owner Story；contributing Story 只表达跨 Story 的 phase、生产者、消费方或 hardening，不冒充最终完成。

### Coverage Matrix

| FR | PRD requirement | Epic/Story coverage | Status |
|---|---|---|---|
| FR-1 | 统一入口与单点登录 | owner 1.2; contributors 1.1c,1.1d | ✓ Covered |
| FR-2 | 权威身份与责任关系同步 | owner 1.6c; contributors 1.6a,1.6b | ✓ Covered |
| FR-3 | 角色化首页 | owner 1.7; contributors 1.6a,1.6b,1.6c | ✓ Covered |
| FR-4 | 公共消息与待办联动 | owner 5.5; contributors 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.1,5.2d | ✓ Covered |
| FR-5 | 角色与数据范围控制 | owner 1.7; contributors 1.8 | ✓ Covered |
| FR-6 | 字段级展示与脱敏 | owner 3.14c; contributors 1.8,3.14a,3.14b | ✓ Covered |
| FR-7 | 敏感信息按任务授权 | owner 3.14c; contributors 1.8,3.14a,3.14b | ✓ Covered |
| FR-8 | 全链路审计 | owner 1.5; contributors 1.3,1.4,3.14c | ✓ Covered |
| FR-9 | 数据源目录 | owner 2.1; contributors 2.4 | ✓ Covered |
| FR-10 | 主体标识关联 | owner 2.2; contributors 2.1 | ✓ Covered |
| FR-11 | 数据质量指标 | owner 2.3; contributors 2.1 | ✓ Covered |
| FR-12 | 自动熔断与恢复 | owner 3.2; contributors 2.4,2.5a,2.5b,2.5c | ✓ Covered |
| FR-13 | 历史数据与重算 | owner 2.2; contributors 2.3 | ✓ Covered |
| FR-14 | 规则配置 | owner 3.1a; contributors 3.1b | ✓ Covered |
| FR-15 | 规则生命周期 | owner 3.1c; contributors 3.1a,3.1b | ✓ Covered |
| FR-16 | 个人基线 | owner 3.3a; contributors 2.2 | ✓ Covered |
| FR-17 | 场景排除与白名单 | owner 3.3b; contributors 3.3c | ✓ Covered |
| FR-18 | 规则分数与关怀等级 | owner 3.6; contributors 3.1c | ✓ Covered |
| FR-19 | 单线索优先级与 Top-k | owner 3.7; contributors 3.6 | ✓ Covered |
| FR-20 | 住宿安全场景 | owner 3.4; contributors 2.1,3.2,3.3b,3.3c | ✓ Covered |
| FR-21 | 隐性经济压力场景 | owner 4.1b; contributors 4.1a | ✓ Covered |
| FR-22 | 毕业季关怀场景 | owner 4.4b; contributors 4.1b,4.3,4.4a | ✓ Covered |
| FR-23 | 夜间作息场景 | owner 4.2; contributors 3.5,4.1a | ✓ Covered |
| FR-24 | 季节与群体专项 | owner 4.4c; contributors 4.4a | ✓ Covered |
| FR-25 | 候选线索生成 | owner 3.5; contributors 3.4 | ✓ Covered |
| FR-26 | 正式线索字段 | owner 3.6; contributors 3.5 | ✓ Covered |
| FR-27 | 结构化证据链 | owner 3.6; contributors 3.5 | ✓ Covered |
| FR-28 | 可读解释与建议 | owner 6.3c; contributors 3.6,6.3a,6.3b | ✓ Covered |
| FR-29 | 线索时限与状态 | owner 3.9d; contributors 3.5,3.8,3.9a | ✓ Covered |
| FR-30 | 工作台概览 | owner 3.7; contributors 3.5 | ✓ Covered |
| FR-31 | Top-k 任务队列 | owner 3.7; contributors 3.6 | ✓ Covered |
| FR-32 | 核实反馈 | owner 3.8; contributors 3.6 | ✓ Covered |
| FR-33 | 持续观察 | owner 3.9a; contributors 3.8 | ✓ Covered |
| FR-34 | 权威责任转移 | owner 3.9b; contributors 1.6b,1.6c,3.9d | ✓ Covered |
| FR-35 | 学生基础档案 | owner 3.10; contributors 1.8 | ✓ Covered |
| FR-36 | 动态信号与历史时间线 | owner 5.3; contributors 3.10,3.6,3.8 | ✓ Covered |
| FR-37 | 身份等级与业务标签治理 | owner 4.5c; contributors 4.5a,4.5b | ✓ Covered |
| FR-38 | 标签使用反馈 | owner 4.6; contributors 4.5c | ✓ Covered |
| FR-39 | 发起转介 | owner 5.1; contributors 1.9,3.8 | ✓ Covered |
| FR-40 | 转介状态流转 | owner 5.2d; contributors 5.2a,5.2b,5.2c,5.5 | ✓ Covered |
| FR-41 | 处理结果回填 | owner 5.3; contributors 5.2c | ✓ Covered |
| FR-42 | 集成式转介 | owner 5.4; contributors 5.2d | ✓ Covered |
| FR-43 | 学院成长总览 | owner 3.11; contributors 3.7,3.10 | ✓ Covered |
| FR-44 | 超期督办 | owner 3.12; contributors 3.11,5.5 | ✓ Covered |
| FR-45 | 领导驾驶舱 | owner 6.1; contributors 3.11,6.2 | ✓ Covered |
| FR-46 | 周报与月报 | owner 6.4; contributors 3.13,5.5 | ✓ Covered |
| FR-47 | 规则运营复盘 | owner 6.3c; contributors 6.3a,6.3b | ✓ Covered |
| FR-48 | 结构化解释基线 | owner 3.6; contributors 3.1c,3.3a | ✓ Covered |
| FR-49 | 增强建议灰度 | owner 8.2; contributors 8.1 | ✓ Covered |
| FR-50 | 自然语言问数 | owner 8.3; contributors 8.1,6.4 | ✓ Covered |
| FR-51 | 模型/策略发布关卡 | owner 8.1; contributors 6.3c,6.5,6.6 | ✓ Covered |
| FR-52 | 稳定基线与回退 | owner 8.4; contributors 8.1,8.2,8.3 | ✓ Covered |
| FR-53 | 运行状态监控 | owner 2.6b; contributors 2.6a | ✓ Covered |
| FR-54 | 业务连续性处置 | owner 6.5; contributors 2.7a,2.7b,2.7c,2.8a,2.8b | ✓ Covered |
| FR-55 | 移动端轻量处置 | owner 7.2c; contributors 7.1,7.2a,7.2b | ✓ Covered |
| FR-56 | 进宿舍工作统计 | owner 4.8; contributors 2.1,1.8 | ✓ Covered |
| FR-57 | 学业关怀场景 | owner 4.3; contributors 2.1,3.5,4.1a | ✓ Covered |
| FR-58 | 跨类别独立线索合证 | owner 4.7; contributors 4.2,4.3,4.4b | ✓ Covered |
| FR-59 | 数智学工任务与关怀结果回写 | owner 5.5; contributors 1.9,2.5a,2.5c,3.4,3.9d,3.12,5.2d | ✓ Covered |
| FR-60 | 运营指标发布与回写 | owner 6.4; contributors 5.5,6.3b | ✓ Covered |
| FR-61 | 改进计划与资源调配闭环 | owner 6.2; contributors 6.1,5.5 | ✓ Covered |
| FR-62 | 授权代办 | owner 3.9c; contributors 1.7,1.8,3.9b | ✓ Covered |

### Missing Requirements

无。Epics 中也不存在 PRD 未定义的 `FR-*`。

### Coverage Statistics

- Total PRD FRs: 62
- FRs covered in epics: 62
- Missing FRs: 0
- Extra/undefined FRs in epics: 0
- Coverage percentage: **100%**

## UX Alignment Assessment

### UX Document Status

**Found.** 当前 UX 由两份互补受控文档组成：

- `EXPERIENCE.md`：信息架构、语气、行为组件、状态模式、交互原语、响应式规则、性能/连续性、无障碍底线与 UJ-1—UJ-6。
- `DESIGN.md`：品牌层级、token、排版、栅格、间距、形状、组件视觉契约与 Do/Don't。

两份文档均为 v2.1.0、`implementationReadiness: ready`，绑定 FR-1—FR-62、BR-1—BR-12、NFR-1—NFR-34；24 个行为组件在 DESIGN 的 token 与视觉组件表中逐一存在，缺失 0、额外 0。

### UX ↔ PRD Alignment

| Concern | PRD baseline | UX realization | Result |
|---|---|---|---|
| 用户旅程 | UJ-1—UJ-6 | Key Flows 同名逐条覆盖，含失败/降级路径 | Aligned |
| 线索非结论 | BR-1、FR-18/27/28、NFR-23 | 事实/判断/建议分区，中性非诊断文案 | Aligned |
| Candidate/Clue 与时钟 | BR-2—BR-5、FR-25/29 | 前置门、双队列、绝对截止、超期正交 | Aligned |
| 权限与敏感字段 | FR-3、FR-5—FR-8、RFP-1.0.0、RFP-FIXTURE-1.0.0 | 七角色首页、对象/动作/范围授权、C/M/H 字段投影、不泄露存在性 | Aligned |
| 工作台与 Top-k | FR-19、FR-30—FR-33、QP-1.0.0 | workload-summary 六项对账、双队列、稳定排序 | Aligned |
| 关怀动作与观察 | FR-29、FR-32—FR-33、CAC-1.0.0 | 六类动作、唯一完成事件、dueAt 与 Observation `[startsAt, reviewAt)` 守卫 | Aligned |
| 季节/学业场景 | FR-22—FR-24、FR-57、SPM-1.0.0、ACN-1.0.0 | required/optional 源、单信号不下结论、七类学业节点和降级提示 | Aligned |
| 转介与投递 | FR-39—FR-42、BR-6、TSP/PIC | 业务状态与 DeliveryRecord 分栏，dueAt 不重置 | Aligned |
| 标签/治理/高风险动作 | FR-37/38/51/61/62、BR-10/11 | 三态规则面板、HRAP deny、用途隔离与复查 | Aligned |
| Web/移动 | FR-55、NFR-17—NFR-22、NFR-29—NFR-34 | 门户 Web + 统一 App WebView、375px、在线写、易失草稿 | Aligned |
| 性能 | NFR-2/3/29、PP-1.0.0 | `ui.content-ready` 2s、看板 3s、跨端 5s | Aligned |
| 无障碍 | WCAG 2.2 AA、NFR-17/21/22 | 键盘、焦点、错误摘要、live region、等价表格、200%/reduced-motion | Aligned |

### UX ↔ Architecture Alignment

| UX need | Architecture support | Result |
|---|---|---|
| 统一门户、SSO、深链与 WebView | 宿主入站适配器、HIP/ISP/RFP Gate、共享 API 拓扑 | Supported |
| 服务端授权与即时撤权 | AD-8/AD-9；请求时对象/动作/字段重算 | Supported |
| 真实持久状态与失败可见 | AD-3、AD-7、AD-11、AD-13、AD-20 | Supported |
| 响应式单一前端 | AD-17、AD-21、PAB-1.0.0 的 Vue/Element Plus/ECharts 组合 | Supported |
| 用户感知性能与跨端同步 | AD-17、AD-22 的三个明确 SLI | Supported |
| 无持久化离线业务数据 | AD-17、AD-21、AD-26 | Supported |
| 图表等价数据与读屏反馈 | AD-11、AD-17、一致性约定无障碍条款 | Supported |
| 品牌/断点/token 的可复现实现 | AD-28 release manifest + UXB/CTV/PAB 基线 | Supported |

### Alignment Issues Resolved During Assessment

1. **PRD UJ-1/M6 旧 Gate 语义**：已将“G-03/G-06 或 Gate 关闭”修正为“Gate 静态基线已批准；对应 Story/Release 运行证据控制生产与试运行”。这消除了 DoR 与 DoD 的状态冲突。
2. **36px 视觉高度与 40px 命中区混写**：已明确桌面常规控件视觉高度为 UXB-1.0.0 的 36px；独立图标/行内动作通过外层热区提供至少 40×40px，移动主操作保持至少 44×44px，并受 WCAG 2.2 AA 最小目标规则约束。

### Warnings

无未解决 UX 警告。视觉回归、浏览器/WebView、键盘/辅助技术、对比度和用户感知性能仍须由 Story 1.1c/1.1d、1.2、3.6/3.7、7.x 真实执行；它们是 DoD/Release 证据，不是实现准入阻塞。

## Epic Quality Review

### Epic Structure Validation

| Epic | User outcome | Independence result |
|---|---|---|
| 1 可信门户接入与可问责授权 | 授权用户可从可信入口进入并在正确职责范围工作 | Standalone foundation；无后续 Epic 依赖 |
| 2 可信数据运营与安全连续性 | 数据/运维人员可管理质量、熔断、恢复、降级与可观测性 | 仅依赖 Epic 1；无 Epic 3+ 前置 |
| 3 住宿安全首个生产闭环 | 辅导员可从规则到 Candidate、核实、行动和闭环 | 仅依赖 Epic 1—2 |
| 4 多场景与受治理上下文 | 辅导员/治理人员可处理经济、作息、学业、标签和合证 | 仅依赖 Epic 1—3 |
| 5 跨部门协同闭环 | 发起方和协同方可共享可回填、可对账工单 | 仅依赖 Epic 1—3 与已交付生产者 |
| 6 校级治理与持续改进 | 管理者可从汇总态势形成行动、报告、复盘和发布验收 | 仅依赖 Epic 1—5 |
| 7 移动在线轻量处置 | 两类角色可在统一 App 中安全在线处置 | 复用既有 Web/API/协同；无 Epic 8 依赖 |
| 8 受治理智能增强 | 用户在完整 Release 基线上获得可回退建议与汇总问数 | 仅依赖 Epic 1—7 的稳定事实与 Release 证据 |

Epic 1/2 含工程、供应链、数据与运行 enabler，但 Epic 本身分别交付可信接入/授权和数据责任人可使用的质量/连续性结果，不是孤立的“搭数据库/API”技术里程碑。

### Story and Dependency Audit

- Stories: **88**（13 enabler、8 integration、46 feature、10 governance、8 hardening、3 release）。
- Status: **88 planned；0 blocked**。
- Estimates: 1×2d、30×3d、49×5d、8×8d；全部 ≤8d。
- Dependency references: **0 unknown、0 forward、0 cycles**。
- Required metadata: type/dependsOn/readyWhen/estimate/status 全部存在。
- Acceptance criteria: **335**；每条均含 Given/When/Then，缺失 0。
- 七路径登记：**36/36** Story 均含 HAPPY、AUTH-DENIAL、ILLEGAL-STATE、BOUNDARY、DEPENDENCY-FAILURE、IDEMPOTENCY、CONCURRENCY。
- Starter/brownfield: Architecture 未指定可直接采用的生产 starter；Story 1.1a 先盘点现有 Vite 原型/资产，1.1b 建生产骨架，1.1c/1.1d 冻结依赖与 CI/SBOM/签名，符合当前“原型迁移 + 生产工程新建”的实际形态。
- Database timing: 不存在“一次创建全部模型/表”的 Story；AD-2 固定模块唯一写所有权，各能力在首次需要时交付本域迁移。

### Quality Defects Resolved During Assessment

| ID | Severity before correction | Defect | Closure |
|---|---|---|---|
| EQ-01 | Major | NIGHT-001、ACADEMIC-001 的 production/canary 是场景 Story 的隐含 readyWhen，但无明确前序 owner | Story 4.1a 扩为三条首期场景规则的资格、D4、canary、production owner；4.2/4.3 显式依赖其证据 |
| EQ-02 | Major | CORROBORATE-001 当前 inactive/fused，而 4.7 只写计算 happy path | 4.7 改为发布 + 合证 owner，增加完整七路径并在上游正式线索可用后执行 |
| EQ-03 | Major | Story 3.1b production 发布使用 QualityEligibility，却未声明 2.4 依赖 | `dependsOn` 增加 2.4；仍为后向依赖且无环 |
| EQ-04 | Major | Story 8.1 可在 6.6 跨域删除/回执 Release 验收前启动智能发布 | 8.1 增加 6.6 依赖，确保完整 Release 基线先通过 |
| EQ-05 | Minor | Story 3.14b 使用 pending/completed，与 PRD/AD-13 的后台作业枚举冲突 | 统一为 queued/running/succeeded/failed/cancelled |

### Independent Adversarial Audit Defects Resolved

首次 READY 判定后，独立审阅者以“能否仅凭受控制品写出无歧义实现和自动化验收”为准重新抽查，并暂时撤回 READY。第一轮发现 3 个 Blocker 与 2 组 Major；修订后的第二轮又发现 2 个 Blocker 与 2 个 Major 语义残余。IA-01—IA-09 均已在 2.1.0 基线关闭：

| ID | Prior severity | Residual defect | Closure evidence |
|---|---|---|---|
| IA-01 | Blocker | RFP 缺七角色对象/动作/范围和字段投影 oracle | 委托基线 1.1.0 冻结 `RFP-1.0.0` 与 `RFP-FIXTURE-1.0.0`；PRD FR-3/5/6/7、AD-8/9、UX sensitive-field、Story 1.7/1.8 同步 |
| IA-02 | Blocker | 4.4c 的 required/optional 季节矩阵不存在，且直接源错误使用规则 `production` 状态 | `SPM-1.0.0` 冻结三专项的源矩阵、合证数、边界和降级；直接源改验合同/生效、eligible、sealed 与 DCC 水位；Story 4.4a—4.4c 同步 |
| IA-03 | Blocker | CareAction 类别、完成事件和 dueAt/Observation 守卫未版本化 | `CAC-1.0.0` 冻结六类动作、唯一 completionKind、状态机和 `[startsAt, reviewAt)`；Story 3.8/3.9a/3.9d/5.3 同步 |
| IA-04 | Major | FR-22/23/57 owner Story 缺授权、边界、依赖失败、重放/更正；学业节点未封闭 | `ACN-1.0.0` 冻结七节点；Story 4.2、4.3、4.4b、4.4c 由 4 条 AC 扩为 20 条，覆盖单信号、隐私授权、失败、边界、幂等和更正 |
| IA-05 | Major | `AC-4.1b-AUTH-DENIAL` 验规则发布权限，未验经济对象授权 | 改验 RFP 的 `care.read`、`candidate.review` 与责任学生/有效 DelegationGrant 范围；越权不读取明细、不改变 Candidate 并留拒绝审计 |
| IA-06 | Blocker | R5 星号字段、R2 督办 scope 与斜杠 actionId 仍缺确定 oracle | 冻结 7 字段全集、purpose/fieldAllowlist 求交、WORKITEM-A/TRANSFER-A fixture、action 展开与 RFP→HRAP 映射；Story 1.7/1.8 同步 |
| IA-07 | Major | ACADEMIC-001 的 CALENDAR required 在目录与 AC 不一致 | 将既有 `DEP-P0-CALENDAR-001` 纳入 ACADEMIC-001 required all-of；4.3 与 PRD/AD/Trace 同步，不增加 dependency 数 |
| IA-08 | Major | 多 Clue 专项 Observation 无 owner/唯一键 | CAC 要求显式单一参与 clueId，专项仅作 sourceContextRef，并冻结 observation/task key；Story 3.9a/4.4b 同步 |
| IA-09 | Blocker | academicCalendarProjection/termStartAt 无 schema、owner、版本和 program-specific 必填 | SPM 冻结 ACADEMIC+CALENDAR 两源、教务 owner、schema/版本/封账/质量/水位和三 program 各自必填/时序/NA 语义；Story 4.4a—c 同步 |

独立终检不改变 Story 数或七路径登记数；验收条件由 319 增至 335。上述合同和 AC 都是静态 DoR 关闭证据，未把尚未执行的运行证据计为通过。

### Remaining Violations

- 🔴 Critical: **0**
- 🟠 Major: **0**
- 🟡 Minor: **0**

所有发现均已在受控文档中修正；没有需要外部 owner 再确认的 Epic/Story 结构问题。

## Summary and Recommendations

### Overall Readiness Status

**READY FOR IMPLEMENTATION**

PRD、Architecture、UX、Epics/Stories 及治理附件已经一致；62/62 FR 有 Story 覆盖，88 个 Story 的依赖图无未知引用、前向依赖或循环，12 个原始 readiness 阻塞项、正式复检发现的 7 个质量缺陷，以及两轮独立终检发现的 IA-01—IA-09 均已关闭。当前不存在需要外部 owner 再确认的实现准入项。

此结论仅表示可以进入排期与实现，不表示 production-ready。沙箱/契约、性能、自然月可用性、灾备、删除、无障碍、canary 等尚未执行的运行证据保持 `pending-story-execution`，必须由指定 Story/Release DoD 真实产生；失败或缺失时继续 fail-closed，禁止完成 Story、提升 RC 或进入生产。

### Critical Issues Requiring Immediate Action

**None.** Critical、Major、Minor 未解决项均为 0。

### Recommended Next Steps

1. 按 `epics.md` 的实现顺序和 `dependsOn` DAG 进入 sprint planning，从 Story 1.1a 开始；不再等待额外的静态 owner 批准。
2. 在 CI 中持续校验 FR/Story/AC ID、依赖 DAG、版本化策略与 Gate 状态，防止受控基线漂移。
3. 严格执行 Story/Release DoD，重点保留 Story 1.1c/1.1d、2.8a、6.5、6.6、7.2c、8.1—8.4 的供应链、性能、恢复、删除、无障碍与智能发布证据。
4. 若任何运行证据不达标，按对应 Story 的回退/熔断路径处理，不回写为已通过，也不改变本次实现准入结论的审计事实。

### Final Validation Record

| Check | Result |
|---|---|
| 结构、元数据、GWT、追踪、Gate/DEC/合同/规则/动作及证据边界 | **3423/3423 passed；0 error** |
| FR / BR / NFR / AD | 62 / 12 / 34 / 28，连续且唯一 |
| Story / AC / 七路径 Story | 88 / 335 / 36；全部 planned，估算≤8d |
| `dependsOn` | unknown=0、forward=0、cycle=0 |
| Markdown 相对链接与表格列 | broken link=0、column mismatch=0 |
| 运行证据 | `pending-story-execution`；未被计作通过 |

### Final Note

正式复检先发现并修正 7 个残留问题；两轮独立对抗式终检再发现 IA-01—IA-09（5 个 Blocker、4 个 Major）并全部关闭。原报告的 12 个阻塞项已逐项形成关闭证据，最终未解决 Critical/Major/Minor 均为 0。最终裁决依据 `AUTH-2026-07-17-001`，评估日期为 2026-07-17，评估者为 Codex / BMAD Implementation Readiness。
