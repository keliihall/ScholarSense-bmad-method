#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
export PYTHONDONTWRITEBYTECODE=1

echo "[verify-core] verify the complete Maven resolution lock before lifecycle execution"
"$TOOLCHAIN" python3 "$ROOT_DIR/scripts/check_backend_lock.py" "$ROOT_DIR"

echo "[verify-core] clean backend build and contract tests"
"$TOOLCHAIN" "$ROOT_DIR/backend/mvnw" -f "$ROOT_DIR/backend/pom.xml" clean verify

echo "[verify-core] audit and standard-library regression"
(
  cd "$ROOT_DIR"
  "$TOOLCHAIN" python3 -m unittest discover -s _bmad/scripts/tests -p 'test_*.py'
  "$TOOLCHAIN" python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  "$TOOLCHAIN" python3 scripts/check_frontend_structure.py frontend
  "$TOOLCHAIN" python3 scripts/check_contract_seeds.py .
  "$TOOLCHAIN" python3 scripts/check_production_pollution.py .
  "$TOOLCHAIN" python3 scripts/normalized_manifest.py . --summary
  "$TOOLCHAIN" python3 scripts/check_release_source.py .
  "$TOOLCHAIN" python3 scripts/check_cisb.py .
  "$TOOLCHAIN" python3 scripts/check_workflow_security.py
  "$TOOLCHAIN" python3 scripts/check_release_workflows.py .
  "$TOOLCHAIN" python3 scripts/check_release_contracts.py .
)

echo "[verify-core] isolated frontend offline verification"
"$TOOLCHAIN" "$ROOT_DIR/scripts/verify_frontend.sh" --offline

echo "[verify-core] PASS"
