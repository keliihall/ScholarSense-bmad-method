#!/usr/bin/env python3
"""Frozen frontend extraction and formal Web evidence semantics."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath
from typing import Any, Callable


PROJECT_ROOT = Path(__file__).resolve().parents[1]

sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_sha256  # noqa: E402


HEX64 = re.compile(r"^[0-9a-f]{64}$")
OCI_URI = re.compile(r"^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}$")
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
CHECK_NAMES = frozenset(
    {
        "artifactServed",
        "resources",
        "console",
        "network",
        "keyboardFocus",
        "axe",
        "zoom200",
        "responsive375",
        "reflow320",
        "uiTokens",
        "brandAssets",
    }
)
FONT_FILES = (
    (
        "PingFang.ttc",
        Path("/System/Library/AssetsV2/com_apple_MobileAsset_Font8/86ba2c91f017a3749571a82f2c6d890ac7ffb2fb.asset/AssetData/PingFang.ttc"),
        "9ff3ce9439fe285cdabb46f9ceb46b1ac58f1ca07e6f4a764e8286db621a0af9",
    ),
    ("SFNS.ttf", Path("/System/Library/Fonts/SFNS.ttf"), "2bfd40dc72e6759e248f82a52a40d551338979fffc9b5c070e685b4b7ad19e66"),
    (
        "Hiragino Sans GB.ttc",
        Path("/System/Library/Fonts/Hiragino Sans GB.ttc"),
        "f887295caf2881cab9554b14c5ab4c9ee624c3895599da152ec37416b5aefae0",
    ),
)


class FormalWebError(RuntimeError):
    """Stable fail-closed formal Web diagnostic."""


def formal_runner_issues(document: Any, *, require_actions: bool = False) -> list[str]:
    if not isinstance(document, dict) or not isinstance(document.get("fingerprint"), dict):
        return ["FORMAL_WEB_RUNNER_BASELINE_INVALID"]
    issues: list[str] = []
    fingerprint = document["fingerprint"]
    if document.get("version") != "FORMAL-WEB-RUNNER-1.0.0" or document.get("status") != "approved":
        issues.append("FORMAL_WEB_RUNNER_APPROVAL_INVALID")
    if canonical_sha256(fingerprint) != document.get("runnerImageSha256"):
        issues.append("FORMAL_WEB_RUNNER_IMAGE_DIGEST_MISMATCH")
    font_manifest = [{"name": name, "sha256": digest} for name, _path, digest in FONT_FILES]
    if canonical_sha256(font_manifest) != fingerprint.get("fontManifestSha256"):
        issues.append("FORMAL_WEB_FONT_MANIFEST_DIGEST_MISMATCH")
    for name, path, expected in FONT_FILES:
        try:
            actual = _sha256(path)
        except OSError:
            issues.append(f"FORMAL_WEB_FONT_MISSING: {name}")
            continue
        if actual != expected:
            issues.append(f"FORMAL_WEB_FONT_DRIFT: {name}")

    def command(arguments: list[str]) -> str:
        result = subprocess.run(arguments, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        return result.stdout.strip() if result.returncode == 0 else ""

    system = {
        "osVersion": command(["sw_vers", "-productVersion"]),
        "osBuild": command(["sw_vers", "-buildVersion"]),
        "architecture": command(["uname", "-m"]),
    }
    for field, actual in system.items():
        if fingerprint.get(field) != actual:
            issues.append(f"FORMAL_WEB_RUNNER_{field.upper()}_DRIFT")
    if require_actions:
        if os.environ.get("RUNNER_ENVIRONMENT") != "self-hosted":
            issues.append("FORMAL_WEB_RUNNER_NOT_SELF_HOSTED")
        if os.environ.get("RUNNER_OS") != "macOS" or os.environ.get("RUNNER_ARCH") != "ARM64":
            issues.append("FORMAL_WEB_RUNNER_ACTIONS_IDENTITY_DRIFT")
        if not os.environ.get("RUNNER_NAME", "").startswith("scholarsense-test-env-1-"):
            issues.append("FORMAL_WEB_RUNNER_NAME_DRIFT")
    return sorted(set(issues))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_member_path(name: str) -> PurePosixPath:
    if not name or "\\" in name or name.startswith("/"):
        raise FormalWebError(f"FORMAL_WEB_ARCHIVE_PATH_UNSAFE: {name}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise FormalWebError(f"FORMAL_WEB_ARCHIVE_PATH_UNSAFE: {name}")
    return path


def safe_extract_frontend(
    archive: Path,
    destination: Path,
    expected_sha256: str,
    *,
    before_extract: Callable[[], None] | None = None,
) -> dict[str, Any]:
    """Verify first, inspect the complete tar, then extract without following links."""

    if not HEX64.fullmatch(expected_sha256):
        raise FormalWebError("FORMAL_WEB_EXPECTED_DIGEST_INVALID")
    if destination.exists():
        raise FormalWebError("FORMAL_WEB_OUTPUT_ALREADY_EXISTS")
    try:
        descriptor = os.open(archive, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        archive_stream = os.fdopen(descriptor, "rb")
        archive_stat = os.fstat(archive_stream.fileno())
        if not stat.S_ISREG(archive_stat.st_mode):
            archive_stream.close()
            raise FormalWebError("FORMAL_WEB_ARTIFACT_NOT_REGULAR")
        digest = hashlib.sha256()
        for chunk in iter(lambda: archive_stream.read(1024 * 1024), b""):
            digest.update(chunk)
        actual_sha256 = digest.hexdigest()
        archive_stream.seek(0)
    except OSError as error:
        raise FormalWebError("FORMAL_WEB_ARTIFACT_UNREADABLE") from error
    if actual_sha256 != expected_sha256:
        archive_stream.close()
        raise FormalWebError(
            f"FORMAL_WEB_ARTIFACT_DIGEST_MISMATCH: expected={expected_sha256} actual={actual_sha256}"
        )
    if before_extract is not None:
        try:
            before_extract()
        except BaseException:
            archive_stream.close()
            raise

    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.extracting")
    if temporary.exists():
        raise FormalWebError("FORMAL_WEB_TEMPORARY_OUTPUT_EXISTS")
    members: list[tuple[tarfile.TarInfo, PurePosixPath]] = []
    names: set[str] = set()
    total = 0
    try:
        with archive_stream, tarfile.open(fileobj=archive_stream, mode="r:gz") as source:
            for member in source.getmembers():
                relative = _safe_member_path(member.name.rstrip("/") if member.isdir() else member.name)
                rendered = relative.as_posix()
                if rendered in names:
                    raise FormalWebError(f"FORMAL_WEB_ARCHIVE_DUPLICATE_PATH: {rendered}")
                names.add(rendered)
                if member.issym() or member.islnk():
                    raise FormalWebError(f"FORMAL_WEB_ARCHIVE_LINK_FORBIDDEN: {rendered}")
                if not member.isfile() and not member.isdir():
                    raise FormalWebError(f"FORMAL_WEB_ARCHIVE_TYPE_FORBIDDEN: {rendered}")
                if member.size < 0 or member.size > MAX_FILE_BYTES:
                    raise FormalWebError(f"FORMAL_WEB_ARCHIVE_FILE_SIZE_INVALID: {rendered}")
                total += member.size
                if total > MAX_TOTAL_BYTES:
                    raise FormalWebError("FORMAL_WEB_ARCHIVE_TOTAL_SIZE_EXCEEDED")
                members.append((member, relative))
            if "index.html" not in names:
                raise FormalWebError("FORMAL_WEB_ENTRYPOINT_MISSING")

            temporary.mkdir(mode=0o700)
            for member, relative in members:
                target = temporary.joinpath(*relative.parts)
                if member.isdir():
                    target.mkdir(mode=0o700, parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                extracted = source.extractfile(member)
                if extracted is None:
                    raise FormalWebError(f"FORMAL_WEB_ARCHIVE_FILE_UNREADABLE: {relative}")
                with target.open("xb") as output:
                    shutil.copyfileobj(extracted, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
                target.chmod(0o444)
        for directory in sorted((path for path in temporary.rglob("*") if path.is_dir()), reverse=True):
            directory.chmod(0o555)
        temporary.chmod(0o555)
        if destination.exists():
            raise FormalWebError("FORMAL_WEB_OUTPUT_ALREADY_EXISTS")
        temporary.rename(destination)
    except (OSError, tarfile.TarError) as error:
        if isinstance(error, FormalWebError):
            raise
        raise FormalWebError("FORMAL_WEB_ARCHIVE_INVALID") from error
    finally:
        if temporary.exists():
            temporary.chmod(0o700)
            for path in temporary.rglob("*"):
                try:
                    path.chmod(0o700 if path.is_dir() else 0o600)
                except OSError:
                    pass
            shutil.rmtree(temporary, ignore_errors=True)
    return {
        "subjectArtifactSha256": actual_sha256,
        "fileCount": sum(1 for _member, _relative in members if _member.isfile()),
        "totalBytes": total,
    }


def visual_baseline_issues(document: Any, test_environment: Any) -> list[str]:
    if not isinstance(document, dict) or not isinstance(test_environment, dict):
        return ["FORMAL_WEB_VGB_INVALID"]
    issues: list[str] = []
    expected_top = {
        "version": "VGB-1.0.0",
        "status": "approved",
        "testEnvironmentVersion": "TEST-ENV-1.0.0",
        "deviceScaleFactor": 1,
        "locale": "zh-CN",
        "timezone": "Asia/Shanghai",
        "colorScheme": "light",
        "reducedMotion": "reduce",
        "networkProfile": "frozen-local-static-only",
        "dataProfile": "deterministic-baseline-fixture",
        "thresholdPermille": 100,
        "maxDiffPixels": 0,
        "animations": "disabled",
        "caret": "hide",
        "scale": "css",
    }
    for field, expected in expected_top.items():
        if document.get(field) != expected:
            issues.append(f"FORMAL_WEB_VGB_{field.upper()}_MISMATCH")
    for field in (
        "approvedArtifactSha256",
        "runnerImageSha256",
        "fontManifestSha256",
        "testEnvironmentSha256",
    ):
        if not HEX64.fullmatch(str(document.get(field, ""))):
            issues.append(f"FORMAL_WEB_VGB_{field.upper()}_INVALID")
    if document.get("testEnvironmentSha256") != test_environment.get("contentSha256"):
        issues.append("FORMAL_WEB_VGB_TEST_ENVIRONMENT_MISMATCH")
    if not document.get("approvedByUxBrand") or not document.get("approvedByWebQa"):
        issues.append("FORMAL_WEB_VGB_APPROVAL_MISSING")

    viewport_records = {
        item.get("id"): item
        for item in test_environment.get("viewports", [])
        if isinstance(item, dict) and item.get("id") in {"desktop-reference", "desktop-minimum"}
    }
    browser_records: dict[tuple[str, str], dict[str, Any]] = {}
    web = test_environment.get("web", {})
    if isinstance(web, dict):
        for browser in ("chrome", "edge"):
            for channel in ("current", "previous"):
                record = web.get(browser, {}).get(channel) if isinstance(web.get(browser), dict) else None
                if isinstance(record, dict):
                    browser_records[(browser, channel)] = record
    expected_ids = {
        f"{browser}-{channel}-{viewport}"
        for browser, channel in browser_records
        for viewport in viewport_records
    }
    cells = document.get("cells")
    if not isinstance(cells, list):
        return sorted(set(issues + ["FORMAL_WEB_VGB_CELLS_INVALID"]))
    actual_ids: set[str] = set()
    for cell in cells:
        if not isinstance(cell, dict):
            issues.append("FORMAL_WEB_VGB_CELL_INVALID")
            continue
        identity = cell.get("id")
        if not isinstance(identity, str) or identity in actual_ids:
            issues.append("FORMAL_WEB_VGB_CELL_ID_INVALID")
            continue
        actual_ids.add(identity)
        key = (cell.get("browser"), cell.get("channel"))
        browser = browser_records.get(key)
        viewport = viewport_records.get(cell.get("viewport"))
        if browser is None or viewport is None:
            issues.append(f"FORMAL_WEB_VGB_CELL_MATRIX_INVALID: {identity}")
            continue
        expectations = {
            "major": browser.get("major"),
            "browserVersion": browser.get("version"),
            "executableSha256": browser.get("executableSha256"),
            "browserArtifactSha256": browser.get("artifactSha256"),
            "osBuild": test_environment.get("os", {}).get("brandMatrixTarget"),
            "width": viewport.get("width"),
            "height": viewport.get("height"),
            "deviceScaleFactor": document.get("deviceScaleFactor"),
            "locale": document.get("locale"),
            "timezone": document.get("timezone"),
            "clock": document.get("clock"),
            "networkProfile": document.get("networkProfile"),
            "dataProfile": document.get("dataProfile"),
        }
        for field, expected in expectations.items():
            if cell.get(field) != expected:
                issues.append(f"FORMAL_WEB_VGB_CELL_{field.upper()}_MISMATCH: {identity}")
        if not OCI_URI.fullmatch(str(cell.get("goldenUri", ""))):
            issues.append(f"FORMAL_WEB_VGB_GOLDEN_URI_INVALID: {identity}")
        if not HEX64.fullmatch(str(cell.get("goldenScreenshotSha256", ""))):
            issues.append(f"FORMAL_WEB_VGB_GOLDEN_DIGEST_INVALID: {identity}")
        expected_path = f"goldens/{identity}.png"
        if cell.get("goldenPath") != expected_path:
            issues.append(f"FORMAL_WEB_VGB_GOLDEN_PATH_INVALID: {identity}")
    if actual_ids != expected_ids or len(cells) != 8:
        issues.append(
            "FORMAL_WEB_VGB_CELL_SET_INVALID: "
            f"missing={','.join(sorted(expected_ids - actual_ids))};extra={','.join(sorted(actual_ids - expected_ids))}"
        )
    return sorted(set(issues))


def formal_web_report_issues(report: Any, visual_baseline: Any, test_environment: Any) -> list[str]:
    if not isinstance(report, dict):
        return ["FORMAL_WEB_REPORT_INVALID"]
    issues = visual_baseline_issues(visual_baseline, test_environment)
    if issues:
        return [f"FORMAL_WEB_REPORT_ORACLE_INVALID: {issue}" for issue in issues]
    expected_top = {
        "version": "FORMAL-WEB-REPORT-1.0.0",
        "subjectArtifactSha256": visual_baseline["approvedArtifactSha256"],
        "testEnvironmentSha256": test_environment["contentSha256"],
        "visualBaselineSha256": canonical_sha256(visual_baseline),
        "runnerImageSha256": visual_baseline["runnerImageSha256"],
        "result": "passed",
    }
    for field, expected in expected_top.items():
        if report.get(field) != expected:
            issues.append(f"FORMAL_WEB_REPORT_{field.upper()}_MISMATCH")
    if not OCI_URI.fullmatch(str(report.get("subjectArtifactUri", ""))):
        issues.append("FORMAL_WEB_REPORT_SUBJECT_URI_INVALID")
    if not HEX64.fullmatch(str(report.get("buildManifestSha256", ""))):
        issues.append("FORMAL_WEB_REPORT_BUILD_MANIFEST_DIGEST_INVALID")
    expected_scope = {
        "productionEntry": "/scholarsense/",
        "downstreamBusinessPages": "not-in-scope-before-their-own-stories",
        "appApplicability": "not-applicable",
        "appRuntimeEvidenceClaim": "none",
    }
    if report.get("scope") != expected_scope:
        issues.append("FORMAL_WEB_REPORT_SCOPE_INVALID")

    expected_cells = {cell["id"]: cell for cell in visual_baseline["cells"]}
    matrix = report.get("matrix")
    if not isinstance(matrix, list):
        return sorted(set(issues + ["FORMAL_WEB_REPORT_MATRIX_INVALID"]))
    actual_cells: dict[str, dict[str, Any]] = {}
    for cell in matrix:
        if not isinstance(cell, dict) or not isinstance(cell.get("id"), str) or cell["id"] in actual_cells:
            issues.append("FORMAL_WEB_REPORT_CELL_ID_INVALID")
            continue
        identity = cell["id"]
        actual_cells[identity] = cell
        expected = expected_cells.get(identity)
        if expected is None:
            issues.append(f"FORMAL_WEB_REPORT_CELL_UNKNOWN: {identity}")
            continue
        comparisons = {
            "browser": expected["browser"],
            "channel": expected["channel"],
            "major": expected["major"],
            "browserVersion": expected["browserVersion"],
            "viewport": expected["viewport"],
            "executableSha256": expected["executableSha256"],
            "goldenScreenshotSha256": expected["goldenScreenshotSha256"],
            "actualScreenshotSha256": expected["goldenScreenshotSha256"],
            "actualScreenshotPath": f"screenshots/{expected['goldenPath']}",
            "diffPixels": 0,
            "result": "passed",
        }
        for field, value in comparisons.items():
            if cell.get(field) != value:
                issues.append(f"FORMAL_WEB_REPORT_CELL_{field.upper()}_MISMATCH: {identity}")
        if not HEX64.fullmatch(str(cell.get("diffRgbaSha256", ""))):
            issues.append(f"FORMAL_WEB_REPORT_CELL_DIFF_DIGEST_INVALID: {identity}")
        checks = cell.get("checks")
        if not isinstance(checks, dict) or set(checks) != set(CHECK_NAMES) or any(
            value != "passed" for value in checks.values()
        ):
            issues.append(f"FORMAL_WEB_REPORT_CELL_CHECKS_FAILED: {identity}")
    if set(actual_cells) != set(expected_cells) or len(matrix) != len(expected_cells):
        issues.append("FORMAL_WEB_REPORT_MATRIX_SET_INVALID")
    return sorted(set(issues))


def main(argv: list[str]) -> int:
    if len(argv) != 5 or argv[1] != "safe_extract_frontend":
        print(
            "usage: formal_web.py safe_extract_frontend ARCHIVE DESTINATION EXPECTED_SHA256",
            file=sys.stderr,
        )
        return 2
    try:
        result = safe_extract_frontend(Path(argv[2]), Path(argv[3]), argv[4])
    except (FormalWebError, OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(
        "formal-web-extraction: PASS "
        f"({result['subjectArtifactSha256']}, {result['fileCount']} files, {result['totalBytes']} bytes)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
