---
title: '按批准提案更新规划文档'
type: 'chore'
created: '2026-07-16'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: 'NO_VCS'
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-07-16.md'
  - '{project-root}/_bmad-output/planning-artifacts/implementation-readiness-report-2026-07-16.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 当前 PRD、架构、UX 与 Epics 尚未落实已批准的 Sprint 变更提案，仍含前向依赖、超大 Story 和缺失的验收权威源，不能进入 Sprint Planning。

**Approach:** 以批准提案为唯一变更边界，建立三份治理附件，并同步更新所有权威规划文档的 Story 顺序、验收落点与追踪关系。

## Boundaries & Constraints

**Always:** 保持 8 个 Epic、FR1–FR58、MVP/首期/增强范围和架构范式不变；重写 Story 使用 AC ID 与分行 Given/When/Then/And；未决值必须带 owner、deadline、临时默认值和阻塞级别。

**Ask First:** 新增/删除 FR、改变 Epic 数量、缩减 MVP、降低已批准门禁或改变批准提案责任边界。

**Never:** 不修改源输入或评估报告的历史结论；不提前创建 `sprint-status.yaml`；不把产品假设写成校方硬规范；不解除 `ECON-012` 的量纲/边界样本阻塞。

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md` -- 更新 §9.7、§15、§16。
- `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md` -- 更新 AD-5、AD-13、AD-23、Deferred。
- `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/{DESIGN,EXPERIENCE}.md` -- 更新治理、多角色及临时 UX 基线。
- `_bmad-output/planning-artifacts/epics.md` -- 重排依赖、拆分 Story、更新 FR 映射与 AC。
- `_bmad-output/planning-artifacts/{rule-catalog,high-risk-action-matrix,open-decisions}.md` -- 三份版本化治理权威源。

## Tasks & Acceptance

**Execution:**
- [x] 新建规则目录、高风险动作矩阵和未决事项决策表，完整落实批准内容。
- [x] 更新 PRD、Architecture 与 UX 双脊柱，使开放项、门禁和权威引用一致。
- [x] 重写 `epics.md` 中 Epic 1–4、6 的受影响 Story，并更新 Epic/FR 与 Story/FR 映射。
- [x] 扫描权威规划文档，修复旧编号、缺失附录 D、笼统门禁和“无开放问题”等冲突。
- [x] 在批准提案中记录实施结果，但保留审批内容和历史结论。

**Acceptance Criteria:**
- Given 更新完成，when 统计 FR 映射，then FR1–FR58 均有最终端到端验收落点且无未定义 FR。
- Given 新 Story 顺序，when 检查报告中的 5 项严重依赖，then 均已消除。
- Given 拆分结果，when 检查 Epic 1–4，then批准要求的 5 组超大 Story 均已形成独立交付单元。
- Given 治理或发布动作，when 查阅规划文档，then 可唯一定位三份版本化权威源。
- Given 校方决策未提供，when 查阅 UX/架构/Story，then 仅出现批准的临时默认值与阻塞说明。

## Spec Change Log

## Design Notes

三份治理附件独立成权威源，其他文档只引用并补充本领域约束，避免复制漂移。`epics.md` 保持 Epic 边界，用新编号和 AC ID 重写受影响 Story。

## Verification

**Commands:**
- `rg` 扫描权威规划文档中的旧 Story 编号、缺失附录 D、笼统门禁与“无开放问题”残留。
- 提取 `epics.md` 的 FR 与 Story 标题，验证 FR1–FR58 连续覆盖、编号无重复且顺序符合批准提案。
