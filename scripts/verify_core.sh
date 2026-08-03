#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
export PYTHONDONTWRITEBYTECODE=1

MODE="${1:-}"
if [[ -n "$MODE" && "$MODE" != "--review" ]]; then
  echo "usage: $0 [--review]" >&2
  exit 2
fi
echo "[verify-core] verify the complete Maven resolution lock before lifecycle execution"
"$TOOLCHAIN" python3 -B "$ROOT_DIR/scripts/check_backend_lock.py" "$ROOT_DIR"

echo "[verify-core] clean backend build and contract tests"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" clean verify

echo "[verify-core] production backend plaintext-canary scan"
"$TOOLCHAIN" python3 -B "$ROOT_DIR/scripts/scan_privacy_canaries.py" \
  "$ROOT_DIR/backend/target/classes" \
  "$ROOT_DIR/backend/target/scholarsense-backend.jar"

echo "[verify-core] audit and standard-library regression"
(
  cd "$ROOT_DIR"
  "$TOOLCHAIN" python3 -B -m unittest discover -s _bmad/scripts/tests -p 'test_*.py'
  "$TOOLCHAIN" python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
  "$TOOLCHAIN" python3 -B scripts/check_frontend_structure.py frontend
  "$TOOLCHAIN" python3 -B scripts/check_contract_seeds.py .
  "$TOOLCHAIN" python3 -B scripts/check_identity_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_identity_authority_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_responsibility_authority_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_access_invalidation_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_authorization_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_field_projection_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_audit_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_audit_ledger_contracts.py .
  "$TOOLCHAIN" python3 -B scripts/check_audit_retention_contracts.py .
  if [[ "$MODE" == "--review" ]]; then
    "$TOOLCHAIN" python3 -B scripts/check_identity_runtime_evidence.py . --review
    "$TOOLCHAIN" python3 -B scripts/check_host_deployment.py . --review
  else
    "$TOOLCHAIN" python3 -B scripts/check_identity_runtime_evidence.py .
    "$TOOLCHAIN" python3 -B scripts/check_host_deployment.py .
  fi
  "$TOOLCHAIN" python3 -B scripts/check_production_pollution.py .
  "$TOOLCHAIN" python3 -B scripts/normalized_manifest.py . --summary
  "$TOOLCHAIN" python3 -B scripts/check_release_source.py .
  "$TOOLCHAIN" python3 -B scripts/check_cisb.py .
  "$TOOLCHAIN" python3 -B scripts/check_workflow_security.py
  "$TOOLCHAIN" python3 -B scripts/check_release_workflows.py .
  "$TOOLCHAIN" python3 -B scripts/check_release_contracts.py .
)

echo "[verify-core] controlled identity-authority sandbox provider/consumer evidence"
SANDBOX_EVIDENCE_FILE="$(mktemp "${TMPDIR:-/tmp}/scholarsense-identity-sandbox-evidence-XXXXXX")"
cleanup_sandbox_evidence() {
  rm -f -- "$SANDBOX_EVIDENCE_FILE"
}
trap cleanup_sandbox_evidence EXIT INT TERM
"$TOOLCHAIN" python3 -B "$ROOT_DIR/scripts/run_identity_authority_sandbox_tests.py" \
  --evidence "$SANDBOX_EVIDENCE_FILE"
cleanup_sandbox_evidence
trap - EXIT INT TERM

echo "[verify-core] controlled responsibility-authority sandbox evidence"
RESPONSIBILITY_SANDBOX_EVIDENCE_FILE="$(mktemp "${TMPDIR:-/tmp}/scholarsense-responsibility-sandbox-evidence-XXXXXX")"
cleanup_responsibility_sandbox_evidence() {
  rm -f -- "$RESPONSIBILITY_SANDBOX_EVIDENCE_FILE"
}
trap cleanup_responsibility_sandbox_evidence EXIT INT TERM
"$TOOLCHAIN" python3 -B \
  "$ROOT_DIR/scripts/run_responsibility_authority_sandbox_tests.py" \
  --evidence "$RESPONSIBILITY_SANDBOX_EVIDENCE_FILE"
cleanup_responsibility_sandbox_evidence
trap - EXIT INT TERM

echo "[verify-core] controlled access-invalidation convergence evidence"
ACCESS_INVALIDATION_EVIDENCE_FILE="$(mktemp "${TMPDIR:-/tmp}/scholarsense-access-invalidation-evidence-XXXXXX")"
cleanup_access_invalidation_evidence() {
  rm -f -- "$ACCESS_INVALIDATION_EVIDENCE_FILE"
}
trap cleanup_access_invalidation_evidence EXIT INT TERM
"$TOOLCHAIN" python3 -B \
  "$ROOT_DIR/scripts/run_access_invalidation_sandbox_tests.py" \
  --evidence "$ACCESS_INVALIDATION_EVIDENCE_FILE"
cleanup_access_invalidation_evidence
trap - EXIT INT TERM

echo "[verify-core] PostgreSQL 18.4 evidence completed by the sandbox E2E runner"

echo "[verify-core] isolated frontend offline verification"
"$TOOLCHAIN" "$ROOT_DIR/scripts/verify_frontend.sh" --offline

echo "[verify-core] PASS"
