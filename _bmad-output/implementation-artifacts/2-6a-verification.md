# Story 2.6a Verification Artifact

- Story：`2.6a 贯通全链路 traceId 与技术遥测`
- 验证日期：`2026-08-16`
- 结论：`PASS — Story 2.6a local executable conformance`
- 字段字典：`OBS-1.0.0`
- 验证候选 commit：`002210b8f464587b4735a74044e2d2a097dc137e`
- 验证候选 tree：`8b4e740657343aa53eb7a1f5eb2dde9383d41278`
- 候选 parent：`db2c6f8ad6109b1da8157e4c949291b0e881d144`
- 候选生成方式：使用独立 Git index 创建 detached verification candidate；未切换、提交或重写用户分支与 index。
- 本文件在候选完成双重 clean release replay 后刷新，不作为该候选的运行时输入。

## 验收映射

| 验收项 | 可执行证据 | 结果 |
|---|---|---|
| AC-2.6a-HAPPY：关键链路共享 traceId | `DataBatchAtomicEvidencePostgreSqlIT#singleCausalChainTraversesTrustedIngressDurableJdbcCommandConsumerAndEgress` 在 PostgreSQL 18.4 上贯通真实 HTTP trust boundary → durable JDBC job → `SubjectWindowRecomputeProcessor` → `DataBatchCommandService` → persisted V2 outbox → strict decoder → consumer transaction → trusted external HTTP；`FullTraceBundleConformanceTest` 仅承担 SDK topology/privacy 扫描 | PASS |
| AC-2.6a-HAPPY：无学生明文、证据正文或密钥 | `ObservabilityPrivacySurfaceTest`、完整 trace bundle 值级扫描、`scan_privacy_canaries.py` 和 OBS invalid fixtures 覆盖 log、span export、metric export/scrape 与 event fixture | PASS |
| AC-2.6a-HAPPY：服务端授权、失败可见、审计 | HTTP correlation/security/error/audit 定向测试；`AuthorizationHttpSemanticsTest`、`AuthorizationAuditServiceTest`、`AuditSearchServiceTest` | PASS |
| AC-2.6a-HAPPY：幂等与 aggregateVersion 冲突 | DataBatch、outbox、consumer 的 duplicate/old/gap/poison/replay/conflict 合同与应用测试；不绕过 inbox/watermark/fence | PASS |
| IA-1/IA-6：字段、标签、隐私、W3C 演进 | `observability-contract-1.0.0.json`、schema、8 类 fail-closed fixtures/checker；span 运行时导出映射为 `service.name` 与冻结的 `scholarsense.*` 属性名；指标固定为 `scholarsense.operation.total` 与 `scholarsense.operation.duration`，禁止 traceId/object ID tag | PASS |
| IA-2：HTTP、安全、错误与审计唯一上下文 | trust-boundary/correlation filters 和单一 `HttpTraceContext`；合法受信 parent 继承，非法/全零/非受信输入生成干净 root | PASS |
| IA-3/IA-4：durable job、batch、outbox、event | V22 原子持久完整 `traceparent`、旧行 clean-root、每 attempt 独立 child、真实 PRODUCER parent、V23 双读 V1/V2 且单发 V2；V2 分别保留 batch 与 snapshot 的 `effectiveAt` | PASS |
| IA-5：受信外调 | 五条自定义 Java HTTP 适配器统一经 typed authority profile 创建的 `TrustedHttpClient`；dev/test 只允许显式 loopback sandbox，stage/prod fail closed；exact-host HTTPS allowlist、W3C/legacy 双写及 mTLS/Basic/签名/普通 HTTP conformance；治理构造器拒绝自动 redirect，避免传播头越过 allowlist | PASS |
| IA-7：两角色与 exporter outage | `ScholarSenseApplicationSmokeTest` 分别启动 `web-api`/`worker`，向真实不可达 collector 导出并断言 failure counter 与 `DEGRADED` health；业务 sentinel 保留，batch queue 上限 `2048` | PASS |
| IA-8：供应链与可重复证据 | backend lock v2、SBOM OTel purl 断言、runtime bundle、release manifest/evidence v10；provenance/SBOM evidence 同时绑定 kind 与 backend subject；两次 clean offline release replay | PASS |

## 冻结合同与摘要

所有摘要均为文件原始字节 SHA-256；v10 conformance 文档另标 canonical JSON 摘要。

| 输入 | SHA-256 |
|---|---|
| `contracts/observability/observability-contract-1.0.0.json` | `8bc685d2c233a90ce1a2fa34b85ef943ff4850917001aa48eb25ab87effc4cf8` |
| `contracts/observability/event-trace-context-compatibility-1.0.0.json` | `182c9b0da580e4580ab7cd77706d4d93dea7111123d251285aa1786426f702b8` |
| `contracts/config/observability-runtime-bundle-1.0.0.json` | `772cfa23ff246b72d34d1571818f5dd5cd3843b1d4d215c3d4e6a49e7fb01518` |
| `contracts/release/backend-lock-2.0.0.json` | `c594a01cc25d6145ef1159e0995c06ecc72213dd4e7f27174f6050c3a3ba2ad3` |
| predecessor `backend-lock-1.0.0.json`（只读） | `c71982099779c38bcdb2ccf922367ae329f12f79ec6de6606063dc12e8032b01` |
| `release-manifest-10.schema.json` | `c8a9199b466b78716e0030d79b18a301a65b608ea1f058d94a0a3a000a82bd77` |
| `evidence-index-10.schema.json` | `3ab4850f1dbc0fe7c937a5c12578e0e69b1208eb1e9b949e3a3f182e979d7560` |
| predecessor `release-manifest-9.schema.json`（只读） | `b24be7b2e029b57240b86d5fe808e161e48c01bf7d2e051956fe937d0edb9ff2` |
| predecessor `evidence-index-9.schema.json`（只读） | `5d8bea4fe0b148518119d97f897a1ee32e29a07f6ec5ab3d716c4f88fe12c55a` |

`ObservabilityRuntimeBundle` 绑定 dev/test/stage/prod 四个 profile 与 OBS 合同；角色集合固定为 `web-api`、`worker`，export queue 上限为 `2048`，owner transaction 禁止网络 I/O，export failure 保留业务真相。

## 验证命令与结果

### 后端与运行行为

- `cd backend && ../_bmad/scripts/with_pab_toolchain.sh mvn -q clean verify`
  - `261` 个 Surefire suite；`1168` tests、`0` failure、`0` error、`0` skipped。
  - 当前工作树 JAR：`1f679c7a998534566536287590634e0e607a341de0d94aca54443f93b05c2d2e`；clean release JAR：`537f01ddf880a69d7bce2ec11344d3f9a78961f0fee36e3dbc2eee19bd2ef4b6`。
- `_bmad/scripts/with_pab_toolchain.sh python3 scripts/check_backend_lock.py`
  - `PASS (101 runtime, 7 plugins)`。
- `_bmad/scripts/with_pab_toolchain.sh scripts/run_audit_postgresql_tests.sh`
  - PostgreSQL `18.4` clean + upgrade、actual-login、raw-DML 与 concurrency 全部通过。
  - Maven PostgreSQL selection：`190` tests、`0` failure、`0` error；包括完整单因果链真实生产边界测试。
  - migration inventory：`24`；clean/upgrade fingerprint：`81843ca6b13b0a8ff774b5e7d34c928d816c8585eec0849193a167d9680ff98a`；summary：`189|2608|3658|483|49|199`。

### 合同、供应链与回归

- `_bmad/scripts/with_pab_toolchain.sh python3 -m unittest discover -s scripts/tests -p 'test_release*.py'`
  - `105` tests，全部通过。
- `_bmad/scripts/with_pab_toolchain.sh python3 -m unittest scripts.tests.test_backend_lock_v2 scripts.tests.test_sbom scripts.tests.test_observability_contracts`
  - `20` tests，全部通过；SBOM fixture 包含 OTel exporter/bridge purl。
- `scripts/check_release_contracts.py`、`scripts/check_observability_contract.py`
  - 全部通过。
- `_bmad/scripts/tests` 全量：`145/145` 通过。
- `scripts/tests` 与全部合同 checker 已在两个 clean release replay 根目录各执行一次，均为 `611/611` 通过；工作树执行为 `610/611`，唯一失败是既有 `frontend/dist` 与 `frontend/node_modules` 触发生产树清洁门禁；排除生成目录后的隔离 delivery-quality 为 `19/19`。dev/test loopback 只按精确文件和片段豁免，新增 rogue endpoint 负例仍 fail closed。

### 两次 clean offline deterministic release replay

命令：从 detached candidate 的独立 shared clone 中执行 `scripts/build-release.sh <isolated-output>`。`build_release.py` 创建两个独立 clean 根目录；Maven 以 offline 参数执行，每个根目录运行完整 `verify_core --review`，前端各自再执行两次 clean offline replay，最后比较构件字节。

| 项目 | 值 |
|---|---|
| source commit | `002210b8f464587b4735a74044e2d2a097dc137e` |
| build input | `d0675c384cbb5a8ca57cb12ef8013f289f6fbdf483342a53a107cb4f72e72dfd` |
| attempt 1 artifact set | `92aa87a0542b5e558e2adfb291417b06a86fb71961f1e66118cff06033d0597a` |
| attempt 2 artifact set | `92aa87a0542b5e558e2adfb291417b06a86fb71961f1e66118cff06033d0597a` |
| release backend JAR | `537f01ddf880a69d7bce2ec11344d3f9a78961f0fee36e3dbc2eee19bd2ef4b6` |
| release frontend tar.gz | `e89437b7045c398d50d01e090f133f5e3eb1b1769679f42e992c87d717da1562` |
| frontend build | `48aa5b2f3f00aa44792eb36dab2d53d3639e22201729d93b74b9753183f0f184` |
| source manifest | `7084d9da32118f7c523486e6b7e0160efb57a98ab11f1c36cabfebf4b3a9a2bb` |
| 结果 | `build-release: PASS` |

### Release v10 additive successor

- v10 controlled inputs：`48`，在 v9 基础上增加 `ObservabilityContract`、`ObservabilityEventCompatibility`、`ObservabilityRuntimeBundle`，并要求 backend lock v2。
- 两次相同输入的 canonical generation 完全一致：
  - release manifest：`2ba08f1feacc931b144520682fb05ff13da2d13a173ae4f749e409692d4c123e`
  - evidence index：`ba6e9aebfb6821252e47b669051583bb79be56f8ceb754f37fe3f4a9646788ce`
- 这里的 v10 摘要是确定性合同生成 conformance；`backend-provenance`、`backend-sbom-cyclonedx` 是生产发布流水线要求的 evidence IDs，不把本地测试签名冒充已签署或已部署的生产 provenance。

## 明确限制与未声明范围

- `runtimeEvidenceClaim` 仅覆盖 `story-2.6a-observability-closure` 的本地可执行 conformance；没有声称 collector、dashboard 或告警已部署到生产。
- Story `2.6b` 的角色隔离运行面板、告警、runbook、跨角色深链负例以及 FR-53/NFR-24 最终验收：`none / 未声明`。
- Story `2.7a/2.7b/2.7c` 的降级 UI、业务重试/fencing/时效策略和全部生产者对账：`none / 未声明`。
- Story `2.8a/2.8b` 的完整自然月 99.9% SLI、灾备与恢复演练：`none / 未声明`。
- production-duration evidence：`none`；本 Story 不以测试时长替代生产时长证据。
- 本 Story 未创建生产 `RuleEvaluation`、`Candidate` 或 `Clue`，也未新建平行业务真相源。
