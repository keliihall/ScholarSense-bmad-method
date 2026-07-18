# Story 1.1b 验证记录

验证时间：2026-07-18T23:48:25+08:00  
工作区：`NO_VCS`（当前目录不是 Git 仓库，不提供伪造 commit、分支、远端或 provenance）

## 结论

Story 1.1b 的生产工程骨架及累计 29 条审查修复已在同一受控输入上连续完成两次清洁验证，两次命令均退出 0，测试计数和规范化清单完全一致。

本记录只证明本 Story 的静态边界、后端构建/测试、双角色 smoke、配置与结构负例。它不证明 1.1c/1.1d 所拥有的浏览器、供应链或生产提升证据。

## 实际工具链

| 工具 | 实际版本 | 取证命令 | 退出码 |
|---|---:|---|---:|
| OpenJDK | 25.0.3 | `_bmad/scripts/with_pab_toolchain.sh java -version` | 0 |
| Node.js | 24.18.0 | `_bmad/scripts/with_pab_toolchain.sh node --version` | 0 |
| npm | 11.16.0 | `_bmad/scripts/with_pab_toolchain.sh npm --version` | 0 |
| Apache Maven | 3.9.16 | `cd backend && ../_bmad/scripts/with_pab_toolchain.sh ./mvnw --version` | 0 |
| Maven Wrapper Plugin | 3.3.4 | `backend/pom.xml` 与 `backend/.mvn/wrapper/maven-wrapper.properties` 静态契约测试 | 0 |
| Spring Boot | 4.1.0 | `backend/pom.xml` 受控 parent/BOM 与构建日志 | 0 |

Maven Wrapper 发行包：`apache-maven-3.9.16-bin.zip`  
记录的 distribution SHA-256：`5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`

## 受控输入摘要

- Story 1.1a 在 Story 与 sprint 中均为 `done`。
- 1.1a 的资产清单、JSON Schema 和 PAB 差距报告均存在，`validate` / `check` 及 145 项审计回归通过。
- owner=1.1b 的 13 项受控处置均有非空方向与理由；本实现未复制 `unknown-blocked` 原型代码或安装树。
- G-01/PAB-1.0.0 的输入状态为 `approved-for-implementation`；这是静态实施输入，不记作运行测试通过。
- `docs/input/原型/frontend/**` 在本 Story 中保持 READ ONLY。

## 精确命令与结果

### Bootstrap

命令：`./scripts/bootstrap.sh`  
退出码：0

- 固定 Maven Wrapper 报告 Maven 3.9.16、Java 25.0.3。
- `frontend-structure: PASS`
- `contract-seeds: PASS`
- `production-pollution: PASS`

### 清洁验证 Run 1

命令：`./scripts/verify.sh`  
结束时间：2026-07-18T21:55:20+08:00  
退出码：0

| 验证项 | 结果 |
|---|---:|
| Maven `clean verify`（含编译、架构/迁移、配置、双角色 smoke、跨切面） | 31/31 通过 |
| 1.1a 审计回归 | 145/145 通过 |
| 1.1b Python 结构/合同/污染与负例 | 17/17 通过 |
| 独立前端结构检查 | PASS |
| 独立合同种子检查 | PASS |
| 独立生产污染扫描 | PASS |
| 规范化清单文件数 | 139 |
| 规范化清单 SHA-256 | `3c0ce7a312e49c2524cbf675245f39503306abeab500b71cb9abbde452ba6167` |

### 清洁验证 Run 2

命令：`./scripts/verify.sh`  
结束时间：2026-07-18T21:55:48+08:00  
退出码：0

| 验证项 | 结果 |
|---|---:|
| Maven `clean verify`（含编译、架构/迁移、配置、双角色 smoke、跨切面） | 31/31 通过 |
| 1.1a 审计回归 | 145/145 通过 |
| 1.1b Python 结构/合同/污染与负例 | 17/17 通过 |
| 独立前端结构检查 | PASS |
| 独立合同种子检查 | PASS |
| 独立生产污染扫描 | PASS |
| 规范化清单文件数 | 139 |
| 规范化清单 SHA-256 | `3c0ce7a312e49c2524cbf675245f39503306abeab500b71cb9abbde452ba6167` |

两次清单摘要一致；`backend/target`、Python cache、JAR/class 等生成物不进入规范化摘要。
最终两次运行已包含自审修正：端口 schema 与 Java 同步限定为 0—65535，架构守卫拦截无 `import` 的全限定跨域内部引用，且 `.gitignore` 不阻断 1.1c 未来提交已批准 lock。

### 审查修复后的最终清洁验证

审查清单中的 14 条 High 与 5 条 Medium 已逐条通过 RED→GREEN→完整回归并勾选。最终连续两次执行 `./scripts/verify.sh`：

| 验证项 | Run 1（2026-07-18T22:59:16+08:00） | Run 2（2026-07-18T23:00:42+08:00） |
|---|---:|---:|
| 命令退出码 | 0 | 0 |
| Maven `clean verify` | 35/35 通过 | 35/35 通过 |
| 1.1a 审计回归 | 145/145 通过 | 145/145 通过 |
| 1.1b Python 结构/合同/污染与负例 | 30/30 通过 | 30/30 通过 |
| 前端/合同/污染独立检查 | 全部 PASS | 全部 PASS |
| 规范化清单文件数 | 163 | 163 |
| 规范化清单 SHA-256 | `54538ac1ecbe1564419b96e584c7c0bc0f2e1e8b11ae00a6f823dec79d1f7409` | `54538ac1ecbe1564419b96e584c7c0bc0f2e1e8b11ae00a6f823dec79d1f7409` |

新增回归覆盖：受控 Spring 属性优先级、真实 JAR 路径、源码与编译产物架构逃逸、SQL 引号/别名/DDL 上下文、事实唯一 owner、动态 import 与环境变量多语法、敏感文件/JSON 凭据、配置与 envelope 深层语义、可移植工具链及符号链接清单边界。

### 新一轮审查修复后的最终清洁验证

新一轮 7 条 High 与 3 条 Medium 已逐条通过 RED→GREEN→完整回归并勾选。最终连续两次执行 `./scripts/verify.sh`：

| 验证项 | Run 1（2026-07-18T23:47:50+08:00） | Run 2（2026-07-18T23:48:25+08:00） |
|---|---:|---:|
| 命令退出码 | 0 | 0 |
| Maven `clean verify` | 36/36 通过 | 36/36 通过 |
| 1.1a 审计回归 | 145/145 通过 | 145/145 通过 |
| 1.1b Python 结构/合同/污染与负例 | 38/38 通过 | 38/38 通过 |
| 前端/合同/污染独立检查 | 全部 PASS | 全部 PASS |
| 规范化清单文件数 | 172 | 172 |
| 规范化清单 SHA-256 | `94a3aa896a2755d819f75bf4201ee199ee403d9e0a9db5355be9dbe3a8270991` | `94a3aa896a2755d819f75bf4201ee199ee403d9e0a9db5355be9dbe3a8270991` |

本轮新增回归覆盖：Java 注释/根包架构逃逸、隐藏/大小写/Unicode JSON 凭据、运行配置 URI 对齐、Maven `finalName`、SQL 别名作用域与索引语法、动态 import/环境变量绕过、namespace/`FieldError` 深层语义和单文件入口祖先符号链接。

后端测试报告位于 `backend/target/surefire-reports/`；最终 JAR 位于 `backend/target/scholarsense-backend-0.1.0-SNAPSHOT.jar`。

## 输出结构清单

- `backend/`：固定 Maven Wrapper、Spring Boot 同制品双角色入口、九模块四边界、shared 技术内核、AD-2 owner 注册表、空迁移目录、全部 JUnit 守卫与 test-only 跨切面 fixture。
- `frontend/`：`app/domains/components/shared`、九个 kebab-case domain 公开入口、集中 router/theme/config 扩展位和 client allowlist；无 `package.json` 或生产 lock。
- `contracts/config/`：JSON Schema 2020-12 非密配置和 dev/test/stage/prod 保留值示例。
- `contracts/openapi/`：OpenAPI 3.1.2 错误 envelope，`paths` 为空。
- `contracts/events/`：CloudEvents 1.0.2 基线的 envelope；线上 `specversion=1.0`，`data` 未定义业务 schema。
- `deploy/base/`：同 JAR 的 `web-api`/`worker`、健康探针与环境变量种子；无 CI 或产品编排清单。
- `scripts/`：bootstrap、两次可重放验证、前端/合同/污染检查和规范化清单工具。
- 根级 `README.md`、`.gitignore`、`.editorconfig`：最小使用、生成物隔离和一致格式约定。

## 已知未决项与唯一 owner

### Story 1.1c（pending）

- 精确 PAB 前端依赖组合、完整兼容性实测、生产 `package-lock.json` 与 lock 批准。
- Performance Profile ADR、浏览器/WebView、视觉与完整 WCAG 验收。
- 离线同摘要制品及真实前端 bundle/页面验收。

### Story 1.1d（pending）

- Git 源头/远端/commit 与可追溯 provenance 起点。
- CI 运行、SBOM、provenance、签名、漏洞与许可证扫描证据。
- 生产制品提升、发布环境批准与供应链运行证据。

上述项目均未在本 Story 中执行，不得从本记录推断为通过。
