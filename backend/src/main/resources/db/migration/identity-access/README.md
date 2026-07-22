# identity-access migration owner

Only migrations owned by `identity-access` may be placed here.

- `V000001` establishes the session boundary and is immutable.
- `V000002` forward-expands the early audit table to `LocalAuditFact` v1, preserves legacy rows,
  creates the separate audit outbox, and grants the online role insert/select-only access to facts.
- `V000004` widens only the outbox delivery fencing counter to `bigint`, so indefinite replay cannot
  poison a claim batch at the former 32-bit boundary; it does not alter immutable fact bytes.

Never store audit delivery state on a fact row, add a gap-sensitive producer sequence, or grant the
online role update/delete/truncate on the fact table. Future changes use a new global six-digit
migration version.
