# Story 2.5b Verification — 执行证据化质量恢复

Date: 2026-08-13
Scope: Story 2.5b only
Result: PASS — complete

## Verified outcome

Story 2.5b installs one executable recovery path for a real active quality-fuse episode:

`request -> durable validation worker -> QRP evidence/preview -> D4 maker-checker approval -> identity-access execution lease -> ingestion-quality owner transaction -> fused -> recovering`

The successful owner transaction also writes the recovery audit, two owner-local outbox records, idempotent response and one local execution JTI; the identity-access lease reaches `executed`. The task remains `open`, the episode remains active, and no path publishes `eligible`, creates RuleEvaluation/Candidate/Clue, closes the task, or claims production recovery.

The positive closure is exercised by `QualityRecoveryRuntimePostgreSqlIT.threeApprovedStreamingBatchesAndRealSampleProviderProduceSelfContainedD4Evidence`. It uses actual PostgreSQL workload logins, closed functions and JDBC repositories for the recovery worker, signal-evaluation normalized input/durable replay, rule-governance business-owner binding, identity-access natural-person binding/approval/lease, and ingestion-quality owner commit. A same-key execute replay returns the original commit and does not consume a second JTI or emit duplicate owner facts.

## Authority and immutable predecessors

- QRSCR binding-set approval: `AUTH-2026-08-12-QRSCR-001`, effective `2026-08-12T00:00:00+08:00`, approved digest `sha256:dfa05c804f27a4c92edb3c6ed7253c3bdb3fa7d3c6d77be7121a44c716b7aed5`.
- QRSCR raw SHA-256: `35df983b04d3f80482d1bd21776137712f60f934d79480e47fc002fb2148bb3d`.
- Recovery lock 1.2 raw SHA-256: `93db910242e8d89a2ddff147f086ea3f53b72ddcc11995e48b7c7feee95d176e`.
- V15 remains `21a66478ce29bf71838f4375c7162f5bbd390d5db60661981c5acfa03c419edb`.
- V16 remains `a720f004d30962b4b7bff2e8e734e1ee211f06866e8c3e02ec5c5d36b6eb791c`.
- Additive global migrations are V17 identity-access HRAP/lease, V18 rule-governance checker binding, V19 signal-evaluation sample provider, and V20 ingestion-quality recovery.

The sample wire conflict was corrected additively: provider/evidence/result 1.0 predecessors remain locked, while 1.1 supplies bounded per-stratum count/digest evidence required by the self-contained pack. Cross-module dependencies use public `.api` ports; ingestion-quality retains an internal outbound port and adapter.

## Runtime and database evidence

- Java full suite: 1245 tests, 0 failures/errors/skips.
- PostgreSQL 18.4 clean and upgrade runs: PASS, 20 continuous migrations and 187 tests.
- PostgreSQL schema fingerprint: `a53a152f6d1e6851e1b92b0b4a00efa670e3e8e69b4cb727860d3123be8b9794`.
- PostgreSQL inventory summary: `181|2478|3462|458|40|171` for tables/columns/constraints/indexes/triggers/functions.
- Review closeout: all 26 findings are resolved; recovery contract/API tests pass 28/28 and the standalone recovery checker passes.
- Recovery contracts/API/release and standard scripts: 584 tests PASS.
- BMAD/PAB scripts: 145 tests PASS.
- Recovery contract checker: PASS with QRP, D4 owner, sample boundary, two-phase handoff and predecessor locks.
- Production-pollution and privacy-canary scans: PASS.

The database gates cover actual-login grants, raw DML denial, clean/upgrade equality, common per-source advisory locking, stale generation fences, current evidence drift, idempotent replay and single-use execution. V20 rechecks current members/watermarks/object versions and consumes the owner-local execution JTI in the same transaction that performs `fused -> recovering`.

## Frontend evidence

`verify_frontend.sh --offline` completed two isolated clean replays:

- TypeScript typecheck: PASS twice.
- Vitest: 15 files / 112 tests PASS twice.
- Playwright: 177 PASS / 35 intentional skips across 1440, 1366, 768 and 375 projects, twice.
- Build budget and privacy canary scan: PASS twice.
- Source SHA-256: `394adb0cce9e1a3ac1a8df3ac7b4704fca144e98091bae7b80de4f3557655db4`.
- Lock SHA-256: `92a4bd6376de161f75dd48179742135b1f32651a63c4c92c81b4d8a5ede5b667`.
- Dependency-tree SHA-256: `0fc4e2aef3fa7ae56d9f9372bbb40d4495b5f48e6a241f420fcb1a10a7d550e2`.
- Build SHA-256: `39aeb4b89b2ca80a8d8d057ea0e722c08ce0d39403dc521c6ddfd9676cb50941`.

The recovery action is separately gated by `quality-fuse.recover`; read capabilities remain independent. Volatile command state is cleared on identity/capability/connectivity/narrow/unmount changes, 409 requires an explicit new preview, online recovery never auto-submits, and the 375px project makes zero recovery-object requests and renders no object DOM.

## Release evidence

ReleaseManifest/EvidenceIndex v8 and ingestion-quality runtime/roles v5 are additive successors. They bind the recovery lock, OpenAPI 1.1, real sample provider, identity-access HRAP owner/lease, literal action capability and positive owner-transaction closure. Predecessor v7/v4 raw bytes are checked and future claims remain absent.

The final clean candidate commit/tree and the two-attempt artifact/build-manifest digest are reported with the final handoff and retained in the generated `build-manifest.json`. The candidate is materialized through a temporary Git index and detached worktree; the user branch, index and working tree are not reset or checked out.

## Explicitly deferred — no false closure

The following remain `runtimeEvidenceClaim: none`:

- Story 2.5c full observation, relapse handling, `recovering -> eligible`, episode/task closure and final `latestActionableAt` filtering.
- Story 3.2 live rule-consumer activation and production proof of zero new RuleEvaluation/Candidate/Clue while fused/recovering.
- Story 5.5 final public-task apply/writeback.
- Production-duration and protected-environment evidence.

Local/CI fixtures, HTTP success, database rows, deployment profiles and this verification document are not relabelled as any of those deferred production claims.
