#!/usr/bin/env python3
"""Validate the approved frontend lock, ADRs, and performance manifests."""

from __future__ import annotations

import hashlib
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


EXPECTED_DEPENDENCIES = {
    "@tanstack/vue-query": "5.101.2",
    "echarts": "6.1.0",
    "element-plus": "2.14.3",
    "pinia": "3.0.4",
    "vue": "3.5.40",
    "vue-echarts": "8.0.1",
    "vue-router": "4.6.4",
}
EXPECTED_DEV_DEPENDENCIES = {
    "@playwright/test": "1.61.1",
    "@types/node": "24.13.3",
    "@vitejs/plugin-vue": "6.0.8",
    "axe-core": "4.12.1",
    "typescript": "npm:@typescript/typescript6@6.0.2",
    "vite": "8.1.5",
    "vitest": "4.1.10",
    "vue-tsc": "3.3.7",
}
EXPECTED_OVERRIDES = {"@typescript/old": "npm:typescript@6.0.2"}
EXPECTED_SCRIPTS = {
    "dev": "vite",
    "typecheck": "node scripts/vue-tsc6.mjs --noEmit -p tsconfig.app.json",
    "test": "vitest run",
    "test:unit": "vitest run tests/unit",
    "build": "npm run typecheck && vite build && node scripts/check-build-budget.mjs",
    "preview": "vite preview",
    "test:baseline": "playwright test",
    "preflight:brand": "node scripts/run-brand-preflight.mjs",
    "check:baseline": "python3 ../scripts/check_frontend_baseline.py ..",
}
EXPECTED_TOOLCHAIN = {"node": "24.18.0", "npm": "11.16.0"}
APPROVED_REGISTRY = "https://registry.npmjs.org/"
ALLOWED_NPMRC = {
    "registry": APPROVED_REGISTRY,
    "package-lock": "true",
    "save-exact": "true",
    "audit": "false",
    "fund": "false",
}
LOCK_NAMES = {"package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock"}
EXCLUDED_SEARCH_PARTS = {"node_modules", "dist", "coverage", "playwright-report", "docs"}
PLACEHOLDERS = {"latest", "current", "normal", "normal-network", "tbd", "todo", "unknown"}
ATTRIBUTION_SEGMENTS = {
    "host/navigation",
    "gateway",
    "query",
    "serialization",
    "network",
    "parse",
    "render/main-thread",
    "accessibility-ready",
    "cache-state",
}
SCHEMA_REQUIRED = {
    "frontend-baseline.schema.json": {
        "$schema", "schemaVersion", "baselineVersion", "pabVersion", "toolchain", "registry",
        "packageLockSha256", "dependencyTreeSha256", "buildBudget", "queryKey",
        "stateBoundary", "contentSha256",
    },
    "performance-profile.schema.json": {
        "$schema", "schemaVersion", "performanceProfileVersion", "topology", "capacity", "hotWindow",
        "scenarioWindow", "roleMix", "requestMix", "dataDistribution", "devices", "networks",
        "cacheMix", "clock", "sampling", "attributionSegments", "targets", "guardrails",
        "contentSha256",
    },
    "availability-policy.schema.json": {
        "$schema", "schemaVersion", "availabilityPolicyVersion", "timezone", "businessWindow", "probes",
        "cadenceSeconds", "transaction", "goodMinute", "monthlySli", "missingSampleTreatment",
        "plannedMaintenance", "externalDependencyFailure", "contentSha256",
    },
    "test-environment.schema.json": {
        "$schema", "schemaVersion", "environmentVersion", "capturedAt", "os", "viewports", "web",
        "deterministicBaseline", "mobile", "contentSha256",
    },
}
MANIFESTS = (
    "frontend-baseline-1.0.0.json",
    "performance-profile-pp-1.0.0.json",
    "availability-policy-ap-1.0.0.json",
    "test-environment-1.0.0.json",
)
MANIFEST_SCHEMAS = {
    "frontend-baseline-1.0.0.json": "frontend-baseline.schema.json",
    "performance-profile-pp-1.0.0.json": "performance-profile.schema.json",
    "availability-policy-ap-1.0.0.json": "availability-policy.schema.json",
    "test-environment-1.0.0.json": "test-environment.schema.json",
}
EXPECTED_SCHEMA_CONTENT_SHA256 = {
    "availability-policy.schema.json": "45ae89a1cfb6a116f761cf04a977fed1f4d2733312ba71abbb68ac0b323aa6a6",
    "frontend-baseline.schema.json": "6350280d062c57dcd4021fcd5fc72c1dcb7219d99e26befce95c8929490532f0",
    "performance-profile.schema.json": "55c2cfad0d66e2b0719082bb9ae18178a64189f7350800c9cfd41bde1bff702b",
    "test-environment.schema.json": "c77972c435318542da3fbd3f0db752456b9981bc38b79599b0f403606f41af4d",
}
EXPECTED_MANIFEST_CONTENT_SHA256 = {
    "availability-policy-ap-1.0.0.json": "4b9635ac0747b7a8743a4752064fbbfac19c51d1e2b31c5099c72b7a8642e747",
    "frontend-baseline-1.0.0.json": "aac4378526b4fa2fcb5cdce087808154a3af1ca51d86ee9b48c441b451633180",
    "performance-profile-pp-1.0.0.json": "fd66577ba59bdeb90df2dcb3fdbbc94292fa7fbc359893bdfcafcfa09c4f3529",
    "test-environment-1.0.0.json": "1361a3f0fdeb6e747c5c1f6bfc682863d2a96240cd2136ec23c2370e52593fbb",
}

EXPECTED_STATE_BOUNDARY = {
    "serverState": "volatile-vue-query-memory",
    "uiState": "volatile-pinia-memory",
    "forbiddenPersistence": [
        "localStorage",
        "sessionStorage",
        "IndexedDB",
        "Cache Storage",
        "Service Worker",
        "filesystem",
    ],
    "lifecycleClear": ["logout", "account-switch", "refresh", "webview-destroyed", "host-session-invalid"],
    "recovery": ["reauthenticate", "reauthorize", "refresh-aggregateVersion", "explicit-user-retry"],
}
EXPECTED_SAMPLING = {
    "samplePoints": [
        "user-action",
        "navigation-start",
        "gateway-response",
        "query-complete",
        "parse-complete",
        "render-complete",
        "accessibility-ready",
        "ui-content-ready",
        "server-committedAt",
        "ui-state-observed",
    ],
    "failureSamplePolicy": "retain-with-attribution-and-include-in-denominator",
    "duplicatePolicy": "same-sampleId-is-idempotent",
    "scenarioWindowsRequired": True,
}
BRAND_MATRIX_TARGET_OS = "macOS 26.5.2 build 25F84 arm64"
EXPECTED_BROWSER_ARTIFACTS = {
    ("chrome", "current"): {
        "major": 150,
        "version": "150.0.7871.124",
        "targetOs": BRAND_MATRIX_TARGET_OS,
        "artifactKind": "chrome-for-testing-mac-arm64-zip",
        "artifactUrl": "https://storage.googleapis.com/chrome-for-testing-public/150.0.7871.124/mac-arm64/chrome-mac-arm64.zip",
        "artifactSha256": "36c8b5fe04c08a418a172206bb392600ec1550941bde6af2d4353df21db87a47",
        "executableSha256": "22ddf33cec88bbfd181588eb3da31250a65ba8ebfdb6efcd2694a36275697284",
        "releaseRecordUrl": "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json",
    },
    ("chrome", "previous"): {
        "major": 149,
        "version": "149.0.7827.155",
        "targetOs": BRAND_MATRIX_TARGET_OS,
        "artifactKind": "chrome-for-testing-mac-arm64-zip",
        "artifactUrl": "https://storage.googleapis.com/chrome-for-testing-public/149.0.7827.155/mac-arm64/chrome-mac-arm64.zip",
        "artifactSha256": "135b697c49a375025ba6540a9d963d803d0b80b01f497c77ef5fd8296e4f36c7",
        "executableSha256": "e9c22e6eb15fc062f58202f8fbebbe1e6e2d30211a9d4739a5593e986e7bf01d",
        "releaseRecordUrl": "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json",
    },
    ("edge", "current"): {
        "major": 150,
        "version": "150.0.4078.65",
        "targetOs": BRAND_MATRIX_TARGET_OS,
        "artifactKind": "microsoft-edge-macos-universal-pkg",
        "artifactUrl": "https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/50ebf58f-2ba1-47fe-8b2d-b717caec1fad/MicrosoftEdge-150.0.4078.65.pkg",
        "artifactSha256": "68929c051651b056123369874fe5f6bea0a268500e6c506f6922b2d539a2fd86",
        "executableSha256": "d82cb159d44fecd4e7263b7d20b55e9ca46f0c18485eb0bcdf63b635bd9664bb",
        "releaseRecordUrl": "https://edgeupdates.microsoft.com/api/products?view=enterprise",
    },
    ("edge", "previous"): {
        "major": 149,
        "version": "149.0.4022.98",
        "targetOs": BRAND_MATRIX_TARGET_OS,
        "artifactKind": "microsoft-edge-macos-universal-pkg",
        "artifactUrl": "https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/351963e1-f6ca-4391-a0a5-fb84132a0d98/MicrosoftEdge-149.0.4022.98.pkg",
        "artifactSha256": "0165f110a529d2ed8ce98ed82ef4b19c39ae6b0485b88ccd5797e710f6b9b9d5",
        "executableSha256": "e7da6f1bf1824324bcdd44ad75f87fc40d02bd848a10b5020bd52518133648af",
        "releaseRecordUrl": "https://edgeupdates.microsoft.com/api/products?view=enterprise",
    },
}


def availability_good_minute(probe_results: list[bool]) -> bool:
    return len(probe_results) == 3 and all(isinstance(value, bool) for value in probe_results) and sum(probe_results) >= 2


def availability_monthly_sli(good_eligible_minutes: int, eligible_minutes: int) -> float:
    if eligible_minutes <= 0 or good_eligible_minutes < 0 or good_eligible_minutes > eligible_minutes:
        raise ValueError("eligible minute counts are invalid")
    return good_eligible_minutes / eligible_minutes


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    violations: list[str] = []
    frontend = root / "frontend"
    contracts = root / "contracts/performance"

    package = _load_json(frontend / "package.json", "PACKAGE_JSON", violations)
    lock = _load_json(frontend / "package-lock.json", "PRODUCTION_LOCK", violations)
    _validate_lock_inventory(root, violations)
    if package is not None:
        _validate_package(package, violations)
    if package is not None and lock is not None:
        _validate_lock(package, lock, violations)
    _validate_npmrc(frontend / ".npmrc", violations)
    _validate_adrs(root, violations)

    schemas: dict[str, dict[str, Any]] = {}
    for name, expected_required in SCHEMA_REQUIRED.items():
        schema = _load_json(contracts / name, "SCHEMA", violations)
        if schema is None:
            continue
        schemas[name] = schema
        if set(schema.get("required", [])) != expected_required:
            violations.append(f"SCHEMA_REQUIRED_FIELD_DRIFT: {name}")
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            violations.append(f"SCHEMA_DIALECT_DRIFT: {name}")
        if _canonical_json_digest(schema) != EXPECTED_SCHEMA_CONTENT_SHA256[name]:
            violations.append(f"SCHEMA_VERSION_CONTENT_DRIFT: {name}")

    manifests: dict[str, dict[str, Any]] = {}
    for name in MANIFESTS:
        value = _load_json(contracts / name, "MANIFEST", violations)
        if value is not None:
            manifests[name] = value
            _validate_content_digest(name, value, violations)
            if value.get("schemaVersion") != "1.0.0":
                violations.append(f"MANIFEST_SCHEMA_VERSION_MISMATCH: {name}")

            schema_name = MANIFEST_SCHEMAS[name]
            expected_reference = f"./{schema_name}"
            if value.get("$schema") != expected_reference:
                violations.append(f"MANIFEST_SCHEMA_REFERENCE_DRIFT: {name}")
            schema = schemas.get(schema_name)
            if schema is not None:
                for issue in _json_schema_issues(value, schema):
                    violations.append(f"MANIFEST_SCHEMA_VALIDATION: {name} -> {issue}")

    baseline = manifests.get("frontend-baseline-1.0.0.json")
    if baseline is not None and lock is not None:
        _validate_baseline_manifest(frontend / "package-lock.json", lock, baseline, violations)
    profile = manifests.get("performance-profile-pp-1.0.0.json")
    if profile is not None:
        _validate_profile(profile, violations)
    availability = manifests.get("availability-policy-ap-1.0.0.json")
    if availability is not None:
        _validate_availability(availability, violations)
    environment = manifests.get("test-environment-1.0.0.json")
    if environment is not None:
        _validate_environment(environment, violations)

    return sorted(set(violations))


def _validate_package(package: dict[str, Any], violations: list[str]) -> None:
    if package.get("packageManager") != "npm@11.16.0" or package.get("engines") != EXPECTED_TOOLCHAIN:
        violations.append("TOOLCHAIN_CONSTRAINT_DRIFT: frontend/package.json")
    for section, expected in (
        ("dependencies", EXPECTED_DEPENDENCIES),
        ("devDependencies", EXPECTED_DEV_DEPENDENCIES),
    ):
        actual = package.get(section, {})
        if not isinstance(actual, dict):
            violations.append(f"DEPENDENCY_SECTION_INVALID: {section}")
            continue
        for name in sorted(set(actual) - set(expected)):
            violations.append(f"DIRECT_DEPENDENCY_NOT_APPROVED: {section}.{name}")
        for name in sorted(set(expected) - set(actual)):
            violations.append(f"DIRECT_DEPENDENCY_MISSING: {section}.{name}")
        for name, version in actual.items():
            if name in {"node", "npm"}:
                violations.append(f"TOOLCHAIN_AS_NPM_DEPENDENCY: {section}.{name}")
            expected_version = expected.get(name)
            if expected_version is not None and version != expected_version:
                violations.append(f"DEPENDENCY_VERSION_NOT_EXACT: {section}.{name}={version}")
            if not isinstance(version, str) or _has_range_syntax(version):
                violations.append(f"DEPENDENCY_VERSION_NOT_EXACT: {section}.{name}={version}")
    if package.get("overrides") != EXPECTED_OVERRIDES:
        violations.append("NPM_OVERRIDE_NOT_APPROVED: frontend/package.json")
    if package.get("scripts") != EXPECTED_SCRIPTS:
        violations.append("PACKAGE_SCRIPT_NOT_APPROVED: frontend/package.json")
    if package.get("devDependencies", {}).get("typescript") != "npm:@typescript/typescript6@6.0.2":
        violations.append("TYPESCRIPT_ALIAS_INVALID: frontend/package.json")


def _validate_lock_inventory(root: Path, violations: list[str]) -> None:
    found: list[str] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.name not in LOCK_NAMES:
            continue
        relative = path.relative_to(root)
        if any(part in EXCLUDED_SEARCH_PARTS for part in relative.parts):
            continue
        found.append(relative.as_posix())
    if "frontend/package-lock.json" not in found:
        violations.append("PRODUCTION_LOCK_REQUIRED: frontend/package-lock.json")
    extras = sorted(set(found) - {"frontend/package-lock.json"})
    if extras:
        violations.append("MULTIPLE_PACKAGE_LOCKS: " + ",".join(extras))


def _validate_lock(package: dict[str, Any], lock: dict[str, Any], violations: list[str]) -> None:
    if lock.get("lockfileVersion") != 3:
        violations.append("LOCKFILE_VERSION_DRIFT: frontend/package-lock.json")
    root_entry = lock.get("packages", {}).get("")
    if not isinstance(root_entry, dict):
        violations.append("LOCK_ROOT_MISSING: frontend/package-lock.json")
        return
    for key in ("name", "version", "dependencies", "devDependencies", "engines"):
        if root_entry.get(key) != package.get(key):
            violations.append(f"LOCK_ROOT_DRIFT: {key}")

    compiler_versions: set[str] = set()
    for path, metadata in lock.get("packages", {}).items():
        if not isinstance(metadata, dict) or not path:
            continue
        resolved = metadata.get("resolved")
        if not isinstance(resolved, str) or not resolved:
            violations.append(f"LOCK_RESOLVED_MISSING: {path}")
        elif not resolved.startswith(APPROVED_REGISTRY):
            violations.append(f"LOCK_REGISTRY_NOT_APPROVED: {path}")
        integrity = metadata.get("integrity")
        if not isinstance(integrity, str) or not integrity:
            violations.append(f"LOCK_INTEGRITY_MISSING: {path}")
        if path == "node_modules/typescript":
            if metadata.get("name") != "@typescript/typescript6" or metadata.get("version") != "6.0.2":
                violations.append("TYPESCRIPT_ALIAS_INVALID: frontend/package-lock.json")
            compiler_versions.add(str(metadata.get("version")))
        elif path.endswith("/node_modules/typescript") or path == "node_modules/@typescript/old":
            compiler_versions.add(str(metadata.get("version")))
    if compiler_versions != {"6.0.2"}:
        violations.append("TYPESCRIPT_COMPILER_NOT_UNIQUE: " + ",".join(sorted(compiler_versions)))


def _validate_npmrc(path: Path, violations: list[str]) -> None:
    if not path.is_file():
        violations.append("NPMRC_REQUIRED: frontend/.npmrc")
        return
    actual: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if "=" not in line:
            violations.append(f"NPMRC_SETTING_NOT_APPROVED: line {line_number}")
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        actual[key] = value
        lowered = key.casefold()
        if any(secret in lowered for secret in ("_authtoken", "_password", "username")):
            violations.append(f"NPMRC_SETTING_NOT_APPROVED: {key}")
        if re.search(r"(?:/Users/|/home/|[A-Za-z]:\\\\Users\\\\)", value):
            violations.append(f"NPMRC_SETTING_NOT_APPROVED: {key}")
    if actual != ALLOWED_NPMRC:
        violations.append("NPMRC_SETTING_NOT_APPROVED: settings-drift")


def _validate_adrs(root: Path, violations: list[str]) -> None:
    frontend_adr = root / "docs/architecture/adr/frontend-production-baseline.md"
    performance_adr = root / "docs/architecture/adr/performance-profile-pp-1.0.0.md"
    for path, markers in (
        (frontend_adr, ("FPB-1.0.0", "approved", "@vitejs/plugin-vue", "@types/node", "provisional-for-build", "Story 1.1d", "axios", "DEFER-1", "250000", "600000")),
        (performance_adr, ("PP-1.0.0", "AP-1.0.0", "ui.content-ready", "ui.state-observed", "not-applicable")),
    ):
        if not path.is_file():
            violations.append(f"ADR_REQUIRED: {path.name}")
            continue
        content = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in content:
                violations.append(f"ADR_DECISION_MISSING: {path.name} -> {marker}")


def _validate_baseline_manifest(
    lock_path: Path,
    lock: dict[str, Any],
    baseline: dict[str, Any],
    violations: list[str],
) -> None:
    expected = {
        "baselineVersion": "FPB-1.0.0",
        "pabVersion": "PAB-1.0.0",
        "toolchain": EXPECTED_TOOLCHAIN,
        "registry": APPROVED_REGISTRY,
        "queryKey": ["domain", "resource", "params"],
    }
    for key, value in expected.items():
        if baseline.get(key) != value:
            violations.append(f"FRONTEND_BASELINE_DRIFT: {key}")
    if baseline.get("stateBoundary") != EXPECTED_STATE_BOUNDARY:
        violations.append("FRONTEND_BASELINE_DRIFT: stateBoundary")
    if baseline.get("packageLockSha256") != hashlib.sha256(lock_path.read_bytes()).hexdigest():
        violations.append("PACKAGE_LOCK_SHA256_DRIFT: frontend-baseline")
    if baseline.get("dependencyTreeSha256") != _dependency_tree_digest(lock):
        violations.append("DEPENDENCY_TREE_SHA256_DRIFT: frontend-baseline")
    budget = baseline.get("buildBudget", {})
    expected_budget = {
        "measurement": "vite-minified-bytes-and-gzip-level-9",
        "initialJavaScriptRawBytesPerFile": 250000,
        "initialJavaScriptGzipBytesPerFile": 90000,
        "initialJavaScriptRawBytesTotal": 350000,
        "asyncJavaScriptRawBytesPerFile": 600000,
        "asyncJavaScriptGzipBytesPerFile": 200000,
        "cssRawBytesPerFile": 120000,
        "cssGzipBytesPerFile": 25000,
        "failure": "non-zero-exit",
    }
    if budget != expected_budget:
        violations.append("BUILD_BUDGET_DRIFT: frontend-baseline")


def _validate_profile(profile: dict[str, Any], violations: list[str]) -> None:
    if profile.get("performanceProfileVersion") != "PP-1.0.0" or profile.get("topology") != "production-isomorphic":
        violations.append("PROFILE_VERSION_OR_TOPOLOGY_DRIFT: PP-1.0.0")
    capacity = profile.get("capacity", {})
    if capacity != {"students": 50000, "eventsPerDay": 5000000, "peakConcurrentActiveSessions": 1000}:
        violations.append("PROFILE_CAPACITY_DRIFT: PP-1.0.0")
    if profile.get("hotWindow") != {"days": 60, "approximateEvents": 300000000}:
        violations.append("PROFILE_HOT_WINDOW_DRIFT: PP-1.0.0")
    if profile.get("scenarioWindow") != {"rampMinutes": 15, "warmupMinutes": 15, "steadyMinutes": 60, "peakMinutes": 15}:
        violations.append("PROFILE_WINDOW_DRIFT: PP-1.0.0")
    for mix_name in ("roleMix", "requestMix"):
        mix = profile.get(mix_name, {})
        if not isinstance(mix, dict) or sum(value for value in mix.values() if isinstance(value, (int, float))) != 100:
            violations.append(f"PROFILE_PERCENTAGE_SUM_INVALID: {mix_name}")
    if profile.get("roleMix") != {
        "counselor": 70,
        "college": 15,
        "collaboration": 5,
        "schoolLeadership": 5,
        "governanceAndOperations": 5,
    }:
        violations.append("PROFILE_ROLE_MIX_DRIFT: PP-1.0.0")
    if profile.get("requestMix") != {
        "workbenchAndList": 35,
        "detail": 25,
        "filterAndDashboard": 15,
        "stateCommand": 10,
        "taskAndWorkOrder": 10,
        "reportAndExportSubmission": 5,
    }:
        violations.append("PROFILE_REQUEST_MIX_DRIFT: PP-1.0.0")
    sampling = profile.get("sampling", {})
    if sampling != EXPECTED_SAMPLING:
        violations.append("PROFILE_SAMPLING_DRIFT: PP-1.0.0")
    if set(profile.get("attributionSegments", [])) != ATTRIBUTION_SEGMENTS:
        violations.append("PROFILE_ATTRIBUTION_DRIFT: PP-1.0.0")
    distribution = profile.get("dataDistribution", {})
    if (
        distribution.get("source") != "production-isomorphic-synthetic"
        or distribution.get("piiPolicy") != "no-production-plaintext"
        or set(distribution.get("requiredDimensions", [])) != {
            "college", "grade", "role", "domain", "event-time", "aggregateVersion",
            "authorized-projection-shape",
        }
    ):
        violations.append("PROFILE_DATA_DISTRIBUTION_DRIFT: PP-1.0.0")
    if profile.get("devices") != {
        "desktop": {"cpuCores": 4, "memoryGiB": 8},
        "mobile": {"cpuCores": 4, "memoryGiB": 4},
    }:
        violations.append("PROFILE_DEVICE_DRIFT: PP-1.0.0")
    if profile.get("cacheMix") != {"coldPercent": 50, "warmPercent": 50}:
        violations.append("PROFILE_CACHE_DRIFT: PP-1.0.0")
    if profile.get("networks") != {
        "campus": {"downMbps": 20, "upMbps": 10, "rttMs": 50, "lossPercent": 0.1},
        "mobile": {"downMbps": 10, "upMbps": 5, "rttMs": 100, "lossPercent": 1.0},
    }:
        violations.append("PROFILE_NETWORK_DRIFT: PP-1.0.0")
    if profile.get("clock") != {"source": "server-synchronized-ntp", "maximumSkewMs": 100}:
        violations.append("PROFILE_CLOCK_DRIFT: PP-1.0.0")
    if profile.get("targets") != {
        "contentReadyP95Ms": 2000,
        "filterReadyP95Ms": 3000,
        "stateObservedP95Ms": 5000,
    }:
        violations.append("PROFILE_TARGET_DRIFT: PP-1.0.0")
    if profile.get("guardrails") != {"percentile": 75, "lcpMs": 2500, "inpMs": 200, "cls": 0.1}:
        violations.append("PROFILE_GUARDRAIL_DRIFT: PP-1.0.0")


def _validate_availability(policy: dict[str, Any], violations: list[str]) -> None:
    expected_step_ids = ["post-sso-home", "list", "detail", "safe-idempotent-command-read-back"]
    steps = policy.get("transaction", {}).get("steps", [])
    if [step.get("id") for step in steps if isinstance(step, dict)] != expected_step_ids:
        violations.append("AVAILABILITY_TRANSACTION_DRIFT: AP-1.0.0")
    if any(not isinstance(step.get("timeoutMs"), int) or step["timeoutMs"] <= 0 for step in steps if isinstance(step, dict)):
        violations.append("AVAILABILITY_TIMEOUT_INVALID: AP-1.0.0")
    expected_steps = [
        {"id": "post-sso-home", "timeoutMs": 5000, "success": "authorized-content-ready"},
        {"id": "list", "timeoutMs": 5000, "success": "authorized-list-content-ready"},
        {"id": "detail", "timeoutMs": 5000, "success": "authorized-detail-content-ready"},
        {"id": "safe-idempotent-command-read-back", "timeoutMs": 8000, "success": "command-accepted-and-read-back-same-or-higher-version"},
    ]
    if steps != expected_steps or policy.get("transaction", {}).get("orderRequired") is not True or policy.get("transaction", {}).get("anyStepFailureFailsProbeMinute") is not True:
        violations.append("AVAILABILITY_TRANSACTION_DRIFT: AP-1.0.0")
    expected_probes = [
        {"id": "campus-wired", "networkProfile": "campus"},
        {"id": "campus-wifi", "networkProfile": "campus"},
        {"id": "external-mobile", "networkProfile": "mobile"},
    ]
    if policy.get("probes") != expected_probes or policy.get("cadenceSeconds") != 60:
        violations.append("AVAILABILITY_PROBE_DRIFT: AP-1.0.0")
    if (
        policy.get("availabilityPolicyVersion") != "AP-1.0.0"
        or policy.get("timezone") != "Asia/Shanghai"
        or policy.get("businessWindow") != {"start": "07:00", "end": "23:00", "naturalMonthRequired": True}
    ):
        violations.append("AVAILABILITY_WINDOW_DRIFT: AP-1.0.0")
    if policy.get("goodMinute") != {"formula": "successfulProbes >= 2 of exactly 3", "minimumSuccessfulProbes": 2}:
        violations.append("AVAILABILITY_GOOD_MINUTE_DRIFT: AP-1.0.0")
    if policy.get("missingSampleTreatment") != "bad":
        violations.append("AVAILABILITY_MISSING_SAMPLE_DRIFT: AP-1.0.0")
    monthly = policy.get("monthlySli", {})
    if monthly != {
        "formula": "good eligible minutes / eligible minutes",
        "targetMinimum": 0.999,
        "evidenceWindow": "complete-natural-month",
    }:
        violations.append("AVAILABILITY_MONTHLY_FORMULA_DRIFT: AP-1.0.0")
    denominator_policy = "included-in-denominator-and-reported-separately"
    if policy.get("plannedMaintenance") != denominator_policy or policy.get("externalDependencyFailure") != denominator_policy:
        violations.append("AVAILABILITY_DENOMINATOR_DRIFT: AP-1.0.0")


def _validate_environment(environment: dict[str, Any], violations: list[str]) -> None:
    for value in _string_values(environment):
        if value.casefold() in PLACEHOLDERS:
            violations.append(f"ENVIRONMENT_PLACEHOLDER_FORBIDDEN: {value}")
    web = environment.get("web", {})
    for (browser, channel), expected_record in EXPECTED_BROWSER_ARTIFACTS.items():
        record = web.get(browser, {}).get(channel, {})
        if record.get("major") != expected_record["major"] or record.get("version") != expected_record["version"]:
            violations.append(f"BROWSER_VERSION_DRIFT: {browser}.{channel}")
        if record != expected_record:
            violations.append(f"BROWSER_ARTIFACT_DRIFT: {browser}.{channel}")
    if (
        environment.get("environmentVersion") != "TEST-ENV-1.0.0"
        or environment.get("capturedAt") != "2026-07-19T00:00:00+08:00"
        or environment.get("os") != {
            "brandMatrixTarget": BRAND_MATRIX_TARGET_OS,
            "deterministicBaseline": BRAND_MATRIX_TARGET_OS,
        }
    ):
        violations.append("ENVIRONMENT_METADATA_DRIFT: TEST-ENV-1.0.0")
    if environment.get("viewports") != [
        {"id": "desktop-reference", "width": 1440, "height": 900},
        {"id": "desktop-minimum", "width": 1366, "height": 768},
        {"id": "responsive-mobile", "width": 375, "height": 812},
    ]:
        violations.append("ENVIRONMENT_VIEWPORT_DRIFT: TEST-ENV-1.0.0")
    if environment.get("deterministicBaseline") != {
        "runner": "@playwright/test@1.61.1",
        "browser": "playwright-bundled-chromium",
        "browserVersion": "149.0.7827.55",
        "targetOs": BRAND_MATRIX_TARGET_OS,
        "executableSha256": "11e393326c7d20a7c56641a7c65def33ea9c280da3b0b74cf8563b07989a0ee3",
        "classification": "local-baseline-not-brand-report",
        "digestScope": "browser-executable-bytes",
    }:
        violations.append("ENVIRONMENT_BASELINE_RUNNER_DRIFT: TEST-ENV-1.0.0")
    mobile = environment.get("mobile", {})
    if (
        mobile.get("applicability") != "not-applicable"
        or not mobile.get("decisionId")
        or not mobile.get("approvedBy")
        or not mobile.get("effectiveAt")
        or not mobile.get("reason")
        or mobile.get("responsiveFixtureViewportCssPx") != 375
        or mobile.get("runtimeEvidenceClaim") != "none"
    ):
        violations.append("MOBILE_NA_DECISION_INCOMPLETE: test-environment")


def _validate_content_digest(name: str, value: dict[str, Any], violations: list[str]) -> None:
    expected = value.get("contentSha256")
    payload = dict(value)
    payload.pop("contentSha256", None)
    actual = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    if expected != actual:
        violations.append(f"MANIFEST_CONTENT_DIGEST_DRIFT: {name}")
    if actual != EXPECTED_MANIFEST_CONTENT_SHA256[name]:
        violations.append(f"MANIFEST_VERSION_CONTENT_DRIFT: {name}")


def _canonical_json_digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _dependency_tree_digest(lock: dict[str, Any]) -> str:
    rows = []
    for path, metadata in sorted(lock.get("packages", {}).items()):
        if not isinstance(metadata, dict):
            continue
        rows.append({
            "path": path,
            "name": metadata.get("name"),
            "version": metadata.get("version"),
            "resolved": metadata.get("resolved"),
            "integrity": metadata.get("integrity"),
        })
    payload = json.dumps(rows, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


def _json_schema_issues(instance: Any, schema: dict[str, Any]) -> list[str]:
    """Validate the Draft 2020-12 keywords used by the checked-in contracts.

    The project intentionally keeps this gate on the Python standard library.  The
    implementation is deliberately fail-closed: an unsupported keyword in the
    contract is itself a validation error instead of being silently ignored.
    """

    issues: list[str] = []
    _validate_schema_node(instance, schema, "$", issues)
    return issues


def _validate_schema_node(instance: Any, schema: Any, path: str, issues: list[str]) -> None:
    if isinstance(schema, bool):
        if not schema:
            issues.append(f"{path}: rejected by false schema")
        return
    if not isinstance(schema, dict):
        issues.append(f"{path}: schema node is not an object")
        return

    supported = {
        "$schema", "$id", "title", "description", "type", "const", "enum",
        "properties", "required", "additionalProperties", "items", "minItems",
        "maxItems", "uniqueItems", "minLength", "pattern", "format", "minimum",
        "maximum",
    }
    unsupported = sorted(set(schema) - supported)
    if unsupported:
        issues.append(f"{path}: unsupported schema keyword(s): {','.join(unsupported)}")
        return

    declared_type = schema.get("type")
    if declared_type is not None and not _schema_type_matches(instance, declared_type):
        issues.append(f"{path}: expected type {declared_type}")
        return

    if "const" in schema and not _json_values_equal(instance, schema["const"]):
        issues.append(f"{path}: value does not match const")
    if "enum" in schema and not any(_json_values_equal(instance, candidate) for candidate in schema["enum"]):
        issues.append(f"{path}: value is not in enum")

    if isinstance(instance, dict):
        required = schema.get("required", [])
        for key in required:
            if key not in instance:
                issues.append(f"{path}.{key}: required property missing")
        properties = schema.get("properties", {})
        for key, child in instance.items():
            child_path = f"{path}.{key}"
            if key in properties:
                _validate_schema_node(child, properties[key], child_path, issues)
            else:
                additional = schema.get("additionalProperties", True)
                if additional is False:
                    issues.append(f"{child_path}: additional property not allowed")
                elif isinstance(additional, (dict, bool)):
                    _validate_schema_node(child, additional, child_path, issues)

    if isinstance(instance, list):
        if len(instance) < schema.get("minItems", 0):
            issues.append(f"{path}: fewer than minItems")
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            issues.append(f"{path}: more than maxItems")
        if schema.get("uniqueItems") is True:
            for index, value in enumerate(instance):
                if any(_json_values_equal(value, prior) for prior in instance[:index]):
                    issues.append(f"{path}[{index}]: duplicate item")
                    break
        if "items" in schema:
            for index, value in enumerate(instance):
                _validate_schema_node(value, schema["items"], f"{path}[{index}]", issues)

    if isinstance(instance, str):
        if len(instance) < schema.get("minLength", 0):
            issues.append(f"{path}: shorter than minLength")
        pattern = schema.get("pattern")
        if pattern is not None and re.search(pattern, instance) is None:
            issues.append(f"{path}: string does not match pattern")
        if schema.get("format") == "date-time" and not _is_rfc3339_datetime(instance):
            issues.append(f"{path}: invalid date-time")

    if _is_json_number(instance):
        if not math.isfinite(float(instance)):
            issues.append(f"{path}: number must be finite")
        if "minimum" in schema and instance < schema["minimum"]:
            issues.append(f"{path}: below minimum")
        if "maximum" in schema and instance > schema["maximum"]:
            issues.append(f"{path}: above maximum")


def _schema_type_matches(value: Any, declared: Any) -> bool:
    if isinstance(declared, list):
        return any(_schema_type_matches(value, item) for item in declared)
    checks = {
        "object": lambda item: isinstance(item, dict),
        "array": lambda item: isinstance(item, list),
        "string": lambda item: isinstance(item, str),
        "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
        "number": _is_json_number,
        "boolean": lambda item: isinstance(item, bool),
        "null": lambda item: item is None,
    }
    check = checks.get(declared)
    return check(value) if check is not None else False


def _is_json_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _json_values_equal(left: Any, right: Any) -> bool:
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(_json_values_equal(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(_json_values_equal(a, b) for a, b in zip(left, right))
    return left == right


def _is_rfc3339_datetime(value: str) -> bool:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", value):
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


class _DuplicateJsonKey(ValueError):
    pass


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise _DuplicateJsonKey(key)
        value[key] = item
    return value


def _load_json(path: Path, label: str, violations: list[str]) -> dict[str, Any] | None:
    if not path.is_file():
        violations.append(f"{label}_REQUIRED: {path.as_posix()}")
        return None
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_json_keys,
        )
    except (json.JSONDecodeError, OSError, _DuplicateJsonKey) as error:
        violations.append(f"{label}_INVALID: {path.name} -> {error.__class__.__name__}")
        return None
    if not isinstance(value, dict):
        violations.append(f"{label}_INVALID: {path.name} -> expected object")
        return None
    return value


def _has_range_syntax(version: str) -> bool:
    if version.startswith("npm:@typescript/typescript6@"):
        return version != "npm:@typescript/typescript6@6.0.2"
    return bool(re.search(r"[~^*<>=|\s]", version)) or version.casefold() in PLACEHOLDERS


def _string_values(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _string_values(item)
    elif isinstance(value, list):
        for item in value:
            yield from _string_values(item)


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    violations = validate(root)
    if violations:
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("frontend-baseline: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
