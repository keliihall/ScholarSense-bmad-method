# ADR：CI / Supply-chain Baseline CISB-1.0.0

- Decision ID: `CISB-1.0.0`
- Authority: `USER-2026-07-19-PLATFORM-DELEGATION`
- Accountable: Hei
- Platform Responsible: keliihall
- Security Responsible: keliihall
- Release Responsible: keliihall
- Effective at: capability probe 全绿后，以该次受信 run 的创建时间为准
- Machine contract: `contracts/release/ci-supply-chain-baseline-1.0.0.json`

## 决策

选择 GitHub.com public repository `keliihall/ScholarSense-bmad-method`（repository ID `1305224312`）和 GitHub Actions。制品以 OCI SHA-256 digest 存入 GHCR 的 candidate、stage、production 独立 namespace；GitHub Artifact Attestations 保存可按 subject digest 查询的 provenance，Cosign v3.1.2 使用 GitHub OIDC keyless bundle 做外置签名，Trivy v0.72.0 扫描，ORAS v1.3.3 只按 digest 搬运。

stage 与 production 使用各自 GitHub protected environment。构建、attestation/signing、promotion、独立 verifier 是不同 job identity，默认 workflow token 为只读，只有对应 job 显式取得 `packages`、`attestations`、`artifact-metadata`、`id-token` 或 ledger 所需权限。promotion 使用 `releaseVersion + targetEnvironment` 作为业务键，创建不可变 ledger Git ref 作为 compare-and-set；同键异 digest 必须冲突，回退只能重新验证并提升历史 digest。

## 激活门

本 ADR 的平台选择与具名职责已由 Hei 授权冻结，但在 `platform-probe.yml` 尚未证明以下能力前，机器合同保持 `approved-pending-capability-probe`，只允许 U1：

1. GHCR 按 digest 上传、回读并逐字节核对；
2. GitHub attestation 能按 subject SHA-256 查询；
3. Cosign bundle 能以固定 workflow identity 和 OIDC issuer 复验；
4. 受保护 stage job 能进行 digest-only copy，并以 create-only Git ref 证明 CAS 冲突；
5. 无写权限的独立 verifier 能从远端重新取回并验证结果。

探测全绿后，把 immutable run、attestation、OCI digest、ledger ref URI 写回 machine contract，状态改为 `approved`。任何 URI 缺失、摘要不一致或权限漂移都以 `CISB_PLATFORM_BASELINE_INCOMPLETE` fail closed，不得用本地报告替代。

## 安全固定

所有外部 Action 使用完整 commit SHA；工具下载先验证上游 release asset SHA-256，并保留对应 Sigstore bundle digest。当前固定版本与摘要在 machine contract 中，升级必须发布新 CISB。禁止 `pull_request_target` 写权限、`write-all`、可移动 Action/tag、未固定容器、secret 回显和未批准外部上传。

## 边界

本决策只覆盖发布供应链平台，不声明业务数据加密、保留销毁、DR、完整月 SLI 或真实生产业务部署已通过。正式 Web U4 仍必须使用与 `TEST-ENV-1.0.0` 精确匹配的一次性/受控 runner；普通 GitHub-hosted Ubuntu runner 不能替代它。
