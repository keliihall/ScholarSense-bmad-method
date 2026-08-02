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
- `V000008` adds the access-invalidation boundary: nullable V2 lineage metadata on the
  responsibility current projection for approved dual-read, append-only invalidation facts,
  immutable authoritative-role binding history so corrections can still fan out to every old
  and new account/organization association,
  conditional lineage heads, a business outbox and delivery attempts, controlled consumer
  registration, the identity-owned current-scope inbox/apply/watermark, producer-observed
  applied facts, propagation/reconciliation views, and fenced expiry/impact jobs. The sync
  worker may append producer facts and mutate only recoverable heads, delivery state, jobs, and
  coordinator views; it has read-only access to consumer evidence and still cannot
  update/delete/truncate immutable invalidation facts. The local current-scope consumer uses a
  separate database login that is a member only of
  `scholarsense_identity_invalidation_consumer`; application startup verifies the same database and
  PostgreSQL cluster identity, distinct restricted login sessions (not two `SET ROLE` views of one
  login), exclusive role membership, and the exact producer/consumer capability matrix, including
  column-level grants. Each side's JDBC template and transaction manager must use the same
  DataSource so the proof and all atomic writes stay on one physical transaction. Configure the second
  connection with
  `scholarsense.identity-sync.access-invalidation-consumer.{jdbc-url,username,password}`; role
  verification is mandatory outside the test environment and the password must be nonblank.
  Responsibility V2 reconciliation and activation use a third, independent connection configured
  as `scholarsense.identity-sync.responsibility-v2-cutover.{jdbc-url,username,password}`. Its login
  must be a member only of `scholarsense_identity_responsibility_v2_cutover` and must differ from
  both the routine sync and invalidation-consumer logins; startup proves the session identities and
  refuses to disable this check outside tests. The responsibility repository, invalidation facts,
  expiry jobs, cutover audit, and transaction boundary all use this same DataSource so activation is
  atomic.
  Consumer-owned table constraints pin this release's
  login to `authorization-current-scope`, so it cannot impersonate planned consumers. Planned
  consumers remain `runtimeEvidenceClaim=none` until their owner stories activate and replay them.

Never store audit delivery state on a fact row, add a gap-sensitive producer sequence, or grant the
online role update/delete/truncate on the fact table. Future changes use a new global six-digit
migration version.
