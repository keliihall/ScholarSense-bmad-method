# identity-access migration owner

Only migrations owned by `identity-access` may be placed here.

- `V000001` establishes the session boundary and is immutable.
- `V000002` forward-expands the early audit table to `LocalAuditFact` v1, preserves legacy rows,
  creates the separate audit outbox, and grants the online role insert/select-only access to facts.
- `V000004` widens only the outbox delivery fencing counter to `bigint`, so indefinite replay cannot
  poison a claim batch at the former 32-bit boundary; it does not alter immutable fact bytes.
- `V000006` adds the authoritative identity and organization synchronization boundary: encrypted
  source inbox, source facts/current projections, checkpoint and replay state, persistent jobs and
  attempts, monotonic fenced leases, rejected-input diagnostics, reconciliation evidence,
  dual-read/new-write subject-binding history, actual read-back SLO evidence, and durable SLO
  compensation requests. Its sync worker and current reader are distinct least-privilege
  roles; the online application role inherits current-read access but never source payload access.
- `V000007` adds an independent responsibility source inbox/archive/fact chain, rebuildable
  student-to-recipient current projection, append-only exception history plus current college queue,
  and separate full-reconciliation job/attempt/lease/run/detail tables. It also stores responsibility
  read-back SLO evidence and durable compensation. `scholarsense_identity_sync_worker` may append
  facts/history and mutate only recoverable current/job state; `scholarsense_identity_current_reader`
  may select only the current responsibility, current exception, and reconciliation run projections.
  Neither role receives delete access, and the online role receives no direct new grant.

Never store audit delivery state on a fact row, add a gap-sensitive producer sequence, or grant the
online role update/delete/truncate on the fact table. Future changes use a new global six-digit
migration version.
