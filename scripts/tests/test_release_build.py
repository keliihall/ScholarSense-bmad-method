from __future__ import annotations

import hashlib
import os
import stat
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from archive import create_normalized_tar_gz  # noqa: E402
from build_release import (  # noqa: E402
    artifact_content_issues,
    artifact_set_sha256,
    build_release,
    validate_build_manifest,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class ReleaseBuildContractTest(unittest.TestCase):
    def test_backend_output_name_and_reproducible_timestamp_are_frozen(self) -> None:
        pom = (PROJECT_ROOT / "backend/pom.xml").read_text(encoding="utf-8")
        self.assertIn("<version>0.1.0-SNAPSHOT</version>", pom)
        self.assertIn("<finalName>scholarsense-backend</finalName>", pom)
        self.assertIn("<project.build.outputTimestamp>2026-07-19T00:00:00Z</project.build.outputTimestamp>", pom)
        roles = (PROJECT_ROOT / "deploy/base/roles.json").read_text(encoding="utf-8")
        self.assertNotIn("scholarsense-backend-0.1.0-SNAPSHOT.jar", roles)
        self.assertEqual(2, roles.count("backend/target/scholarsense-backend.jar"))

    def test_normalized_frontend_archive_ignores_source_mtime_and_mode_noise(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "dist"
            root.mkdir()
            (root / "index.html").write_text("<main>学林知微</main>\n", encoding="utf-8")
            script = root / "assets/app.js"
            script.parent.mkdir()
            script.write_text("console.log('ok')\n", encoding="utf-8")
            first = Path(directory) / "first.tar.gz"
            second = Path(directory) / "second.tar.gz"
            create_normalized_tar_gz(root, first)
            os.utime(script, (1_900_000_000, 1_900_000_000))
            script.chmod(script.stat().st_mode | stat.S_IXUSR)
            create_normalized_tar_gz(root, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with tarfile.open(first, "r:gz") as archive:
                self.assertEqual(["assets", "assets/app.js", "index.html"], archive.getnames())
                self.assertTrue(all(member.mtime == 0 for member in archive.getmembers()))

    def test_build_manifest_requires_two_equal_attempts_and_neutral_artifact_names(self) -> None:
        digest = "a" * 64
        manifest = {
            "version": "BUILD-MANIFEST-1.0.0",
            "sourceCommit": "b" * 40,
            "sourceManifestSha256": "c" * 64,
            "toolchainLockSha256": "d" * 64,
            "backendLockSha256": "2" * 64,
            "frontendLockSha256": "3" * 64,
            "attempts": [
                {"attempt": 1, "buildInputSha256": "e" * 64, "artifactSetSha256": digest},
                {"attempt": 2, "buildInputSha256": "e" * 64, "artifactSetSha256": digest},
            ],
            "artifacts": [
                {"name": "scholarsense-backend.jar", "mediaType": "application/java-archive", "size": 1, "binarySha256": "f" * 64},
                {"name": "scholarsense-frontend.tar.gz", "mediaType": "application/gzip", "size": 1, "binarySha256": "1" * 64},
            ],
        }
        self.assertEqual([], validate_build_manifest(manifest))
        self.assertRegex(artifact_set_sha256(manifest["artifacts"]), r"^[0-9a-f]{64}$")
        drift = {**manifest, "attempts": [*manifest["attempts"]]}
        drift["attempts"][1] = {**drift["attempts"][1], "artifactSetSha256": "2" * 64}
        self.assertTrue(validate_build_manifest(drift))
        old_name = {**manifest, "artifacts": [*manifest["artifacts"]]}
        old_name["artifacts"][0] = {**old_name["artifacts"][0], "name": "scholarsense-backend-0.1.0-SNAPSHOT.jar"}
        self.assertTrue(validate_build_manifest(old_name))

    def test_artifacts_reject_generated_reports_and_secret_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backend = root / "backend.jar"
            with zipfile.ZipFile(backend, "w") as archive:
                archive.writestr("BOOT-INF/classes/.env.production", "TOKEN=forbidden")
            frontend_source = root / "dist"
            (frontend_source / "test-results").mkdir(parents=True)
            (frontend_source / "test-results/report.json").write_text("{}", encoding="utf-8")
            frontend = root / "frontend.tar.gz"
            create_normalized_tar_gz(frontend_source, frontend)
            issues = artifact_content_issues(backend, frontend)
            self.assertTrue(any("SECRET_FILE" in issue for issue in issues))
            self.assertTrue(any("GENERATED_OR_SECRET_PATH" in issue for issue in issues))

    def test_dirty_source_failure_leaves_no_partial_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "release"
            with mock.patch("build_release._git", return_value="dirty"):
                with self.assertRaisesRegex(RuntimeError, "SOURCE_WORKSPACE_DIRTY"):
                    build_release(PROJECT_ROOT, destination)
            self.assertFalse(destination.exists())

    def test_build_entry_is_non_recursive_and_uses_only_backend_wrapper(self) -> None:
        entry = PROJECT_ROOT / "scripts/build-release.sh"
        core = PROJECT_ROOT / "scripts/verify_core.sh"
        self.assertTrue(entry.is_file() and os.access(entry, os.X_OK))
        self.assertTrue(core.is_file() and os.access(core, os.X_OK))
        content = entry.read_text(encoding="utf-8")
        core_content = core.read_text(encoding="utf-8")
        self.assertNotIn("verify.sh", content)
        self.assertNotIn("/mvnw", content.replace("backend/mvnw", ""))
        self.assertNotIn("build-release", core_content)
        self.assertNotIn("verify.sh", core_content)


if __name__ == "__main__":
    unittest.main()
