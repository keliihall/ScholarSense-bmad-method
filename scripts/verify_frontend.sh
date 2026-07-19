#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"
EXPECTED_REGISTRY="https://registry.npmjs.org/"

if [[ "$MODE" != "--prepare" && "$MODE" != "--offline" ]]; then
  echo "usage: $0 --prepare|--offline" >&2
  exit 2
fi

[[ "$(node --version)" == "v24.18.0" ]] || {
  echo "frontend verification requires Node v24.18.0" >&2
  exit 1
}
[[ "$(npm --version)" == "11.16.0" ]] || {
  echo "frontend verification requires npm 11.16.0" >&2
  exit 1
}

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarsense-frontend-XXXXXX")"
cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

if [[ "$MODE" == "--prepare" ]]; then
  mkdir -p "$WORK_DIR/prepare/frontend"
  cp -R "$ROOT_DIR/frontend/." "$WORK_DIR/prepare/frontend/"
  cd "$WORK_DIR/prepare/frontend"
  registry="$(npm config get registry)"
  [[ "$registry" == "$EXPECTED_REGISTRY" ]] || {
    echo "unexpected npm registry: $registry" >&2
    exit 1
  }
  echo "[frontend] prewarm exact lock and fixed Playwright Chromium"
  npm ci --prefer-offline --ignore-scripts
  ./node_modules/.bin/playwright install chromium
  echo "[frontend] prepare PASS"
  exit 0
fi

python3 "$ROOT_DIR/scripts/check_frontend_baseline.py" "$ROOT_DIR"
EXPECTED_LOCK_SHA="$(shasum -a 256 "$ROOT_DIR/frontend/package-lock.json" | awk '{print $1}')"

run_replay() {
  local replay="$1"
  local replay_root="$WORK_DIR/$replay"
  local summary="$WORK_DIR/$replay.summary"

  mkdir -p "$replay_root/frontend" "$replay_root/backend/src" "$replay_root/docs/architecture"
  cp -R "$ROOT_DIR/frontend/." "$replay_root/frontend/"
  cp -R "$ROOT_DIR/backend/src/main" "$replay_root/backend/src/main"
  cp -R "$ROOT_DIR/contracts" "$replay_root/contracts"
  cp -R "$ROOT_DIR/deploy" "$replay_root/deploy"
  cp -R "$ROOT_DIR/.github" "$replay_root/.github"
  cp -R "$ROOT_DIR/release" "$replay_root/release"
  cp -R "$ROOT_DIR/scripts" "$replay_root/scripts"
  cp -R "$ROOT_DIR/docs/architecture/adr" "$replay_root/docs/architecture/adr"
  cd "$replay_root/frontend"

  local registry
  registry="$(npm config get registry)"
  [[ "$registry" == "$EXPECTED_REGISTRY" ]] || {
    echo "unexpected npm registry in $replay: $registry" >&2
    exit 1
  }

  python3 "$ROOT_DIR/scripts/check_frontend_baseline.py" "$replay_root"
  python3 "$ROOT_DIR/scripts/check_frontend_structure.py" "$replay_root/frontend"
  python3 "$ROOT_DIR/scripts/check_production_pollution.py" "$replay_root"

  local source_before source_after_install
  source_before="$(
    python3 "$ROOT_DIR/scripts/normalized_manifest.py" "$replay_root" --summary \
      | awk -F= '/^manifest-sha256=/ {print $2}'
  )"
  [[ "$source_before" =~ ^[0-9a-f]{64}$ ]] || {
    echo "FRONTEND_SOURCE_DIGEST_INVALID: $replay" >&2
    exit 1
  }

  echo "[frontend][$replay] isolated offline install without lifecycle scripts"
  npm ci --offline --ignore-scripts

  source_after_install="$(
    python3 "$ROOT_DIR/scripts/normalized_manifest.py" "$replay_root" --summary \
      | awk -F= '/^manifest-sha256=/ {print $2}'
  )"
  [[ "$source_after_install" == "$source_before" ]] || {
    echo "FRONTEND_INSTALL_SOURCE_MUTATION: $replay" >&2
    exit 1
  }

  echo "[frontend][$replay] contract, type, unit, build, browser and accessibility"
  python3 "$ROOT_DIR/scripts/check_frontend_baseline.py" "$replay_root"
  python3 "$ROOT_DIR/scripts/check_frontend_structure.py" "$replay_root/frontend"
  npm run typecheck
  npm run test:unit
  npm run build
  npm run test:baseline

  local lock_sha tree_sha build_sha source_after_suite
  lock_sha="$(shasum -a 256 package-lock.json | awk '{print $1}')"
  [[ "$lock_sha" == "$EXPECTED_LOCK_SHA" ]] || {
    echo "FRONTEND_APPROVED_LOCK_DRIFT: expected=$EXPECTED_LOCK_SHA actual=$lock_sha" >&2
    exit 1
  }
  tree_sha="$(npm ls --all --json | shasum -a 256 | awk '{print $1}')"
  build_sha="$({
    cd dist
    find . -type f -print | LC_ALL=C sort | while IFS= read -r path; do
      digest="$(shasum -a 256 "$path" | awk '{print $1}')"
      printf '%s\0%s\n' "$path" "$digest"
    done
  } | shasum -a 256 | awk '{print $1}')"
  source_after_suite="$(
    python3 "$ROOT_DIR/scripts/normalized_manifest.py" "$replay_root" --summary \
      | awk -F= '/^manifest-sha256=/ {print $2}'
  )"
  [[ "$source_after_suite" == "$source_before" ]] || {
    echo "FRONTEND_SUITE_SOURCE_MUTATION: $replay" >&2
    exit 1
  }

  printf 'frontend-source-sha256=%s\nfrontend-lock-sha256=%s\nfrontend-tree-sha256=%s\nfrontend-build-sha256=%s\n' \
    "$source_before" "$lock_sha" "$tree_sha" "$build_sha" > "$summary"
  cat "$summary"
}

run_replay replay-1
run_replay replay-2

if ! cmp -s "$WORK_DIR/replay-1.summary" "$WORK_DIR/replay-2.summary"; then
  echo "FRONTEND_REPRODUCIBILITY_DRIFT: clean offline replays differ" >&2
  diff -u "$WORK_DIR/replay-1.summary" "$WORK_DIR/replay-2.summary" >&2 || true
  exit 1
fi

if [[ -n "${FRONTEND_RELEASE_OUTPUT:-}" ]]; then
  [[ "$FRONTEND_RELEASE_OUTPUT" = /* ]] || {
    echo "FRONTEND_RELEASE_OUTPUT_MUST_BE_ABSOLUTE" >&2
    exit 1
  }
  [[ ! -e "$FRONTEND_RELEASE_OUTPUT" ]] || {
    echo "FRONTEND_RELEASE_OUTPUT_ALREADY_EXISTS" >&2
    exit 1
  }
  mkdir -p "$(dirname "$FRONTEND_RELEASE_OUTPUT")"
  cp -R "$WORK_DIR/replay-1/frontend/dist" "$FRONTEND_RELEASE_OUTPUT"
fi

echo "[frontend] two clean offline replays match"
cat "$WORK_DIR/replay-1.summary"
echo "[frontend] offline verification PASS"
