#!/usr/bin/env python3
"""Scan production roots for prototype, local-machine, credential, and cache pollution."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


PRODUCTION_ROOTS = (
    ".github",
    "backend/src/main",
    "frontend",
    "contracts",
    "deploy",
    "release",
    "scripts",
)
SKIPPED_PATH_PREFIXES = ("scripts/tests/",)
FORBIDDEN_PATH_PARTS = {"node_modules", "dist", "mock", "mocks", "__pycache__"}
TEXT_SUFFIXES = {
    ".java", ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs",
    ".vue", ".html", ".css", ".json", ".yml", ".yaml", ".properties", ".xml",
    ".md", ".csv", ".example", ".sh", ".key", ".pem",
}
CODE_SUFFIXES = {
    ".java", ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs",
    ".vue", ".html", ".css", ".json", ".yml", ".yaml",
}
LOCAL_MACHINE_PATH = re.compile(r"(?:/Users/[^/\s]+/|/home/[^/\s]+/|[A-Za-z]:\\\\Users\\\\)")
LOCAL_ENDPOINT = re.compile(r"(?i)(?:https?://)?(?:localhost|127\.0\.0\.1|0\.0\.0\.0)(?::\d+)?")
RAW_CREDENTIAL = re.compile(
    r"(?im)(?:^|[{,]\s*)['\"]?(?:password|passwd|token|private[_-]?key|client[_-]?secret)['\"]?"
    r"\s*[:=]\s*['\"]?(?!\s*(?:$|[,}]|<|\$\{|secret://|ref:))[^,\s#'\"}]+"
)
PRIVATE_KEY_MATERIAL = re.compile(r"-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----")
PERSISTENT_CACHE = re.compile(
    r"(?i)(?:localStorage|sessionStorage|indexedDB|caches\.open|navigator\.serviceWorker|"
    r"pinia-plugin-persistedstate|persist\s*:)")
CREDENTIAL_KEYS = {"password", "passwd", "token", "private_key", "client_secret"}
APPROVED_TEST_LOOPBACKS = {
    "scripts/run_audit_postgresql_tests.sh": (
        's.bind(("127.0.0.1", 0))',
        "-h 127.0.0.1",
        "export PGHOST=127.0.0.1",
        "jdbc:postgresql://127.0.0.1:$PORT",
    ),
    "frontend/playwright.config.ts": (
        "http://127.0.0.1:4173",
        "--host 127.0.0.1 --port 4173",
        "127.0.0.1:4173",
        "http://127.0.0.1:4174",
        "--bind 0.0.0.0 --directory tests/host-fixture",
    ),
    "frontend/scripts/formal-web-harness.mjs": (
        "server.listen(0, '127.0.0.1'",
        "http://127.0.0.1:${address.port}",
    ),
    "frontend/tests/baseline/host-cross-origin.spec.ts": (
        "http://127.0.0.1:4173",
        "http://127.0.0.1:4174",
        "http://localhost:4174",
    ),
    "frontend/tests/host-fixture/index.html": (
        "http://127.0.0.1:4173",
    ),
}
APPROVED_PERSISTENCE_ASSERTIONS = {
    "frontend/tests/baseline/identity-shell.spec.ts",
    "frontend/tests/baseline/audit-search.spec.ts",
}
APPROVED_MIGRATION_PREFIX = "backend/src/main/resources/db/migration/"
NPMRC_FORBIDDEN = re.compile(
    r"(?im)^\s*(?://[^\s]+/:)?(?:_authToken|_password|username|cache|userconfig)\s*="
)


def scan(project_root: Path) -> list[str]:
    root = project_root.resolve()
    violations: list[str] = []
    for relative_root in PRODUCTION_ROOTS:
        production_root = root / relative_root
        if not production_root.is_dir():
            violations.append(f"PRODUCTION_ROOT_MISSING: {relative_root}")
            continue
        for entry in sorted(production_root.rglob("*")):
            relative = entry.relative_to(root)
            if any(relative.as_posix().startswith(prefix) for prefix in SKIPPED_PATH_PREFIXES):
                continue
            if entry.is_symlink():
                violations.append(f"PRODUCTION_SYMLINK_FORBIDDEN: {relative}")
                continue
            if any(part in FORBIDDEN_PATH_PARTS for part in relative.parts):
                violations.append(f"GENERATED_OR_PROTOTYPE_PATH: {relative}")
                continue
            if not entry.is_file():
                continue
            if (relative_root == "backend/src/main" and entry.suffix == ".sql"
                    and not relative.as_posix().startswith(APPROVED_MIGRATION_PREFIX)):
                violations.append(f"PREMATURE_PRODUCTION_MIGRATION: {relative}")
            suffix = entry.suffix.lower()
            if suffix not in TEXT_SUFFIXES and not entry.name.lower().startswith(".env") and entry.name != ".npmrc":
                continue
            content = entry.read_text(encoding="utf-8")
            if LOCAL_MACHINE_PATH.search(content):
                violations.append(f"LOCAL_MACHINE_PATH_LITERAL: {relative}")
            local_scan_content = content
            for approved in APPROVED_TEST_LOOPBACKS.get(relative.as_posix(), ()):
                local_scan_content = local_scan_content.replace(approved, "APPROVED_TEST_LOOPBACK")
            if LOCAL_ENDPOINT.search(local_scan_content):
                violations.append(f"LOCAL_ENDPOINT_LITERAL: {relative}")
            if RAW_CREDENTIAL.search(content) or (suffix == ".json" and _json_has_raw_credential(content)):
                violations.append(f"RAW_CREDENTIAL_LITERAL: {relative}")
            if entry.name == ".npmrc" and NPMRC_FORBIDDEN.search(content):
                violations.append(f"RAW_CREDENTIAL_LITERAL: {relative}")
            if PRIVATE_KEY_MATERIAL.search(content):
                violations.append(f"PRIVATE_KEY_MATERIAL: {relative}")
            if suffix in CODE_SUFFIXES:
                if "docs/input/原型" in content or "/原型/" in content:
                    violations.append(f"PROTOTYPE_SOURCE_REFERENCE: {relative}")
                if (relative_root == "frontend" and PERSISTENT_CACHE.search(content)
                        and relative.as_posix() not in APPROVED_PERSISTENCE_ASSERTIONS):
                    violations.append(f"PERSISTENT_BUSINESS_CACHE: {relative}")
    return sorted(set(violations))


def _json_has_raw_credential(content: str) -> bool:
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        return False

    def contains(candidate: object) -> bool:
        if isinstance(candidate, dict):
            for key, item in candidate.items():
                normalized_key = key.casefold().replace("-", "_") if isinstance(key, str) else ""
                if normalized_key in CREDENTIAL_KEYS and _is_raw_credential_value(item):
                    return True
                if contains(item):
                    return True
        elif isinstance(candidate, list):
            return any(contains(item) for item in candidate)
        return False

    return contains(value)


def _is_raw_credential_value(value: object) -> bool:
    if value is None:
        return False
    rendered = str(value).strip()
    return bool(rendered) and not rendered.startswith(("<", "${", "secret://", "ref:"))


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    violations = scan(root)
    if violations:
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("production-pollution: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
