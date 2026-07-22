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

"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V1" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V2" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V3" >/dev/null
"$PG_BIN/psql" -v ON_ERROR_STOP=1 -d scholarsense_audit_clean -f "$V4" >/dev/null

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

"$ROOT/_bmad/scripts/with_pab_toolchain.sh" mvn -q -f "$ROOT/backend/pom.xml" \
  -Dtest=IdentityAuditPostgreSqlIT,AuditLedgerPostgreSqlIT \
  -Dscholarsense.audit.pg.url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_clean" \
  -Dscholarsense.audit.pg.upgrade-url="jdbc:postgresql://127.0.0.1:$PORT/scholarsense_audit_upgrade" \
  -Dscholarsense.audit.pg.user="$USER_NAME" test

echo "audit-postgresql: PASS (PostgreSQL 18.4; clean + V000001/V000002/V000003/V000004 upgrade + concurrency/rollback/replay/privilege/tamper probes)"
