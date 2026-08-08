# Base runtime-role seed

This directory records a product-neutral contract for running the same backend JAR as either `web-api` or `worker`. It is not a container manifest, CI definition, production environment approval, or artifact-promotion record.

The web role enables identity access and the read-only audit availability gate, but never starts the audit collector. The role and its ingestion-quality capability profile are eligible only in `test`, `stage`, and `prod`; `dev` must not activate them. It requires `SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_PATH`, an absolute, protected, regular, non-symlink secret-manager-mounted HMAC key, plus the explicit `SCHOLARSENSE_IDENTITY_AUDIT_TOKEN_KEY_VERSION`; missing, invalid, or insecure material fails startup closed. The web and identity-sync mounts must resolve the same controlled audit-token key version during rotation. The deployment also owns one integer handoff floor in the cross-language-safe range `1..9007199254740991` that may only increase. CI/release receives it as `DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION`, while runtime receives the same value as `SCHOLARSENSE_INGESTION_QUALITY_MINIMUM_HANDOFF_REVISION`; either input outside that range, or a release or target report below the shared floor, fails closed. The worker role enables the audit ledger collector, identity-owned relay, verifier and alert delivery without starting an HTTP server; its probe is process liveness. Both capabilities require an environment-bound trusted-clock source reference. The worker additionally requires current environment-bound ingestion policy, hash profile, collector, verifier, structured-alert and Micrometer binding references; missing, cross-environment or stale versions fail before scheduling begins. The non-production identity-sync worker also requires `SCHOLARSENSE_IDENTITY_SYNC_SECURITY_DIRECTORY`, an absolute, non-symlink secret-manager mount whose protected files and manifest are validated against the selected identity-authority profile before scheduling starts. When responsibility sync is enabled, the same protected mount must additionally provide the responsibility-only `responsibility-workload-token`, `responsibility-signature-hmac.key`, and `responsibility-envelope-kek.key`; these are bound to `SCHOLARSENSE_RESPONSIBILITY_AUTHORITY_PROFILE_REF` and are never reused by the identity-org projection. The deployment injects two further database bindings for the local invalidation consumer and the V2 cutover executor; their logins are distinct from the routine sync login and are exclusive members of their respective NOLOGIN group roles. JDBC passwords are injected secrets, never committed values. All environment-specific values are injected externally and sensitive material is represented only by an environment-scoped reference or a protected mounted file.

The ingestion-quality capability adds the controlled requirements in
`ingestion-quality-runtime-1.0.0.json`. Its web runtime loads digest-bound SOURCE/DEPENDENCY
owner bindings from an absolute deployment mount. Its audit outbox relay runs only in the
existing non-HTTP audit worker. Both paths open the actual DataSource before their database
adapters become available; production requires pgJDBC `sslmode=verify-full`,
`channelBinding=require`, server-reported PostgreSQL `server_version_num=180004`, and
`session_user=current_user` equal to the configured workload LOGIN. The distinct web and worker
logins receive `GRANT ... WITH INHERIT TRUE, SET FALSE`, giving them inherited, exclusive
membership without `SET ROLE` in the NOLOGIN
`scholarsense_ingestion_quality_online` and `scholarsense_ingestion_quality_relay` group roles,
respectively. Startup rejects elevated login/group attributes, cross-membership, or any drift from
the exact effective table/column privilege matrix. Missing bindings, ambiguous object owners, TLS
downgrade, identity mismatch, or role drift fail closed. Before accepting a frozen target report,
the web runtime also reads
`SCHOLARSENSE_INGESTION_QUALITY_TARGET_SIGNING_KEY_PATH` from an absolute, protected, regular,
non-symlink deployment mount and verifies each source observation's exact HMAC-SHA256 authority
signature. A missing, short, insecure, linked, or mismatched key fails startup closed. The binding
mount, signing key, and database credentials are deployment inputs and are never committed.

Subject mapping exceptions and recompute jobs are derived authorization objects, never new owner
binding rows: both resolve through the persisted source ID into the frozen SOURCE binding. R6 gets
the `owned-source` anchor; a job additionally carries `technical-object` for the approved R7 read
path. Their authorization token is the lowercase SHA-256 digest of that source ID, not an
exception/job UUID.

The subject-registry capability is separately versioned in
`subject-registry-runtime-1.0.0.json`. The web role opens distinct online and relay DataSources;
each startup gate verifies PostgreSQL 18.4, production TLS/channel binding, exact connection
identity, exclusive inherited NOLOGIN role membership and the complete effective
table/column/function privilege matrix. The relay confirms the producer outbox only after the
ingestion-quality consumer transaction commits. The same role runs the database-fenced
subject-window worker; 60-second leases and monotonic fencing tokens make replica overlap safe.
Identifier AES-GCM and HMAC material must be delivered from the approved school KMS into protected,
non-symlink mounts. The profile remains `deployment-input-required`: local fixtures do not claim the
production KMS runtime evidence required for a production-complete declaration.
