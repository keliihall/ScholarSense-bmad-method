from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_production_pollution import PRODUCTION_ROOTS, scan  # noqa: E402
from normalized_manifest import build_manifest  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class DeliveryQualityTest(unittest.TestCase):
    def test_delivery_entrypoints_exist_and_are_executable(self) -> None:
        self.assertTrue((PROJECT_ROOT / "README.md").is_file())
        for relative in ("scripts/bootstrap.sh", "scripts/verify.sh"):
            path = PROJECT_ROOT / relative
            self.assertTrue(path.is_file(), f"missing {relative}")
            self.assertTrue(path.stat().st_mode & 0o111, f"not executable: {relative}")

    def test_frontend_replay_copies_every_production_root_it_scans(self) -> None:
        verifier = (PROJECT_ROOT / "scripts/verify_frontend.sh").read_text(encoding="utf-8")
        for relative in PRODUCTION_ROOTS:
            if relative == "backend/src/main":
                expected = 'cp -R "$ROOT_DIR/backend/src/main" "$replay_root/backend/src/main"'
            elif relative == "frontend":
                expected = 'cp -R "$ROOT_DIR/frontend/." "$replay_root/frontend/"'
            else:
                expected = f'cp -R "$ROOT_DIR/{relative}" "$replay_root/{relative}"'
            self.assertIn(expected, verifier, f"isolated replay omits production root: {relative}")

    def test_production_tree_is_clean(self) -> None:
        self.assertEqual([], scan(PROJECT_ROOT))

    def test_supply_chain_configuration_is_in_production_pollution_scope(self) -> None:
        self.assertIn(".github", PRODUCTION_ROOTS)
        self.assertIn("release", PRODUCTION_ROOTS)
        cases = (
            (".github/workflows/leak.yml", "token: do-not-commit\n", "RAW_CREDENTIAL_LITERAL"),
            ("release/policies/local.json", '{"endpoint":"http://localhost:8080"}\n', "LOCAL_ENDPOINT_LITERAL"),
        )
        for relative, content, reason in cases:
            with self.subTest(relative=relative), self.fixture() as root:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                self.assertTrue(any(item.startswith(reason) for item in scan(root)), scan(root))

    def test_normalized_manifest_is_deterministic_and_excludes_generated_files(self) -> None:
        first = build_manifest(PROJECT_ROOT)
        second = build_manifest(PROJECT_ROOT)
        self.assertEqual(first, second)
        self.assertFalse(any("target/" in item["path"] for item in first["files"]))
        self.assertFalse(any("__pycache__" in item["path"] for item in first["files"]))

    def test_normalized_manifest_covers_release_supply_chain_inputs(self) -> None:
        paths = {item["path"] for item in build_manifest(PROJECT_ROOT)["files"]}
        required = {
            ".github/CODEOWNERS",
            ".github/workflows/platform-probe.yml",
            "backend/mvnw",
            "backend/.mvn/wrapper/maven-wrapper.properties",
            "contracts/release/ci-supply-chain-baseline-1.0.0.json",
            "docs/architecture/adr/ci-supply-chain-baseline-cisb-1.0.0.md",
            "release/README.md",
        }
        self.assertEqual(set(), required - paths)

    def test_normalized_manifest_includes_frontend_sources_config_and_lock_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            included = {
                "frontend/src/App.vue": "<template>\r\n  <main />\r\n</template>\r\n",
                "frontend/src/theme.css": ":root { color: black; }\r\n",
                "frontend/index.html": "<!doctype html>\r\n",
                "frontend/vite.config.ts": "export default {};\r\n",
                "frontend/scripts/check.mjs": "export {};\r\n",
                "frontend/package-lock.json": '{"lockfileVersion":3}\r\n',
                "frontend/.npmrc": "registry=https://registry.npmjs.org/\r\n",
            }
            for relative, content in included.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8", newline="")
            for generated in (
                "frontend/node_modules/pkg/index.js",
                "frontend/dist/index.html",
                "frontend/coverage/report.css",
                "frontend/playwright-report/index.html",
                "frontend/test-results/result.json",
            ):
                path = root / generated
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("generated\n", encoding="utf-8")
            generated_target = root / "generated-bin-target"
            generated_target.write_text("generated\n", encoding="utf-8")
            generated_link = root / "frontend/node_modules/.bin/tool"
            generated_link.parent.mkdir(parents=True, exist_ok=True)
            generated_link.symlink_to(generated_target)

            manifest = build_manifest(root)
            actual_paths = {item["path"] for item in manifest["files"]}

            self.assertEqual(set(included), actual_paths)
            for item in manifest["files"]:
                source = included[item["path"]].replace("\r\n", "\n").encode()
                self.assertEqual(hashlib.sha256(source).hexdigest(), item["sha256"])

    def test_pollution_fixtures_are_rejected(self) -> None:
        cases = {
            "GENERATED_OR_PROTOTYPE_PATH": ("frontend/src/mock/user.ts", "export {};\n"),
            "LOCAL_ENDPOINT_LITERAL": ("frontend/src/app/config/bad.ts", "export const x='http://127.0.0.1:8080';\n"),
            "LOCAL_MACHINE_PATH_LITERAL": ("deploy/base/bad.json", '{"path":"/Users/example/private"}\n'),
            "RAW_CREDENTIAL_LITERAL": ("deploy/base/bad.env.example", "password=changeme\n"),
            "PREMATURE_PRODUCTION_MIGRATION": (
                "backend/src/main/resources/db/migration/identity-access/V000001__identity-access__bad.sql",
                "-- forbidden in this Story\n",
            ),
        }
        for reason, (relative, content) in cases.items():
            with self.subTest(reason=reason), self.fixture() as root:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                actual = scan(root)
                self.assertTrue(any(value.startswith(reason) for value in actual), actual)

    def test_hidden_environment_private_key_and_json_credentials_are_rejected(self) -> None:
        cases = {
            "hidden env": ("deploy/base/.env.prod", "PASSWORD=do-not-commit\n", "RAW_CREDENTIAL_LITERAL"),
            "key file": (
                "deploy/base/service.key",
                "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\n",
                "PRIVATE_KEY_MATERIAL",
            ),
            "pem file": (
                "deploy/base/service.pem",
                "-----BEGIN RSA PRIVATE KEY-----\nsecret\n-----END RSA PRIVATE KEY-----\n",
                "PRIVATE_KEY_MATERIAL",
            ),
            "json credential": (
                "deploy/base/credential.json",
                '{"password": "do-not-commit"}\n',
                "RAW_CREDENTIAL_LITERAL",
            ),
        }
        for label, (relative, content, reason) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                actual = scan(root)
                self.assertTrue(any(value.startswith(reason) for value in actual), actual)

    def test_approved_playwright_loopback_is_narrow_and_npmrc_secrets_are_rejected(self) -> None:
        with self.fixture() as root:
            config = root / "frontend/playwright.config.ts"
            config.write_text(
                "export const baseURL = 'http://127.0.0.1:4173/scholarsense/';\n",
                encoding="utf-8",
            )
            self.assertFalse(any(value.startswith("LOCAL_ENDPOINT_LITERAL") for value in scan(root)), scan(root))
            config.write_text("export const baseURL = 'http://127.0.0.1:4174/';\n", encoding="utf-8")
            self.assertTrue(any(value.startswith("LOCAL_ENDPOINT_LITERAL") for value in scan(root)), scan(root))

        with self.fixture() as root:
            npmrc = root / "frontend/.npmrc"
            npmrc.write_text("//registry.npmjs.org/:_authToken=secret\n", encoding="utf-8")
            self.assertTrue(any(value.startswith("RAW_CREDENTIAL_LITERAL") for value in scan(root)), scan(root))

    def test_envrc_case_insensitive_extensions_and_escaped_json_keys_are_rejected(self) -> None:
        cases = {
            "envrc": ("deploy/base/.envrc", "TOKEN=do-not-commit\n"),
            "uppercase extension": ("deploy/base/credential.JSON", '{"password": "do-not-commit"}\n'),
            "escaped json key": ("deploy/base/escaped.json", '{"pass\\u0077ord": "do-not-commit"}\n'),
        }
        for label, (relative, content) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                actual = scan(root)
                self.assertTrue(any(value.startswith("RAW_CREDENTIAL_LITERAL") for value in actual), actual)

    def test_extended_frontend_text_extensions_are_scanned_fail_closed(self) -> None:
        cases = (
            ("mjs endpoint", "frontend/scripts/bad.mjs", "const api='http://127.0.0.1:9999';\n", "LOCAL_ENDPOINT_LITERAL"),
            ("cjs credential", "frontend/scripts/bad.cjs", "token='do-not-commit';\n", "RAW_CREDENTIAL_LITERAL"),
            ("mts cache", "frontend/src/bad.mts", "localStorage.setItem('business', 'value');\n", "PERSISTENT_BUSINESS_CACHE"),
            ("cts endpoint", "frontend/src/bad.cts", "const api='http://localhost:9999';\n", "LOCAL_ENDPOINT_LITERAL"),
            ("html cache", "frontend/bad.html", "<script>sessionStorage.setItem('business', 'value')</script>\n", "PERSISTENT_BUSINESS_CACHE"),
            ("css endpoint", "frontend/src/bad.css", "body { background: url('http://0.0.0.0:9999/a.png'); }\n", "LOCAL_ENDPOINT_LITERAL"),
        )
        for label, relative, content, reason in cases:
            with self.subTest(label=label), self.fixture() as root:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                actual = scan(root)
                self.assertTrue(any(value.startswith(reason) for value in actual), actual)

    def test_toolchain_uses_explicit_paths_and_wrapper_without_brew_or_global_maven(self) -> None:
        node = shutil.which("node")
        self.assertIsNotNone(node)
        environment = os.environ.copy()
        environment["PATH"] = "/usr/bin:/bin"
        environment["PAB_JAVA_HOME"] = os.environ["JAVA_HOME"]
        environment["PAB_NODE_PREFIX"] = str(Path(node).resolve().parent.parent)
        environment.pop("PAB_JDK_PREFIX", None)

        result = subprocess.run(
            [str(PROJECT_ROOT / "_bmad/scripts/with_pab_toolchain.sh")],
            cwd=PROJECT_ROOT,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("maven=3.9.16", result.stdout)

    def test_normalized_manifest_rejects_symlinks_to_files_outside_source_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory) / "project"
            fixture_root.mkdir()
            (fixture_root / "backend").mkdir()
            external = Path(directory) / "outside.md"
            external.write_text("must not enter manifest\n", encoding="utf-8")
            (fixture_root / "backend/leak.md").symlink_to(external)

            with self.assertRaisesRegex(ValueError, "MANIFEST_SYMLINK_FORBIDDEN"):
                build_manifest(fixture_root)

    def test_normalized_manifest_rejects_symlink_ancestor_for_single_file_entry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory) / "project"
            fixture_root.mkdir()
            external = Path(directory) / "external-bmad"
            (external / "scripts").mkdir(parents=True)
            (external / "scripts/with_pab_toolchain.sh").write_text(
                "must not enter manifest\n", encoding="utf-8"
            )
            (fixture_root / "_bmad").symlink_to(external, target_is_directory=True)

            with self.assertRaisesRegex(ValueError, "MANIFEST_SYMLINK_FORBIDDEN"):
                build_manifest(fixture_root)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in PRODUCTION_ROOTS:
            (root / relative).mkdir(parents=True)

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
