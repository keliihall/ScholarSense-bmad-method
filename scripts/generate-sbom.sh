#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT_DIR/_bmad/scripts/with_pab_toolchain.sh"
ARTIFACTS="${1:-}"
OUTPUT="${2:-}"
TRIVY_BIN="${3:-}"
TRIVY_ARCHIVE="${4:-}"
TRIVY_BUNDLE="${5:-}"
TRIVY_CACHE="${6:-}"
TRIVY_TOOL_NAME="${7:-}"
COSIGN_BIN="${8:-}"
COSIGN_TOOL_NAME="${9:-}"

if [[ -z "$ARTIFACTS" || -z "$OUTPUT" || -z "$TRIVY_BIN" || -z "$TRIVY_ARCHIVE" || -z "$TRIVY_BUNDLE" || -z "$TRIVY_CACHE" || -z "$TRIVY_TOOL_NAME" || -z "$COSIGN_BIN" || -z "$COSIGN_TOOL_NAME" ]]; then
  echo "usage: $0 ARTIFACTS OUTPUT TRIVY_BIN TRIVY_ARCHIVE TRIVY_BUNDLE TRIVY_CACHE TRIVY_TOOL_NAME COSIGN_BIN COSIGN_TOOL_NAME" >&2
  exit 2
fi

export PYTHONDONTWRITEBYTECODE=1
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarsense-sbom-XXXXXX")"
cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK_DIR/frontend" "$WORK_DIR/docker-config"
"$TOOLCHAIN" python3 -B "$ROOT_DIR/scripts/check_release_tool.py" "$ROOT_DIR" "$COSIGN_TOOL_NAME" "$COSIGN_BIN"
cp "$ROOT_DIR/frontend/package.json" "$ROOT_DIR/frontend/package-lock.json" "$ROOT_DIR/frontend/.npmrc" "$WORK_DIR/frontend/"
(
  cd "$WORK_DIR/frontend"
  "$TOOLCHAIN" npm ci --offline --ignore-scripts
  "$TOOLCHAIN" npm ls --all --json > "$WORK_DIR/npm-tree.json"
)

"$TOOLCHAIN" python3 -B "$ROOT_DIR/release/generate_sbom.py" \
  --root "$ROOT_DIR" \
  --artifacts "$ARTIFACTS" \
  --output "$OUTPUT" \
  --trivy "$TRIVY_BIN" \
  --trivy-archive "$TRIVY_ARCHIVE" \
  --trivy-bundle "$TRIVY_BUNDLE" \
  --cache-dir "$TRIVY_CACHE" \
  --tool-name "$TRIVY_TOOL_NAME" \
  --docker-config "$WORK_DIR/docker-config" \
  --npm-tree "$WORK_DIR/npm-tree.json" \
  --cosign "$COSIGN_BIN" \
  --cosign-tool-name "$COSIGN_TOOL_NAME"
