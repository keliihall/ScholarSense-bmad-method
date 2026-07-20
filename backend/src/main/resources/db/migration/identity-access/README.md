# identity-access migration owner

Only migrations owned by `identity-access` may be placed here.

- `V000001` establishes the session boundary and is immutable.
- `V000002` forward-expands the early audit table to `LocalAuditFact` v1, preserves legacy rows,
  creates the separate audit outbox, and grants the online role insert/select-only access to facts.

Never store audit delivery state on a fact row, add a gap-sensitive producer sequence, or grant the
online role update/delete/truncate on the fact table. Future changes use a new global six-digit
migration version.
