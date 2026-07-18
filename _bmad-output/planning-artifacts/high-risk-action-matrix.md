---
title: 学林知微高风险动作矩阵
status: approved
evidence_status: approved-policy-runtime-tests-pending
version: 1.0.0
effective_date: 2026-07-17
updated: 2026-07-17
owners:
  - 安全负责人
  - 学工业务负责人
named_document_owner: Hei
named_approver: Hei
approval_date: 2026-07-17
evidence_uri: delegated-decision-baseline-2026-07-17.md
---

# 高风险动作矩阵

本文件是已批准的 `HighRiskActionPolicyVersion=HRAP-1.0.0`。等级为：`D0=deny`、`D1=即时确认`、`D2=二次认证`、`D3=审批`、`D4=双人复核（maker/checker）`。未列动作默认 D0。项目总负责人 Hei 依据 `AUTH-2026-07-17-001` 批准本版本；运行时 Responsible 身份由 RBAC 配置绑定，D4 的 maker/checker 必须是不同主体，不能由本文档批准代替。

DEC-004 已关闭，本版本对实现立即生效。执行时仍须重检 `policyVersion`、`approvalVersion`、对象状态和当前授权；审批状态固定为 `pending → approved/rejected/expired/cancelled`。各 Story 的负向、幂等、并发和职责分离测试尚须真实执行，未通过不得完成或发布。

等级执行合同：D1 使用绑定 actor/action/object/scope/policy/aggregateVersion 的 5 分钟单次 challenge；D2 要求 5 分钟内新鲜、AAL2 类 step-up authentication；D3 申请人与独立审批人分离，申请 24 小时过期、批准后执行 token 30 分钟过期；D4 maker/checker 必须为不同自然人且禁止自审，pending 4 小时过期、执行 token 15 分钟过期。任一政策、权限、对象版本、范围或认证状态变化都使原批准失效。

| actionType | 动作 | 批准门禁 | ownerRole | runtime owner binding | approverName | approvalDate | effectiveDate | evidenceUri | 最低测试证据 | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|
| sensitive-export.create | 创建含敏感字段导出 | D3 | 数据 owner 或授权管理人员 | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 最小字段预览、范围、有效期、审批过期/撤权、非法状态、幂等/并发 | approved-policy / runtime-tests-pending |
| sensitive-export.download | 下载敏感导出 | D2 | 下载者所属数据 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 当前授权∩申请快照、有效期、下载审计、撤权/更正失效 | approved-policy / runtime-tests-pending |
| temporary-grant.issue | 发放临时对象/字段授权 | D4 | 权限管理员 + 业务 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 期限、purpose、最小范围、职责冲突、即时撤销、并发冲突 | approved-policy / runtime-tests-pending |
| temporary-grant.revoke | 撤销生效中的代办授权 | D1 | 授权人、当前权威责任人或具权权限管理员 | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 仅 active 可撤销、受控原因、首次撤销立即失权、幂等重放不重复事件、越权/终态/409 保持状态 | approved-policy / runtime-tests-pending |
| whitelist.create-or-change | 新增/修改白名单 | D4 | 规则治理人员 + 业务 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 命中影响预览、有效期、理由、样本重算、历史不改写 | approved-policy / runtime-tests-pending |
| rule.publish | 规则 canary/production 发布 | D4 | 规则治理 maker + checker | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | governanceStatus=approved、生效日期、匿名边界样本、qualityStatus=eligible、依赖证据、差异和影响范围 | approved-policy / runtime-tests-pending |
| rule.rollback | 规则回退 | D3 | 规则治理负责人 | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 目标稳定版本、影响预览、历史不改写、未完成任务连续性 | approved-policy / runtime-tests-pending |
| strategy.publish | 增强策略 canary/production 发布 | D4 | 模型治理 maker + checker | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | approved StrategyGatePolicyVersion、质量、效果、噪声、分群、比较基线、人工复核关卡 | approved-policy / runtime-tests-pending |
| strategy.rollback | 增强策略回退 | D3 | 模型治理负责人 | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 稳定规则基线、未完成任务不中断、历史决策可追溯 | approved-policy / runtime-tests-pending |
| quality-fuse.recover | 质量熔断恢复 | D4 | 数据 owner + 业务 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 完整性校验、样本重算、影响预览、恢复观察、失败回退 | approved-policy / runtime-tests-pending |
| bulk-governance.execute | 批量标签/责任/状态治理 | D4 | 业务 owner + 治理复核人 | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 数量/范围预览、逐对象授权、可撤销性、失败明细、部分成功和对账 | approved-policy / runtime-tests-pending |
| transfer.submit | 普通最小必要转介 | D1 | 当前责任辅导员所属业务 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 必填、最小字段投影、目标授权、状态守卫、幂等、并发冲突 | approved-policy / runtime-tests-pending |
| leader-action.record | 记录资源调配/改进要求 | D1 | 具权领导所属业务 owner | runtime-RBAC | Hei | 2026-07-17 | 2026-07-17 | delegated-decision-baseline-2026-07-17.md | 非惩戒声明、受控动作目录、责任方、截止、基线周期和审计 | approved-policy / runtime-tests-pending |

## 生效与运行验收规则

- 文档批准证据已齐备；运行环境必须为每个角色绑定唯一主体，D4 必须验证 maker/checker 不同且无职责冲突。
- 策略证据 URI 指向委托决策基线；测试证据由表内 owner Story 产生并保存制品 URI，不得用“已确认”代替执行结果。
- 运行时代码只有在对应 Story 的七路径和职责分离测试通过后才可启用 actionType；测试未通过的单项保持 runtime deny，但不反向阻塞其他 Story 开工。
- 校方提高门禁等级时采用更严格值；降低等级必须经过正式 PRD/Architecture 变更评审并发布矩阵新版本。命令、审批、审计和测试证据均必须保存所用矩阵版本。
