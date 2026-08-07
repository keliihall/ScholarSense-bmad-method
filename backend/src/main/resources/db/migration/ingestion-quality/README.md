# ingestion-quality migrations

This owner uses schema `ingestion_quality`, table prefix `iq_`, and independent online/relay roles.
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

RS-1.0.0 retains catalog, evidence, and local-audit facts for exactly three UTC calendar years and
idempotency claims for exactly 90 days. Expiry, schedule, owner, and legal-hold fields are protected
from workload mutation. A trusted-cutoff `SECURITY DEFINER` cleanup function is executable only by
the relay role; it removes eligible child facts before non-current catalog roots and leaves held or
unexpired records untouched. Source and dependency identifier reservations are permanent governance
tombstones: cleanup may rebind their first surviving catalog or set it to null, but never deletes them.

The global sequence and clean/upgrade inventory are discovered from the production migration tree;
this document is explanatory and is not runtime evidence.
