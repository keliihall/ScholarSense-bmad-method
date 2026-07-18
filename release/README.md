# Release tooling boundary

This directory contains provider-neutral release generators, verifiers, and promotion adapters. Generated artifacts and evidence belong in the ignored `release-out/` directory; only schemas, policies, fixtures, and executable source are committed.

Local runs can prove deterministic contracts but cannot mint trusted provenance, keyless signatures, or protected-environment promotion records. Those claims are created only by the pinned GitHub Actions release workflow.
