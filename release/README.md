# Release tooling boundary

This directory contains provider-neutral release generators, verifiers, and promotion adapters. Generated artifacts and evidence belong in the ignored `release-out/` directory; only schemas, policies, fixtures, and executable source are committed.

Local runs can prove deterministic contracts but cannot mint trusted provenance, keyless signatures, or protected-environment promotion records. Those claims are created only by the pinned GitHub Actions release workflow.

Promotion is provider-neutral at the service boundary and digest-only at the GHCR adapter. A digest-derived transfer tag is not authoritative: the create-only `releaseVersion + targetEnvironment` Git-ref ledger is the promotion decision, and every readback reconciles that ledger with the target digest and tag. Same-material retries replay; different material conflicts and cleans any newly created transfer tag.

Rollback is a separate protected workflow. It reads an immutable historical promotion record, re-fetches its artifact and every signed evidence URI, runs the current independent verifier, and replays that exact digest. It never calls the build entrypoint, rewrites a historical ledger, or mints replacement provenance/signatures.
