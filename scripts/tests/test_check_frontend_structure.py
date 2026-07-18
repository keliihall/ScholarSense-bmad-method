from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_frontend_structure import DOMAINS, validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class FrontendStructureTest(unittest.TestCase):
    def test_production_skeleton_passes(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT / "frontend"))

    def test_minimal_valid_fixture_passes(self) -> None:
        with self.fixture() as root:
            self.assertEqual([], validate(root))

    def test_prototype_directory_is_rejected(self) -> None:
        with self.fixture() as root:
            (root / "src/mock").mkdir()
            self.assert_reason(root, "PROTOTYPE_DIRECTORY_FORBIDDEN")

    def test_local_endpoint_and_persistent_cache_are_rejected(self) -> None:
        with self.fixture() as root:
            (root / "src/app/config/index.ts").write_text(
                "const gateway = 'http://127.0.0.1:8080';\nlocalStorage.setItem('clues', '[]');\n",
                encoding="utf-8",
            )
            self.assert_reason(root, "LOCAL_ENDPOINT_FORBIDDEN")
            self.assert_reason(root, "PERSISTENT_BUSINESS_CACHE_FORBIDDEN")

    def test_non_allowlisted_client_variable_is_rejected(self) -> None:
        with self.fixture() as root:
            (root / "src/app/config/index.ts").write_text(
                "export const secret = import.meta.env.VITE_DATABASE_PASSWORD;\n", encoding="utf-8"
            )
            self.assert_reason(root, "CLIENT_ENV_NOT_ALLOWLISTED")

    def test_non_allowlisted_client_variable_with_legal_trivia_is_rejected(self) -> None:
        cases = (
            "export const secret = import.meta . env . VITE_DATABASE_PASSWORD;\n",
            "export const secret = import.meta/*x*/.env.VITE_DATABASE_PASSWORD;\n",
            "export const secret = process /*x*/ . env[\"VITE_DATABASE_PASSWORD\"];\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/app/config/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "CLIENT_ENV_NOT_ALLOWLISTED")

    def test_bracket_and_destructured_client_variables_are_rejected(self) -> None:
        cases = (
            "export const secret = import.meta.env['VITE_DATABASE_PASSWORD'];\n",
            "const { VITE_DATABASE_PASSWORD: secret } = import.meta.env;\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/app/config/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "CLIENT_ENV_NOT_ALLOWLISTED")

    def test_template_key_and_optional_chain_client_variables_are_rejected(self) -> None:
        cases = (
            "export const secret = import.meta.env[`VITE_DATABASE_PASSWORD`];\n",
            "export const secret = import.meta.env?.VITE_DATABASE_PASSWORD;\n",
            "export const secret = process?.env?.[`VITE_DATABASE_PASSWORD`];\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/app/config/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "CLIENT_ENV_NOT_ALLOWLISTED")

    def test_approved_static_client_environment_accesses_pass(self) -> None:
        with self.fixture() as root:
            (root / "src/app/config/client-env-allowlist.json").write_text(
                json.dumps(["VITE_PUBLIC_API", "VITE_PUBLIC_MODE"]), encoding="utf-8"
            )
            (root / "src/app/config/index.ts").write_text(
                "export const api = import.meta.env.VITE_PUBLIC_API;\n"
                "export const mode = import.meta.env?.['VITE_PUBLIC_MODE'];\n"
                "export const bracketRoot = import.meta['env'].VITE_PUBLIC_API;\n"
                "export const spaced = import.meta /* root */ . env /* field */ . VITE_PUBLIC_API;\n"
                "export const processSpaced = process /* root */ . env /* field */ ['VITE_PUBLIC_MODE'];\n"
                "const { VITE_PUBLIC_API: approvedApi, } = import.meta.env;\n"
                "export { approvedApi };\n",
                encoding="utf-8",
            )
            actual = validate(root)
            self.assertFalse(any(value.startswith("CLIENT_ENV_") for value in actual), actual)

    def test_ambiguous_client_environment_accesses_are_rejected(self) -> None:
        cases = (
            "const env = import.meta.env;\n",
            "const copy = { ...import.meta.env };\n",
            "const key = 'VITE_PUBLIC_API'; export const value = import.meta.env[key];\n",
            "const key = 'VITE_PUBLIC_API'; export const value = import.meta['env'][key];\n",
            "export const keys = Object.keys(import.meta.env);\n",
            "const { ...environment } = import.meta.env;\n",
            "const name = 'API'; export const value = import.meta.env[`VITE_${name}`];\n",
            "const env = import.meta . env; export const secret = env.VITE_DATABASE_PASSWORD;\n",
            "const env = import.meta/*x*/.env; export const secret = env.VITE_DATABASE_PASSWORD;\n",
            "const env = process /*x*/ . env; export const secret = env['VITE_DATABASE_PASSWORD'];\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/app/config/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "CLIENT_ENV_ACCESS_NOT_STATIC")

    def test_cross_domain_internal_import_is_rejected(self) -> None:
        with self.fixture() as root:
            (root / "src/domains/identity-access/index.ts").write_text(
                "import { secret } from '@/domains/subject-registry/internal/secret';\n",
                encoding="utf-8",
            )
            self.assert_reason(root, "CROSS_DOMAIN_INTERNAL_IMPORT")

    def test_dynamic_cross_domain_internal_import_is_rejected(self) -> None:
        with self.fixture() as root:
            (root / "src/domains/identity-access/index.ts").write_text(
                "const loadSecret = () => import('@/domains/subject-registry/internal/secret');\n",
                encoding="utf-8",
            )
            self.assert_reason(root, "CROSS_DOMAIN_INTERNAL_IMPORT")

    def test_template_and_commented_dynamic_cross_domain_imports_are_rejected(self) -> None:
        cases = (
            "const loadSecret = () => import(`@/domains/subject-registry/internal/secret`);\n",
            "const loadSecret = () => import(/* preload */ '@/domains/subject-registry/internal/secret');\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/domains/identity-access/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "CROSS_DOMAIN_INTERNAL_IMPORT")

    def test_non_static_dynamic_import_specifiers_are_rejected(self) -> None:
        cases = (
            "const target = '../subject-registry/internal/secret'; import(target);\n",
            "const target = 'subject-registry'; import(`../${target}/internal/secret`);\n",
            "const target = '../subject-registry/internal/secret'; import('' + target);\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "src/domains/identity-access/index.ts").write_text(content, encoding="utf-8")
                self.assert_reason(root, "DYNAMIC_IMPORT_SPECIFIER_NOT_STATIC")

    def test_approved_production_lock_is_required(self) -> None:
        with self.fixture() as root:
            (root / "package-lock.json").unlink()
            self.assert_reason(root, "PRODUCTION_LOCK_REQUIRED")
            (root / "package-lock.json").write_text("{}\n", encoding="utf-8")
            self.assertFalse(
                any(value.startswith("PRODUCTION_LOCK_REQUIRED") for value in validate(root)),
                validate(root),
            )

    def test_alternate_lock_is_rejected(self) -> None:
        with self.fixture() as root:
            (root / "yarn.lock").write_text("fixture\n", encoding="utf-8")
            self.assert_reason(root, "ALTERNATE_PRODUCTION_LOCK")

    def assert_reason(self, root: Path, reason: str) -> None:
        self.assertTrue(
            any(value.startswith(reason) for value in validate(root)),
            f"expected {reason}, got {validate(root)}",
        )

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in (
            "src/app/router/index.ts",
            "src/app/theme/index.ts",
            "src/app/config/index.ts",
            "src/components/README.md",
            "src/shared/README.md",
        ):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("export {};\n" if path.suffix == ".ts" else "Placeholder.\n", encoding="utf-8")
        for domain in DOMAINS:
            path = root / "src/domains" / domain / "index.ts"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("export {};\n", encoding="utf-8")
        allowlist = root / "src/app/config/client-env-allowlist.json"
        allowlist.write_text(json.dumps([]), encoding="utf-8")
        (root / "package-lock.json").write_text("{}\n", encoding="utf-8")

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
