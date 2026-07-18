---
title: 学林知微决策关闭登记
status: closed
version: 2.1.1
updated: 2026-07-19-story-1.1d-applicability-alignment
owner: Hei
authorityRecordId: AUTH-2026-07-17-001
implementationReadiness: ready
runtimeEvidenceStatus: pending-story-execution
---

# 决策关闭登记

本表记录 DEC-001—DEC-018 的最终项目实现裁决。2026-07-17，项目总负责人 Hei 通过 `AUTH-2026-07-17-001` 委托并批准全部决策；详细数值、Responsible 角色、证据边界和运行验证 Story 以 [委托决策与实现准入基线](delegated-decision-baseline-2026-07-17.md) 为准。`closed` 表示实现所需参数、责任与失败语义已冻结，不表示沙箱、压测、灾备、删除或 canary 已实测通过；这些证据由对应 Story/DoD 生成，失败时禁止完成 Story 或发布。

## 后续作用域裁决

| authorityRecordId | approved baseline | accountable / approved | scope | effect | future owner |
|---|---|---|---|---|---|
| `USER-2026-07-19-SCHOOL-APP-NA` | [AAB-1.0.0](app-applicability-baseline-2026-07-19.md) | Hei / 2026-07-19 | Story 1.1c、1.1d | App/WebView 报告 `not-applicable` 且 `runtimeEvidenceClaim=none`；1.1d Web 正式证据仍必需 | NFR-31、真实 App/WebView/真机：7.1/7.x |

该 companion 不改写 DEC-001/DEC-014 的历史批准，也不取消统一 App 产品范围；未来启用 App 必须发布新的 AAB/PAB/TEST-ENV/ReleaseManifest 版本。

Story 1.1d 的 `CISB-1.0.0` 当前尚未批准：真实 repository/CI、artifact/attestation store、signing identity、受保护环境与 promotion adapter/endpoint 均未冻结。它不重开 DEC-014 的技术组合裁决，但属于 1.1d 的运行完成门；批准前只允许 U1 本地合同，整体不得进入 `review/done`。

| decisionId | 事项 | approved version | accountable / approved | affected Stories / Gate | 后续运行证据 owner | 状态 |
|---|---|---|---|---|---|---|
| DEC-001 | 门户/WebView 接入 | HIP-1.0.0 | Hei / 2026-07-17 | 1.2、7.1；G-02 | 1.2、7.1 | closed |
| DEC-002 | SSO/会话 | ISP-1.0.0 | Hei / 2026-07-17 | 1.2、1.6a—1.6c、7.1；G-02 | 1.6a—1.6c、7.1 | closed |
| DEC-003 | 多角色/字段授权（含 R5 星号字段/WORKITEM-A/action 展开） | RFP-1.0.0 + RFP-FIXTURE-1.0.0 | Hei / 2026-07-17 | 1.7、1.8、3.14、7.x；G-01/G-02 | 1.7、1.8、3.14b、7.x | closed |
| DEC-004 | 高风险动作 | HRAP-1.0.0 | Hei / 2026-07-17 | 高风险 Story；G-01 | 各 actionType owner Story | closed |
| DEC-005 | 六条规则/ECON-012 | RC-1.0.0 | Hei / 2026-07-17 | 3.x、4.x；G-06 | 3.1a—3.4、4.1a—4.7 | closed |
| DEC-006 | 44×44 触控 | UXB-1.0.0 | Hei / 2026-07-17 | 7.1、7.2；G-05 | 7.1、7.2c | closed |
| DEC-007 | 768/1024 断点 | UXB-1.0.0 | Hei / 2026-07-17 | 1.2、7.1；G-05 | 1.2、7.1 | closed |
| DEC-008 | 品牌/token | UXB-1.0.0 | Hei / 2026-07-17 | 1.2、7.1；G-05 | 1.2、7.1 | closed |
| DEC-009 | 辅助色 fallback | UXB-1.0.0 | Hei / 2026-07-17 | 7.x；G-05 | 7.x | closed |
| DEC-010 | I/II/III 术语 | CTV-1.0.0 | Hei / 2026-07-17 | 3.6、3.8、7.2a；G-05 | 3.6、3.8、7.2a | closed |
| DEC-011 | 公共任务/工单/回写 | PIC-1.0.0、TSP-1.0.0、MPP-1.0.0 | Hei / 2026-07-17 | 1.9、5.x、6.4；G-04 | 1.9、5.1—5.5、6.4 | closed |
| DEC-012 | 17 源/质量恢复 | DCC-1.0.0、QG-1.0.0、QRP-1.0.0 | Hei / 2026-07-17 | Epic 2/3/4；G-03 | Epic 2、3.2—3.4、6.5 | closed |
| DEC-013 | 灾备方案 | DRP-1.0.0 | Hei / 2026-07-17 | 2.8b、6.5；G-08 | 2.8b、6.5 | closed |
| DEC-014 | 供应链/运行时 | PAB-1.0.0 | Hei / 2026-07-17 | 1.1c—1.1d、正式 UI；G-05 | 1.1c、1.1d | closed |
| DEC-015 | 保留/销毁 | RS-1.0.0 | Hei / 2026-07-17 | 1.5、3.14、6.4—6.6；G-07 | 6.6 | closed |
| DEC-016 | 证据/队列/校历/到访/关怀动作/专项日历投影/学业节点 | ES/QP/BC/WVP/CAC/SPM/ACN-1.0.0 | Hei / 2026-07-17 | 3.4、3.6—3.9、4.2—4.4c、4.8、5.3、6.1；G-06 | 3.7—3.9、4.2—4.4c、4.8、5.3、6.1 | closed |
| DEC-017 | 性能/可用性 Profile | PP-1.0.0、AP-1.0.0 | Hei / 2026-07-17 | 性能 Story；G-01/G-05 | 1.1c、2.6a、2.8a、3.7、7.2c | closed |
| DEC-018 | 智能发布阈值 | SGP-1.0.0 | Hei / 2026-07-17 | 8.1—8.4；G-09 | 8.1—8.4 | closed |

## DEC-012 逐数据源责任登记

DEC-012 已通过 DCC/QG/QRP-1.0.0 关闭。Hei 是本版本的 accountable owner/批准人，表中领域角色是实施期 Responsible；逐源运行身份在部署配置中绑定，不再作为规划期外部阻塞。凡被 RuleVersion 消费的 sourceId 仍必须映射到规则目录的稳定 dependencyId；身份/纪实等非规则消费源保留 sourceId 与 purpose 合同，不伪造 dependency。契约、补数和对账未实测通过时，对应 Story 不得完成或发布。

| sourceId | 数据源/最小切片 | ownerRole | ownerName | required evidence | evidenceUri | 状态 |
|---|---|---|---|---|---|---|
| SRC-P0-STUDENT-001 | 学生主数据与学籍有效区间 | 学籍主数据 owner | Hei（A）；领域角色（R） | schema/版本、主键、有效区间、更正撤销、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-RESPONSIBILITY-001 | 组织、角色与辅导员责任关系 | 组织身份 owner + 学工责任关系 owner | Hei（A）；领域角色（R） | schema/版本、15 分钟增量 SLO、每日全量对账、完整性/新鲜度质量门、撤权/换号、异常队列、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-ACCOMMODATION-001 | 校内住宿分配及校外住宿备案有效区间 | 宿管数据 owner + 学工住宿备案 owner | Hei（A）；领域角色（R） | schema/版本、住宿类型（校内/校外备案）、校区/楼栋、有效区间、冲突与更正、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-CARD-001 | 一卡通主体映射与消费事实 | 一卡通数据 owner | Hei（A）；领域角色（R） | 标识映射、消费事件/商户或餐次类别/金额量纲/发生接收时间、退款冲正/撤销、来源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-CAMPUS-ACCESS-001 | 校门门禁有效事件 | 保卫校门数据 owner | Hei（A）；领域角色（R） | 事件/主体/设备/方向/时间、故障区间、SLO、质量门、迟到/补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-DORM-ACCESS-001 | 宿舍门禁有效事件 | 宿管/保卫宿舍门禁 owner | Hei（A）；领域角色（R） | 事件/主体/楼栋/设备/方向/时间、去重、更正、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-DEVICE-001 | 校门/宿舍门禁设备在线、故障与维护区间 | 保卫设备 owner + 宿管设备 owner | Hei（A）；领域角色（R） | 设备/位置/适用门禁源、在线/故障/维护起止、心跳、源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-LEAVE-001 | 请销假、实习备案与离返校有效区间 | 学工请假/实习数据 owner | Hei（A）；领域角色（R） | 类型（请假/实习/离返校）、起止、审批、撤销更正、版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-CALENDAR-001 | 有效校历、工作日与调休 | 校历 owner | Hei（A）；领域角色（R） | BusinessCalendarVersion、适用区间、节假日/调休、版本生效、SLO、质量门、补数/对账、契约测试与边界样本 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P0-TIMETABLE-001 | 课程/教学活动最小排除切片 | 教务课表数据 owner | Hei（A）；领域角色（R） | 学期/教学班/活动起止/校区地点/选课有效/停调课、effectiveAt、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-OFFCAMPUS-001 | FR-20 P0 校外住宿备案之外的离校/校外状态有效区间 | 学工离校备案 owner | Hei（A）；领域角色（R） | 备案类型/起止/审批/撤销更正、适用校区、与 P0 住宿备案去重规则、源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-NETWORK-001 | 夜间校园网最小汇总 | 信息中心网络数据 owner | Hei（A）；领域角色（R） | 主体映射、会话时间/时长/流量/接入区域汇总、禁止内容字段证明、源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-ACADEMIC-001 | 学业节点与学期批次事实 | 教务学业数据 owner | Hei（A）；领域角色（R） | 节点/学期/批次、有效状态、封账/更正、学业日历版本、源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-CARE-LIST-001 | 关爱名单最小事实 | 学工关爱名单 owner | Hei（A）；领域角色（R） | 名单类型、purpose、来源、有效期、审核/撤销、更正、敏感等级、源版本、SLO、质量门、补数/对账、契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-PSYCH-DEID-001 | 心理关注脱敏标签 | 心理中心数据 owner + 隐私复核人 | Hei（A）；领域角色（R） | 仅批准的脱敏类别/有效期/purpose，不含诊断与咨询内容；审核/撤销、更正、源版本、SLO、质量门、补数/对账、隐私负例与契约测试 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-AID-001 | 经济困难身份最小事实 | 资助数据 owner | Hei（A）；领域角色（R） | 身份类别、purpose、有效期、审核/撤销、更正、源版本、SLO、质量门、补数/对账、契约测试；不得替代 ECON-012 消费行为核实 | delegated-decision-baseline-2026-07-17.md | approved-contract |
| SRC-P1-WORK-VISIT-001 | 辅导员进宿舍工作纪实 | 辅导员运营 owner | Hei（A）；领域角色（R） | 访问人/日期/区域/方式/受控摘要、WorkVisitPolicyVersion、去重/配对、purpose 隔离、源版本、SLO、质量门、对账、契约测试；禁止进入学生研判特征 | delegated-decision-baseline-2026-07-17.md | approved-contract |

本表所有项目的决策版本、具名批准人、批准日期、生效日期和证据 URI 已齐备。后续改变参数、影响 Story 或控制强度必须走变更评审并产生新版本；不得把未完成的运行证据标记为已通过。
