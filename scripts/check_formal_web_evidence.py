#!/usr/bin/env python3
"""Validate formal Web preconditions and immutable report bytes fail closed."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from formal_web import formal_web_report_issues, visual_baseline_issues  # noqa: E402
from check_cisb import visual_governance_issues  # noqa: E402
from release_json import load_json, schema_issues  # noqa: E402


HEX64 = frozenset("0123456789abcdef")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _is_sha256(value: str) -> bool:
    return len(value) == 64 and all(character in HEX64 for character in value)


def _unique_file(root: Path, name: str) -> tuple[Path | None, list[str]]:
    matches = sorted(path for path in root.rglob(name) if path.is_file() and not path.is_symlink())
    if len(matches) != 1:
        return None, [f"FORMAL_WEB_FILE_SET_INVALID: {name}: count={len(matches)}"]
    return matches[0], []


def _load_controlled(root: Path) -> tuple[dict[str, Any], dict[str, Any], list[str]]:
    contracts = root / "contracts/release"
    issues: list[str] = []
    try:
        test_environment = load_json(root / "contracts/performance/test-environment-1.0.0.json", legacy_numbers=True)
        oracle = load_json(contracts / "visual-baseline-vgb-1.0.0.json")
        cisb = load_json(contracts / "ci-supply-chain-baseline-1.0.0.json")
        controlled = (
            (oracle, load_json(contracts / "visual-baseline.schema.json"), "visual-baseline-vgb-1.0.0.json"),
            (
                load_json(contracts / "formal-web-runner-1.0.0.json"),
                load_json(contracts / "formal-web-runner.schema.json"),
                "formal-web-runner-1.0.0.json",
            ),
            (
                load_json(contracts / "ui-token-manifest-1.0.0.json"),
                load_json(contracts / "ui-token-manifest.schema.json"),
                "ui-token-manifest-1.0.0.json",
            ),
            (
                load_json(contracts / "brand-asset-manifest-1.0.0.json"),
                load_json(contracts / "brand-asset-manifest.schema.json"),
                "brand-asset-manifest-1.0.0.json",
            ),
        )
        for instance, schema, label in controlled:
            issues.extend(f"{label}: {issue}" for issue in schema_issues(instance, schema))
        issues.extend(visual_baseline_issues(oracle, test_environment))
        issues.extend(visual_governance_issues(cisb, oracle, root))
    except (OSError, TypeError, ValueError) as error:
        return {}, {}, [f"FORMAL_WEB_CONTROLLED_INPUT_INVALID: {error}"]
    return oracle, test_environment, sorted(set(issues))


def preflight_issues(
    root: Path,
    build_root: Path,
    expected_frontend_sha256: str,
    expected_build_manifest_sha256: str,
    expected_test_environment_sha256: str,
) -> tuple[list[str], str | None]:
    oracle, test_environment, issues = _load_controlled(root)
    if not _is_sha256(expected_frontend_sha256):
        issues.append("FORMAL_WEB_EXPECTED_ARTIFACT_DIGEST_INVALID")
    if not _is_sha256(expected_build_manifest_sha256):
        issues.append("FORMAL_WEB_EXPECTED_BUILD_MANIFEST_DIGEST_INVALID")
    if not _is_sha256(expected_test_environment_sha256):
        issues.append("FORMAL_WEB_EXPECTED_TEST_ENVIRONMENT_DIGEST_INVALID")
    if issues:
        return sorted(set(issues)), None
    if test_environment.get("contentSha256") != expected_test_environment_sha256:
        issues.append("FORMAL_WEB_TEST_ENVIRONMENT_DIGEST_MISMATCH")
    if oracle.get("approvedArtifactSha256") != expected_frontend_sha256:
        issues.append("FORMAL_WEB_ORACLE_SUBJECT_MISMATCH")

    manifest_path = build_root / "build-manifest.json"
    frontend_path = build_root / "scholarsense-frontend.tar.gz"
    try:
        manifest = load_json(manifest_path)
        manifest_schema = load_json(root / "contracts/release/build-manifest.schema.json")
        issues.extend(f"build-manifest.json: {issue}" for issue in schema_issues(manifest, manifest_schema))
        if _sha256(manifest_path) != expected_build_manifest_sha256:
            issues.append("FORMAL_WEB_BUILD_MANIFEST_DIGEST_MISMATCH")
        actual_frontend_sha256 = _sha256(frontend_path)
        if actual_frontend_sha256 != expected_frontend_sha256:
            issues.append("FORMAL_WEB_ARTIFACT_DIGEST_MISMATCH")
        artifacts = [item for item in manifest.get("artifacts", []) if item.get("name") == frontend_path.name]
        if len(artifacts) != 1:
            issues.append("FORMAL_WEB_BUILD_MANIFEST_FRONTEND_SET_INVALID")
        else:
            artifact = artifacts[0]
            if artifact.get("mediaType") != "application/gzip":
                issues.append("FORMAL_WEB_BUILD_MANIFEST_MEDIA_TYPE_MISMATCH")
            if artifact.get("binarySha256") != expected_frontend_sha256:
                issues.append("FORMAL_WEB_BUILD_MANIFEST_SUBJECT_MISMATCH")
            if artifact.get("size") != frontend_path.stat().st_size:
                issues.append("FORMAL_WEB_BUILD_MANIFEST_SIZE_MISMATCH")
    except (OSError, TypeError, ValueError) as error:
        issues.append(f"FORMAL_WEB_BUILD_INPUT_INVALID: {error}")

    golden_uris = {cell.get("goldenUri") for cell in oracle.get("cells", []) if isinstance(cell, dict)}
    golden_uri = next(iter(golden_uris)) if len(golden_uris) == 1 else None
    if golden_uri is None:
        issues.append("FORMAL_WEB_GOLDEN_URI_SET_INVALID")
    return sorted(set(issues)), golden_uri


def report_issues(
    root: Path,
    web_root: Path,
    build_root: Path,
    expected_frontend_sha256: str,
    expected_build_manifest_sha256: str,
    expected_test_environment_sha256: str,
) -> list[str]:
    issues, _golden_uri = preflight_issues(
        root,
        build_root,
        expected_frontend_sha256,
        expected_build_manifest_sha256,
        expected_test_environment_sha256,
    )
    if issues:
        return issues
    oracle, test_environment, controlled_issues = _load_controlled(root)
    issues.extend(controlled_issues)
    report_path, file_issues = _unique_file(web_root, "formal-web-report.json")
    issues.extend(file_issues)
    if report_path is None:
        return sorted(set(issues))
    try:
        report = load_json(report_path)
        report_schema = load_json(root / "contracts/release/formal-web-report.schema.json")
        issues.extend(f"formal-web-report.json: {issue}" for issue in schema_issues(report, report_schema))
        issues.extend(formal_web_report_issues(report, oracle, test_environment))
        for cell in report.get("matrix", []):
            relative = cell.get("actualScreenshotPath")
            if not isinstance(relative, str):
                issues.append("FORMAL_WEB_SCREENSHOT_PATH_INVALID")
                continue
            screenshot = report_path.parent / relative
            try:
                if screenshot.is_symlink() or not screenshot.is_file():
                    issues.append(f"FORMAL_WEB_SCREENSHOT_MISSING: {cell.get('id', '')}")
                elif _sha256(screenshot) != cell.get("actualScreenshotSha256"):
                    issues.append(f"FORMAL_WEB_SCREENSHOT_DIGEST_MISMATCH: {cell.get('id', '')}")
            except OSError:
                issues.append(f"FORMAL_WEB_SCREENSHOT_UNREADABLE: {cell.get('id', '')}")
    except (OSError, TypeError, ValueError) as error:
        issues.append(f"FORMAL_WEB_REPORT_INVALID: {error}")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    if len(argv) == 7 and argv[1] == "preflight":
        mode = "preflight"
        root = Path(argv[2]).resolve()
        build_root = Path(argv[3]).resolve()
        data_root = build_root
        expected_frontend_sha256, expected_build_manifest_sha256, expected_test_environment_sha256 = argv[4:7]
    elif len(argv) == 8 and argv[1] == "verify":
        mode = "verify"
        root = Path(argv[2]).resolve()
        data_root = Path(argv[3]).resolve()
        build_root = Path(argv[4]).resolve()
        expected_frontend_sha256, expected_build_manifest_sha256, expected_test_environment_sha256 = argv[5:8]
    elif len(argv) == 4:
        mode = "legacy-verify"
        root = Path(argv[1]).resolve()
        data_root = Path(argv[2]).resolve()
        build_root = Path(argv[3]).resolve()
        report_path, file_issues = _unique_file(data_root, "formal-web-report.json")
        if file_issues or report_path is None:
            print("\n".join(file_issues), file=sys.stderr)
            return 1
        try:
            report = load_json(report_path)
            expected_frontend_sha256 = report["subjectArtifactSha256"]
            expected_build_manifest_sha256 = report["buildManifestSha256"]
            expected_test_environment_sha256 = report["testEnvironmentSha256"]
        except (KeyError, OSError, TypeError, ValueError) as error:
            print(f"FORMAL_WEB_REPORT_INVALID: {error}", file=sys.stderr)
            return 1
    else:
        print(
            "usage: check_formal_web_evidence.py preflight ROOT BUILD_ROOT "
            "FRONTEND_SHA256 BUILD_MANIFEST_SHA256 TEST_ENV_SHA256 | "
            "verify ROOT WEB_ROOT BUILD_ROOT FRONTEND_SHA256 BUILD_MANIFEST_SHA256 TEST_ENV_SHA256 | "
            "ROOT WEB_ROOT BUILD_ROOT",
            file=sys.stderr,
        )
        return 2

    if mode == "preflight":
        issues, golden_uri = preflight_issues(
            root,
            build_root,
            expected_frontend_sha256,
            expected_build_manifest_sha256,
            expected_test_environment_sha256,
        )
        if not issues and golden_uri is not None:
            print(golden_uri)
            return 0
    else:
        issues = report_issues(
            root,
            data_root,
            build_root,
            expected_frontend_sha256,
            expected_build_manifest_sha256,
            expected_test_environment_sha256,
        )
        if not issues:
            print("formal-web-evidence: PASS")
            return 0
    print("\n".join(issues), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
