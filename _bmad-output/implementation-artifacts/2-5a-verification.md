# Story 2.5a Verification Evidence

## Final review repairs (2026-08-12)

- The production retention adapter now executes both `iq_cleanup_expired` and `iq_cleanup_quality_fuse_expired` with the same trusted cutoff, so the existing scheduled workload reaches V16 episode/task/delivery/idempotency/read-audit retention.
- The task relay now holds a task-scoped PostgreSQL session advisory-lock permit across the final current-route authorization, external send and local finalizer. Route updates take the matching transaction advisory lock, so a newer route cannot commit inside the authorization-to-send window; external I/O remains outside database transactions.
- JDK 25.0.3 passed 1,159 Java tests with zero failures, errors or skips. PostgreSQL 18.4 passed all 185 integration tests; clean and upgrade fingerprints match at `1e9770c9e714147ac501c116de21d7f340a0765fa0ffa1fd2b820e2e3c93f6b6` with summary `156|2205|3076|400|40|122`.
- Thirty-three focused fuse contract/persistence/API tests and 145 PAB tests passed. The repository-wide project-script discovery remains unsuitable in this working tree because the pre-existing `frontend/node_modules` directory intentionally triggers its production-pollution gate; this repair did not create or modify that directory.

Initially verified on 2026-08-11 and review-closeout verified on 2026-08-12 with the repository-pinned PAB toolchain. Each implementation candidate was materialized and committed only in an isolated temporary clone; verification did not change the user's branch reference or index.

## Review closeout candidate and gates (2026-08-12)

- Isolated candidate commit: `b7257edc35ac0ae7e83674ae5ea9c0371e48c82f`; tree: `db9206ec83945f424dc5a1c09b6d6d8c75493d45`. The candidate was cloned from the real repository object graph, overlaid with the current Story 2.4/2.5a worktree, committed in the temporary clone and deleted after verification.
- Direct backend regression passed on JDK 25.0.4. The clean release verification passed 972 Java tests with zero failures, errors or skips in each of two attempts.
- PostgreSQL 18.4 clean V000001→V000016 and upgrade inventory produced identical schema fingerprint `d263c1706c0e6389365bdc0ceb79180939d854d39a74d6b0dfe4acdc3e45dc0d` and summary `156|2205|3076|400|40|122`; 185 integration tests passed, including recovering-pass preservation, route-sequence pre-send/finalizer fencing, exact workload privileges, retention and audit behavior.
- Contract/persistence mutation gates passed 28 focused fuse tests. The complete clean verifier passed 145 PAB tests and 551 project script tests in each release attempt; production-pollution scanning passed in the isolated candidate without generated dependency/build directories.
- Direct frontend verification passed 108 Vitest tests, typecheck, Vite build and the build budget. Playwright passed 176 tests with 32 intentional narrow-capability skips across the complete 1440/1366/768/375 matrix. Both clean offline replays matched frontend source `e2a1ce7c427cf40fd2f0ffd3348708656be2d5b5231299a2b16cc3d8844da464` and build `548b62bdd15d29c31401e310cc20259d1b6072515021c154c4d308b00e38e8fd`.
- Two full clean release attempts matched build input `771f999d0c3b837251bc2136b65669ffb4866f42de5c560d26bf32332503d358` and artifact set `29ff6953ab7c302fe106e8262c476d2dbdd3e2a77eac8ae9a7b5cb5daf1988b6`. Backend JAR: `0cb25c79b1e8aec4011441adbf49c1ec5b433d55a622c17a092944d28ea3ec54`; frontend archive: `f21f274642d33e47171397ce545529ade358393faa3b0384b3cd9d88305ce6f7`.
- Review closeout resolves all 17 recorded findings while preserving the original evidence boundary: target activation, Story 2.5b/2.5c recovery closure, Story 3.2 consumption and Story 5.5 final public apply remain deferred with `runtimeEvidenceClaim=none`.

## Initial candidate identity (2026-08-11)

- Candidate commit: `505041a96b4bcb03807013abd6e312c44c6c6b03`
- Candidate tree: `ee6df7260c71d75aca7f26d00d2af4ab4a9f4df8`
- Working baseline: `017997ae`
- Full deterministic command: `python3 -B release/build_release.py <clean-candidate> <new-output>`
- Result: `PASS`

## Reproducible gates

- Contracts and mutation: fuse latch/episode/task/PIC/retention contracts and predecessor byte locks passed, including nine Story 2.5a mutation/regression tests and release v7 false-claim/digest-rebinding rejection.
- Backend: Java 25 clean verify passed 1,139 tests with zero failures, errors or skips. MVC denies mutation/recovery routes; application/JDBC tests cover authorization generation recheck, audit-before-return and the constant three-query page upper bound.
- Python/checkers: 145 PAB tests and 537 project contract, mutation, release, workflow, privacy and production-pollution tests passed in each clean attempt.
- PostgreSQL: PostgreSQL 18.4 clean V000001→V000016 and V000015→V000016 produced the same schema fingerprint `537344e9eb49fbf4c67071ead12ec3935051c53630b523f59d878b24c846e95c` and inventory summary `155|2179|3045|393|38|115`. Seven actual workload logins, raw-DML denial, rollback, CAS/concurrency, one-active-task, lease/fencing/reconcile, read-audit and 64 KiB/64 KiB+1 probes passed.
- Frontend: 107 Vitest tests passed. The complete Playwright matrix passed with 175 tests and 29 intentional `<768px` skips across 1440, 1366, 768 and 375 widths; axe, 200% zoom, keyboard/live-region, no-persistence and no forbidden-object DOM/a11y content checks are included. Both offline replays produced frontend build hash `cf595e530b84bc4646ea652eaf9761218145899ef95418134a487b2c4b68d685`.

## Release v7 and deterministic artifacts

- ReleaseManifest/EvidenceIndex v7 binds 36 controlled inputs and the runtime/roles v4 successors. CI assembly and OCI media types now select v7; false target activation remains rejected.
- Both clean build attempts used build input `6b593b2fd917602846acb7ae96dd7abe70cefa4372792e2f6c3b82b240f4f4f1` and produced artifact set `833e9569c267d7b4a37e7c7cccc34821ec33f0f0c37ea564f011e2da77bf0a49`.
- Backend JAR: `83ddff188a0e82786057c3c9cbd47fdb5a3c58c4cae9068bb63facea2f6adccb`; frontend archive: `96a7ae81e524d180645fcba9c41b8cf146fbaa84d69075c663bcb16c7ff9f09d`.
- Runtime v3 remains `cdfa4a0ff993ecb8c8a6380fcbe319a21b2b960b74711ef3d57bb41a0e8263a5`; roles v3 remains `01e670bbd03c6507d2344f705040fbff0a2fad4b5d784789fdadc00e78906cf8`; release/evidence v6 schemas remain `d49391835cb8e56c76a3d769f164a86ff38e1a95e2af826dd2e5ae42ccc855e5` and `ec808e03af39f80d17f2c458022b0c3d94a11e48d1051bb2afb66aefdff825fc`.
- Dependency files were not changed. Toolchain, backend and frontend lock digests are respectively `ba575e6feb8ab3990a81ec6d7b7e30793839fcd999c282828f64eed4f0f4595c`, `ad731dae67e6623b453522ac7fff5b7575aee36c9806c6fe7724fb68d456e34f` and `92a4bd6376de161f75dd48179742135b1f32651a63c4c92c81b4d8a5ede5b667`.

## Evidence boundary

Verified evidence covers owner-local latch/task/outbox conformance, independent delivery state, source-owned read projection, PostgreSQL roles, UI behavior and reproducible artifacts. PIC target activation, Story 2.5b/2.5c recovery, Story 3.2 consumer apply, Story 5.5 final public apply, protected target credentials and production-duration evidence remain deferred with `runtimeEvidenceClaim=none`. Fixtures, database rows, transport confirmation, deployment profiles and local replay are not reported as production activation or downstream business closure.
