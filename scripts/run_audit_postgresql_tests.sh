#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_BIN="${SCHOLARSENSE_POSTGRES18_BIN:-/opt/homebrew/opt/postgresql@18/bin}"
PG_SHARE="${SCHOLARSENSE_POSTGRES18_SHARE:-$(cd "$PG_BIN/../share/postgresql" 2>/dev/null && pwd || true)}"

if [[ ! -x "$PG_BIN/postgres" || ! -x "$PG_BIN/initdb" || ! -f "$PG_SHARE/postgres.bki" ]]; then
  echo "audit-postgresql: PostgreSQL 18.4 binaries are required at $PG_BIN" >&2
  exit 1
fi
if [[ "$($PG_BIN/postgres --version)" != "postgres (PostgreSQL) 18.4"* ]]; then
  echo "audit-postgresql: exact PostgreSQL 18.4 is required" >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/scholarsense-audit-pg18.XXXXXX")"
DATA="$WORK/data"
SOCKET_DIR="$WORK/socket"
POSTGRES_LOG="$WORK/postgres.log"
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
USER_NAME="$(id -un)"

mkdir -m 0700 "$SOCKET_DIR"

cleanup() {
  if [[ -f "$DATA/postmaster.pid" ]]; then
    "$PG_BIN/pg_ctl" -D "$DATA" -m immediate -w stop >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

"$PG_BIN/initdb" -L "$PG_SHARE" -D "$DATA" --no-locale --encoding=UTF8 --auth=trust >/dev/null
if ! "$PG_BIN/pg_ctl" -D "$DATA" -l "$POSTGRES_LOG" \
  -o "-F -p $PORT -h 127.0.0.1 -k $SOCKET_DIR" -w start >/dev/null; then
  echo "audit-postgresql: server startup failed; PostgreSQL log follows" >&2
  if [[ -f "$POSTGRES_LOG" ]]; then
    sed 's/^/audit-postgresql: /' "$POSTGRES_LOG" >&2
  fi
  exit 1
fi

export PGHOST=127.0.0.1 PGPORT="$PORT" PGUSER="$USER_NAME"
"$PG_BIN/createdb" scholarsense_audit_clean
"$PG_BIN/createdb" scholarsense_audit_upgrade

V1="$ROOT/backend/src/main/resources/db/migration/identity-access/V000001__identity-access__session_boundary.sql"
V2="$ROOT/backend/src/main/resources/db/migration/identity-access/V000002__identity-access__local_audit_v1.sql"
V3="$ROOT/backend/src/main/resources/db/migration/audit-operations/V000003__audit-operations__immutable_ledger_v1.sql"
V4="$ROOT/backend/src/main/resources/db/migration/identity-access/V000004__identity-access__audit_delivery_attempts_bigint.sql"
V5="$ROOT/backend/src/main/resources/db/migration/audit-operations/V000005__audit-operations__authorized_search_retention_v1.sql"
V6="$ROOT/backend/src/main/resources/db/migration/identity-access/V000006__identity-access__authoritative_identity_org_v1.sql"
V7="$ROOT/backend/src/main/resources/db/migration/identity-access/V000007__identity-access__responsibility_reconciliation_v1.sql"
V8="$ROOT/backend/src/main/resources/db/migration/identity-access/V000008__identity-access__access_invalidation_v1.sql"
V9="$ROOT/backend/src/main/resources/db/migration/identity-access/V000009__identity-access__authorization_audit_context_v1.sql"

EXPECTED_MIGRATIONS="$(printf '%s\n' "$V1" "$V2" "$V3" "$V4" "$V5" "$V6" "$V7" "$V8" "$V9" \
  | LC_ALL=C sort)"
DISCOVERED_MIGRATIONS="$(find "$ROOT/backend/src/main/resources/db/migration" \
  -type f -name 'V*.sql' -print | LC_ALL=C sort)"
if [[ "$DISCOVERED_MIGRATIONS" != "$EXPECTED_MIGRATIONS" ]]; then
  echo "audit-postgresql: the registered migration set is not exactly V000001..V000009" >&2
  diff -u <(printf '%s\n' "$EXPECTED_MIGRATIONS") \
    <(printf '%s\n' "$DISCOVERED_MIGRATIONS") >&2 || true
  exit 1
fi

"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V1" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V2" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V3" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V4" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V5" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V6" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V7" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V8" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V9" >/dev/null

"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V1" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade <<'SQL' >/dev/null
insert into identity_access.ia_local_audit_fact (
  audit_id, actor_pseudonym, session_pseudonym, action, result, occurred_at,
  source_ip_pseudonym, trace_id, profile_version)
values (
  '019bf18e-6c00-7000-8000-000000000010', 'legacy-actor', 'legacy-session',
  'identity.session.login', 'accepted', '2026-07-20T01:00:00Z',
  'legacy-ip', 'trace-legacy-upgrade', 'ISP-1.0.0');
SQL
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V2" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V3" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V4" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V5" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V6" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V7" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V8" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_upgrade -f "$V9" >/dev/null

schema_inventory() {
  "$PG_BIN/psql" -X -v ON_ERROR_STOP=1 -At -d "$1" <<'SQL'
select inventory
from (
  select 'relation|' || concat_ws('|', n.nspname, c.relname, c.relkind, c.relpersistence,
      pg_get_userbyid(c.relowner)) inventory
  from pg_catalog.pg_class c
  join pg_catalog.pg_namespace n on n.oid=c.relnamespace
  where n.nspname in ('identity_access', 'audit_operations')
  union all
  select 'column|' || concat_ws('|', table_schema, table_name, ordinal_position::text,
      column_name, data_type, udt_schema, udt_name, coalesce(character_maximum_length::text, ''),
      coalesce(numeric_precision::text, ''), coalesce(numeric_scale::text, ''), is_nullable,
      coalesce(column_default, ''), is_identity, is_generated)
  from information_schema.columns
  where table_schema in ('identity_access', 'audit_operations')
  union all
  select 'constraint|' || concat_ws('|', n.nspname, rel.relname, con.conname, con.contype,
      pg_get_constraintdef(con.oid, true))
  from pg_catalog.pg_constraint con
  join pg_catalog.pg_class rel on rel.oid=con.conrelid
  join pg_catalog.pg_namespace n on n.oid=rel.relnamespace
  where n.nspname in ('identity_access', 'audit_operations')
  union all
  select 'index|' || concat_ws('|', schemaname, tablename, indexname, indexdef)
  from pg_catalog.pg_indexes
  where schemaname in ('identity_access', 'audit_operations')
  union all
  select 'trigger|' || concat_ws('|', n.nspname, rel.relname, trg.tgname,
      pg_get_triggerdef(trg.oid, true))
  from pg_catalog.pg_trigger trg
  join pg_catalog.pg_class rel on rel.oid=trg.tgrelid
  join pg_catalog.pg_namespace n on n.oid=rel.relnamespace
  where n.nspname in ('identity_access', 'audit_operations') and not trg.tgisinternal
  union all
  select 'function|' || concat_ws('|', n.nspname, proc.proname,
      pg_get_function_identity_arguments(proc.oid), pg_get_function_result(proc.oid),
      proc.prokind, proc.provolatile, proc.prosecdef::text, md5(pg_get_functiondef(proc.oid)))
  from pg_catalog.pg_proc proc
  join pg_catalog.pg_namespace n on n.oid=proc.pronamespace
  where n.nspname in ('identity_access', 'audit_operations')
  union all
  select 'grant|' || concat_ws('|', table_schema, table_name, grantee, privilege_type,
      is_grantable)
  from information_schema.role_table_grants
  where table_schema in ('identity_access', 'audit_operations')
) catalog
order by inventory;
SQL
}

CLEAN_SCHEMA="$WORK/clean-schema.inventory"
UPGRADE_SCHEMA="$WORK/upgrade-schema.inventory"
schema_inventory scholarsense_audit_clean >"$CLEAN_SCHEMA"
schema_inventory scholarsense_audit_upgrade >"$UPGRADE_SCHEMA"
if ! cmp -s "$CLEAN_SCHEMA" "$UPGRADE_SCHEMA"; then
  echo "audit-postgresql: clean and upgrade-from-prior schemas drifted" >&2
  diff -u "$CLEAN_SCHEMA" "$UPGRADE_SCHEMA" >&2 || true
  exit 1
fi
SCHEMA_FINGERPRINT="$(shasum -a 256 "$CLEAN_SCHEMA" | awk '{print $1}')"
SCHEMA_SUMMARY="$($PG_BIN/psql -X -v ON_ERROR_STOP=1 -At -d scholarsense_audit_clean -c "
  select concat_ws('|',
    (select count(*) from information_schema.tables
      where table_schema in ('identity_access','audit_operations') and table_type='BASE TABLE'),
    (select count(*) from information_schema.columns
      where table_schema in ('identity_access','audit_operations')),
    (select count(*) from pg_catalog.pg_constraint c join pg_catalog.pg_namespace n
      on n.oid=c.connamespace where n.nspname in ('identity_access','audit_operations')),
    (select count(*) from pg_catalog.pg_indexes
      where schemaname in ('identity_access','audit_operations')),
    (select count(*) from pg_catalog.pg_trigger t join pg_catalog.pg_class r on r.oid=t.tgrelid
      join pg_catalog.pg_namespace n on n.oid=r.relnamespace
      where n.nspname in ('identity_access','audit_operations') and not t.tgisinternal),
    (select count(*) from pg_catalog.pg_proc p join pg_catalog.pg_namespace n
      on n.oid=p.pronamespace where n.nspname in ('identity_access','audit_operations')))
  ")"
echo "audit-postgresql: schema fingerprint=$SCHEMA_FINGERPRINT summary=tables|columns|constraints|indexes|triggers|functions=$SCHEMA_SUMMARY (clean=upgrade; migration=V000009 authorization-audit-context; historical rows remain null)"

for database in scholarsense_audit_clean scholarsense_audit_upgrade; do
  attempts_type=$("$PG_BIN/psql" -At -d "$database" -c "
    select data_type from information_schema.columns
    where table_schema='identity_access'
      and table_name='ia_local_audit_outbox'
      and column_name='attempts'")
  if [[ "$attempts_type" != "bigint" ]]; then
    echo "audit-postgresql: attempts fencing type mismatch in $database" >&2
    exit 1
  fi
done

POSTGRES_TESTS="IdentityAuditPostgreSqlIT,IdentityAuthorityPostgreSqlIT,ResponsibilityAuthorityPostgreSqlIT,AccessInvalidationPostgreSqlIT,AuditLedgerPostgreSqlIT,PublicIntegrationPostgreSqlIT"
if [[ -n "${IDENTITY_SANDBOX_ENDPOINT:-}" ]]; then
  POSTGRES_TESTS="$POSTGRES_TESTS,IdentityAuthoritySandboxIT#sameTraceRunsThroughWorkerPostgreSqlAndCurrentAuthorizationReadBack"
fi

if [[ -n "${IDENTITY_SANDBOX_ENDPOINT:-}" ]]; then
  "$ROOT/_bmad/scripts/with_pab_toolchain.sh" mvn -q -f "$ROOT/backend/pom.xml" \
    "-Dtest=$POSTGRES_TESTS" \
    -Dscholarsense.audit.pg.url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_clean" \
    -Dscholarsense.audit.pg.upgrade-url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_upgrade" \
    -Dscholarsense.audit.pg.user="$USER_NAME" \
    "-Didentity.sandbox.endpoint=$IDENTITY_SANDBOX_ENDPOINT" \
    "-Didentity.sandbox.token=$IDENTITY_SANDBOX_TOKEN" \
    "-Didentity.sandbox.signatureKey=$IDENTITY_SANDBOX_SIGNATURE_KEY" test
else
  "$ROOT/_bmad/scripts/with_pab_toolchain.sh" mvn -q -f "$ROOT/backend/pom.xml" \
    "-Dtest=$POSTGRES_TESTS" \
    -Dscholarsense.audit.pg.url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_clean" \
    -Dscholarsense.audit.pg.upgrade-url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_upgrade" \
    -Dscholarsense.audit.pg.user="$USER_NAME" test
fi

echo "audit-postgresql: PASS (PostgreSQL 18.4; clean + V000001..V000009 upgrade + authorization successor persistence + identity/responsibility invalidation fencing/atomicity/SLO/privilege + PIC test-scope queue/current/mapping/fence/retention + audit projection/concurrency/rollback/replay/tamper probes)"
