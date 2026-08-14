# Story 2.4 Verification Evidence

Verified again on 2026-08-11 with the repository-pinned PAB toolchain after resolving all four review findings. The implementation candidate was materialized in an isolated temporary clone and committed only there, so the user's working tree, index and branch reference were not changed by verification.

## Candidate identity

- Candidate commit: `19a0e6baefa426b8289e519ae3734fe84836e8fe`
- Candidate tree: `ac42cffe4490b21c3f23a6ab8f1b308095706d8b`
- Baseline: `f3d2d4279eead340deedae87f72250ce6a7c5f3e`
- Full command: `./scripts/verify.sh`
- Result: `PASS`

## Reproducible gates

- Bootstrap and pollution: exact Maven/Java/Node/browser preparation, contract seeds, frontend structure/baseline and production-pollution checks passed.
- Backend: clean Java 25 build and 936 tests passed with zero failures, errors or skips; packaged JAR and backend privacy canary scan passed.
- Python/checker suites: 145 standard-library regression tests and 520 contract, mutation and release workflow tests passed. The Story 2.4 eligibility contract/persistence/API checkers and their negative mutations were included.
- PostgreSQL: PostgreSQL 18.4 clean install and full V000001→V000015 upgrade produced the same schema fingerprint `83deb9ba1a1a0e6a4ecb4293a4396f54e7e6e927bb2e351729a4a827af53c0bb` and inventory summary `144|2059|2854|367|34|104` for tables, columns, constraints, indexes, triggers and functions. Actual-login, least-privilege, raw-DML rejection, rollback, replay, immutability, Story 2.4 applied-path and overlapping-source RuleVersion serialization probes passed.
- Frontend: both isolated offline replays passed typecheck, 103 Vitest tests, production build/budget/privacy checks and the complete 200-test Playwright matrix (`174 passed`, `26` intentional responsive skips). Both replays produced identical source, lock, tree and build hashes; the build hash was `49487d6aae9e2b5e85fe9984726bbe3da52b89f62061393d1fa50a0d76ce1f87`.
- Release v6: additive manifest/evidence schemas, 32-input release assembly, workflow/security/source-inventory checks and v1-v5 preservation tests passed. Two clean release attempts produced the same artifact set `d4a8c6d79963df33f527828bb388813a5974a36c7b2075c88e903dc054b56c88`.

## Review patch coverage

- Cross-rule event regression proves each RuleVersion is evaluated only with its own dependency states.
- JDBC boundary and real PostgreSQL concurrency tests prove affected RuleVersions are locked in stable order before state is reread and eligibility is calculated.
- Query-service regressions bind the complete member/version set to one shared authorization generation and seal that generation immediately before audit/serialization.
- Eight decoder mutations prove CloudEvent envelope and data/batch/snapshot identities, versions, states and evidence values are cross-bound fail closed.

## Evidence boundary

The verified runtime evidence covers owner-local contracts, PostgreSQL persistence/roles, API projection, frontend behavior and reproducible release assembly. The Story 2.5 recovery caller, Story 3.2 rule-domain consumer, target deployment activation, production duration/SLO, target KMS/trusted-time, backfill operations and retention activation remain `runtimeEvidenceClaim=none` or deferred; fixtures, design targets and outbox rows are not reported as production activation.
