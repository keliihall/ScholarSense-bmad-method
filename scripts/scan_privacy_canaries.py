#!/usr/bin/env python3
"""Fail a production-artifact scan when a Story 1.8 plaintext canary is present."""

from __future__ import annotations

import io
import stat
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


# Non-production sentinels already exercised by the Story 1.8 projection, error,
# and browser-storage tests. Values are never rendered by this scanner.
CANARIES: dict[str, bytes] = {
    "S18_CRYPTO_PLAINTEXT": "student-contact-canary-严禁泄露".encode(),
    "S18_GLOBAL_HIDDEN_BODY": b"canary-secret",
    "S18_GLOBAL_HIDDEN_NETWORK": b"canary-network-secret",
    "S18_ERROR_REFLECTION": b"AUDIT_SEARCH_CANARY_PLAINTEXT_DO_NOT_REFLECT",
    "S18_BROWSER_STORAGE": b"audit-failure-persistence-canary",
    "S19_STUDENT_IDENTIFIER": b"PIC_STUDENT_IDENTIFIER_CANARY_DO_NOT_EXPORT",
    "S19_EXTERNAL_BODY": b"PIC_EXTERNAL_BODY_CANARY_DO_NOT_RECORD",
    "S19_FREE_TEXT": b"PIC_FREE_TEXT_CANARY_DO_NOT_EXPORT",
    "S19_SECRET": b"PIC_SECRET_CANARY_DO_NOT_RECORD",
    "S19_NONCE": b"PIC_NONCE_CANARY_DO_NOT_RECORD",
    "S19_SIGNATURE": b"PIC_SIGNATURE_CANARY_DO_NOT_RECORD",
}
ARCHIVE_SUFFIXES = {".jar", ".war", ".zip"}
ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")
MAX_ARCHIVE_DEPTH = 8
MAX_ARCHIVE_MEMBER_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 100_000
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024


@dataclass(frozen=True, order=True)
class CanaryFinding:
    canary_id: str
    location: str


@dataclass(frozen=True)
class ScanReport:
    findings: tuple[CanaryFinding, ...]
    files_scanned: int
    bytes_scanned: int
    archive_members_scanned: int


class PrivacyScanError(RuntimeError):
    def __init__(self, code: str, location: str) -> None:
        super().__init__(code)
        self.code = code
        self.location = location


@dataclass
class _Accumulator:
    findings: set[CanaryFinding]
    files_scanned: int = 0
    bytes_scanned: int = 0
    archive_members_scanned: int = 0
    archive_uncompressed_bytes: int = 0


def scan_paths(paths: Iterable[Path]) -> ScanReport:
    requested = tuple(Path(path) for path in paths)
    if not requested:
        raise PrivacyScanError("PRIVACY_SCAN_TARGET_REQUIRED", "<none>")

    accumulator = _Accumulator(set())
    for requested_path in requested:
        _scan_requested_path(requested_path, accumulator)
    return ScanReport(
        tuple(sorted(accumulator.findings)),
        accumulator.files_scanned,
        accumulator.bytes_scanned,
        accumulator.archive_members_scanned,
    )


def format_finding(finding: CanaryFinding) -> str:
    return (
        "PRIVACY_CANARY_DETECTED: "
        f"id={finding.canary_id} file={_redact_canaries(finding.location)}"
    )


def _scan_requested_path(path: Path, accumulator: _Accumulator) -> None:
    if not path.exists():
        raise PrivacyScanError("PRIVACY_SCAN_TARGET_MISSING", str(path))
    if path.is_symlink():
        raise PrivacyScanError("PRIVACY_SCAN_SYMLINK_FORBIDDEN", str(path))
    if path.is_file():
        _scan_filesystem_file(path, accumulator)
        return
    if not path.is_dir():
        raise PrivacyScanError("PRIVACY_SCAN_TARGET_NOT_REGULAR", str(path))

    for entry in sorted(path.rglob("*")):
        if entry.is_symlink():
            raise PrivacyScanError("PRIVACY_SCAN_SYMLINK_FORBIDDEN", str(entry))
        if entry.is_file():
            _scan_filesystem_file(entry, accumulator)
        elif not entry.is_dir():
            raise PrivacyScanError("PRIVACY_SCAN_TARGET_NOT_REGULAR", str(entry))


def _scan_filesystem_file(path: Path, accumulator: _Accumulator) -> None:
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode):
            raise PrivacyScanError("PRIVACY_SCAN_TARGET_NOT_REGULAR", str(path))
        data = path.read_bytes()
    except PrivacyScanError:
        raise
    except OSError as error:
        raise PrivacyScanError("PRIVACY_SCAN_READ_FAILED", str(path)) from error

    location = str(path)
    _record_payload(data, location, accumulator, archive_member=False)
    if _archive_candidate(path.suffix, data):
        _scan_archive(data, location, path.suffix, accumulator, depth=1)


def _scan_archive(
    data: bytes,
    location: str,
    suffix: str,
    accumulator: _Accumulator,
    *,
    depth: int,
) -> None:
    if depth > MAX_ARCHIVE_DEPTH:
        raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_DEPTH_EXCEEDED", location)
    try:
        if not zipfile.is_zipfile(io.BytesIO(data)):
            if suffix.casefold() in ARCHIVE_SUFFIXES or data.startswith(ZIP_MAGICS):
                raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_INVALID", location)
            return
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for member in sorted(archive.infolist(), key=lambda item: item.filename):
                if member.is_dir():
                    continue
                member_location = f"{location}!{member.filename}"
                if member.flag_bits & 0x1:
                    raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_ENCRYPTED", member_location)
                if member.file_size > MAX_ARCHIVE_MEMBER_BYTES:
                    raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_MEMBER_TOO_LARGE", member_location)
                if accumulator.archive_members_scanned + 1 > MAX_ARCHIVE_MEMBERS:
                    raise PrivacyScanError(
                        "PRIVACY_SCAN_ARCHIVE_MEMBER_LIMIT_EXCEEDED", member_location
                    )
                if (
                    accumulator.archive_uncompressed_bytes + member.file_size
                    > MAX_ARCHIVE_UNCOMPRESSED_BYTES
                ):
                    raise PrivacyScanError(
                        "PRIVACY_SCAN_ARCHIVE_BYTES_EXCEEDED", member_location
                    )
                member_data = archive.read(member)
                if len(member_data) != member.file_size:
                    raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_MEMBER_TRUNCATED", member_location)
                _record_payload(
                    member_data,
                    member_location,
                    accumulator,
                    archive_member=True,
                )
                member_suffix = PurePosixPath(member.filename).suffix
                if _archive_candidate(member_suffix, member_data):
                    _scan_archive(
                        member_data,
                        member_location,
                        member_suffix,
                        accumulator,
                        depth=depth + 1,
                    )
    except PrivacyScanError:
        raise
    except (OSError, RuntimeError, NotImplementedError, zipfile.BadZipFile) as error:
        raise PrivacyScanError("PRIVACY_SCAN_ARCHIVE_READ_FAILED", location) from error


def _record_payload(
    data: bytes,
    location: str,
    accumulator: _Accumulator,
    *,
    archive_member: bool,
) -> None:
    accumulator.files_scanned += 1
    accumulator.bytes_scanned += len(data)
    if archive_member:
        accumulator.archive_members_scanned += 1
        accumulator.archive_uncompressed_bytes += len(data)
    for canary_id, canary in CANARIES.items():
        if canary in data:
            accumulator.findings.add(CanaryFinding(canary_id, location))


def _archive_candidate(suffix: str, data: bytes) -> bool:
    return suffix.casefold() in ARCHIVE_SUFFIXES or data.startswith(ZIP_MAGICS)


def _redact_canaries(value: str) -> str:
    redacted = value
    for canary_id, canary in CANARIES.items():
        redacted = redacted.replace(canary.decode("utf-8"), f"[{canary_id}]")
    return redacted


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: scan_privacy_canaries.py PATH [PATH ...]", file=sys.stderr)
        return 2
    try:
        report = scan_paths(Path(value) for value in argv[1:])
    except PrivacyScanError as error:
        print(
            "PRIVACY_CANARY_SCAN_ERROR: "
            f"code={error.code} file={_redact_canaries(error.location)}",
            file=sys.stderr,
        )
        return 1
    if report.findings:
        for finding in report.findings:
            print(format_finding(finding), file=sys.stderr)
        return 1
    print(
        "privacy-canary-scan: PASS "
        f"files={report.files_scanned} bytes={report.bytes_scanned} "
        f"archive-members={report.archive_members_scanned} canaries={len(CANARIES)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
