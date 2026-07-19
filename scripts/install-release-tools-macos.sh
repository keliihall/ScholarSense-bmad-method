#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="${1:-}"
PROFILE="${2:-signing}"

if [[ -z "$DESTINATION" || ! "$PROFILE" =~ ^(oci|signing)$ ]]; then
  echo "usage: $0 DESTINATION {oci|signing}" >&2
  exit 2
fi
if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "RELEASE_TOOL_PLATFORM_UNSUPPORTED" >&2
  exit 1
fi
if [[ -e "$DESTINATION" ]]; then
  echo "RELEASE_TOOL_DESTINATION_EXISTS" >&2
  exit 1
fi

mkdir -p "$DESTINATION"
cleanup() {
  if [[ "${COMPLETE:-0}" != "1" ]]; then
    rm -rf -- "$DESTINATION"
  fi
}
trap cleanup EXIT INT TERM

source_uri() {
  PYTHONDONTWRITEBYTECODE=1 python3 -B - "$ROOT_DIR" "$1" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, str(Path(sys.argv[1]) / "scripts"))
from release_json import load_json
lock = load_json(Path(sys.argv[1]) / "contracts/release/toolchain-lock-1.0.0.json")
matches = [item for item in lock["tools"] if item.get("name") == sys.argv[2]]
if len(matches) != 1:
    raise SystemExit("RELEASE_TOOL_LOCK_ENTRY_INVALID")
print(matches[0]["sourceUri"])
PY
}

fetch() {
  local name="$1"
  local output="$2"
  curl --fail --location --proto '=https' --tlsv1.2 --output "$DESTINATION/$output" "$(source_uri "$name")"
  PYTHONDONTWRITEBYTECODE=1 python3 -B "$ROOT_DIR/scripts/check_release_tool.py" "$ROOT_DIR" "$name" "$DESTINATION/$output"
}

fetch "oras-macos-arm64-archive" "oras.tar.gz"
fetch "oras-1.3.3-checksums" "oras-checksums.txt"
grep -Fx 'f33fc12753c54172b0d0d19eaa0318d3f90fe9b094d96e8b259c881713c92e1c  oras_1.3.3_darwin_arm64.tar.gz' "$DESTINATION/oras-checksums.txt" >/dev/null
tar -xzf "$DESTINATION/oras.tar.gz" -C "$DESTINATION" oras
chmod 0755 "$DESTINATION/oras"

if [[ "$PROFILE" == "oci" ]]; then
  "$DESTINATION/oras" version
  COMPLETE=1
  exit 0
fi

fetch "cosign-macos-arm64" "cosign"
fetch "cosign-macos-arm64-bundle" "cosign.sigstore.json"
chmod 0755 "$DESTINATION/cosign"
"$DESTINATION/cosign" verify-blob \
  --bundle "$DESTINATION/cosign.sigstore.json" \
  --certificate-identity "keyless@projectsigstore.iam.gserviceaccount.com" \
  --certificate-oidc-issuer "https://accounts.google.com" \
  "$DESTINATION/cosign"
"$DESTINATION/oras" version
"$DESTINATION/cosign" version
COMPLETE=1
