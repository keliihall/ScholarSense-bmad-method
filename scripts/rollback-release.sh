#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONDONTWRITEBYTECODE=1

if [[ $# -ne 3 ]]; then
  echo "usage: $0 HISTORICAL_RELEASE_VERSION HISTORICAL_ENVIRONMENT OUTPUT.json" >&2
  exit 2
fi
required=(ORAS_PASSWORD GITHUB_ACTOR GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_REPOSITORY)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "PROMOTION_INPUT_MISSING: $name" >&2
    exit 2
  fi
done

release_version="$1"
historical_environment="$2"
output="$3"
history="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/historical-promotion-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.json"
[[ ! -e "$history" ]] || {
  echo "PROMOTION_HISTORY_OUTPUT_ALREADY_EXISTS" >&2
  exit 1
}

python3 -B "$ROOT_DIR/scripts/read_promotion.py" \
  "$release_version" "$historical_environment" "$GITHUB_REPOSITORY" "$history"

export ARTIFACT_URI="$(jq -er '.targetArtifactUri' "$history")"
export SBOM_URI="$(jq -er '.sbomUri' "$history")"
export ATTESTATION_URI="$(jq -er '.attestationUri' "$history")"
export WEB_URI="$(jq -er '.webUri' "$history")"
export MANIFEST_URI="$(jq -er '.manifestUri' "$history")"
export MANIFEST_SHA256="$(jq -er '.manifestSha256' "$history")"
export SIGNATURE_URI="$(jq -er '.signatureUri' "$history")"
export INDEX_URI="$(jq -er '.evidenceIndexUri' "$history")"
export EVIDENCE_INDEX_SHA256="$(jq -er '.evidenceIndexSha256' "$history")"
export REQUIRE_CURRENT_RESCAN=1

# promote-release invokes scripts/verify-release.sh against every historical remote
# subject before the production CAS. It never invokes build-release or a package build.
"$ROOT_DIR/scripts/promote-release.sh" \
  "$release_version" production "$output" "$release_version" "$historical_environment"

echo "rollback-release: PASS"
