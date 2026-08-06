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
