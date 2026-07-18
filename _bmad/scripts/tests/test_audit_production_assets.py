from __future__ import annotations

import hashlib
import json
import io
import os
import shlex
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import audit_production_assets as audit_module  # noqa: E402
from audit_production_assets import (  # noqa: E402
    AuditConfig,
    AuditError,
    ArtifactPaths,
    build_dependency_gap,
    build_inventory,
    canonical_inventory,
    check_artifacts,
    generate_artifacts,
    main,
    parse_inventory_report,
    parse_gap_report,
    schema_document,
    render_gap_report,
    render_inventory_report,
    summarize_path,
    validate_artifacts,
    validate_gap_rows,
    validate_inventory,
)


class ContractAndDigestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def _valid_asset(self, **overrides: object) -> dict[str, object]:
        evidence = self.root / "evidence.txt"
        evidence.write_text("evidence", encoding="utf-8")
        asset: dict[str, object] = {
            "assetId": "asset.example",
            "type": "source-code",
            "pathOrSource": "evidence.txt",
            "owner": "实施团队",
            "versionOrDigest": "sha256:" + "a" * 64,
            "verifiedDate": "2026-07-17",
            "evidenceLinks": ["evidence.txt"],
            "classification": "migrate",
            "recommendedDisposition": "migrate",
            "dispositionReason": "仅迁移受控意图。",
            "risk": "原型行为未经生产验证。",
            "ownerStory": "1.1b",
            "present": True,
            "readStatus": "readable",
            "checkedPaths": ["evidence.txt"],
            "snapshotOnly": False,
        }
        asset.update(overrides)
        return asset

    def _valid_inventory(self, assets: list[dict[str, object]]) -> dict[str, object]:
        return {
            "schemaVersion": audit_module.SCHEMA_VERSION,
            "auditVersion": "1.0.0",
            "generationId": "sha256:" + "d" * 64,
            "generatedAt": "2026-07-17T21:00:00+08:00",
            "verificationDate": "2026-07-17",
            "controlledInputDigest": "sha256:" + "b" * 64,
            "assets": assets,
            "unresolvedItems": [],
            "executionEvidence": {
                "generateCommand": "python3 audit_production_assets.py generate ...",
                "replayCommand": "python3 audit_production_assets.py check ...",
                "scriptVersion": "1.0.0",
                "scriptSha256": "sha256:" + "c" * 64,
                "inputScope": ["evidence.txt"],
                "result": "passed",
                "resultScope": "generation-and-in-memory-validation",
                "acceptanceEvidenceLocation": "Story Dev Agent Record",
                "steps": [
                    {
                        "name": "generate",
                        "command": "Python API test",
                        "status": "passed",
                        "exitCode": 0,
                    },
                    {
                        "name": "schema-validation",
                        "command": "Python API test",
                        "status": "passed",
                        "exitCode": 0,
                    },
                    {
                        "name": "gap-validation",
                        "command": "Python API test",
                        "status": "passed",
                        "exitCode": 0,
                    },
                ],
                "unresolvedCount": 0,
            },
        }

    def test_schema_is_closed_and_uses_required_classification_enum(self) -> None:
        schema = schema_document()
        self.assertFalse(schema["additionalProperties"])
        asset_schema = schema["$defs"]["asset"]
        self.assertFalse(asset_schema["additionalProperties"])
        self.assertEqual(
            asset_schema["properties"]["classification"]["enum"],
            ["reuse-as-is", "migrate", "reference-only", "replace", "unknown-blocked"],
        )
        self.assertIn("recommendedDisposition", asset_schema["required"])

    def test_validation_rejects_unknown_fields_duplicate_ids_and_broken_evidence(self) -> None:
        first = self._valid_asset(extra="must fail")
        second = self._valid_asset(assetId="asset.example", evidenceLinks=["missing.txt"])
        errors = validate_inventory(self._valid_inventory([first, second]), self.root)
        combined = "\n".join(errors)
        self.assertIn("未知字段", combined)
        self.assertIn("重复 assetId", combined)
        self.assertIn("证据路径不可解析", combined)

    def test_validation_reports_non_string_checked_paths_without_type_error(self) -> None:
        asset = self._valid_asset(checkedPaths=[{}])
        errors = "\n".join(
            validate_inventory(self._valid_inventory([asset]), self.root)
        )
        self.assertIn("checkedPaths 必须只含非空字符串", errors)

    def test_unknown_owner_or_version_requires_unknown_blocked(self) -> None:
        assets = [
            self._valid_asset(owner="UNKNOWN"),
            self._valid_asset(assetId="asset.version", versionOrDigest="UNKNOWN"),
        ]
        errors = "\n".join(validate_inventory(self._valid_inventory(assets), self.root))
        self.assertIn("owner 为 UNKNOWN 时 classification 必须为 unknown-blocked", errors)
        self.assertIn("版本/摘要未知时 classification 必须为 unknown-blocked", errors)

    def test_validation_rejects_nested_unknown_fields_and_inconsistent_counts(self) -> None:
        asset = self._valid_asset(
            metrics={
                "scope": "recursive-regular-files",
                "regularFileCount": 1,
                "regularFileByteCount": 8,
                "mtime": 123,
            }
        )
        inventory = self._valid_inventory([asset])
        inventory["executionEvidence"]["extra"] = True
        inventory["executionEvidence"]["unresolvedCount"] = 2
        inventory["unresolvedItems"] = [
            {
                "assetId": "asset.example",
                "issue": "test",
                "impact": "test",
                "ownerStory": "1.1b",
                "extra": "must fail",
            }
        ]
        errors = "\n".join(validate_inventory(inventory, self.root))
        self.assertIn("metrics 含未知字段", errors)
        self.assertIn("executionEvidence 含未知字段", errors)
        self.assertIn("unresolvedItems[0] 含未知字段", errors)
        self.assertIn("unresolvedCount 与 unresolvedItems 数量不一致", errors)

    def test_execution_evidence_rejects_cross_field_state_matrix(self) -> None:
        valid = self._valid_inventory([self._valid_asset()])

        passed_with_failure_scope = json.loads(json.dumps(valid))
        passed_with_failure_scope["executionEvidence"]["resultScope"] = "input-audit-failed"
        errors = "\n".join(validate_inventory(passed_with_failure_scope, self.root))
        self.assertIn("resultScope", errors)

        failed_with_zero_exit = json.loads(json.dumps(valid))
        evidence = failed_with_zero_exit["executionEvidence"]
        evidence["result"] = "failed"
        evidence["resultScope"] = "input-audit-failed"
        evidence["steps"][0]["status"] = "failed"
        evidence["steps"][0]["exitCode"] = 0
        errors = "\n".join(validate_inventory(failed_with_zero_exit, self.root))
        self.assertIn("非零", errors)

        failed_with_nonzero_passed_step = json.loads(json.dumps(valid))
        evidence = failed_with_nonzero_passed_step["executionEvidence"]
        evidence["result"] = "failed"
        evidence["resultScope"] = "input-audit-failed"
        evidence["steps"][0]["status"] = "failed"
        evidence["steps"][0]["exitCode"] = 2
        evidence["steps"][1]["status"] = "passed"
        evidence["steps"][1]["exitCode"] = 1
        errors = "\n".join(
            validate_inventory(failed_with_nonzero_passed_step, self.root)
        )
        self.assertIn("退出码 0", errors)

    def test_inventory_dates_require_real_calendar_and_timezone_values(self) -> None:
        invalid_cases = (
            (
                "generatedAt",
                lambda inventory: inventory.__setitem__(
                    "generatedAt", "2026-99-99T29:77:88+88:99"
                ),
                "generatedAt 必须为带时区的真实日期时间",
            ),
            (
                "verificationDate",
                lambda inventory: inventory.__setitem__(
                    "verificationDate", "2026-99-99"
                ),
                "verificationDate 必须为真实日期",
            ),
            (
                "verifiedDate",
                lambda inventory: inventory["assets"][0].__setitem__(
                    "verifiedDate", "2026-99-99"
                ),
                "asset.example verifiedDate 必须为真实日期",
            ),
        )
        for field, mutate, expected_error in invalid_cases:
            with self.subTest(field=field):
                invalid = self._valid_inventory([self._valid_asset()])
                mutate(invalid)
                errors = "\n".join(validate_inventory(invalid, self.root))
                self.assertIn(expected_error, errors)

        valid = self._valid_inventory(
            [self._valid_asset(verifiedDate="2024-02-29")]
        )
        valid["generatedAt"] = "2024-02-29T23:59:59Z"
        valid["verificationDate"] = "2024-02-29"
        errors = "\n".join(validate_inventory(valid, self.root))
        self.assertNotIn("日期", errors)
        self.assertNotIn("generatedAt", errors)

    def test_snapshot_metrics_require_scope_specific_counts(self) -> None:
        invalid_metrics = (
            {},
            {"scope": "top-level-entries"},
            {"scope": "top-level-entries", "topLevelEntryCount": 1},
            {
                "scope": "top-level-entries",
                "topLevelEntryCount": True,
                "topLevelRegularFileByteCount": 8,
            },
            {
                "scope": "top-level-entries",
                "topLevelEntryCount": 1,
                "topLevelRegularFileByteCount": -1,
            },
            {
                "scope": "top-level-entries",
                "regularFileCount": 1,
                "regularFileByteCount": 8,
            },
        )
        for metrics in invalid_metrics:
            with self.subTest(metrics=metrics):
                asset = self._valid_asset(snapshotOnly=True, metrics=metrics)
                errors = "\n".join(
                    validate_inventory(self._valid_inventory([asset]), self.root)
                )
                self.assertIn("metrics", errors)
        valid_asset = self._valid_asset(
            snapshotOnly=True,
            metrics={
                "scope": "top-level-entries",
                "topLevelEntryCount": 1,
                "topLevelRegularFileByteCount": 8,
            },
        )
        errors = "\n".join(
            validate_inventory(self._valid_inventory([valid_asset]), self.root)
        )
        self.assertNotIn("metrics", errors)

    def test_published_schema_alone_requires_scope_specific_metrics(self) -> None:
        schema = schema_document()
        metrics_schema = schema["$defs"]["asset"]["properties"]["metrics"]
        self.assertIn("oneOf", metrics_schema)
        invalid_metrics = (
            {"scope": "recursive-regular-files"},
            {
                "scope": "top-level-entries",
                "topLevelEntryCount": 1,
            },
            {
                "scope": "recursive-regular-files",
                "topLevelEntryCount": 1,
                "topLevelRegularFileByteCount": 8,
            },
        )
        for metrics in invalid_metrics:
            with self.subTest(metrics=metrics):
                errors = audit_module._validate_json_schema(
                    metrics,
                    metrics_schema,
                    schema,
                    "$.metrics",
                )
                self.assertTrue(errors)

        valid_metrics = (
            {
                "scope": "recursive-regular-files",
                "regularFileCount": 1,
                "regularFileByteCount": 8,
            },
            {
                "scope": "top-level-entries",
                "topLevelEntryCount": 1,
                "topLevelRegularFileByteCount": 8,
            },
        )
        for metrics in valid_metrics:
            with self.subTest(metrics=metrics):
                self.assertEqual(
                    audit_module._validate_json_schema(
                        metrics,
                        metrics_schema,
                        schema,
                        "$.metrics",
                    ),
                    [],
                )

    def test_snapshot_metrics_have_explicit_recursive_or_top_level_scope(self) -> None:
        recursive = self._valid_asset(
            metrics={
                "scope": "recursive-regular-files",
                "regularFileCount": 1,
                "regularFileByteCount": 8,
            }
        )
        shallow = self._valid_asset(
            snapshotOnly=True,
            metrics={
                "scope": "top-level-entries",
                "topLevelEntryCount": 1,
                "topLevelRegularFileByteCount": 0,
            },
        )
        for asset in (recursive, shallow):
            with self.subTest(scope=asset["metrics"]["scope"]):
                errors = "\n".join(
                    validate_inventory(self._valid_inventory([asset]), self.root)
                )
                self.assertNotIn("metrics", errors)

    def test_summary_is_stable_ignores_ds_store_and_never_uses_mtime(self) -> None:
        source = self.root / "src"
        source.mkdir()
        tracked = source / "main.ts"
        tracked.write_text("export const value = 1;\n", encoding="utf-8")
        (source / ".DS_Store").write_bytes(b"unstable")

        first = summarize_path(source, self.root)
        os.utime(tracked, (1_700_000_000, 1_700_000_000))
        (source / ".DS_Store").write_bytes(b"changed cache")
        second = summarize_path(source, self.root)
        self.assertEqual(first, second)

    def test_controlled_source_does_not_ignore_business_directories_named_like_caches(self) -> None:
        source = self.root / "src"
        coverage = source / "coverage/rule.ts"
        cache = source / ".cache/domain.ts"
        coverage.parent.mkdir(parents=True)
        cache.parent.mkdir(parents=True)
        coverage.write_text("export const coverageRule = 1;\n", encoding="utf-8")
        cache.write_text("export const cachedDomain = 1;\n", encoding="utf-8")
        first = summarize_path(source, self.root)
        coverage.write_text("export const coverageRule = 2;\n", encoding="utf-8")
        second = summarize_path(source, self.root)
        self.assertNotEqual(first["digest"], second["digest"])
        cache.write_text("export const cachedDomain = 2;\n", encoding="utf-8")
        third = summarize_path(source, self.root)
        self.assertNotEqual(second["digest"], third["digest"])

    def test_summary_rejects_symlink_without_disclosing_target(self) -> None:
        outside = self.root.parent / "secret-outside.txt"
        outside.write_text("do-not-read", encoding="utf-8")
        link = self.root / "escape"
        link.symlink_to(outside)
        with self.assertRaisesRegex(AuditError, "符号链接") as caught:
            summarize_path(link, self.root)
        self.assertNotIn(str(outside), str(caught.exception))

    def test_uncontrolled_tree_ignores_symlink_without_following_it(self) -> None:
        tree = self.root / "node_modules"
        tree.mkdir()
        outside = self.root.parent / "outside-token.txt"
        outside.write_text("outside-secret", encoding="utf-8")
        (tree / "link").symlink_to(outside)
        summary = summarize_path(tree, self.root, reject_symlinks=False)
        self.assertEqual(summary["fileCount"], 1)
        self.assertEqual(summary["byteCount"], 0)
        self.assertNotEqual(summary["digest"], "sha256:" + hashlib.sha256(b"outside-secret").hexdigest())

    def test_summary_rejects_fifo_without_blocking(self) -> None:
        tree = self.root / "fifo-tree"
        tree.mkdir()
        fifo = tree / "audit.fifo"
        os.mkfifo(fifo)

        def write_fifo() -> None:
            try:
                with fifo.open("wb") as handle:
                    handle.write(b"must-not-be-hashed")
            except OSError:
                pass

        writer = threading.Thread(target=write_fifo, daemon=True)
        writer.start()
        try:
            with self.assertRaisesRegex(AuditError, "普通文件"):
                summarize_path(tree, self.root)
        finally:
            if writer.is_alive():
                descriptor = os.open(fifo, os.O_RDONLY | os.O_NONBLOCK)
                os.close(descriptor)
            writer.join(timeout=2)

    def test_summary_rejects_unreadable_nested_directory(self) -> None:
        source = self.root / "src"
        blocked = source / "nested/blocked"
        blocked.mkdir(parents=True)
        (source / "main.ts").write_text("export {};\n", encoding="utf-8")
        (blocked / "secret.ts").write_text(
            "export const secret = true;\n", encoding="utf-8"
        )
        blocked.chmod(0)
        try:
            with self.assertRaisesRegex(AuditError, "目录.*读取.*权限"):
                summarize_path(source, self.root)
        finally:
            blocked.chmod(0o755)

    def test_summary_streams_large_files_without_read_bytes(self) -> None:
        large = self.root / "large.bin"
        content = b"streaming-digest" * 300_000
        large.write_bytes(content)
        with patch(
            "audit_production_assets._read_bytes",
            side_effect=AssertionError("summarize_path must stream"),
        ):
            summary = summarize_path(large, self.root)
        expected_file_digest = hashlib.sha256(content).hexdigest().encode("ascii")
        mode_token = (
            f"mode:{stat.S_IMODE(large.stat().st_mode) & 0o7777:04o}"
        ).encode("ascii")
        expected = hashlib.sha256(
            b"large.bin\0" + expected_file_digest + b"\0" + mode_token + b"\n"
        ).hexdigest()
        self.assertEqual(summary["digest"], "sha256:" + expected)
        self.assertEqual(summary["byteCount"], len(content))

    def test_summary_detects_empty_directory_add_remove_and_rename(self) -> None:
        source = self.root / "src"
        source.mkdir()
        before = summarize_path(source, self.root)
        (source / "empty-a").mkdir()
        added = summarize_path(source, self.root)
        (source / "empty-a").rename(source / "empty-b")
        renamed = summarize_path(source, self.root)
        (source / "empty-b").rmdir()
        removed = summarize_path(source, self.root)
        self.assertNotEqual(before["digest"], added["digest"])
        self.assertNotEqual(added["digest"], renamed["digest"])
        self.assertEqual(before["digest"], removed["digest"])

    def test_canonical_inventory_excludes_all_verification_dates(self) -> None:
        inventory = self._valid_inventory([self._valid_asset()])
        replay = json.loads(json.dumps(inventory))
        replay["generatedAt"] = "2026-07-18T08:00:00+08:00"
        replay["verificationDate"] = "2026-07-18"
        replay["assets"][0]["verifiedDate"] = "2026-07-18"
        self.assertEqual(canonical_inventory(inventory), canonical_inventory(replay))

    def test_canonical_inventory_ignores_commands_but_preserves_step_outcomes(self) -> None:
        inventory = self._valid_inventory([self._valid_asset()])
        commands_changed = json.loads(json.dumps(inventory))
        commands_changed["executionEvidence"]["generateCommand"] = "different generate"
        commands_changed["executionEvidence"]["replayCommand"] = "different replay"
        for step in commands_changed["executionEvidence"]["steps"]:
            step["command"] = "different command"
        self.assertEqual(
            canonical_inventory(inventory), canonical_inventory(commands_changed)
        )

        outcomes_changed = json.loads(json.dumps(inventory))
        outcomes_changed["executionEvidence"]["result"] = "failed"
        outcomes_changed["executionEvidence"]["resultScope"] = "input-audit-failed"
        outcomes_changed["executionEvidence"]["steps"][0]["status"] = "failed"
        outcomes_changed["executionEvidence"]["steps"][0]["exitCode"] = 2
        self.assertNotEqual(
            canonical_inventory(inventory), canonical_inventory(outcomes_changed)
        )

    def test_published_schema_rejects_numeric_asset_id_and_empty_evidence(self) -> None:
        inventory = self._valid_inventory(
            [self._valid_asset(assetId=123, evidenceLinks=[])]
        )
        errors = "\n".join(validate_inventory(inventory, self.root))
        self.assertIn("assetId", errors)
        self.assertIn("evidenceLinks", errors)

    def test_unresolved_items_are_bidirectionally_equal_to_unknown_blocked_assets(self) -> None:
        blocked = self._valid_asset(
            assetId="asset.blocked",
            owner="UNKNOWN",
            versionOrDigest="UNAVAILABLE",
            classification="unknown-blocked",
        )
        inventory = self._valid_inventory([blocked])
        inventory["unresolvedItems"] = [
            {
                "assetId": "asset.phantom",
                "issue": "虚构项",
                "impact": "无",
                "ownerStory": "1.1b",
            }
        ]
        inventory["executionEvidence"]["unresolvedCount"] = 1
        errors = "\n".join(validate_inventory(inventory, self.root))
        self.assertIn("unknown-blocked 资产与 unresolvedItems 必须双向一致", errors)

    def test_asset_semantics_reject_contradictory_state_and_escaping_checked_paths(self) -> None:
        mutations = [
            {"present": False},
            {
                "present": False,
                "readStatus": "missing",
                "classification": "unknown-blocked",
            },
            {
                "versionOrDigest": "UNAVAILABLE",
                "classification": "unknown-blocked",
            },
            {"checkedPaths": ["../../outside"]},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                asset = self._valid_asset(**mutation)
                inventory = self._valid_inventory([asset])
                if asset["classification"] == "unknown-blocked":
                    inventory["unresolvedItems"] = [
                        {
                            "assetId": "asset.example",
                            "issue": "test",
                            "impact": "test",
                            "ownerStory": "1.1b",
                        }
                    ]
                    inventory["executionEvidence"]["unresolvedCount"] = 1
                errors = "\n".join(validate_inventory(inventory, self.root))
                self.assertIn("资产状态语义", errors)

        asset = self._valid_asset()
        asset.pop("readStatus")
        errors = "\n".join(validate_inventory(self._valid_inventory([asset]), self.root))
        self.assertIn("readStatus", errors)

        outside = self.root.parent / "asset-evidence-outside.txt"
        outside.write_text("outside", encoding="utf-8")
        escaping = self.root / "escaping-evidence"
        escaping.symlink_to(outside)
        asset = self._valid_asset(
            pathOrSource="escaping-evidence",
            evidenceLinks=["escaping-evidence"],
            checkedPaths=["escaping-evidence"],
        )
        errors = "\n".join(validate_inventory(self._valid_inventory([asset]), self.root))
        self.assertIn("逃逸工作区", errors)


class AuditFixture:
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name).resolve()
        self.prototype = self.root / "docs/input/原型/frontend"
        self.prototype.mkdir(parents=True)
        files = {
            "src/main.ts": "const token = 'fixture-secret-must-not-leak';\n",
            "src/router/index.ts": "export const routes = [];\n",
            "src/layouts/MainLayout.vue": "<template><main /></template>\n",
            "src/components/Card.vue": "<template><article /></template>\n",
            "src/mock/auth.ts": "export const auth = true;\n",
            "src/stores/user.ts": "export const user = {};\n",
            "src/types/user.ts": "export type User = {};\n",
            "src/styles/main.css": ":root { color: black; }\n",
            "src/assets/logo.svg": "<svg></svg>\n",
            "src/views/Home.vue": "<template><h1>Home</h1></template>\n",
            "vite.config.ts": "// 微前端：网关代理到各微服务\n",
            "dist/index.html": "<html></html>\n",
            "node_modules/vue/package.json": '{"version":"3.4.0"}\n',
        }
        for relative, content in files.items():
            target = self.prototype / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        package = {
            "name": "fixture",
            "dependencies": {
                "@element-plus/icons-vue": "^2.3.1",
                "axios": "^1.6.8",
                "vue": "^3.4.0",
            },
            "devDependencies": {
                "@types/node": "^20.0.0",
                "@vitejs/plugin-vue": "^5.0.0",
                "prettier": "^3.2.5",
                "vite": "^5.0.0",
            },
        }
        lock = {
            "name": "fixture",
            "lockfileVersion": 3,
            "packages": {
                "": package,
                "node_modules/vue": {"version": "3.4.0"},
                "node_modules/vite": {"version": "5.0.1"},
                "node_modules/axios": {"version": "1.6.8"},
                "node_modules/@element-plus/icons-vue": {"version": "2.3.1"},
                "node_modules/@types/node": {"version": "20.0.0"},
                "node_modules/@vitejs/plugin-vue": {"version": "5.0.0"},
                "node_modules/prettier": {"version": "3.2.5"},
            },
        }
        (self.prototype / "package.json").write_text(json.dumps(package), encoding="utf-8")
        (self.prototype / "package-lock.json").write_text(json.dumps(lock), encoding="utf-8")
        self.architecture = self.root / "planning/ARCHITECTURE-SPINE.md"
        self.pab = self.root / "planning/delegated-baseline.md"
        self.architecture.parent.mkdir(parents=True)
        self.architecture.write_text(
            """AD-1 模块化单体；AD-28 Vite 5 仅迁移。

## 生产技术基线 PAB-1.0.0

| 名称 | 精确基线 |
| --- | --- |
| OpenJDK / Spring Boot / PostgreSQL | 25 LTS / 4.1.0 / 18.4 |
| OpenAPI / CloudEvents / JSON Schema | 3.1.2 / 1.0.2 / 2020-12 |
| Node.js / npm | 24.18.0 LTS / 11.16.0 |
| Vite / Vue / TypeScript | 8.1.5 / 3.5.40 / `@typescript/typescript6` 6.0.2 |
| vue-tsc / Vue Router / Pinia | 3.3.7 / 4.6.4 / 3.0.4 |
| Element Plus / TanStack Vue Query | 2.14.3 / 5.101.2 |
| ECharts / vue-echarts | 6.1.0 / 8.0.1 |
| Vitest / Playwright / axe-core | 4.1.10 / 1.61.1 / 4.12.1 |
""",
            encoding="utf-8",
        )
        self.pab.write_text(
            """# 委托决策基线

## 4. 公共集成 PIC-1.0.0

- 同步 API 用 OpenAPI 3.1.2；异步事件采用 CloudEvents 1.0.2 + JSON Schema 2020-12。

## 9. PAB/PP/AP-1.0.0

- PAB-1.0.0 精确版本：JDK 25 LTS、Spring Boot 4.1.0、PostgreSQL 18.4、Node 24.18.0 LTS、npm 11.16.0、Vite 8.1.5、Vue 3.5.40、`@typescript/typescript6` 6.0.2、vue-tsc 3.3.7、Vue Router 4.6.4、Pinia 3.0.4、Element Plus 2.14.3、TanStack Vue Query 5.101.2、ECharts 6.1.0、vue-echarts 8.0.1、Vitest 4.1.10、Playwright 1.61.1、axe-core 4.12.1。
""",
            encoding="utf-8",
        )
        self.config = AuditConfig(
            workspace_root=self.root,
            prototype_root=self.prototype,
            architecture_source=self.architecture,
            pab_source=self.pab,
            generated_at="2026-07-17T21:00:00+08:00",
        )

    def tearDown(self) -> None:
        self.tempdir.cleanup()


class WorkspaceInventoryTests(AuditFixture, unittest.TestCase):
    def test_parameterized_frontend_prototype_is_not_a_production_candidate(self) -> None:
        custom_prototype = self.root / "frontend"
        self.prototype.rename(custom_prototype)
        config = AuditConfig(
            workspace_root=self.root,
            prototype_root=custom_prototype,
            architecture_source=self.architecture,
            pab_source=self.pab,
            generated_at=self.config.generated_at,
        )

        inventory = build_inventory(config)
        production_frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )

        self.assertFalse(production_frontend["present"])
        self.assertNotIn("frontend", production_frontend["evidenceLinks"])

    def test_parameterized_frontend_prototype_never_gets_recursive_workspace_snapshot(self) -> None:
        custom_prototype = self.root / "frontend"
        self.prototype.rename(custom_prototype)
        config = AuditConfig(
            workspace_root=self.root,
            prototype_root=custom_prototype,
            architecture_source=self.architecture,
            pab_source=self.pab,
            generated_at=self.config.generated_at,
        )

        before = audit_module._capture_controlled_snapshot(config)
        self.assertNotIn("frontend", before.input_scope)
        (custom_prototype / "node_modules/vue/runtime-cache.js").write_text(
            "uncontrolled install-tree change\n", encoding="utf-8"
        )
        after = audit_module._capture_controlled_snapshot(config)

        self.assertEqual(before.digest, after.digest)
        self.assertEqual(before.asset_candidates, after.asset_candidates)

    def test_nested_prototype_is_excluded_from_parent_frontend_snapshot(self) -> None:
        project_root = self.root / "apps/portal"
        project_root.mkdir(parents=True)
        custom_prototype = project_root / "prototype"
        self.prototype.rename(custom_prototype)
        (project_root / "package.json").write_text(
            '{"name":"production-portal"}\n', encoding="utf-8"
        )
        (project_root / "vite.config.ts").write_text(
            "export default {}\n", encoding="utf-8"
        )
        (project_root / "src").mkdir()
        (project_root / "src/main.ts").write_text(
            "export const production = true\n", encoding="utf-8"
        )
        config = AuditConfig(
            workspace_root=self.root,
            prototype_root=custom_prototype,
            architecture_source=self.architecture,
            pab_source=self.pab,
            generated_at=self.config.generated_at,
        )

        before = audit_module._capture_controlled_snapshot(config)
        inventory = build_inventory(config, controlled_snapshot=before)
        production_frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )
        self.assertIn("apps/portal", production_frontend["evidenceLinks"])

        (custom_prototype / "node_modules/vue/runtime-cache.js").write_text(
            "uncontrolled install-tree change\n", encoding="utf-8"
        )
        after_install_change = audit_module._capture_controlled_snapshot(config)
        self.assertEqual(before.digest, after_install_change.digest)

        (project_root / "src/main.ts").write_text(
            "export const production = false\n", encoding="utf-8"
        )
        after_production_change = audit_module._capture_controlled_snapshot(config)
        self.assertNotEqual(before.digest, after_production_change.digest)

    def test_controlled_scope_covers_target_roots_and_common_toolchain_inputs(self) -> None:
        paths = {
            "frontend/README.md": "frontend\n",
            "contracts/openapi.yaml": "openapi: 3.1.2\n",
            ".nvmrc": "24.18.0\n",
            "docs/input/原型/frontend/.nvmrc": "20\n",
            "docs/input/原型/frontend/eslint.config.js": "export default []\n",
        }
        snapshot_before = audit_module._capture_controlled_snapshot(self.config)
        for relative, content in paths.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        snapshot_after = audit_module._capture_controlled_snapshot(self.config)
        self.assertNotEqual(snapshot_before.digest, snapshot_after.digest)
        for relative in (
            "frontend",
            "contracts",
            ".nvmrc",
            "docs/input/原型/frontend/.nvmrc",
            "docs/input/原型/frontend/eslint.config.js",
        ):
            self.assertIn(relative, snapshot_after.input_scope)

    def test_frontend_and_contract_roots_have_explicit_inventory_assets(self) -> None:
        files = {
            "frontend/README.md": "production frontend candidate\n",
            "contracts/openapi.yaml": "openapi: 3.1.2\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        inventory = build_inventory(self.config)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        expected = {
            "workspace.production-frontend": "frontend",
            "workspace.contract-definitions": "contracts",
        }
        for asset_id, evidence in expected.items():
            with self.subTest(asset_id=asset_id):
                asset = assets[asset_id]
                self.assertTrue(asset["present"])
                self.assertEqual(asset["evidenceLinks"], [evidence])
                self.assertEqual(asset["owner"], "UNKNOWN")
                self.assertEqual(asset["classification"], "unknown-blocked")
                self.assertEqual(asset["ownerStory"], "1.1b")
                self.assertTrue(asset["dispositionReason"])
                self.assertTrue(asset["risk"])

    def test_contract_discovery_covers_root_contract_fingerprints(self) -> None:
        files = {
            "openapi.yaml": "openapi: 3.1.2\n",
            "asyncapi.yaml": "asyncapi: 3.0.0\n",
            "student.schema.json": '{"$schema":"https://json-schema.org/draft/2020-12/schema"}\n',
        }
        for relative, content in files.items():
            (self.root / relative).write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        contracts = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.contract-definitions"
        )
        self.assertTrue(contracts["present"])
        self.assertEqual(
            contracts["evidenceLinks"],
            ["asyncapi.yaml", "openapi.yaml", "student.schema.json"],
        )
        self.assertTrue(set(contracts["evidenceLinks"]).issubset(snapshot.input_scope))

        before = snapshot.digest
        (self.root / "openapi.yaml").write_text("openapi: 3.1.1\n", encoding="utf-8")
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_contract_discovery_covers_nested_contract_fingerprints(self) -> None:
        files = {
            "services/payments/openapi.yaml": "openapi: 3.1.2\n",
            "services/payments/events/asyncapi.yml": "asyncapi: 3.0.0\n",
            "services/payments/schemas/payment.schema.json": (
                '{"$schema":"https://json-schema.org/draft/2020-12/schema"}\n'
            ),
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        contracts = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.contract-definitions"
        )
        expected = sorted(files)
        self.assertTrue(contracts["present"])
        self.assertEqual(contracts["evidenceLinks"], expected)
        self.assertTrue(set(expected).issubset(snapshot.input_scope))

        before = snapshot.digest
        (self.root / "services/payments/openapi.yaml").write_text(
            "openapi: 3.1.1\n", encoding="utf-8"
        )
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_contract_discovery_covers_docs_fingerprints_outside_prototype(self) -> None:
        files = {
            "docs/api/openapi.yaml": "openapi: 3.1.2\n",
            "docs/events/asyncapi.yml": "asyncapi: 3.0.0\n",
            "docs/schemas/student.schema.json": (
                '{"$schema":"https://json-schema.org/draft/2020-12/schema"}\n'
            ),
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        prototype_contract = self.prototype / "src/prototype.schema.json"
        prototype_contract.write_text(
            '{"$schema":"https://json-schema.org/draft/2020-12/schema"}\n',
            encoding="utf-8",
        )
        ignored_contracts = (
            "docs/dist/openapi.yaml",
            "docs/node_modules/vendor.schema.json",
            "docs/generated/asyncapi.yml",
        )
        for relative in ignored_contracts:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("generated evidence, not a source contract\n", encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        contracts = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.contract-definitions"
        )
        expected = sorted(files)
        self.assertTrue(contracts["present"])
        self.assertEqual(contracts["evidenceLinks"], expected)
        self.assertTrue(set(expected).issubset(snapshot.input_scope))
        self.assertNotIn(
            "docs/input/原型/frontend/src/prototype.schema.json",
            snapshot.input_scope,
        )
        for relative in ignored_contracts:
            self.assertNotIn(relative, snapshot.input_scope)

        before = snapshot.digest
        (self.root / "docs/api/openapi.yaml").write_text(
            "openapi: 3.1.1\n", encoding="utf-8"
        )
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_frontend_discovery_covers_first_level_project_fingerprints(self) -> None:
        files = {
            "portal/package.json": '{"name":"portal"}\n',
            "portal/vite.config.ts": "export default {}\n",
            "portal/src/main.ts": "export {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )
        self.assertTrue(frontend["present"])
        self.assertIn("portal", frontend["evidenceLinks"])
        self.assertIn("portal", snapshot.input_scope)

    def test_frontend_discovery_covers_workspace_root_project(self) -> None:
        files = {
            "package.json": '{"name":"root-portal"}\n',
            "src/main.ts": "export const rootPortal = true\n",
            "index.html": "<main id=\"app\"></main>\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )
        self.assertTrue(frontend["present"])
        self.assertEqual(
            frontend["evidenceLinks"],
            ["index.html", "package.json", "src"],
        )
        self.assertTrue(
            {"index.html", "package.json", "src"}.issubset(snapshot.input_scope)
        )

        before = snapshot.digest
        (self.root / "src/main.ts").write_text(
            "export const rootPortal = false\n", encoding="utf-8"
        )
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_root_next_frontend_controls_app_and_pages_sources(self) -> None:
        files = {
            "package.json": '{"name":"root-next-portal"}\n',
            "next.config.mjs": "export default {}\n",
            "app/page.tsx": "export default function Page() { return null }\n",
            "pages/index.tsx": "export default function Index() { return null }\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )
        expected_sources = {"app", "pages"}
        self.assertTrue(expected_sources.issubset(frontend["evidenceLinks"]))
        self.assertTrue(expected_sources.issubset(snapshot.input_scope))

        before = snapshot.digest
        (self.root / "app/page.tsx").write_text(
            "export default function Page() { return 'changed' }\n",
            encoding="utf-8",
        )
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_nested_frontend_snapshot_excludes_install_and_build_trees(self) -> None:
        files = {
            "apps/portal/package.json": '{"name":"portal"}\n',
            "apps/portal/vite.config.ts": "export default {}\n",
            "apps/portal/src/main.ts": "export const portal = true\n",
            "apps/portal/node_modules/vue/cache.js": "install cache\n",
            "apps/portal/dist/index.js": "generated bundle\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        before = audit_module._capture_controlled_snapshot(self.config)
        (self.root / "apps/portal/node_modules/vue/cache.js").write_text(
            "changed install cache\n", encoding="utf-8"
        )
        (self.root / "apps/portal/dist/index.js").write_text(
            "changed generated bundle\n", encoding="utf-8"
        )
        after_generated_changes = audit_module._capture_controlled_snapshot(self.config)
        self.assertEqual(before.digest, after_generated_changes.digest)

        (self.root / "apps/portal/src/main.ts").write_text(
            "export const portal = false\n", encoding="utf-8"
        )
        after_source_change = audit_module._capture_controlled_snapshot(self.config)
        self.assertNotEqual(before.digest, after_source_change.digest)

    def test_root_frontend_toolchain_configuration_is_controlled(self) -> None:
        files = {
            "package.json": '{"name":"root-portal"}\n',
            "src/main.ts": "export const rootPortal = true\n",
            "index.html": '<main id="app"></main>\n',
            "tsconfig.app.json": "{}\n",
            "eslint.config.js": "export default []\n",
            ".prettierrc.json": "{}\n",
            "vitest.config.ts": "export default {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        controlled_configs = {
            "tsconfig.app.json",
            "eslint.config.js",
            ".prettierrc.json",
            "vitest.config.ts",
        }
        self.assertTrue(controlled_configs.issubset(snapshot.input_scope))

        before = snapshot.digest
        (self.root / "tsconfig.app.json").write_text(
            '{"compilerOptions":{"strict":true}}\n', encoding="utf-8"
        )
        self.assertNotEqual(
            before,
            audit_module._capture_controlled_snapshot(self.config).digest,
        )

    def test_controlled_snapshot_streams_nonsemantic_large_files(self) -> None:
        large = self.prototype / "src/large.bin"
        large.write_bytes(b"x" * (3 * 1024 * 1024))
        real_read_bytes = audit_module._read_bytes

        def reject_large_read(path: Path) -> bytes:
            if path == large:
                raise AssertionError("large controlled file must be streamed")
            return real_read_bytes(path)

        with patch("audit_production_assets._read_bytes", side_effect=reject_large_read):
            snapshot = audit_module._capture_controlled_snapshot(self.config)
        self.assertIn("docs/input/原型/frontend/src/large.bin", {e[0] for e in snapshot.entries})

    def test_inventory_records_missing_repository_backend_ci_and_deployment(self) -> None:
        inventory = build_inventory(self.config)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        for asset_id in (
            "workspace.git-repository",
            "workspace.production-backend",
            "workspace.ci-definitions",
            "workspace.deployment-engineering",
        ):
            with self.subTest(asset_id=asset_id):
                self.assertFalse(assets[asset_id]["present"])
                self.assertEqual(assets[asset_id]["owner"], "UNKNOWN")
                self.assertEqual(assets[asset_id]["classification"], "unknown-blocked")
                self.assertNotIn(".", assets[asset_id]["evidenceLinks"])
                self.assertEqual(
                    assets[asset_id]["evidenceLinks"],
                    [f"audit://absence/{asset_id}"],
                )
                self.assertTrue(assets[asset_id]["checkedPaths"])

    def test_inventory_discovers_common_ci_and_deployment_candidates(self) -> None:
        files = {
            ".circleci/config.yml": "version: 2.1\n",
            "compose.yaml": "services: {}\n",
            "Containerfile": "FROM scratch\n",
            "k8s/deployment.yaml": "apiVersion: apps/v1\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        inventory = build_inventory(self.config)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        self.assertIn(".circleci", assets["workspace.ci-definitions"]["evidenceLinks"])
        deployment_links = set(
            assets["workspace.deployment-engineering"]["evidenceLinks"]
        )
        self.assertTrue(
            {"compose.yaml", "Containerfile", "k8s"}.issubset(deployment_links)
        )

    def test_ci_discovery_covers_first_level_projects(self) -> None:
        files = {
            "service/Jenkinsfile": "pipeline {}\n",
            "portal/.gitlab-ci.yml": "stages: [test]\n",
            "worker/.github/workflows/ci.yml": "name: ci\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        inventory = build_inventory(self.config)
        ci = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.ci-definitions"
        )
        self.assertTrue(
            {
                "service/Jenkinsfile",
                "portal/.gitlab-ci.yml",
                "worker/.github/workflows",
            }.issubset(set(ci["evidenceLinks"]))
        )

    def test_standalone_database_migrations_are_backend_candidates(self) -> None:
        migration = self.root / "db/migrations/V001__init.sql"
        migration.parent.mkdir(parents=True)
        migration.write_text("create table example(id bigint);\n", encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        backend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-backend"
        )
        self.assertTrue(backend["present"])
        self.assertIn("db/migrations", backend["evidenceLinks"])
        self.assertIn("db/migrations", snapshot.input_scope)

    def test_first_level_deployment_fingerprint_captures_full_context(self) -> None:
        files = {
            "container-app/Dockerfile": "FROM scratch\n",
            "container-app/app.conf": "mode=production\n",
            "chart/Chart.yaml": "apiVersion: v2\nname: example\n",
            "chart/templates/deployment.yaml": "apiVersion: apps/v1\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        inventory = build_inventory(self.config)
        deployment = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.deployment-engineering"
        )
        self.assertTrue(
            {"container-app", "chart"}.issubset(set(deployment["evidenceLinks"]))
        )
        self.assertNotIn("container-app/Dockerfile", deployment["evidenceLinks"])

    def test_root_backend_fingerprint_captures_source_migrations_and_config(self) -> None:
        files = {
            "pom.xml": "<project />\n",
            "src/main/java/App.java": "class App {}\n",
            "db/migrations/V001__init.sql": "select 1;\n",
            "config/application.yml": "app: {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        backend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-backend"
        )
        expected = {"pom.xml", "src", "db/migrations", "config"}
        self.assertTrue(expected.issubset(set(backend["evidenceLinks"])))
        self.assertTrue(expected.issubset(set(snapshot.input_scope)))

    def test_workspace_fingerprint_discovery_covers_one_level_projects_and_root_files(self) -> None:
        files = {
            "server/pom.xml": "<project />\n",
            ".travis.yml": "language: java\n",
            "main.tf": "terraform {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        assets = {item["assetId"]: item for item in inventory["assets"]}

        self.assertIn("server", assets["workspace.production-backend"]["evidenceLinks"])
        self.assertIn(".travis.yml", assets["workspace.ci-definitions"]["evidenceLinks"])
        self.assertIn("main.tf", assets["workspace.deployment-engineering"]["evidenceLinks"])
        self.assertTrue(
            {"server", ".travis.yml", "main.tf"}.issubset(snapshot.input_scope)
        )

    def test_workspace_fingerprint_discovery_covers_nested_projects(self) -> None:
        files = {
            "services/payments/pom.xml": "<project />\n",
            "services/payments/src/main/java/App.java": "class App {}\n",
            "apps/portal/package.json": '{"name":"portal"}\n',
            "apps/portal/vite.config.ts": "export default {}\n",
            "apps/portal/src/main.ts": "export {}\n",
            "platform/environments/prod/main.tf": "terraform {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        assets = {item["assetId"]: item for item in inventory["assets"]}

        self.assertIn(
            "services/payments",
            assets["workspace.production-backend"]["evidenceLinks"],
        )
        self.assertIn(
            "apps/portal",
            assets["workspace.production-frontend"]["evidenceLinks"],
        )
        self.assertIn(
            "platform/environments/prod",
            assets["workspace.deployment-engineering"]["evidenceLinks"],
        )
        self.assertTrue(
            {
                "services/payments",
                "apps/portal",
                "platform/environments/prod",
            }.issubset(snapshot.input_scope)
        )

    def test_unreadable_first_level_directory_blocks_negative_discovery_claims(self) -> None:
        hidden = self.root / "hidden-project"
        hidden.mkdir()
        (hidden / "pom.xml").write_text("<project />\n", encoding="utf-8")
        real_scandir = os.scandir

        def fail_hidden(path: object) -> object:
            if isinstance(path, (str, os.PathLike)) and Path(path).resolve() == hidden:
                raise PermissionError("simulated unreadable project")
            return real_scandir(path)

        with patch("audit_production_assets.os.scandir", side_effect=fail_hidden):
            inventory = build_inventory(self.config)

        assets = {item["assetId"]: item for item in inventory["assets"]}
        for asset_id in (
            "workspace.production-backend",
            "workspace.ci-definitions",
            "workspace.deployment-engineering",
        ):
            with self.subTest(asset_id=asset_id):
                self.assertEqual(assets[asset_id]["readStatus"], "unreadable")
                self.assertIn("hidden-project", assets[asset_id]["checkedPaths"])
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_empty_controlled_directory_requires_read_and_traverse_mode(self) -> None:
        frontend = self.root / "frontend"
        frontend.mkdir()
        original_mode = stat.S_IMODE(frontend.stat().st_mode)
        frontend.chmod(0o444)
        try:
            inventory = build_inventory(self.config)
        finally:
            frontend.chmod(original_mode)

        production_frontend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-frontend"
        )
        self.assertTrue(production_frontend["present"])
        self.assertEqual(production_frontend["readStatus"], "unreadable")
        self.assertEqual(production_frontend["classification"], "unknown-blocked")
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_workspace_fingerprint_candidates_are_frozen_in_controlled_snapshot(self) -> None:
        server = self.root / "server"
        server.mkdir()
        (server / "pom.xml").write_text("<project />\n", encoding="utf-8")
        snapshot = audit_module._capture_controlled_snapshot(self.config)

        with patch(
            "audit_production_assets._workspace_asset_candidates",
            side_effect=AssertionError("inventory must reuse frozen candidates"),
        ):
            inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        backend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-backend"
        )

        self.assertTrue(backend["present"])
        self.assertEqual(backend["evidenceLinks"], ["server"])

    def test_frozen_unreadable_candidate_cannot_be_dropped_by_live_path_probe(self) -> None:
        server = self.root / "server"
        server.mkdir()
        (server / "pom.xml").write_text("<project />\n", encoding="utf-8")
        real_walk_directory = audit_module._walk_directory
        real_existing_paths = audit_module._existing_paths

        def fail_frozen_server(directory: Path, **kwargs: object) -> list[Path]:
            if directory.resolve() == server:
                raise AuditError("simulated frozen unreadable server")
            return real_walk_directory(directory, **kwargs)

        def hide_server_from_live_probe(candidates: list[Path]) -> list[Path]:
            return [
                path
                for path in real_existing_paths(candidates)
                if path.resolve(strict=False) != server
            ]

        with patch(
            "audit_production_assets._walk_directory",
            side_effect=fail_frozen_server,
        ):
            snapshot = audit_module._capture_controlled_snapshot(self.config)
        with patch(
            "audit_production_assets._existing_paths",
            side_effect=hide_server_from_live_probe,
        ):
            inventory = build_inventory(
                self.config,
                controlled_snapshot=snapshot,
            )

        backend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-backend"
        )
        self.assertTrue(backend["present"])
        self.assertEqual(backend["readStatus"], "unreadable")
        self.assertEqual(backend["classification"], "unknown-blocked")
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_inventory_covers_all_prototype_asset_classes_and_boundaries(self) -> None:
        inventory = build_inventory(self.config)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        expected = {
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
        self.assertTrue(expected.issubset(assets))
        self.assertEqual(assets["prototype.source"]["recommendedDisposition"], "migrate")
        self.assertEqual(assets["prototype.mocks"]["recommendedDisposition"], "replace")
        self.assertEqual(assets["prototype.dist"]["recommendedDisposition"], "replace")
        self.assertEqual(assets["prototype.node-modules"]["recommendedDisposition"], "replace")
        self.assertIn("原型假设", assets["prototype.config"]["dispositionReason"])
        self.assertIn("AD-28", assets["prototype.config"]["risk"])

    def test_prototype_config_covers_all_root_configuration_and_detects_drift(self) -> None:
        configurations = {
            ".env.example": "VITE_PUBLIC_MODE=fixture\n",
            ".prettierrc.json": "{}\n",
            "index.html": "<div id=\"app\"></div>\n",
            "tsconfig.json": "{}\n",
            "tsconfig.app.json": "{}\n",
            "tsconfig.node.json": "{}\n",
        }
        for relative, content in configurations.items():
            (self.prototype / relative).write_text(content, encoding="utf-8")

        inventory = build_inventory(self.config)
        config_asset = next(
            item for item in inventory["assets"] if item["assetId"] == "prototype.config"
        )
        expected_links = {
            f"docs/input/原型/frontend/{relative}" for relative in configurations
        } | {"docs/input/原型/frontend/vite.config.ts"}
        self.assertTrue(expected_links.issubset(set(config_asset["evidenceLinks"])))

        for index, relative in enumerate(configurations):
            with self.subTest(relative=relative):
                paths = ArtifactPaths(
                    inventory=self.root / f"config-drift-{index}/inventory.md",
                    schema=self.root / f"config-drift-{index}/schema.json",
                    gap=self.root / f"config-drift-{index}/gap.md",
                )
                generate_artifacts(self.config, paths)
                target = self.prototype / relative
                original = target.read_text(encoding="utf-8")
                target.write_text(original + "changed\n", encoding="utf-8")
                try:
                    with self.assertRaisesRegex(AuditError, "漂移"):
                        check_artifacts(self.config, paths)
                finally:
                    target.write_text(original, encoding="utf-8")

    def test_prototype_config_covers_test_tooling_configuration(self) -> None:
        configurations = {
            "vitest.config.ts": "export default {}\n",
            "playwright.config.ts": "export default {}\n",
            "cypress.config.ts": "export default {}\n",
        }
        for relative, content in configurations.items():
            (self.prototype / relative).write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        config_asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.config"
        )
        expected = {
            f"docs/input/原型/frontend/{relative}" for relative in configurations
        }
        self.assertTrue(expected.issubset(set(config_asset["evidenceLinks"])))
        self.assertTrue(expected.issubset(set(snapshot.input_scope)))

    def test_prototype_config_covers_postcss_and_tailwind_configuration(self) -> None:
        configurations = {
            "postcss.config.cjs": "module.exports = {}\n",
            "tailwind.config.mjs": "export default {}\n",
        }
        for relative, content in configurations.items():
            (self.prototype / relative).write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        config_asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.config"
        )
        expected = {
            f"docs/input/原型/frontend/{relative}" for relative in configurations
        }
        self.assertTrue(expected.issubset(set(config_asset["evidenceLinks"])))
        self.assertTrue(expected.issubset(set(snapshot.input_scope)))

        paths = ArtifactPaths(
            inventory=self.root / "postcss-tailwind-drift/inventory.md",
            schema=self.root / "postcss-tailwind-drift/schema.json",
            gap=self.root / "postcss-tailwind-drift/gap.md",
        )
        generate_artifacts(self.config, paths)
        for relative in configurations:
            target = self.prototype / relative
            original = target.read_text(encoding="utf-8")
            target.write_text(original + "// changed\n", encoding="utf-8")
            try:
                with self.assertRaisesRegex(AuditError, "漂移"):
                    check_artifacts(self.config, paths)
            finally:
                target.write_text(original, encoding="utf-8")

    def test_prototype_config_and_dist_have_disjoint_evidence_and_drift(self) -> None:
        before = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }
        dist_file = self.prototype / "dist/index.html"
        dist_file.write_text("<html>changed dist</html>\n", encoding="utf-8")
        after = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }

        config_links = set(before["prototype.config"]["evidenceLinks"])
        dist_links = set(before["prototype.dist"]["evidenceLinks"])
        self.assertTrue(config_links.isdisjoint(dist_links))
        self.assertEqual(before["prototype.config"], after["prototype.config"])
        self.assertNotEqual(
            before["prototype.dist"]["versionOrDigest"],
            after["prototype.dist"]["versionOrDigest"],
        )

    def test_unknown_prototype_owner_blocks_classification_but_keeps_recommendation(self) -> None:
        inventory = build_inventory(self.config)
        prototype_assets = [item for item in inventory["assets"] if item["assetId"].startswith("prototype.")]
        self.assertTrue(prototype_assets)
        for asset in prototype_assets:
            self.assertEqual(asset["owner"], "UNKNOWN")
            self.assertEqual(asset["classification"], "unknown-blocked")
            self.assertIn(asset["ownerStory"], {"1.1b", "1.1c", "1.1d"})

    def test_inventory_never_echoes_source_secrets(self) -> None:
        rendered = json.dumps(build_inventory(self.config), ensure_ascii=False)
        self.assertNotIn("fixture-secret-must-not-leak", rendered)

    def test_inventory_is_stably_sorted_and_valid(self) -> None:
        inventory = build_inventory(self.config)
        ids = [item["assetId"] for item in inventory["assets"]]
        self.assertEqual(ids, sorted(ids))
        self.assertEqual(validate_inventory(inventory, self.root), [])

    def test_all_existing_candidate_paths_are_preserved(self) -> None:
        (self.prototype / "public").mkdir()
        (self.prototype / "public/manifest.json").write_text("{}", encoding="utf-8")
        (self.root / "backend").mkdir()
        (self.root / "backend/README.md").write_text("backend", encoding="utf-8")
        (self.root / "pom.xml").write_text("<project />", encoding="utf-8")
        inventory = build_inventory(self.config)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        self.assertEqual(
            assets["prototype.static-assets"]["evidenceLinks"],
            ["docs/input/原型/frontend/public", "docs/input/原型/frontend/src/assets"],
        )
        self.assertEqual(
            assets["workspace.production-backend"]["evidenceLinks"],
            ["backend", "pom.xml"],
        )

    def test_same_category_parent_child_candidates_are_counted_once(self) -> None:
        files = {
            "services/pom.xml": "<project />\n",
            "services/src/main/java/Root.java": "class Root {}\n",
            "services/child/pom.xml": "<project />\n",
            "services/child/src/main/java/Child.java": "class Child {}\n",
        }
        for relative, content in files.items():
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        snapshot = audit_module._capture_controlled_snapshot(self.config)
        inventory = build_inventory(self.config, controlled_snapshot=snapshot)
        backend = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "workspace.production-backend"
        )
        self.assertTrue(
            {"services", "services/child"}.issubset(backend["evidenceLinks"])
        )
        unique_summary = audit_module._snapshot_path_summary(
            snapshot,
            self.root / "services",
            self.root,
        )
        self.assertEqual(
            backend["metrics"]["regularFileCount"],
            unique_summary["fileCount"],
        )
        self.assertEqual(
            backend["metrics"]["regularFileByteCount"],
            unique_summary["byteCount"],
        )

    def test_node_modules_runtime_tmp_does_not_change_snapshot(self) -> None:
        before = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }["prototype.node-modules"]
        temporary = self.prototype / "node_modules/.tmp"
        temporary.mkdir()
        (temporary / "type-check.cache").write_text("volatile", encoding="utf-8")
        after = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }["prototype.node-modules"]
        self.assertEqual(before, after)

    def test_node_modules_internal_content_is_excluded_from_asset_digest(self) -> None:
        before = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }["prototype.node-modules"]
        (self.prototype / "node_modules/vue/runtime-cache.js").write_text(
            "changed internal install tree", encoding="utf-8"
        )
        after = {
            item["assetId"]: item for item in build_inventory(self.config)["assets"]
        }["prototype.node-modules"]
        self.assertTrue(before["snapshotOnly"])
        self.assertEqual(before["versionOrDigest"], after["versionOrDigest"])

    def test_node_modules_snapshot_never_recurses_into_internal_special_files(self) -> None:
        fifo = self.prototype / "node_modules/vue/internal.pipe"
        os.mkfifo(fifo)
        try:
            inventory = build_inventory(self.config)
        finally:
            fifo.unlink()
        asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        self.assertEqual(asset["readStatus"], "readable")
        self.assertTrue(asset["snapshotOnly"])
        self.assertEqual(asset["metrics"]["scope"], "top-level-entries")
        self.assertGreater(asset["metrics"]["topLevelEntryCount"], 0)
        self.assertNotIn("fileCount", asset["metrics"])
        self.assertNotIn("byteCount", asset["metrics"])

    def test_node_modules_top_level_stat_failure_is_not_silently_omitted(self) -> None:
        node_modules = self.prototype / "node_modules"
        real_scandir = os.scandir

        class FailingEntry:
            name = "unreadable-package"

            def stat(self, *, follow_symlinks: bool = True) -> os.stat_result:
                raise OSError("simulated stat failure")

        class FailingScan:
            def __enter__(self) -> object:
                return iter([FailingEntry()])

            def __exit__(self, *args: object) -> bool:
                return False

        def fail_node_modules_entry(path: object) -> object:
            if Path(path) == node_modules:
                return FailingScan()
            return real_scandir(path)

        with patch(
            "audit_production_assets.os.scandir",
            side_effect=fail_node_modules_entry,
        ):
            inventory = build_inventory(self.config)

        asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        self.assertEqual(asset["readStatus"], "unreadable")
        self.assertEqual(asset["classification"], "unknown-blocked")
        self.assertNotIn("metrics", asset)
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_node_modules_requires_directory_read_and_traverse_mode(self) -> None:
        node_modules = self.prototype / "node_modules"
        original_mode = stat.S_IMODE(node_modules.stat().st_mode)
        real_scandir = os.scandir

        class EmptyScan:
            def __enter__(self) -> object:
                return iter(())

            def __exit__(self, *args: object) -> bool:
                return False

        def allow_enumeration_despite_mode(path: object) -> object:
            if Path(path) == node_modules:
                return EmptyScan()
            return real_scandir(path)

        node_modules.chmod(0o111)
        try:
            with patch(
                "audit_production_assets.os.scandir",
                side_effect=allow_enumeration_despite_mode,
            ):
                inventory = build_inventory(self.config)
        finally:
            node_modules.chmod(original_mode)

        asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        self.assertEqual(asset["readStatus"], "unreadable")
        self.assertEqual(asset["classification"], "unknown-blocked")
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_node_modules_regular_file_is_unknown_blocked(self) -> None:
        node_modules = self.prototype / "node_modules"
        backup = self.prototype / "node_modules.fixture-backup"
        node_modules.rename(backup)
        node_modules.write_text("not an installed dependency tree\n", encoding="utf-8")
        try:
            inventory = build_inventory(self.config)
        finally:
            node_modules.unlink()
            backup.rename(node_modules)

        asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        self.assertEqual(asset["readStatus"], "unreadable")
        self.assertEqual(asset["classification"], "unknown-blocked")
        self.assertFalse(asset["snapshotOnly"])
        self.assertNotIn("metrics", asset)
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")


class DependencyGapTests(AuditFixture, unittest.TestCase):
    def test_casefold_ties_have_total_deterministic_order(self) -> None:
        package = json.loads((self.prototype / "package.json").read_text(encoding="utf-8"))
        package["dependencies"]["Foo"] = "1.0.0"
        package["dependencies"]["foo"] = "1.0.0"
        lock = json.loads((self.prototype / "package-lock.json").read_text(encoding="utf-8"))
        lock["packages"][""]["dependencies"]["Foo"] = "1.0.0"
        lock["packages"][""]["dependencies"]["foo"] = "1.0.0"
        lock["packages"]["node_modules/Foo"] = {"version": "1.0.0"}
        lock["packages"]["node_modules/foo"] = {"version": "1.0.0"}
        (self.prototype / "package.json").write_text(json.dumps(package), encoding="utf-8")
        (self.prototype / "package-lock.json").write_text(json.dumps(lock), encoding="utf-8")
        rows = build_dependency_gap(self.config)
        names = [row["dependency"] for row in rows]
        self.assertEqual(names, sorted(names, key=lambda value: (value.casefold(), value)))

    def test_gap_covers_pab_and_required_prototype_extras(self) -> None:
        rows = build_dependency_gap(self.config)
        names = {row["dependency"] for row in rows}
        expected = {
            "OpenJDK",
            "Spring Boot",
            "PostgreSQL",
            "OpenAPI",
            "CloudEvents",
            "JSON Schema",
            "Node.js",
            "npm",
            "Vite",
            "Vue",
            "TypeScript",
            "vue-tsc",
            "Vue Router",
            "Pinia",
            "Element Plus",
            "TanStack Vue Query",
            "ECharts",
            "vue-echarts",
            "Vitest",
            "Playwright",
            "axe-core",
            "axios",
            "@vitejs/plugin-vue",
            "@element-plus/icons-vue",
            "@types/node",
            "prettier",
        }
        self.assertTrue(expected.issubset(names))

    def test_pab_oracle_is_semantically_read_and_conflicts_fail(self) -> None:
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["OpenJDK"]["pabBaseline"], "25 LTS")
        self.assertEqual(rows["OpenAPI"]["pabBaseline"], "3.1.2")
        self.pab.write_text(
            self.pab.read_text(encoding="utf-8").replace("Vite 8.1.5", "Vite 8.1.4"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "PAB oracle 冲突"):
            build_dependency_gap(self.config)

    def test_pab_oracle_is_pinned_to_story_approved_exact_values(self) -> None:
        self.architecture.write_text(
            self.architecture.read_text(encoding="utf-8").replace(
                "| Vite / Vue / TypeScript | 8.1.5 /",
                "| Vite / Vue / TypeScript | 8.1.4 /",
            ),
            encoding="utf-8",
        )
        self.pab.write_text(
            self.pab.read_text(encoding="utf-8").replace(
                "Vite 8.1.5", "Vite 8.1.4"
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(AuditError, "Story 批准值"):
            build_dependency_gap(self.config)

    def test_pab_parser_is_scoped_rejects_duplicates_and_requires_all_authorizations(self) -> None:
        original_architecture = self.architecture.read_text(encoding="utf-8")
        original_pab = self.pab.read_text(encoding="utf-8")
        self.architecture.write_text(
            original_architecture
            + "\n## 无关表\n\n| 名称 | 精确基线 |\n| --- | --- |\n| Vite | 9.9.9 |\n",
            encoding="utf-8",
        )
        self.pab.write_text(
            original_pab
            + "\n技术选择参考：[PostgreSQL 18.4 release](https://example.invalid/postgresql)。\n",
            encoding="utf-8",
        )
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["Vite"]["pabBaseline"], "8.1.5")

        self.architecture.write_text(
            original_architecture.replace(
                "| Vite / Vue / TypeScript | 8.1.5 / 3.5.40 / `@typescript/typescript6` 6.0.2 |",
                "| Vite / Vue / TypeScript | 8.1.5 / 3.5.40 / `@typescript/typescript6` 6.0.2 |\n"
                "| Vite | 8.1.5 |",
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "重复"):
            build_dependency_gap(self.config)

        self.architecture.write_text(original_architecture, encoding="utf-8")
        self.pab.write_text(
            original_pab.replace("axe-core 4.12.1", "axe-core"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "缺失|冲突"):
            build_dependency_gap(self.config)

    def test_pab_oracle_requires_exact_unique_version_tokens(self) -> None:
        original_architecture = self.architecture.read_text(encoding="utf-8")
        original_pab = self.pab.read_text(encoding="utf-8")
        cases = {
            "version-prefix": (
                original_architecture,
                original_pab.replace("Vite 8.1.5", "Vite 8.1.5+tampered"),
            ),
            "duplicate-conflict": (
                original_architecture,
                original_pab.replace(
                    "Vite 8.1.5、Vue",
                    "Vite 8.1.5、Vite 8.1.4、Vue",
                ),
            ),
            "range-in-table-and-prose": (
                original_architecture.replace(
                    "| Vite / Vue / TypeScript | 8.1.5 /",
                    "| Vite / Vue / TypeScript | ^8.1.5 /",
                ),
                original_pab.replace("Vite 8.1.5", "Vite ^8.1.5"),
            ),
        }
        for name, (architecture_text, pab_text) in cases.items():
            with self.subTest(name=name):
                self.architecture.write_text(architecture_text, encoding="utf-8")
                self.pab.write_text(pab_text, encoding="utf-8")
                with self.assertRaisesRegex(AuditError, "PAB oracle"):
                    build_dependency_gap(self.config)
        self.architecture.write_text(original_architecture, encoding="utf-8")
        self.pab.write_text(original_pab, encoding="utf-8")

    def test_pab_exact_list_allows_following_nonbaseline_sentences(self) -> None:
        original = self.pab.read_text(encoding="utf-8")
        self.pab.write_text(
            original.replace(
                "axe-core 4.12.1。",
                "axe-core 4.12.1。CI 镜像另用 digest 固定。"
                "Node 26 Current 与 TypeScript 7.0 不进入本基线。",
            ),
            encoding="utf-8",
        )
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["Vite"]["pabBaseline"], "8.1.5")
        self.assertEqual(rows["TypeScript"]["pabBaseline"], "@typescript/typescript6 6.0.2")

    def test_declared_range_and_lock_resolution_are_distinct(self) -> None:
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["Vite"]["prototypeRange"], "^5.0.0")
        self.assertEqual(rows["Vite"]["lockfileVersion"], "5.0.1")
        self.assertEqual(rows["Vite"]["pabBaseline"], "8.1.5")
        self.assertEqual(rows["Vite"]["classification"], "reference-only")
        self.assertEqual(rows["Vue"]["prototypeRange"], "^3.4.0")
        self.assertEqual(rows["Vue"]["lockfileVersion"], "3.4.0")

    def test_literal_missing_markers_remain_declared_and_resolved_values(self) -> None:
        package_path = self.prototype / "package.json"
        lock_path = self.prototype / "package-lock.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        package["devDependencies"]["vite"] = "NOT-DECLARED"
        package["dependencies"]["literal-extra"] = "NOT-DECLARED"
        package["engines"] = {"node": "NOT-DECLARED"}
        lock["packages"][""]["devDependencies"]["vite"] = "NOT-DECLARED"
        lock["packages"][""]["dependencies"]["literal-extra"] = "NOT-DECLARED"
        lock["packages"]["node_modules/vite"]["version"] = "NOT-RESOLVED"
        lock["packages"]["node_modules/literal-extra"] = {
            "version": "NOT-RESOLVED"
        }
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock_path.write_text(json.dumps(lock), encoding="utf-8")

        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        vite = rows["Vite"]
        self.assertEqual(vite["prototypeState"], "declared")
        self.assertEqual(vite["lockfileState"], "resolved")
        self.assertEqual(vite["prototypeRange"], "NOT-DECLARED")
        self.assertEqual(vite["lockfileVersion"], "NOT-RESOLVED")
        self.assertEqual(vite["differenceType"], "version-mismatch")
        self.assertEqual(vite["classification"], "reference-only")

        extra = rows["literal-extra"]
        self.assertEqual(extra["prototypeState"], "declared")
        self.assertEqual(extra["lockfileState"], "resolved")
        self.assertEqual(extra["differenceType"], "unfrozen-prototype-extra")

        node = rows["Node.js"]
        self.assertEqual(node["prototypeState"], "declared")
        self.assertEqual(node["lockfileState"], "not-recorded")
        self.assertIn("记录了运行时版本声明", node["migrationRisk"])

    def test_engines_root_must_be_object_when_present(self) -> None:
        package_path = self.prototype / "package.json"
        original = json.loads(package_path.read_text(encoding="utf-8"))
        try:
            for value in ([], "node>=24", None):
                with self.subTest(value_type=type(value).__name__):
                    package = json.loads(json.dumps(original))
                    package["engines"] = value
                    package_path.write_text(json.dumps(package), encoding="utf-8")
                    with self.assertRaisesRegex(AuditError, "engines 必须为对象"):
                        build_dependency_gap(self.config)
        finally:
            package_path.write_text(json.dumps(original), encoding="utf-8")

    def test_package_manager_supplies_exact_npm_manifest_evidence(self) -> None:
        package_path = self.prototype / "package.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        package["packageManager"] = "npm@11.16.0"
        package_path.write_text(json.dumps(package), encoding="utf-8")

        npm_row = next(
            row for row in build_dependency_gap(self.config) if row["dependency"] == "npm"
        )
        self.assertEqual(npm_row["prototypeRange"], "11.16.0")
        self.assertNotEqual(npm_row["prototypeRange"], "NOT-DECLARED")
        self.assertIn("运行", npm_row["migrationRisk"])

    def test_non_npm_package_manager_is_an_explicit_pab_conflict(self) -> None:
        package_path = self.prototype / "package.json"
        original = json.loads(package_path.read_text(encoding="utf-8"))
        for declaration in ("pnpm@9.12.3", "yarn@4.5.1"):
            with self.subTest(declaration=declaration):
                package = json.loads(json.dumps(original))
                package["packageManager"] = declaration
                package_path.write_text(json.dumps(package), encoding="utf-8")

                npm_row = next(
                    row
                    for row in build_dependency_gap(self.config)
                    if row["dependency"] == "npm"
                )
                self.assertEqual(npm_row["prototypeRange"], declaration)
                self.assertEqual(
                    npm_row["differenceType"], "package-manager-conflict"
                )
                self.assertEqual(npm_row["classification"], "unknown-blocked")
                self.assertIn(declaration.split("@", 1)[0], npm_row["migrationRisk"])
                self.assertIn("npm", npm_row["disposition"])

    def test_missing_pab_candidates_and_unfrozen_extras_have_explicit_disposition(self) -> None:
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        for dependency in ("TanStack Vue Query", "Vitest", "Playwright", "axe-core"):
            self.assertEqual(rows[dependency]["differenceType"], "missing-from-prototype")
            self.assertEqual(rows[dependency]["ownerStory"], "1.1c")
        for dependency in ("axios", "@vitejs/plugin-vue", "@element-plus/icons-vue", "@types/node"):
            self.assertEqual(rows[dependency]["pabBaseline"], "NOT-FROZEN")
            self.assertIn("移除、替换或在 1.1c 中通过新 ADR 批准", rows[dependency]["disposition"])
        self.assertEqual(rows["prettier"]["pabBaseline"], "NOT-FROZEN")

    def test_sensitive_dependency_spec_is_redacted_from_rows_and_markdown(self) -> None:
        package_path = self.prototype / "package.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        package["dependencies"]["private-package"] = (
            "https://user:password@packages.example/pkg.tgz?token=supersecret"
        )
        package["dependencies"]["single-userinfo"] = (
            "https://single-secret-token@packages.example/pkg.tgz"
        )
        package["dependencies"]["encoded-userinfo"] = (
            "https://user%3Aencoded-secret@packages.example/pkg.tgz"
        )
        package["dependencies"]["mixed-query"] = (
            "https://packages.example/pkg.tgz?ToKeN=query-secret"
        )
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock_path = self.prototype / "package-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        lock["packages"][""]["dependencies"] = package["dependencies"]
        for dependency in (
            "private-package",
            "single-userinfo",
            "encoded-userinfo",
            "mixed-query",
        ):
            lock["packages"][f"node_modules/{dependency}"] = {"version": "1.0.0"}
        lock["packages"]["node_modules/vue"]["version"] = (
            "https://lock-secret@packages.example/vue.tgz"
        )
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        rows = build_dependency_gap(self.config)
        private = next(row for row in rows if row["dependency"] == "private-package")
        report = render_gap_report(rows, self.config)
        self.assertEqual(private["prototypeRange"], "REDACTED-SENSITIVE-SPEC")
        self.assertEqual(
            next(row for row in rows if row["dependency"] == "single-userinfo")[
                "prototypeRange"
            ],
            "REDACTED-SENSITIVE-SPEC",
        )
        self.assertEqual(
            next(row for row in rows if row["dependency"] == "Vue")["lockfileVersion"],
            "REDACTED-SENSITIVE-SPEC",
        )
        for secret in (
            "password",
            "supersecret",
            "single-secret-token",
            "encoded-secret",
            "query-secret",
            "lock-secret",
            "token=",
        ):
            self.assertNotIn(secret, json.dumps(rows, ensure_ascii=False))
            self.assertNotIn(secret, report)

    def test_sensitive_key_variants_and_pab_conflicts_never_leak(self) -> None:
        package_path = self.prototype / "package.json"
        lock_path = self.prototype / "package-lock.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        sensitive_specs = {
            "access-token-package": "https://packages.example/a.tgz?access_token=ACCESS_SECRET",
            "api-key-package": "https://packages.example/b.tgz?api_key=API_SECRET",
            "auth-token-package": "https://packages.example/c.tgz?authToken=AUTH_SECRET",
            "refresh-token-package": "https://packages.example/d.tgz?refresh_token=REFRESH_SECRET",
            "id-token-package": "https://packages.example/e.tgz?id_token=ID_SECRET",
            "private-token-package": "https://packages.example/f.tgz?private_token=PRIVATE_SECRET",
            "session-token-package": "https://packages.example/g.tgz?session_token=SESSION_SECRET",
            "secret-key-package": "https://packages.example/h.tgz?secret_key=KEY_SECRET",
        }
        package["dependencies"].update(sensitive_specs)
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        lock["packages"][""]["dependencies"] = package["dependencies"]
        for dependency in sensitive_specs:
            lock["packages"][f"node_modules/{dependency}"] = {"version": "1.0.0"}
        lock_path.write_text(json.dumps(lock), encoding="utf-8")

        rows = build_dependency_gap(self.config)
        rendered = render_gap_report(rows, self.config)
        by_dependency = {row["dependency"]: row for row in rows}
        for dependency in sensitive_specs:
            self.assertEqual(
                by_dependency[dependency]["prototypeRange"],
                "REDACTED-SENSITIVE-SPEC",
            )
        for secret in (
            "ACCESS_SECRET",
            "API_SECRET",
            "AUTH_SECRET",
            "REFRESH_SECRET",
            "ID_SECRET",
            "PRIVATE_SECRET",
            "SESSION_SECRET",
            "KEY_SECRET",
        ):
            self.assertNotIn(secret, json.dumps(rows, ensure_ascii=False))
            self.assertNotIn(secret, rendered)

        self.architecture.write_text(
            self.architecture.read_text(encoding="utf-8").replace(
                "| Vite / Vue / TypeScript | 8.1.5 /",
                "| Vite / Vue / TypeScript | 8.1.5?access_token=PAB_SECRET /",
            ),
            encoding="utf-8",
        )
        paths = ArtifactPaths(
            inventory=self.root / "pab-secret/inventory.md",
            schema=self.root / "pab-secret/schema.json",
            gap=self.root / "pab-secret/gap.md",
        )
        with self.assertRaises(AuditError) as caught:
            generate_artifacts(self.config, paths)
        failure_output = str(caught.exception) + paths.gap.read_text(encoding="utf-8")
        self.assertNotIn("PAB_SECRET", failure_output)

    def test_non_string_dependency_values_fail_without_leaking_secrets(self) -> None:
        package_path = self.prototype / "package.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        package["dependencies"]["private-object"] = {
            "credential": "object-secret-must-not-leak"
        }
        package_path.write_text(json.dumps(package), encoding="utf-8")
        paths = ArtifactPaths(
            inventory=self.root / "unsafe/inventory.md",
            schema=self.root / "unsafe/schema.json",
            gap=self.root / "unsafe/gap.md",
        )
        with self.assertRaises(AuditError) as caught:
            generate_artifacts(self.config, paths)
        rendered = str(caught.exception) + "\n" + "\n".join(
            path.read_text(encoding="utf-8") for path in (paths.inventory, paths.schema, paths.gap)
        )
        self.assertNotIn("object-secret-must-not-leak", rendered)

        package["dependencies"].pop("private-object")
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock_path = self.prototype / "package-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        lock["packages"]["node_modules/vue"]["version"] = {
            "credential": "lock-object-secret-must-not-leak"
        }
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        second_paths = ArtifactPaths(
            inventory=self.root / "unsafe-lock/inventory.md",
            schema=self.root / "unsafe-lock/schema.json",
            gap=self.root / "unsafe-lock/gap.md",
        )
        with self.assertRaises(AuditError) as caught:
            generate_artifacts(self.config, second_paths)
        rendered = str(caught.exception) + "\n" + "\n".join(
            path.read_text(encoding="utf-8")
            for path in (second_paths.inventory, second_paths.schema, second_paths.gap)
        )
        self.assertNotIn("lock-object-secret-must-not-leak", rendered)

    def test_transitive_only_pab_package_is_not_reported_as_version_mismatch(self) -> None:
        lock_path = self.prototype / "package-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        lock["packages"]["node_modules/vitest"] = {"version": "4.1.10"}
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["Vitest"]["differenceType"], "transitive-only")
        self.assertNotEqual(rows["Vitest"]["differenceType"], "version-mismatch")

    def test_direct_dependency_without_exact_lock_node_is_input_failure(self) -> None:
        lock_path = self.prototype / "package-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        del lock["packages"]["node_modules/vue"]
        lock_path.write_text(json.dumps(lock), encoding="utf-8")

        with self.assertRaisesRegex(AuditError, "精确解析节点"):
            build_dependency_gap(self.config)

        paths = ArtifactPaths(
            inventory=self.root / "blocked/inventory.md",
            schema=self.root / "blocked/inventory.schema.json",
            gap=self.root / "blocked/gap.md",
        )
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "generate",
                    "--workspace-root",
                    str(self.root),
                    "--prototype-root",
                    str(self.prototype),
                    "--architecture-source",
                    str(self.architecture),
                    "--pab-source",
                    str(self.pab),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                    "--generated-at",
                    "2026-07-17T21:00:00+08:00",
                ]
            )
        self.assertEqual(exit_code, 2)
        rows = parse_gap_report(paths.gap)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["dependency"], "AUDIT-INPUT-BLOCKED")
        self.assertEqual(rows[0]["classification"], "unknown-blocked")

    def test_peer_dependency_is_direct_and_manifest_lock_contract_is_strict(self) -> None:
        package_path = self.prototype / "package.json"
        lock_path = self.prototype / "package-lock.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        package["peerDependencies"] = {"vitest": "^4.1.10"}
        lock["packages"][""]["peerDependencies"] = {"vitest": "^4.1.10"}
        lock["packages"]["node_modules/vitest"] = {"version": "4.1.10"}
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        rows = {row["dependency"]: row for row in build_dependency_gap(self.config)}
        self.assertEqual(rows["Vitest"]["prototypeRange"], "^4.1.10")
        self.assertNotEqual(rows["Vitest"]["differenceType"], "transitive-only")

        package["devDependencies"]["vue"] = "^3.4.0"
        package_path.write_text(json.dumps(package), encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "跨区重复"):
            build_dependency_gap(self.config)

        package["devDependencies"].pop("vue")
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock["name"] = "another-project"
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "不属于当前 package.json"):
            build_dependency_gap(self.config)

        lock["name"] = package["name"]
        lock["packages"][""]["dependencies"]["vue"] = "^0.0.1"
        lock_path.write_text(json.dumps(lock), encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "直接依赖声明不一致"):
            build_dependency_gap(self.config)

    def test_gap_report_separates_verified_facts_from_pending_evidence(self) -> None:
        report = render_gap_report(build_dependency_gap(self.config), self.config)
        self.assertIn("本 Story 核验的当前事实", report)
        self.assertIn("1.1c / 1.1d 尚未产生的证据", report)
        owner_line = next(line for line in report.splitlines() if line.startswith("- 后续 owner："))
        self.assertIn("Story 1.1b", owner_line)
        self.assertIn("Story 1.1c", owner_line)
        self.assertNotIn("每项唯一绑定 Story 1.1c", owner_line)
        for evidence in ("精确生产 lock", "SBOM", "provenance", "签名", "漏洞", "许可证", "浏览器/WebView"):
            self.assertIn(evidence, report)

    def test_story_1_1d_handoff_lists_all_ad28_actual_reports(self) -> None:
        report = render_gap_report(build_dependency_gap(self.config), self.config)
        handoff_line = next(
            line for line in report.splitlines() if line.startswith("- Story 1.1d：")
        )
        for required in ("浏览器/WebView", "视觉", "无障碍", "实际"):
            self.assertIn(required, handoff_line)
        self.assertIn("禁止制品提升", handoff_line)

    def test_corrupt_package_json_and_missing_lockfile_fail_visibly(self) -> None:
        (self.prototype / "package.json").write_text("{broken", encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "JSON 解析失败"):
            build_dependency_gap(self.config)
        (self.prototype / "package.json").write_text("{}", encoding="utf-8")
        (self.prototype / "package-lock.json").unlink()
        with self.assertRaisesRegex(AuditError, "必需输入不存在"):
            build_dependency_gap(self.config)

    def test_json_inputs_reject_duplicate_keys(self) -> None:
        package_path = self.prototype / "package.json"
        lock_path = self.prototype / "package-lock.json"
        originals = {
            package_path: package_path.read_text(encoding="utf-8"),
            lock_path: lock_path.read_text(encoding="utf-8"),
        }
        mutations = {
            package_path: '{"name":"shadow",' + originals[package_path].lstrip()[1:],
            lock_path: '{"lockfileVersion":2,' + originals[lock_path].lstrip()[1:],
        }
        for target, content in mutations.items():
            with self.subTest(target=target.name):
                target.write_text(content, encoding="utf-8")
                try:
                    with self.assertRaisesRegex(AuditError, "重复键"):
                        build_dependency_gap(self.config)
                finally:
                    target.write_text(originals[target], encoding="utf-8")

    def test_json_inputs_reject_non_finite_constants(self) -> None:
        package_path = self.prototype / "package.json"
        original = package_path.read_text(encoding="utf-8")
        for constant in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(constant=constant):
                package_path.write_text(
                    original.rstrip()[:-1] + f',"metadata":{constant}}}',
                    encoding="utf-8",
                )
                try:
                    with self.assertRaisesRegex(AuditError, "非有限|JSON"):
                        build_dependency_gap(self.config)
                finally:
                    package_path.write_text(original, encoding="utf-8")

    def test_gap_parser_rejects_empty_or_unknown_rows(self) -> None:
        paths = ArtifactPaths(
            inventory=self.root / "inventory.md",
            schema=self.root / "schema.json",
            gap=self.root / "gap.md",
        )
        paths.gap.write_text(
            "<!-- audit-generation-id: sha256:"
            + "a" * 64
            + " -->\n<!-- dependency-gap-json:start -->\n```json\n[{}]\n```\n"
            "<!-- dependency-gap-json:end -->\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "依赖差异行校验失败"):
            parse_gap_report(paths.gap)

    def test_gap_validation_rejects_cross_field_semantic_contradictions(self) -> None:
        original = build_dependency_gap(self.config)
        mutations = [
            ("Vite", "differenceType", "exact-match-unverified"),
            ("Vite", "pabBaseline", "NOT-FROZEN"),
            ("axios", "classification", "reference-only"),
            ("Node.js", "classification", "migrate"),
        ]
        for dependency, field, value in mutations:
            with self.subTest(dependency=dependency, field=field):
                rows = json.loads(json.dumps(original))
                row = next(item for item in rows if item["dependency"] == dependency)
                row[field] = value
                errors = "\n".join(validate_gap_rows(rows))
                self.assertIn("语义", errors)


class ReproducibleAuditTests(AuditFixture, unittest.TestCase):
    def _paths(self, name: str) -> ArtifactPaths:
        output = self.root / name
        return ArtifactPaths(
            inventory=output / "inventory.md",
            schema=output / "inventory.schema.json",
            gap=output / "gap.md",
        )

    def _validate_exit(self, paths: ArtifactPaths) -> tuple[int, str]:
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "validate",
                    "--workspace-root",
                    str(self.root),
                    "--prototype-root",
                    str(self.prototype),
                    "--architecture-source",
                    str(self.architecture),
                    "--pab-source",
                    str(self.pab),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                ]
            )
        return exit_code, stderr.getvalue()

    def test_two_generations_are_byte_stable_and_check_passes(self) -> None:
        first = self._paths("first")
        second = self._paths("second")
        generate_artifacts(self.config, first)
        generate_artifacts(self.config, second)
        self.assertEqual(
            canonical_inventory(parse_inventory_report(first.inventory)),
            canonical_inventory(parse_inventory_report(second.inventory)),
        )
        first_schema = json.loads(first.schema.read_text(encoding="utf-8"))
        second_schema = json.loads(second.schema.read_text(encoding="utf-8"))
        first_schema.pop("x-generationId")
        second_schema.pop("x-generationId")
        self.assertEqual(first_schema, second_schema)
        self.assertEqual(parse_gap_report(first.gap), parse_gap_report(second.gap))
        check_artifacts(self.config, first)

    def test_node_modules_top_level_presence_is_frozen_during_generation_aba(self) -> None:
        paths = self._paths("node-modules-presence-aba")
        node_modules = self.prototype / "node_modules"
        real_existing_paths = audit_module._existing_paths

        def transient_absence(candidates: list[Path]) -> list[Path]:
            if candidates == [node_modules]:
                return []
            return real_existing_paths(candidates)

        with patch(
            "audit_production_assets._existing_paths",
            side_effect=transient_absence,
        ):
            generate_artifacts(self.config, paths)

        inventory = parse_inventory_report(paths.inventory)
        asset = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        self.assertTrue(asset["present"])
        self.assertEqual(asset["readStatus"], "readable")
        self.assertTrue(asset["snapshotOnly"])
        self.assertIn(
            "docs/input/原型/frontend/node_modules",
            inventory["executionEvidence"]["inputScope"],
        )
        check_artifacts(self.config, paths)

    def test_check_detects_controlled_input_drift_without_overwriting_evidence(self) -> None:
        paths = self._paths("approved")
        generate_artifacts(self.config, paths)
        approved = paths.inventory.read_bytes()
        (self.prototype / "src/main.ts").write_text("export const changed = true;\n", encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "检测到受控输入漂移"):
            check_artifacts(self.config, paths)
        self.assertEqual(paths.inventory.read_bytes(), approved)

    def test_check_detects_behavioral_permission_drift(self) -> None:
        paths = self._paths("permission-drift")
        controlled_file = self.prototype / "src/main.ts"
        controlled_directory = self.prototype / "src"
        original_mode = stat.S_IMODE(controlled_file.stat().st_mode)
        original_directory_mode = stat.S_IMODE(controlled_directory.stat().st_mode)
        generate_artifacts(self.config, paths)
        controlled_file.chmod(original_mode ^ 0o100)
        try:
            with self.assertRaisesRegex(AuditError, "漂移"):
                check_artifacts(self.config, paths)
        finally:
            controlled_file.chmod(original_mode)
        controlled_directory.chmod(original_directory_mode ^ 0o200)
        try:
            with self.assertRaisesRegex(AuditError, "漂移"):
                check_artifacts(self.config, paths)
        finally:
            controlled_directory.chmod(original_directory_mode)

    def test_check_detects_markdown_body_tampering(self) -> None:
        paths = self._paths("body-tamper")
        generate_artifacts(self.config, paths)
        text = paths.inventory.read_text(encoding="utf-8")
        paths.inventory.write_text(
            text.replace("## 机器可读清单", "手工插入的不可重现结论\n\n## 机器可读清单"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "Markdown 正文漂移"):
            check_artifacts(self.config, paths)

    def test_check_ignores_uncontrolled_node_modules_internal_changes(self) -> None:
        paths = self._paths("node-modules-drift")
        generate_artifacts(self.config, paths)
        (self.prototype / "node_modules/vue/runtime-cache.js").write_text(
            "changed internal install tree", encoding="utf-8"
        )
        check_artifacts(self.config, paths)

    def test_generate_refuses_to_overwrite_existing_evidence(self) -> None:
        paths = self._paths("approved")
        generate_artifacts(self.config, paths)
        with self.assertRaisesRegex(AuditError, "拒绝覆盖"):
            generate_artifacts(self.config, paths)

    def test_non_force_publish_never_overwrites_target_created_after_preflight(self) -> None:
        paths = self._paths("late-target")
        sentinel = "externally-created-after-preflight\n"
        real_transactional_write = audit_module._transactional_write
        injected = False

        def inject_target_before_commit(*args: object, **kwargs: object) -> None:
            nonlocal injected
            if not injected:
                injected = True
                paths.inventory.parent.mkdir(parents=True, exist_ok=True)
                paths.inventory.write_text(sentinel, encoding="utf-8")
            real_transactional_write(*args, **kwargs)

        with patch(
            "audit_production_assets._transactional_write",
            side_effect=inject_target_before_commit,
        ):
            with self.assertRaisesRegex(AuditError, "已有|外部|覆盖|目标"):
                generate_artifacts(self.config, paths)

        self.assertTrue(injected)
        self.assertEqual(paths.inventory.read_text(encoding="utf-8"), sentinel)
        self.assertFalse(paths.schema.exists())
        self.assertFalse(paths.gap.exists())

    def test_publish_rechecks_candidates_created_after_snapshot(self) -> None:
        paths = self._paths("late-candidate")
        late_fingerprint = self.root / "services/late/pom.xml"
        real_transactional_write = audit_module._transactional_write
        injected = False

        def inject_candidate_before_commit(*args: object, **kwargs: object) -> None:
            nonlocal injected
            if not injected:
                injected = True
                late_fingerprint.parent.mkdir(parents=True, exist_ok=True)
                late_fingerprint.write_text("<project />\n", encoding="utf-8")
            real_transactional_write(*args, **kwargs)

        with patch(
            "audit_production_assets._transactional_write",
            side_effect=inject_candidate_before_commit,
        ):
            with self.assertRaisesRegex(AuditError, "受控输入|生产资产|指纹|变化"):
                generate_artifacts(self.config, paths)

        self.assertTrue(injected)
        self.assertFalse(paths.inventory.exists())
        self.assertFalse(paths.schema.exists())
        self.assertFalse(paths.gap.exists())

    def test_outputs_must_be_distinct_and_outside_read_only_inputs(self) -> None:
        duplicate = ArtifactPaths(
            inventory=self.root / "same.md",
            schema=self.root / "same.md",
            gap=self.root / "gap.md",
        )
        with self.assertRaisesRegex(AuditError, "三份输出路径必须互异"):
            generate_artifacts(self.config, duplicate)
        inside_read_only = ArtifactPaths(
            inventory=self.prototype / "audit.md",
            schema=self.root / "schema.json",
            gap=self.root / "gap.md",
        )
        with self.assertRaisesRegex(AuditError, "只读输入"):
            generate_artifacts(self.config, inside_read_only)

    def test_outputs_cannot_have_parent_child_path_relationships(self) -> None:
        output_root = self.root / "out"
        paths = ArtifactPaths(
            inventory=output_root / "bundle",
            schema=output_root / "bundle/schema.json",
            gap=output_root / "gap.md",
        )

        with self.assertRaisesRegex(AuditError, "父子路径"):
            generate_artifacts(self.config, paths)

        self.assertFalse(output_root.exists())

    def test_outputs_cannot_differ_only_by_letter_case(self) -> None:
        output_root = self.root / "case-output"
        paths = ArtifactPaths(
            inventory=output_root / "Inventory.md",
            schema=output_root / "inventory.md",
            gap=output_root / "gap.md",
        )

        with self.assertRaisesRegex(AuditError, "大小写"):
            generate_artifacts(self.config, paths)

        self.assertFalse(output_root.exists())

    def test_outputs_cannot_overlap_any_controlled_input_or_control_file(self) -> None:
        cases = (
            self.root / "backend/audit.md",
            self.root / ".github/workflows/audit.md",
            self.root / "deploy/audit.md",
            self.root / ".audit-production-assets.transaction.json",
        )
        for index, inventory in enumerate(cases):
            with self.subTest(inventory=inventory):
                paths = ArtifactPaths(
                    inventory=inventory,
                    schema=self.root / f"safe-{index}/schema.json",
                    gap=self.root / f"safe-{index}/gap.md",
                )
                with self.assertRaisesRegex(AuditError, "受控输入|控制文件"):
                    generate_artifacts(self.config, paths)

    def test_outputs_cannot_create_new_discovery_fingerprints(self) -> None:
        for index, inventory in enumerate(
            (self.root / "service/pom.xml", self.root / "openapi.yaml")
        ):
            with self.subTest(inventory=inventory):
                paths = ArtifactPaths(
                    inventory=inventory,
                    schema=self.root / f"safe-output-{index}/schema.json",
                    gap=self.root / f"safe-output-{index}/gap.md",
                )

                with self.assertRaisesRegex(AuditError, "受控输入|生产资产|指纹"):
                    generate_artifacts(self.config, paths)
                self.assertFalse(paths.inventory.exists())

    def test_outputs_cannot_create_combined_or_nested_discovery_fingerprints(self) -> None:
        (self.root / "portal/src").mkdir(parents=True)
        (self.root / "portal/src/main.ts").write_text("export {}\n", encoding="utf-8")
        cases = (
            self.root / "portal/package.json",
            self.root / "artifacts/services/payments/pom.xml",
            self.root / "artifacts/apps/portal/vite.config.ts",
        )
        for index, inventory in enumerate(cases):
            with self.subTest(inventory=inventory):
                paths = ArtifactPaths(
                    inventory=inventory,
                    schema=self.root / f"safe-combined-{index}/schema.json",
                    gap=self.root / f"safe-combined-{index}/gap.md",
                )
                with self.assertRaisesRegex(AuditError, "生产资产|指纹"):
                    generate_artifacts(self.config, paths)
                self.assertFalse(paths.inventory.exists())

    def test_outputs_cannot_create_nested_ci_discovery_directories(self) -> None:
        paths = ArtifactPaths(
            inventory=self.root / "services/api/.github/workflows/audit.md",
            schema=self.root / "safe-nested-ci/schema.json",
            gap=self.root / "safe-nested-ci/gap.md",
        )

        with self.assertRaisesRegex(AuditError, "生产资产|指纹"):
            generate_artifacts(self.config, paths)

        self.assertFalse(paths.inventory.exists())

    def test_outputs_cannot_create_nested_contract_fingerprints(self) -> None:
        contract_outputs = (
            self.root / "services/api/openapi.yaml",
            self.root / "services/api/events/asyncapi.yml",
            self.root / "services/api/schemas/customer.schema.json",
        )
        for parent in {path.parent for path in contract_outputs}:
            parent.mkdir(parents=True, exist_ok=True)

        for index, inventory in enumerate(contract_outputs):
            with self.subTest(inventory=inventory):
                paths = ArtifactPaths(
                    inventory=inventory,
                    schema=self.root / f"safe-nested-contract-{index}/inventory.schema.json",
                    gap=self.root / f"safe-nested-contract-{index}/gap.md",
                )
                with self.assertRaisesRegex(AuditError, "生产资产|指纹|受控输入"):
                    generate_artifacts(self.config, paths)
                self.assertFalse(paths.inventory.exists())

    def test_safe_outputs_are_allowed_when_root_frontend_already_exists(self) -> None:
        (self.root / "package.json").write_text('{"name":"root-portal"}\n', encoding="utf-8")
        (self.root / "src").mkdir()
        (self.root / "src/main.ts").write_text("export {}\n", encoding="utf-8")
        paths = self._paths("safe-root-frontend-output")

        generate_artifacts(self.config, paths)
        check_artifacts(self.config, paths)

    def test_unique_atomic_temp_does_not_follow_precreated_fixed_symlink(self) -> None:
        paths = self._paths("atomic")
        paths.inventory.parent.mkdir(parents=True)
        sentinel = self.root / "sentinel.txt"
        sentinel.write_text("KEEP", encoding="utf-8")
        fixed_temp = paths.inventory.with_name(paths.inventory.name + ".tmp")
        fixed_temp.symlink_to(sentinel)
        generate_artifacts(self.config, paths)
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "KEEP")

    def test_three_artifacts_roll_back_as_one_publication_on_commit_failure(self) -> None:
        paths = self._paths("transaction")
        generate_artifacts(self.config, paths)
        approved = {
            path: path.read_bytes()
            for path in (paths.inventory, paths.schema, paths.gap)
        }
        changed_config = AuditConfig(
            workspace_root=self.config.workspace_root,
            prototype_root=self.config.prototype_root,
            architecture_source=self.config.architecture_source,
            pab_source=self.config.pab_source,
            generated_at="2026-07-18T21:00:00+08:00",
        )
        real_replace = os.replace
        publish_count = 0

        def fail_second_publish(source: object, target: object) -> None:
            nonlocal publish_count
            if Path(target) in approved and str(source).endswith(".tmp"):
                publish_count += 1
                if publish_count == 2:
                    raise OSError("simulated second artifact failure")
            real_replace(source, target)

        with patch("audit_production_assets.os.replace", side_effect=fail_second_publish):
            with self.assertRaisesRegex(AuditError, "事务发布失败"):
                generate_artifacts(changed_config, paths, force=True)

        for path, content in approved.items():
            self.assertEqual(path.read_bytes(), content)

    def test_first_publish_uses_0644_and_force_preserves_each_file_mode(self) -> None:
        paths = self._paths("permissions")
        outputs = (paths.inventory, paths.schema, paths.gap)
        generate_artifacts(self.config, paths)
        self.assertEqual(
            [stat.S_IMODE(path.stat().st_mode) for path in outputs],
            [0o644, 0o644, 0o644],
        )
        expected_modes = [0o640, 0o644, 0o664]
        for path, mode in zip(outputs, expected_modes):
            path.chmod(mode)
        later = AuditConfig(
            workspace_root=self.config.workspace_root,
            prototype_root=self.config.prototype_root,
            architecture_source=self.config.architecture_source,
            pab_source=self.config.pab_source,
            generated_at="2026-07-18T21:00:00+08:00",
        )
        generate_artifacts(later, paths, force=True)
        self.assertEqual(
            [stat.S_IMODE(path.stat().st_mode) for path in outputs],
            expected_modes,
        )

    def test_generation_id_is_shared_and_mixed_generation_is_rejected(self) -> None:
        first = self._paths("generation-first")
        second = self._paths("generation-second")
        generate_artifacts(self.config, first)
        later = AuditConfig(
            workspace_root=self.config.workspace_root,
            prototype_root=self.config.prototype_root,
            architecture_source=self.config.architecture_source,
            pab_source=self.config.pab_source,
            generated_at="2026-07-18T21:00:00+08:00",
        )
        generate_artifacts(later, second)

        first_inventory = parse_inventory_report(first.inventory)
        second_inventory = parse_inventory_report(second.inventory)
        first_schema = json.loads(first.schema.read_text(encoding="utf-8"))
        second_schema = json.loads(second.schema.read_text(encoding="utf-8"))
        first_gap = first.gap.read_text(encoding="utf-8")
        second_gap = second.gap.read_text(encoding="utf-8")

        first_id = first_inventory["generationId"]
        second_id = second_inventory["generationId"]
        self.assertRegex(first_id, r"^sha256:[0-9a-f]{64}$")
        self.assertNotEqual(first_id, second_id)
        self.assertEqual(first_schema["x-generationId"], first_id)
        self.assertEqual(second_schema["x-generationId"], second_id)
        self.assertIn(f"<!-- audit-generation-id: {first_id} -->", first_gap)
        self.assertIn(f"<!-- audit-generation-id: {second_id} -->", second_gap)

        approved_gap = first.gap.read_bytes()
        approved_schema = first.schema.read_bytes()
        first.gap.write_bytes(second.gap.read_bytes())
        with self.assertRaisesRegex(AuditError, "generation ID|generationId|代次"):
            validate_artifacts(self.root, first, self.config)
        with self.assertRaisesRegex(AuditError, "generation ID|generationId|代次"):
            check_artifacts(self.config, first)
        first.gap.write_bytes(approved_gap)

        first.schema.write_bytes(second.schema.read_bytes())
        with self.assertRaisesRegex(AuditError, "generation ID|generationId|代次"):
            validate_artifacts(self.root, first, self.config)
        first.schema.write_bytes(approved_schema)

    def test_generation_aborts_when_controlled_input_changes_mid_snapshot(self) -> None:
        paths = self._paths("snapshot-race")
        real_build_gap = audit_module.build_dependency_gap

        def mutate_after_gap(config: AuditConfig, *args: object) -> list[dict[str, str]]:
            rows = real_build_gap(config, *args)
            (self.prototype / "src/main.ts").write_text(
                "export const changedDuringGeneration = true;\n", encoding="utf-8"
            )
            return rows

        with patch(
            "audit_production_assets.build_dependency_gap",
            side_effect=mutate_after_gap,
        ):
            with self.assertRaisesRegex(AuditError, "受控输入.*变化|快照"):
                generate_artifacts(self.config, paths)
        self.assertFalse(paths.inventory.exists())
        self.assertFalse(paths.schema.exists())
        self.assertFalse(paths.gap.exists())

    def test_generation_uses_frozen_snapshot_during_manifest_aba_change(self) -> None:
        paths = self._paths("snapshot-aba")
        package_path = self.prototype / "package.json"
        approved = package_path.read_bytes()
        changed = json.loads(approved.decode("utf-8"))
        changed["name"] = "changed-only-during-inventory-summary"
        changed_bytes = json.dumps(changed, sort_keys=True).encode("utf-8")
        real_summarized_asset = audit_module._summarized_asset
        triggered = False

        def summarize_during_aba(*args: object, **kwargs: object) -> dict[str, object]:
            nonlocal triggered
            if kwargs.get("asset_id") == "prototype.package-manifest" and not triggered:
                triggered = True
                package_path.write_bytes(changed_bytes)
                try:
                    return real_summarized_asset(*args, **kwargs)
                finally:
                    package_path.write_bytes(approved)
            return real_summarized_asset(*args, **kwargs)

        with patch(
            "audit_production_assets._summarized_asset",
            side_effect=summarize_during_aba,
        ):
            generate_artifacts(self.config, paths)

        self.assertTrue(triggered)
        check_artifacts(self.config, paths)

    def test_concurrent_force_generations_are_serialized_by_publication_lock(self) -> None:
        paths = self._paths("concurrent-force")
        generate_artifacts(self.config, paths)
        real_build_gap = audit_module.build_dependency_gap
        active = 0
        maximum_active = 0
        counter_lock = threading.Lock()
        errors: list[BaseException] = []

        def slow_build_gap(config: AuditConfig, *args: object) -> list[dict[str, str]]:
            nonlocal active, maximum_active
            with counter_lock:
                active += 1
                maximum_active = max(maximum_active, active)
            try:
                time.sleep(0.08)
                return real_build_gap(config, *args)
            finally:
                with counter_lock:
                    active -= 1

        def worker(generated_at: str) -> None:
            config = AuditConfig(
                workspace_root=self.config.workspace_root,
                prototype_root=self.config.prototype_root,
                architecture_source=self.config.architecture_source,
                pab_source=self.config.pab_source,
                generated_at=generated_at,
            )
            try:
                generate_artifacts(config, paths, force=True)
            except BaseException as exc:  # pragma: no cover - assertion reports details
                errors.append(exc)

        with patch(
            "audit_production_assets.build_dependency_gap",
            side_effect=slow_build_gap,
        ):
            threads = [
                threading.Thread(target=worker, args=("2026-07-18T21:00:00+08:00",)),
                threading.Thread(target=worker, args=("2026-07-19T21:00:00+08:00",)),
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=5)

        self.assertFalse(errors, errors)
        self.assertEqual(maximum_active, 1)
        validate_artifacts(self.root, paths, self.config)

    def test_partially_overlapping_bundles_share_workspace_publication_lock(self) -> None:
        first = self._paths("overlap-first")
        second_defaults = self._paths("overlap-second")
        second = ArtifactPaths(
            inventory=first.inventory,
            schema=second_defaults.schema,
            gap=second_defaults.gap,
        )
        active = 0
        maximum_active = 0
        counter_lock = threading.Lock()
        errors: list[BaseException] = []

        def worker(paths: ArtifactPaths) -> None:
            nonlocal active, maximum_active
            try:
                with audit_module._artifact_guard(
                    paths, self.root, write=True
                ):
                    with counter_lock:
                        active += 1
                        maximum_active = max(maximum_active, active)
                    try:
                        time.sleep(0.08)
                    finally:
                        with counter_lock:
                            active -= 1
            except BaseException as exc:  # pragma: no cover - assertion reports details
                errors.append(exc)

        threads = [
            threading.Thread(target=worker, args=(first,)),
            threading.Thread(target=worker, args=(second,)),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)

        self.assertFalse(errors, errors)
        self.assertEqual(maximum_active, 1)

    def test_validate_waits_for_writer_and_never_reads_partial_bundle(self) -> None:
        paths = self._paths("reader-writer-lock")
        generate_artifacts(self.config, paths)
        real_build_gap = audit_module.build_dependency_gap
        writer_entered = threading.Event()
        release_writer = threading.Event()
        reader_done = threading.Event()
        errors: list[BaseException] = []

        def pause_first_gap(config: AuditConfig, *args: object) -> list[dict[str, str]]:
            if not writer_entered.is_set():
                writer_entered.set()
                if not release_writer.wait(timeout=5):
                    raise AssertionError("writer release timed out")
            return real_build_gap(config, *args)

        later = AuditConfig(
            workspace_root=self.config.workspace_root,
            prototype_root=self.config.prototype_root,
            architecture_source=self.config.architecture_source,
            pab_source=self.config.pab_source,
            generated_at="2026-07-18T21:00:00+08:00",
        )

        def writer() -> None:
            try:
                generate_artifacts(later, paths, force=True)
            except BaseException as exc:  # pragma: no cover - assertion reports details
                errors.append(exc)

        def reader() -> None:
            try:
                validate_artifacts(self.root, paths, self.config)
            except BaseException as exc:  # pragma: no cover - assertion reports details
                errors.append(exc)
            finally:
                reader_done.set()

        with patch(
            "audit_production_assets.build_dependency_gap",
            side_effect=pause_first_gap,
        ):
            writer_thread = threading.Thread(target=writer)
            writer_thread.start()
            self.assertTrue(writer_entered.wait(timeout=2))
            reader_thread = threading.Thread(target=reader)
            reader_thread.start()
            time.sleep(0.08)
            self.assertFalse(reader_done.is_set())
            release_writer.set()
            writer_thread.join(timeout=5)
            reader_thread.join(timeout=5)

        self.assertFalse(errors, errors)
        self.assertTrue(reader_done.is_set())

    def test_recoverable_interruption_restores_previous_generation(self) -> None:
        paths = self._paths("recoverable-interruption")
        generate_artifacts(self.config, paths)
        approved = {
            path: path.read_bytes()
            for path in (paths.inventory, paths.schema, paths.gap)
        }
        changed_config = AuditConfig(
            workspace_root=self.config.workspace_root,
            prototype_root=self.config.prototype_root,
            architecture_source=self.config.architecture_source,
            pab_source=self.config.pab_source,
            generated_at="2026-07-18T21:00:00+08:00",
        )
        real_replace = os.replace
        publish_count = 0

        def interrupt_second_publish(source: object, target: object) -> None:
            nonlocal publish_count
            if Path(target) in approved and str(source).endswith(".tmp"):
                publish_count += 1
                if publish_count == 2:
                    raise SystemExit("simulated recoverable interruption")
            real_replace(source, target)

        with patch(
            "audit_production_assets.os.replace",
            side_effect=interrupt_second_publish,
        ):
            with self.assertRaises(SystemExit):
                generate_artifacts(changed_config, paths, force=True)

        validate_artifacts(self.root, paths, self.config)
        for path, content in approved.items():
            self.assertEqual(path.read_bytes(), content)

    def test_recovery_rejects_unmanaged_staged_and_backup_paths(self) -> None:
        target = self.root / "evidence/inventory.md"
        sentinel = self.root / "unrelated.txt"
        sentinel.write_text("KEEP", encoding="utf-8")
        journal = self.root / ".audit-production-assets.transaction.json"
        journal.write_text(
            json.dumps(
                {
                    "version": 1,
                    "generationId": "test",
                    "entries": [
                        {
                            "target": str(target),
                            "staged": str(sentinel),
                            "backup": None,
                            "existed": False,
                            "oldMode": None,
                            "oldDigest": None,
                            "newDigest": "sha256:" + "a" * 64,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(AuditError, "临时路径|事务日志"):
            audit_module._recover_transaction(journal, {target})
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "KEEP")

    def test_committed_generation_survives_final_directory_fsync_failure(self) -> None:
        paths = self._paths("fsync-commit")
        real_fsync_directory = audit_module._fsync_directory

        def fail_only_after_journal_unlink(path: Path) -> None:
            journal = self.root / ".audit-production-assets.transaction.json"
            if not journal.exists() and all(
                output.exists() for output in (paths.inventory, paths.schema, paths.gap)
            ):
                raise OSError("simulated final cleanup fsync failure")
            real_fsync_directory(path)

        with patch(
            "audit_production_assets._fsync_directory",
            side_effect=fail_only_after_journal_unlink,
        ):
            generate_artifacts(self.config, paths)
        validate_artifacts(self.root, paths, self.config)

    def test_execution_evidence_records_custom_parameters_and_truthful_steps(self) -> None:
        paths = self._paths("custom-evidence")
        generate_artifacts(self.config, paths)
        evidence = parse_inventory_report(paths.inventory)["executionEvidence"]
        self.assertIn("--prototype-root", evidence["generateCommand"])
        self.assertIn("docs/input/原型/frontend", evidence["generateCommand"])
        self.assertIn("custom-evidence/inventory.md", evidence["generateCommand"])
        self.assertIn("--generated-at", evidence["generateCommand"])
        steps = {step["name"]: step for step in evidence["steps"]}
        self.assertEqual(steps["generate"]["status"], "passed")
        self.assertEqual(steps["generate"]["exitCode"], 0)
        self.assertEqual(steps["schema-validation"]["status"], "passed")
        self.assertEqual(steps["gap-validation"]["status"], "passed")
        self.assertEqual(evidence["acceptanceEvidenceLocation"], "Story Dev Agent Record")

    def test_recorded_replay_command_is_self_contained_outside_workspace(self) -> None:
        paths = self._paths("absolute-replay")
        generate_artifacts(self.config, paths)
        evidence = parse_inventory_report(paths.inventory)["executionEvidence"]
        command = shlex.split(evidence["replayCommand"])
        self.assertTrue(Path(command[1]).is_absolute())
        workspace_value = command[command.index("--workspace-root") + 1]
        self.assertTrue(Path(workspace_value).is_absolute())

        completed = subprocess.run(
            command,
            cwd=self.root.parent,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_inventory_prose_uses_parameterized_path_and_snapshot_stack_fact(self) -> None:
        custom_prototype = self.root / "prototype/custom-ui"
        custom_prototype.parent.mkdir(parents=True)
        self.prototype.rename(custom_prototype)
        config = AuditConfig(
            workspace_root=self.root,
            prototype_root=custom_prototype,
            architecture_source=self.architecture,
            pab_source=self.pab,
            generated_at=self.config.generated_at,
        )
        inventory = build_inventory(config)
        report = render_inventory_report(inventory)
        self.assertIn("`prototype/custom-ui`", report)
        manifest = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.package-manifest"
        )
        self.assertIn("Vite 5 / Vue 3 原型", manifest["dispositionReason"])
        self.assertIn("Vite 5 / Vue 3 原型", report)

    def test_inventory_report_explains_source_count_exclusions(self) -> None:
        (self.prototype / "src/.DS_Store").write_bytes(b"ignored metadata")
        inventory = build_inventory(self.config)
        source = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.source"
        )
        report = render_inventory_report(inventory)
        self.assertIn(
            f"原型源码受控文件数：{source['metrics']['regularFileCount']}",
            report,
        )
        self.assertIn(".DS_Store", report)
        self.assertIn("原始目录枚举", report)

    def test_inventory_stack_fact_tracks_changed_manifest_and_lock_versions(self) -> None:
        package_path = self.prototype / "package.json"
        lock_path = self.prototype / "package-lock.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        package["devDependencies"]["vite"] = "^6.0.0"
        package["dependencies"]["vue"] = "^4.0.0"
        lock["packages"][""]["devDependencies"]["vite"] = "^6.0.0"
        lock["packages"][""]["dependencies"]["vue"] = "^4.0.0"
        lock["packages"]["node_modules/vite"]["version"] = "6.0.1"
        lock["packages"]["node_modules/vue"]["version"] = "4.0.1"
        package_path.write_text(json.dumps(package), encoding="utf-8")
        lock_path.write_text(json.dumps(lock), encoding="utf-8")

        inventory = build_inventory(self.config)
        manifest = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.package-manifest"
        )
        report = render_inventory_report(inventory)
        self.assertIn("Vite 6 / Vue 4 原型", manifest["dispositionReason"])
        self.assertIn("Vite 6 / Vue 4 原型", report)
        self.assertNotIn("Vite 5 / Vue 3 原型", report)

    def test_machine_evidence_excludes_acceptance_results_and_check_rebuilds_generation_claims(self) -> None:
        paths = self._paths("scoped-evidence")
        generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        evidence = inventory["executionEvidence"]

        self.assertEqual(
            evidence["acceptanceEvidenceLocation"],
            "Story Dev Agent Record",
        )
        self.assertEqual(
            [step["name"] for step in evidence["steps"]],
            ["generate", "schema-validation", "gap-validation"],
        )
        self.assertNotIn("checkCommand", evidence)

        evidence["result"] = "failed"
        evidence["steps"][0]["status"] = "failed"
        evidence["steps"][0]["exitCode"] = 2
        paths.inventory.write_text(render_inventory_report(inventory), encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "执行证据|Markdown 正文漂移"):
            check_artifacts(self.config, paths)

    def test_validate_mode_rejects_duplicate_id_and_broken_link(self) -> None:
        paths = self._paths("tampered")
        generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        duplicate = dict(inventory["assets"][0])
        duplicate["evidenceLinks"] = ["does-not-exist"]
        inventory["assets"].append(duplicate)
        from audit_production_assets import render_inventory_report

        paths.inventory.write_text(render_inventory_report(inventory), encoding="utf-8")
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "validate",
                    "--workspace-root",
                    str(self.root),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                ]
            )
        self.assertNotEqual(exit_code, 0)
        self.assertIn("重复 assetId", stderr.getvalue())
        self.assertIn("证据路径不可解析", stderr.getvalue())

    def test_machine_json_blocks_must_be_unique(self) -> None:
        paths = self._paths("duplicate-machine-block")
        generate_artifacts(self.config, paths)
        inventory_text = paths.inventory.read_text(encoding="utf-8")
        start = inventory_text.index("<!-- asset-inventory-json:start -->")
        duplicate = inventory_text[start:]
        paths.inventory.write_text(inventory_text + duplicate, encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "必须且只能出现一次|区块"):
            parse_inventory_report(paths.inventory)

        paths.inventory.write_text(inventory_text, encoding="utf-8")
        generate_artifacts(self.config, paths, force=True)
        gap_text = paths.gap.read_text(encoding="utf-8")
        start = gap_text.index("<!-- dependency-gap-json:start -->")
        duplicate = gap_text[start:]
        paths.gap.write_text(gap_text + duplicate, encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "必须且只能出现一次|区块"):
            parse_gap_report(paths.gap)

    def test_validate_requires_complete_assets_and_dependency_coverage(self) -> None:
        paths = self._paths("coverage")
        generate_artifacts(self.config, paths)
        approved_inventory = paths.inventory.read_text(encoding="utf-8")
        approved_gap = paths.gap.read_text(encoding="utf-8")

        inventory = parse_inventory_report(paths.inventory)
        inventory["assets"] = []
        inventory["unresolvedItems"] = []
        inventory["executionEvidence"]["unresolvedCount"] = 0
        start = "<!-- asset-inventory-json:start -->"
        end = "<!-- asset-inventory-json:end -->"
        prefix, remainder = approved_inventory.split(start, 1)
        _, suffix = remainder.split(end, 1)
        paths.inventory.write_text(
            prefix
            + start
            + "\n```json\n"
            + json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2)
            + "\n```\n"
            + end
            + suffix,
            encoding="utf-8",
        )
        exit_code, stderr = self._validate_exit(paths)
        self.assertEqual(exit_code, 2)
        self.assertIn("assets", stderr)
        paths.inventory.write_text(approved_inventory, encoding="utf-8")

        paths.gap.write_text(render_gap_report([], self.config), encoding="utf-8")
        exit_code, stderr = self._validate_exit(paths)
        self.assertEqual(exit_code, 2)
        self.assertIn("依赖差异", stderr)

        original_rows = build_dependency_gap(self.config)
        for dependency in ("Vite", "OpenAPI", "axios", "prettier"):
            with self.subTest(dependency=dependency):
                rows = [row for row in original_rows if row["dependency"] != dependency]
                paths.gap.write_text(render_gap_report(rows, self.config), encoding="utf-8")
                exit_code, stderr = self._validate_exit(paths)
                self.assertEqual(exit_code, 2)
                self.assertIn("依赖覆盖", stderr)
        paths.gap.write_text(approved_gap, encoding="utf-8")

    def test_validate_rejects_tampered_asset_digest_against_current_snapshot(self) -> None:
        paths = self._paths("tampered-digest")
        generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        source = next(
            item for item in inventory["assets"] if item["assetId"] == "prototype.source"
        )
        source["versionOrDigest"] = "sha256:" + "f" * 64
        paths.inventory.write_text(render_inventory_report(inventory), encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "当前受控输入|资产|摘要"):
            validate_artifacts(self.root, paths, self.config)

    def test_snapshot_only_metrics_are_bound_by_cross_artifact_receipt(self) -> None:
        paths = self._paths("tampered-snapshot-metrics")
        generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        node_modules = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        node_modules["metrics"]["topLevelEntryCount"] += 1
        node_modules["metrics"]["topLevelRegularFileByteCount"] += 7
        paths.inventory.write_text(
            render_inventory_report(inventory), encoding="utf-8"
        )

        with self.assertRaisesRegex(AuditError, "snapshotOnly|指标|回执"):
            validate_artifacts(self.root, paths, self.config)
        with self.assertRaisesRegex(AuditError, "snapshotOnly|指标|回执"):
            check_artifacts(self.config, paths)

    def test_synchronized_snapshot_metric_and_receipt_tampering_is_rejected(self) -> None:
        paths = self._paths("tampered-snapshot-metrics-and-receipt")
        generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        node_modules = next(
            item
            for item in inventory["assets"]
            if item["assetId"] == "prototype.node-modules"
        )
        node_modules["metrics"]["topLevelEntryCount"] += 1
        node_modules["metrics"]["topLevelRegularFileByteCount"] += 7
        paths.inventory.write_text(
            render_inventory_report(inventory), encoding="utf-8"
        )
        paths.gap.write_text(
            render_gap_report(
                parse_gap_report(paths.gap),
                self.config,
                inventory["generationId"],
                audit_module._snapshot_metrics_receipt(inventory),
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(AuditError, "snapshotOnly|指标|受控输入漂移"):
            validate_artifacts(self.root, paths, self.config)
        with self.assertRaisesRegex(AuditError, "snapshotOnly|指标|受控输入漂移"):
            check_artifacts(self.config, paths)

    def test_validate_cli_handles_invalid_utf8_without_traceback(self) -> None:
        paths = self._paths("invalid-utf8")
        generate_artifacts(self.config, paths)
        approved_inventory = paths.inventory.read_bytes()
        approved_gap = paths.gap.read_bytes()

        for target, approved in (
            (paths.inventory, approved_inventory),
            (paths.gap, approved_gap),
        ):
            with self.subTest(target=target.name):
                try:
                    target.write_bytes(b"\xff\xfe\xfa")
                    exit_code, stderr = self._validate_exit(paths)
                    self.assertEqual(exit_code, 2)
                    self.assertIn("文本编码无效", stderr)
                    self.assertNotIn("Traceback", stderr)
                finally:
                    target.write_bytes(approved)

    def test_generate_cli_wraps_oversized_json_integer_without_traceback(self) -> None:
        paths = self._paths("oversized-json-integer")
        package_path = self.prototype / "package.json"
        package = json.loads(package_path.read_text(encoding="utf-8"))
        encoded = json.dumps(package, ensure_ascii=False)
        package_path.write_text(
            encoded[:-1] + ',"oversized":' + ("9" * 5000) + "}",
            encoding="utf-8",
        )

        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "generate",
                    "--workspace-root",
                    str(self.root),
                    "--prototype-root",
                    str(self.prototype),
                    "--architecture-source",
                    str(self.architecture),
                    "--pab-source",
                    str(self.pab),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                    "--generated-at",
                    self.config.generated_at,
                ]
            )
        self.assertEqual(exit_code, 2)
        self.assertIn("JSON", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())

    def test_unreadable_required_input_returns_nonzero(self) -> None:
        paths = self._paths("unreadable")
        package = self.prototype / "package.json"
        package.chmod(0)
        try:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = main(
                    [
                        "generate",
                        "--workspace-root",
                        str(self.root),
                        "--prototype-root",
                        str(self.prototype),
                        "--architecture-source",
                        str(self.architecture),
                        "--pab-source",
                        str(self.pab),
                        "--inventory-output",
                        str(paths.inventory),
                        "--schema-output",
                        str(paths.schema),
                        "--gap-output",
                        str(paths.gap),
                        "--generated-at",
                        "2026-07-17T21:00:00+08:00",
                    ]
                )
            self.assertNotEqual(exit_code, 0)
            self.assertIn("无读取权限", stderr.getvalue())
            self.assertTrue(paths.inventory.exists())
            inventory = parse_inventory_report(paths.inventory)
            package_asset = next(
                item
                for item in inventory["assets"]
                if item["assetId"] == "prototype.package-manifest"
            )
            self.assertEqual(package_asset["classification"], "unknown-blocked")
            self.assertEqual(package_asset["readStatus"], "unreadable")
            self.assertEqual(inventory["executionEvidence"]["result"], "failed")
        finally:
            package.chmod(0o644)

    def test_required_semantic_files_cannot_be_replaced_by_directories(self) -> None:
        targets = (
            (self.prototype / "package.json", "prototype.package-manifest"),
            (self.prototype / "package-lock.json", "prototype.lockfile"),
            (self.architecture, "planning.architecture-spine"),
            (self.pab, "planning.pab-1.0.0"),
        )
        for index, (target, asset_id) in enumerate(targets):
            with self.subTest(target=target.name):
                original = target.read_bytes()
                target.unlink()
                target.mkdir()
                paths = self._paths(f"semantic-directory-{index}")
                try:
                    with self.assertRaises(AuditError):
                        generate_artifacts(self.config, paths)
                    inventory = parse_inventory_report(paths.inventory)
                    asset = next(
                        item
                        for item in inventory["assets"]
                        if item["assetId"] == asset_id
                    )
                    self.assertEqual(asset["readStatus"], "unreadable")
                    self.assertEqual(asset["classification"], "unknown-blocked")
                finally:
                    target.rmdir()
                    target.write_bytes(original)

    def test_unreadable_controlled_source_returns_failed_handoff(self) -> None:
        paths = self._paths("unreadable-source")
        source = self.prototype / "src"
        source.chmod(0)
        try:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = main(
                    [
                        "generate",
                        "--workspace-root",
                        str(self.root),
                        "--prototype-root",
                        str(self.prototype),
                        "--architecture-source",
                        str(self.architecture),
                        "--pab-source",
                        str(self.pab),
                        "--inventory-output",
                        str(paths.inventory),
                        "--schema-output",
                        str(paths.schema),
                        "--gap-output",
                        str(paths.gap),
                        "--generated-at",
                        "2026-07-17T21:00:00+08:00",
                    ]
                )
            self.assertEqual(exit_code, 2)
            inventory = parse_inventory_report(paths.inventory)
            source_asset = next(
                item for item in inventory["assets"] if item["assetId"] == "prototype.source"
            )
            self.assertEqual(source_asset["readStatus"], "unreadable")
            self.assertEqual(source_asset["classification"], "unknown-blocked")
            self.assertEqual(inventory["executionEvidence"]["result"], "failed")
        finally:
            source.chmod(0o755)

    def test_unreadable_existing_prototype_config_returns_failed_handoff(self) -> None:
        paths = self._paths("unreadable-config")
        config_file = self.prototype / "eslint.config.js"
        config_file.write_text("export default []\n", encoding="utf-8")
        config_file.chmod(0)
        try:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                exit_code = main(
                    [
                        "generate",
                        "--workspace-root", str(self.root),
                        "--prototype-root", str(self.prototype),
                        "--architecture-source", str(self.architecture),
                        "--pab-source", str(self.pab),
                        "--inventory-output", str(paths.inventory),
                        "--schema-output", str(paths.schema),
                        "--gap-output", str(paths.gap),
                        "--generated-at", self.config.generated_at,
                    ]
                )
            self.assertEqual(exit_code, 2)
            error_text = stderr.getvalue()
            self.assertIn("prototype.config", error_text)
            self.assertNotIn("必需受控输入不可读：\n", error_text)
            inventory = parse_inventory_report(paths.inventory)
            asset = next(
                item for item in inventory["assets"] if item["assetId"] == "prototype.config"
            )
            self.assertEqual(asset["readStatus"], "unreadable")
            self.assertEqual(inventory["executionEvidence"]["result"], "failed")
        finally:
            config_file.chmod(0o644)

    def test_validate_rejects_tampered_failure_step_attribution(self) -> None:
        paths = self._paths("tampered-failure-steps")
        config_file = self.prototype / "eslint.config.js"
        config_file.write_text("export default []\n", encoding="utf-8")
        config_file.chmod(0)
        try:
            with self.assertRaises(AuditError):
                generate_artifacts(self.config, paths)
            inventory = parse_inventory_report(paths.inventory)
            steps = {
                step["name"]: step for step in inventory["executionEvidence"]["steps"]
            }
            steps["generate"]["status"] = "passed"
            steps["generate"]["exitCode"] = 0
            steps["gap-validation"]["status"] = "failed"
            steps["gap-validation"]["exitCode"] = 2
            paths.inventory.write_text(
                render_inventory_report(inventory), encoding="utf-8"
            )

            with self.assertRaisesRegex(AuditError, "执行|受控输入|机器清单"):
                validate_artifacts(self.root, paths, self.config)
        finally:
            config_file.chmod(0o644)

    def test_dist_is_frozen_in_same_snapshot_as_other_controlled_inputs(self) -> None:
        paths = self._paths("dist-aba")
        dist_file = self.prototype / "dist/index.html"
        approved = dist_file.read_bytes()
        real_summarized_asset = audit_module._summarized_asset
        triggered = False

        def summarize_during_aba(*args: object, **kwargs: object) -> dict[str, object]:
            nonlocal triggered
            if kwargs.get("asset_id") == "prototype.dist" and not triggered:
                triggered = True
                dist_file.write_bytes(b"<html>changed only during render</html>\n")
                try:
                    return real_summarized_asset(*args, **kwargs)
                finally:
                    dist_file.write_bytes(approved)
            return real_summarized_asset(*args, **kwargs)

        with patch("audit_production_assets._summarized_asset", side_effect=summarize_during_aba):
            generate_artifacts(self.config, paths)
        self.assertTrue(triggered)
        check_artifacts(self.config, paths)

    def test_semantically_invalid_pab_oracle_blocks_oracle_assets(self) -> None:
        paths = self._paths("invalid-pab")
        self.pab.write_text(
            self.pab.read_text(encoding="utf-8").replace("Vite 8.1.5", "Vite 8.1.4"),
            encoding="utf-8",
        )
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "generate",
                    "--workspace-root",
                    str(self.root),
                    "--prototype-root",
                    str(self.prototype),
                    "--architecture-source",
                    str(self.architecture),
                    "--pab-source",
                    str(self.pab),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                    "--generated-at",
                    "2026-07-17T21:00:00+08:00",
                ]
            )
        self.assertEqual(exit_code, 2)
        inventory = parse_inventory_report(paths.inventory)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        for asset_id in ("planning.architecture-spine", "planning.pab-1.0.0"):
            self.assertEqual(assets[asset_id]["classification"], "unknown-blocked")
            self.assertEqual(assets[asset_id]["readStatus"], "unreadable")
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")

    def test_multiple_dependency_input_failures_are_aggregated_and_replayable(self) -> None:
        paths = self._paths("multiple-input-failures")
        (self.prototype / "package.json").write_text("{", encoding="utf-8")
        self.pab.write_text(
            self.pab.read_text(encoding="utf-8").replace(
                "Vite 8.1.5", "Vite 8.1.4"
            ),
            encoding="utf-8",
        )
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "generate",
                    "--workspace-root", str(self.root),
                    "--prototype-root", str(self.prototype),
                    "--architecture-source", str(self.architecture),
                    "--pab-source", str(self.pab),
                    "--inventory-output", str(paths.inventory),
                    "--schema-output", str(paths.schema),
                    "--gap-output", str(paths.gap),
                    "--generated-at", self.config.generated_at,
                ]
            )

        self.assertEqual(exit_code, 2)
        error_text = stderr.getvalue()
        self.assertIn("JSON 解析失败", error_text)
        self.assertIn("PAB oracle 语义校验失败", error_text)
        inventory = parse_inventory_report(paths.inventory)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        for asset_id in ("planning.architecture-spine", "planning.pab-1.0.0"):
            with self.subTest(asset_id=asset_id):
                self.assertEqual(assets[asset_id]["owner"], "UNKNOWN")
                self.assertEqual(assets[asset_id]["versionOrDigest"], "UNAVAILABLE")
                self.assertEqual(assets[asset_id]["classification"], "unknown-blocked")
                self.assertEqual(assets[asset_id]["readStatus"], "unreadable")
        validate_artifacts(self.root, paths, self.config)
        check_artifacts(self.config, paths)

    def test_validate_invalid_pab_requires_planning_asset_downgrade(self) -> None:
        paths = self._paths("invalid-pab-tamper")
        self.pab.write_text(
            self.pab.read_text(encoding="utf-8").replace("Vite 8.1.5", "Vite 8.1.4"),
            encoding="utf-8",
        )
        with self.assertRaises(AuditError):
            generate_artifacts(self.config, paths)
        inventory = parse_inventory_report(paths.inventory)
        planning_ids = {"planning.architecture-spine", "planning.pab-1.0.0"}
        for asset in inventory["assets"]:
            if asset["assetId"] not in planning_ids:
                continue
            asset["owner"] = "伪造的受控 owner"
            asset["versionOrDigest"] = "sha256:" + "f" * 64
            asset["classification"] = "reuse-as-is"
            asset["readStatus"] = "readable"
            asset["present"] = True
        inventory["unresolvedItems"] = [
            item
            for item in inventory["unresolvedItems"]
            if item["assetId"] not in planning_ids
        ]
        inventory["executionEvidence"]["unresolvedCount"] = len(
            inventory["unresolvedItems"]
        )
        paths.inventory.write_text(
            render_inventory_report(inventory), encoding="utf-8"
        )

        with self.assertRaisesRegex(AuditError, "PAB.*规划资产|unknown-blocked"):
            validate_artifacts(self.root, paths, self.config)

    def test_missing_pab_source_publishes_consistent_unknown_blocked_handoff(self) -> None:
        paths = self._paths("missing-pab-source")
        self.pab.unlink()
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            exit_code = main(
                [
                    "generate",
                    "--workspace-root",
                    str(self.root),
                    "--prototype-root",
                    str(self.prototype),
                    "--architecture-source",
                    str(self.architecture),
                    "--pab-source",
                    str(self.pab),
                    "--inventory-output",
                    str(paths.inventory),
                    "--schema-output",
                    str(paths.schema),
                    "--gap-output",
                    str(paths.gap),
                    "--generated-at",
                    "2026-07-17T21:00:00+08:00",
                ]
            )
        self.assertEqual(exit_code, 2)
        self.assertIn("unknown-blocked", stderr.getvalue())
        self.assertTrue(all(path.exists() for path in (paths.inventory, paths.schema, paths.gap)))
        inventory = parse_inventory_report(paths.inventory)
        assets = {item["assetId"]: item for item in inventory["assets"]}
        missing = assets["planning.pab-1.0.0"]
        self.assertFalse(missing["present"])
        self.assertEqual(missing["readStatus"], "missing")
        self.assertEqual(missing["classification"], "unknown-blocked")
        self.assertEqual(
            missing["evidenceLinks"], ["audit://absence/planning.pab-1.0.0"]
        )
        self.assertEqual(inventory["executionEvidence"]["result"], "failed")
        check_artifacts(self.config, paths)


if __name__ == "__main__":
    unittest.main()
