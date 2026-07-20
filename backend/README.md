# ScholarSense 后端

本目录是模块化单体的生产后端根。当前已包含 identity-access 的会话/SSO API、
模块自有 PostgreSQL 表，以及与业务写入同事务提交的 `LocalAuditFact` + audit outbox。

从仓库根执行：

```bash
_bmad/scripts/with_pab_toolchain.sh backend/mvnw -f backend/pom.xml clean verify
```

真实数据库验收使用精确 PostgreSQL 18.4，并同时验证干净迁移与 V000001 升级路径：

```bash
scripts/run_audit_postgresql_tests.sh
```

The Maven coordinates remain `0.1.0-SNAPSHOT`, but the only runnable release artifact is the neutral, reproducible path `backend/target/scholarsense-backend.jar`. Release identity is its SHA-256 plus the signed ReleaseManifest version; the filename and Maven coordinate are not release identities.

`web-api` 与 `worker` 使用同一个 jar。两者都必须通过环境变量提供以下引用型配置：
`SCHOLARSENSE_ENV`、`SCHOLARSENSE_ROLE`、`SCHOLARSENSE_ACCOUNT_REF`、
`SCHOLARSENSE_DATABASE_REF`、`SCHOLARSENSE_SECRET_REF`、
`SCHOLARSENSE_STORAGE_NAMESPACE`、`SCHOLARSENSE_EXTERNAL_BASE_URI`。启用 identity-access 时还必须
提供环境绑定的 `SCHOLARSENSE_CLOCK_SOURCE_REF`；同步状态、100ms 上限和 HMAC 审计检索密钥由
部署注入的受控端口提供，静态布尔值不能充当时间同步证据。

- `web-api` 启动 HTTP 运行时；未启用 identity-access 时只暴露 Actuator health/probe 种子。
- `worker` 使用非 Web 运行时，不监听 HTTP 端口。
- 账户、数据库、密钥引用、存储命名空间和外部 URI 必须与 `dev/test/stage/prod` 环境一致。
- 错误只返回稳定代码与字段名，不回显配置值。

完整启动和验证命令由仓库根 README 与 `scripts/` 质量入口统一提供。

本地 `verify.sh` 会通过两个 clean root 重放该 JAR 的确定性构建，但不会创建受信发布声明。只有受保护 release workflow 才能把 JAR digest 与其 SBOM、漏洞/许可证结果、GitHub provenance、artifact 签名、ReleaseManifest 和 EvidenceIndex 绑定，并在独立复验后执行 digest-only 提升。回退只能重新验证并重放已有的已签名 digest；不得从 Maven `SNAPSHOT` 坐标、文件名或裸 tag 推断发布身份。
