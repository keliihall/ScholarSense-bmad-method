# ScholarSense 后端

本目录是模块化单体的生产后端根。当前已包含 identity-access 的会话/SSO API、
模块自有 PostgreSQL 表、与业务写入同事务提交的 `LocalAuditFact` + audit outbox，
以及 audit-operations 拥有的在线防篡改中心账本。

从仓库根执行：

```bash
_bmad/scripts/with_pab_toolchain.sh backend/mvnw -f backend/pom.xml clean verify
```

真实数据库验收使用精确 PostgreSQL 18.4，并同时验证干净迁移与
V000001→V000002→V000003→V000004→V000005 升级、搜索分页/索引、并发、回滚、重放、权限和特权篡改路径：

```bash
scripts/run_audit_postgresql_tests.sh
```

The Maven coordinates remain `0.1.0-SNAPSHOT`, but the only runnable release artifact is the neutral, reproducible path `backend/target/scholarsense-backend.jar`. Release identity is its SHA-256 plus the signed ReleaseManifest version; the filename and Maven coordinate are not release identities.

`web-api` 与 `worker` 使用同一个 jar。两者都必须通过环境变量提供以下引用型配置：
`SCHOLARSENSE_ENV`、`SCHOLARSENSE_ROLE`、`SCHOLARSENSE_ACCOUNT_REF`、
`SCHOLARSENSE_DATABASE_REF`、`SCHOLARSENSE_SECRET_REF`、
`SCHOLARSENSE_STORAGE_NAMESPACE`、`SCHOLARSENSE_EXTERNAL_BASE_URI`、
`SCHOLARSENSE_IDENTITY_ENABLED` 与 `SCHOLARSENSE_AUDIT_LEDGER_ENABLED`。启用任一能力时还必须
提供环境绑定的 `SCHOLARSENSE_CLOCK_SOURCE_REF`；同步状态、100ms 上限和 HMAC 审计检索密钥由
部署注入的受控端口提供，静态布尔值不能充当时间同步证据。

启用 audit worker 时还必须提供与当前环境及 v1 版本精确匹配的
`SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF`、`SCHOLARSENSE_AUDIT_HASH_PROFILE_REF`、
`SCHOLARSENSE_AUDIT_COLLECTOR_REF`、`SCHOLARSENSE_AUDIT_VERIFIER_REF`、
`SCHOLARSENSE_AUDIT_ALERT_TRANSPORT_REF`、`SCHOLARSENSE_AUDIT_METRIC_BINDING_REF` 和
`SCHOLARSENSE_AUDIT_RETENTION_CAPABILITY_REF`。
这些引用从 jar 内的版本化 `audit-runtime/*.properties` 资源解析出阈值、批次、租约和调度周期；
资源缺失、字段漂移、跨环境或旧版本引用均在 scheduler 启动前失败。时钟引用必须在装配时完整；
若部署证据提供器暂无新鲜证据，worker 保持存活，但所有主动取时、审计归集和高风险路径以
`AUDIT_TIME_SOURCE_UNAVAILABLE` 失败关闭，不会生成伪造的健康观测。告警进入受控结构化日志通道，指标进入 Micrometer。

- `web-api` 启动 HTTP 运行时，加载 identity-access 与只读 `AuditAvailabilityPort`，不启动 collector/verifier。
- `worker` 在 `SCHOLARSENSE_AUDIT_LEDGER_ENABLED=true` 时加载 identity-owned relay、中心 collector、
  verifier、15 秒 backlog 观测和 alert outbox 投递；它使用非 Web 运行时，不监听 HTTP 端口。
- 账户、数据库、密钥引用、存储命名空间和外部 URI 必须与 `dev/test/stage/prod` 环境一致。
- 错误只返回稳定代码与字段名，不回显配置值。

账本固定使用 `AUDIT-INGESTION-POLICY-1.0.0` 与 `AUDIT-LEDGER-HASH-1.0.0`。
完整性 finding code 为 `AUDIT_LEDGER_SEQUENCE_GAP`、
`AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH`、`AUDIT_LEDGER_ENTRY_HASH_MISMATCH`、
`AUDIT_LEDGER_HEAD_MISMATCH`、`AUDIT_INGESTION_DUPLICATE_CONFLICT`、
`AUDIT_INGESTION_CONTRACT_REJECTED` 和 `AUDIT_INGESTION_BACKLOG`；runbook URI 固定为
`runbook://audit/<code-kebab-case>`。chain/head 异常或 backlog blocked 后，不得修改账本来“修复”；
必须先处置根因，再执行一次从 genesis 开始的 full-chain verification，连续两次新鲜健康观测后
才允许高风险路径恢复。

该中心账本是在线 tamper-evident 控制：最小权限和 SHA-256 链能发现在线数据库内的删除或改写，
但数据库 owner 仍可实施篡改，因此它不是 WORM、外部时间戳服务或离线归档。Story 1.5 已交付
授权搜索 conformance、存储中立归档端口及匿名 fixture 的 retention 演练；capability manifest 仍固定
`productionAuthorizationEnabled=false`、`productionArchiveEnabled=false` 和
`deletionReceiptRuntimeIssuable=false`。这不能被解释为校方 WORM 已绑定或全域生产销毁已经完成。

完整启动和验证命令由仓库根 README 与 `scripts/` 质量入口统一提供。

本地 `verify.sh` 会通过两个 clean root 重放该 JAR 的确定性构建，但不会创建受信发布声明。只有受保护 release workflow 才能把 JAR digest 与其 SBOM、漏洞/许可证结果、GitHub provenance、artifact 签名、ReleaseManifest 和 EvidenceIndex 绑定，并在独立复验后执行 digest-only 提升。回退只能重新验证并重放已有的已签名 digest；不得从 Maven `SNAPSHOT` 坐标、文件名或裸 tag 推断发布身份。
