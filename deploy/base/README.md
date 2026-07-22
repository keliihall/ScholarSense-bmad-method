# Base runtime-role seed

This directory records a product-neutral contract for running the same backend JAR as either `web-api` or `worker`. It is not a container manifest, CI definition, production environment approval, or artifact-promotion record.

The web role enables identity access and the read-only audit availability gate, but never starts the audit collector. The worker role enables the audit ledger collector, identity-owned relay, verifier and alert delivery without starting an HTTP server; its probe is process liveness. Both capabilities require an environment-bound trusted-clock source reference. The worker additionally requires current environment-bound ingestion policy, hash profile, collector, verifier, structured-alert and Micrometer binding references; missing, cross-environment or stale versions fail before scheduling begins. All environment-specific values are injected externally and sensitive material is represented only by an environment-scoped reference.
