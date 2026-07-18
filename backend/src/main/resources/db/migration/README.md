# Module-owned migrations

This tree reserves migration ownership; Story 1.1b intentionally contains no SQL or business tables.

Future migrations must:

- live below the owner directory registered in `../module-ownership.csv`;
- use `V<six-digit-global-sequence>__<owner-module>__<snake_case_description>.sql`;
- use only the registered schema and table prefix;
- never reference another module's schema/table directly or create a cross-module foreign key;
- obtain a new global sequence number; sequence reuse is rejected even across owner directories.

Flyway or another migration runtime is not selected by this Story. The contract is enforced by the JDK test suite without a third-party architecture/migration library.

