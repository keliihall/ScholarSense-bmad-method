#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
export PYTHONDONTWRITEBYTECODE=1

REPLAY_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/scholarsense-local-release-XXXXXX")"
cleanup() {
  rm -rf -- "$REPLAY_ROOT"
}
trap cleanup EXIT INT TERM

"$ROOT_DIR/scripts/bootstrap.sh"

"$ROOT_DIR/scripts/verify_core.sh"

echo "[verify] clean two-attempt release replay"
"$ROOT_DIR/scripts/build-release.sh" "$REPLAY_ROOT/release"

echo "[verify] PASS"
