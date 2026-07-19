# ScholarSense 后端骨架

本目录是模块化单体的生产后端根，不包含真实业务 API 或数据库表。

从仓库根执行：

```bash
_bmad/scripts/with_pab_toolchain.sh backend/mvnw -f backend/pom.xml clean verify
```

The Maven coordinates remain `0.1.0-SNAPSHOT`, but the only runnable release artifact is the neutral, reproducible path `backend/target/scholarsense-backend.jar`. Release identity is its SHA-256 plus the signed ReleaseManifest version; the filename and Maven coordinate are not release identities.

`web-api` 与 `worker` 使用同一个 jar。两者都必须通过环境变量提供以下引用型配置：
`SCHOLARSENSE_ENV`、`SCHOLARSENSE_ROLE`、`SCHOLARSENSE_ACCOUNT_REF`、
`SCHOLARSENSE_DATABASE_REF`、`SCHOLARSENSE_SECRET_REF`、
`SCHOLARSENSE_STORAGE_NAMESPACE`、`SCHOLARSENSE_EXTERNAL_BASE_URI`。

- `web-api` 启动最小 HTTP 运行时，只暴露 Actuator health/probe 种子。
- `worker` 使用非 Web 运行时，不监听 HTTP 端口。
- 账户、数据库、密钥引用、存储命名空间和外部 URI 必须与 `dev/test/stage/prod` 环境一致。
- 错误只返回稳定代码与字段名，不回显配置值。

完整启动和验证命令由仓库根 README 与 `scripts/` 质量入口统一提供。

本地 `verify.sh` 会通过两个 clean root 重放该 JAR 的确定性构建，但不会创建受信发布声明。只有受保护 release workflow 才能把 JAR digest 与其 SBOM、漏洞/许可证结果、GitHub provenance、artifact 签名、ReleaseManifest 和 EvidenceIndex 绑定，并在独立复验后执行 digest-only 提升。回退只能重新验证并重放已有的已签名 digest；不得从 Maven `SNAPSHOT` 坐标、文件名或裸 tag 推断发布身份。
