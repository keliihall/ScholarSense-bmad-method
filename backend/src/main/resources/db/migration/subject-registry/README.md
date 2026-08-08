# subject-registry migration owner

Only migrations owned by `subject-registry` may be placed here.

`V000012__subject-registry__subject_mapping_v1.sql` establishes the production
owner boundary for irreversible `StudentRef` reservations, protected identifiers,
non-overlapping temporal mappings, mapping exceptions, append-only correction
lineage, recompute-request outbox, 90-day repair idempotency, and local audit. Both
source-native and approved official identifiers are stored only as key-versioned
ciphertext plus environment-bound deterministic tokens; neither plaintext value is
persisted. Four fixed-search-path functions reserve StudentRefs, append mappings,
record exceptions, and atomically repair an exception with its correction event,
idempotency result, audit fact, and a self-contained ≤64 KiB CloudEvent outbox fact. A minimal
security-definer request lookup exposes only request/source/time/trace during relay lag.

The online group role has read access only to the minimum service state and can
mutate state solely through `SECURITY DEFINER` functions with
`search_path=pg_catalog`. It has no direct INSERT, UPDATE, DELETE, or TRUNCATE
privilege. The relay role can read immutable event/audit payloads and update only their delivery
metadata; it cannot insert, delete, or modify business payload columns. No migration
in this directory may query, reference, grant, or mutate another module schema.
