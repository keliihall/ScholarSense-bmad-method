# Story 1.1d Verification

## 结论

**PASS — 可进入 review。** Story 1.1d 的 U1—U4、全部 Acceptance Criteria 与真实平台完成门均已通过。最终 release、stage promotion、目标 store 回读对账和 production rollback 绑定同一 source commit `f0c74aa51bd316234382543642411ab968f257cd`、release version `1.1.1` 与 artifact digest `sha256:0d0fb03b26f3f9f605aca08a108d0cc8aa126baffb1e4da3662bb8d0ded8d8eb`。

## 本地可复现回归

在没有代码变更的条件下连续两次执行：

```text
PYTHONDONTWRITEBYTECODE=1 ./scripts/verify.sh
```

两轮均退出 `0`，且均得到 artifact set `9f8488d543a978a53412118072616305684ca01867de882b54e1418b5394d8ea`：

- 顶层与两个隔离 clean release attempt 分别完成 backend `36/36`、历史资产审计 `145/145`、Python `167/167`。
- 每个隔离根的两次 frontend replay 分别完成 unit `27/27`、Playwright/axe `20 passed / 4 skipped`。
- 两轮最终 JAR、前端规范化归档、BuildManifest 与 artifact set 摘要逐项相同；没有半成品或受控源码漂移。

## 正式 Web 与视觉基线

- Golden approval run：`29668939935`，job `88144715639`，8 个 matrix cell 全部通过；Hei UX/Brand 与 Web QA 的委托审批时间为 `2026-07-19T09:47:15+08:00`。
- 只读 VGB：`ghcr.io/keliihall/scholarsense-visual-goldens@sha256:b2d686431983a7ab2c1797b2132b190aca352e40f2dc7252f8acf2818845106c`。
- 正式前端 artifact SHA-256：`811cf79679aa946f52cf7c17451932cf89ef89e4a934b0fdd9e72e27b66ea5a2`。
- runner：macOS `26.5.2` build `25F84` arm64；runner fingerprint `4b158…`，font digest `3ff557…`，TEST-ENV SHA-256 `1361a3…`；使用冻结的 Chrome 150 与 Edge 149 实际二进制。
- release job 直接验证并服务 store 中冻结前端字节；未重建源码、未更新 golden、未把桌面 Chromium 冒充 App/WebView 证据。

## 真实受保护 release 与 promotion

受保护 release workflow run `29676318745`，attempt `2`，source `f0c74aa51bd316234382543642411ab968f257cd`，release `1.1.1`，target `stage`。全部 job 通过：

| Job | 结果 | 时长 |
|---|---:|---:|
| build-cas | success | 5m05s |
| sbom-scan | success | 47s |
| artifact-attestation | success | 27s |
| formal-web | success | 3m35s |
| release-manifest | success | 6s |
| manifest-signature | success | 14s |
| evidence-index | success | 11s |
| independent-verifier | success | 16s |
| promotion | success | 15s |

不可变证据：

- artifact：`ghcr.io/keliihall/scholarsense-release-candidate@sha256:0d0fb03b26f3f9f605aca08a108d0cc8aa126baffb1e4da3662bb8d0ded8d8eb`
- SBOM：`ghcr.io/keliihall/scholarsense-release-evidence@sha256:47553a60323c3e442691ef9cd8ea3925fae78ea2ee42053c0fae8d7aa2be9fe7`
- attestation：`ghcr.io/keliihall/scholarsense-release-evidence@sha256:2ade6f4c8a4293244a461cba7c0d45e9513b978d1bbd47b767a38d5a8ae5d8b9`
- formal Web report：`ghcr.io/keliihall/scholarsense-release-evidence@sha256:f39da6708328368574145ceb5bdaae4a4ac8764ec3ee21a172c7c27208fbff41`
- ReleaseManifest：`ghcr.io/keliihall/scholarsense-release-manifest@sha256:c033c45f6a18a1b8478dd780c42c239a0a849e749090c2b78c9b9cd6b892c244`；canonical SHA-256 `15631a76f17fc68eade6466b5fe3a2ab109e84adbe6f5680c6a070d887ecb9ff`
- manifest signature：`ghcr.io/keliihall/scholarsense-release-evidence@sha256:5f24e66ab5a0da263ccd07cc173c26b7bcd5518c2162e3f2b23f28a2040ce6c8`
- EvidenceIndex：`ghcr.io/keliihall/scholarsense-release-manifest@sha256:decb5968a9446f047f464031d2a4202d38ffdb8eba73fd3dd23eae08fec81e94`；canonical SHA-256 `729a197adad128a3b8908d0a16438eef683df97a8474775ab80f0f22f10519b4`
- stage ledger：`refs/tags/promotion-ledger/1.1.1-stage`，commit `aba710aec83990a3f4ceed6e4ad17d883196427e`；远端回读 `promotion-reconciliation: PASS`。

一次性 formal runner 在 job 结束后自动注销、移除 credentials 并清理 root；仓库 self-hosted runner 数量回读为 `0`。

## 真实 production rollback

受保护 rollback workflow run `29676837068` 通过，source 与 release material 与上述 release 完全一致。production 审批后依次通过：

- 读取并验证 `refs/tags/promotion-ledger/1.1.1-stage`；
- digest-only 提升到 `refs/tags/promotion-ledger/1.1.1-production`；
- 从 production ledger/store 回读对账同一 artifact digest；
- `rollback-release: PASS`，没有重建、重签名或覆盖历史 manifest/ledger。

production ledger commit：`25d704743accad600f7db14211095a590c7b0017`。

## 受保护运行失败审计

| Run / attempt | 本轮失败 job / step | 上轮失败 job / step | 是否进入更后阶段 | 根因 | 修改文件 | 新增回归测试 |
|---|---|---|---|---|---|---|
| `29675404248` | `promotion` / `Promote verified digest to protected target` | `29674437791` 同一 job / step | 否；连续两次停在 promotion | GitHub Git Blobs API 对 base64 content 每 60 字符插入 LF，严格 decoder 未先去除 GitHub 合法换行，导致已存在 ledger 被误判为 `PROMOTION_LEDGER_RECORD_INVALID` | `release/promotion.py` | `test_git_ref_ledger_reads_github_line_wrapped_base64_blob_content` |
| `29676318745` attempt 1 | `sbom-scan` / `Install upstream-bundle-verified security tools` | 上一受保护 run 已进入 promotion | 否；尚未越过 SBOM 安装 | 上游下载连接瞬时重置：`curl: (35) Recv failure: Connection reset by peer`；未发现代码或锁漂移 | 无 | 无；原样重跑失败 job |
| `29676318745` attempt 2 | 无 | attempt 1：`sbom-scan` / tool install | 是；通过 formal Web、manifest、signature、index、verifier 与 stage promotion | 瞬时网络故障在未修改代码的重跑中消失 | 无 | 无 |
| `29676837068` | 无 | `29676318745` attempt 2：无 | 是；进入 production 并完成 rollback | 无失败根因 | 无 | 无 |

连续两次 promotion 失败后已停止 patch 并完成根因审计；修复仅处理原始 AC 所需 ledger 回读。针对 ORAS 空仓库诊断的另一项必要修复只接受精确 `name unknown` 为目标不存在，并由 `test_ghcr_prepare_treats_registry_name_unknown_as_an_absent_repository` 覆盖。没有新增 hardening、兼容范围或 Acceptance Criteria。

## 最终判定

- AC-1.1d-HAPPY 及其 PLATFORM-FREEZE、SOURCE-CI-TRUST、REPRODUCIBLE-ARTIFACTS、SBOM-SCAN、ATTEST-SIGN、RELEASE-MANIFEST、CANONICAL-SCHEMA、EVIDENCE-LIFECYCLE、PROMOTION-ROLLBACK、FORMAL-WEB-EVIDENCE、SECURITY-EVIDENCE-BOUNDARY 分解门全部满足。
- release、promotion、rollback 的远端运行与不可变证据已完成；Story 状态更新为 `review`。
- Clean completion：没有剩余 AC blocker，停止继续寻找改进项。
