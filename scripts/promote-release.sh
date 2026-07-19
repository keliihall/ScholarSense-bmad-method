#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONDONTWRITEBYTECODE=1

if [[ $# -ne 3 && $# -ne 5 ]]; then
  echo "usage: $0 RELEASE_VERSION TARGET_ENVIRONMENT OUTPUT.json [ROLLBACK_VERSION ROLLBACK_ENVIRONMENT]" >&2
  exit 2
fi

release_version="$1"
target_environment="$2"
output="$3"
required=(
  ARTIFACT_URI SBOM_URI ATTESTATION_URI WEB_URI MANIFEST_URI SIGNATURE_URI INDEX_URI
  MANIFEST_SHA256 EVIDENCE_INDEX_SHA256 ORAS_PASSWORD GITHUB_ACTOR GITHUB_RUN_ID GITHUB_RUN_ATTEMPT
  GITHUB_REPOSITORY
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "PROMOTION_INPUT_MISSING: $name" >&2
    exit 2
  fi
done
[[ -x "$ROOT_DIR/scripts/verify-release.sh" ]] || {
  echo "PROMOTION_VERIFIER_ENTRYPOINT_MISSING: scripts/verify-release.sh" >&2
  exit 1
}
[[ "$output" = /* ]] || {
  echo "PROMOTION_OUTPUT_MUST_BE_ABSOLUTE" >&2
  exit 2
}
[[ ! -e "$output" ]] || {
  echo "PROMOTION_OUTPUT_ALREADY_EXISTS" >&2
  exit 1
}

case "$target_environment" in
  stage)
    from_environment="candidate"
    target_repository="ghcr.io/keliihall/scholarsense-release-stage"
    ;;
  production)
    from_environment="stage"
    target_repository="ghcr.io/keliihall/scholarsense-release-production"
    ;;
  *)
    echo "PROMOTION_TARGET_ENVIRONMENT_INVALID" >&2
    exit 2
    ;;
esac

if [[ "$target_environment" == "production" ]]; then
  stage_history="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/stage-promotion-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}.json"
  python3 -B "$ROOT_DIR/scripts/read_promotion.py" \
    "$release_version" stage "$GITHUB_REPOSITORY" "$stage_history"
  export ARTIFACT_URI="$(jq -er '.targetArtifactUri' "$stage_history")"
  export SBOM_URI="$(jq -er '.sbomUri' "$stage_history")"
  export ATTESTATION_URI="$(jq -er '.attestationUri' "$stage_history")"
  export WEB_URI="$(jq -er '.webUri' "$stage_history")"
  export MANIFEST_URI="$(jq -er '.manifestUri' "$stage_history")"
  export MANIFEST_SHA256="$(jq -er '.manifestSha256' "$stage_history")"
  export SIGNATURE_URI="$(jq -er '.signatureUri' "$stage_history")"
  export INDEX_URI="$(jq -er '.evidenceIndexUri' "$stage_history")"
  export EVIDENCE_INDEX_SHA256="$(jq -er '.evidenceIndexSha256' "$stage_history")"
fi

tools="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/scholarsense-promotion-tools-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
"$ROOT_DIR/scripts/install-release-tools.sh" "$tools" oci
"$tools/oras" login ghcr.io --username "$GITHUB_ACTOR" --password-stdin <<< "$ORAS_PASSWORD"
approval_history="$tools/approval-history.json"
environment_id="$(gh api "repos/$GITHUB_REPOSITORY/environments/$target_environment" --jq .id)"
gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/approvals" > "$approval_history"

arguments=(
  --release-version "$release_version"
  --from-environment "$from_environment"
  --target-environment "$target_environment"
  --artifact-uri "$ARTIFACT_URI"
  --sbom-uri "$SBOM_URI"
  --attestation-uri "$ATTESTATION_URI"
  --web-uri "$WEB_URI"
  --manifest-uri "$MANIFEST_URI"
  --signature-uri "$SIGNATURE_URI"
  --index-uri "$INDEX_URI"
  --manifest-sha256 "$MANIFEST_SHA256"
  --evidence-index-sha256 "$EVIDENCE_INDEX_SHA256"
  --actor "$GITHUB_ACTOR"
  --approval-history "$approval_history"
  --environment-id "$environment_id"
  --run-id "$GITHUB_RUN_ID"
  --run-attempt "$GITHUB_RUN_ATTEMPT"
  --repository "$GITHUB_REPOSITORY"
  --target-repository "$target_repository"
  --oras "$tools/oras"
  --output "$output"
)
if [[ $# -eq 5 ]]; then
  arguments+=(--rollback-version "$4" --rollback-environment "$5")
fi

python3 -B "$ROOT_DIR/release/promotion.py" "${arguments[@]}"
python3 -B "$ROOT_DIR/scripts/check_promotion.py" \
  "$release_version" "$target_environment" "$target_repository" "$tools/oras" "$GITHUB_REPOSITORY"

echo "promote-release-entrypoint: PASS"
