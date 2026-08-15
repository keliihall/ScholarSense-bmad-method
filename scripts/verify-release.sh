#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONDONTWRITEBYTECODE=1

required=(ARTIFACT_URI SBOM_URI ATTESTATION_URI WEB_URI MANIFEST_URI SIGNATURE_URI INDEX_URI ORAS_PASSWORD)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "VERIFIER_INPUT_MISSING: $name" >&2
    exit 2
  fi
done
for uri in "$ARTIFACT_URI" "$SBOM_URI" "$ATTESTATION_URI" "$WEB_URI" "$MANIFEST_URI" "$SIGNATURE_URI" "$INDEX_URI"; do
  python3 -B "$ROOT_DIR/release/verifier.py" oci-uri "$uri"
done

require_input() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "VERIFIER_INPUT_MISSING: $name" >&2
    exit 2
  fi
}

WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/scholarsense-verifier-XXXXXX")"
cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

tool_profile="signing"
if [[ "${REQUIRE_CURRENT_RESCAN:-0}" == "1" ]]; then
  tool_profile="security"
fi
"$ROOT_DIR/scripts/install-release-tools.sh" "$WORK_DIR/tools" "$tool_profile"
"$WORK_DIR/tools/oras" login ghcr.io --username "${GITHUB_ACTOR:?}" --password-stdin <<< "$ORAS_PASSWORD"
for entry in \
  "artifact:$ARTIFACT_URI" "sbom:$SBOM_URI" "attestation:$ATTESTATION_URI" \
  "web:$WEB_URI" "manifest:$MANIFEST_URI" "signature:$SIGNATURE_URI" "index:$INDEX_URI"; do
  name="${entry%%:*}"
  uri="${entry#*:}"
  mkdir "$WORK_DIR/$name"
  "$WORK_DIR/tools/oras" pull "$uri" --output "$WORK_DIR/$name"
done

BUILD="$WORK_DIR/artifact/release-out/build"
SBOM="$WORK_DIR/sbom/release-out/sbom"
ATTESTATION="$WORK_DIR/attestation/release-out/attestation"
MANIFEST="$WORK_DIR/manifest/release-out/release-manifest.json"
SIGNATURE="$WORK_DIR/signature/release-out/manifest-signature/release-manifest.sigstore.json"
INDEX="$WORK_DIR/index/release-out/evidence-index.json"
ARTIFACT_IDENTITY="https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml@refs/heads/main"
MANIFEST_IDENTITY="https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/manifest-signing.yml@refs/heads/main"
ARTIFACT_SIGNER_WORKFLOW="keliihall/ScholarSense-bmad-method/.github/workflows/artifact-signing.yml"
MANIFEST_VERSION="$(jq -er '.version' "$MANIFEST")"
SOURCE_COMMIT="$(jq -er '.sourceCommit | select(test("^[0-9a-f]{40}$"))' "$MANIFEST")"
SOURCE_ARCHIVE_SHA256="$(jq -er '.sourceArchive.binarySha256 | select(test("^[0-9a-f]{64}$"))' "$MANIFEST")"
test "$(jq -er '.sourceArchive.uri' "$MANIFEST")" = "oci://$ARTIFACT_URI"
ISSUER="https://token.actions.githubusercontent.com"

python3 -B "$ROOT_DIR/scripts/check_sbom.py" "$ROOT_DIR" "$BUILD" "$SBOM"
if [[ "${REQUIRE_CURRENT_RESCAN:-0}" == "1" ]]; then
  for sbom in "$SBOM"/*.cdx.json; do
    scan="$WORK_DIR/current-$(basename "$sbom")"
    "$WORK_DIR/tools/trivy" sbom --scanners vuln --format cyclonedx --output "$scan" "$sbom"
    subject="$(jq -er '.metadata.component.hashes[] | select(.alg == "SHA-256") | .content' "$sbom")"
    python3 -B "$ROOT_DIR/release/verifier.py" current-vulnerability-policy "$scan" "$subject"
  done
fi
for subject in scholarsense-backend.jar scholarsense-frontend.tar.gz; do
  path="$BUILD/$subject"
  stem="${subject%%.*}"
  digest="$(sha256sum "$path" | cut -d' ' -f1)"
  python3 -B "$ROOT_DIR/release/verifier.py" attestation-query \
    "$ATTESTATION/${stem}.attestations.json" "$digest" \
    "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom" "https://spdx.dev/Document"
  python3 -B "$ROOT_DIR/release/verifier.py" github-attestations \
    "$path" "$digest" "keliihall/ScholarSense-bmad-method" \
    "$ARTIFACT_SIGNER_WORKFLOW" "$SOURCE_COMMIT" \
    "https://slsa.dev/provenance/v1" "https://cyclonedx.org/bom" "https://spdx.dev/Document"
  "$WORK_DIR/tools/cosign" verify-blob \
    --bundle "$ATTESTATION/${stem}.sigstore.json" \
    --certificate-identity "$ARTIFACT_IDENTITY" \
    --certificate-oidc-issuer "$ISSUER" \
    "$path"
done

python3 -B "$ROOT_DIR/scripts/check_formal_web_evidence.py" "$ROOT_DIR" "$WORK_DIR/web" "$BUILD"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" release "$MANIFEST" "$BUILD/build-manifest.json"
"$WORK_DIR/tools/cosign" verify-blob \
  --bundle "$SIGNATURE" \
  --certificate-identity "$MANIFEST_IDENTITY" \
  --certificate-oidc-issuer "$ISSUER" \
  "$MANIFEST"
python3 -B "$ROOT_DIR/scripts/check_release_manifests.py" index "$INDEX" "$MANIFEST"
python3 -B "$ROOT_DIR/release/verifier.py" extract-source \
  "$BUILD/release-source.tar.gz" "$WORK_DIR/source" "$SOURCE_ARCHIVE_SHA256"

target_uri() {
  local evidence_id="$1"
  local count uri raw digest
  count="$(jq -er --arg id "$evidence_id" '[.evidence[] | select(.id == $id)] | length' "$MANIFEST")"
  if [[ "$count" != "1" ]]; then
    echo "VERIFIER_TARGET_NODE_CARDINALITY_INVALID: $evidence_id" >&2
    exit 1
  fi
  uri="$(jq -er --arg id "$evidence_id" '.evidence[] | select(.id == $id) | .uri' "$MANIFEST")"
  digest="$(jq -er --arg id "$evidence_id" '.evidence[] | select(.id == $id) | .ociDigest' "$MANIFEST")"
  if [[ "$uri" != oci://* ]]; then
    echo "VERIFIER_TARGET_URI_INVALID: $evidence_id" >&2
    exit 1
  fi
  raw="${uri#oci://}"
  python3 -B "$ROOT_DIR/release/verifier.py" oci-uri "$raw" >/dev/null
  if [[ "$digest" != "${raw##*@}" ]]; then
    echo "VERIFIER_TARGET_OCI_DIGEST_MISMATCH: $evidence_id" >&2
    exit 1
  fi
  printf '%s\n' "$raw"
}

target_count="$(jq -er '[.evidence[] | select(.id == "PublicIntegrationTargetConformance" or .id == "DataCatalogTargetConformance")] | length' "$MANIFEST")"
pulled_material_arguments=(
  "$WORK_DIR/source" "$BUILD" "$SBOM" "$ATTESTATION" "$WORK_DIR/web"
  "$MANIFEST" "$SIGNATURE" "$INDEX"
  "$ARTIFACT_URI" "$SBOM_URI" "$ATTESTATION_URI" "$WEB_URI" "$MANIFEST_URI" "$SIGNATURE_URI"
)
case "$MANIFEST_VERSION" in
  RELEASE-MANIFEST-1.0.0)
    if [[ "$target_count" != "0" ]]; then
      echo "VERIFIER_V1_TARGET_NODE_FORBIDDEN" >&2
      exit 1
    fi
    ;;
  RELEASE-MANIFEST-2.0.0)
    if [[ "$target_count" != "1" ]]; then
      echo "VERIFIER_V2_TARGET_GRAPH_INVALID" >&2
      exit 1
    fi
    PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI="$(target_uri PublicIntegrationTargetConformance)"
    mkdir "$WORK_DIR/public-integration-target"
    "$WORK_DIR/tools/oras" pull "$PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI" \
      --output "$WORK_DIR/public-integration-target"
    test -f "$WORK_DIR/public-integration-target/public-integration-target-conformance-evidence-1.0.0.json"
    pulled_material_arguments+=(
      "$WORK_DIR/public-integration-target" "$PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI"
    )
    ;;
  RELEASE-MANIFEST-3.0.0|RELEASE-MANIFEST-4.0.0|RELEASE-MANIFEST-5.0.0|RELEASE-MANIFEST-6.0.0|RELEASE-MANIFEST-7.0.0|RELEASE-MANIFEST-8.0.0|RELEASE-MANIFEST-9.0.0|RELEASE-MANIFEST-10.0.0)
    if [[ "$target_count" != "2" ]]; then
      echo "VERIFIER_V3_TARGET_GRAPH_INVALID" >&2
      exit 1
    fi
    for name in \
      DATA_CATALOG_TARGET_TRUSTED_SIGNING_KEY \
      DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION \
      DATA_CATALOG_TARGET_EXPECTED_AUTHORITY \
      DATA_CATALOG_TARGET_EXPECTED_ENVIRONMENT; do
      require_input "$name"
    done
    if [[ ! "$DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION" =~ ^[1-9][0-9]*$ ]]; then
      echo "VERIFIER_DCC_TARGET_REVISION_FLOOR_INVALID" >&2
      exit 2
    fi
    PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI="$(target_uri PublicIntegrationTargetConformance)"
    DATA_CATALOG_TARGET_EVIDENCE_URI="$(target_uri DataCatalogTargetConformance)"
    mkdir "$WORK_DIR/public-integration-target" "$WORK_DIR/data-catalog-target"
    "$WORK_DIR/tools/oras" pull "$PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI" \
      --output "$WORK_DIR/public-integration-target"
    "$WORK_DIR/tools/oras" pull "$DATA_CATALOG_TARGET_EVIDENCE_URI" \
      --output "$WORK_DIR/data-catalog-target"
    test -f "$WORK_DIR/public-integration-target/public-integration-target-conformance-evidence-1.0.0.json"
    test -f "$WORK_DIR/data-catalog-target/data-catalog-target-conformance-evidence-1.0.0.json"
    pulled_material_arguments+=(
      "$WORK_DIR/public-integration-target" "$PUBLIC_INTEGRATION_TARGET_EVIDENCE_URI"
      "$WORK_DIR/data-catalog-target" "$DATA_CATALOG_TARGET_EVIDENCE_URI"
      "$DATA_CATALOG_TARGET_TRUSTED_SIGNING_KEY"
      "$DATA_CATALOG_TARGET_MINIMUM_HANDOFF_REVISION"
      "$DATA_CATALOG_TARGET_EXPECTED_AUTHORITY"
      "$DATA_CATALOG_TARGET_EXPECTED_ENVIRONMENT"
    )
    ;;
  *)
    echo "VERIFIER_RELEASE_MANIFEST_VERSION_INVALID: $MANIFEST_VERSION" >&2
    exit 1
    ;;
esac

python3 -B "$ROOT_DIR/release/verifier.py" pulled-material \
  "${pulled_material_arguments[@]}"

printf 'VERIFIED_MANIFEST_SHA256=%s\n' "$(sha256sum "$MANIFEST" | cut -d' ' -f1)"
printf 'VERIFIED_EVIDENCE_INDEX_SHA256=%s\n' "$(sha256sum "$INDEX" | cut -d' ' -f1)"
echo "verify-release: PASS"
