# ADR：CI / Supply-chain Baseline CISB-1.0.0

- Decision ID: `CISB-1.0.0`
- Authority: `USER-2026-07-19-PLATFORM-DELEGATION`
- Accountable: Hei
- Platform Responsible: keliihall
- Security Responsible: keliihall
- Release Responsible: keliihall
- Effective at: `2026-07-19T05:32:52+08:00`
- Machine contract: `contracts/release/ci-supply-chain-baseline-1.0.0.json`

## 决策

选择 GitHub.com public repository `keliihall/ScholarSense-bmad-method`（repository ID `1305224312`）和 GitHub Actions。制品以 OCI SHA-256 digest 存入 GHCR 的 candidate、stage、production 独立 namespace；GitHub Artifact Attestations 保存可按 subject digest 查询的 provenance，Cosign v3.1.2 使用 GitHub OIDC keyless bundle 做外置签名，Trivy v0.72.0 扫描，ORAS v1.3.3 只按 digest 搬运。

stage 与 production 使用各自 GitHub protected environment。构建、attestation/signing、promotion、独立 verifier 是不同 job identity，默认 workflow token 为只读，只有对应 job 显式取得 `packages`、`attestations`、`artifact-metadata`、`id-token` 或 ledger 所需权限。promotion 使用 `releaseVersion + targetEnvironment` 作为业务键，创建不可变 ledger Git ref 作为 compare-and-set；同键异 digest 必须冲突，回退只能重新验证并提升历史 digest。

## 激活证据

受信 run [`29661725147`](https://github.com/keliihall/ScholarSense-bmad-method/actions/runs/29661725147) 在 source commit `c19e4e31fa07fd1242c9120cc2a6ef77112938e7` 上完成以下能力探测，machine contract 因而激活为 `approved`：

1. GHCR 按 digest 上传、回读并逐字节核对；
2. GitHub attestation 能按 subject SHA-256 查询；
3. Cosign bundle 能以固定 workflow identity 和 OIDC issuer 复验；
4. 受保护 stage job 能进行 digest-only copy，并以 create-only Git ref 证明 CAS 冲突；
5. 无写权限的独立 verifier 能从远端重新取回并验证结果。

探测产物 OCI digest 为 `sha256:864b4ca2860632cdc30e672e40734ec9dd3926fa826d5337a28d4f53d51c1987`，Sigstore/query evidence digest 为 `sha256:424382f12b571419c7ae91df30f8209f6b3945dfce579272430dac27f39534f6`，attestation subject 为 `sha256:c221277ad1e6632e9fa15cb55c827ef60c79b9d5ed2c2952ad95f4f346d72d42`。stage copy 保持相同 OCI digest；ledger ref `refs/tags/promotion-ledger/probe-29661725147-stage` 的 commit 为 `4d361b70b41a7fc55fe2333e452cc1ab37e439e5`，第二次异值 create 被拒绝。main 与 promotion-ledger 分别由 ruleset `19154234`、`19154231` 保护。

任何 URI 缺失、摘要不一致或权限漂移都以 `CISB_PLATFORM_BASELINE_INCOMPLETE` fail closed，不得用本地报告替代。

## 安全固定

所有外部 Action 使用完整 commit SHA；工具下载先验证上游 release asset SHA-256，并保留对应 Sigstore bundle digest。当前固定版本与摘要在 machine contract 中，升级必须发布新 CISB。禁止 `pull_request_target` 写权限、`write-all`、可移动 Action/tag、未固定容器、secret 回显和未批准外部上传。

## 边界

本决策只覆盖发布供应链平台，不声明业务数据加密、保留销毁、DR、完整月 SLI 或真实生产业务部署已通过。正式 Web U4 仍必须使用与 `TEST-ENV-1.0.0` 精确匹配的一次性/受控 runner；普通 GitHub-hosted Ubuntu runner 不能替代它。
