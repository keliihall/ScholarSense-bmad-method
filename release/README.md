# Release tooling boundary

This directory contains provider-neutral release generators, verifiers, and promotion adapters. Generated artifacts and evidence belong in the ignored `release-out/` directory; only schemas, policies, fixtures, and executable source are committed.

Local runs can prove deterministic contracts but cannot mint trusted provenance, keyless signatures, or protected-environment promotion records. Those claims are created only by the pinned GitHub Actions release workflow.

Promotion is provider-neutral at the service boundary and digest-only at the GHCR adapter. A digest-derived transfer tag is not authoritative: the create-only `releaseVersion + targetEnvironment` Git-ref ledger is the promotion decision, and every readback reconciles that ledger with the target digest and tag. Same-material retries replay; different material conflicts and cleans any newly created transfer tag.

Rollback is a separate protected workflow. It reads an immutable historical promotion record, re-fetches its artifact and every signed evidence URI, runs the current independent verifier, and replays that exact digest. It never calls the build entrypoint, rewrites a historical ledger, or mints replacement provenance/signatures.

The root `scripts/verify.sh` is the sole complete local handoff command: it runs `verify-core` and then `build-release` in two clean roots, compares the final artifact set and BuildManifest, and deletes the local replay output. This is deterministic evidence only; it is not a trusted release dry-run.

The protected workflow order is fixed as build/CAS readback, SBOM and policy scan, artifact attestations/signatures, exact-runner formal Web evidence, one-time ReleaseManifest assembly, external manifest signature, EvidenceIndex, independent remote verification, then protected-environment promotion. Every inter-job value is a digest or immutable URI. A job that fails before publication cannot synthesize later evidence, and a promotion failure must not leave a ledger/tag mismatch.

Operators start `protected-release` only on protected `main` with an immutable semantic version and `stage` or `production`. They approve the exact pending protected-environment deployment only after the independent verifier is green, then retain the workflow run, all immutable output URIs, and the create-only Git-ref promotion ledger record. `protected-rollback` accepts a historical release version/environment, re-fetches that record, applies the current mandatory gates, and replays the recorded digest without rebuilding.
