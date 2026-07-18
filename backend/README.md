# ScholarSense 后端骨架

本目录是模块化单体的生产后端根，不包含真实业务 API 或数据库表。

从仓库根执行：

```bash
_bmad/scripts/with_pab_toolchain.sh backend/mvnw -f backend/pom.xml clean verify
```

`web-api` 与 `worker` 使用同一个 jar。两者都必须通过环境变量提供以下引用型配置：
`SCHOLARSENSE_ENV`、`SCHOLARSENSE_ROLE`、`SCHOLARSENSE_ACCOUNT_REF`、
`SCHOLARSENSE_DATABASE_REF`、`SCHOLARSENSE_SECRET_REF`、
`SCHOLARSENSE_STORAGE_NAMESPACE`、`SCHOLARSENSE_EXTERNAL_BASE_URI`。

- `web-api` 启动最小 HTTP 运行时，只暴露 Actuator health/probe 种子。
- `worker` 使用非 Web 运行时，不监听 HTTP 端口。
- 账户、数据库、密钥引用、存储命名空间和外部 URI 必须与 `dev/test/stage/prod` 环境一致。
- 错误只返回稳定代码与字段名，不回显配置值。

完整启动和验证命令由仓库根 README 与 `scripts/` 质量入口统一提供。
