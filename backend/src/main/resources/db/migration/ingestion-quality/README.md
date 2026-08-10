# ingestion-quality migrations

This owner uses schema `ingestion_quality`, table prefix `iq_`, and independent online,
quality-worker, relay, consumer-registry-authority, and retention-executor workload roles. The
batch owner is a restricted `NOLOGIN` definer role and is never a workload identity. V000014 also
reassigns the callable V000011/V000013 security-definer routines and cleanup to that restricted
owner, grants it only their enumerated owner relations, and preserves each workload's closed
execute-only surface.
`V000010__ingestion-quality__data_source_catalog_v1.sql` is the first forward migration. It owns
catalog/source/dependency history, validation/evidence, the atomic current pointer, 90-day
idempotency state, module-local audit/outbox and RS-1.0.0 lifecycle fields. It creates no cross-schema
foreign key or query. Published catalog business/publication fields are immutable; `legal_hold` is
the sole owner-controlled lifecycle mutation. Stable identifier reservations are permanent.

`V000011__ingestion-quality__catalog_publication_cas_v1.sql` adds a monotonic current-pointer
version for conditional publication, atomic in-transaction idempotency claims, and the complete
17/17 target-evidence columns. Upgrade fails closed if legacy partial evidence rows exist; they must
be re-imported from the controlled verified report instead of being assigned fabricated defaults.
The successor removes raw online DML for source/dependency reservations, validation attempts,
publication evidence, catalog transitions, and the current pointer. Four least-privilege
`SECURITY DEFINER` functions atomically add a source or dependency, record a validation transition,
or publish a complete catalog. Published rows reject every mutation except an actual
owner-controlled `legal_hold` transition. Publication locks the catalog root, persists evidence,
verifies exactly 17 source contracts, 11 dependency bindings, and one passing evidence row per
source, then advances the current pointer last. Child inserts lock the same root and are rejected
after publication, so concurrent appends cannot escape the structural check. The current pointer
accepts only a different published catalog with an exact aggregate-version/publication-time match;
its CAS revision increases by one and `switched_at` must move forward.
Aggregate and pointer versions are bounded to the cross-language exact-integer range
`1..9007199254740991`.

`V000013__ingestion-quality__subject_window_recompute_v1.sql` adds the owner-local,
rebuildable historical-window projection and the durable subject-mapping correction
consumer. Historical windows use UTC half-open ranges, retain the explicit business
timezone and version/watermark lineage, and reject overlap with a GiST exclusion
constraint. The correction inbox enforces `source + event_id` idempotency and monotonic
aggregate watermarks, while gap/poison quarantine and reconciliation remain explicit.
Recompute jobs use the approved seven-field identity, leases with monotonic fencing
tokens, append-only results, and an outbox; completion rechecks `latest_actionable_at`
before it can publish an actionable result. The online role has no raw write privilege
on these tables and can mutate them only through fixed-search-path security-definer
functions. The relay role can only read and advance delivery metadata on the recompute
outbox.

`V000014__ingestion-quality__data_batch_quality_snapshot_v1.sql` adds the Story 2.3 batch,
normalized-fact, bounded measurement/operand and impact-scope staging, immutable
QualitySnapshot/metric, idempotency,
business outbox, deletion-result, and passed-only publication view. Business keys, record IDs,
watermark/impact hash material, and event payloads use strict UTF-8 `bytea`; the generic QSHM
canonicalizer therefore retains its approved Unicode and U+0000 semantics. Before production
persistence, private owner validators narrow watermark to exact lowercase `sourceId@YYYY-MM-DD`
with a real calendar date or lowercase `sha256:<64 hex>`, and narrow each impact scope to an exact
source-applicable embedded QMDP `metricId`. Free text, student plaintext, U+0000, noncanonical
digests, and cross-source metric IDs fail before the first staging write. Receiving accumulates
normalized-fact cardinality/schema in the batch row exactly once per actual insert and stages the
policy-bounded formula/operand and impact-scope sets. Command claims use the database statement
clock, expire after exactly 90 days, and are
atomically reclaimed after expiry; receive checks an existing scope before identity resolution.
Correction chains have one root per lineage and accept only a same-source, same-business-key,
strictly newer, time-monotonic direct successor of the locked current head. Seal validates the
source-specific QMDP cardinality without rescanning facts and freezes
both sets, so evaluation remains a short local transaction. Consequently no separate durable
quality-evaluation job, lease, fence, or checkpoint is required for this version. Seven fixed-search-
path worker functions own receive, fact/measurement/impact append, seal, atomic quality commit, and
publish. The quality commit copies only the frozen impact set in canonical UTF-8 order and writes
snapshot, metrics, assessed state, local audit/outbox, business outbox, and idempotency result
together; publish changes one batch row without rescanning facts, and the view exposes every fact at
the same commit boundary. Publish first validates a canonical, exact PIC published envelope bound to
the command, unique accepted evaluation causation, batch/version, trace, time, type, and schema;
invalid transport creates no idempotency, state, audit, or outbox write. Online and worker roles
receive no raw batch-table DML.
The compound commands validate the shared typed canonical local-audit record, its semantic digest,
and every aggregate/action/result/trace/request/time binding. Seal accepts only the exact approved
17-field QMDP/QSHM evidence and freezes its raw/canonical digest chain. A private owner table embeds
the complete digest-verified QMDP-1.0.0 source; measurement staging and evaluation use its exact
source projection, applicability, formula order, operand closure, rational comparison, HALF_UP
rounding, safe-integer bounds, and overall result. Evaluation independently rebuilds the complete
26-field QSHM material and hash, then validates the exact canonical PIC quality-assessed payload,
raw digest, causation/correlation, aggregate, schema, trace, and time bindings before its first
write. Late audit or business-outbox failures roll back the entire compound transaction.
Business-outbox rows enter only as pristine `pending` records. A due claim or expired-lease reclaim
advances `attempts` exactly once under a fixed five-minute database lease. The relay has read-only
table access and can change delivery state only through four fixed-search-path owner functions:
one database-clocked claim and three `(eventId, expectedAttempt)` compare-and-set finalizers.
Release computes a database-owned retry delay of at most one hour and the public error vocabulary is
limited to `BATCH_QUALITY_RELAY_UNAVAILABLE` and
`BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID`. Attempt eight is terminal (including an expired crashed
claim) under the private `BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED` state and attempt nine cannot be
created. Stale claimants return `false` without mutation. `delivered` and `failed` are terminal and
the event envelope is immutable.

The same successor creates a split production retention boundary. Conformance-only Task 0 anchors
never authorize deletion. The independent consumer-registry-authority role has only schema usage
and the three-argument strict UTF-8 authority ingest; it cannot read or mutate raw tables or execute
retention. Ingest accepts only the exact closed scope/member/digest/chronology binding and writes the
trusted production status itself. Structurally valid negative watermark, transport/inbox,
evidence-copy, and nullable-attestation observations remain available authority evidence rather
than being mislabeled as registry unavailable. The retention role cannot ingest or inspect raw
authority; it receives an opaque evidence ID and calls only a bounded candidate finder and the
seven-argument execution function `(executionId, resultEventId, snapshotId, immutableHash,
scopeDigest, authorityEvidenceId, traceId)`.

The candidate finder is non-claiming and deterministic: never-attempted due snapshots come first,
then least-recently-attempted blocked snapshots, with due time and snapshot ID as final tie-breakers.
It returns the latest same-scope blocked execution or the snapshot UUID for a root. Execution uses
that stable identity, serializes concurrent schedulers, requires version 1 or the direct blocked
successor, and makes completed terminal calls return `completed` with zero new result/outbox writes.
A shared ordered advisory-lock namespace prevents any authority-evidence ID from racing into an
alias with a deletion-result event ID.

PostgreSQL, not either workload, materializes the exact canonical
`QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0` event and stores identical bytes/digest in the owner result
and outbox. Missing or structurally invalid authority produces `CONSUMER_REGISTRY_UNAVAILABLE`;
trusted negative evidence produces the exact deduplicated Task 0 watermark/dependency/evidence-copy
blockers while keeping registry evidence available. Every valid available authority, including a
legal-hold or negative blocked decision, is consumed once with its execution. An eligible decision
atomically deletes snapshot, metrics, and impact-scope read models, records their actual counts and
digests under one transaction evidence digest, and appends the direct-successor result/outbox. The
invocation trace independently drives the envelope and traceparent. Event replay compares only the
canonical public event; the owner accepts no caller deletion-result payload and never emits the old
1.0/conformance result as production. This owner does not create an audit-operations
`DeletionReceipt`.

RS-1.0.0 retains catalog, evidence, and local-audit facts for exactly three UTC calendar years and
idempotency claims for exactly 90 days. Expiry, schedule, owner, and legal-hold fields are protected
from workload mutation. A trusted-cutoff `SECURITY DEFINER` cleanup function is executable only by
the retention-executor role; V000014 revokes this capability from the relay. It removes expired
batch-command claims, unconsumed expired authority evidence that no deletion result references, and
eligible child facts before non-current catalog roots; held, unexpired, consumed, or referenced
records remain untouched. Source
and dependency identifier reservations are permanent governance tombstones: cleanup may rebind
their first surviving catalog or set it to null, but never deletes them.

The global sequence and clean/upgrade inventory are discovered from the production migration tree;
this document is explanatory and is not runtime evidence.
