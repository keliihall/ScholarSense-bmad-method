#!/usr/bin/env python3
"""审计生产资产与 PAB 候选，并校验受控输入漂移。

本脚本只使用 Python 标准库；不会安装依赖、修改原型或访问网络。
"""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import shlex
import shutil
import stat
import sys
import tempfile
import threading
import uuid
from copy import deepcopy
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any, Callable
from urllib.parse import unquote_plus, urlsplit


SCRIPT_VERSION = "2.9.0"
SCHEMA_VERSION = "3.2.0"
CLASSIFICATIONS = [
    "reuse-as-is",
    "migrate",
    "reference-only",
    "replace",
    "unknown-blocked",
]
OWNER_STORIES = ["1.1b", "1.1c", "1.1d"]
REQUIRED_ASSET_IDS = {
    "planning.architecture-spine",
    "planning.pab-1.0.0",
    "workspace.git-repository",
    "workspace.production-backend",
    "workspace.production-frontend",
    "workspace.contract-definitions",
    "workspace.ci-definitions",
    "workspace.deployment-engineering",
    "prototype.source",
    "prototype.routes",
    "prototype.layouts",
    "prototype.components",
    "prototype.mocks",
    "prototype.stores",
    "prototype.types",
    "prototype.styles",
    "prototype.static-assets",
    "prototype.config",
    "prototype.package-manifest",
    "prototype.lockfile",
    "prototype.dist",
    "prototype.node-modules",
}
REQUIRED_READABLE_ASSET_IDS = {
    "planning.architecture-spine",
    "planning.pab-1.0.0",
    "prototype.package-manifest",
    "prototype.lockfile",
    "prototype.source",
}
REQUIRED_PROTOTYPE_EXTRAS = {
    "@element-plus/icons-vue",
    "@types/node",
    "@vitejs/plugin-vue",
    "axios",
}
DEPENDENCY_SECTIONS = (
    "dependencies",
    "devDependencies",
    "optionalDependencies",
    "peerDependencies",
)
IGNORED_NAMES = {
    ".DS_Store",
    ".cache",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".vite",
    ".tmp",
    ".bin",
    ".npm",
    ".pnpm",
    ".turbo",
    ".yarn",
    "__pycache__",
    "coverage",
}
WORKSPACE_DISCOVERY_EXCLUDED_ROOTS = {
    ".agents",
    ".git",
    "_bmad",
    "_bmad-output",
    "docs",
}
WORKSPACE_DISCOVERY_PRUNED_DIRECTORIES = {
    ".git",
    ".hg",
    ".svn",
    ".venv",
    "build",
    "dist",
    "node_modules",
    "target",
    "venv",
}
BACKEND_PROJECT_FINGERPRINTS = {
    "build.gradle",
    "build.gradle.kts",
    "gradlew",
    "mvnw",
    "pom.xml",
    "settings.gradle",
    "settings.gradle.kts",
}
FRONTEND_PROJECT_FINGERPRINTS = {
    "angular.json",
    "astro.config.js",
    "astro.config.mjs",
    "astro.config.ts",
    "next.config.js",
    "next.config.mjs",
    "next.config.ts",
    "nuxt.config.js",
    "nuxt.config.ts",
    "vite.config.js",
    "vite.config.mjs",
    "vite.config.mts",
    "vite.config.ts",
}
FRONTEND_SOURCE_ROOTS = {
    "app",
    "components",
    "pages",
    "public",
    "src",
}
FRONTEND_SNAPSHOT_PRUNED_DIRECTORIES = {
    ".astro",
    ".next",
    ".nuxt",
    ".output",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
}
CONTRACT_ROOT_FINGERPRINTS = {
    "asyncapi.json",
    "asyncapi.yaml",
    "asyncapi.yml",
    "openapi.json",
    "openapi.yaml",
    "openapi.yml",
    "schema.json",
    "swagger.json",
    "swagger.yaml",
    "swagger.yml",
}
CI_ROOT_FINGERPRINTS = {
    ".drone.yml",
    ".gitlab-ci.yml",
    ".travis.yml",
    ".travis.yaml",
    "Jenkinsfile",
    "appveyor.yml",
    "appveyor.yaml",
    "azure-pipelines.yml",
    "bitbucket-pipelines.yml",
}
DEPLOYMENT_ROOT_FINGERPRINTS = {
    "Containerfile",
    "Dockerfile",
    "compose.yaml",
    "compose.yml",
    "docker-compose.yaml",
    "docker-compose.yml",
    "terragrunt.hcl",
}
DEPLOYMENT_FIRST_LEVEL_FINGERPRINTS = DEPLOYMENT_ROOT_FINGERPRINTS | {
    "Chart.yaml",
    "kustomization.yaml",
}
SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"
DATETIME_PATTERN = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})$"
GENERATION_ID_PATTERN = r"^sha256:[0-9a-f]{64}$"
EMPTY_SHA256 = "sha256:" + "0" * 64


def _stable_text_key(value: str) -> tuple[str, str]:
    """为大小写折叠相同的合法名称提供全序，避免集合迭代顺序泄漏。"""

    return value.casefold(), value


class AuditError(RuntimeError):
    """可定位且适合命令行显示的审计失败。"""


class PabOracleError(AuditError):
    """PAB 表或授权正文无法形成有效语义 oracle。"""


class DependencyInputError(AuditError):
    """多个依赖输入同时失败；保留结构化失败类型并安全聚合消息。"""

    def __init__(self, failures: list[tuple[str, AuditError]]) -> None:
        self.failures = tuple(failures)
        self.pab_oracle_failed = any(
            isinstance(error, PabOracleError) for _, error in failures
        )
        safe_messages: list[str] = []
        for label, error in failures:
            detail = (
                "PAB oracle 语义校验失败（详细输入值已隐藏）"
                if isinstance(error, PabOracleError)
                else str(error)
            )
            safe_messages.append(f"{label}：{detail}")
        super().__init__("；".join(safe_messages))


def _has_pab_oracle_failure(error: AuditError | None) -> bool:
    return isinstance(error, PabOracleError) or (
        isinstance(error, DependencyInputError) and error.pab_oracle_failed
    )


def _safe_audit_error_detail(error: AuditError) -> str:
    if isinstance(error, PabOracleError):
        return "PAB oracle 语义校验失败（详细输入值已隐藏）"
    return str(error)


class _DuplicateJsonKeyError(ValueError):
    """JSON 对象包含重复键。"""


class _NonFiniteJsonConstantError(ValueError):
    """JSON 包含 RFC 8259 不允许的非有限数字常量。"""


class _JsonIntegerRangeError(ValueError):
    """JSON 整数超出本契约的确定性解析上限。"""


def _strict_json_loads(text: str) -> Any:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise _DuplicateJsonKeyError("duplicate JSON key")
            result[key] = value
        return result

    def reject_non_finite(value: str) -> Any:
        raise _NonFiniteJsonConstantError(value)

    def parse_bounded_integer(value: str) -> int:
        if len(value.lstrip("-")) > 256:
            raise _JsonIntegerRangeError("JSON integer exceeds 256 digits")
        try:
            return int(value)
        except ValueError as exc:  # pragma: no cover - json scanner normally guarantees digits
            raise _JsonIntegerRangeError("invalid JSON integer") from exc

    return json.loads(
        text,
        object_pairs_hook=reject_duplicates,
        parse_constant=reject_non_finite,
        parse_int=parse_bounded_integer,
    )


@dataclass(frozen=True)
class AuditConfig:
    workspace_root: Path
    prototype_root: Path
    architecture_source: Path
    pab_source: Path
    generated_at: str


@dataclass(frozen=True)
class ArtifactPaths:
    inventory: Path
    schema: Path
    gap: Path


@dataclass(frozen=True)
class PublicationTargetState:
    existed: bool
    digest: str | None
    mode: int | None


@dataclass(frozen=True)
class ControlledSnapshot:
    digest: str
    input_scope: tuple[str, ...]
    file_contents: tuple[tuple[str, bytes], ...] = ()
    file_errors: tuple[tuple[str, str], ...] = ()
    entries: tuple[tuple[str, str, str, int], ...] = ()
    path_states: tuple[tuple[str, str, str], ...] = ()
    asset_candidates: tuple[tuple[str, str], ...] = ()
    snapshot_metrics: tuple[tuple[str, int, int], ...] = ()


def schema_document(generation_id: str | None = None) -> dict[str, Any]:
    """返回关闭未知字段的 JSON Schema 2020-12 契约。"""

    document: dict[str, Any] = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": f"urn:scholarsense:production-asset-inventory:{SCHEMA_VERSION}",
        "title": "ScholarSense 生产资产审计清单",
        "type": "object",
        "additionalProperties": False,
        "required": [
            "schemaVersion",
            "auditVersion",
            "generationId",
            "generatedAt",
            "verificationDate",
            "controlledInputDigest",
            "assets",
            "unresolvedItems",
            "executionEvidence",
        ],
        "properties": {
            "schemaVersion": {"const": SCHEMA_VERSION},
            "auditVersion": {"type": "string", "minLength": 1},
            "generationId": {"type": "string", "pattern": GENERATION_ID_PATTERN},
            "generatedAt": {
                "type": "string",
                "pattern": DATETIME_PATTERN,
                "format": "date-time",
            },
            "verificationDate": {
                "type": "string",
                "pattern": DATE_PATTERN,
                "format": "date",
            },
            "controlledInputDigest": {"type": "string", "pattern": SHA256_PATTERN},
            "assets": {
                "type": "array",
                "minItems": 1,
                "items": {"$ref": "#/$defs/asset"},
            },
            "unresolvedItems": {
                "type": "array",
                "items": {"$ref": "#/$defs/unresolvedItem"},
            },
            "executionEvidence": {"$ref": "#/$defs/executionEvidence"},
        },
        "$defs": {
            "asset": {
                "type": "object",
                "additionalProperties": False,
                "required": [
                    "assetId",
                    "type",
                    "pathOrSource",
                    "owner",
                    "versionOrDigest",
                    "verifiedDate",
                    "evidenceLinks",
                    "classification",
                    "recommendedDisposition",
                    "dispositionReason",
                    "risk",
                    "ownerStory",
                    "present",
                    "readStatus",
                    "snapshotOnly",
                    "checkedPaths",
                ],
                "properties": {
                    "assetId": {"type": "string", "pattern": "^[a-z0-9][a-z0-9._-]+$"},
                    "type": {"type": "string", "minLength": 1},
                    "pathOrSource": {"type": "string", "minLength": 1},
                    "owner": {"type": "string", "minLength": 1},
                    "versionOrDigest": {"type": "string", "minLength": 1},
                    "verifiedDate": {
                        "type": "string",
                        "pattern": DATE_PATTERN,
                        "format": "date",
                    },
                    "evidenceLinks": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "minLength": 1},
                    },
                    "classification": {"type": "string", "enum": CLASSIFICATIONS},
                    "recommendedDisposition": {"type": "string", "enum": CLASSIFICATIONS[:-1]},
                    "dispositionReason": {"type": "string", "minLength": 1},
                    "risk": {"type": "string", "minLength": 1},
                    "ownerStory": {"type": "string", "enum": OWNER_STORIES},
                    "present": {"type": "boolean"},
                    "readStatus": {"enum": ["readable", "missing", "unreadable"]},
                    "snapshotOnly": {"type": "boolean"},
                    "checkedPaths": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "minLength": 1},
                    },
                    "metrics": {
                        "oneOf": [
                            {
                                "type": "object",
                                "additionalProperties": False,
                                "required": [
                                    "scope",
                                    "regularFileCount",
                                    "regularFileByteCount",
                                ],
                                "properties": {
                                    "scope": {"const": "recursive-regular-files"},
                                    "regularFileCount": {
                                        "type": "integer",
                                        "minimum": 0,
                                    },
                                    "regularFileByteCount": {
                                        "type": "integer",
                                        "minimum": 0,
                                    },
                                },
                            },
                            {
                                "type": "object",
                                "additionalProperties": False,
                                "required": [
                                    "scope",
                                    "topLevelEntryCount",
                                    "topLevelRegularFileByteCount",
                                ],
                                "properties": {
                                    "scope": {"const": "top-level-entries"},
                                    "topLevelEntryCount": {
                                        "type": "integer",
                                        "minimum": 0,
                                    },
                                    "topLevelRegularFileByteCount": {
                                        "type": "integer",
                                        "minimum": 0,
                                    },
                                },
                            },
                        ],
                    },
                },
            },
            "unresolvedItem": {
                "type": "object",
                "additionalProperties": False,
                "required": ["assetId", "issue", "impact", "ownerStory"],
                "properties": {
                    "assetId": {"type": "string", "minLength": 1},
                    "issue": {"type": "string", "minLength": 1},
                    "impact": {"type": "string", "minLength": 1},
                    "ownerStory": {"type": "string", "enum": OWNER_STORIES},
                },
            },
            "executionEvidence": {
                "type": "object",
                "additionalProperties": False,
                "required": [
                    "generateCommand",
                    "replayCommand",
                    "scriptVersion",
                    "scriptSha256",
                    "inputScope",
                    "result",
                    "resultScope",
                    "acceptanceEvidenceLocation",
                    "steps",
                    "unresolvedCount",
                ],
                "properties": {
                    "generateCommand": {"type": "string", "minLength": 1},
                    "replayCommand": {"type": "string", "minLength": 1},
                    "scriptVersion": {"type": "string", "minLength": 1},
                    "scriptSha256": {"type": "string", "pattern": SHA256_PATTERN},
                    "inputScope": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string", "minLength": 1},
                    },
                    "result": {"enum": ["passed", "failed"]},
                    "resultScope": {
                        "enum": ["generation-and-in-memory-validation", "input-audit-failed"]
                    },
                    "acceptanceEvidenceLocation": {"const": "Story Dev Agent Record"},
                    "steps": {
                        "type": "array",
                        "minItems": 3,
                        "items": {"$ref": "#/$defs/executionStep"},
                    },
                    "unresolvedCount": {"type": "integer", "minimum": 0},
                },
            },
            "executionStep": {
                "type": "object",
                "additionalProperties": False,
                "required": ["name", "command", "status", "exitCode"],
                "properties": {
                    "name": {"enum": ["generate", "schema-validation", "gap-validation"]},
                    "command": {"type": "string", "minLength": 1},
                    "status": {"enum": ["passed", "failed", "not-run"]},
                    "exitCode": {"type": ["integer", "null"]},
                },
            },
        },
    }
    if generation_id is not None:
        if re.fullmatch(GENERATION_ID_PATTERN, generation_id) is None:
            raise AuditError("generation ID 格式无效")
        document["x-generationId"] = generation_id
    return document


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _read_bytes(path: Path) -> bytes:
    mode = path.stat(follow_symlinks=False).st_mode
    if stat.S_ISLNK(mode):
        raise AuditError(f"拒绝读取符号链接：{path.name}")
    if not stat.S_ISREG(mode):
        raise AuditError(f"拒绝读取非普通文件：{path.name}")
    if mode & 0o444 == 0:
        raise AuditError(f"文件无读取权限：{path.name}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise AuditError(f"读取失败：{path.name}（{exc.__class__.__name__}）") from exc
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise AuditError(f"拒绝读取非普通文件：{path.name}")
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            return handle.read()
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _stream_file_digest(path: Path) -> tuple[str, int]:
    """用固定内存读取普通文件，并在打开后再次核验类型。"""

    mode = path.stat(follow_symlinks=False).st_mode
    if stat.S_ISLNK(mode):
        raise AuditError(f"拒绝读取符号链接：{path.name}")
    if not stat.S_ISREG(mode):
        raise AuditError(f"拒绝读取非普通文件：{path.name}")
    if mode & 0o444 == 0:
        raise AuditError(f"文件无读取权限：{path.name}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise AuditError(f"读取失败：{path.name}（{exc.__class__.__name__}）") from exc
    hasher = hashlib.sha256()
    byte_count = 0
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise AuditError(f"拒绝读取非普通文件：{path.name}")
        with os.fdopen(descriptor, "rb") as handle:
            descriptor = -1
            while chunk := handle.read(1024 * 1024):
                hasher.update(chunk)
                byte_count += len(chunk)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    return hasher.hexdigest(), byte_count


def _behavior_mode_token(mode: int) -> str:
    """绑定会改变读写、执行、遍历或特殊执行语义的 POSIX 权限位。"""

    return f"mode:{stat.S_IMODE(mode) & 0o7777:04o}"


def _require_directory_read_and_traverse(mode: int, label: str) -> None:
    if mode & 0o444 == 0 or mode & 0o111 == 0:
        raise AuditError(f"目录缺少读取或遍历权限：{label}")


def _walk_directory(
    directory: Path,
    *,
    excluded_roots: tuple[Path, ...] = (),
) -> list[Path]:
    """显式遍历目录并拒绝任何不可读子目录，避免 Path.rglob 静默跳过。"""

    excluded = tuple(path.resolve(strict=False) for path in excluded_roots)
    entries: list[Path] = []
    pending = [directory]
    while pending:
        current = pending.pop()
        mode = current.stat(follow_symlinks=False).st_mode
        if not stat.S_ISDIR(mode):
            raise AuditError(f"目录遍历遇到非目录：{current.name}")
        _require_directory_read_and_traverse(mode, current.name)
        try:
            with os.scandir(current) as iterator:
                children = sorted(iterator, key=lambda item: item.name)
        except OSError as exc:
            raise AuditError(f"目录读取失败：{current.name}（{exc.__class__.__name__}）") from exc
        child_directories: list[Path] = []
        for child in children:
            path = Path(child.path)
            resolved = path.resolve(strict=False)
            if any(
                resolved == excluded_root or _is_relative_to(resolved, excluded_root)
                for excluded_root in excluded
            ):
                continue
            entries.append(path)
            try:
                if child.is_dir(follow_symlinks=False):
                    child_directories.append(path)
            except OSError as exc:
                raise AuditError(
                    f"目录项读取失败：{child.name}（{exc.__class__.__name__}）"
                ) from exc
        pending.extend(reversed(child_directories))
    return sorted(entries, key=lambda path: path.as_posix())


def summarize_path(
    path: Path,
    workspace_root: Path,
    *,
    reject_symlinks: bool = True,
    ignore_volatile_names: bool = False,
) -> dict[str, int | str]:
    """用稳定路径和内容计算摘要；忽略时间戳、缓存与 .DS_Store。"""

    root = workspace_root.resolve()
    lexical_candidate = path.absolute()
    if lexical_candidate.is_symlink():
        raise AuditError(f"拒绝读取符号链接：{lexical_candidate.name}")
    candidate = lexical_candidate.resolve(strict=False)
    if not _is_relative_to(candidate, root):
        raise AuditError("审计路径逃逸工作区")
    if not candidate.exists() and not candidate.is_symlink():
        raise AuditError(f"输入路径不存在：{candidate.relative_to(root).as_posix()}")
    candidate_mode = candidate.stat(follow_symlinks=False).st_mode
    if stat.S_ISDIR(candidate_mode):
        _require_directory_read_and_traverse(candidate_mode, candidate.name)
    if not stat.S_ISDIR(candidate_mode) and not stat.S_ISREG(candidate_mode):
        raise AuditError(f"拒绝读取非普通文件：{candidate.name}")

    hasher = hashlib.sha256()
    file_count = 0
    byte_count = 0
    entries = (
        [candidate]
        if candidate.is_file()
        else [candidate, *_walk_directory(candidate)]
    )
    for entry in entries:
        relative_to_candidate = entry.relative_to(candidate if candidate.is_dir() else candidate.parent)
        if ".DS_Store" in relative_to_candidate.parts:
            continue
        if ignore_volatile_names and any(
            part in IGNORED_NAMES for part in relative_to_candidate.parts
        ):
            continue
        if entry.is_symlink():
            if reject_symlinks:
                raise AuditError(f"拒绝读取符号链接：{relative_to_candidate.as_posix()}")
            rel = entry.relative_to(root).as_posix().encode("utf-8")
            hasher.update(rel + b"\0SYMLINK-IGNORED\n")
            file_count += 1
            continue
        if entry.is_dir():
            rel = entry.relative_to(root).as_posix().encode("utf-8")
            mode_token = _behavior_mode_token(
                entry.stat(follow_symlinks=False).st_mode
            ).encode("ascii")
            hasher.update(rel + b"\0DIRECTORY\0" + mode_token + b"\n")
            continue
        digest_value, size = _stream_file_digest(entry)
        rel = entry.relative_to(root).as_posix().encode("utf-8")
        digest = digest_value.encode("ascii")
        mode_token = _behavior_mode_token(
            entry.stat(follow_symlinks=False).st_mode
        ).encode("ascii")
        hasher.update(rel + b"\0" + digest + b"\0" + mode_token + b"\n")
        file_count += 1
        byte_count += size

    return {
        "digest": f"sha256:{hasher.hexdigest()}",
        "fileCount": file_count,
        "byteCount": byte_count,
    }


def canonical_inventory(inventory: dict[str, Any]) -> str:
    """移除允许变化的生成/核验日期后，返回可逐字节比较的规范化 JSON。"""

    normalized = deepcopy(inventory)
    normalized.pop("generationId", None)
    normalized.pop("generatedAt", None)
    normalized.pop("verificationDate", None)
    for asset in normalized.get("assets", []):
        if isinstance(asset, dict):
            asset.pop("verifiedDate", None)
    execution = normalized.get("executionEvidence")
    if isinstance(execution, dict):
        for field in ("generateCommand", "replayCommand"):
            execution.pop(field, None)
        steps = execution.get("steps")
        if isinstance(steps, list):
            for step in steps:
                if isinstance(step, dict):
                    step.pop("command", None)
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _snapshot_metrics_receipt(inventory: dict[str, Any]) -> str:
    """绑定生成时 snapshotOnly 指标到配套 gap，避免指标被单独改写。"""

    metrics = [
        {
            "assetId": asset.get("assetId"),
            "metrics": asset.get("metrics"),
        }
        for asset in inventory.get("assets", [])
        if isinstance(asset, dict) and asset.get("snapshotOnly") is True
    ]
    payload = {
        "generationId": inventory.get("generationId"),
        "controlledInputDigest": inventory.get("controlledInputDigest"),
        "snapshotOnlyMetrics": metrics,
    }
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return "sha256:" + hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _relative(path: Path, root: Path) -> str:
    resolved_root = root.resolve()
    resolved = path.resolve(strict=False)
    if not _is_relative_to(resolved, resolved_root):
        raise AuditError("配置路径逃逸工作区")
    return resolved.relative_to(resolved_root).as_posix() or "."


def _existing_paths(candidates: list[Path]) -> list[Path]:
    unique = {path.resolve(strict=False): path for path in candidates if path.exists() or path.is_symlink()}
    return sorted(unique.values(), key=lambda path: path.as_posix())


def _combined_digest(summaries: list[tuple[str, str]]) -> str:
    hasher = hashlib.sha256()
    for relative, digest in sorted(summaries):
        hasher.update(relative.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n")
    return f"sha256:{hasher.hexdigest()}"


def _non_overlapping_paths(paths: list[Path]) -> list[Path]:
    """保留最小覆盖根，避免同一资产的父子候选重复摘要和计数。"""

    selected: list[Path] = []
    for path in sorted(
        paths,
        key=lambda candidate: (
            len(candidate.resolve(strict=False).parts),
            candidate.as_posix(),
        ),
    ):
        resolved = path.resolve(strict=False)
        if any(
            resolved == root or _is_relative_to(resolved, root)
            for root in (item.resolve(strict=False) for item in selected)
        ):
            continue
        selected.append(path)
    return sorted(selected, key=lambda path: path.as_posix())


def _prototype_config_candidates(prototype: Path) -> list[Path]:
    """返回会影响原型行为或工具链的根级配置输入，包含缺失候选与新发现文件。"""

    fixed_names = {
        ".env.example",
        ".eslintrc",
        ".eslintrc.cjs",
        ".eslintrc.js",
        ".eslintrc.json",
        ".nvmrc",
        ".prettierrc",
        ".prettierrc.json",
        ".prettierrc.js",
        "index.html",
        "eslint.config.js",
        "eslint.config.mjs",
        "eslint.config.ts",
        "prettier.config.js",
        "prettier.config.mjs",
        "playwright.config.js",
        "playwright.config.mjs",
        "playwright.config.ts",
        "postcss.config.cjs",
        "postcss.config.js",
        "postcss.config.mjs",
        "postcss.config.ts",
        "vitest.config.js",
        "vitest.config.mjs",
        "vitest.config.ts",
        "cypress.config.js",
        "cypress.config.mjs",
        "cypress.config.ts",
        "jest.config.js",
        "jest.config.ts",
        "tailwind.config.cjs",
        "tailwind.config.js",
        "tailwind.config.mjs",
        "tailwind.config.ts",
        "wdio.conf.js",
        "wdio.conf.ts",
        "tsconfig.json",
        "tsconfig.app.json",
        "tsconfig.node.json",
        "vite.config.js",
        "vite.config.mjs",
        "vite.config.mts",
        "vite.config.ts",
    }
    discovered: set[Path] = set()
    for pattern in (
        ".env*",
        ".eslintrc*",
        ".prettierrc*",
        "eslint.config.*",
        "prettier.config.*",
        "playwright.config.*",
        "postcss.config.*",
        "vitest.config.*",
        "cypress.config.*",
        "jest.config.*",
        "tailwind.config.*",
        "wdio.conf.*",
        "tsconfig*.json",
        "vite.config.*",
    ):
        discovered.update(prototype.glob(pattern))
    return sorted(
        {prototype / name for name in fixed_names} | discovered,
        key=lambda path: path.as_posix(),
    )


def _prototype_subasset_candidates(prototype: Path) -> list[Path]:
    """列出由原型父快照派生、但仍需冻结存在状态的细分资产候选。"""

    return [
        prototype / "src",
        prototype / "src/router",
        prototype / "src/routes",
        prototype / "src/layouts",
        prototype / "src/layout",
        prototype / "src/components",
        prototype / "src/mock",
        prototype / "src/mocks",
        prototype / "src/stores",
        prototype / "src/store",
        prototype / "src/types",
        prototype / "src/styles",
        prototype / "src/style.css",
        prototype / "src/assets",
        prototype / "public",
    ]


def _is_published_audit_schema(path: Path) -> bool:
    """识别本工具既有 Schema 产物，避免把审计证据反向盘点为业务契约。"""

    if not path.name.endswith(".json") or not path.is_file():
        return False
    try:
        value = _parse_json_bytes(_read_bytes(path), path.as_posix())
    except (AuditError, OSError):
        return False
    return isinstance(value, dict) and value.get("$id") == (
        f"urn:scholarsense:production-asset-inventory:{SCHEMA_VERSION}"
    )


def _is_contract_fingerprint(path: Path) -> bool:
    return path.name in CONTRACT_ROOT_FINGERPRINTS or path.name.endswith(
        ".schema.json"
    )


def _docs_contract_candidates(
    root: Path,
    *,
    excluded_roots: tuple[Path, ...] = (),
) -> set[Path]:
    """只在 docs/** 中发现精确契约指纹，不把文档树泛化为生产工程。"""

    docs_root = root / "docs"
    if not docs_root.exists() and not docs_root.is_symlink():
        return set()
    excluded = tuple(path.resolve(strict=False) for path in excluded_roots)

    def is_excluded(path: Path) -> bool:
        resolved = path.resolve(strict=False)
        return any(
            resolved == excluded_root or _is_relative_to(resolved, excluded_root)
            for excluded_root in excluded
        )

    candidates: set[Path] = set()
    pending = [docs_root]
    while pending:
        current = pending.pop()
        if is_excluded(current):
            continue
        try:
            mode = current.stat(follow_symlinks=False).st_mode
        except OSError:
            candidates.add(current)
            continue
        if not stat.S_ISDIR(mode):
            if _is_contract_fingerprint(current):
                candidates.add(current)
            continue
        try:
            _require_directory_read_and_traverse(mode, current.name)
        except AuditError:
            candidates.add(current)
            continue
        try:
            with os.scandir(current) as iterator:
                children = sorted(iterator, key=lambda item: item.name)
        except OSError:
            # 无法证明该 docs 子树没有契约；作为不可读候选形成 blocked 事实。
            candidates.add(current)
            continue
        for child in children:
            path = Path(child.path)
            if is_excluded(path):
                continue
            try:
                if child.is_dir(follow_symlinks=False):
                    if child.name in (
                        WORKSPACE_DISCOVERY_PRUNED_DIRECTORIES
                        | {"generated", "out"}
                    ):
                        continue
                    pending.append(path)
                elif _is_contract_fingerprint(path):
                    candidates.add(path)
            except OSError:
                candidates.add(path)
    return candidates


def _workspace_asset_candidates(
    root: Path,
    *,
    excluded_paths: tuple[Path, ...] = (),
    docs_excluded_roots: tuple[Path, ...] = (),
) -> dict[str, list[Path]]:
    """递归发现工作区中的生产工程、契约、CI 与部署候选。"""

    excluded = {path.resolve(strict=False) for path in excluded_paths}
    backend = {
        root / "backend",
        root / "db/migrations",
        root / "database/migrations",
        root / "migrations",
        *(root / name for name in BACKEND_PROJECT_FINGERPRINTS),
    }
    if any(
        (root / name).exists() or (root / name).is_symlink()
        for name in BACKEND_PROJECT_FINGERPRINTS
    ):
        backend.update(
            {
                root / "src",
                root / "config",
                root / "conf",
                root / "application.yml",
                root / "application.yaml",
                root / "application.properties",
                root / "bootstrap.yml",
                root / "bootstrap.yaml",
                root / "bootstrap.properties",
            }
        )
    frontend = {root / "frontend"}
    contracts = {
        root / "contracts",
        *(root / name for name in CONTRACT_ROOT_FINGERPRINTS),
        *root.glob("*.schema.json"),
    }
    contracts.update(
        _docs_contract_candidates(
            root,
            excluded_roots=(*excluded_paths, *docs_excluded_roots),
        )
    )
    ci = {
        root / ".github/workflows",
        root / ".circleci",
        root / ".buildkite",
        *(root / name for name in CI_ROOT_FINGERPRINTS),
    }
    deployment = {
        root / "deploy",
        root / "infra",
        root / "helm",
        root / "k8s",
        root / "kubernetes",
        root / "manifests",
        *(root / name for name in DEPLOYMENT_ROOT_FINGERPRINTS),
    }
    deployment.update(root.glob("*.tf"))
    deployment.update(root.glob("*.tf.json"))

    try:
        with os.scandir(root) as iterator:
            first_level = sorted(
                (Path(entry.path) for entry in iterator),
                key=lambda path: path.name,
            )
    except OSError as exc:
        raise AuditError("无法枚举工作区根级生产资产候选") from exc
    root_children = {path.name: path for path in first_level}
    root_frontend_markers = FRONTEND_PROJECT_FINGERPRINTS.intersection(root_children)
    if root_frontend_markers or (
        "package.json" in root_children
        and ((FRONTEND_SOURCE_ROOTS | {"index.html"}) & set(root_children))
    ):
        for name in sorted(
            root_frontend_markers
            | FRONTEND_SOURCE_ROOTS
            | {"package.json", "package-lock.json", "index.html"}
        ):
            candidate = root / name
            if candidate.exists() or candidate.is_symlink():
                frontend.add(candidate)
        frontend.update(_prototype_config_candidates(root))
    pending = [
        path
        for path in reversed(first_level)
        if path.name not in WORKSPACE_DISCOVERY_EXCLUDED_ROOTS
    ]
    while pending:
        project_root = pending.pop()
        try:
            mode = project_root.stat(follow_symlinks=False).st_mode
        except OSError:
            backend.add(project_root)
            frontend.add(project_root)
            contracts.add(project_root)
            ci.add(project_root)
            deployment.add(project_root)
            continue
        if not stat.S_ISDIR(mode):
            continue
        if project_root.name in WORKSPACE_DISCOVERY_PRUNED_DIRECTORIES:
            continue
        try:
            with os.scandir(project_root) as iterator:
                children = {
                    entry.name: Path(entry.path)
                    for entry in iterator
                }
        except OSError:
            # 无法排除其中包含后端、CI 或部署指纹；保守地绑定到三类
            # 负面事实，使后续快照形成可定位的 blocked 交接。
            backend.add(project_root)
            frontend.add(project_root)
            contracts.add(project_root)
            ci.add(project_root)
            deployment.add(project_root)
            continue
        pending.extend(
            reversed(
                sorted(
                    (
                        child
                        for child in children.values()
                        if child.name not in WORKSPACE_DISCOVERY_PRUNED_DIRECTORIES
                    ),
                    key=lambda path: path.name,
                )
            )
        )
        if project_root.name == "backend" or (
            project_root.name == "migrations"
            and project_root.parent.name in {"db", "database"}
        ):
            backend.add(project_root)
        if project_root.name == "frontend":
            frontend.add(project_root)
        if project_root.name == "contracts":
            contracts.add(project_root)
        inside_contract_tree = any(
            ancestor.name == "contracts"
            for ancestor in (project_root, *project_root.parents)
            if _is_relative_to(ancestor.resolve(strict=False), root)
        )
        for name, child in children.items():
            if not inside_contract_tree and (
                name in CONTRACT_ROOT_FINGERPRINTS or name.endswith(".schema.json")
            ):
                contracts.add(child)
        if project_root.name in {
            "deploy",
            "helm",
            "infra",
            "k8s",
            "kubernetes",
            "manifests",
        }:
            deployment.add(project_root)
        if BACKEND_PROJECT_FINGERPRINTS.intersection(children):
            backend.add(project_root)
        if FRONTEND_PROJECT_FINGERPRINTS.intersection(children) or (
            "package.json" in children
            and ((FRONTEND_SOURCE_ROOTS | {"index.html"}) & set(children))
        ):
            frontend.add(project_root)
        for name in CI_ROOT_FINGERPRINTS.intersection(children):
            ci.add(children[name])
        for name in (".circleci", ".buildkite"):
            if name in children:
                ci.add(children[name])
        workflows = project_root / ".github/workflows"
        if workflows.exists() or workflows.is_symlink():
            ci.add(workflows)
        for name in DEPLOYMENT_FIRST_LEVEL_FINGERPRINTS.intersection(children):
            deployment.add(project_root)
        for child in children.values():
            if child.name.endswith(".tf") or child.name.endswith(".tf.json"):
                deployment.add(project_root)

    return {
        "backend": sorted(
            (path for path in backend if path.resolve(strict=False) not in excluded),
            key=lambda path: path.as_posix(),
        ),
        "frontend": sorted(
            (path for path in frontend if path.resolve(strict=False) not in excluded),
            key=lambda path: path.as_posix(),
        ),
        "contracts": sorted(
            (
                path
                for path in contracts
                if path.resolve(strict=False) not in excluded
                and not _is_published_audit_schema(path)
            ),
            key=lambda path: path.as_posix(),
        ),
        "ci": sorted(
            (path for path in ci if path.resolve(strict=False) not in excluded),
            key=lambda path: path.as_posix(),
        ),
        "deployment": sorted(
            (path for path in deployment if path.resolve(strict=False) not in excluded),
            key=lambda path: path.as_posix(),
        ),
    }


def _controlled_input_paths(
    config: AuditConfig,
    workspace_candidates: dict[str, list[Path]] | None = None,
    *,
    excluded_paths: tuple[Path, ...] = (),
) -> list[Path]:
    prototype = config.prototype_root.resolve()
    root = config.workspace_root.resolve()
    candidates = (
        workspace_candidates
        if workspace_candidates is not None
        else _workspace_asset_candidates(
            root,
            excluded_paths=excluded_paths,
            docs_excluded_roots=(config.prototype_root,),
        )
    )
    return [
        config.architecture_source,
        config.pab_source,
        root / ".git",
        root / ".nvmrc",
        *candidates["backend"],
        *candidates["frontend"],
        *candidates["contracts"],
        *candidates["ci"],
        *candidates["deployment"],
        prototype / "package.json",
        prototype / "package-lock.json",
        prototype / "src",
        prototype / "public",
        prototype / "dist",
        prototype / "node_modules",
        *_prototype_config_candidates(prototype),
    ]


def _snapshot_summary_from_entries(
    relative: str,
    entries: list[tuple[str, str, str, int]] | tuple[tuple[str, str, str, int], ...],
) -> dict[str, int | str]:
    by_path = {entry[0]: entry for entry in entries}
    root_entry = by_path.get(relative)
    if root_entry is None:
        raise AuditError(f"快照路径不存在：{relative}")
    if root_entry[1] == "file":
        selected = [root_entry]
    elif root_entry[1] == "directory":
        prefix = relative.rstrip("/") + "/"
        selected = [root_entry, *[
            entry
            for entry in entries
            if entry[0].startswith(prefix)
        ]]
    else:  # pragma: no cover - 由快照捕获器保证
        raise AuditError(f"快照路径类型无效：{relative}")
    hasher = hashlib.sha256()
    byte_count = 0
    file_count = 0
    for entry_relative, entry_type, digest, size in sorted(selected):
        if entry_type == "directory":
            hasher.update(
                entry_relative.encode("utf-8")
                + b"\0DIRECTORY\0"
                + digest.encode("ascii")
                + b"\n"
            )
            continue
        hasher.update(entry_relative.encode("utf-8") + b"\0" + digest.encode("ascii") + b"\n")
        file_count += 1
        byte_count += size
    return {
        "digest": f"sha256:{hasher.hexdigest()}",
        "fileCount": file_count,
        "byteCount": byte_count,
    }


def _capture_controlled_snapshot(
    config: AuditConfig,
    *,
    excluded_paths: tuple[Path, ...] = (),
) -> ControlledSnapshot:
    """把受控文件内容冻结到内存；后续证据只从该不可变快照派生。"""

    root = config.workspace_root.resolve()
    discovered_workspace_candidates = _workspace_asset_candidates(
        root,
        excluded_paths=excluded_paths,
        docs_excluded_roots=(config.prototype_root,),
    )
    prototype_root = config.prototype_root.resolve(strict=False)
    prototype_frontend_check = any(
        path.resolve(strict=False) == prototype_root
        for path in discovered_workspace_candidates["frontend"]
    )
    workspace_candidates = {
        category: [
            path
            for path in paths
            if not _is_relative_to(path.resolve(strict=False), prototype_root)
        ]
        for category, paths in discovered_workspace_candidates.items()
    }
    prototype_parent_candidates = {
        _relative(path, root)
        for paths in workspace_candidates.values()
        for path in paths
        if path.resolve(strict=False) != prototype_root
        and _is_relative_to(prototype_root, path.resolve(strict=False))
    }
    frontend_snapshot_roots = {
        _relative(path, root)
        for path in workspace_candidates["frontend"]
    }
    summaries: list[tuple[str, str]] = []
    file_contents: dict[str, bytes] = {}
    file_errors: list[tuple[str, str]] = []
    entries: dict[str, tuple[str, str, str, int]] = {}
    path_states: list[tuple[str, str, str]] = []
    snapshot_metrics: dict[str, tuple[int, int]] = {}
    raw_content_paths = {
        _relative(path, root)
        for path in (
            config.architecture_source,
            config.pab_source,
            config.prototype_root / "package.json",
            config.prototype_root / "package-lock.json",
        )
    }
    shallow_directory_paths = {
        _relative(config.prototype_root / "node_modules", root),
    }
    unique_paths = {
        _relative(path, root): path
        for path in _controlled_input_paths(config, workspace_candidates)
    }
    for relative, path in sorted(unique_paths.items()):
        local_entries: list[tuple[str, str, str, int]] = []
        local_contents: dict[str, bytes] = {}
        relative = _relative(path, root)
        try:
            mode = path.stat(follow_symlinks=False).st_mode
            if stat.S_ISLNK(mode):
                raise AuditError(f"拒绝读取符号链接：{path.name}")
            if relative in raw_content_paths and not stat.S_ISREG(mode):
                raise AuditError(f"必需语义输入不是普通文件：{relative}")
            if relative in shallow_directory_paths and not stat.S_ISDIR(mode):
                raise AuditError(f"浅层快照输入不是目录：{relative}")
            if stat.S_ISREG(mode):
                captured_paths = [path]
            elif stat.S_ISDIR(mode):
                if relative in shallow_directory_paths:
                    _require_directory_read_and_traverse(mode, relative)
                    captured_paths = [path]
                    entry_count = 0
                    byte_count = 0
                    try:
                        with os.scandir(path) as iterator:
                            for entry in iterator:
                                if entry.name in IGNORED_NAMES or entry.name == ".DS_Store":
                                    continue
                                try:
                                    entry_stat = entry.stat(follow_symlinks=False)
                                    entry_mode = entry_stat.st_mode
                                except OSError as exc:
                                    raise AuditError(
                                        "浅层快照目录项不可读："
                                        f"{relative}/{entry.name}"
                                    ) from exc
                                entry_count += 1
                                if stat.S_ISREG(entry_mode):
                                    byte_count += entry_stat.st_size
                    except OSError as exc:
                        raise AuditError(
                            f"浅层快照无法枚举：{relative}"
                        ) from exc
                    snapshot_metrics[relative] = (entry_count, byte_count)
                else:
                    excluded_roots_set: set[Path] = set()
                    if relative in prototype_parent_candidates:
                        excluded_roots_set.add(prototype_root)
                    if relative in frontend_snapshot_roots:
                        excluded_roots_set.update(
                            path / name
                            for name in FRONTEND_SNAPSHOT_PRUNED_DIRECTORIES
                        )
                    captured_paths = [
                        path,
                        *_walk_directory(
                            path,
                            excluded_roots=tuple(sorted(excluded_roots_set)),
                        ),
                    ]
            else:
                raise AuditError(f"拒绝读取非普通文件：{path.name}")
            for captured in captured_paths:
                relative_to_source = captured.relative_to(
                    path if stat.S_ISDIR(mode) else path.parent
                )
                if ".DS_Store" in relative_to_source.parts:
                    continue
                captured_relative = _relative(captured, root)
                captured_mode = captured.stat(follow_symlinks=False).st_mode
                if stat.S_ISDIR(captured_mode):
                    local_entries.append(
                        (
                            captured_relative,
                            "directory",
                            _behavior_mode_token(captured_mode),
                            0,
                        )
                    )
                    continue
                keep_content = captured_relative in raw_content_paths
                if keep_content:
                    content = _read_bytes(captured)
                    content_digest = hashlib.sha256(content).hexdigest()
                    local_contents[captured_relative] = content
                    content_size = len(content)
                else:
                    content_digest, content_size = _stream_file_digest(captured)
                local_entries.append(
                    (
                        captured_relative,
                        "file",
                        f"{content_digest}:{_behavior_mode_token(captured_mode)}",
                        content_size,
                    )
                )
            summary = _snapshot_summary_from_entries(relative, local_entries)
            digest = str(summary["digest"])
            for entry in local_entries:
                entries[entry[0]] = entry
            file_contents.update(local_contents)
            path_states.append((relative, "readable", ""))
        except FileNotFoundError:
            state = "MISSING"
            message = f"必需输入不存在：{relative}"
            digest = "sha256:" + hashlib.sha256(state.encode("utf-8")).hexdigest()
            file_errors.append((relative, message))
            path_states.append((relative, "missing", message))
        except (AuditError, OSError) as exc:
            state = f"UNREADABLE:{exc.__class__.__name__}"
            message = (
                str(exc)
                if isinstance(exc, AuditError)
                else f"必需输入不可读：{relative}"
            )
            digest = "sha256:" + hashlib.sha256(state.encode("utf-8")).hexdigest()
            file_errors.append((relative, message))
            path_states.append((relative, "unreadable", message))
        summaries.append((relative, digest))
    state_by_path = {
        relative: (status, message)
        for relative, status, message in path_states
    }
    entry_paths = set(entries)
    for candidate in _prototype_subasset_candidates(config.prototype_root):
        relative = _relative(candidate, root)
        if relative in state_by_path:
            continue
        parent_failures = sorted(
            (
                (parent, status, message)
                for parent, (status, message) in state_by_path.items()
                if relative.startswith(parent.rstrip("/") + "/")
                and status == "unreadable"
            ),
            key=lambda item: len(item[0]),
            reverse=True,
        )
        try:
            candidate.stat(follow_symlinks=False)
        except FileNotFoundError:
            status, message = "missing", ""
        except OSError:
            status, message = (
                "unreadable",
                f"必需输入不可读：{relative}",
            )
        else:
            if parent_failures:
                _, _, parent_message = parent_failures[0]
                status, message = "unreadable", parent_message
            elif relative in entry_paths:
                status, message = "readable", ""
            else:
                status, message = (
                    "unreadable",
                    f"必需输入不可读：{relative}",
                )
        state_by_path[relative] = (status, message)
    path_states = [
        (relative, status, message)
        for relative, (status, message) in state_by_path.items()
    ]
    summaries = sorted(set(summaries))
    return ControlledSnapshot(
        digest=_combined_digest(summaries),
        input_scope=tuple(relative for relative, _ in summaries),
        file_contents=tuple(sorted(file_contents.items())),
        file_errors=tuple(sorted(file_errors, key=lambda item: item[0])),
        entries=tuple(sorted(entries.values())),
        path_states=tuple(sorted(path_states)),
        asset_candidates=tuple(
            sorted(
                {
                    (category, _relative(path, root))
                    for category, paths in workspace_candidates.items()
                    for path in paths
                }
                | (
                    {("frontend", _relative(prototype_root, root))}
                    if prototype_frontend_check
                    else set()
                )
            )
        ),
        snapshot_metrics=tuple(
            sorted(
                (relative, counts[0], counts[1])
                for relative, counts in snapshot_metrics.items()
            )
        ),
    )


def _snapshot_path_status(
    snapshot: ControlledSnapshot,
    path: Path,
    workspace_root: Path,
) -> tuple[str, str]:
    relative = _relative(path, workspace_root)
    entry_paths = {entry[0] for entry in snapshot.entries}
    if relative in entry_paths:
        return "readable", ""
    states = {item[0]: (item[1], item[2]) for item in snapshot.path_states}
    if relative in states:
        return states[relative]
    parents = sorted(
        (
            (candidate, status, message)
            for candidate, (status, message) in states.items()
            if relative.startswith(candidate.rstrip("/") + "/")
        ),
        key=lambda item: len(item[0]),
        reverse=True,
    )
    if parents:
        _, status, message = parents[0]
        return ("missing", "") if status == "readable" else (status, message)
    return "missing", ""


def _snapshot_unreadable_evidence_path(
    snapshot: ControlledSnapshot,
    path: Path,
    workspace_root: Path,
) -> Path:
    """细分候选无法穿透父目录时，以最近的冻结不可读父路径作为证据。"""

    relative = _relative(path, workspace_root)
    ancestors = sorted(
        (
            candidate
            for candidate, status, _ in snapshot.path_states
            if status == "unreadable"
            and candidate != relative
            and relative.startswith(candidate.rstrip("/") + "/")
        ),
        key=len,
        reverse=True,
    )
    return workspace_root / ancestors[0] if ancestors else path


def _snapshot_path_summary(
    snapshot: ControlledSnapshot,
    path: Path,
    workspace_root: Path,
) -> dict[str, int | str]:
    relative = _relative(path, workspace_root)
    status, message = _snapshot_path_status(snapshot, path, workspace_root)
    if status != "readable":
        raise AuditError(message or f"快照路径不存在：{relative}")
    summary = _snapshot_summary_from_entries(relative, snapshot.entries)
    shallow_metrics = {
        item[0]: {"fileCount": item[1], "byteCount": item[2]}
        for item in snapshot.snapshot_metrics
    }
    if relative in shallow_metrics:
        summary.update(shallow_metrics[relative])
    return summary


def _snapshot_prototype_config_candidates(
    snapshot: ControlledSnapshot,
    prototype: Path,
    workspace_root: Path,
) -> list[Path]:
    prototype_relative = _relative(prototype, workspace_root)
    excluded = {
        "package.json",
        "package-lock.json",
        "src",
        "public",
        "dist",
        "node_modules",
    }
    return sorted(
        [
            workspace_root / relative
            for relative in snapshot.input_scope
            if Path(relative).parent.as_posix() == prototype_relative
            and Path(relative).name not in excluded
        ],
        key=lambda path: path.as_posix(),
    )


def _snapshot_workspace_asset_candidates(
    snapshot: ControlledSnapshot,
    workspace_root: Path,
) -> dict[str, list[Path]]:
    candidates: dict[str, list[Path]] = {
        "backend": [],
        "frontend": [],
        "contracts": [],
        "ci": [],
        "deployment": [],
    }
    for category, relative in snapshot.asset_candidates:
        if category not in candidates:
            raise AuditError(f"快照包含未知工作区候选分类：{category}")
        candidates[category].append(workspace_root / relative)
    return candidates


def _prototype_stack_fact(
    config: AuditConfig,
    snapshot: ControlledSnapshot,
) -> str:
    """从同一受控 package/lock 快照提取不泄露细节的 Vite/Vue 主版本事实。"""

    package_path = config.prototype_root / "package.json"
    lock_path = config.prototype_root / "package-lock.json"
    try:
        package = _parse_json_bytes(
            _snapshot_bytes(snapshot, package_path, config.workspace_root),
            _relative(package_path, config.workspace_root),
        )
        lock = _parse_json_bytes(
            _snapshot_bytes(snapshot, lock_path, config.workspace_root),
            _relative(lock_path, config.workspace_root),
        )
        packages = lock.get("packages")
        if not isinstance(packages, dict):
            raise AuditError("lockfile 缺少 packages")
        package_sections = {
            name: package.get(name, {}) for name in DEPENDENCY_SECTIONS
        }
        declared_names = {
            dependency
            for section in package_sections.values()
            if isinstance(section, dict)
            for dependency in section
            if isinstance(dependency, str)
        }
        if not {"vite", "vue"}.issubset(declared_names):
            raise AuditError("原型未直接声明 Vite/Vue")
        versions = {
            name: packages.get(f"node_modules/{name}", {}).get("version")
            if isinstance(packages.get(f"node_modules/{name}"), dict)
            else None
            for name in ("vite", "vue")
        }
        majors: dict[str, str] = {}
        for name, version in versions.items():
            if not isinstance(version, str):
                raise AuditError("lockfile 缺少 Vite/Vue 精确版本")
            match = re.match(r"^(\d+)(?:\.|$)", version)
            if match is None:
                raise AuditError("Vite/Vue 版本不可核验")
            majors[name] = match.group(1)
        return f"Vite {majors['vite']} / Vue {majors['vue']} 原型"
    except (AuditError, KeyError, TypeError, ValueError):
        return "Vite / Vue 版本不可核验的原型"


def _generation_id(config: AuditConfig, snapshot: ControlledSnapshot) -> str:
    root = config.workspace_root.resolve()
    payload = {
        "auditVersion": SCRIPT_VERSION,
        "generatedAt": config.generated_at,
        "controlledInputDigest": snapshot.digest,
        "prototypeRoot": _relative(config.prototype_root, root),
        "architectureSource": _relative(config.architecture_source, root),
        "pabSource": _relative(config.pab_source, root),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "sha256:" + hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _new_generation_id() -> str:
    return "sha256:" + hashlib.sha256(uuid.uuid4().bytes).hexdigest()


def _command_for(config: AuditConfig, paths: ArtifactPaths, mode: str, *, force: bool = False) -> str:
    root = config.workspace_root.resolve()
    arguments = [
        "python3",
        str(Path(__file__).resolve()),
        mode,
        "--workspace-root",
        str(root),
        "--prototype-root",
        _relative(config.prototype_root, root),
        "--architecture-source",
        _relative(config.architecture_source, root),
        "--pab-source",
        _relative(config.pab_source, root),
        "--inventory-output",
        _relative(paths.inventory, root),
        "--schema-output",
        _relative(paths.schema, root),
        "--gap-output",
        _relative(paths.gap, root),
    ]
    if mode == "generate":
        arguments.extend(["--generated-at", config.generated_at])
        if force:
            arguments.append("--force")
    return shlex.join(arguments)


def _execution_evidence(
    config: AuditConfig,
    paths: ArtifactPaths | None = None,
    *,
    force: bool = False,
    failed: bool = False,
) -> dict[str, Any]:
    script_digest = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    if paths is None:
        generate_command = "Python API: build_inventory(config)"
        replay_command = "Python API: check_artifacts(config, paths)"
    else:
        generate_command = _command_for(config, paths, "generate", force=force)
        replay_command = _command_for(config, paths, "check")
    result = "failed" if failed else "passed"
    exit_code = 2 if failed else 0
    return {
        "generateCommand": generate_command,
        "replayCommand": replay_command,
        "scriptVersion": SCRIPT_VERSION,
        "scriptSha256": f"sha256:{script_digest}",
        "inputScope": [],
        "result": result,
        "resultScope": "input-audit-failed" if failed else "generation-and-in-memory-validation",
        "acceptanceEvidenceLocation": "Story Dev Agent Record",
        "steps": [
            {"name": "generate", "command": generate_command, "status": result, "exitCode": exit_code},
            {
                "name": "schema-validation",
                "command": "in-memory published JSON Schema validation",
                "status": "passed",
                "exitCode": 0,
            },
            {
                "name": "gap-validation",
                "command": "in-memory dependency gap contract validation",
                "status": "failed" if failed else "passed",
                "exitCode": exit_code,
            },
        ],
        "unresolvedCount": 0,
    }


def _asset(
    *,
    asset_id: str,
    asset_type: str,
    path_or_source: str,
    owner: str,
    version_or_digest: str,
    verified_date: str,
    evidence_links: list[str],
    classification: str,
    reason: str,
    risk: str,
    owner_story: str,
    present: bool,
    recommended: str | None = None,
    metrics: dict[str, int] | None = None,
    read_status: str = "readable",
    checked_paths: list[str] | None = None,
    snapshot_only: bool = False,
) -> dict[str, Any]:
    item: dict[str, Any] = {
        "assetId": asset_id,
        "type": asset_type,
        "pathOrSource": path_or_source,
        "owner": owner,
        "versionOrDigest": version_or_digest,
        "verifiedDate": verified_date,
        "evidenceLinks": evidence_links,
        "classification": classification,
        "dispositionReason": reason,
        "risk": risk,
        "ownerStory": owner_story,
        "present": present,
        "readStatus": read_status,
        "snapshotOnly": snapshot_only,
    }
    if checked_paths is not None:
        item["checkedPaths"] = checked_paths
    if recommended is not None:
        item["recommendedDisposition"] = recommended
    if metrics is not None:
        item["metrics"] = metrics
    return item


def _failed_input_asset_ids(assets: list[dict[str, Any]]) -> list[str]:
    """统一判定会阻断生成的不可读资产与必需缺失资产。"""

    return sorted(
        item["assetId"]
        for item in assets
        if item.get("readStatus") == "unreadable"
        or (
            item.get("readStatus") == "missing"
            and item.get("assetId") in REQUIRED_READABLE_ASSET_IDS
        )
    )


def _summarized_asset(
    config: AuditConfig,
    *,
    asset_id: str,
    asset_type: str,
    paths: list[Path],
    candidates: list[Path],
    missing_source: str,
    owner_story: str,
    recommended: str,
    reason: str,
    risk: str,
    owner: str = "UNKNOWN",
    classification: str = "unknown-blocked",
    controlled_snapshot: ControlledSnapshot | None = None,
    checked_candidates: list[Path] | None = None,
) -> dict[str, Any]:
    root = config.workspace_root.resolve()
    checked = sorted(
        _relative(path, root)
        for path in (candidates if checked_candidates is None else checked_candidates)
    )
    effective_paths = paths
    if controlled_snapshot is not None:
        effective_paths = []
        for path in candidates:
            status, _ = _snapshot_path_status(controlled_snapshot, path, root)
            if status == "readable":
                effective_paths.append(path)
            elif status == "unreadable":
                effective_paths.append(
                    _snapshot_unreadable_evidence_path(
                        controlled_snapshot,
                        path,
                        root,
                    )
                )
        effective_paths.sort(key=lambda path: path.as_posix())
        effective_paths = list(dict.fromkeys(effective_paths))
        checked = sorted(
            set(checked) | {_relative(path, root) for path in effective_paths}
        )
    if not effective_paths:
        return _asset(
            asset_id=asset_id,
            asset_type=asset_type,
            path_or_source=missing_source,
            owner=owner,
            version_or_digest="UNAVAILABLE",
            verified_date=config.generated_at[:10],
            evidence_links=[f"audit://absence/{asset_id}"],
            classification="unknown-blocked",
            recommended=recommended,
            reason=reason,
            risk=risk,
            owner_story=owner_story,
            present=False,
            read_status="missing",
            checked_paths=checked,
        )
    evidence_paths = sorted(_relative(path, root) for path in effective_paths)
    coverage_paths = _non_overlapping_paths(effective_paths)
    summaries: list[tuple[str, dict[str, int | str]]] = []
    try:
        for path in coverage_paths:
            summaries.append(
                (
                    _relative(path, root),
                    (
                    _snapshot_path_summary(controlled_snapshot, path, root)
                    if controlled_snapshot is not None
                    else summarize_path(
                            path,
                            root,
                            reject_symlinks=asset_id
                            not in {"prototype.node-modules", "prototype.dist"},
                            ignore_volatile_names=asset_id == "prototype.node-modules",
                        )
                    ),
                )
            )
    except AuditError as exc:
        return _asset(
            asset_id=asset_id,
            asset_type=asset_type,
            path_or_source="; ".join(evidence_paths),
            owner=owner,
            version_or_digest="UNAVAILABLE",
            verified_date=config.generated_at[:10],
            evidence_links=evidence_paths,
            classification="unknown-blocked",
            recommended=recommended,
            reason=f"{reason} 读取失败：{exc}",
            risk=risk,
            owner_story=owner_story,
            present=True,
            read_status="unreadable",
            checked_paths=checked,
        )
    snapshot_only = asset_id == "prototype.node-modules"
    if snapshot_only:
        marker = "\n".join(relative for relative, _ in summaries) + "\nUNCONTROLLED-INTERNALS-EXCLUDED"
        digest = "sha256:" + hashlib.sha256(marker.encode("utf-8")).hexdigest()
    else:
        digest = _combined_digest([(relative, str(summary["digest"])) for relative, summary in summaries])
    return _asset(
        asset_id=asset_id,
        asset_type=asset_type,
        path_or_source="; ".join(evidence_paths),
        owner=owner,
        version_or_digest=digest,
        verified_date=config.generated_at[:10],
        evidence_links=evidence_paths,
        classification=classification,
        recommended=recommended,
        reason=reason,
        risk=risk,
        owner_story=owner_story,
        present=True,
        metrics=(
            {
                "scope": "top-level-entries",
                "topLevelEntryCount": sum(
                    int(summary["fileCount"]) for _, summary in summaries
                ),
                "topLevelRegularFileByteCount": sum(
                    int(summary["byteCount"]) for _, summary in summaries
                ),
            }
            if snapshot_only
            else {
                "scope": "recursive-regular-files",
                "regularFileCount": sum(
                    int(summary["fileCount"]) for _, summary in summaries
                ),
                "regularFileByteCount": sum(
                    int(summary["byteCount"]) for _, summary in summaries
                ),
            }
        ),
        checked_paths=checked,
        snapshot_only=snapshot_only,
    )


def build_inventory(
    config: AuditConfig,
    execution_evidence: dict[str, Any] | None = None,
    blocked_asset_ids: set[str] | None = None,
    controlled_snapshot: ControlledSnapshot | None = None,
    generation_id: str | None = None,
) -> dict[str, Any]:
    """盘点当前工作区；所有路径均为工作区相对 POSIX 路径。"""

    root = config.workspace_root.resolve()
    prototype = config.prototype_root.resolve()
    if not root.exists() or not root.is_dir():
        raise AuditError("工作区根不存在或不是目录")
    snapshot = controlled_snapshot or _capture_controlled_snapshot(config)

    verified_date = config.generated_at[:10]
    assets: list[dict[str, Any]] = []

    assets.extend(
        [
            _summarized_asset(
                config,
                asset_id="planning.architecture-spine",
                asset_type="controlled-planning-baseline",
                paths=[config.architecture_source],
                candidates=[config.architecture_source],
                missing_source=f"{_relative(config.architecture_source, root)}（未发现）",
                owner="AUTH-2026-07-17-001 受控架构基线",
                classification="reuse-as-is",
                reason="作为 AD-1/AD-28 的只读受控约束使用。",
                risk="不得把目标源码树误报为现有生产资产。",
                owner_story="1.1b",
                recommended="reference-only",
                controlled_snapshot=snapshot,
            ),
            _summarized_asset(
                config,
                asset_id="planning.pab-1.0.0",
                asset_type="approved-version-oracle",
                paths=[config.pab_source],
                candidates=[config.pab_source],
                missing_source=f"{_relative(config.pab_source, root)}（未发现）",
                owner="项目总负责人与委托决策人（Hei）",
                classification="reuse-as-is",
                reason="仅作为 PAB-1.0.0 对账 oracle，不代表运行证据通过。",
                risk="精确 lock、兼容性与供应链证据仍由 1.1c/1.1d 产生。",
                owner_story="1.1c",
                recommended="reference-only",
                controlled_snapshot=snapshot,
            ),
        ]
    )

    workspace_candidates = _snapshot_workspace_asset_candidates(snapshot, root)
    production_frontend_candidates = [
        path
        for path in workspace_candidates["frontend"]
        if path.resolve(strict=False) != prototype
    ]
    negative_specs = [
        (
            "workspace.git-repository",
            "repository-metadata",
            [root / ".git"],
            ".git（未发现）",
            "1.1b",
            "reference-only",
            "工作区根未发现 Git 元数据；远程、分支与提交均不可核验。",
            "缺失版本历史与远程来源证据，禁止声称仓库核验通过。",
        ),
        (
            "workspace.production-frontend",
            "production-frontend",
            production_frontend_candidates,
            "frontend/ 或一级前端项目指纹（未发现）",
            "1.1b",
            "migrate",
            "生产前端候选必须作为独立资产审计，不得只隐含在工作区摘要中。",
            "owner、来源与生产真实性尚未闭合，需由 1.1b 建立受控工程边界。",
        ),
        (
            "workspace.contract-definitions",
            "api-and-event-contracts",
            workspace_candidates["contracts"],
            "contracts/ 或根级 OpenAPI、AsyncAPI、JSON Schema（未发现）",
            "1.1b",
            "reference-only",
            "OpenAPI、事件与 Schema 候选必须显式盘点并追溯来源。",
            "现存文件不自动等同于已批准的 PAB/PIC 契约或运行兼容性证据。",
        ),
        (
            "workspace.production-backend",
            "production-backend",
            workspace_candidates["backend"],
            "backend/、根级或一级项目中的 Maven/Gradle 指纹（未发现）",
            "1.1b",
            "replace",
            "未发现生产后端或数据库迁移工程。",
            "Architecture 的 backend/ 是目标 seed，不是可复用现状。",
        ),
        (
            "workspace.ci-definitions",
            "ci-metadata",
            workspace_candidates["ci"],
            ".github/workflows、GitLab/Jenkins/Azure/CircleCI/Travis/Bitbucket/Buildkite/Drone（未发现）",
            "1.1d",
            "replace",
            "未发现 CI 定义；缺失本身是供应链证据事实。",
            "不得推断已执行构建、扫描、SBOM、provenance 或签名。",
        ),
        (
            "workspace.deployment-engineering",
            "deployment-metadata",
            workspace_candidates["deployment"],
            "deploy/、容器/Compose、Terraform、Helm、Kubernetes 或一级项目部署指纹（未发现）",
            "1.1d",
            "replace",
            "未发现容器、部署或基础设施工程。",
            "不得虚构部署平台、构建镜像 digest 或制品库。",
        ),
    ]
    for asset_id, asset_type, candidates, missing, owner_story, recommended, reason, risk in negative_specs:
        assets.append(
            _summarized_asset(
                config,
                asset_id=asset_id,
                asset_type=asset_type,
                paths=candidates,
                candidates=candidates,
                missing_source=missing,
                owner_story=owner_story,
                recommended=recommended,
                reason=reason,
                risk=risk,
                controlled_snapshot=snapshot,
                checked_candidates=(
                    workspace_candidates["frontend"]
                    if asset_id == "workspace.production-frontend"
                    else None
                ),
            )
        )

    prototype_config_candidates = _snapshot_prototype_config_candidates(
        snapshot, prototype, root
    )
    prototype_stack_fact = _prototype_stack_fact(config, snapshot)
    prototype_specs = [
        ("prototype.source", "source-code", [prototype / "src"], "1.1b", "migrate", "UX 与页面意图仅作为迁移输入。", "mock/auth/store/API 行为不是生产真实性。"),
        ("prototype.routes", "routing", [prototype / "src/router", prototype / "src/routes"], "1.1b", "migrate", "路由意图可迁移，行为须重新验证。", "不得继承原型认证与授权假设。"),
        ("prototype.layouts", "layout", [prototype / "src/layouts", prototype / "src/layout"], "1.1b", "migrate", "布局与交互意图可作为迁移参考。", "未经过生产宿主、WebView 与无障碍验收。"),
        ("prototype.components", "ui-components", [prototype / "src/components"], "1.1b", "migrate", "组件意图可迁移。", "原型组件与 PAB 依赖组合未验证。"),
        ("prototype.mocks", "mock-behavior", [prototype / "src/mock", prototype / "src/mocks"], "1.1b", "replace", "mock 行为必须由真实端口/契约替换。", "把 mock 当真实性会绕过授权、失败与审计语义。"),
        ("prototype.stores", "client-state", [prototype / "src/stores", prototype / "src/store"], "1.1b", "migrate", "仅迁移必要的页面状态意图。", "Pinia 2 和持久化行为不得成为生产基线。"),
        ("prototype.types", "typescript-types", [prototype / "src/types"], "1.1b", "migrate", "类型名称只作契约发现输入。", "原型类型不能替代受控 OpenAPI/事件 schema。"),
        ("prototype.styles", "styles", [prototype / "src/styles", prototype / "src/style.css"], "1.1b", "migrate", "视觉意图可迁移。", "尚无视觉回归、WCAG 2.2 AA 或宿主适配证据。"),
        ("prototype.static-assets", "static-assets", [prototype / "src/assets", prototype / "public"], "1.1b", "reference-only", "静态资产仅作为设计参考。", "来源、许可证和生产优化状态未核验。"),
        ("prototype.config", "prototype-configuration", prototype_config_candidates, "1.1c", "reference-only", "“微前端”“网关”“各微服务”等注释是原型假设。", "原型假设不得覆盖 AD-1 模块化单体与 AD-28 生产基线。"),
        ("prototype.package-manifest", "dependency-manifest", [prototype / "package.json"], "1.1c", "reference-only", f"{prototype_stack_fact}；semver 声明只作为差异输入。", "范围声明不是 PAB 精确 lock。"),
        ("prototype.lockfile", "dependency-lock-snapshot", [prototype / "package-lock.json"], "1.1c", "reference-only", "仅用于解析原型实际版本。", "不得作为未来生产 lockfile 提升。"),
        ("prototype.dist", "generated-build-output", [prototype / "dist"], "1.1d", "replace", "已生成 dist 只记录摘要与规模，不可提升。", "无可复现构建、digest、签名或扫描证据。"),
        ("prototype.node-modules", "installed-dependency-tree", [prototype / "node_modules"], "1.1d", "replace", "node_modules 只记录摘要与规模，不作依赖分发源。", "安装树可能含缓存、平台差异与未受控传递依赖。"),
    ]
    for asset_id, asset_type, candidates, owner_story, recommended, reason, risk in prototype_specs:
        assets.append(
            _summarized_asset(
                config,
                asset_id=asset_id,
                asset_type=asset_type,
                paths=candidates,
                candidates=candidates,
                missing_source=" / ".join(_relative(candidate, root) for candidate in candidates) + "（未发现）",
                owner_story=owner_story,
                recommended=recommended,
                reason=reason,
                risk=risk,
                controlled_snapshot=snapshot,
            )
        )

    blocked_ids = blocked_asset_ids or set()
    for asset in assets:
        if asset["assetId"] not in blocked_ids:
            continue
        asset["owner"] = "UNKNOWN"
        asset["versionOrDigest"] = "UNAVAILABLE"
        asset["classification"] = "unknown-blocked"
        asset["readStatus"] = "missing" if asset.get("present") is False else "unreadable"
        asset["snapshotOnly"] = False
        asset.pop("metrics", None)
        asset["dispositionReason"] += " PAB 语义 oracle 无效，禁止作为已批准可复用事实。"

    assets.sort(key=lambda item: item["assetId"])
    unresolved = [
        {
            "assetId": item["assetId"],
            "issue": "当前 owner 或来源/版本证据未闭合。",
            "impact": item["risk"],
            "ownerStory": item["ownerStory"],
        }
        for item in assets
        if item["classification"] == "unknown-blocked"
    ]
    evidence = deepcopy(execution_evidence) if execution_evidence is not None else _execution_evidence(config)
    failed_required_assets = _failed_input_asset_ids(assets)
    if failed_required_assets:
        evidence["result"] = "failed"
        evidence["resultScope"] = "input-audit-failed"
        for step in evidence.get("steps", []):
            if step.get("name") == "generate":
                step["status"] = "failed"
                step["exitCode"] = 2
    evidence["inputScope"] = list(snapshot.input_scope)
    evidence["unresolvedCount"] = len(unresolved)
    inventory: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "auditVersion": SCRIPT_VERSION,
        "generationId": generation_id or _generation_id(config, snapshot),
        "generatedAt": config.generated_at,
        "verificationDate": verified_date,
        "controlledInputDigest": snapshot.digest,
        "assets": assets,
        "unresolvedItems": unresolved,
        "executionEvidence": evidence,
    }
    errors = validate_inventory(inventory, root)
    if errors:
        raise AuditError("生成的清单未通过契约校验：\n- " + "\n- ".join(errors))
    return inventory


PAB_DEPENDENCY_META: dict[str, tuple[str | None, str]] = {
    "OpenJDK": (None, "1.1b"),
    "Spring Boot": (None, "1.1b"),
    "PostgreSQL": (None, "1.1b"),
    "OpenAPI": (None, "1.1b"),
    "CloudEvents": (None, "1.1b"),
    "JSON Schema": (None, "1.1b"),
    "Node.js": (None, "1.1c"),
    "npm": (None, "1.1c"),
    "Vite": ("vite", "1.1c"),
    "Vue": ("vue", "1.1c"),
    "TypeScript": ("typescript", "1.1c"),
    "vue-tsc": ("vue-tsc", "1.1c"),
    "Vue Router": ("vue-router", "1.1c"),
    "Pinia": ("pinia", "1.1c"),
    "Element Plus": ("element-plus", "1.1c"),
    "TanStack Vue Query": ("@tanstack/vue-query", "1.1c"),
    "ECharts": ("echarts", "1.1c"),
    "vue-echarts": ("vue-echarts", "1.1c"),
    "Vitest": ("vitest", "1.1c"),
    "Playwright": ("@playwright/test", "1.1c"),
    "axe-core": ("axe-core", "1.1c"),
}
APPROVED_PAB_1_0_0: dict[str, str] = {
    "OpenJDK": "25 LTS",
    "Spring Boot": "4.1.0",
    "PostgreSQL": "18.4",
    "OpenAPI": "3.1.2",
    "CloudEvents": "1.0.2",
    "JSON Schema": "2020-12",
    "Node.js": "24.18.0 LTS",
    "npm": "11.16.0",
    "Vite": "8.1.5",
    "Vue": "3.5.40",
    "TypeScript": "@typescript/typescript6 6.0.2",
    "vue-tsc": "3.3.7",
    "Vue Router": "4.6.4",
    "Pinia": "3.0.4",
    "Element Plus": "2.14.3",
    "TanStack Vue Query": "5.101.2",
    "ECharts": "6.1.0",
    "vue-echarts": "8.0.1",
    "Vitest": "4.1.10",
    "Playwright": "1.61.1",
    "axe-core": "4.12.1",
}
PAB_PROSE_ALIASES = {
    "OpenJDK": "JDK",
    "Node.js": "Node",
    "TypeScript": "@typescript/typescript6",
}
REFERENCE_ONLY_COMBINATIONS = {"Vite", "TypeScript", "Pinia", "ECharts"}
GAP_FIELDS = {
    "dependency",
    "packageName",
    "prototypeState",
    "prototypeRange",
    "lockfileState",
    "lockfileVersion",
    "pabBaseline",
    "differenceType",
    "classification",
    "migrationRisk",
    "ownerStory",
    "disposition",
}
PROTOTYPE_STATES = {
    "declared",
    "not-declared",
    "not-applicable",
    "unavailable",
}
LOCKFILE_STATES = {
    "resolved",
    "not-resolved",
    "not-recorded",
    "not-applicable",
    "unavailable",
}
GAP_DIFFERENCE_TYPES = {
    "not-applicable-to-frontend-prototype",
    "runtime-unverified",
    "package-manager-conflict",
    "missing-from-prototype",
    "transitive-only",
    "version-mismatch",
    "semver-range-not-exact",
    "exact-match-unverified",
    "unfrozen-prototype-extra",
    "not-present-and-not-frozen",
    "input-unavailable",
}
NON_FRONTEND_PAB = {
    "OpenJDK",
    "Spring Boot",
    "PostgreSQL",
    "OpenAPI",
    "CloudEvents",
    "JSON Schema",
}


def _parse_json_bytes(content: bytes, source_label: str) -> dict[str, Any]:
    try:
        value = _strict_json_loads(content.decode("utf-8"))
    except _DuplicateJsonKeyError as exc:
        raise AuditError(f"JSON 含重复键：{source_label}") from exc
    except _NonFiniteJsonConstantError as exc:
        raise AuditError(f"JSON 含非有限数字常量：{source_label}") from exc
    except _JsonIntegerRangeError as exc:
        raise AuditError(f"JSON 整数超出解析限制：{source_label}") from exc
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AuditError(f"JSON 解析失败：{source_label}（{exc.__class__.__name__}）") from exc
    if not isinstance(value, dict):
        raise AuditError(f"JSON 根节点必须为对象：{source_label}")
    return value


def _load_json(path: Path, workspace_root: Path) -> dict[str, Any]:
    if not path.exists():
        raise AuditError(f"必需输入不存在：{_relative(path, workspace_root)}")
    return _parse_json_bytes(_read_bytes(path), _relative(path, workspace_root))


def _snapshot_bytes(
    snapshot: ControlledSnapshot,
    path: Path,
    workspace_root: Path,
) -> bytes:
    relative = _relative(path, workspace_root)
    contents = dict(snapshot.file_contents)
    if relative not in contents:
        errors = dict(snapshot.file_errors)
        raise AuditError(errors.get(relative, f"必需输入不可读：{relative}"))
    return contents[relative]


def _read_utf8(path: Path) -> str:
    try:
        return _read_bytes(path).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise AuditError(f"文本编码无效：{path.name}") from exc


def _read_text(path: Path, workspace_root: Path) -> str:
    if not path.exists():
        raise AuditError(f"必需输入不存在：{_relative(path, workspace_root)}")
    try:
        return _read_utf8(path)
    except AuditError as exc:
        if "文本编码无效" in str(exc):
            raise AuditError(f"文本编码无效：{_relative(path, workspace_root)}") from exc
        raise


def _clean_pab_value(value: str) -> str:
    return value.strip().strip("`").replace("`", "")


def _validate_exact_pab_version(name: str, version: str) -> None:
    patterns = {
        "OpenJDK": r"\d+ LTS",
        "Node.js": r"\d+\.\d+\.\d+ LTS",
        "TypeScript": r"@typescript/typescript6 \d+\.\d+\.\d+",
        "JSON Schema": r"\d{4}-\d{2}",
        "PostgreSQL": r"\d+\.\d+",
    }
    pattern = patterns.get(name, r"\d+\.\d+\.\d+")
    if re.fullmatch(pattern, version) is None:
        raise PabOracleError(f"PAB oracle 表中的 {name} 必须使用批准的精确版本格式")


def _unique_h2_section(text: str, heading_pattern: str, label: str) -> str:
    matches = list(re.finditer(heading_pattern, text, re.MULTILINE))
    if len(matches) != 1:
        raise PabOracleError(f"PAB oracle 的 {label} 章节必须且只能出现一次")
    start = matches[0].end()
    following = re.search(r"(?m)^##\s+", text[start:])
    end = start + following.start() if following else len(text)
    return text[start:end]


def _architecture_pab_table(architecture_text: str) -> dict[str, str]:
    section = _unique_h2_section(
        architecture_text,
        r"^##\s+生产技术基线\s+PAB-1\.0\.0\s*$",
        "生产技术基线 PAB-1.0.0",
    )
    tables: list[list[str]] = []
    current: list[str] = []
    for line in section.splitlines():
        if line.strip().startswith("|"):
            current.append(line)
        elif current:
            tables.append(current)
            current = []
    if current:
        tables.append(current)

    candidates: list[list[str]] = []
    for table in tables:
        header = [cell.strip() for cell in table[0].strip().strip("|").split("|")]
        if header == ["名称", "精确基线"]:
            candidates.append(table)
    if len(candidates) != 1:
        raise PabOracleError("PAB oracle 必须包含唯一的“名称 | 精确基线”表")

    parsed: dict[str, str] = {}
    rows = candidates[0]
    if len(rows) < 3:
        raise PabOracleError("PAB oracle 精确基线表没有数据行")
    separator = [cell.strip() for cell in rows[1].strip().strip("|").split("|")]
    if len(separator) != 2 or any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
        raise PabOracleError("PAB oracle 表头分隔行无效")
    for row_number, line in enumerate(rows[2:], start=3):
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 2 or not all(cells):
            raise PabOracleError(f"PAB oracle 表第 {row_number} 行必须为两个非空单元格")
        names = [item.strip() for item in re.split(r"\s+/\s+", cells[0])]
        versions = [_clean_pab_value(item) for item in re.split(r"\s+/\s+", cells[1])]
        if len(names) != len(versions) or not all(names) or not all(versions):
            raise PabOracleError(f"PAB oracle 表第 {row_number} 行名称与版本数量不一致")
        for name, version in zip(names, versions):
            if name not in PAB_DEPENDENCY_META:
                raise PabOracleError(f"PAB oracle 表含未知技术项：{name}")
            if name in parsed:
                raise PabOracleError(f"PAB oracle 表含重复技术项：{name}")
            _validate_exact_pab_version(name, version)
            parsed[name] = version
    missing = sorted(set(PAB_DEPENDENCY_META) - set(parsed))
    if missing:
        raise PabOracleError("PAB oracle 全表解析不完整：" + ", ".join(missing))
    mismatched = sorted(
        name
        for name, approved in APPROVED_PAB_1_0_0.items()
        if parsed.get(name) != approved
    )
    if mismatched:
        raise PabOracleError(
            "PAB-1.0.0 与 Story 批准值不一致：" + ", ".join(mismatched)
        )
    return parsed


def _verify_authorized_pab_prose(pab_text: str, parsed: dict[str, str]) -> None:
    pic_section = _unique_h2_section(
        pab_text,
        r"^##\s+4\.\s+公共集成\s+PIC-1\.0\.0\s*$",
        "§4 公共集成 PIC-1.0.0",
    )
    pab_section = _unique_h2_section(
        pab_text,
        r"^##\s+9\.\s+PAB/PP/AP-1\.0\.0\s*$",
        "§9 PAB/PP/AP-1.0.0",
    )
    pic_authorizations = [
        line.strip()
        for line in pic_section.splitlines()
        if re.match(r"^-\s+同步\s+API\s+用\s+", line.strip())
    ]
    pab_authorizations = [
        line.strip()
        for line in pab_section.splitlines()
        if re.match(r"^-\s+PAB(?:-1\.0\.0)?\s+精确版本[：:]", line.strip())
    ]
    if len(pic_authorizations) != 1:
        raise PabOracleError("PAB oracle 的 §4 公共契约授权句必须且只能出现一次")
    if len(pab_authorizations) != 1:
        raise PabOracleError("PAB oracle 的 §9 精确版本授权句必须且只能出现一次")
    pic_names = {"OpenAPI", "CloudEvents", "JSON Schema"}
    for name in sorted(pic_names):
        baseline = parsed[name]
        alias_pattern = rf"(?<![\w@/-]){re.escape(name)}(?![\w@/-])"
        alias_matches = list(re.finditer(alias_pattern, pic_authorizations[0]))
        if len(alias_matches) != 1:
            state = "缺失" if not alias_matches else "重复"
            raise PabOracleError(f"PAB oracle 授权正文{state}：{name}")
        expected_pattern = (
            alias_pattern
            + rf"\s+{re.escape(baseline)}"
            + r"(?=$|[、，；。]|\s+\+)"
        )
        if len(list(re.finditer(expected_pattern, pic_authorizations[0]))) != 1:
            raise PabOracleError(f"PAB oracle 冲突：{name} 授权正文与精确表不一致")

    payload_parts = re.split(r"[：:]", pab_authorizations[0], maxsplit=1)
    if len(payload_parts) != 2:
        raise PabOracleError("PAB oracle 的 §9 精确版本授权句格式无效")
    baseline_sentence = payload_parts[1].split("。", 1)[0].strip()
    actual_items = [
        item.strip().strip("。").replace("`", "")
        for item in baseline_sentence.split("、")
        if item.strip()
    ]
    expected_items = []
    for name, baseline in parsed.items():
        if name in pic_names:
            continue
        alias = PAB_PROSE_ALIASES.get(name, name)
        expected_version = baseline.rsplit(" ", 1)[-1] if name == "TypeScript" else baseline
        expected_items.append(f"{alias} {expected_version}")
    if len(actual_items) != len(expected_items) or sorted(actual_items) != sorted(expected_items):
        raise PabOracleError("PAB oracle 冲突：授权正文技术项不唯一、缺失或版本不一致")


def load_pab_oracle(
    config: AuditConfig,
    controlled_snapshot: ControlledSnapshot | None = None,
) -> dict[str, str]:
    """语义解析受控 PAB 表，并与授权基线正文的重叠项对账。"""

    try:
        if controlled_snapshot is None:
            architecture_text = _read_text(config.architecture_source, config.workspace_root)
            pab_text = _read_text(config.pab_source, config.workspace_root)
        else:
            architecture_text = _snapshot_bytes(
                controlled_snapshot, config.architecture_source, config.workspace_root
            ).decode("utf-8")
            pab_text = _snapshot_bytes(
                controlled_snapshot, config.pab_source, config.workspace_root
            ).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PabOracleError("PAB oracle 文本编码无效") from exc
    except AuditError as exc:
        raise PabOracleError(str(exc)) from exc
    if "PAB-1.0.0" not in architecture_text or "PAB-1.0.0" not in pab_text:
        raise PabOracleError("PAB oracle 缺少 PAB-1.0.0 标识")
    parsed = _architecture_pab_table(architecture_text)
    _verify_authorized_pab_prose(pab_text, parsed)
    return parsed


def _dependency_maps(package: dict[str, Any], lock: dict[str, Any]) -> tuple[dict[str, str], dict[str, str]]:
    declared: dict[str, str] = {}
    declaration_sources: dict[str, str] = {}
    manifest_sections: dict[str, dict[str, str]] = {}
    for section in DEPENDENCY_SECTIONS:
        values = package.get(section, {})
        if not isinstance(values, dict):
            raise AuditError(f"package.json 的 {section} 必须为对象")
        normalized_section: dict[str, str] = {}
        for name, version in values.items():
            if not isinstance(name, str) or not name:
                raise AuditError(f"package.json 的 {section} 依赖名称必须为非空字符串")
            if not isinstance(version, str):
                raise AuditError(f"package.json 的 {section} 依赖声明值必须为字符串")
            if name in declaration_sources:
                raise AuditError(
                    "package.json 依赖跨区重复声明："
                    f"{declaration_sources[name]} 与 {section}"
                )
            declaration_sources[name] = section
            normalized_section[name] = version
            declared[name] = _redact_dependency_spec(version)
        manifest_sections[section] = normalized_section

    package_name = package.get("name")
    package_version = package.get("version")
    if not isinstance(package_name, str) or not package_name:
        raise AuditError("package.json 的 name 必须为非空字符串")
    if package_version is not None and not isinstance(package_version, str):
        raise AuditError("package.json 的 version 必须为字符串")
    lockfile_version = lock.get("lockfileVersion")
    if not isinstance(lockfile_version, int) or isinstance(lockfile_version, bool) or lockfile_version not in {2, 3}:
        raise AuditError("package-lock.json 的 lockfileVersion 必须为 2 或 3")
    packages = lock.get("packages")
    if not isinstance(packages, dict):
        raise AuditError("package-lock.json 缺少 packages 对象，无法获得精确解析版本")
    lock_root = packages.get("")
    if not isinstance(lock_root, dict):
        raise AuditError("package-lock.json 缺少 packages[''] 根包，无法证明 lock 归属")
    if lock.get("name") != package_name or lock_root.get("name") != package_name:
        raise AuditError("package-lock.json 不属于当前 package.json")
    if lock.get("version") != package_version or lock_root.get("version") != package_version:
        raise AuditError("package-lock.json 的根包 version 不属于当前 package.json")
    for section in DEPENDENCY_SECTIONS:
        lock_values = lock_root.get(section, {})
        if not isinstance(lock_values, dict):
            raise AuditError(f"package-lock.json 根包的 {section} 必须为对象")
        normalized_lock: dict[str, str] = {}
        for name, version in lock_values.items():
            if not isinstance(name, str) or not name or not isinstance(version, str):
                raise AuditError(f"package-lock.json 根包的 {section} 声明无效")
            normalized_lock[name] = version
        if normalized_lock != manifest_sections[section]:
            raise AuditError(f"package-lock.json 根包的 {section} 与 package.json 直接依赖声明不一致")
    resolved: dict[str, str] = {}
    prefix = "node_modules/"
    for path, metadata in packages.items():
        if not isinstance(path, str) or not path.startswith(prefix) or not isinstance(metadata, dict):
            continue
        version = metadata.get("version")
        if version is None:
            continue
        if not isinstance(version, str):
            raise AuditError("package-lock.json 的依赖 version 必须为字符串")
        resolved[path[len(prefix) :]] = _redact_dependency_spec(version)
    missing_resolution_nodes = sorted(set(declared) - set(resolved), key=_stable_text_key)
    if missing_resolution_nodes:
        raise AuditError(
            "package-lock.json 缺少直接依赖的精确解析节点："
            + ", ".join(missing_resolution_nodes)
        )
    return declared, resolved


def _difference_type(
    prototype_range: str,
    lock_version: str,
    baseline: str,
    *,
    prototype_state: str,
    lockfile_state: str,
) -> str:
    if prototype_state == "not-declared" and lockfile_state == "not-resolved":
        return "missing-from-prototype"
    if prototype_state == "not-declared" and lockfile_state == "resolved":
        return "transitive-only"
    normalized_baseline = baseline.rsplit(" ", 1)[-1] if " " in baseline else baseline
    if lock_version != normalized_baseline:
        return "version-mismatch"
    if prototype_range != normalized_baseline:
        return "semver-range-not-exact"
    return "exact-match-unverified"


def _parse_package_manager_declaration(value: str) -> tuple[str, str]:
    match = re.fullmatch(
        r"([A-Za-z0-9][A-Za-z0-9._-]*)@"
        r"(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)(?:\+[^\s]+)?",
        value,
    )
    if match is None:
        raise AuditError("package.json 的 packageManager 必须记录包管理器名称与精确版本")
    return match.group(1), match.group(2)


def _is_sensitive_query_key(raw_key: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", unquote_plus(raw_key).casefold())
    return normalized in {
        "token",
        "password",
        "passwd",
        "secret",
        "auth",
        "key",
        "apikey",
        "credential",
        "credentials",
    } or any(
        marker in normalized
        for marker in (
            "accesstoken",
            "authtoken",
            "refreshtoken",
            "idtoken",
            "sessiontoken",
            "privatetoken",
            "authorization",
            "clientsecret",
            "secretkey",
            "privatekey",
            "signingkey",
            "encryptionkey",
            "apikey",
        )
    )


def _redact_dependency_spec(value: str) -> str:
    for raw_key in re.findall(r"(?:^|[?&#;])([^?&#;=]+)=", value):
        if _is_sensitive_query_key(raw_key):
            return "REDACTED-SENSITIVE-SPEC"
    if re.search(r"(?i)://[^/?#\s]*@", value):
        return "REDACTED-SENSITIVE-SPEC"
    try:
        parsed = urlsplit(value)
    except ValueError:
        parsed = None
    if parsed is not None:
        if parsed.scheme and parsed.netloc and parsed.username is not None:
            return "REDACTED-SENSITIVE-SPEC"
        for component in (parsed.query, parsed.fragment):
            for pair in re.split(r"[&;]", component):
                raw_key = pair.partition("=")[0]
                if _is_sensitive_query_key(raw_key):
                    return "REDACTED-SENSITIVE-SPEC"
    return value


def build_dependency_gap(
    config: AuditConfig,
    controlled_snapshot: ControlledSnapshot | None = None,
) -> list[dict[str, str]]:
    """分离 package.json 范围、lock 精确版本与 PAB oracle。"""

    package_path = config.prototype_root / "package.json"
    lock_path = config.prototype_root / "package-lock.json"
    failures: list[tuple[str, AuditError]] = []
    package: dict[str, Any] | None = None
    lock: dict[str, Any] | None = None
    oracle: dict[str, str] | None = None

    try:
        package = (
            _load_json(package_path, config.workspace_root)
            if controlled_snapshot is None
            else _parse_json_bytes(
                _snapshot_bytes(controlled_snapshot, package_path, config.workspace_root),
                _relative(package_path, config.workspace_root),
            )
        )
    except AuditError as exc:
        failures.append(("package.json", exc))
    try:
        lock = (
            _load_json(lock_path, config.workspace_root)
            if controlled_snapshot is None
            else _parse_json_bytes(
                _snapshot_bytes(controlled_snapshot, lock_path, config.workspace_root),
                _relative(lock_path, config.workspace_root),
            )
        )
    except AuditError as exc:
        failures.append(("package-lock.json", exc))
    try:
        oracle = load_pab_oracle(config, controlled_snapshot)
    except PabOracleError as exc:
        failures.append(("PAB-1.0.0", exc))

    declared: dict[str, str] | None = None
    resolved: dict[str, str] | None = None
    engines: dict[str, Any] | None = None
    package_manager_npm: str | None = None
    package_manager_conflict: str | None = None
    if package is not None and lock is not None:
        try:
            declared, resolved = _dependency_maps(package, lock)
        except AuditError as exc:
            failures.append(("原型依赖契约", exc))
    if package is not None:
        engines_value = package.get("engines", {})
        if isinstance(engines_value, dict):
            engines = engines_value
        else:
            failures.append(
                ("package.json", AuditError("package.json 的 engines 必须为对象"))
            )
        package_manager = package.get("packageManager")
        if package_manager is not None:
            if not isinstance(package_manager, str):
                failures.append(
                    ("package.json", AuditError("package.json 的 packageManager 必须为字符串"))
                )
            else:
                try:
                    manager_name, manager_version = _parse_package_manager_declaration(
                        package_manager
                    )
                except AuditError as exc:
                    failures.append(("package.json", exc))
                else:
                    if manager_name == "npm":
                        package_manager_npm = manager_version
                    else:
                        package_manager_conflict = (
                            f"{manager_name}@{manager_version}"
                        )

    if failures:
        if len(failures) == 1:
            raise failures[0][1]
        raise DependencyInputError(failures)

    assert package is not None
    assert oracle is not None
    assert declared is not None
    assert resolved is not None
    assert engines is not None
    rows: list[dict[str, str]] = []

    for display, baseline in oracle.items():
        package_name, owner_story = PAB_DEPENDENCY_META[display]
        if display in {"OpenJDK", "Spring Boot", "PostgreSQL", "OpenAPI", "CloudEvents", "JSON Schema"}:
            prototype_state = "not-applicable"
            prototype_range = "NOT-APPLICABLE"
            lockfile_state = "not-applicable"
            lock_version = "NOT-APPLICABLE"
            difference = "not-applicable-to-frontend-prototype"
            classification = "reference-only"
            risk = "该 PAB 基线不由当前纯前端原型声明或 lockfile 证明。"
            disposition = f"由 {owner_story} 保留 PAB-1.0.0 精确基线 {baseline}；本 Story 仅记录 oracle。"
        elif package_name is None:
            engine_name = "node" if display == "Node.js" else "npm"
            manager_conflict = (
                package_manager_conflict if display == "npm" else None
            )
            runtime_declared = (
                manager_conflict is not None
                or (display == "npm" and package_manager_npm is not None)
                or engine_name in engines
            )
            engine_value = manager_conflict or (
                package_manager_npm
                if display == "npm" and package_manager_npm is not None
                else (
                    engines[engine_name]
                    if engine_name in engines
                    else "NOT-DECLARED"
                )
            )
            if not isinstance(engine_value, str):
                raise AuditError(f"package.json 的 engines.{engine_name} 必须为字符串")
            prototype_state = "declared" if runtime_declared else "not-declared"
            prototype_range = _redact_dependency_spec(engine_value)
            lockfile_state = "not-recorded"
            lock_version = "NOT-RECORDED"
            difference = (
                "package-manager-conflict"
                if manager_conflict is not None
                else "runtime-unverified"
            )
            classification = "unknown-blocked"
            if manager_conflict is not None:
                manager_name = manager_conflict.split("@", 1)[0]
                risk = (
                    f"packageManager 显式声明 {manager_name}，与 PAB-1.0.0 的 npm "
                    "基线冲突；现存 package-lock.json 不得消解该冲突。"
                )
                disposition = (
                    f"由 1.1c 移除或通过新 ADR 批准 {manager_name}，否则按 "
                    f"PAB-1.0.0 冻结 npm {baseline}；由 1.1d 产生运行证据。"
                )
            else:
                risk = (
                    "package.json 记录了运行时版本声明，但尚无受控运行命令或制品证据。"
                    if prototype_state == "declared"
                    else "原型未提供可核验的运行时精确版本；本机版本不得替代受控证据。"
                )
                disposition = f"由 1.1c 按 PAB-1.0.0 冻结 {display} {baseline}，并在 1.1d 产生运行证据。"
        else:
            prototype_state = (
                "declared" if package_name in declared else "not-declared"
            )
            lockfile_state = (
                "resolved" if package_name in resolved else "not-resolved"
            )
            prototype_range = declared.get(package_name, "NOT-DECLARED")
            lock_version = resolved.get(package_name, "NOT-RESOLVED")
            difference = _difference_type(
                prototype_range,
                lock_version,
                baseline,
                prototype_state=prototype_state,
                lockfile_state=lockfile_state,
            )
            if display in REFERENCE_ONLY_COMBINATIONS and prototype_state == "declared":
                classification = "reference-only"
            elif difference == "exact-match-unverified":
                classification = "reference-only"
            else:
                classification = "migrate"
            if difference == "missing-from-prototype":
                risk = "PAB 候选在原型中缺失，不能由当前原型行为证明兼容性。"
            elif difference == "transitive-only":
                risk = "该 PAB 包仅由 lockfile 传递提升，未被原型直接声明，不能当作生产候选。"
            elif difference == "version-mismatch":
                risk = "原型解析版本与 PAB 精确基线不同，组合兼容性未知。"
            else:
                risk = "即使版本接近或相同，仍缺生产 lock、兼容性与运行验收。"
            disposition = f"由 1.1c 迁移并精确锁定为 {baseline}；本 Story 不升级依赖。"
        rows.append(
            {
                "dependency": display,
                "packageName": package_name or "RUNTIME",
                "prototypeState": prototype_state,
                "prototypeRange": prototype_range,
                "lockfileState": lockfile_state,
                "lockfileVersion": lock_version,
                "pabBaseline": baseline,
                "differenceType": difference,
                "classification": classification,
                "migrationRisk": risk,
                "ownerStory": owner_story,
                "disposition": disposition,
            }
        )

    baseline_packages = {package for package, _ in PAB_DEPENDENCY_META.values() if package is not None}
    extra_packages = (set(declared) - baseline_packages) | REQUIRED_PROTOTYPE_EXTRAS
    for package_name in sorted(extra_packages, key=_stable_text_key):
        display = package_name
        prototype_state = "declared" if package_name in declared else "not-declared"
        lockfile_state = "resolved" if package_name in resolved else "not-resolved"
        prototype_range = declared.get(package_name, "NOT-DECLARED")
        lock_version = resolved.get(package_name, "NOT-RESOLVED")
        present = prototype_state == "declared" or lockfile_state == "resolved"
        rows.append(
            {
                "dependency": display,
                "packageName": package_name,
                "prototypeState": prototype_state,
                "prototypeRange": prototype_range,
                "lockfileState": lockfile_state,
                "lockfileVersion": lock_version,
                "pabBaseline": "NOT-FROZEN",
                "differenceType": "unfrozen-prototype-extra" if present else "not-present-and-not-frozen",
                "classification": "unknown-blocked",
                "migrationRisk": "PAB-1.0.0 未冻结该依赖，不能自动进入生产候选。",
                "ownerStory": "1.1c",
                "disposition": "在 1.1c 中移除、替换或在 1.1c 中通过新 ADR 批准；默认不得进入生产候选。",
            }
        )
    rows = sorted(rows, key=lambda row: _stable_text_key(row["dependency"]))
    gap_errors = validate_gap_rows(rows)
    if gap_errors:
        raise AuditError("生成的依赖差异未通过契约：\n- " + "\n- ".join(gap_errors))
    return rows


def validate_gap_rows(rows: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(rows, list):
        return ["根节点必须为数组"]
    if not rows:
        return ["依赖差异不得为空"]
    dependencies: list[str] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f"rows[{index}] 必须为对象")
            continue
        missing = sorted(GAP_FIELDS - set(row))
        unknown = sorted(set(row) - GAP_FIELDS)
        if missing:
            errors.append(f"rows[{index}] 缺少字段：{', '.join(missing)}")
        if unknown:
            errors.append(f"rows[{index}] 含未知字段：{', '.join(unknown)}")
        for field in GAP_FIELDS:
            if field in row and (not isinstance(row[field], str) or not row[field]):
                errors.append(f"rows[{index}].{field} 必须为非空字符串")
        if row.get("classification") not in CLASSIFICATIONS:
            errors.append(f"rows[{index}].classification 无效")
        if row.get("ownerStory") not in OWNER_STORIES:
            errors.append(f"rows[{index}].ownerStory 无效")
        if row.get("differenceType") not in GAP_DIFFERENCE_TYPES:
            errors.append(f"rows[{index}].differenceType 无效")
        if row.get("prototypeState") not in PROTOTYPE_STATES:
            errors.append(f"rows[{index}].prototypeState 无效")
        if row.get("lockfileState") not in LOCKFILE_STATES:
            errors.append(f"rows[{index}].lockfileState 无效")
        if isinstance(row.get("dependency"), str):
            dependencies.append(row["dependency"])
        if not GAP_FIELDS.issubset(row) or any(not isinstance(row.get(field), str) for field in GAP_FIELDS):
            continue

        dependency = row["dependency"]
        semantic_errors: list[str] = []
        prototype_state = row["prototypeState"]
        lockfile_state = row["lockfileState"]
        expected_prototype_markers = {
            "not-declared": "NOT-DECLARED",
            "not-applicable": "NOT-APPLICABLE",
            "unavailable": "UNAVAILABLE",
        }
        expected_lockfile_markers = {
            "not-resolved": "NOT-RESOLVED",
            "not-recorded": "NOT-RECORDED",
            "not-applicable": "NOT-APPLICABLE",
            "unavailable": "UNAVAILABLE",
        }
        if (
            prototype_state in expected_prototype_markers
            and row["prototypeRange"] != expected_prototype_markers[prototype_state]
        ):
            semantic_errors.append("prototypeState")
        if (
            lockfile_state in expected_lockfile_markers
            and row["lockfileVersion"] != expected_lockfile_markers[lockfile_state]
        ):
            semantic_errors.append("lockfileState")
        if dependency == "AUDIT-INPUT-BLOCKED":
            expected = {
                "packageName": "AUDIT-INPUT",
                "prototypeState": "unavailable",
                "prototypeRange": "UNAVAILABLE",
                "lockfileState": "unavailable",
                "lockfileVersion": "UNAVAILABLE",
                "pabBaseline": "UNAVAILABLE",
                "differenceType": "input-unavailable",
                "classification": "unknown-blocked",
                "ownerStory": "1.1c",
            }
            semantic_errors.extend(
                field for field, value in expected.items() if row[field] != value
            )
        elif dependency in PAB_DEPENDENCY_META:
            package_name, owner_story = PAB_DEPENDENCY_META[dependency]
            expected_package = package_name or "RUNTIME"
            if row["packageName"] != expected_package:
                semantic_errors.append("packageName")
            if row["ownerStory"] != owner_story:
                semantic_errors.append("ownerStory")
            if row["pabBaseline"] in {"NOT-FROZEN", "UNAVAILABLE"}:
                semantic_errors.append("pabBaseline")
            if dependency in NON_FRONTEND_PAB:
                expected = (
                    prototype_state == "not-applicable"
                    and row["prototypeRange"] == "NOT-APPLICABLE"
                    and lockfile_state == "not-applicable"
                    and row["lockfileVersion"] == "NOT-APPLICABLE"
                    and row["differenceType"] == "not-applicable-to-frontend-prototype"
                    and row["classification"] == "reference-only"
                )
                if not expected:
                    semantic_errors.append("nonFrontendPab")
            elif package_name is None:
                package_manager_conflict = False
                if (
                    dependency == "npm"
                    and row["differenceType"] == "package-manager-conflict"
                ):
                    try:
                        manager_name, _ = _parse_package_manager_declaration(
                            row["prototypeRange"]
                        )
                    except AuditError:
                        manager_name = "npm"
                    package_manager_conflict = manager_name != "npm"
                expected = (
                    prototype_state in {"declared", "not-declared"}
                    and row["lockfileVersion"] == "NOT-RECORDED"
                    and lockfile_state == "not-recorded"
                    and row["classification"] == "unknown-blocked"
                    and (
                        package_manager_conflict
                        or row["differenceType"] == "runtime-unverified"
                    )
                )
                if not expected:
                    semantic_errors.append("runtimePab")
            else:
                if prototype_state not in {"declared", "not-declared"}:
                    semantic_errors.append("prototypeState")
                if lockfile_state not in {"resolved", "not-resolved"}:
                    semantic_errors.append("lockfileState")
                expected_difference = _difference_type(
                    row["prototypeRange"],
                    row["lockfileVersion"],
                    row["pabBaseline"],
                    prototype_state=prototype_state,
                    lockfile_state=lockfile_state,
                )
                if row["differenceType"] != expected_difference:
                    semantic_errors.append("differenceType")
                expected_classification = (
                    "reference-only"
                    if (
                        dependency in REFERENCE_ONLY_COMBINATIONS
                        and prototype_state == "declared"
                    )
                    or expected_difference == "exact-match-unverified"
                    else "migrate"
                )
                if row["classification"] != expected_classification:
                    semantic_errors.append("classification")
        else:
            if prototype_state not in {"declared", "not-declared"}:
                semantic_errors.append("prototypeState")
            if lockfile_state not in {"resolved", "not-resolved"}:
                semantic_errors.append("lockfileState")
            present = prototype_state == "declared" or lockfile_state == "resolved"
            expected_difference = (
                "unfrozen-prototype-extra" if present else "not-present-and-not-frozen"
            )
            if not (
                row["packageName"] == dependency
                and row["pabBaseline"] == "NOT-FROZEN"
                and row["differenceType"] == expected_difference
                and row["classification"] == "unknown-blocked"
                and row["ownerStory"] == "1.1c"
            ):
                semantic_errors.append("unfrozenPrototypeExtra")
        if semantic_errors:
            errors.append(
                f"rows[{index}] 跨字段语义矛盾：{', '.join(sorted(set(semantic_errors)))}"
            )
    if len(dependencies) != len(set(dependencies)):
        errors.append("dependency 必须唯一")
    if dependencies != sorted(dependencies, key=_stable_text_key):
        errors.append("依赖差异必须稳定排序")
    if "AUDIT-INPUT-BLOCKED" in dependencies and len(rows) != 1:
        errors.append("AUDIT-INPUT-BLOCKED 失败交接行必须独占 gap")
    return errors


def _md(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def render_gap_report(
    rows: list[dict[str, str]],
    config: AuditConfig,
    generation_id: str | None = None,
    snapshot_metrics_receipt: str | None = None,
) -> str:
    """生成人可读表格和机器可读 JSON，明确区分事实与后续证据。"""

    resolved_generation_id = generation_id or _generation_id(
        config, _capture_controlled_snapshot(config)
    )
    owner_summary = "、".join(
        f"Story {owner}" for owner in sorted({row["ownerStory"] for row in rows})
    )
    lines = [
        "# Story 1.1a：原型与 PAB-1.0.0 差异报告",
        "",
        f"- Generation ID：`{resolved_generation_id}`",
        f"- 核验日期：{config.generated_at[:10]}",
        f"- 原型来源：`{_relative(config.prototype_root, config.workspace_root)}`",
        "- 对账 oracle：PAB-1.0.0 / AUTH-2026-07-17-001（只读批准基线）",
        f"- 后续 owner：表中各项按行唯一绑定 {owner_summary}；供应链运行证据由 Story 1.1d 产生。",
        "",
        "## 本 Story 核验的当前事实",
        "",
        "下表只陈述 `package.json` 的声明范围、`package-lock.json` 的解析版本和 PAB 静态 oracle。当前 `node_modules` 的存在不替代 lockfile，也不证明生产兼容性。",
        "",
        "| 依赖 | 声明状态 | 原型声明范围 | lock 状态 | lockfile 解析版本 | PAB 精确基线 | 差异 | 分类 | 迁移风险 | owner | 处置 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for row in rows:
        lines.append(
            "| "
            + " | ".join(
                _md(row[key])
                for key in (
                    "dependency",
                    "prototypeState",
                    "prototypeRange",
                    "lockfileState",
                    "lockfileVersion",
                    "pabBaseline",
                    "differenceType",
                    "classification",
                    "migrationRisk",
                    "ownerStory",
                    "disposition",
                )
            )
            + " |"
        )
    lines.extend(
        [
            "",
            "## 1.1c / 1.1d 尚未产生的证据",
            "",
            "本报告不声称以下证据已经产生或通过：精确生产 lock、组合兼容性、可复现构建、SBOM、provenance、签名、漏洞、许可证、制品提升、浏览器/WebView、性能、视觉与无障碍证据。",
            "",
            "- Story 1.1c：批准生产前端组合、新 ADR（如确需未冻结依赖）、精确 lock、兼容性及浏览器/WebView profile。",
            "- Story 1.1d：实际生成可复现构建、SBOM、provenance、签名、漏洞/许可证扫描，以及浏览器/WebView、视觉与无障碍实际报告；任一强制证据未通过时禁止制品提升。",
            "",
            "## 机器可读差异",
            "",
            f"<!-- audit-generation-id: {resolved_generation_id} -->",
            "<!-- audit-snapshot-metrics-receipt: "
            f"{snapshot_metrics_receipt or EMPTY_SHA256} -->",
            "<!-- dependency-gap-json:start -->",
            "```json",
            json.dumps(rows, ensure_ascii=False, sort_keys=True, indent=2),
            "```",
            "<!-- dependency-gap-json:end -->",
            "",
        ]
    )
    return "\n".join(lines)


def render_inventory_report(inventory: dict[str, Any]) -> str:
    assets = inventory["assets"]
    by_id = {item["assetId"]: item for item in assets}

    def absent(asset_id: str) -> str:
        return "未发现" if not by_id[asset_id].get("present", False) else "已发现（仍需核验）"

    manifest_source = str(by_id["prototype.package-manifest"]["pathOrSource"]).split("; ", 1)[0]
    prototype_source = Path(manifest_source).parent.as_posix()
    prototype_stack_fact = str(
        by_id["prototype.package-manifest"]["dispositionReason"]
    ).split("；", 1)[0]
    source_metrics = by_id["prototype.source"].get("metrics")
    source_file_count = (
        source_metrics.get("regularFileCount", "不可用")
        if isinstance(source_metrics, dict)
        else "不可用"
    )

    lines = [
        "# Story 1.1a：生产资产清单",
        "",
        f"- 清单 Schema：{inventory['schemaVersion']}",
        f"- 审计脚本版本：{inventory['auditVersion']}",
        f"- Generation ID：`{inventory['generationId']}`",
        f"- 生成时间：{inventory['generatedAt']}",
        f"- 核验日期：{inventory['verificationDate']}",
        f"- 受控输入摘要：`{inventory['controlledInputDigest']}`",
        "",
        "## 当前可见事实与边界",
        "",
        f"- Git 仓库元数据：{absent('workspace.git-repository')}。远程 URL、分支和 commit 不可据此推断。",
        f"- 生产后端/数据库迁移：{absent('workspace.production-backend')}。Architecture 中的 `backend/` 是 1.1b 目标 seed。",
        f"- 生产前端工程：{absent('workspace.production-frontend')}。任何现存 `frontend/` 仍须由 1.1b 核验 owner、来源与真实性。",
        f"- 公共契约定义：{absent('workspace.contract-definitions')}。任何现存 `contracts/` 不自动等同于已批准或已兼容的运行契约。",
        f"- CI 定义：{absent('workspace.ci-definitions')}。本 Story 不声称构建、扫描或签名通过。",
        f"- 部署/基础设施工程：{absent('workspace.deployment-engineering')}。不得虚构部署平台或制品库。",
        f"- `{prototype_source}` 是 {prototype_stack_fact}；该事实从同 generation 的 package manifest 与 lockfile 动态提取，PAB 差异详见配套报告。源码与页面意图仅作迁移输入，`dist` 不是可提升制品，`node_modules` 不是依赖分发源。",
        f"- 原型源码受控文件数：{source_file_count}。机器计数按审计契约忽略 `.DS_Store`；原始目录枚举若包含此类元数据，数字可能更大。",
        "- 原型注释中的“微前端”“网关”“各微服务”属于未受控原型假设，不覆盖 AD-1 模块化单体和 AD-28。",
        "",
        "## 资产明细",
        "",
        "| assetId | 类型 | 路径/来源 | owner | 版本/摘要 | 日期 | 分类 | 建议 | owner Story | 风险 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for item in assets:
        lines.append(
            "| "
            + " | ".join(
                _md(str(value))
                for value in (
                    item["assetId"],
                    item["type"],
                    item["pathOrSource"],
                    item["owner"],
                    item["versionOrDigest"],
                    item["verifiedDate"],
                    item["classification"],
                    item.get("recommendedDisposition", "—"),
                    item["ownerStory"],
                    item["risk"],
                )
            )
            + " |"
        )
    lines.extend(
        [
            "",
            "## unknown-blocked 风险清单",
            "",
        ]
    )
    for item in inventory["unresolvedItems"]:
        lines.append(
            f"- `{item['assetId']}` → {item['ownerStory']}：{item['issue']} 影响：{item['impact']}"
        )
    evidence = inventory["executionEvidence"]
    lines.extend(
        [
            "",
            "## 执行证据",
            "",
            f"- 生成：`{evidence['generateCommand']}`",
            f"- 重放命令（命令本身不代表已执行）：`{evidence['replayCommand']}`",
            f"- 脚本：{evidence['scriptVersion']} / `{evidence['scriptSha256']}`",
            f"- 输入范围：{', '.join(f'`{item}`' for item in evidence['inputScope'])}",
            f"- 结果范围：{evidence['resultScope']}；结果：{evidence['result']}；未解决项：{evidence['unresolvedCount']}",
            f"- 测试与漂移检查验收回执：{evidence['acceptanceEvidenceLocation']}（不属于机器清单自证范围）。",
            "",
            "| 步骤 | 命令 | 状态 | 退出码 |",
            "| --- | --- | --- | --- |",
            *[
                f"| {_md(step['name'])} | `{_md(step['command'])}` | {step['status']} | "
                f"{step['exitCode'] if step['exitCode'] is not None else '—'} |"
                for step in evidence["steps"]
            ],
            "",
            "## 机器可读清单",
            "",
            "<!-- asset-inventory-json:start -->",
            "```json",
            json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2),
            "```",
            "<!-- asset-inventory-json:end -->",
            "",
        ]
    )
    return "\n".join(lines)


def _extract_json_block(text: str, start: str, end: str, source: Path) -> Any:
    if text.count(start) != 1 or text.count(end) != 1:
        raise AuditError(f"机器可读 JSON 区块标记必须且只能出现一次：{source.name}")
    pattern = re.escape(start) + r"\s*```json\s*(.*?)\s*```\s*" + re.escape(end)
    matches = list(re.finditer(pattern, text, re.DOTALL))
    if len(matches) != 1:
        raise AuditError(f"机器可读 JSON 区块缺失：{source.name}")
    match = matches[0]
    try:
        return _strict_json_loads(match.group(1))
    except _DuplicateJsonKeyError as exc:
        raise AuditError(f"机器可读 JSON 含重复键：{source.name}") from exc
    except _NonFiniteJsonConstantError as exc:
        raise AuditError(f"机器可读 JSON 含非有限数字：{source.name}") from exc
    except _JsonIntegerRangeError as exc:
        raise AuditError(f"机器可读 JSON 整数超出解析限制：{source.name}") from exc
    except json.JSONDecodeError as exc:
        raise AuditError(f"机器可读 JSON 解析失败：{source.name}") from exc


def parse_inventory_report(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise AuditError(f"审计证据不存在：{path.name}")
    value = _extract_json_block(
        _read_utf8(path),
        "<!-- asset-inventory-json:start -->",
        "<!-- asset-inventory-json:end -->",
        path,
    )
    if not isinstance(value, dict):
        raise AuditError("资产清单 JSON 根节点必须为对象")
    return value


def parse_gap_report(path: Path) -> list[dict[str, str]]:
    _, _, rows = _parse_gap_report_with_generation(path)
    return rows


def _parse_gap_report_with_generation(
    path: Path,
) -> tuple[str, str, list[dict[str, str]]]:
    if not path.exists():
        raise AuditError(f"审计证据不存在：{path.name}")
    text = _read_utf8(path)
    generation_matches = re.findall(
        r"<!--\s*audit-generation-id:\s*(sha256:[0-9a-f]{64})\s*-->", text
    )
    if len(generation_matches) != 1:
        raise AuditError(f"依赖差异 generation ID 必须且只能出现一次：{path.name}")
    value = _extract_json_block(
        text,
        "<!-- dependency-gap-json:start -->",
        "<!-- dependency-gap-json:end -->",
        path,
    )
    errors = validate_gap_rows(value)
    if errors:
        raise AuditError("依赖差异行校验失败：\n- " + "\n- ".join(errors))
    receipt_matches = re.findall(
        r"<!--\s*audit-snapshot-metrics-receipt:\s*(sha256:[0-9a-f]{64})\s*-->",
        text,
    )
    if len(receipt_matches) != 1:
        raise AuditError(f"snapshotOnly 指标回执必须且只能出现一次：{path.name}")
    return generation_matches[0], receipt_matches[0], value


_PROCESS_LOCKS_GUARD = threading.Lock()
_PROCESS_LOCKS: dict[str, threading.RLock] = {}


def _artifact_control_paths(workspace_root: Path) -> tuple[Path, Path]:
    """使用工作区级固定锁，覆盖任何相交的自定义三产物路径集合。"""

    root = workspace_root.resolve()
    return (
        root / ".audit-production-assets.lock",
        root / ".audit-production-assets.transaction.json",
    )


def _fsync_directory(path: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _stage_text(
    path: Path,
    content: str,
    *,
    suffix: str = ".tmp",
    mode: int | None = None,
) -> Path:
    if path.is_symlink():
        raise AuditError(f"拒绝覆盖符号链接输出：{path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=suffix, dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        target_mode = mode
        if target_mode is None:
            target_mode = stat.S_IMODE(path.stat(follow_symlinks=False).st_mode) if path.exists() else 0o644
        os.fchmod(descriptor, target_mode)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        return temporary
    except BaseException:
        if temporary.exists():
            temporary.unlink()
        raise


def _backup_output(path: Path) -> Path:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    source_descriptor = os.open(path, flags)
    backup_descriptor, backup_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".bak", dir=path.parent
    )
    backup = Path(backup_name)
    try:
        source_stat = os.fstat(source_descriptor)
        if not stat.S_ISREG(source_stat.st_mode):
            raise AuditError(f"输出不是普通文件：{path.name}")
        os.fchmod(backup_descriptor, stat.S_IMODE(source_stat.st_mode))
        with os.fdopen(source_descriptor, "rb") as source, os.fdopen(
            backup_descriptor, "wb"
        ) as target:
            source_descriptor = -1
            backup_descriptor = -1
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
            target.flush()
            os.fsync(target.fileno())
        return backup
    except BaseException:
        if source_descriptor >= 0:
            os.close(source_descriptor)
        if backup_descriptor >= 0:
            os.close(backup_descriptor)
        if backup.exists():
            backup.unlink()
        raise


def _write_transaction_journal(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = _stage_text(
        path,
        json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        suffix=".journal.tmp",
        mode=0o600,
    )
    os.replace(temporary, path)
    _fsync_directory(path.parent)


def _transaction_file_digest(path: Path) -> str:
    digest, _ = _stream_file_digest(path)
    return f"sha256:{digest}"


def _publication_target_state(path: Path) -> PublicationTargetState:
    if path.is_symlink():
        raise AuditError(f"拒绝覆盖符号链接输出：{path.name}")
    if not path.exists():
        return PublicationTargetState(False, None, None)
    mode = path.stat(follow_symlinks=False).st_mode
    if not stat.S_ISREG(mode):
        raise AuditError(f"发布目标不是普通文件：{path.name}")
    return PublicationTargetState(
        True,
        _transaction_file_digest(path),
        stat.S_IMODE(mode),
    )


def _assert_publication_target_state(
    path: Path,
    expected: PublicationTargetState,
) -> None:
    current = _publication_target_state(path)
    if current != expected:
        raise AuditError(f"发布目标被外部改变，拒绝覆盖：{path.name}")


def _is_managed_transaction_temp(candidate: Path, target: Path, suffix: str) -> bool:
    """只接受由本工具为对应目标在同目录创建的随机临时文件。"""

    return (
        candidate.is_absolute()
        and candidate.parent.resolve(strict=False) == target.parent.resolve(strict=False)
        and candidate.name.startswith(f".{target.name}.")
        and candidate.name.endswith(suffix)
        and candidate.resolve(strict=False) != target.resolve(strict=False)
    )


def _recover_transaction(journal_path: Path, expected_targets: set[Path]) -> str:
    if not journal_path.exists() and not journal_path.is_symlink():
        return "none"
    if journal_path.is_symlink():
        raise AuditError("事务日志是符号链接，拒绝恢复")
    try:
        payload = _strict_json_loads(_read_bytes(journal_path).decode("utf-8"))
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        _DuplicateJsonKeyError,
        _NonFiniteJsonConstantError,
        _JsonIntegerRangeError,
    ) as exc:
        raise AuditError("事务日志损坏，拒绝猜测恢复") from exc
    if not isinstance(payload, dict) or payload.get("version") not in {1, 2}:
        raise AuditError("事务日志版本无效，拒绝恢复")
    records = payload.get("entries")
    if not isinstance(records, list) or len(records) != len(expected_targets):
        raise AuditError("事务日志目标集合无效，拒绝恢复")
    journal_targets = {
        Path(str(record.get("target"))).resolve(strict=False)
        for record in records
        if isinstance(record, dict)
    }
    if journal_targets != {path.resolve(strict=False) for path in expected_targets}:
        raise AuditError("事务日志目标与当前产物集合不一致，拒绝恢复")

    managed_temporaries: set[Path] = set()
    for record in records:
        if not isinstance(record, dict):
            raise AuditError("事务日志条目无效，拒绝恢复")
        target = Path(str(record.get("target", "")))
        staged = Path(str(record.get("staged", "")))
        backup_value = record.get("backup")
        backup = Path(str(backup_value)) if backup_value else None
        if not _is_managed_transaction_temp(staged, target, ".tmp"):
            raise AuditError(f"事务日志 staged 临时路径不受控：{target.name}")
        if record.get("existed") is True:
            if backup is None or not _is_managed_transaction_temp(backup, target, ".bak"):
                raise AuditError(f"事务日志 backup 临时路径不受控：{target.name}")
        elif backup is not None:
            raise AuditError(f"事务日志为新目标记录了非法备份：{target.name}")
        for temporary in (staged, backup):
            if temporary is None:
                continue
            resolved = temporary.resolve(strict=False)
            if resolved == journal_path.resolve(strict=False) or resolved in managed_temporaries:
                raise AuditError("事务日志临时路径重复或覆盖控制文件")
            managed_temporaries.add(resolved)

    if payload.get("version") == 2 and payload.get("state") == "committed":
        for record in records:
            target = Path(str(record["target"]))
            if not target.exists() or _transaction_file_digest(target) != record.get("newDigest"):
                raise AuditError(f"已提交事务的目标不完整：{target.name}")
        for record in records:
            Path(str(record["staged"])).unlink(missing_ok=True)
            backup_value = record.get("backup")
            if backup_value:
                Path(str(backup_value)).unlink(missing_ok=True)
        journal_path.unlink(missing_ok=True)
        try:
            _fsync_directory(journal_path.parent)
        except OSError:
            pass
        return "committed"
    if payload.get("version") == 2 and payload.get("state") != "prepared":
        raise AuditError("事务日志状态无效，拒绝恢复")

    for record in reversed(records):
        if not isinstance(record, dict):
            raise AuditError("事务日志条目无效，拒绝恢复")
        target = Path(str(record["target"]))
        staged = Path(str(record["staged"]))
        backup_value = record.get("backup")
        backup = Path(str(backup_value)) if backup_value else None
        existed = record.get("existed") is True
        old_digest = record.get("oldDigest")
        old_mode = record.get("oldMode")
        if existed:
            if target.exists():
                current_digest = _transaction_file_digest(target)
                if current_digest == old_digest:
                    continue
                if current_digest != record.get("newDigest"):
                    raise AuditError(f"事务恢复检测到外部修改：{target.name}")
            else:
                raise AuditError(f"事务恢复检测到目标意外缺失：{target.name}")
            if backup is None or not backup.exists() or backup.is_symlink():
                raise AuditError(f"事务恢复缺少旧版备份：{target.name}")
            descriptor, recovery_name = tempfile.mkstemp(
                prefix=f".{target.name}.", suffix=".recover", dir=target.parent
            )
            recovery = Path(recovery_name)
            try:
                if not isinstance(old_mode, int):
                    raise AuditError(f"事务日志缺少权限元数据：{target.name}")
                os.fchmod(descriptor, old_mode)
                with backup.open("rb") as source, os.fdopen(descriptor, "wb") as destination:
                    descriptor = -1
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
                    destination.flush()
                    os.fsync(destination.fileno())
                os.replace(recovery, target)
                _fsync_directory(target.parent)
            finally:
                if descriptor >= 0:
                    os.close(descriptor)
                recovery.unlink(missing_ok=True)
        elif target.exists():
            new_digest = record.get("newDigest")
            if new_digest != _transaction_file_digest(target):
                raise AuditError(f"事务恢复检测到外部修改：{target.name}")
            target.unlink()
            _fsync_directory(target.parent)
        staged.unlink(missing_ok=True)

    for record in records:
        if isinstance(record, dict):
            Path(str(record.get("staged", ""))).unlink(missing_ok=True)
            backup_value = record.get("backup")
            if backup_value:
                Path(str(backup_value)).unlink(missing_ok=True)
    journal_path.unlink()
    _fsync_directory(journal_path.parent)
    return "rolled-back"


@contextmanager
def _artifact_guard(paths: ArtifactPaths, workspace_root: Path, *, write: bool):
    lock_path, journal_path = _artifact_control_paths(workspace_root)
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock_key = str(lock_path.resolve(strict=False))
    with _PROCESS_LOCKS_GUARD:
        process_lock = _PROCESS_LOCKS.setdefault(lock_key, threading.RLock())
    with process_lock:
        flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(lock_path, flags, 0o600)
        try:
            if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                raise AuditError("发布锁不是普通文件")
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            targets = {paths.inventory, paths.schema, paths.gap}
            _recover_transaction(journal_path, targets)
            if not write:
                fcntl.flock(descriptor, fcntl.LOCK_SH)
            yield journal_path
        finally:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)


def _transactional_write(
    entries: list[tuple[Path, str]],
    *,
    journal_path: Path | None = None,
    generation_id: str = "UNSPECIFIED",
    expected_target_states: dict[Path, PublicationTargetState] | None = None,
    precommit_validator: Callable[[], None] | None = None,
) -> None:
    """用持久事务日志原子发布；日志存在时，下一命令会幂等恢复旧集合。"""

    targets = {path for path, _ in entries}
    if journal_path is None:
        common_parent = Path(os.path.commonpath([str(path.parent) for path in targets]))
        journal_path = common_parent / ".audit-production-assets-standalone.transaction.json"
    staged: dict[Path, Path] = {}
    backups: dict[Path, Path] = {}
    existed: dict[Path, bool] = {}
    records: list[dict[str, Any]] = []
    journal_written = False
    completed = False
    try:
        expected_states = (
            expected_target_states
            if expected_target_states is not None
            else {path: _publication_target_state(path) for path, _ in entries}
        )
        if set(expected_states) != targets:
            raise AuditError("发布目标基线集合与产物集合不一致")
        for path, _ in entries:
            _assert_publication_target_state(path, expected_states[path])
        for path, content in entries:
            existed[path] = expected_states[path].existed
            mode = expected_states[path].mode if existed[path] else 0o644
            if mode is None:  # pragma: no cover - PublicationTargetState 保证
                raise AuditError(f"发布目标缺少权限基线：{path.name}")
            staged[path] = _stage_text(path, content, mode=mode)
        for path, _ in entries:
            _assert_publication_target_state(path, expected_states[path])
            if existed[path]:
                backups[path] = _backup_output(path)
            records.append(
                {
                    "target": str(path.resolve(strict=False)),
                    "staged": str(staged[path].resolve(strict=False)),
                    "backup": str(backups[path].resolve(strict=False)) if path in backups else None,
                    "existed": existed[path],
                    "oldMode": expected_states[path].mode,
                    "oldDigest": expected_states[path].digest,
                    "newDigest": _transaction_file_digest(staged[path]),
                }
            )
        if precommit_validator is not None:
            precommit_validator()
        for path, _ in entries:
            _assert_publication_target_state(path, expected_states[path])
        _write_transaction_journal(
            journal_path,
            {
                "version": 2,
                "state": "prepared",
                "generationId": generation_id,
                "entries": records,
            },
        )
        journal_written = True
        for path, _ in entries:
            _assert_publication_target_state(path, expected_states[path])
            os.replace(staged[path], path)
            _fsync_directory(path.parent)

        _write_transaction_journal(
            journal_path,
            {
                "version": 2,
                "state": "committed",
                "generationId": generation_id,
                "entries": records,
            },
        )
        try:
            journal_path.unlink()
            _fsync_directory(journal_path.parent)
        except OSError:
            # 新 generation 已由 committed 日志确认；残留日志会在下一次加锁时清理。
            pass
        journal_written = False
        completed = True
    except Exception as exc:
        if journal_written:
            try:
                recovery_result = _recover_transaction(journal_path, targets)
                journal_written = False
            except AuditError as recovery_exc:
                raise AuditError(f"事务发布失败且恢复不完整：{recovery_exc}") from exc
            if recovery_result == "committed":
                raise AuditError("事务已提交，但持久化确认失败；产物保持新 generation") from exc
        if isinstance(exc, AuditError):
            raise
        raise AuditError("事务发布失败；三份证据已恢复到提交前版本") from exc
    finally:
        if completed or not journal_written:
            for temporary in [*staged.values(), *backups.values()]:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass


def _atomic_write(path: Path, content: str) -> None:
    _transactional_write([(path, content)])


def _outputs_would_create_workspace_candidate(
    root: Path,
    outputs: list[Path],
    *,
    audit_schema_output: Path,
) -> bool:
    """在发布前模拟输出文件与其父目录，防止输出反向变成审计输入。"""

    virtual_files = {path.resolve(strict=False) for path in outputs}
    resolved_schema_output = audit_schema_output.resolve(strict=False)

    def file_exists(path: Path) -> bool:
        resolved = path.resolve(strict=False)
        return resolved in virtual_files or path.exists() or path.is_symlink()

    def directory_exists(path: Path) -> bool:
        resolved = path.resolve(strict=False)
        return path.is_dir() or any(
            _is_relative_to(output, resolved) and output != resolved
            for output in virtual_files
        )

    literal_candidate_directories = {
        ".buildkite",
        ".circleci",
        "backend",
        "contracts",
        "deploy",
        "frontend",
        "helm",
        "infra",
        "k8s",
        "kubernetes",
        "manifests",
    }
    for output in virtual_files:
        relative = output.relative_to(root)
        relative_parts = relative.parts
        if any(
            relative_parts[index : index + 2] == (".github", "workflows")
            for index in range(len(relative_parts) - 1)
        ):
            return True
        name = output.name
        creates_contract_fingerprint = (
            name in CONTRACT_ROOT_FINGERPRINTS or name.endswith(".schema.json")
        )
        is_excludable_audit_schema = (
            output == resolved_schema_output and name.endswith(".json")
        )
        if (
            name
            in (
                BACKEND_PROJECT_FINGERPRINTS
                | FRONTEND_PROJECT_FINGERPRINTS
                | CI_ROOT_FINGERPRINTS
                | DEPLOYMENT_FIRST_LEVEL_FINGERPRINTS
            )
            or name.endswith(".tf")
            or name.endswith(".tf.json")
            or (creates_contract_fingerprint and not is_excludable_audit_schema)
        ) and not (output.exists() or output.is_symlink()):
            return True
        current = root
        for part in relative.parts[:-1]:
            current /= part
            if part in literal_candidate_directories and not current.exists():
                return True

    possible_projects = {
        parent
        for output in virtual_files
        for parent in output.parents
        if _is_relative_to(parent, root) and parent != root
    } | {root}
    for project in possible_projects:
        existed_before = (
            (project / "package.json").is_file()
            and any((project / name).is_dir() for name in FRONTEND_SOURCE_ROOTS)
            or (
                (project / "package.json").is_file()
                and (project / "index.html").is_file()
            )
        )
        exists_after = file_exists(project / "package.json") and (
            any(directory_exists(project / name) for name in FRONTEND_SOURCE_ROOTS)
            or file_exists(project / "index.html")
        )
        if exists_after and not existed_before:
            return True
    return False


def _validate_artifact_paths(config: AuditConfig, paths: ArtifactPaths) -> None:
    root = config.workspace_root.resolve()
    outputs = [path.resolve(strict=False) for path in (paths.inventory, paths.schema, paths.gap)]
    if len(set(outputs)) != 3:
        raise AuditError("三份输出路径必须互异")
    if len({str(output).casefold() for output in outputs}) != 3:
        raise AuditError("三份输出路径不得仅以字母大小写区分")
    for index, output in enumerate(outputs):
        for other in outputs[index + 1 :]:
            if _is_relative_to(output, other) or _is_relative_to(other, output):
                raise AuditError("三份输出路径不得存在父子路径关系")
    controlled_inputs = {
        path.resolve(strict=False)
        for path in _controlled_input_paths(
            config,
            excluded_paths=tuple(outputs),
        )
    }
    lock_path, journal_path = _artifact_control_paths(root)
    control_files = {
        lock_path.resolve(strict=False),
        journal_path.resolve(strict=False),
        (root / ".audit-production-assets-standalone.transaction.json").resolve(strict=False),
    }
    for output in outputs:
        if not _is_relative_to(output, root):
            raise AuditError("输出路径逃逸工作区")
        if output in control_files:
            raise AuditError(f"输出路径不得覆盖审计控制文件：{_relative(output, root)}")
        if _is_relative_to(output, config.prototype_root.resolve(strict=False)):
            raise AuditError(f"输出路径不得位于只读输入范围：{_relative(output, root)}")
        if any(
            _is_relative_to(output, controlled)
            or _is_relative_to(controlled, output)
            for controlled in controlled_inputs
        ):
            raise AuditError(f"输出路径不得与只读输入或受控输入范围重叠：{_relative(output, root)}")
    if _outputs_would_create_workspace_candidate(
        root,
        outputs,
        audit_schema_output=paths.schema,
    ):
        raise AuditError("输出路径发布后会形成新的生产资产发现指纹")
    for output in (paths.inventory, paths.schema, paths.gap):
        if output.is_symlink():
            raise AuditError(f"拒绝覆盖符号链接输出：{output.name}")


def _blocked_gap(error: AuditError) -> list[dict[str, str]]:
    safe_error = _safe_audit_error_detail(error)
    return [
        {
            "dependency": "AUDIT-INPUT-BLOCKED",
            "packageName": "AUDIT-INPUT",
            "prototypeState": "unavailable",
            "prototypeRange": "UNAVAILABLE",
            "lockfileState": "unavailable",
            "lockfileVersion": "UNAVAILABLE",
            "pabBaseline": "UNAVAILABLE",
            "differenceType": "input-unavailable",
            "classification": "unknown-blocked",
            "migrationRisk": safe_error,
            "ownerStory": "1.1c",
            "disposition": "修复必需输入的缺失、权限或格式问题后重新生成；不得推断生产候选。",
        }
    ]


def _published_generation_markers(paths: ArtifactPaths) -> list[str | None]:
    inventory_text = _read_utf8(paths.inventory)
    inventory_value = _extract_json_block(
        inventory_text,
        "<!-- asset-inventory-json:start -->",
        "<!-- asset-inventory-json:end -->",
        paths.inventory,
    )
    inventory_id = inventory_value.get("generationId") if isinstance(inventory_value, dict) else None
    schema_value = _load_json(paths.schema, paths.schema.parent)
    schema_id = schema_value.get("x-generationId")
    gap_text = _read_utf8(paths.gap)
    gap_matches = re.findall(
        r"<!--\s*audit-generation-id:\s*(sha256:[0-9a-f]{64})\s*-->", gap_text
    )
    gap_id = gap_matches[0] if len(gap_matches) == 1 else None
    return [
        inventory_id if isinstance(inventory_id, str) else None,
        schema_id if isinstance(schema_id, str) else None,
        gap_id,
    ]


def generate_artifacts(config: AuditConfig, paths: ArtifactPaths, *, force: bool = False) -> None:
    _validate_artifact_paths(config, paths)
    with _artifact_guard(paths, config.workspace_root, write=True) as journal_path:
        _validate_artifact_paths(config, paths)
        _generate_artifacts_locked(config, paths, force=force, journal_path=journal_path)


def _generate_artifacts_locked(
    config: AuditConfig,
    paths: ArtifactPaths,
    *,
    force: bool,
    journal_path: Path,
) -> None:
    outputs = (paths.inventory, paths.schema, paths.gap)
    target_states = {
        path: _publication_target_state(path)
        for path in outputs
    }
    existing = [path for path in outputs if target_states[path].existed]
    if existing and not force:
        names = ", ".join(path.name for path in existing)
        raise AuditError(f"拒绝覆盖已有审计证据：{names}；请先运行 check，确需重建时显式使用 --force")
    if force and existing and len(existing) != len(outputs):
        raise AuditError("拒绝把部分产物集合用作事务回滚基线")
    if force and len(existing) == len(outputs):
        markers = _published_generation_markers(paths)
        present_markers = [marker for marker in markers if marker is not None]
        if present_markers and (
            len(present_markers) != len(markers) or len(set(present_markers)) != 1
        ):
            raise AuditError("已有三份产物属于混合 generation，拒绝覆盖损坏基线")

    excluded_outputs = (paths.inventory, paths.schema, paths.gap)
    snapshot_before = _capture_controlled_snapshot(
        config,
        excluded_paths=excluded_outputs,
    )
    generation_id = _new_generation_id()
    blocked_error: AuditError | None = None
    try:
        gap_rows = build_dependency_gap(config, snapshot_before)
    except AuditError as exc:
        blocked_error = exc
        gap_rows = _blocked_gap(exc)
    evidence = _execution_evidence(config, paths, force=force, failed=blocked_error is not None)
    blocked_assets = (
        {"planning.architecture-spine", "planning.pab-1.0.0"}
        if _has_pab_oracle_failure(blocked_error)
        else set()
    )
    inventory = build_inventory(
        config,
        evidence,
        blocked_assets,
        controlled_snapshot=snapshot_before,
        generation_id=generation_id,
    )
    if blocked_error is None and inventory["executionEvidence"]["result"] == "failed":
        failed_assets = _failed_input_asset_ids(inventory["assets"])
        if not failed_assets:
            raise AuditError("执行证据失败但未找到对应输入资产")
        blocked_error = AuditError("必需受控输入不可读：" + ", ".join(failed_assets))
    snapshot_after = _capture_controlled_snapshot(
        config,
        excluded_paths=excluded_outputs,
    )
    if snapshot_after != snapshot_before:
        raise AuditError("受控输入在生成期间发生变化；未发布不一致快照")
    inventory_text = render_inventory_report(inventory)
    schema_text = json.dumps(
        schema_document(generation_id), ensure_ascii=False, sort_keys=True, indent=2
    ) + "\n"
    gap_text = render_gap_report(
        gap_rows,
        config,
        generation_id,
        _snapshot_metrics_receipt(inventory),
    )

    def validate_precommit_state() -> None:
        _validate_artifact_paths(config, paths)
        current_snapshot = _capture_controlled_snapshot(
            config,
            excluded_paths=excluded_outputs,
        )
        if current_snapshot != snapshot_before:
            raise AuditError("受控输入在发布前发生变化；未发布不一致快照")

    _transactional_write(
        [
            (paths.inventory, inventory_text),
            (paths.schema, schema_text),
            (paths.gap, gap_text),
        ],
        journal_path=journal_path,
        generation_id=generation_id,
        expected_target_states=target_states,
        precommit_validator=validate_precommit_state,
    )
    if blocked_error is not None:
        detail = _safe_audit_error_detail(blocked_error)
        raise AuditError(f"审计输入失败，已生成 unknown-blocked 交接证据：{detail}")


def _load_schema(path: Path) -> dict[str, Any]:
    value = _load_json(path, path.parent)
    generation_id = value.get("x-generationId")
    if not isinstance(generation_id, str) or re.fullmatch(
        GENERATION_ID_PATTERN, generation_id
    ) is None:
        raise AuditError(f"Schema 缺少有效 generation ID：{path.name}")
    static_value = deepcopy(value)
    static_value.pop("x-generationId", None)
    if static_value != schema_document():
        raise AuditError(f"Schema 与审计工具版本不一致：{path.name}")
    return value


def validate_artifacts(
    workspace_root: Path,
    paths: ArtifactPaths,
    config: AuditConfig | None = None,
) -> None:
    _validate_artifact_paths(
        config
        if config is not None
        else AuditConfig(
            workspace_root=workspace_root,
            prototype_root=workspace_root / "docs/input/原型/frontend",
            architecture_source=workspace_root / "_bmad-output/planning-artifacts/unused-architecture",
            pab_source=workspace_root / "_bmad-output/planning-artifacts/unused-pab",
            generated_at="1970-01-01T00:00:00+00:00",
        ),
        paths,
    )
    with _artifact_guard(paths, workspace_root, write=False):
        _validate_artifact_paths(
            config
            if config is not None
            else AuditConfig(
                workspace_root=workspace_root,
                prototype_root=workspace_root / "docs/input/原型/frontend",
                architecture_source=workspace_root / "_bmad-output/planning-artifacts/unused-architecture",
                pab_source=workspace_root / "_bmad-output/planning-artifacts/unused-pab",
                generated_at="1970-01-01T00:00:00+00:00",
            ),
            paths,
        )
        _validate_artifacts_locked(workspace_root, paths, config)


def _validate_artifacts_locked(
    workspace_root: Path,
    paths: ArtifactPaths,
    config: AuditConfig | None,
) -> None:
    published_schema = _load_schema(paths.schema)
    inventory = parse_inventory_report(paths.inventory)
    inventory_generation = inventory.get("generationId")
    schema_generation = published_schema.get("x-generationId")
    errors = validate_inventory(
        inventory,
        workspace_root,
        published_schema,
        enforce_coverage=True,
    )
    gap_generation, metrics_receipt, gap_rows = _parse_gap_report_with_generation(paths.gap)
    if not isinstance(inventory_generation, str) or len(
        {inventory_generation, schema_generation, gap_generation}
    ) != 1:
        errors.append("三份产物的 generation ID 不一致，检测到混合代次")
    if metrics_receipt != _snapshot_metrics_receipt(inventory):
        errors.append("snapshotOnly 指标与跨产物回执不一致")
    if config is not None:
        excluded_outputs = (paths.inventory, paths.schema, paths.gap)
        snapshot_before = _capture_controlled_snapshot(
            config,
            excluded_paths=excluded_outputs,
        )
        gap_failed = False
        pab_oracle_failed = False
        try:
            expected_gap = build_dependency_gap(config, snapshot_before)
        except AuditError as exc:
            expected_gap = _blocked_gap(exc)
            gap_failed = True
            pab_oracle_failed = _has_pab_oracle_failure(exc)
        actual_dependencies = {row["dependency"] for row in gap_rows}
        expected_dependencies = {row["dependency"] for row in expected_gap}
        if actual_dependencies != expected_dependencies:
            missing = sorted(expected_dependencies - actual_dependencies, key=_stable_text_key)
            extra = sorted(actual_dependencies - expected_dependencies, key=_stable_text_key)
            details = []
            if missing:
                details.append("缺失 " + ", ".join(missing))
            if extra:
                details.append("多余 " + ", ".join(extra))
            errors.append("依赖覆盖不完整或包含未授权行：" + "；".join(details))
        elif gap_rows != expected_gap:
            errors.append("依赖差异内容与当前受控输入不一致")
        blocked_asset_ids = (
            {"planning.architecture-spine", "planning.pab-1.0.0"}
            if pab_oracle_failed
            else set()
        )
        fresh_inventory = build_inventory(
            config,
            execution_evidence=_execution_evidence(config, failed=gap_failed),
            blocked_asset_ids=blocked_asset_ids,
            controlled_snapshot=snapshot_before,
            generation_id=inventory_generation if isinstance(inventory_generation, str) else None,
        )
        expected_result = (
            "failed"
            if gap_failed or fresh_inventory["executionEvidence"]["result"] == "failed"
            else "passed"
        )
        if inventory.get("executionEvidence", {}).get("result") != expected_result:
            errors.append("执行证据结果与当前必需输入状态不一致")
        if canonical_inventory(inventory) != canonical_inventory(fresh_inventory):
            errors.append("检测到受控输入漂移：机器清单资产或摘要与当前快照不一致")
        if pab_oracle_failed:
            inventory_assets = {
                item.get("assetId"): item
                for item in inventory.get("assets", [])
                if isinstance(item, dict)
            }
            for asset_id in (
                "planning.architecture-spine",
                "planning.pab-1.0.0",
            ):
                asset = inventory_assets.get(asset_id)
                if not isinstance(asset, dict) or not (
                    asset.get("owner") == "UNKNOWN"
                    and asset.get("versionOrDigest") == "UNAVAILABLE"
                    and asset.get("classification") == "unknown-blocked"
                    and asset.get("readStatus") in {"missing", "unreadable"}
                ):
                    errors.append(
                        f"PAB oracle 失败时规划资产 {asset_id} 必须降级为 unknown-blocked"
                    )
        snapshot_after = _capture_controlled_snapshot(
            config,
            excluded_paths=excluded_outputs,
        )
        if snapshot_after != snapshot_before:
            errors.append("受控输入在校验期间发生变化，无法形成单一快照")
    if errors:
        raise AuditError("资产清单校验失败：\n- " + "\n- ".join(errors))


def check_artifacts(config: AuditConfig, paths: ArtifactPaths) -> None:
    _validate_artifact_paths(config, paths)
    with _artifact_guard(paths, config.workspace_root, write=False):
        _validate_artifact_paths(config, paths)
        _check_artifacts_locked(config, paths)


def _check_artifacts_locked(config: AuditConfig, paths: ArtifactPaths) -> None:
    _validate_artifacts_locked(config.workspace_root, paths, config)
    stored_inventory = parse_inventory_report(paths.inventory)
    replay_config = AuditConfig(
        workspace_root=config.workspace_root,
        prototype_root=config.prototype_root,
        architecture_source=config.architecture_source,
        pab_source=config.pab_source,
        generated_at=str(stored_inventory["generatedAt"]),
    )
    stored_evidence = stored_inventory["executionEvidence"]
    generate_command = stored_evidence.get("generateCommand")
    if generate_command == _command_for(replay_config, paths, "generate", force=False):
        generated_with_force = False
    elif generate_command == _command_for(replay_config, paths, "generate", force=True):
        generated_with_force = True
    else:
        raise AuditError("执行证据 generateCommand 与当前审计参数不一致")
    if stored_evidence.get("replayCommand") != _command_for(replay_config, paths, "check"):
        raise AuditError("执行证据 replayCommand 与当前审计参数不一致")
    excluded_outputs = (paths.inventory, paths.schema, paths.gap)
    snapshot_before = _capture_controlled_snapshot(
        replay_config,
        excluded_paths=excluded_outputs,
    )
    blocked_error: AuditError | None = None
    try:
        current_gap = build_dependency_gap(replay_config, snapshot_before)
    except AuditError as exc:
        blocked_error = exc
        current_gap = _blocked_gap(exc)
    current_evidence = _execution_evidence(
        replay_config,
        paths,
        force=generated_with_force,
        failed=blocked_error is not None,
    )
    blocked_assets = (
        {"planning.architecture-spine", "planning.pab-1.0.0"}
        if _has_pab_oracle_failure(blocked_error)
        else set()
    )
    current_inventory = build_inventory(
        replay_config,
        current_evidence,
        blocked_assets,
        controlled_snapshot=snapshot_before,
        generation_id=str(stored_inventory["generationId"]),
    )
    drift: list[str] = []
    if canonical_inventory(stored_inventory) != canonical_inventory(current_inventory):
        stored_assets = {item["assetId"]: item for item in stored_inventory.get("assets", [])}
        current_assets = {item["assetId"]: item for item in current_inventory.get("assets", [])}
        changed = sorted(
            asset_id
            for asset_id in set(stored_assets) | set(current_assets)
            if stored_assets.get(asset_id) != current_assets.get(asset_id)
        )
        drift.append("资产变化：" + (", ".join(changed) if changed else "清单元数据变化"))
    _, _, stored_gap = _parse_gap_report_with_generation(paths.gap)
    if stored_gap != current_gap:
        drift.append("PAB 依赖差异变化")
    stored_inventory_text = _read_utf8(paths.inventory)
    if stored_inventory_text != render_inventory_report(current_inventory):
        drift.append("Markdown 正文漂移：资产清单不可由机器数据重现")
    stored_gap_text = _read_utf8(paths.gap)
    if stored_gap_text != render_gap_report(
        current_gap,
        replay_config,
        str(stored_inventory["generationId"]),
        _snapshot_metrics_receipt(current_inventory),
    ):
        drift.append("Markdown 正文漂移：PAB 差异报告不可由机器数据重现")
    snapshot_after = _capture_controlled_snapshot(
        replay_config,
        excluded_paths=excluded_outputs,
    )
    if snapshot_after != snapshot_before:
        drift.append("受控输入在检查期间发生变化，无法形成单一快照")
    if drift:
        raise AuditError("检测到受控输入漂移；未覆盖已有证据。\n- " + "\n- ".join(drift))


def _within_root(value: str, root: Path) -> Path:
    path = Path(value)
    candidate = path if path.is_absolute() else root / path
    resolved = candidate.resolve(strict=False)
    if not _is_relative_to(resolved, root.resolve()):
        raise AuditError(f"参数路径逃逸工作区：{value}")
    return resolved


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("generate", "check", "validate"))
    parser.add_argument("--workspace-root", default=".")
    parser.add_argument("--prototype-root", default="docs/input/原型/frontend")
    parser.add_argument(
        "--architecture-source",
        default="_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md",
    )
    parser.add_argument(
        "--pab-source",
        default="_bmad-output/planning-artifacts/delegated-decision-baseline-2026-07-17.md",
    )
    parser.add_argument(
        "--inventory-output",
        default="_bmad-output/implementation-artifacts/1-1a-production-asset-inventory.md",
    )
    parser.add_argument(
        "--schema-output",
        default="_bmad-output/implementation-artifacts/1-1a-production-asset-inventory.schema.json",
    )
    parser.add_argument(
        "--gap-output",
        default="_bmad-output/implementation-artifacts/1-1a-prototype-pab-gap.md",
    )
    parser.add_argument("--generated-at")
    parser.add_argument("--force", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        root = Path(args.workspace_root).resolve()
        generated_at = args.generated_at or datetime.now().astimezone().isoformat(timespec="seconds")
        config = AuditConfig(
            workspace_root=root,
            prototype_root=_within_root(args.prototype_root, root),
            architecture_source=_within_root(args.architecture_source, root),
            pab_source=_within_root(args.pab_source, root),
            generated_at=generated_at,
        )
        paths = ArtifactPaths(
            inventory=_within_root(args.inventory_output, root),
            schema=_within_root(args.schema_output, root),
            gap=_within_root(args.gap_output, root),
        )
        if args.mode == "generate":
            generate_artifacts(config, paths, force=args.force)
            print(f"审计证据生成成功：{paths.inventory.name}、{paths.schema.name}、{paths.gap.name}")
        elif args.mode == "check":
            check_artifacts(config, paths)
            print("漂移检查通过：受控输入与已保存证据一致")
        else:
            validate_artifacts(root, paths, config)
            print("Schema、清单与证据链接校验通过")
        return 0
    except (AuditError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 2


def _schema_type_matches(value: Any, expected: str) -> bool:
    return {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(expected, False)


def _is_valid_date(value: str) -> bool:
    if re.fullmatch(DATE_PATTERN, value) is None:
        return False
    try:
        date.fromisoformat(value)
    except ValueError:
        return False
    return True


def _is_valid_datetime(value: str) -> bool:
    match = re.fullmatch(DATETIME_PATTERN, value)
    if match is None:
        return False
    if value.endswith("Z"):
        normalized = value[:-1] + "+00:00"
    else:
        offset_match = re.search(r"([+-])(\d{2}):(\d{2})$", value)
        if offset_match is None:
            return False
        if int(offset_match.group(2)) > 23 or int(offset_match.group(3)) > 59:
            return False
        normalized = value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return False
    return parsed.tzinfo is not None


def _resolve_schema_ref(root_schema: dict[str, Any], reference: str) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise AuditError(f"不支持的 JSON Schema 引用：{reference}")
    node: Any = root_schema
    for part in reference[2:].split("/"):
        if not isinstance(node, dict) or part not in node:
            raise AuditError(f"JSON Schema 引用不可解析：{reference}")
        node = node[part]
    if not isinstance(node, dict):
        raise AuditError(f"JSON Schema 引用目标不是对象：{reference}")
    return node


def _validate_json_schema(
    value: Any,
    schema: dict[str, Any],
    root_schema: dict[str, Any],
    path: str = "$",
) -> list[str]:
    if "$ref" in schema:
        return _validate_json_schema(value, _resolve_schema_ref(root_schema, schema["$ref"]), root_schema, path)
    one_of = schema.get("oneOf")
    if isinstance(one_of, list):
        branch_errors = [
            _validate_json_schema(value, branch, root_schema, path)
            for branch in one_of
            if isinstance(branch, dict)
        ]
        matching_branches = sum(not errors for errors in branch_errors)
        if matching_branches != 1:
            return [f"{path} 必须且只能匹配一个 oneOf 分支"]
        return []
    errors: list[str] = []
    expected = schema.get("type")
    if expected is not None:
        expected_types = expected if isinstance(expected, list) else [expected]
        if not any(_schema_type_matches(value, item) for item in expected_types):
            return [f"{path} 类型应为 {' 或 '.join(expected_types)}"]
    if "const" in schema and value != schema["const"]:
        errors.append(f"{path} 必须等于 {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path} 不在允许枚举中")
    if isinstance(value, str):
        if len(value) < int(schema.get("minLength", 0)):
            errors.append(f"{path} 长度小于 {schema['minLength']}")
        pattern = schema.get("pattern")
        if pattern is not None and re.fullmatch(pattern, value) is None:
            errors.append(f"{path} 不匹配格式 {pattern}")
        if schema.get("format") == "date" and not _is_valid_date(value):
            errors.append(f"{path} 必须为真实日期")
        if schema.get("format") == "date-time" and not _is_valid_datetime(value):
            errors.append(f"{path} 必须为带时区的真实日期时间")
    if isinstance(value, list):
        if len(value) < int(schema.get("minItems", 0)):
            errors.append(f"{path} 项数小于 {schema['minItems']}")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                errors.extend(_validate_json_schema(item, item_schema, root_schema, f"{path}[{index}]"))
    if isinstance(value, dict):
        required = schema.get("required", [])
        for field in required:
            if field not in value:
                errors.append(f"{path}.{field} 是必填字段")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            for field in sorted(set(value) - set(properties)):
                errors.append(f"{path}.{field} 是未知字段")
        for field, field_schema in properties.items():
            if field in value:
                errors.extend(_validate_json_schema(value[field], field_schema, root_schema, f"{path}.{field}"))
    if isinstance(value, (int, float)) and not isinstance(value, bool) and "minimum" in schema:
        if value < schema["minimum"]:
            errors.append(f"{path} 小于最小值 {schema['minimum']}")
    return errors


def validate_inventory(
    inventory: dict[str, Any],
    workspace_root: Path,
    published_schema: dict[str, Any] | None = None,
    *,
    enforce_coverage: bool = False,
) -> list[str]:
    """执行发布的 JSON Schema，再执行跨字段和证据语义校验。"""

    schema = published_schema or schema_document()
    errors: list[str] = _validate_json_schema(inventory, schema, schema)
    allowed_top = set(schema["properties"])
    unknown_top = sorted(set(inventory) - allowed_top)
    if unknown_top:
        errors.append(f"清单含未知字段：{', '.join(unknown_top)}")
    for field in schema["required"]:
        if field not in inventory:
            errors.append(f"清单缺少必填字段：{field}")

    if inventory.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"schemaVersion 必须为 {SCHEMA_VERSION}")
    if not isinstance(inventory.get("auditVersion"), str) or not inventory.get("auditVersion"):
        errors.append("auditVersion 必须为非空字符串")

    for field, pattern in (
        ("generationId", GENERATION_ID_PATTERN),
        ("generatedAt", DATETIME_PATTERN),
        ("verificationDate", DATE_PATTERN),
        ("controlledInputDigest", SHA256_PATTERN),
    ):
        value = inventory.get(field)
        if not isinstance(value, str) or re.fullmatch(pattern, value) is None:
            errors.append(f"字段格式无效：{field}")
    generated_at_value = inventory.get("generatedAt")
    if isinstance(generated_at_value, str) and not _is_valid_datetime(generated_at_value):
        errors.append("generatedAt 必须为带时区的真实日期时间")
    verification_date_value = inventory.get("verificationDate")
    if isinstance(verification_date_value, str) and not _is_valid_date(verification_date_value):
        errors.append("verificationDate 必须为真实日期")

    assets = inventory.get("assets", [])
    if not isinstance(assets, list):
        return errors + ["assets 必须为数组"]
    allowed_asset = set(schema["$defs"]["asset"]["properties"])
    required_asset = schema["$defs"]["asset"]["required"]
    seen: set[str] = set()
    root = workspace_root.resolve()
    for index, asset in enumerate(assets):
        if not isinstance(asset, dict):
            errors.append(f"assets[{index}] 必须为对象")
            continue
        asset_id = str(asset.get("assetId", f"assets[{index}]"))
        unknown = sorted(set(asset) - allowed_asset)
        if unknown:
            errors.append(f"{asset_id} 含未知字段：{', '.join(unknown)}")
        for field in required_asset:
            if field not in asset or asset[field] in (None, "", []):
                errors.append(f"{asset_id} 缺少必填字段：{field}")
        if asset_id in seen:
            errors.append(f"重复 assetId：{asset_id}")
        seen.add(asset_id)
        if re.fullmatch(r"^[a-z0-9][a-z0-9._-]+$", asset_id) is None:
            errors.append(f"{asset_id} assetId 格式无效")

        for field in ("type", "pathOrSource", "owner", "versionOrDigest", "dispositionReason", "risk"):
            if not isinstance(asset.get(field), str) or not asset.get(field):
                errors.append(f"{asset_id} {field} 必须为非空字符串")

        classification = asset.get("classification")
        if classification not in CLASSIFICATIONS:
            errors.append(f"{asset_id} classification 不在允许枚举中")
        if asset.get("owner") == "UNKNOWN" and classification != "unknown-blocked":
            errors.append(f"{asset_id} owner 为 UNKNOWN 时 classification 必须为 unknown-blocked")
        if asset.get("versionOrDigest") in {"UNKNOWN", "UNAVAILABLE"} and classification != "unknown-blocked":
            errors.append(f"{asset_id} 版本/摘要未知时 classification 必须为 unknown-blocked")
        if classification != "reuse-as-is":
            for field in ("dispositionReason", "risk", "ownerStory"):
                if not asset.get(field):
                    errors.append(f"{asset_id} 非 reuse-as-is 项缺少 {field}")
        if asset.get("ownerStory") not in OWNER_STORIES:
            errors.append(f"{asset_id} ownerStory 必须唯一绑定 1.1b、1.1c 或 1.1d")
        recommended = asset.get("recommendedDisposition")
        if recommended not in CLASSIFICATIONS[:-1]:
            errors.append(f"{asset_id} recommendedDisposition 不在允许枚举中")
        if "present" in asset and not isinstance(asset["present"], bool):
            errors.append(f"{asset_id} present 必须为布尔值")
        if not isinstance(asset.get("snapshotOnly"), bool):
            errors.append(f"{asset_id} snapshotOnly 必须为布尔值")
        if "metrics" in asset:
            metrics = asset["metrics"]
            if not isinstance(metrics, dict):
                errors.append(f"{asset_id} metrics 必须为对象")
            else:
                metric_fields_by_scope = {
                    "recursive-regular-files": {
                        "scope",
                        "regularFileCount",
                        "regularFileByteCount",
                    },
                    "top-level-entries": {
                        "scope",
                        "topLevelEntryCount",
                        "topLevelRegularFileByteCount",
                    },
                }
                allowed_metrics = set().union(*metric_fields_by_scope.values())
                unknown_metrics = sorted(set(metrics) - allowed_metrics)
                if unknown_metrics:
                    errors.append(f"{asset_id} metrics 含未知字段：{', '.join(unknown_metrics)}")
                scope = metrics.get("scope")
                expected_metrics = metric_fields_by_scope.get(scope)
                if expected_metrics is None:
                    errors.append(f"{asset_id} metrics.scope 无效")
                    expected_metrics = {"scope"}
                missing_metrics = sorted(expected_metrics - set(metrics))
                extra_for_scope = sorted(set(metrics) - expected_metrics)
                if missing_metrics:
                    errors.append(f"{asset_id} metrics 缺少字段：{', '.join(missing_metrics)}")
                if extra_for_scope:
                    errors.append(
                        f"{asset_id} metrics 与 scope 不一致：{', '.join(extra_for_scope)}"
                    )
                for field in expected_metrics - {"scope"}:
                    value = metrics.get(field)
                    if field in metrics and (
                        not isinstance(value, int) or isinstance(value, bool) or value < 0
                    ):
                        errors.append(f"{asset_id} metrics.{field} 必须为非负整数")
        if not isinstance(asset.get("verifiedDate"), str) or re.fullmatch(
            DATE_PATTERN, str(asset.get("verifiedDate", ""))
        ) is None:
            errors.append(f"{asset_id} verifiedDate 格式无效")
        elif not _is_valid_date(str(asset["verifiedDate"])):
            errors.append(f"{asset_id} verifiedDate 必须为真实日期")

        links = asset.get("evidenceLinks", [])
        checked_paths = asset.get("checkedPaths", [])
        if not isinstance(checked_paths, list) or not checked_paths:
            errors.append(f"{asset_id} checkedPaths 必须为非空数组")
            checked_paths = []
        elif any(not isinstance(item, str) or not item for item in checked_paths):
            errors.append(f"{asset_id} checkedPaths 必须只含非空字符串")
            checked_paths = []
        else:
            if len(checked_paths) != len(set(checked_paths)) or checked_paths != sorted(checked_paths):
                errors.append(f"{asset_id} checkedPaths 必须唯一且稳定排序")
            for checked_path in checked_paths:
                candidate = Path(checked_path)
                if candidate.is_absolute() or ".." in candidate.parts:
                    errors.append(f"{asset_id} 资产状态语义矛盾：checkedPaths 逃逸工作区")
                    continue
                if not _is_relative_to((root / candidate).resolve(strict=False), root):
                    errors.append(f"{asset_id} 资产状态语义矛盾：checkedPaths 逃逸工作区")

        if isinstance(links, list):
            if len(links) != len(set(str(link) for link in links)) or links != sorted(
                links, key=lambda item: str(item)
            ):
                errors.append(f"{asset_id} evidenceLinks 必须唯一且稳定排序")
            for link in links:
                if not isinstance(link, str):
                    errors.append(f"{asset_id} 证据链接必须为字符串")
                    continue
                if link.startswith("audit://absence/"):
                    expected_link = f"audit://absence/{asset_id}"
                    checked_paths = asset.get("checkedPaths")
                    if link != expected_link or asset.get("present") is not False:
                        errors.append(f"{asset_id} 缺失证据链接不可解析：{link}")
                    if not isinstance(checked_paths, list) or not checked_paths:
                        errors.append(f"{asset_id} 缺失证据没有 checkedPaths")
                    continue
                evidence = Path(link)
                if evidence.is_absolute() or ".." in evidence.parts:
                    errors.append(f"{asset_id} 证据路径逃逸工作区：{link}")
                    continue
                resolved = (root / evidence).resolve()
                if not _is_relative_to(resolved, root) or not resolved.exists():
                    errors.append(f"{asset_id} 证据路径不可解析：{link}")
        else:
            errors.append(f"{asset_id} evidenceLinks 必须为数组")

        semantic_issues: list[str] = []
        read_status = asset.get("readStatus")
        present = asset.get("present")
        version = asset.get("versionOrDigest")
        if read_status == "readable":
            if present is not True:
                semantic_issues.append("readable 必须 present=true")
            if not isinstance(version, str) or re.fullmatch(SHA256_PATTERN, version) is None:
                semantic_issues.append("readable 必须有 SHA-256 摘要")
            if isinstance(links, list) and any(
                isinstance(link, str) and link.startswith("audit://absence/") for link in links
            ):
                semantic_issues.append("readable 不得使用缺失证据")
        elif read_status == "missing":
            if present is not False:
                semantic_issues.append("missing 必须 present=false")
            if version != "UNAVAILABLE":
                semantic_issues.append("missing 必须使用 UNAVAILABLE")
            if classification != "unknown-blocked":
                semantic_issues.append("missing 必须 unknown-blocked")
            if links != [f"audit://absence/{asset_id}"]:
                semantic_issues.append("missing 必须且只能使用对应缺失证据")
            if asset.get("metrics") is not None or asset.get("snapshotOnly") is not False:
                semantic_issues.append("missing 不得包含快照指标")
        elif read_status == "unreadable":
            if present is not True:
                semantic_issues.append("unreadable 必须 present=true")
            if version != "UNAVAILABLE":
                semantic_issues.append("unreadable 必须使用 UNAVAILABLE")
            if classification != "unknown-blocked":
                semantic_issues.append("unreadable 必须 unknown-blocked")
            if isinstance(links, list) and any(
                isinstance(link, str) and link.startswith("audit://absence/") for link in links
            ):
                semantic_issues.append("unreadable 不得冒充缺失证据")
            if asset.get("metrics") is not None or asset.get("snapshotOnly") is not False:
                semantic_issues.append("unreadable 不得包含快照指标")
        else:
            semantic_issues.append("readStatus 必须为 readable、missing 或 unreadable")

        if present is True and isinstance(links, list) and all(
            isinstance(link, str) and not link.startswith("audit://") for link in links
        ):
            if asset.get("pathOrSource") != "; ".join(links):
                semantic_issues.append("present 的 pathOrSource 必须与 evidenceLinks 一致")
            if checked_paths and not set(links).issubset(set(checked_paths)):
                semantic_issues.append("evidenceLinks 必须属于 checkedPaths")
        if asset.get("verifiedDate") != inventory.get("verificationDate"):
            semantic_issues.append("verifiedDate 必须等于清单 verificationDate")
        if asset.get("snapshotOnly") is True and (
            present is not True or read_status != "readable" or not isinstance(asset.get("metrics"), dict)
        ):
            semantic_issues.append("snapshotOnly 必须是带指标的可读现存资产")
        metrics = asset.get("metrics")
        if isinstance(metrics, dict):
            expected_scope = (
                "top-level-entries"
                if asset.get("snapshotOnly") is True
                else "recursive-regular-files"
            )
            if metrics.get("scope") != expected_scope:
                semantic_issues.append(
                    f"snapshotOnly 与 metrics.scope 必须匹配 {expected_scope}"
                )
        if semantic_issues:
            errors.append(f"{asset_id} 资产状态语义矛盾：" + "；".join(semantic_issues))

    ordered_ids = [str(asset.get("assetId", "")) for asset in assets if isinstance(asset, dict)]
    if ordered_ids != sorted(ordered_ids):
        errors.append("assets 必须按 assetId 稳定排序")
    if enforce_coverage:
        missing_asset_ids = sorted(REQUIRED_ASSET_IDS - set(ordered_ids))
        if missing_asset_ids:
            errors.append("assets 缺少 Story 必需资产：" + ", ".join(missing_asset_ids))

    unresolved = inventory.get("unresolvedItems", [])
    unresolved_schema = schema["$defs"]["unresolvedItem"]
    if not isinstance(unresolved, list):
        errors.append("unresolvedItems 必须为数组")
        unresolved = []
    allowed_unresolved = set(unresolved_schema["properties"])
    for index, item in enumerate(unresolved):
        if not isinstance(item, dict):
            errors.append(f"unresolvedItems[{index}] 必须为对象")
            continue
        unknown = sorted(set(item) - allowed_unresolved)
        if unknown:
            errors.append(f"unresolvedItems[{index}] 含未知字段：{', '.join(unknown)}")
        for field in unresolved_schema["required"]:
            if not isinstance(item.get(field), str) or not item.get(field):
                errors.append(f"unresolvedItems[{index}] 缺少非空字段：{field}")
        if item.get("ownerStory") not in OWNER_STORIES:
            errors.append(f"unresolvedItems[{index}] ownerStory 无效")

    blocked_assets = {
        str(asset.get("assetId")): asset
        for asset in assets
        if isinstance(asset, dict) and asset.get("classification") == "unknown-blocked"
    }
    unresolved_ids = [
        str(item.get("assetId")) for item in unresolved if isinstance(item, dict)
    ]
    if len(unresolved_ids) != len(set(unresolved_ids)) or set(unresolved_ids) != set(blocked_assets):
        errors.append("unknown-blocked 资产与 unresolvedItems 必须双向一致")
    for item in unresolved:
        if not isinstance(item, dict):
            continue
        asset = blocked_assets.get(str(item.get("assetId")))
        if asset is not None and item.get("ownerStory") != asset.get("ownerStory"):
            errors.append(f"{item.get('assetId')} unresolved ownerStory 与资产不一致")

    execution = inventory.get("executionEvidence")
    execution_schema = schema["$defs"]["executionEvidence"]
    if not isinstance(execution, dict):
        errors.append("executionEvidence 必须为对象")
    else:
        unknown = sorted(set(execution) - set(execution_schema["properties"]))
        if unknown:
            errors.append(f"executionEvidence 含未知字段：{', '.join(unknown)}")
        for field in execution_schema["required"]:
            if field not in execution:
                errors.append(f"executionEvidence 缺少必填字段：{field}")
        for field in ("generateCommand", "replayCommand", "scriptVersion"):
            if not isinstance(execution.get(field), str) or not execution.get(field):
                errors.append(f"executionEvidence.{field} 必须为非空字符串")
        if not isinstance(execution.get("scriptSha256"), str) or re.fullmatch(
            SHA256_PATTERN, str(execution.get("scriptSha256", ""))
        ) is None:
            errors.append("executionEvidence.scriptSha256 格式无效")
        scope = execution.get("inputScope")
        if not isinstance(scope, list) or not scope or any(not isinstance(item, str) or not item for item in scope):
            errors.append("executionEvidence.inputScope 必须为非空字符串数组")
        elif scope != sorted(scope):
            errors.append("executionEvidence.inputScope 必须稳定排序")
        if execution.get("result") not in {"passed", "failed"}:
            errors.append("executionEvidence.result 无效")
        valid_result_scope_pairs = {
            ("passed", "generation-and-in-memory-validation"),
            ("failed", "input-audit-failed"),
        }
        if (execution.get("result"), execution.get("resultScope")) not in valid_result_scope_pairs:
            errors.append("executionEvidence.result 与 resultScope 状态矩阵矛盾")
        count = execution.get("unresolvedCount")
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            errors.append("executionEvidence.unresolvedCount 必须为非负整数")
        elif count != len(unresolved):
            errors.append("executionEvidence.unresolvedCount 与 unresolvedItems 数量不一致")
        steps = execution.get("steps")
        if isinstance(steps, list):
            names = [step.get("name") for step in steps if isinstance(step, dict)]
            expected_names = ["generate", "schema-validation", "gap-validation"]
            if names != expected_names:
                errors.append("executionEvidence.steps 仅允许按顺序记录生成、Schema 与 gap 内存校验")
            if len(names) != len(set(names)):
                errors.append("executionEvidence.steps 名称必须唯一")
            for index, step in enumerate(steps):
                if not isinstance(step, dict):
                    continue
                status = step.get("status")
                exit_code = step.get("exitCode")
                if status == "not-run" and exit_code is not None:
                    errors.append(f"executionEvidence.steps[{index}] 未运行步骤不得记录退出码")
                if status == "passed" and exit_code != 0:
                    errors.append(f"executionEvidence.steps[{index}] passed 必须使用退出码 0")
                if status == "failed" and (
                    not isinstance(exit_code, int) or isinstance(exit_code, bool) or exit_code == 0
                ):
                    errors.append(f"executionEvidence.steps[{index}] failed 必须使用非零退出码")

            if execution.get("result") == "passed":
                if any(
                    not isinstance(step, dict)
                    or step.get("status") != "passed"
                    or step.get("exitCode") != 0
                    for step in steps
                ):
                    errors.append("executionEvidence passed 必须对应三个生成期步骤全部以退出码 0 通过")
            elif execution.get("result") == "failed":
                if not any(
                    isinstance(step, dict) and step.get("status") == "failed"
                    for step in steps
                ):
                    errors.append("executionEvidence failed 必须至少包含一个失败的生成期步骤")

    return errors


if __name__ == "__main__":
    raise SystemExit(main())
