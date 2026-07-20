# Module-owned migrations

This tree enforces migration ownership. `identity-access` now owns executable session and
module-local audit migrations; inactive modules retain owner-only placeholders and must not receive
empty audit tables before their first audited behavior.

Future migrations must:

- live below the owner directory registered in `../module-ownership.csv`;
- use `V<six-digit-global-sequence>__<owner-module>__<snake_case_description>.sql`;
- use only the registered schema and table prefix;
- never reference another module's schema/table directly or create a cross-module foreign key;
- obtain a new global sequence number; sequence reuse is rejected even across owner directories.

The contract is enforced by the JDK suite and by `scripts/run_audit_postgresql_tests.sh` against
PostgreSQL 18.4. The audit conformance template proves the future module pattern without creating
production tables for inactive modules.
