#!/usr/bin/env python3
"""Install the exact TEST-ENV brand browsers from verified cached/downloaded artifacts."""

from __future__ import annotations

import hashlib
import os
import platform
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, load_json  # noqa: E402


class BrowserInstallError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _run(arguments: list[str], code: str) -> None:
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
    if result.returncode != 0:
        raise BrowserInstallError(f"{code}: {result.stderr.strip()}")


def _macos_identity() -> tuple[str, str, str]:
    def value(flag: str) -> str:
        result = subprocess.run(["sw_vers", flag], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode != 0:
            raise BrowserInstallError("FORMAL_BROWSER_OS_UNREADABLE")
        return result.stdout.strip()

    return value("-productName"), value("-productVersion"), value("-buildVersion")


def _download(url: str, output: Path, expected_sha256: str) -> None:
    if output.exists():
        actual = _sha256(output)
        if actual != expected_sha256:
            raise BrowserInstallError(
                f"FORMAL_BROWSER_CACHE_DIGEST_MISMATCH: {output.name} expected={expected_sha256} actual={actual}"
            )
        return
    temporary = output.with_name(f".{output.name}.{os.getpid()}.downloading")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "ScholarSense-formal-web/1.0"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("xb") as stream:
            if response.geturl() != url and not response.geturl().startswith("https://"):
                raise BrowserInstallError("FORMAL_BROWSER_REDIRECT_SCHEME_FORBIDDEN")
            shutil.copyfileobj(response, stream, length=1024 * 1024)
            stream.flush()
            os.fsync(stream.fileno())
        actual = _sha256(temporary)
        if actual != expected_sha256:
            raise BrowserInstallError(
                f"FORMAL_BROWSER_DOWNLOAD_DIGEST_MISMATCH: {output.name} expected={expected_sha256} actual={actual}"
            )
        os.link(temporary, output)
    except FileExistsError:
        if _sha256(output) != expected_sha256:
            raise BrowserInstallError(f"FORMAL_BROWSER_CACHE_RACE_MISMATCH: {output.name}")
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _readonly_tree(root: Path) -> None:
    for path in sorted(root.rglob("*"), reverse=True):
        if path.is_symlink():
            continue
        if path.is_dir():
            path.chmod(0o555)
        elif path.is_file():
            executable = bool(path.stat().st_mode & 0o111)
            path.chmod(0o555 if executable else 0o444)


def _environment() -> dict[str, Any]:
    document = load_json(
        PROJECT_ROOT / "contracts/performance/test-environment-1.0.0.json",
        legacy_numbers=True,
    )
    if not isinstance(document, dict):
        raise BrowserInstallError("FORMAL_BROWSER_TEST_ENV_INVALID")
    return document


def _executable_relative_path(browser: str, channel: str, version: str) -> Path:
    browser_root = Path(f"{browser}-{channel}")
    if browser == "chrome":
        return browser_root / "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
    return (
        browser_root
        / "package"
        / f"MicrosoftEdge-{version}.pkg/Payload/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
    )


def validate_install(destination: Path) -> dict[str, Any]:
    destination = destination.resolve()
    manifest_path = destination / "browsers.json"
    if not destination.is_dir() or not manifest_path.is_file() or manifest_path.is_symlink():
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_INCOMPLETE")
    manifest = load_json(manifest_path, legacy_numbers=True)
    if not isinstance(manifest, dict):
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_MANIFEST_INVALID")
    if manifest.get("version") != "FORMAL-BROWSER-INSTALL-1.0.0":
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_VERSION_MISMATCH")
    if manifest.get("os") != "macOS 26.5.2 build 25F84 arm64":
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_OS_MISMATCH")
    records = manifest.get("browsers")
    if not isinstance(records, list) or len(records) != 4:
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_MATRIX_INCOMPLETE")
    by_id = {
        record.get("id"): record
        for record in records
        if isinstance(record, dict) and isinstance(record.get("id"), str)
    }
    required_ids = {
        "chrome-current",
        "chrome-previous",
        "edge-current",
        "edge-previous",
    }
    if set(by_id) != required_ids or len(by_id) != len(records):
        raise BrowserInstallError("FORMAL_BROWSER_INSTALL_MATRIX_INVALID")

    environment = _environment()
    record_keys = {
        "id",
        "browser",
        "channel",
        "version",
        "major",
        "artifactPath",
        "artifactSha256",
        "executablePath",
        "executableSha256",
    }
    for browser in ("chrome", "edge"):
        for channel in ("current", "previous"):
            identifier = f"{browser}-{channel}"
            record = by_id[identifier]
            expected = environment["web"][browser][channel]
            if set(record) != record_keys:
                raise BrowserInstallError(f"FORMAL_BROWSER_INSTALL_RECORD_INVALID: {identifier}")
            expected_values = {
                "id": identifier,
                "browser": browser,
                "channel": channel,
                "version": expected["version"],
                "major": expected["major"],
                "artifactSha256": expected["artifactSha256"],
                "executableSha256": expected["executableSha256"],
            }
            if any(record.get(key) != value for key, value in expected_values.items()):
                raise BrowserInstallError(f"FORMAL_BROWSER_INSTALL_RECORD_DRIFT: {identifier}")
            executable = destination / _executable_relative_path(browser, channel, expected["version"])
            if record.get("executablePath") != str(executable):
                raise BrowserInstallError(f"FORMAL_BROWSER_EXECUTABLE_PATH_MISMATCH: {identifier}")
            if executable.is_symlink() or not executable.is_file() or not os.access(executable, os.X_OK):
                raise BrowserInstallError(f"FORMAL_BROWSER_EXECUTABLE_MISSING: {identifier}")
            actual = _sha256(executable)
            if actual != expected["executableSha256"]:
                raise BrowserInstallError(
                    f"FORMAL_BROWSER_EXECUTABLE_DIGEST_MISMATCH: {identifier} "
                    f"expected={expected['executableSha256']} actual={actual}"
                )
    return manifest


def install(destination: Path, cache: Path) -> dict[str, Any]:
    product, version, build = _macos_identity()
    if (product, version, build, platform.machine()) != ("macOS", "26.5.2", "25F84", "arm64"):
        raise BrowserInstallError(
            "FORMAL_BROWSER_OS_DRIFT: "
            f"expected=macOS/26.5.2/25F84/arm64 actual={product}/{version}/{build}/{platform.machine()}"
        )
    if destination.exists():
        raise BrowserInstallError("FORMAL_BROWSER_OUTPUT_ALREADY_EXISTS")
    environment = _environment()
    cache.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.installing")
    temporary.mkdir(mode=0o700)
    records: list[dict[str, Any]] = []
    try:
        for browser in ("chrome", "edge"):
            for channel in ("current", "previous"):
                record = environment["web"][browser][channel]
                suffix = ".zip" if browser == "chrome" else ".pkg"
                artifact = cache / f"{browser}-{channel}{suffix}"
                _download(record["artifactUrl"], artifact, record["artifactSha256"])
                browser_root = temporary / f"{browser}-{channel}"
                if browser == "chrome":
                    browser_root.mkdir()
                    _run(["ditto", "-xk", str(artifact), str(browser_root)], "FORMAL_BROWSER_CHROME_EXTRACT_FAILED")
                    app = browser_root / "chrome-mac-arm64/Google Chrome for Testing.app"
                    executable = app / "Contents/MacOS/Google Chrome for Testing"
                else:
                    expanded = browser_root / "package"
                    browser_root.mkdir()
                    _run(["pkgutil", "--expand-full", str(artifact), str(expanded)], "FORMAL_BROWSER_EDGE_EXTRACT_FAILED")
                    app = expanded / f"MicrosoftEdge-{record['version']}.pkg/Payload/Microsoft Edge.app"
                    executable = app / "Contents/MacOS/Microsoft Edge"
                    _run(["codesign", "--verify", "--strict", str(app)], "FORMAL_BROWSER_EDGE_SIGNATURE_INVALID")
                if not executable.is_file() or not os.access(executable, os.X_OK):
                    raise BrowserInstallError(f"FORMAL_BROWSER_EXECUTABLE_MISSING: {browser}-{channel}")
                actual_executable = _sha256(executable)
                if actual_executable != record["executableSha256"]:
                    raise BrowserInstallError(
                        f"FORMAL_BROWSER_EXECUTABLE_DIGEST_MISMATCH: {browser}-{channel} "
                        f"expected={record['executableSha256']} actual={actual_executable}"
                    )
                records.append(
                    {
                        "id": f"{browser}-{channel}",
                        "browser": browser,
                        "channel": channel,
                        "version": record["version"],
                        "major": record["major"],
                        "artifactPath": str(artifact.resolve()),
                        "artifactSha256": record["artifactSha256"],
                        "executablePath": str((destination / executable.relative_to(temporary)).resolve()),
                        "executableSha256": actual_executable,
                    }
                )
        manifest = {
            "version": "FORMAL-BROWSER-INSTALL-1.0.0",
            "os": "macOS 26.5.2 build 25F84 arm64",
            "browsers": records,
        }
        manifest_path = temporary / "browsers.json"
        manifest_path.write_bytes(canonical_bytes(manifest) + b"\n")
        _readonly_tree(temporary)
        temporary.chmod(0o555)
        if destination.exists():
            raise BrowserInstallError("FORMAL_BROWSER_OUTPUT_ALREADY_EXISTS")
        temporary.rename(destination)
        return manifest
    except Exception:
        if temporary.exists():
            for path in temporary.rglob("*"):
                try:
                    path.chmod(0o700 if path.is_dir() else 0o600)
                except OSError:
                    pass
            shutil.rmtree(temporary, ignore_errors=True)
        raise


def ensure_install(destination: Path, cache: Path) -> dict[str, Any]:
    destination = destination.resolve()
    if destination.exists():
        return validate_install(destination)
    return install(destination, cache)


def main(argv: list[str]) -> int:
    if len(argv) not in {2, 3}:
        print("usage: install_formal_browsers.py DESTINATION [CACHE]", file=sys.stderr)
        return 2
    destination = Path(argv[1]).resolve()
    cache = (
        Path(argv[2]).expanduser().resolve()
        if len(argv) == 3
        else Path.home() / "Library/Caches/ScholarSense/formal-browsers/TEST-ENV-1.0.0/artifacts"
    )
    try:
        manifest = ensure_install(destination, cache)
    except (BrowserInstallError, KeyError, OSError, TypeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"formal-browsers: PASS ({len(manifest['browsers'])} exact executables)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
