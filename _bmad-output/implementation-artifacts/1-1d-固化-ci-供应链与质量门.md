---
baseline_commit: NO_VCS
---

# Story 1.1d：固化 CI、供应链与质量门

Status: in-progress

> 状态说明：静态 G-01/G-05/PAB-1.0.0 已批准，1.1a—1.1c 已完成；当前工作区仍不是 Git 仓库，且没有 CI、受控制品库、attestation store、受保护环境或提升端点。`ready-for-dev` 只表示可开始“U1 本地可复现构建合同”，不表示完成平台已具备。Git/CI/store/attestation/promotion 平台基线冻结并产生真实证据前，本 Story 不得进入 `review`/`done`，也不得把本地报告冒充 provenance、签名或生产提升证据。

## Story

作为发布工程师，
我希望让每个生产制品可复现、可追溯、可阻断，
以便只有绑定不可变源码身份、通过全部质量门并带完整可验证证据的制品才能被提升。

## 业务与交付价值

- 本 Story 把 1.1b/1.1c 的“本地可构建、两次清洁重放一致”提升为真实 CI 中的发布供应链：源码身份、构建、测试、SBOM、扫描、签名、provenance、正式发布矩阵、release manifest 和按摘要提升形成一条可复核链。
- 它是 1.2（门户壳）、1.3（原子审计）、1.9（公共任务/消息/回写契约）和 2.1（P0/P1 数据源目录）的共同工程前置；但不实现这些下游业务能力。
- NFR-33 的完整版本化发布基线由本 Story 负责且是最终 owner。NFR-8 只交付发布操作侧的幂等、重试、对账与回退证据，是 contributor，最终 owner 仍是 2.7c；NFR-13 只交付 CI/制品传输、存储、签名身份与密钥边界，是 contributor，应用敏感字段加密的最终 owner 仍是 1.8。
- G-01/G-05/PAB 的批准只解决“按什么实现”。任何运行证据失败都必须阻断 Story 完成和 Release Candidate 提升。

## 实施前置门槛

1. `sprint-status.yaml` 与 Story 文件中的 1.1c 均为 `done`，`1-1c-verification.md` 可读；必须消费其 FPB/PP/AP/TEST-ENV、精确 lock、浏览器制品摘要和 App `not-applicable` 决策，不得原位改写旧证据。
2. 所有 Java/Node/npm/Maven 命令继续通过 `_bmad/scripts/with_pab_toolchain.sh` 运行，并核对 JDK 25、Maven 3.9.16、Node 24.18.0、npm 11.16.0；CI 构建/扫描工具和第三方 Action 还须由独立供应链 lock 以版本、完整 SHA-256/OCI digest 和来源固定。
3. 开工时记录真实源码身份现状。当前为 `NO_VCS`；不得伪造 commit、remote、branch、tag 或 provenance。发布路径必须在真实 Git 仓库和受信远端上运行，且 `sourceCommit`、workflow path/ref、repository ID 和构建身份可验证；没有这些条件只能执行本地可重放检查，不能提升。
4. 发布工程需创建并批准 `CISB-1.0.0`（CI/Supply-chain Baseline）ADR。GitHub Actions + `actions/attest` + Sigstore/Cosign + Trivy 只是待平台 owner 评审的候选组合，不是开发者可自行采用的默认完成平台；GitHub.com/GHES、仓库类型、attestation store、artifact store、保护环境和 promotion adapter 必须按下节冻结，平台不支持时须先批准等价适配器，SLSA predicate、OIDC/KMS 身份、完整 SHA 固定、最小权限、证据字段和 fail-closed 语义不得改变。
5. 当前没有受控制品库、attestation store、环境保护规则或提升端点。实现者只能先完成 U1 的 provider-neutral 本地合同与负例；在完整平台基线批准、真实 digest-addressed store、受保护 release environment 和 digest-only promotion adapter 可用前，Story 不得进入 `review`/`done`。
6. `docs/input/原型/frontend/**` 始终只读；其 `dist`、`node_modules`、Vite 5 lock 和任何本机缓存均不得进入 SBOM、制品或证据链。
7. 1.1a 资产清单是创建 backend/frontend/contracts/deploy 之前的历史快照；对当前工作区执行其 `check/validate` 已因输入漂移退出 2，而现有 `verify.sh` 只运行该审计工具的 fixture 单测。不得覆盖旧清单或继续把它描述成当前有效；本 Story 必须生成新的版本化 release-source inventory，并让 CI 校验当前 inventory 与实际 source commit 一致。

## 完成平台冻结门

`CISB-1.0.0` 必须在 U2—U4 开始前由 Hei（accountable）与具名平台/安全/发布 Responsible 共同批准，且以下字段全部为不可变、可验证值；`TBD`、`GitHub 或等价平台`、`某制品库`、可移动 tag 或“由开发决定”均视为 `CISB_PLATFORM_BASELINE_INCOMPLETE`：

| 冻结域 | 必填决策与完成证据 |
|---|---|
| Git / repository | GitHub.com 或精确 GHES hostname/version；repository ID/URL、public/private/internal、套餐/edition、default branch、受保护 ref/tag pattern、required checks、CODEOWNERS/审批规则和真实 source commit 获取方式 |
| CI / trust | workflow engine/version、workflow path/ref、runner OS/image OCI digest、是否 self-hosted、OIDC issuer、builder identity、最小 permissions、fork/PR 隔离、release environment 与具名审批组 |
| Artifact store | 产品/endpoint/namespace、digest URI 语法、write-once/CAS 语义、retention、TLS/静态加密、读写身份与从 URI 回读原字节的 API；临时 CI artifact 不能冒充长期不可变 store |
| Attestation / signing | GitHub Artifact Attestations 或等价 store 的可用性/套餐、subject namespace、上传/查询/验证 API、期望 issuer/certificate identity 或 KMS keyRef/keyVersion、信任根和失效/轮换规则 |
| Promotion | adapter 产品/version、stage/prod endpoint 与环境 ID、`releaseVersion + targetEnvironment` 幂等/CAS 机制、approval identity、不可变 ledger URI、对账与 rollback API |
| Identity separation | build、artifact signer、manifest signer、verifier、promotion 五类实际 identity/principal、最小权限矩阵、OIDC audience/issuer 或 keyRef、禁止的互相 assume/写权限及审计查询证据 |

- 开发者可以实现候选 adapter 和 fixture，但无权用候选值批准 `CISB-1.0.0`。任一域缺失时只能验收 U1，本 Story 总状态保持 `ready-for-dev`/`in-progress`。
- 进入 `review` 的硬门是 U1—U4 全部通过、至少一次真实受保护 release run 和一次 digest-only 提升后从目标 store 回读验真；`dry-run`、本地 signer、内存 adapter、临时 CI artifact 或自填 provenance 均不满足。
- App/WebView 对本 Story 的 N/A 已通过规划 companion `AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 正式同步；该裁决不补足上述平台能力，也不取消 NFR-31/Story 7.1/7.x 的未来真机责任。

## NFR 责任与独立验收单元

| NFR | 1.1d 角色 | 最终 owner |
|---|---|---|
| NFR-8 | contributor：发布提升的幂等、重试、对账与回退 | 2.7c |
| NFR-13 | contributor：CI/制品传输与存储、签名身份和密钥边界 | 1.8 |
| NFR-33 | final owner：统一、版本化、可验证的 ReleaseManifest 与发布证据图 | 1.1d |

本 Story 保持一个目标和一个文件，但实施/验收拆成四个可独立重放并分别记录 verification 的单元：

| 单元 | 可独立验收结果 | 开始/完成条件 |
|---|---|---|
| U1 可复现构建合同 | 双 clean root 的最终 JAR/前端归档/BuildManifest 摘要一致；canonical/schema/lock 负例通过 | 当前即可开发；只证明本地合同 |
| U2 受信供应链 CI | 真实受保护 CI 对同一 artifact digest 生成 SBOM、scan、provenance、attestation、artifact signature | 需完整 CISB 与真实 Git/CI/store/signing |
| U3 真实 promotion | 独立 verifier 通过后 digest-only CAS 提升、对账与 rollback | 需 U2、U4 汇合并封存 EvidenceIndex；需真实保护环境/端点 |
| U4 正式 Web 证据 | 直接测试 store 中待提升前端 artifact digest，生成浏览器/视觉/无障碍报告 | 需完整 CISB、只读 golden 与精确 TEST-ENV runner |

整体完成公式为 `done = U1 ∧ U2 ∧ U3 ∧ U4`。U2 与 U4 必须绑定同一 selected artifact set，之后才能封存 ReleaseManifest；任何单元的本地 fixture、dry-run 或部分通过均不得替代整体 `review/done` 门。

## Acceptance Criteria

### AC-1.1d-HAPPY：运行 CI 与制品提升（上游原文）

**Given** 1.1c 的生产基线 ADR 已批准且供应链证据齐备  
**When** 发布工程师运行 CI 与制品提升  
**Then** CI 完成构建、测试、依赖与漏洞检查、SBOM、签名和制品提升，任一必需证据失败即阻断  
**And** 同一提交可重复制得同摘要制品，并保存批准基线和完整证据链接。

规范解释：上游 Given 中“供应链证据齐备”指运行 release pipeline 所需的已批准基线、可信输入与平台能力已经可用；SBOM、扫描、provenance、签名和提升回执由本次 release run 按顺序生成并门控，不是开工前已存在的本 Story 产物。

以下 AC 是为满足该复合 AC、NFR-8/NFR-13/NFR-33 与 AD-28 提出的候选可执行设计；`CISB-1.0.0` 批准后成为本 Story 的实现合同。具体工具和平台属于可替换实现决策，但证据完整性、不可变身份与 fail-closed 语义不可弱化。

### AC-1.1d-PLATFORM-FREEZE：完成平台由 accountable owner 冻结

**Given** 当前没有 Git、CI、artifact/attestation store、受保护环境和 promotion endpoint，候选平台能力因 GitHub.com/GHES、仓库类型与套餐而不同  
**When** Story 申请执行 U2—U4 或进入 `review`  
**Then** `CISB-1.0.0` 必须完整冻结“完成平台冻结门”表中的 provider、repository、runner、identity、store、attestation、environment 和 promotion adapter，具名记录 approvedBy/effectiveAt/证据 URI，开发者不得自行选择或用占位值补齐  
**And** 任一字段缺失、平台能力不可用或只存在本地/fixture adapter 时以 `CISB_PLATFORM_BASELINE_INCOMPLETE` fail closed；只允许 U1 本地合同继续，Story 不得进入 `review`/`done`。

### AC-1.1d-SOURCE-CI-TRUST：不可变源码身份与受信 CI

**Given** 当前工作区没有 Git/CI 元数据，发布工作流将接触写权限、OIDC 与制品凭据  
**When** CI 执行 PR 验证或受保护 release 工作流  
**Then** PR 工作流默认只读、不得取得签名/提升身份；release 只接受受保护 tag/ref 或具审批的环境，拒绝 fork/untrusted PR、dirty tree、浅克隆来源不明、tag/commit 不一致和 `NO_VCS`  
**And** workflow 级 `permissions` 最小化，只有 attestation/signing job 可短时取得 `id-token: write`、`attestations: write` 和必要的 artifact metadata/package 权限；构建/测试 job 无写权限，签名 job 不执行未受信源码  
**And** 所有外部 Actions 使用经审阅的完整 commit SHA，不使用 branch、可移动 tag 或 `latest`；所有构建/扫描镜像使用 OCI digest，工具二进制先校验厂商 checksum/签名再执行  
**And** CI 记录 repository ID/URL、source commit、ref、workflow path/ref、run/attempt、runner/容器 digest、触发者与审批环境，任何缺失或漂移以稳定 machine code 非零退出。

### AC-1.1d-REPRODUCIBLE-ARTIFACTS：两个清洁构建产生相同生产摘要

**Given** 同一 immutable source commit、PAB/FPB、Maven/npm lock、构建镜像 digest 和 `SOURCE_DATE_EPOCH`  
**When** 两个相互隔离、无共享工作目录的 clean build 分别执行完整验证与打包  
**Then** 后端可执行 JAR、前端静态发布包和仅含确定性构建输入/输出的 `BuildManifest` 规范化 SHA-256 逐项相同；比较的是最终待提升字节，不得只比较源清单或中间 `dist` 目录  
**And** Maven 固定输出时间与插件/依赖解析结果；前端继续 `npm ci --ignore-scripts`、精确 lock 和预算门；发布归档固定文件顺序、路径、mode、uid/gid、mtime、压缩参数与 locale/timezone，不直接依赖不同平台 `tar` 的默认元数据  
**And** Maven 依赖、插件、Wrapper 及 npm 完整解析树均进入机器可读 dependency lock，保存坐标、来源、checksum/integrity；缺失 checksum、动态/SNAPSHOT 外部依赖、非批准 registry/repository、安装脚本未批准或两次摘要不同即阻断；现有项目坐标 `0.1.0-SNAPSHOT` 可保留，但生产制品身份只认中性文件名、摘要与 ReleaseManifest releaseVersion  
**And** 构建前、安装后、测试后和打包后均验证受控源码未变；生成物只出现在隔离输出目录，源树不得留下 `target/node_modules/dist/coverage/playwright-report/test-results/release-out`。

### AC-1.1d-SBOM-SCAN：完整 SBOM、漏洞与许可证门

**Given** 后端、前端和发布工具依赖均已从精确 lock 解析，构建产物摘要已冻结  
**When** CI 为每个生产制品和聚合 release 生成 SBOM 并执行扫描  
**Then** 至少生成 schema-valid CycloneDX JSON 与 SPDX JSON，记录组件 purl、版本、直接/传递关系、license、hash、生成工具版本/摘要、sourceCommit 和对应 artifact SHA-256；SBOM 必须与 Maven/npm 解析树对账，漏项、重复歧义、未知来源或 subject digest 不匹配均失败  
**And** 漏洞扫描记录 scanner 版本、已验证二进制/镜像 digest、vulnerability DB digest/更新时间、severity 与受影响 artifact；Critical/High 必须修复，或存在具名 owner、理由、影响判断、到期日和证据链接的版本化例外，过期/无 owner/无 artifact 绑定的例外无效  
**And** 许可证使用显式 SPDX expression allowlist/denylist；UNKNOWN、Forbidden、Restricted 或无法解析表达式默认阻断，除非有同等版本化、限时批准；前端 `@vitejs/plugin-vue`、`@types/node` companion 和所有 `hasInstallScript`/Maven plugin 必须得出最终 `approved|rejected` 结论  
**And** Trivy 不得使用 2026 年供应链事件涉及的 v0.69.4、Docker v0.69.5/v0.69.6 或可移动 tag；采用经签名/checksum 核验并精确固定的安全版本，扫描工具自身也进入 release toolchain lock 和 SBOM。

### AC-1.1d-ATTEST-SIGN：SLSA provenance、SBOM attestation 与制品签名

**Given** 两次 clean build 摘要一致且所有测试/扫描门已通过  
**When** 受信 release job 为待提升制品生成 provenance、attestation 和签名  
**Then** 每个 artifact 的 SLSA Build Provenance 使用 `predicateType=https://slsa.dev/provenance/v1`，subject name/digest 与 release manifest 一致，包含可验证 builder/workflow/source identity、build definition、resolved dependencies 和 run details；本地自填 JSON 不算受信 provenance  
**And** CycloneDX/SPDX SBOM 作为同一 artifact digest 的 attestation 保存；每个制品先生成外置 Sigstore bundle 或学校 KMS 等价签名，最终 release manifest 冻结后再生成其外置签名；验签固定期望 certificate identity、OIDC issuer/workflow 或 KMS keyRef/keyVersion  
**And** 私钥、OIDC token、KMS 凭据和长期 secret 不进入仓库、镜像、缓存、日志、artifact、SBOM 或事件；签名失败、身份/issuer 不匹配、bundle 缺失、subject digest 漂移或重放旧签名均阻断  
**And** promotion 前在一个与构建 job 分离的 verifier 中从制品位置重新下载并验 hash、签名、provenance 与 SBOM attestation，不能只信上游 job output 字符串。

### AC-1.1d-RELEASE-MANIFEST：统一、版本化且诚实的发布清单

**Given** NFR-33/AD-28 要求所有生产依赖、政策和公共契约进入同一发布基线  
**When** CI 生成 `ReleaseManifestVersion` 并进行 schema/checker 验证  
**Then** 确定性 `BuildManifest` 只保存 source、lock、toolchain 与 artifact 输入/输出；选定摘要相同的获胜制品且其全部 evidence/signature 已存在后，一次性生成并冻结不可变 `ReleaseManifest`，再为其 canonical digest 生成外置签名；manifest 保存 release/version、source identity、PAB/FPB/CISB、toolchain/runner/image/lock digest、两次 build attempt、各 artifact name/mediaType/size/SHA-256/store URI、测试/扫描/SBOM/provenance/artifact-signature/report URI 与摘要、批准人和生效时间  
**And** 同一 manifest 固定 Rule、Queue、BusinessCalendar、WorkVisit、CareActionCatalog、SeasonalProgramMatrix、AcademicCareNodeSet、RetentionSchedule、RoleField、StrategyGate、QualityRecovery、EvidenceSchema、PerformanceProfile、Availability、TransferSla、MetricPublication policy 以及公共契约/受控枚举版本、生效日期和证据链接  
**And** 显式保存 `appApplicabilityBaselineVersion=AAB-1.0.0`、`appApplicabilityBaselineSha256`、`appDecisionId`、`UXBaselineVersion`、`visualBaselineVersion=VGB-1.0.0`、`uiTokenManifestVersion/Uri/Sha256`、`brandAssetManifestVersion/Uri/Sha256`、`testEnvironmentVersion/Sha256` 与 `canonicalizationProfile`；每项分别记录 `baselineStatus` 与 `runtimeEvidenceStatus`，尚未由下游 Story 实测的项目保持 `pending-story-execution`，App 保持批准的 `not-applicable + runtimeEvidenceClaim=none`，不得把静态批准或 N/A 写成运行通过  
**And** JSON 服从 `SCHOLARSENSE-JSON-SCHEMA-SUBSET-1.0.0` 与 `SCHOLARSENSE-CANONICAL-JSON-1.0.0`；任何 artifact/evidence/policy 变化必须产生新的 `ReleaseManifest` version。ReleaseManifest 不得引用自身签名、EvidenceIndex 或 promotion；提升 actor、run/attempt、状态和回退只写独立的 `PromotionRecord/Ledger`，不得回写 manifest。

### AC-1.1d-CANONICAL-SCHEMA：唯一 canonical digest 与 Schema 方言

**Given** 当前项目已有 sorted-JSON 摘要与只支持 Draft 2020-12 子集的校验器，release 对象不能再产生第三套不兼容实现  
**When** BuildManifest、ReleaseManifest、EvidenceIndex、PromotionRecord、lock 或 evidence 计算摘要并执行 schema/语义验证  
**Then** 唯一摘要协议为 `SCHOLARSENSE-CANONICAL-JSON-1.0.0`：原始输入必须是无 BOM 的 UTF-8；解析时拒绝重复 key、lone surrogate、NaN/Infinity、`-0`、浮点数和超出 `[-9007199254740991,9007199254740991]` 的整数，十进制业务量必须用 schema 约束的字符串；不做 Unicode normalization；对象 key 按 Unicode code point 升序递归排序、数组顺序保留；使用等价于 Python `json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")` 的字节，并输出 lowercase SHA-256 hex  
**And** 只有各 schema 明确命名的自摘要字段在计算该对象摘要前移除；其余字段不得任意排除。Python/Node/独立 verifier 必须消费同一 helper 或逐字节等价的跨语言 fixture，并覆盖中文/non-BMP、key 顺序、数组、边界整数、重复 key、非法 Unicode/number；1.1c 已冻结历史摘要不得按新 profile 重算  
**And** 唯一 schema 方言为 `SCHOLARSENSE-JSON-SCHEMA-SUBSET-1.0.0`，只允许当前实现已支持的 `$schema`、`$id`、`title`、`description`、`type`、`const`、`enum`、`properties`、`required`、`additionalProperties`、`items`、`minItems`、`maxItems`、`uniqueItems`、`minLength`、`pattern`、`format`、`minimum`、`maximum`；`format` 只允许且必须验证 `date-time`，其他 format、远程 `$ref`、`oneOf/anyOf/allOf` 等未实现关键词一律拒绝；URI、SHA-256、跨字段、无环/顺序/CAS 约束由唯一 semantic checker 以稳定错误码 fail closed。

### AC-1.1d-EVIDENCE-LIFECYCLE：发布证据图唯一无环顺序

**Given** 两次 clean build 已选出同摘要 artifact set  
**When** 生成证据、ReleaseManifest、签名、EvidenceIndex 与提升记录  
**Then** 严格按以下顺序执行：`双构建选定字节 → digest-addressed store 上传并回读验 hash → 对该 digest 生成 SBOM/scan → 生成 artifact provenance/SBOM attestation/artifact 外置签名 → 正式 Web 入口回读并验真同一 artifact 后生成报告 → 一次性冻结 ReleaseManifest → 生成 manifest 外置签名 → 生成以 manifest digest 为 subject 的不可变 EvidenceIndex → 独立 verifier 从 store 回读验证 → digest-only CAS promotion → 只追加 PromotionRecord/Ledger`  
**And** ReleaseManifest 只引用冻结前已存在的 artifact evidence/signature，不含自身签名、EvidenceIndex 或 promotion；EvidenceIndex 引用 manifest 外置签名和全部 evidence，manifest 不反向引用 EvidenceIndex；PromotionRecord 引用 manifest/EvidenceIndex digest 且绝不回写既有对象  
**And** checker 拒绝自引用、反向引用、环、越序对象、EvidenceIndex subject 漂移，以及同一 `releaseVersion` 出现第二个不同 manifest digest；失败不得产生可见的半成品或 promotion。

### AC-1.1d-PROMOTION-ROLLBACK：按摘要提升、幂等、对账与回退

**Given** release bundle 已进入 digest-addressed store 且所有必需证据可读取  
**When** 发布工程师申请提升或重放同一提升请求  
**Then** promotion gate 从存储重新验证 manifest、manifest 外置签名、EvidenceIndex、artifact、SBOM、扫描策略/例外、provenance、artifact 签名和正式报告；只接受 digest，不接受裸 tag/文件名作为身份，任何缺失、失效、篡改或 store URI 指向不同字节均拒绝  
**And** `releaseVersion + targetEnvironment` 为幂等业务键：同键同 manifest digest 重放原结果，同键异 digest 冲突；并发只允许一个胜者，失败不得留下部分 promoted 状态  
**And** 提升记录保存 from/to 环境、artifact/manifest/EvidenceIndex digest、actor/approver、run/attempt、时间、结果与证据 URI；构建存储与提升身份分离，未经审批的 job 无提升权限  
**And** 回退只能选择已验证、签名且仍满足当前强制策略的既有 digest，不重新构建或改写历史；对账能发现 store、attestation service 与 promotion ledger 的缺失/多余/水位不一致并 fail closed。

### AC-1.1d-FORMAL-WEB-EVIDENCE：正式品牌浏览器、视觉与无障碍发布报告

**Given** TEST-ENV-1.0.0 已冻结目标 OS、Chrome/Edge 当前及前一 major 的不可变 URL 与 artifact/executable SHA，`VGB-1.0.0` 已在本次 release run 前获批且学校 App 按 `AAB-1.0.0` 为 `not-applicable`  
**When** release CI 在精确匹配的浏览器/OS 环境执行正式基线矩阵  
**Then** 独立正式入口 `run-formal-web-evidence` 只接受 artifact store URI、expected frontend artifact SHA-256、selected BuildManifest digest 与 TEST-ENV digest；从 store 下载待提升归档，验证 size/mediaType/hash/signature/provenance，安全解包到只读临时目录并直接服务这些冻结字节。正式路径禁止执行 `npm run build`、`vite build`、读取工作区源码/`dist` 或把当前源码重建结果作为测试 subject；报告 `subjectArtifactSha256` 必须等于 ReleaseManifest 与 promotion gate 使用的前端 digest  
**And** Chrome 150/149 与 Edge 150/149 分别覆盖 1440×900、1366×768、375×812、320px reflow、200% zoom、键盘/焦点、非颜色状态、等价表格/live region、axe/WCAG baseline、视觉差异、reduced-motion、资源/base-path/console/network error，并生成绑定 source/artifact/BuildManifest/TEST-ENV/runner/browser executable digest 的机器报告和可读摘要  
**And** `VGB-1.0.0` 为版本化只读 golden manifest：每个 OS/browser 完整版本与 executable digest/page/viewport 都有预先存在的 golden URI/SHA-256；固定 `deviceScaleFactor=1`、`locale=zh-CN`、`timezone=Asia/Shanghai`、light color scheme、clock/data/network、实际字体文件/runner image digest，等待 `document.fonts.ready`，禁用 animation/transition、隐藏 caret、固定 reduced-motion，并以 `threshold=0.1,maxDiffPixels=0` 比较且保存 golden/actual/diff digest  
**And** release workflow 禁止 `--update-snapshots` 或本次 run 生成 expected 后自验；golden 只能由独立受保护更新工作流发布新版本，并取得具名 UX/品牌 owner 与 Web QA 双审批。本次 run 之前不存在/未批准、摘要漂移、字体/OS/browser 漂移或阈值漂移均 fail closed  
**And** 1.1c 的 `optional-brand-preflight-not-formal-report` 及会从当前源码重建的 `run-brand-preflight.mjs` 保持原分类，只能作为本地预检，不能改名或扩展成正式报告。正式报告只证明生产启动面发布基线，不声称 1.2 尚未实现的门户/SSO/业务页已通过最终 Web/WCAG  
**And** App/WebView 继续携带 `AAB-1.0.0 / USER-2026-07-19-SCHOOL-APP-NA` 与 `runtimeEvidenceClaim=none`；不得用桌面 Chromium 代替真机，也不得生成虚假 pass。

### AC-1.1d-SECURITY-EVIDENCE-BOUNDARY：NFR-13 供应链贡献与证据诚实性

**Given** 制品、SBOM、报告、签名 bundle 和 promotion ledger 可能含敏感供应链元数据  
**When** CI 存储、传输、记录或验证这些对象  
**Then** 制品/证据端点强制 TLS，存储加密和访问控制证据可定位，签名/KMS/OIDC 身份版本化且最小权限；日志只记录 digest、keyRef/identity 与稳定错误码，不记录 token、私钥、secret 或不必要业务数据  
**And** `check_production_pollution`/workflow checker 覆盖 `.github`、release contracts/policies/scripts，拒绝原始凭据、私钥、未固定 Action/镜像、危险 PR 写权限、secret 回显和未批准外部上传  
**And** 本 Story 只声明 NFR-13 的发布供应链贡献；不宣称 1.8 的业务字段投影/加密、6.6 的保留销毁、2.8a 的完整自然月 SLI、6.5 的 DR 或真实生产业务部署已通过。

## Tasks / Subtasks

- [x] Task 0：执行开工门、现实复核与 CI/制品平台冻结（AC: HAPPY, PLATFORM-FREEZE, SOURCE-CI-TRUST）
  - [x] 核验 1.1c `done`、最新 verification、G-01/G-05/PAB/FPB/PP/AP/TEST-ENV 摘要和 companion pending 清单；重放当前 `./scripts/verify.sh`。
  - [x] 记录当前 `NO_VCS`、无 CI/制品库/提升平台事实；取得真实 repository/commit/remote 后才启用 release job。缺失时实现 fail-closed 合同但不得伪造运行证据。
  - [x] 用 1.1a inventory 内记录的 replay 参数分别执行 `audit_production_assets.py check` 与 `validate`，把当前预期退出码 `2` 和“受控输入漂移”诊断保存为历史快照已过期的开工事实；该预期失败不得并入总体验证的绿色门。保留历史产物，新建绑定真实 source commit 的 release-source inventory，并改由统一入口自动校验新 inventory。
  - [x] 新建 `CISB-1.0.0` ADR，并由 Hei（A）及具名平台/安全/发布 Responsible 批准“完成平台冻结门”的全部实际值、能力探测与证据 URI；GitHub Actions、`actions/attest`、Cosign、Trivy 只作候选，开发者不得自行批准。若 GitHub.com/GHES、仓库类型/套餐或 Artifact Attestations 能力不匹配，必须先批准等价 adapter/store/signer，不能继续引用不可用能力。
  - [x] 对 CISB 执行机器门：真实 repository/workflow/store/attestation/signing/promotion/verifier 身份与权限可查询，受保护 ref/environment 生效，artifact store write-once/CAS 与回读、attestation subject 查询、promotion 条件写/ledger 均通过；任一占位或能力缺失只允许 U1。
  - [x] 核验工具当前安全状态：Trivy 精确安全版本与 Sigstore bundle、Cosign v3.1.2 当前候选及 bundle 语义、`actions/attest` 当前 major；所有外部 Action 转成完整 commit SHA 并保留对应 release 注释。版本只作开工候选，CISB 批准时必须重新核验。

- [x] Task 1：先建立 release schema、canonical/profile policy 与 fail-closed RED 契约（AC: 全部）
  - [x] 在 `contracts/release/` 固定 `canonical-json-profile-1.0.0.json`、`schema-subset-profile-1.0.0.json`，以及 toolchain lock、backend dependency/plugin lock、license/vulnerability exception、BuildManifest、ReleaseManifest、EvidenceIndex、PromotionRecord、release-source inventory、formal Web report、visual baseline、UI token/brand manifest 的 schema 与正负 fixture；全部 schema 自身先通过受控 subset checker。
  - [x] 建立唯一 canonical/semantic helper 与跨 Python/Node/verifier test vectors，证明相同输入产生逐字节相同 canonical bytes/digest；拒绝 duplicate key、非法 Unicode/number、自摘要字段误排除、未知/未实现 schema keyword/format、远程 `$ref`、证据图环和同版本异 manifest digest。
  - [x] 为 `NO_VCS`/dirty/ref 漂移、mutable Action/tag、过宽权限、未固定镜像、重复 JSON key、未知字段、占位词、绝对本机路径、digest/URI/subject 不匹配编写 RED 测试。
  - [x] 为漏 SBOM 组件、Critical/High 无有效处置、UNKNOWN/禁止许可证、过期例外、安装脚本未批准、签名 identity/issuer 错误、旧 bundle 重放、部分提升与并发冲突编写 RED 测试。
  - [x] Schema 不得用一个泛化 `sha` 字段混装身份：分别定义并校验 `actionCommitOid`、`binarySha256`、`ociDigest` 及其来源/解析语义。
  - [x] 负例以临时 fixture/复制目录执行，不改写受控 Story/manifest；所有检查器错误码稳定、非零并保持输出原子性。

- [x] Task 2：建立确定性后端/前端生产打包与双构建硬门（AC: REPRODUCIBLE-ARTIFACTS）
  - [x] Maven Wrapper 的唯一合法路径为 `backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/**`；所有正式命令从项目根显式调用 `backend/mvnw -f backend/pom.xml ...` 或进入 `backend/` 调用，不得引用不存在的根级 `mvnw/.mvn`。
  - [x] 保留现有 Maven 项目坐标 `0.1.0-SNAPSHOT`，在 `backend/pom.xml` 增加 `<finalName>scholarsense-backend</finalName>` 与 reproducible `project.build.outputTimestamp`，将唯一运行制品路径固定为 `backend/target/scholarsense-backend.jar`；发布身份只认 artifact SHA-256 + ReleaseManifest releaseVersion，不认项目 SNAPSHOT 坐标或文件名。
  - [x] JAR 路径调整必须作为单一原子变更更新 `backend/pom.xml`、`deploy/base/roles.json` 的 `web-api/worker` 两个 role、`scripts/tests/test_check_contract_seeds.py`、`backend/src/test/**/BuildRootContractTest.java`、`backend/README.md`、release-source inventory/BuildManifest fixtures 与相关 lock/checker；保留“只改一个 role、旧 SNAPSHOT 路径、错误 finalName”的负例，不改写 1.1b 历史 verification。
  - [x] 生成并校验含 Maven 外部依赖/plugin/Wrapper 坐标、来源与 checksum 的 backend lock，拒绝外部 SNAPSHOT/动态解析。
  - [x] 复用 `verify_frontend.sh` 的两次隔离离线重放与 `check-build-budget.mjs`，不要重写第二套 npm 安装/摘要逻辑；将最终 `dist` 通过规范化归档器生成前端发布包。
  - [x] 创建单一 `build-release` 入口，在两个 clean root 中调用非递归 `verify-core`、完成后端 JAR/前端包打包并硬比较最终 artifact 与确定性 BuildManifest digests；固定 locale/timezone/mtime/mode/order/压缩参数。禁止 `build-release → verify.sh → build-release` 递归。
  - [x] 验证构建过程不修改源码/lock，原型/缓存/报告/测试 secret 不进入制品，失败不保留半成品。
  - [x] 更新 `.gitignore` 排除本地 `release-out`、SBOM、provenance、signature 和正式报告临时物；schema、policy、fixture、工具 lock 和 ADR 必须继续受控。

- [x] Task 3：生成、对账并扫描 SBOM（AC: SBOM-SCAN）
  - [x] 用已验证并精确固定的 Trivy 0.72.0（或经 CISB 新版本批准的更安全替代）为每个 artifact 和聚合 release 生成 CycloneDX/SPDX JSON；禁止 v0.69.4 及受影响 Docker v0.69.5/v0.69.6。
  - [x] 对账 SBOM 与 `npm ls --all`、frontend lock、Maven dependency/plugin lock 和实际产物；固定 purl、license expression、component hash、tool/db digest 与 subject artifact digest。
  - [x] 发布显式 vulnerability policy/exception schema 和 SPDX license allow/deny policy；Critical/High、UNKNOWN/禁止许可证与过期例外负例全部 fail closed。
  - [x] 对 `@vitejs/plugin-vue`、`@types/node`、npm lifecycle scripts、Maven plugins/Wrapper 做最终来源、checksum、漏洞与许可证裁决，并把结论写入 release evidence，不回写伪造的 1.1c 历史通过项。当前 lock 的三项 `hasInstallScript`（嵌套 `vue-demi` 与两份可选 `fsevents`）继续由 `--ignore-scripts` 禁止执行；未来启用须单独批准。
  - [x] 许可证策略显式裁决当前已见 MIT、Apache-2.0、BSD-2-Clause、BSD-3-Clause、ISC、0BSD、MPL-2.0；不得因 lock 中有 license 字段就自动批准，NOTICE/源码披露义务也须进入发布证据。

- [x] Task 4：实现分层 BuildManifest、ReleaseManifest 与 EvidenceIndex（AC: RELEASE-MANIFEST, CANONICAL-SCHEMA, EVIDENCE-LIFECYCLE, SECURITY-EVIDENCE-BOUNDARY）
  - [x] 新建 BuildManifest/ReleaseManifest/EvidenceIndex schema、checker 与 generator：BuildManifest 仅含可复现构建输入/输出并参与双构建摘要比较；ReleaseManifest 仅在 selected artifact digest 的全部 evidence/artifact signature 已存在后一次性冻结，纳入两次 attempt、source/PAB/FPB/CISB/AAB/UX/VGB/TEST-ENV/toolchain/locks/artifacts/UI-token/品牌资产与 AD-28 全部政策/契约/枚举版本；其外置签名生成后再创建以 manifest digest 为 subject 的 EvidenceIndex。
  - [x] 为 baseline approval、runtime evidence pending/passed/not-applicable 分列状态；App N/A 与未来 Story pending 必须保持诚实。
  - [x] ReleaseManifest 只保存已存在的 artifact/report/SBOM/provenance/attestation/artifact-signature URI、SHA-256、mediaType、size 与 version；不得保存 manifest 自身签名、EvidenceIndex 或 promotion URI。manifest 外置签名由 EvidenceIndex 关联；promotion URI 只进入 PromotionRecord/Ledger。
  - [x] 扩展 `normalized_manifest.py`/release-source inventory，纳入 `.github`、release contracts/policies/ADR、`backend/mvnw`、`backend/.mvn/wrapper/**` 与所有实际构建配置；为漏项/篡改写负例，并扩展污染扫描覆盖新生产面。
  - [x] 避免 inventory 自引用：源码树摘要明确排除 inventory 实例本身；不可变 ReleaseManifest 再引用 inventory digest。动态 release output/evidence 同样不得进入被测源码集合。

- [ ] Task 5：建立安全 CI、attestation、签名与证据封存工作流（AC: SOURCE-CI-TRUST, ATTEST-SIGN, EVIDENCE-LIFECYCLE）
  - [ ] 新建只读 PR CI：bootstrap、完整 verify、release contract/schema、依赖/漏洞/许可证和篡改负例；fork PR 不获 secret/OIDC/write token。
  - [ ] 新建受保护 release CI，唯一顺序为 `clean build/双摘要 → CAS 上传回读 → SBOM/scan → artifact provenance/SBOM attestation/artifact signature → 正式 Web 报告 → ReleaseManifest → manifest 外置签名 → EvidenceIndex → 独立 verifier`；promotion 是后续受保护 job。各 job 最小权限并只传 digest/不可变 URI。采用 GitHub attestation 时，仅 attestation job 显式取得 `id-token: write`、`attestations: write`、`artifact-metadata: write`。
  - [ ] `actions/attest`、checkout/upload/download、Cosign installer 等全部完整 SHA 固定；Trivy/Cosign 下载先按上游 Sigstore/checksum 验证，版本/digest 收录进 CISB/toolchain lock。
  - [ ] 在独立 verifier job 重新下载并验证全部 subject digest、Sigstore bundle certificate identity/OIDC issuer、SLSA predicate、SBOM attestation 和 manifest 引用。

- [ ] Task 6：实现 digest-only 提升、幂等、对账与回退（AC: PROMOTION-ROLLBACK, EVIDENCE-LIFECYCLE）
  - [ ] 第一阶段定义 provider-neutral promotion port，并用内存/fixture adapter 验证 digest-only、稳定错误、幂等、并发与 fail-closed 合同；该阶段不得声称真实提升已完成。
  - [ ] 平台确定后实现真实 digest-addressed store/promotion adapter：用条件写/CAS 证明同键同 digest 重放、异 digest 409/稳定错误、并发单胜者、失败原子回滚与不可变 ledger；build identity 无提升权限，protected environment 的 promotion identity 只接受已验 manifest + EvidenceIndex digest。
  - [ ] 从 store/attestation service 重新取证后提升；缺证据、证据过期、artifact 篡改、tag 指向变化、store/ledger 水位不一致全部阻断。
  - [ ] 回退引用已签名历史 digest 并再次执行当前强制门；不重建、不覆盖旧 manifest/ledger。

- [ ] Task 7：直接验证冻结前端制品，生成正式 Web 品牌/视觉/无障碍证据（AC: FORMAL-WEB-EVIDENCE）
  - [ ] 新建独立 `run-formal-web-evidence`，不得扩展当前会从源码重建的 `run-brand-preflight.mjs`。正式入口只从 store 下载 expected digest 的前端归档，验证 hash/signature/provenance，拒绝链接/路径逃逸/重复路径后安全解包到只读临时目录，直接服务冻结字节；禁止执行 npm/Vite build 或读取工作区 `dist`。
  - [ ] CISB 批准一次性/ephemeral self-hosted runner 或受控 VM 镜像，记录 TEST-ENV 精确 macOS build、arm64、runner image identity、隔离与清理证据；普通 hosted runner 或容器 digest 不得冒充精确 macOS 环境。
  - [ ] 在 TEST-ENV 精确 OS 和 Chrome/Edge 150/149 实际二进制上运行两桌面视口、zoom/reflow、键盘/焦点、axe、视觉/资源/console/network 矩阵；报告 subject 必须等于 selected frontend artifact digest，不能取得精确 runner 时 fail closed。
  - [ ] 新建并校验 `VGB-1.0.0` 只读 golden manifest，冻结每个 matrix cell 的 golden digest、浏览器/OS/字体/视口/DPR/locale/timezone/clock/data/network 与 Playwright 比较参数；release job 禁止更新 snapshot。同一 run 生成 expected、未经 UX/品牌 + Web QA 双审批更新、任何环境/golden digest 漂移均阻断。
  - [ ] 负例固定为“构建/扫描/签名 A，store 内容或 expected digest 换成 B 后运行 UI 矩阵”，必须在启动浏览器前或生成 PASS 前稳定失败；另测当前源码与 store artifact 不同仍只测试 store 字节。
  - [ ] 明确启动面与下游业务页验收边界；App 仅带 N/A 决策，不下载、模拟或伪造 WebView 证据。

- [ ] Task 8：统一回归、篡改演练、证据归档与交接（AC: 全部）
  - [ ] 更新 `bootstrap.sh`/`verify.sh`：抽取不调用 `build-release` 的非递归 `verify-core`；顶层本地入口依次运行 core 与可重放构建但不签名/提升，CI release 入口才生成受信 provenance/signature/promotion 证据。
  - [ ] 连续两次运行完整本地套件并在真实受信 CI 完成 release dry-run，记录命令、退出码、测试计数、source/run/runner/tool/db digest、artifact/SBOM/signature/provenance/report/manifest URI；local replay/release dry-run 只能完成对应单元，整体 Story 保持 `in-progress`，不得作为进入 `review/done` 的证据。
  - [ ] `done` 前至少完成一次真实受保护、digest-only 提升及从目标 store 回读验证，保存不可变 promotion URI/record；缺少真实平台时保持 `in-progress`。
  - [ ] 演练篡改 artifact/SBOM/manifest/signature、删除证据、过期例外、重放旧 run、并发提升、store/ledger 漂移与 rollback，证明全部 fail closed 且无部分副作用。
  - [ ] 更新 README、后端/前端交接与 verification 记录；删除临时生成物。只有真实 CI、store 和 promotion 证据可验证时才把 Story 转为 review/done。

## Dev Notes

### 当前代码与必须保留的行为

- `scripts/verify.sh`：当前单一回归入口，依次执行 bootstrap、Maven `clean verify`、1.1a 审计工具 fixture 回归、Python 守卫、规范化清单和前端双清洁离线验证。它目前没有校验已保存的 1.1a inventory，因此总体验证可绿而历史资产快照已漂移；应扩展为校验新的 release-source inventory，不得覆盖旧清单、复制或绕开现有回归。
- `scripts/bootstrap.sh`：当前校验精确 PAB 工具链并预热前端依赖/浏览器缓存，含网络准备阶段。CI 必须把“准备/下载”和“离线验证/发布”分离，下载的工具/DB/浏览器均固定并校验；bootstrap 本身不能获得签名或提升权限。
- `scripts/verify_frontend.sh`：已在两个临时 clean root 中执行 `npm ci --offline --ignore-scripts → contract/type/unit/build/Playwright` 并硬比较 source/lock/tree/build digest。复用其结果，但最终制品可复现性还需比较规范化前端发布归档，而不只是 `dist` tree digest。
- `scripts/check_frontend_baseline.py`：锁定 FPB/PP/AP/TEST-ENV schema、内容摘要、精确依赖与四个 Chrome/Edge 制品。其当前摘要实现是 `ensure_ascii=False + sort_keys=True + separators=(",", ":") + UTF-8 + SHA-256`，schema checker 只实现 Draft 2020-12 子集。Story 1.1d 必须把这两项分别冻结为 `SCHOLARSENSE-CANONICAL-JSON-1.0.0` 与 `SCHOLARSENSE-JSON-SCHEMA-SUBSET-1.0.0`，复用唯一 helper/semantic checker；不得复制成第三套排序器，也不得把 subset 宣称为完整 Draft 实现。既有 FPB/PP/AP/TEST-ENV 摘要保持历史值，不静默重算。
- `frontend/scripts/run-brand-preflight.mjs` 与 `frontend/tests/baseline/baseline.spec.ts`：当前删除 `dist` 后执行 `npm run build`，再用 Vite preview 测当前源码，只产生 `optional-brand-preflight-not-formal-report`；它没有验证 store 中待提升前端 artifact。保持该入口和分类不变，正式入口必须另建且直接下载/服务冻结 artifact 字节。
- `scripts/normalized_manifest.py`：当前纳入 backend/frontend/contracts/deploy/scripts 与少量根文件，排除 `.git` 和生成物。需纳入 workflow/release policy/ADR，但绝不能把 CI run 的动态证据纳入源摘要形成自引用。
- `scripts/check_production_pollution.py`：当前扫描 `backend/src/main`、`frontend`、`contracts`、`deploy` 的本机路径、凭据、私钥、持久化缓存与原型污染。需覆盖 `.github`、release 目录和新脚本，并增加 workflow permission/action pin/secret sink 检查。
- `backend/pom.xml`：当前固定 Spring Boot 4.1.0/JDK 25，但没有 `project.build.outputTimestamp`、`finalName`、SBOM 插件或机器可读依赖/plugin lock。Wrapper 只在 `backend/`。本 Story 保留项目坐标 `0.1.0-SNAPSHOT`，把最终中性运行文件固定为 `backend/target/scholarsense-backend.jar`，并原子更新 pom、两个 role、checker/tests、BuildRootContractTest、README 和 release fixtures；不得同时保留旧 SNAPSHOT 运行路径。
- `frontend/package.json` / `package-lock.json`：PAB 直接项与两个 companion 已精确锁定，`npm ci --ignore-scripts` 是安全边界。供应链工具不要随意加入生产 npm tree；若 lock 改变，必须发布新 FPB/manifest 版本而非原位漂移。
- `deploy/base/roles.json`：当前只描述同 JAR 的 `web-api`/`worker` 角色，不是生产编排或制品提升记录；release manifest 应引用实际 JAR digest，但不要把该 seed 冒充部署通过。
- `docs/input/原型/frontend`：永久只读迁移输入，任何 `dist/node_modules/lock` 均不得成为生产 SBOM 或待提升制品。

### 文件结构要求

以下是受控落点；schema/profile 文件名不得由实现者另行改名。生产逻辑不得放入 `_bmad-output` 或原型目录。

```text
.github/workflows/
  ci.yml                              # 只读 PR/branch 质量门
  release.yml                         # 受保护 release/attest/sign/promote
contracts/release/
  canonical-json-profile-1.0.0.json
  schema-subset-profile-1.0.0.json
  toolchain-lock.schema.json
  toolchain-lock-1.0.0.json
  backend-dependency-lock.schema.json
  backend-dependency-lock-1.0.0.json
  build-manifest.schema.json
  release-manifest.schema.json
  evidence-index.schema.json
  promotion-record.schema.json
  release-source-inventory.schema.json
  license-exception.schema.json
  vulnerability-exception.schema.json
  visual-baseline.schema.json
  visual-baseline-vgb-1.0.0.json
  formal-web-evidence-report.schema.json
  ui-token-manifest.schema.json
  brand-asset-manifest.schema.json
  supply-chain-policy.schema.json
  supply-chain-policy-cisb-1.0.0.json
  license-policy-1.0.0.json
  fixtures/canonical/
  fixtures/schema/
  fixtures/evidence-graph/
docs/architecture/adr/
  ci-supply-chain-baseline-cisb-1.0.0.md
release/
  README.md                            # 格式、状态、证据边界；不提交动态 out
scripts/
  build_release.py                     # 规范化打包/双构建摘要
  release_json.py                      # 唯一 canonical/subset/semantic helper
  check_release_supply_chain.py        # schema、lock、SBOM、manifest、policy 门
  verify_release.py                    # 下载后 digest/attestation/signature 验证
  promote_release.py                   # digest-only adapter/幂等/对账
frontend/scripts/
  run-formal-web-evidence.mjs          # 只测试 store 中 expected digest 的冻结字节
scripts/tests/
  test_release_supply_chain.py
.gitignore                            # 只排除固定 release 临时输出目录
```

动态 `release-out/`、SBOM、scan report、Sigstore bundle、provenance、正式浏览器报告和 promotion record 是 CI artifacts/store objects；源码仓库只保存 schema、policy、fixture、工具 lock 与 ADR，不保存伪造运行结果或私钥。

证据图固定为：artifact evidence/signature → ReleaseManifest → manifest 外置签名 → EvidenceIndex → verifier → PromotionRecord/Ledger。ReleaseManifest 不得引用自身签名、EvidenceIndex 或 promotion；EvidenceIndex 以 manifest digest 为 subject 并关联外置签名；promotion 只引用 manifest/EvidenceIndex digest，绝不回写前述对象。

### 架构与安全护栏

- 遵循 AD-15：dev/test/stage/prod 账户、数据库、密钥、对象命名空间与端点隔离；应用、规则、策略独立灰度/回退。Story 1.1d 只建立制品提升门，不把所有业务配置绑成一次应用部署。
- Release manifest 中未来 policy 的 `approved baseline` 与 `runtime evidence` 分开；不允许因其静态版本已列入清单就宣称下游 Story 完成。
- 构建、签名、提升三种身份分离；PR 内容不能在拥有 OIDC/secret/write 权限的上下文执行。环境审批、分支保护和组织 Action policy 属于完成证据，不只是 README 建议。
- 所有外部下载按 HTTPS + immutable URL/version + checksum/Sigstore 验证；工具自身进入 SBOM。缓存只能加速，不能成为唯一或未验证来源。
- 漏洞 DB 必须记录实际 digest/更新时间；离线扫描使用已归档、已签名 DB snapshot。仅记录“扫描工具退出 0”而没有 DB/tool/policy digest 不构成证据。
- 漏洞/许可证例外是版本化、具 owner、到期且绑定 artifact/CVE/license 的治理事实；不得用全局 ignore、空 allowlist 或永不过期 waiver 使门失效。
- Promotion 不改变 artifact bytes。环境差异通过外部非密配置/keyRef 注入，禁止重打包同版本以植入环境值。

### 测试要求

- 保留现有后端、1.1a 审计、Python、前端 unit/type/build、Playwright/axe 和两次 clean replay 全量回归；release 测试是增量，不能替代既有套件。
- 每个 fail-closed 分支至少有一个可重放负例：来源、权限、Action pin、镜像/tool checksum、dependency lock、重复 key、artifact digest、SBOM 对账、漏洞/许可证例外、签名 identity/issuer、SLSA subject、报告摘要、promotion 幂等/并发/回退。
- canonical/profile 必须有 Python/Node/verifier 跨语言字节级 fixture；schema subset 必须拒绝未实现 keyword/format 与远程 `$ref`；证据图必须拒绝自引用、反向引用、越序和同版本异 digest。
- 正式 Web 必须证明入口未执行 npm/Vite build、未读取工作区 `dist`，且“scan/sign A 后测试 B”稳定失败；视觉回归必须证明 release job 不能生成/更新 expected，golden/字体/browser/runner 任一摘要漂移即失败。
- 真实 CI 证据必须包含失败 run 示例，证明关键门不是 `continue-on-error`、软警告或仅上传报告；任何必需门失败时 promotion job 不启动。
- 时间相关测试使用固定 UTC/`Asia/Shanghai` fixture；例外到期边界采用 `[startAt,endAt)`，恰在 end 失效。
- 网络/外部服务失败必须区分可重试与永久失败，重试不重复签名/提升记录；最终结果按 runAttempt 与幂等键对账。

### 前序 Story Intelligence

- 1.1a 证明当前初始工作区没有 Git、CI、deployment engineering，原型 `dist/node_modules` 均为 `unknown-blocked/replace`；不得从历史清单反推现已存在生产平台。
- 1.1b 建立模块化骨架、固定 Maven Wrapper、生产污染扫描、规范化清单和单一 `verify.sh`；多轮审查表明仅靠正则/静态 happy path 容易被注释、引号、动态 import、符号链接、配置优先级和替代命名绕过。新 release checker 必须先写绕过负例并拒绝符号链接、路径逃逸、解析歧义与 TOCTOU。
- 1.1c 已交付精确 npm lock、FPB/PP/AP/TEST-ENV、双清洁离线重放、构建预算、性能事件契约和四个精确浏览器制品 preflight。其当前验证为后端 36/36、审计 145/145、Python 72/72、前端 unit 27/27、Playwright/axe 20 pass/4 skip；这些计数是历史交接，不得硬编码为未来最低值。
- 1.1c 的前端 lock/依赖树/build digest 分别已有历史摘要，但 Story 1.1d 必须在真实 source commit/CI 上重算并绑定最终待提升 artifact；不得复制旧摘要冒充新 release。
- 1.1c 明确把 companion 安全发布批准、安装脚本、SBOM、漏洞/许可证、provenance、签名和制品提升交给本 Story；App owner 基线已由用户批准 N/A。

### Git Intelligence

- 当前项目根没有 `.git`，无法读取最近 5 个 commit、remote、branch、tag 或 diff；不存在可用 Git 历史分析。
- `baseline_commit: NO_VCS` 是事实记录，不是可接受的发布 identity。本 Story 的 release job 必须显式拒绝它；在真实仓库可用后记录实际 commit 与 workflow run，不得回填虚构历史。

### Latest Technical Information（核验于 2026-07-19）

- SLSA Build Provenance 当前规范为 v1.2，predicate type 仍固定为 `https://slsa.dev/provenance/v1`；subject digest、buildDefinition、builder 与 runDetails 是消费者重建/验证链的核心。
- GitHub 新实现应使用 `actions/attest`（v4 线）而非新建对旧 `attest-build-provenance` wrapper 的依赖；它可生成 provenance、CycloneDX/SPDX SBOM attestation，要求 job 级 `id-token: write`、`attestations: write`、`artifact-metadata: write`。Artifact Attestations 对公有仓库适用于当前 GitHub plans，私有/内部仓库要求 GitHub Enterprise Cloud，GitHub Enterprise Server 不支持；不满足时必须选等价适配器。workflow 中仍必须将 Action 固定为经审阅的完整 commit SHA，不能直接写 `@v4`。
- Cosign v3 默认使用 bundle；截至 2026-07-19 官方标记的最新稳定候选为 v3.1.2（2026-07-17 发布，含 bundle/verify 修复）。CISB 创建时必须重新核验当时最新安全稳定版本、发布 bundle、厂商身份与 checksum，并用完整版本及二进制/镜像 digest 固定；不得把本文候选版本视为永久批准。下载的 Cosign 本身先验真，再用于 `sign-blob/verify-blob`；验证必须固定 certificate identity 与 OIDC issuer，不能只验证“某个有效签名”。
- Trivy 官方最新稳定为 v0.72.0（2026-06-30），发布提供 checksums 与 Sigstore bundle。2026-03 供应链事件明确影响 v0.69.4、Docker v0.69.5/v0.69.6 及一批可移动 Action tags；本项目必须使用 v0.72.0 的已验证 binary/image digest 或后续经 CISB 新版本批准的安全版本，并继续完整 SHA/digest 固定。
- CycloneDX 当前规范为 1.7；官方识别 `*.cdx.json`，并以 `https://cyclonedx.org/bom` 作为 attestation predicate type。若工具只能输出较旧但受支持 schema，必须在 CISB 记录精确版本、验证器和兼容理由，不得静默标成 1.7。

### Project Context Reference

- `_bmad-output/project-context.md` 当前只声明项目名、日期和空的技术栈/关键规则占位；本 Story 以受控 PRD/Architecture/Epics、委托基线和前序实际代码为准，不从该空占位推导额外约束。

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#实施与验收规则`]
- [Source: `_bmad-output/planning-artifacts/epics.md#Story-11d固化-CI供应链与质量门`]
- [Source: `_bmad-output/planning-artifacts/epics.md#Epic-1可信门户接入与可问责授权`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#92-可用性与韧性`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#94-安全与审计`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#97-首期验收基线表`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#99-NFR-证据责任与旧引用迁移`]
- [Source: `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md#M0-工程底座`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-15--环境隔离与向前兼容发布`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#AD-28--生产制品与供应链基线`]
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md#生产技术基线-PAB-100`]
- [Source: `_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md#9-PABPPAP-100`]
- [Source: `_bmad-output/planning-artifacts/app-applicability-baseline-2026-07-19.md`]
- [Source: `_bmad-output/planning-artifacts/requirements-traceability.md#NFR-证据追踪`]
- [Source: `_bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md#当前可见事实与边界`]
- [Source: `_bmad-output/implementation-artifacts/1-1b-verification.md#已知未决项与唯一-owner`]
- [Source: `_bmad-output/implementation-artifacts/1-1c-批准生产前端与性能-profile-adr.md#AC-11c-EVIDENCE-BOUNDARY真实证据漂移门与下游交接`]
- [Source: `_bmad-output/implementation-artifacts/1-1c-verification.md#下游交接`]
- [SLSA Build Provenance v1.2](https://slsa.dev/spec/v1.2/build-provenance)
- [GitHub `actions/attest`](https://github.com/actions/attest)
- [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)
- [Sigstore blob signing](https://docs.sigstore.dev/cosign/signing/signing_with_blobs/)
- [Sigstore verification](https://docs.sigstore.dev/cosign/verifying/verify/)
- [Cosign v3.1.2 release](https://github.com/sigstore/cosign/releases/tag/v3.1.2)
- [Trivy v0.72.0 release](https://github.com/aquasecurity/trivy/releases/tag/v0.72.0)
- [Trivy 2026 supply-chain advisory GHSA-69fq-xp46-6x23](https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23)
- [Trivy SBOM documentation](https://trivy.dev/docs/latest/supply-chain/sbom/)
- [Trivy license scanning](https://www.trivy.dev/docs/latest/scanner/license/)
- [CycloneDX specification overview](https://cyclonedx.org/specification/overview/)
- [Maven reproducible builds guide](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
- [SLSA artifact verification](https://slsa.dev/spec/v1.2/verifying-artifacts)
- [Playwright visual comparisons](https://playwright.dev/docs/test-snapshots)
- [Playwright page screenshot assertions](https://playwright.dev/docs/api/class-pageassertions)
- [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)

## Dev Agent Record

### Agent Model Used

GPT-5 Codex（create-story context）

### Debug Log References

- 2026-07-19T04:56:13+08:00—04:57:38+08:00：重放 `./scripts/verify.sh`，退出码 0。后端 36/36、1.1a 审计 145/145、Python 72/72；两次前端重放各为 unit 27/27、Playwright 20 pass/4 skip，source/lock/tree/build 摘要逐项一致。
- 2026-07-19T04:58:00+08:00：`git rev-parse --show-toplevel` 退出 128，根级 `.git` 与 `.github` 均不存在；未发现真实 repository/commit/remote、CI workflow、受控制品库、attestation store 或 promotion adapter。保留 `baseline_commit: NO_VCS`，未生成 provenance、签名、正式报告或提升记录。
- 2026-07-19T04:58:39+08:00：按 1.1a 清单保存的 replay 参数执行 `audit_production_assets.py check` 与 `validate`，二者均按预期退出 2，稳定诊断为“检测到受控输入漂移：机器清单资产或摘要与当前快照不一致”；历史 1.1a 产物未被覆盖。
- 2026-07-19T04:59:23+08:00：Task 0 在 CISB 平台冻结门 HALT。当前没有可填写的 immutable repository/workflow/runner/store/attestation/signing/promotion/verifier 值、能力证据 URI及具名平台/安全/发布 Responsible 批准，不能创建已批准 `CISB-1.0.0` 或继续勾选 Task 0。
- 2026-07-19T05:04:53+08:00：用户提供 GitHub 地址 `https://github.com/keliihall/ScholarSense-bmad-method`。只读核验确认 GitHub.com、public、repository ID `1305224312`、默认分支配置 `main`；仓库为空且 `git ls-remote` 无 ref，尚无 source commit。当前 Actions 允许所有 Action 且未要求 SHA pin，仓库无 environment、ruleset 或 `main` branch protection，因此 SOURCE-CI-TRUST/CISB 仍未满足。当前 `gh` 身份为 `keliihall` 且具有仓库访问能力；未执行 init、commit、push 或远端设置变更。
- 2026-07-19T05:17:07+08:00：Hei 明确授权初始化并公开推送，并委托执行者在 Story 不变量内确定其余平台选择、非必要不 HALT。已审查公开提交范围，初始化 `main`，以 GitHub 账号 `keliihall`/noreply identity 创建并通过 HTTPS 推送 root commit `f9d478a33c022bbb1b735704afc18e0b608b894f`；远端 `refs/heads/main` 回读一致。GitHub Actions 已启用强制 SHA pin；已创建需 `keliihall` 审批的 `stage`（environment ID `18374865168`）与 `production`（environment ID `18374865771`）。保留 `baseline_commit: NO_VCS` 作为开工事实，不回填历史。
- 2026-07-19T05:28:19+08:00：首次受信 platform probe run `29661653754` fail closed；工具摘要校验、GHCR 登录/上传成功，但 ORAS v1.3.3 JSON 输出字段假设错误，未产生 attestation/signature/promotion 通过声明。改用官方 Go-template digest 输出后重跑。
- 2026-07-19T05:30:30+08:00—05:32:52+08:00：受信 run `29661725147` 在 source `c19e4e31fa07fd1242c9120cc2a6ef77112938e7` 全绿。GHCR artifact digest `sha256:864b4ca2860632cdc30e672e40734ec9dd3926fa826d5337a28d4f53d51c1987` 回读一致；GitHub attestation subject `sha256:c221277ad1e6632e9fa15cb55c827ef60c79b9d5ed2c2952ad95f4f346d72d42` 可查询；Cosign bundle/evidence digest `sha256:424382f12b571419c7ae91df30f8209f6b3945dfce579272430dac27f39534f6` 由固定 workflow identity/OIDC issuer 复验；stage digest-only copy 保持 artifact digest，ledger ref `refs/tags/promotion-ledger/probe-29661725147-stage` 对异值二次 create 返回冲突，只读 verifier/reconciler 全绿。
- 2026-07-19T05:34:19+08:00—05:34:42+08:00：启用 `promotion-ledger-immutable` ruleset `19154231` 与 `main-source-integrity` ruleset `19154234`；前者禁止 ledger tag 删除/非快进更新，后者要求 main 经 PR 且禁止删除/强推。后续实现切换到 `story/1-1d-supply-chain` 分支。
- 2026-07-19T05:39:31+08:00—05:40:37+08:00：更新后的 `./scripts/verify.sh` 从头重放退出 0：后端 36/36、审计 145/145、Python 81/81；CISB、workflow security、新 release-source inventory（222 files）均 PASS；两次前端重放各 unit 27/27、Playwright 20 pass/4 skip，source/lock/tree/build digest 一致。首次重放发现隔离目录未复制新增 `.github`/`release` production roots 并正确失败，扩展复制范围后全绿。
- 2026-07-19T05:42:00+08:00—05:58:00+08:00：Task 1 按 RED→GREEN 建立 `SCHOLARSENSE-CANONICAL-JSON-1.0.0` 与受控 schema subset。Python/Node 对 ASCII、中文、non-BMP 与嵌套 vectors 的 canonical UTF-8 bytes/SHA-256 逐字节一致；99 项 Python 测试全绿，release-contracts、production-pollution、历史 frontend-baseline 均 PASS。负例稳定覆盖 duplicate/BOM/float/-0/unsafe integer/lone surrogate、未知 keyword/format/远程 ref/generic sha、NO_VCS/dirty/ref、workflow pin/权限/镜像、SBOM 对账、漏洞/许可证/例外/install script、签名 identity/issuer/old replay、证据环/顺序/版本重绑及部分/并发提升。
- 2026-07-19T06:00:00+08:00—06:27:52+08:00：Task 2 按 RED→GREEN 固定中性后端 JAR、41 项 runtime + 6 项 plugin + Maven Wrapper backend lock、规范化前端归档与非递归双 clean-root 构建。首次真实重放正确暴露 `.vite/manifest.json` 误判，收窄为仅允许该生产映射清单；PR #1 合并后重放又暴露 shared clone 未继承真实 `origin/main`，改为复制父工作区已回读 OID，未放宽 source 门，两次失败均无半成品。
- 2026-07-19T06:31:00+08:00—06:34:14+08:00：受保护主线 merge commit `36266b8d2929c442ffa15e8108e2ff0923575ae8` 上 `scripts/build-release.sh release-out/task2-proof` 退出 0。每个 clean root 均通过后端 36/36、审计 145/145、Python 109/109；每个根内两次前端重放各 unit 27/27、Playwright 20 pass/4 skip，最终 JAR `27f059…1303`、前端归档 `811cf7…a5a2` 与 artifact set `9f8488…d8ea` 逐项一致，BuildManifest canonical SHA-256 为 `e961b7…d07a`。
- 2026-07-19T06:36:00+08:00—07:10:10+08:00：Task 3 按 RED→GREEN 生成后端（42）、前端（156）与聚合（207）组件的 CycloneDX 1.7/SPDX 2.3。Trivy 0.72.0 macOS ARM64 archive `88f208…0016` 与官方 checksums 一致，Cosign 3.1.2 `dec1c3…f10a` 按精确 `reusable-release.yaml@refs/tags/v0.72.0` SAN/GitHub OIDC issuer 验证 bundle `Verified OK`；DB `1b9e58…34c1`。首次 DB 拉取因本机 Docker credential helper 停滞而中止，空临时 Docker config + 官方 GHCR DB 成功；首次 bundle 身份猜为 `.yml` 按预期失败后改用证书精确 `.yaml` 值。
- 2026-07-19T07:10:10+08:00—07:14:00+08:00：实际 SBOM checker 全绿，三类 subject 的 Critical/High/UNKNOWN finding 均为 0；隔离 `npm ci --offline --ignore-scripts` 后 `npm ls --all` 对账为 installed 126 + platform optional 30 = lock unique 156，无额外组件。`@vitejs/plugin-vue`、`@types/node`、6 个 Maven plugin、Wrapper 与 3 个未执行 lifecycle script 均有来源/checksum/license/vulnerability 决策；205 项第三方 NOTICE/源码义务清单摘要 `474720…3ab5`。完整 `verify-core` 后端 36/36、审计 145/145、Python 117/117、前端两次各 unit 27/27、Playwright 20 pass/4 skip。
- 2026-07-19T07:45:00+08:00—07:58:00+08:00：Task 4 按 RED→GREEN 固定 ReleaseManifest 一次冻结与外置签名后 EvidenceIndex 时序。23 项针对性测试通过：阻塞门 pending、缺 artifact signature、未知 subject、可变 URI、manifest 后代引用、反向依赖、签名 subject 错误和同版本 digest 重绑均 fail closed；App/WebView 保持 `not-applicable + runtimeEvidenceClaim=none`，未来 7.1/7.x 保持 `pending-story-execution`。PR #3 合并为受保护主线 `8ebc18d711d70d2e3fb9a34b92c67c4baa6a722d`，release-source inventory 回读 298 files、tree `6032c2…2f4`、normalized digest `d99427…5ec4`，inventory 实例与动态 evidence 均不参与源摘要。

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- 2026-07-19：已合并 Epic 1/Story 1.1d、PRD/Architecture/UX、委托决策/追踪基线、1.1a—1.1c 实施交接、当前代码现实与最新官方供应链资料。
- 当前 `NO_VCS`、无 CI/制品库/提升平台是实施完成门，不是可伪造或静默忽略的证据；Story 已具备开工上下文并保持 `ready-for-dev`。
- 2026-07-19：已固定 AAB 规划 companion、CISB 平台完成门、U1—U4 独立验收、无环证据生命周期、冻结制品正式 Web 入口、版本化视觉 golden oracle、唯一 canonical/schema profile、backend Wrapper/中性 JAR 原子更新范围及 schema 落点。
- 2026-07-19：开发开工复核完成且现有回归全绿；因 Story 明示的 CISB 平台冻结门缺少真实配置、能力证据和具名 Responsible 批准而 fail closed。未修改生产代码、未勾选 Task、未把本地重放冒充受信供应链证据，Sprint 状态保持 `in-progress`。
- 2026-07-19：已补充真实 GitHub repository 元数据，但空仓库尚未形成 immutable source identity，且保护规则、受保护环境、store/attestation/signing/promotion 与职责批准仍缺失；Story 继续 fail closed。
- 2026-07-19：用户授权后已建立并回读真实远端 source commit，启用 Action SHA pin 并创建受审批的 stage/production environments；下一步以该不可变身份完成 CISB、工作流和 store/promotion 能力门。
- 2026-07-19：Task 0 完成。`CISB-1.0.0` 已由用户授权的具名职责激活，GitHub.com/GitHub Actions/GHCR/GitHub Artifact Attestations/Cosign/ORAS/Trivy 与 protected environments/ref/ledger 的真实能力均有不可变 run、OCI digest、attestation subject、job 和 ruleset URI；历史 1.1a 漂移证据保留，新 release-source inventory 由统一验证入口自动检查。
- 2026-07-19：Task 1 完成。14 份 release schema、对应正负 fixture、canonical/schema profile、toolchain/policy 实例与统一 Python/Node helper 已落地；原 FPB/PP/AP/TEST-ENV checker 改为复用同一 helper 的历史兼容模式，既有 content digest 未被重算或改写。
- 2026-07-19：Task 2 完成。后端运行路径已原子切换为中性 JAR，Maven 外部运行依赖/plugin/Wrapper 受 checksum 锁约束；单一 `build-release` 在两个 clean root 复用既有前端双离线重放，固定环境与归档元数据，比较最终制品及候选 BuildManifest canonical digest，并在源码/lock 漂移、秘密/报告/缓存污染或摘要不一致时无半成品失败。
- 2026-07-19：Task 3 完成。固定并验证 Trivy/Cosign release bundle，按实际归档、frontend lock、`npm ls`、backend runtime/plugin/Wrapper lock 生成三组 subject-bound CycloneDX/SPDX；统一 checker 对 component hash/purl/license、工具/DB/subject/policy/report digest fail closed，并生成机器化敏感依赖裁决与第三方 NOTICE/源码义务证据。
- 2026-07-19：Task 4 完成。ReleaseManifest 仅在两次 build attempt、全部 AD-28 基线/受控输入、锁、选定制品及其 SBOM/scan/provenance/attestation/artifact-signature/UI/品牌/Web 证据齐备且阻塞门通过后冻结；manifest 外置签名随后生成，EvidenceIndex 以 manifest canonical digest 为 subject。生成器当前会诚实拒绝尚未由 Task 5/7 产生的真实签名与正式 Web 证据，不提交伪造运行清单。

### File List

- `_bmad-output/implementation-artifacts/1-1d-固化-ci-供应链与质量门.md`（开工验证、HALT 与文件记录）
- `_bmad-output/implementation-artifacts/sprint-status.yaml`（Story 状态改为 `in-progress`）
- `.gitignore`（排除 release 临时输出、审计锁和编辑过程文件）
- `.github/CODEOWNERS`（发布供应链关键路径责任人）
- `.github/workflows/platform-probe.yml`（真实 store/attestation/signing/promotion/verifier 能力探测）
- `contracts/release/ci-supply-chain-baseline-1.0.0.json`（CISB 机器合同与真实证据）
- `contracts/release/release-source-inventory-1.0.0.json`（绑定 immutable Git source 的新发布清单）
- `docs/architecture/adr/ci-supply-chain-baseline-cisb-1.0.0.md`（CISB ADR）
- `release/README.md`（发布代码与生成证据边界）
- `scripts/check_cisb.py`（CISB fail-closed 门）
- `scripts/check_release_source.py`（Git object release-source inventory 门）
- `scripts/check_workflow_security.py`（Action/权限/secret sink/镜像门）
- `scripts/check_production_pollution.py`（扩展 `.github` 与 release production roots）
- `scripts/normalized_manifest.py`（扩展供应链输入覆盖）
- `scripts/verify.sh`（统一接入 CISB/source/workflow 门）
- `scripts/verify_frontend.sh`（隔离重放复制新增 production roots）
- `scripts/tests/test_delivery_quality.py`（新增供应链生产面与 manifest 覆盖测试）
- `scripts/tests/test_release_platform.py`（CISB/workflow RED/负例）
- `scripts/tests/test_release_source_inventory.py`（release-source inventory RED/漂移负例）
- `contracts/release/canonical-json-profile-1.0.0.json`（canonical profile）
- `contracts/release/schema-subset-profile-1.0.0.json`（受控 JSON Schema subset）
- `contracts/release/canonical-json-test-vectors-1.0.0.json`（Python/Node/verifier vectors）
- `contracts/release/toolchain-lock-1.0.0.json`（固定 release 工具与 Action identity）
- `contracts/release/vulnerability-policy-1.0.0.json`（漏洞阻断策略）
- `contracts/release/license-policy-1.0.0.json`（许可证阻断/义务策略）
- `contracts/release/*.schema.json`（14 份 toolchain/backend/policy/manifest/evidence/promotion/source/Web/visual/UI/brand schema）
- `contracts/release/fixtures/index.json`（schema fixture 索引）
- `contracts/release/fixtures/valid/*.json`（11 份独立正例；policy/source 正例复用受控实例）
- `contracts/release/fixtures/invalid/*.json`（14 份 fail-closed 负例）
- `release/canonical-json.mjs`（Node canonical 实现）
- `scripts/release_json.py`（唯一 Python canonical/schema/证据语义 helper）
- `scripts/release_policy.py`（漏洞/许可证/install script/签名/SBOM/source/promotion 语义）
- `scripts/check_release_contracts.py`（schema/profile/policy/fixture 统一门）
- `scripts/check_frontend_baseline.py`（复用唯一 helper，保留历史数字模式）
- `scripts/check_cisb.py`（复用唯一严格 JSON parser）
- `scripts/check_release_source.py`（复用唯一严格 JSON parser）
- `scripts/tests/test_release_contracts.py`（canonical/schema/evidence/identity RED 与跨语言 vectors）
- `scripts/tests/test_release_security_policy.py`（SBOM/漏洞/许可证/install script/签名/source/promotion RED）
- `backend/pom.xml`（保留 SNAPSHOT 坐标并固定中性 JAR、输出时间与构建 plugin 版本）
- `backend/README.md`（中性运行制品路径与发布身份说明）
- `backend/src/test/java/cn/edu/suda/scholarsense/architecture/BuildRootContractTest.java`（JAR/timestamp/Wrapper 构建根合同）
- `deploy/base/roles.json`（web-api/worker 原子切换到同一中性 JAR）
- `contracts/release/backend-lock-1.0.0.json`（41 项 runtime、6 项 plugin 与 Wrapper 来源/checksum 锁）
- `contracts/release/build-manifest.schema.json`（显式绑定 backend/frontend/toolchain locks）
- `contracts/release/fixtures/valid/build-manifest.json`（中性制品名与 lock digest 正例）
- `release/archive.py`（固定 mtime/mode/order/gzip 参数的原子归档器）
- `release/backend_lock.py`（实际 JAR/runtime/plugin/Wrapper lock 生成与漂移门）
- `release/build_release.py`（双 clean-root 非递归构建、制品/Manifest 对比与内容污染门）
- `scripts/build-release.sh`（唯一确定性发布构建入口）
- `scripts/check_backend_lock.py`（backend lock 统一门）
- `scripts/verify_core.sh`（不回调 build-release 的核心验证入口）
- `scripts/tests/test_backend_lock.py`（checksum/source/dynamic/SNAPSHOT 负例）
- `scripts/tests/test_check_contract_seeds.py`（旧路径、单 role、错误 finalName 负例）
- `scripts/tests/test_release_build.py`（归档、双 attempt、秘密/报告/缓存、无半成品与非递归负例）
- `contracts/release/sbom-evidence.schema.json`（subject/tool/DB/policy/SBOM 文档绑定 schema）
- `contracts/release/fixtures/valid/sbom-evidence.json`（SBOM evidence 正例）
- `contracts/release/fixtures/invalid/sbom-evidence.json`（SBOM evidence fail-closed 负例）
- `contracts/release/license-policy-1.0.0.json`（当前 SPDX expression 与 NOTICE/源码义务裁决）
- `contracts/release/license-policy.schema.json`（license expression/obligation schema）
- `contracts/release/toolchain-lock-1.0.0.json`（增加 macOS arm64 Trivy/Cosign 本地 U1 校验入口）
- `release/sbom.py`（实际 npm/Maven/归档组件对账与确定性 CycloneDX/SPDX 渲染）
- `release/generate_sbom.py`（Trivy/Cosign/DB 验真、扫描、策略阻断与原子证据生成）
- `scripts/generate-sbom.sh`（隔离 npm 实装树对账与单一 SBOM 生成入口）
- `scripts/check_release_tool.py`（release tool/archive checksum lock 门）
- `scripts/check_sbom.py`（SBOM/NOTICE/evidence 独立 fail-closed checker）
- `scripts/release_policy.py`（支持显式允许的复合 SPDX expression）
- `scripts/tests/test_sbom.py`（purl/hash/subject/tool/DB/npm tree/version/license/adjudication RED）
- `contracts/release/release-manifest.schema.json`（分列基线批准、运行证据、AD-28 输入、锁、制品及已存在证据）
- `contracts/release/evidence-index.schema.json`（manifest 外置签名、证据阶段与版本绑定）
- `contracts/release/fixtures/valid/release-manifest.json`（完整分层生命周期正例）
- `contracts/release/fixtures/valid/evidence-index.json`（无反向依赖的 manifest-subject 正例）
- `release/manifests.py`（ReleaseManifest/EvidenceIndex 语义门、一次冻结与依赖图）
- `release/generate_manifests.py`（canonical manifest/index 生成入口）
- `scripts/check_release_manifests.py`（冻结字节、schema、subject 与生命周期独立校验）
- `scripts/check_release_contracts.py`（接入 manifest 生命周期 fixture 语义检查）
- `scripts/check_release_source.py`（必需源范围、inventory 自排除与动态 evidence 排除）
- `scripts/normalized_manifest.py`（与 release-source 对齐自排除和动态输出边界）
- `scripts/check_production_pollution.py`（覆盖新增生产 release scripts）
- `scripts/tests/test_release_manifests.py`（冻结、证据齐备、N/A/pending、反向图、版本重绑及 tamper RED）
- `scripts/tests/test_release_source_inventory.py`（workflow/ADR/wrapper/build 配置漏项、自引用及动态输出负例）

## Change Log

- 2026-07-19：创建 Story 1.1d 完整开发上下文，状态设为 `ready-for-dev`。
- 2026-07-19：保持 Story 目标不变，补齐规划适用性、平台、生命周期、浏览器制品、视觉 oracle、canonical/schema 与 Maven/JAR 合同。
- 2026-07-19：启动开发并完成本地开工复核；因 `CISB_PLATFORM_BASELINE_INCOMPLETE` 在 Task 0 fail closed，未进入 U1 代码实现。
- 2026-07-19：登记用户提供的 GitHub.com public repository；只读能力探测显示空仓库和保护配置缺失，未擅自推送或修改远端设置。
- 2026-07-19：依据用户授权初始化并推送 `main`，建立真实 source commit、Action SHA pin 与受审批 stage/production environments。
- 2026-07-19：完成 Task 0 平台冻结：新增 CISB ADR/机器门、release-source inventory、workflow security/pollution 门与受信 platform probe；保存 GHCR/attestation/Cosign/promotion CAS/独立 verifier 真实证据并保护 main/ledger refs。
- 2026-07-19：完成 Task 1 release 合同内核：新增 14 份 schema/正负 fixture、canonical/schema profiles、工具/安全策略、统一 Python/Node canonical 与 fail-closed semantic checker，并把全部门接入 `verify.sh`。
- 2026-07-19：完成 Task 2 可复现制品硬门：固定中性 JAR 与 backend lock，新增规范化前端归档、双 clean-root `build-release`/非递归 `verify-core`、BuildManifest lock 绑定及污染/漂移/失败原子性负例；经 PR #1/#2 进入受保护 main 并在 merge commit 上重放通过。
- 2026-07-19：完成 Task 3 SBOM-SCAN：固定并以 Cosign 验证 Trivy 0.72.0，生成后端/前端/聚合 CycloneDX 1.7 与 SPDX 2.3，严格对账实际制品、npm 实装树、frontend/backend/plugin/Wrapper locks，绑定工具/DB/subject/policy digest 并输出敏感组件、安装脚本和许可证义务证据。
- 2026-07-19：完成 Task 4 分层 manifest 生命周期：新增 fail-closed ReleaseManifest/EvidenceIndex generator/checker、一次冻结、后置 manifest 签名、证据阶段/subject/版本绑定与诚实 applicability 状态；扩展源清单和污染边界，经 PR #3 进入受保护 main 后更新自排除 inventory。
