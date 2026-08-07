# 主体映射与映射更正重算基线（SMR-1.0.0）

- 状态：approved
- 批准人：Hei
- 批准时间：2026-08-06T15:39:00+08:00
- 决策权限：用户明确授权产品 owner 自主解决 Story 2.2 的全部 HALT 决策
- 绑定合同：`SMP-1.0.0`、`SRP-1.0.0`、`SUBJECT-IDENTIFIER-ADAPTER-1.0.0`、`SUBJECT-REPAIR-PROJECTION-1.0.0`

## 决策

1. `StudentRef` 是唯一 canonical 主体 ID，永不复用；wire 字段 `subjectRef` 只是同一值的兼容别名，不形成第二套 ID。
2. `SRC-P0-STUDENT-001` 的有效学号事实是创建 `StudentRef` 的唯一权威证据。其他源只能通过本源 owner 提供的版本化精确 crosswalk 解析既有 `StudentRef`，不能创建主体、跨源降级或模糊匹配。
3. 所有标识先执行 Unicode NFKC、去首尾 Unicode 空白并拒绝控制字符。学号、卡号和门禁凭证转 ASCII 大写；校园网账号转 ASCII 小写；全部保留前导零。规范化后仍不满足 allowlist 的输入隔离。
4. 同一 `sourceId + identifierType + protectedIdentifierToken` 使用 UTC 半开有效区间，数据库拒绝重叠；相邻区间允许。标识重新发放必须有新的权威 crosswalk，不能从旧映射推断连续性。
5. merge、split、correct、revoke 只追加事件；历史事实不搬迁。`split-into` 保留 0..n 目标且必须人工选择。
6. DCC-1.0.0 与既有 source schema 不改写。新增的 pre-normalization sidecar adapter 在规范事实进入 DCC schema 前完成主体解析；隔离记录不得进入可计算投影。
7. 重算只接受 RuleVersion/场景合同提供的 `latestActionableAt`，缺失即只记录更正。执行前和结果发布前均用受信服务端时钟重检。
8. correction 完成要求所有 active consumer 的 watermark 到达更正版本且对账通过。`signal-evaluation` 和 `clue-care` 在 owner Story 激活前保持 planned，`runtimeEvidenceClaim=none`。

## 安全与展示边界

- 外部标识以环境和 keyVersion 绑定的 HMAC-SHA-256 token 查重，以 AES-256-GCM envelope 保护；明文、token、密文和 key ref 不进入 URL、日志、metric、trace、事件或前端持久化。
- R6 仅在 owned-source 和 `SUBJECT_MAPPING_EXCEPTION_REPAIR` purpose 下查看/修复；R7 仅见低基数技术作业状态和 `traceId`。
- `SubjectMappingException` 保持既有七字段投影；`MappingImpact` 与 `RecomputeJob` 仅使用 `SUBJECT-REPAIR-PROJECTION-1.0.0` 明列字段。

## 证据声明

合同、fixture 和本地沙箱只证明 conformance。真实 KMS、目标数据库与下游 owner consumer 未形成绑定当前 committed candidate 的证据前，运行声明保持 `none`。
