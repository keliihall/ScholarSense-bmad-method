# Module-owned migrations

This tree enforces migration ownership. `identity-access` owns executable session and module-local
audit migrations; `audit-operations` owns the immutable online ledger and its mutable technical
state. Inactive modules retain owner-only placeholders and must not receive empty audit tables
before their first audited behavior.

Future migrations must:

- live below the owner directory registered in `../module-ownership.csv`;
- use `V<six-digit-global-sequence>__<owner-module>__<snake_case_description>.sql`;
- use only the registered schema and table prefix;
- never reference another module's schema/table directly or create a cross-module foreign key;
- obtain a new global sequence number; sequence reuse is rejected even across owner directories.

The production inventory is derived from every `V*.sql` file under this directory. The ownership
suite and `scripts/run_audit_postgresql_tests.sh` both require one continuous global sequence,
discover successors automatically, and never maintain a second total-count or tail-version list.
Historical migration-specific digest and behavior assertions remain explicit.

The contract is enforced by the JDK suite and by `scripts/run_audit_postgresql_tests.sh` against
PostgreSQL 18.4. The complete discovered inventory is tested on both a clean path and an upgrade
containing a preserved legacy audit row. V000005 proves search projection backfill/watermark, cross-node atomic one-time CSRF
proof consumption, stable indexed pagination, retention evidence tables, least-privilege
read/executor roles, and the continued absence of ledger update/delete/truncate privileges.
V000006 proves authoritative-identity inbox encryption metadata, exact-once projection/checkpoint/
audit atomicity, persistent job and attempt state, lease fencing and expired-worker takeover,
reconciliation/SLO evidence, replay coverage, and separate least-privilege sync-worker/current-
reader roles. The audit conformance template proves the future module pattern without creating
production tables for inactive modules.
V000007 keeps V000006 immutable and adds responsibility-specific encrypted custody, append-only
facts, rebuildable current scope, exception history/current projection, an independently routed
daily reconciliation job/lease/run model, and post-commit SLO evidence/compensation. The sync worker
has no delete privilege; the current reader (and inherited online role) can only select the minimal
current scope, college exception projection, and reconciliation result.
V000008 adds access-invalidation lineage, delivery and real local-consumer fencing. V000009 adds
the authorization audit-context successor without rewriting historical rows. These statements are
design/test denominators only; they are not new runtime evidence.

V000010 and V000011 establish the ingestion-quality catalog and its CAS/publication successor.
V000012 establishes the subject-registry owner boundary, protected temporal mappings, exception
repair, idempotency, audit, and a producer-owned self-contained correction CloudEvent relay outbox.
V000013 adds ingestion-quality historical-window, request-level orchestration and fenced
mapping-recompute state. The latter two migrations communicate only through immutable event payloads
and identifiers: neither migration creates a cross-schema foreign key, query, grant, or shared
transaction.
V000014 adds ingestion-quality data batches, strict-byte fact and measurement staging, immutable
quality snapshots, passed-only atomic visibility, compound owner-local command functions, and a
separate consumer-registry-authority/retention-executor capability pair. Its bounded receiving-stage
operands keep final evaluation short; normalized-fact count/schema are accumulated on the batch
without seal/publish rescans, and
impact scopes are staged as exact source-applicable embedded QMDP metric IDs and frozen at seal.
Production watermark staging accepts only a lowercase source/date binding with a real calendar
date or an opaque lowercase SHA-256 value; the private owner validators reject free text, student
plaintext, U+0000, and cross-source metric IDs without changing generic QSHM Unicode semantics.
This version deliberately does not enable a durable quality-evaluation job. Database-clock
idempotency supports exact 90-day expiry and lazy reclamation, while locked direct-predecessor
validation makes correction lineages append-only and fork-free. A fifth, mutually exclusive
authority workload can only call the strict typed
three-argument evidence ingest; the retention workload can only find a fair bounded due candidate
and invoke the seven-argument owner materializer by IDs, scope, and an independent trace. The owner
validates and one-way consumes production authority, serializes one stable same-scope execution,
and emits only canonical `QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0` result/outbox bytes.
The same boundary validates the shared typed canonical local-audit envelope and digest, freezes the
exact approved 17-field QMDP/QSHM evidence, projects the embedded digest-verified QMDP policy into
the source-specific formula order, recomputes every metric and the complete QSHM content hash, and
accepts only the exact canonical PIC quality-assessed payload. Audit or business-outbox failures
therefore roll back snapshot, metrics, impacts, batch CAS, audit, outbox, and idempotency together.
The business relay keeps read-only outbox visibility and uses four owner routines for a fixed
five-minute claim plus attempt-fenced release/deliver/fail transitions; database-owned bounded
backoff and an eight-attempt terminal ceiling prevent caller-forged schedules, errors, or attempt
numbers.

V000015 adds the owner-local quality-eligibility projection and its strict ordered event consumer.
V000016 preserves that surface and adds the prior-state fuse-latch persistence successor: one
active source/dependency episode, one RecoveryTask/work-item identity per generation, immutable
transition evidence, and an atomic public-task intent. A seventh mutually exclusive workload role
advances only the independent task-delivery sidecar through lease-fenced owner routines; it has no
business-state DML or eligibility-consumer capability.
