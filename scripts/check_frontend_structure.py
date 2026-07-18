#!/usr/bin/env python3
"""Validate the dependency-free frontend skeleton and prototype isolation rules."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


DOMAINS = {
    "identity-access",
    "subject-registry",
    "ingestion-quality",
    "rule-governance",
    "signal-evaluation",
    "clue-care",
    "collaboration",
    "reporting",
    "audit-operations",
}
REQUIRED = {
    "src/app/router/index.ts",
    "src/app/theme/index.ts",
    "src/app/config/index.ts",
    "src/components/README.md",
    "src/shared/README.md",
}
FORBIDDEN_DIRECTORY_NAMES = {"node_modules", "dist", "mock", "mocks", "auth", "store", "stores"}
CODE_SUFFIXES = {
    ".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs",
    ".vue", ".json", ".html", ".css",
}
LOCAL_ENDPOINT = re.compile(r"(?i)(?:localhost|127\.0\.0\.1|0\.0\.0\.0)(?::\d+)?")
PERSISTENT_BUSINESS_CACHE = re.compile(
    r"(?i)(?:localStorage|sessionStorage|indexedDB|caches\.open|navigator\.serviceWorker|"
    r"pinia-plugin-persistedstate|persist\s*:)")
JS_TRIVIA = r"(?:(?:\s+)|(?:/\*[\s\S]*?\*/)|(?://[^\n]*(?:\n|$)))*"
ENV_ROOT = rf"(?:import{JS_TRIVIA}\.{JS_TRIVIA}meta|process)"
ENV_DOT_ACCESS = rf"{JS_TRIVIA}(?:\.|\?\.){JS_TRIVIA}"
ENV_SOURCE = (
    ENV_ROOT
    + rf"(?:{ENV_DOT_ACCESS}env(?![A-Za-z0-9_$])"
    + rf"|{JS_TRIVIA}(?:\?\.)?{JS_TRIVIA}\[{JS_TRIVIA}(?:['\"]env['\"]|`env`){JS_TRIVIA}\])"
)
ENV_OBJECT = re.compile(ENV_SOURCE)
ENV_IDENTIFIER = r"[A-Za-z_$][A-Za-z0-9_$]*"
CLIENT_ENV = re.compile(ENV_SOURCE + ENV_DOT_ACCESS + rf"({ENV_IDENTIFIER})")
CLIENT_ENV_BRACKET = re.compile(
    ENV_SOURCE
    + JS_TRIVIA
    + r"(?:\?\.)?"
    + JS_TRIVIA
    + r"\["
    + JS_TRIVIA
    + r"(?:['\"]([^'\"]+)['\"]|`([^`$]+)`)"
    + JS_TRIVIA
    + r"\]"
)
CLIENT_ENV_DESTRUCTURE = re.compile(
    r"\{([^{}]+)\}" + JS_TRIVIA + r"=" + JS_TRIVIA + ENV_SOURCE + r"(?![A-Za-z0-9_$])"
)
DESTRUCTURED_ENV_ENTRY = re.compile(
    rf"\s*({ENV_IDENTIFIER})(?:\s*:\s*{ENV_IDENTIFIER})?(?:\s*=\s*[^,]+)?\s*"
)
STATIC_IMPORT = re.compile(r"(?m)^\s*(?:import|export)\b[^'\"]*['\"]([^'\"]+)['\"]")
IMPORT_COMMENTS = r"(?:(?:/\*.*?\*/|//[^\n]*(?:\n|$))\s*)*"
DYNAMIC_IMPORT_CALL = re.compile(
    r"(?<![.\w$])import\b\s*" + IMPORT_COMMENTS + r"\(",
    re.DOTALL,
)
STATIC_DYNAMIC_IMPORT = re.compile(
    r"(?<![.\w$])import\b\s*" + IMPORT_COMMENTS + r"\(\s*" + IMPORT_COMMENTS
    + r"(?:['\"]([^'\"]+)['\"]|`([^`$]+)`)"
    + r"(?=\s*" + IMPORT_COMMENTS + r"(?:,|\)))",
    re.DOTALL,
)


def validate(frontend_root: Path) -> list[str]:
    root = frontend_root.resolve()
    violations: list[str] = []
    if not root.is_dir():
        return [f"FRONTEND_ROOT_MISSING: {frontend_root}"]

    for relative in sorted(REQUIRED):
        if not (root / relative).is_file():
            violations.append(f"REQUIRED_EXTENSION_POINT_MISSING: {relative}")

    domains_root = root / "src/domains"
    actual_domains = {entry.name for entry in domains_root.iterdir() if entry.is_dir()} if domains_root.is_dir() else set()
    if actual_domains != DOMAINS:
        violations.append(
            "DOMAIN_SET_MISMATCH: expected="
            + ",".join(sorted(DOMAINS))
            + " actual="
            + ",".join(sorted(actual_domains))
        )
    for domain in sorted(DOMAINS):
        if not (domains_root / domain / "index.ts").is_file():
            violations.append(f"DOMAIN_PUBLIC_ENTRY_MISSING: {domain}/index.ts")

    if not (root / "package-lock.json").is_file():
        violations.append("PRODUCTION_LOCK_REQUIRED: package-lock.json")
    for forbidden in ("npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock"):
        if (root / forbidden).exists():
            violations.append(f"ALTERNATE_PRODUCTION_LOCK: {forbidden}")

    allowlist = _load_allowlist(root, violations)
    if not (root / "src").is_dir():
        return sorted(set(violations + ["SOURCE_ROOT_MISSING: src"]))

    for path in sorted((root / "src").rglob("*")):
        relative = path.relative_to(root)
        if path.is_dir():
            if path.name in FORBIDDEN_DIRECTORY_NAMES:
                violations.append(f"PROTOTYPE_DIRECTORY_FORBIDDEN: {relative}")
            continue
        if path.suffix not in CODE_SUFFIXES:
            continue
        content = path.read_text(encoding="utf-8")
        if "docs/input/原型" in content or "/原型/" in content:
            violations.append(f"PROTOTYPE_PATH_REFERENCE: {relative}")
        if LOCAL_ENDPOINT.search(content):
            violations.append(f"LOCAL_ENDPOINT_FORBIDDEN: {relative}")
        if PERSISTENT_BUSINESS_CACHE.search(content):
            violations.append(f"PERSISTENT_BUSINESS_CACHE_FORBIDDEN: {relative}")
        client_variables, has_ambiguous_client_env = _client_environment_accesses(content)
        if has_ambiguous_client_env:
            violations.append(f"CLIENT_ENV_ACCESS_NOT_STATIC: {relative}")
        for variable in sorted(client_variables):
            if variable not in allowlist:
                violations.append(f"CLIENT_ENV_NOT_ALLOWLISTED: {relative} -> {variable}")
        violations.extend(_cross_domain_imports(root, path, content))

    return sorted(set(violations))


def _client_environment_variables(content: str) -> set[str]:
    return _client_environment_accesses(content)[0]


def _client_environment_accesses(content: str) -> tuple[set[str], bool]:
    variables = set(CLIENT_ENV.findall(content))
    variables.update(quoted or template for quoted, template in CLIENT_ENV_BRACKET.findall(content))
    safe_spans = [match.span() for match in CLIENT_ENV.finditer(content)]
    safe_spans.extend(match.span() for match in CLIENT_ENV_BRACKET.finditer(content))

    for match in CLIENT_ENV_DESTRUCTURE.finditer(content):
        names = _destructured_environment_names(match.group(1))
        if names is None:
            continue
        variables.update(names)
        safe_spans.append(match.span())

    ambiguous = any(
        not any(safe_start <= source.start() and source.end() <= safe_end for safe_start, safe_end in safe_spans)
        for source in ENV_OBJECT.finditer(content)
    )
    return variables, ambiguous


def _destructured_environment_names(value: str) -> set[str] | None:
    names: set[str] = set()
    entries = value.split(",")
    if entries and not entries[-1].strip():
        entries.pop()
    for entry in entries:
        if not entry.strip() or "..." in entry:
            return None
        match = DESTRUCTURED_ENV_ENTRY.fullmatch(entry)
        if match is None:
            return None
        names.add(match.group(1))
    return names


def _load_allowlist(root: Path, violations: list[str]) -> set[str]:
    path = root / "src/app/config/client-env-allowlist.json"
    if not path.exists():
        return set()
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        violations.append(f"CLIENT_ENV_ALLOWLIST_INVALID: {error.__class__.__name__}")
        return set()
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        violations.append("CLIENT_ENV_ALLOWLIST_INVALID: expected string array")
        return set()
    return set(value)


def _cross_domain_imports(root: Path, source: Path, content: str) -> list[str]:
    result: list[str] = []
    domains_root = (root / "src/domains").resolve()
    try:
        source_domain = source.resolve().relative_to(domains_root).parts[0]
    except (ValueError, IndexError):
        source_domain = None

    static_dynamic_imports = list(STATIC_DYNAMIC_IMPORT.finditer(content))
    safe_dynamic_starts = {match.start() for match in static_dynamic_imports}
    if any(match.start() not in safe_dynamic_starts for match in DYNAMIC_IMPORT_CALL.finditer(content)):
        result.append(f"DYNAMIC_IMPORT_SPECIFIER_NOT_STATIC: {source.relative_to(root)}")
    dynamic_specifiers = {
        quoted or template
        for match in static_dynamic_imports
        for quoted, template in (match.groups(),)
    }
    specifiers = set(STATIC_IMPORT.findall(content)) | dynamic_specifiers
    for specifier in sorted(specifiers):
        target_domain: str | None = None
        internal_suffix = ""
        if specifier.startswith("@/domains/"):
            parts = specifier.removeprefix("@/domains/").split("/")
            target_domain = parts[0]
            internal_suffix = "/".join(parts[1:])
        elif specifier.startswith("."):
            resolved = (source.parent / specifier).resolve()
            try:
                parts = resolved.relative_to(domains_root).parts
            except ValueError:
                continue
            if parts:
                target_domain = parts[0]
                internal_suffix = "/".join(parts[1:])
        if target_domain in DOMAINS and target_domain != source_domain:
            if internal_suffix not in {"", "index", "index.ts"}:
                result.append(
                    f"CROSS_DOMAIN_INTERNAL_IMPORT: {source.relative_to(root)} -> {specifier}"
                )
    return result


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path("frontend")
    violations = validate(root)
    if violations:
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("frontend-structure: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
