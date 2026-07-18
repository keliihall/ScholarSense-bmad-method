# 后端工程决策

| 项目 | 固定方式 | 来源与理由 |
|---|---|---|
| OpenJDK 25 | Maven `release=25` + 项目工具链门 | PAB-1.0.0 批准基线；不得由本机其他 JDK 替代 |
| Spring Boot 4.1.0 | `spring-boot-starter-parent:4.1.0` | PAB-1.0.0 批准基线；统一管理 Spring、测试与构建插件解析版本 |
| Maven 3.9.16 | Maven Wrapper distribution URL + SHA-256 | Story 1.1b 工程决策，不是 PAB 原始条文 |
| Maven Wrapper 3.3.4 | `maven-wrapper-plugin:3.3.4` | Story 1.1b 工程决策；Apache-2.0，用于可重复启动 Maven |
| Actuator | Spring Boot 4.1.0 BOM | 只提供 health/liveness/readiness 种子，不代表生产观测验收 |
| Spring MVC + Tomcat | Spring Boot 4.1.0 BOM | `web-api` 的最小健康探针载体；不包含业务 API |
| Spring Boot Test | test scope，Spring Boot 4.1.0 BOM | JUnit 与 Spring 集成 smoke；不进入生产制品运行时 |

Maven 发行包 SHA-256 固定在 `.mvn/wrapper/maven-wrapper.properties`。依赖树由 Spring Boot
4.1.0 的受控 BOM/parent 解析；本 Story 未引入 Spring Modulith、ArchUnit、Flyway、数据库驱动或业务库。
