# Story 1.9 Verification Record

## Outcome

- Story implementation candidate: `c6ba4f68b0f24f1145a0e4ec72ab09cf9cbe5e40`
- Candidate tree: `e1055e55f3a5201aed6e412cbbecffacc9c54365`
- Merge baseline: `8c37a555d904a5405b9d9086acf29554b14d4ff9`
- Candidate branch: `codex/story-1-9-public-integration`
- Highest gate: `./scripts/verify.sh` PASS from a detached, tracked-clean candidate worktree
- Reproducible artifact set: `8e30b70b880cfd1cc29a87a0cb016b0de94aa7827ce925f82ed5fa20930eb60a`

## Automated gates

| Layer | Result |
|---|---|
| Backend/JUnit | 483 passed; 0 failed, 0 errors, 0 skipped |
| BMAD script tests | 145 passed |
| Project Python tests | 320 passed |
| PostgreSQL 18.4 | clean and V000001-V000009 upgrade PASS; production inventory unchanged |
| PostgreSQL schema fingerprint | `bccf4f372d54ecbe5d8a92d9b42074ffe836cedbbd97440aa9e755c705bf9099` |
| PIC local conformance | 10/10 passed; failed/skipped 0; cleanup pass |
| Frontend unit | 70 passed per clean replay |
| Frontend Playwright | 113 passed, 15 intentionally skipped per clean replay |
| Frontend reproducibility | two offline replays matched |
| Release reproducibility | two clean attempts matched; build-release PASS |
| Production pollution/privacy | 20,882 files/members, 152,592,654 bytes, 11 canaries; PASS |

Frontend reproducibility digests:

- source: `c14388a7a286a349e61b9703fa8cc9ea5672e6a77654630ccb423d584fa7ca16`
- package lock: `92a4bd6376de161f75dd48179742135b1f32651a63c4c92c81b4d8a5ede5b667`
- dependency tree: `0fc4e2aef3fa7ae56d9f9372bbb40d4495b5f48e6a241f420fcb1a10a7d550e2`
- build: `643381d25e53230c713eef8da6be68a6ef8f413d9bd30ea28a7f2bd38029d353`

The immutable v1 release schemas remained byte-identical:

- `release-manifest.schema.json`: `9ee461f8772441366396cb181dd358d3df848aa834837ba19ff76d484a5b1f6e`
- `evidence-index.schema.json`: `c9ad274343c42f3b73ed6f6ee62a8adfdb79c490f60fd6b4c95456b094b6629a`

## Candidate-bound conformance evidence

The retained local evidence was generated outside the candidate tree:

- file SHA-256: `f4570ff6a574d02ccb63817454c87ee71cfe35707a35239e1d331c5d1383bbea`
- evidence digest: `c25f2b136e16600dac8ccbfe9266483edf04f6a4ec850a9c9b16010a15025183`
- result: 10/10 pass, failed/skipped 0, `productionEligible=false`, `productionRuntimeClaim=none`

The target runner was then executed as an independent CLI process against a non-loopback private-LAN delegated sandbox using real mutual TLS, detached Ed25519 handoff signature, expected-digest anti-rollback, trust-root verification, SPKI pinning, bearer authorization, and RFC 9530 body digests:

- target evidence SHA-256: `0ac75a3cff7a0e795a549b01ef45c97edb5c215207eaaa83fb86851aafc96c66`
- evidence digest: `5f441d36951f4a09ecb1521a9c0a7d24d9937d08e8c729e2aa252d7ad1923052`
- handoff binding digest: `098561f63762e2e6a82f51b198333b9f160f0c136e8f65b25f4b7db200b7a476`
- scenario-set digest: `f830fe271b7175cc6f20211a52da0cada5a6b3f8a9335ddf71bcfe52f786be5d`
- provider-issued sandbox run: `sandbox-2d5860a117ca6deb927c84a62af6b24b`
- result: 10/10 pass, failed/skipped 0, transient retry exactly once, cleanup pass, orphan/retained 0

This is a self-managed synthetic delegated non-production target, not a production school endpoint and not production runtime/apply evidence. No target credential, private key, endpoint, or evidence body is committed.

## V2 closed-bundle attestation

The v2 review bundle was created after the target run and outside the candidate tree. Its `PublicIntegrationTargetConformance` DAG node binds the candidate commit/tree, target evidence bytes, and exact scenario set. Manifest, evidence index, and the combined closed-bundle statement were independently Ed25519-signed and verified.

- release manifest SHA-256: `ef07932af7bffa8542af2ced95d9262ac6731e755b6e1046194610157cca6826`
- manifest signature bundle SHA-256: `b10d546d94596cf9a8637efa36d28e4fe09da9f164e56eb03dcc8dc9a68392b5`
- evidence index SHA-256: `df0ca994154325eacf933ddee522f81a05b713c7c6ac71a2da4e13bcdf36ebb2`
- index signature bundle SHA-256: `ddbf704e60e9376c9482667793977c2c712d9eed7454ed60637de764011f58e9`
- closed-bundle attestation SHA-256: `79e9af5a25f326696c40fab8d34c076dc711ad44c06dbfab52589b3c144ce108`
- closed-bundle signature SHA-256: `0a779717e794d3c47b128c4d94cf8115b1b22553a3ce3ba8828e2b459c2ba858`
- schema, semantic, target-evidence, signature, and closed-DAG validation: PASS
- boundary: `productionEligible=false`, `ociPublished=false`, production promotion claim `none`

## Ownership handoff

- Each future real producer owns its DeliveryRecord/JDBC sidecar in that producer's schema; Story 1.9 adds no central production table, scheduler, role, or consumer activation.
- Story 5.5 remains owner of public-task business apply and consumer watermark evidence.
- Story 2.7c remains final owner of full NFR-8 recovery/reconciliation acceptance.
- The requirements-traceability omission of Story 1.9 as an NFR-8 contributor is handed to the planning owner for an explicit add-or-retain decision; this implementation does not rewrite the final owner.
