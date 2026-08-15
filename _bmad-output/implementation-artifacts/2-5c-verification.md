# Story 2.5c Verification

Date: 2026-08-15 (Asia/Shanghai)

## Verified source identity

- Baseline commit: `48b981d2295e8e2f17d26a3d0695548d6388f7fa`
- Baseline tree: `aeed79abf664e7e29920e059968fefb07f27dedd`
- Detached verification candidate commit: `b253433a9e2406fea0b69e29695b8279f3092f23`
- Detached verification candidate tree: `383291bb697a0a1e6dc1219d3a0390ef90b44c21`
- The detached candidate contains the complete executable/contract change plus all 16 review patches and regressions. This evidence file and the final Story/sprint status write-back were appended after the candidate was verified; no branch or user index was changed.

## Executable verification

| Layer | Command / scope | Result |
|---|---|---|
| Backend | `_bmad/scripts/with_pab_toolchain.sh backend/mvnw -f backend/pom.xml clean verify` | PASS — 1101 tests, 0 failures/errors/skips; packaged Spring Boot jar |
| Finalization review regressions | focused finalization/observation/JDBC/controller suite | PASS — response-loss replay, re-request, policy drift, later facts, direct correction, current fence, owner-derived decision ID, bounded fact tail and READY retention are covered |
| PostgreSQL | `_bmad/scripts/with_pab_toolchain.sh scripts/run_audit_postgresql_tests.sh` | PASS — PostgreSQL 18.4 clean+upgrade, actual-login/raw-DML/fencing/concurrency suite; 21 migrations |
| PostgreSQL inventory | clean/upgrade schema summary | `tables|columns|constraints|indexes|triggers|functions=189|2607|3657|483|49|195` |
| PostgreSQL fingerprint | clean equals upgrade | `ce97ee7069751f99f22af9a780148211a7fe29e4e34897194593ea50bba2c762` |
| Python/BMad | `_bmad/scripts/tests` discovery | PASS — 145 tests |
| Python/project | `scripts/tests` discovery | 595 executed; 593 passed directly. The two environmental checks (`JAVA_HOME` absent and ignored/generated production-tree artifacts) then PASSed exactly under the project toolchain with generated artifacts temporarily outside the production tree. |
| Contract gates | finalization, recovery, release source/workflows/contracts | PASS |
| Release focused | release v9 + source inventory + workflow tests | PASS — 20 tests |
| Frontend direct | typecheck, 116 Vitest tests, production build/budget, Playwright | PASS — 177 passed, 35 intentionally skipped |
| Frontend deterministic | `scripts/verify_frontend.sh --offline` | PASS — two clean offline replays, each with typecheck, 116 Vitest, build/privacy scan and 177 Playwright passes |
| Release deterministic | release v9 generation twice in the detached clean checkout | PASS — both 3-test runs green and byte-equivalent |

Deterministic digests:

- ReleaseManifest v9 canonical SHA-256: `deeb5bff3f6dd8e5701421e0342bdf68f7f3f63f126273990a5f6e520e632ea4`
- Frontend source: `3a6d03402aa1d292059ade577c17c983069b023f58c98185f645b4d91fc2c9eb`
- Frontend lock: `92a4bd6376de161f75dd48179742135b1f32651a63c4c92c81b4d8a5ede5b667`
- Frontend dependency tree: `0fc4e2aef3fa7ae56d9f9372bbb40d4495b5f48e6a241f420fcb1a10a7d550e2`
- Frontend build: `48aa5b2f3f00aa44792eb36dab2d53d3639e22201729d93b74b9753183f0f184`

## Closure evidence

- Observation uses the durable V21 fact/current/decision and fenced worker paths. Streaming `PT60M`/3-pair and daily `P1D`/2-pair boundaries, no-data, gap, poison, stale, dependency-unavailable, policy/member/watermark drift and verified failure are fail-closed.
- Finalization performs a fresh `recovering -> eligible` D4 approval/receipt/15-minute lease bound to the current observation decision and versions. The owner transaction atomically commits all affected eligibility histories/current rows, episode close, same-task close, window outcomes, audit/outbox, execution JTI and idempotent response; it performs no network I/O.
- Terminal replay is checked before stale optimistic-version rejection. Same-key/different-command conflicts; different-key/same recovery+watermark replays the winning owner result without issuing a second lease.
- Observation failure reuses the existing QFTP failure-wins latch. A true relapse after terminal close produces generation+1 episode/task through QFRTP and cannot reopen the closed task.
- Task delivery remains a post-commit sidecar. Pending/retrying/failed delivery does not change local eligible/closed truth or claim that the public target applied the close.
- Strict OpenAPI 1.2, field-level authorization, concealed existence, source-filter-before-pagination, read audit, no-store/no-referrer and exact frontend decoders are covered by MVC/JDBC/contract/frontend regressions.
- Review closure additionally proves identity-owned approval/lease verification, response-loss idempotency, terminal approval restart, durable policy drift, refresh-safe approval IDs, same-generation recovery reuse, schema parity, explicit irreversible confirmation, active READY retention and bounded long-running observation claims.

## Preservation and deferred boundaries

- `git diff --exit-code HEAD` is clean for dependency manifests (`backend/pom.xml`, `frontend/package.json`, `frontend/package-lock.json`) and frozen V15/V16/V20 migrations.
- Release v9 tests pin predecessor release v8 schemas and ingestion-quality runtime/roles v5 raw SHA-256 values; source inventory and release-contract gates pass. No dependency was added or upgraded.
- Release/runtime successor: `RELEASE-MANIFEST-9.0.0`, `EVIDENCE-INDEX-9.0.0`, ingestion-quality runtime/roles `6.0.0`.
- Story 3.2 consumer activation: deferred, `runtimeEvidenceClaim=none`.
- Story 5.5 public task-close apply: deferred, `runtimeEvidenceClaim=none`; local delivery confirmation is not public business apply.
- Story 2.7c NFR-8 final acceptance: deferred, `runtimeEvidenceClaim=none`.
- Production-duration evidence: deferred, `runtimeEvidenceClaim=none`. The 60-minute/24-hour rules are executable with trusted manual-clock microsecond boundary tests; no wall-clock sleep is represented as production evidence.
