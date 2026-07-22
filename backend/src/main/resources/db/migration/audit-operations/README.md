# audit-operations migration owner

Only migrations owned by `audit-operations` may be placed here.

`V000003` creates the `ao_` online tamper-evident ledger, its singleton head, ingestion receipts,
verification runs, immutable findings/dispositions, privacy-safe alert outbox and availability
observations. Ledger sequence comes only from a transactionally locked head row; PostgreSQL
sequence/identity is forbidden because rollback must not consume a number.

The writer receives only ledger `SELECT/INSERT` plus the minimum head/receipt/finding/alert columns.
It never receives ledger `UPDATE/DELETE/TRUNCATE`. Verifier, alert-delivery and online roles remain
separate. The verifier may read finding dispositions but cannot insert them, so verification cannot
resolve its own permanent findings. `audit_operations` must not reference the `identity_access` schema; producer backlog and
relay state cross the module boundary only through `auditoperations.api`.

The ledger is an online detection control, not WORM storage. Owner-level tamper is intentionally
injected by the PostgreSQL verification suite and must be detected by a full-chain verifier. Never
repair a mismatch by updating hashes, filling sequence gaps or deleting findings. Record an
independent disposition, resolve the root cause, then run genesis-to-head verification before
allowing the two-observation healthy recovery gate to reopen high-risk operations.
