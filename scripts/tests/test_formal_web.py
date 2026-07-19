from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from formal_web import (  # noqa: E402
    FormalWebError,
    formal_web_report_issues,
    safe_extract_frontend,
    visual_baseline_issues,
)
import install_formal_browsers as browser_installer  # noqa: E402
from release_json import canonical_sha256, load_json  # noqa: E402


VGB_PATH = PROJECT_ROOT / "contracts/release/visual-baseline-vgb-1.0.0.json"
TEST_ENV_PATH = PROJECT_ROOT / "contracts/performance/test-environment-1.0.0.json"


def archive(path: Path, files: dict[str, bytes], *, special: tuple[str, bytes, str] | None = None) -> str:
    with tarfile.open(path, "w:gz", format=tarfile.PAX_FORMAT) as output:
        for name, payload in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mode = 0o644
            output.addfile(info, io.BytesIO(payload))
        if special is not None:
            name, payload, kind = special
            info = tarfile.TarInfo(name)
            if kind == "symlink":
                info.type = tarfile.SYMTYPE
                info.linkname = payload.decode()
            else:
                info.size = len(payload)
            output.addfile(info, None if kind == "symlink" else io.BytesIO(payload))
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FrozenFrontendExtractionTest(unittest.TestCase):
    def test_extracts_only_expected_archive_bytes_to_read_only_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "frontend.tar.gz"
            digest = archive(source, {"index.html": b"artifact-A", "assets/app.js": b"ok"})
            destination = root / "served"

            result = safe_extract_frontend(source, destination, digest)

            self.assertEqual(digest, result["subjectArtifactSha256"])
            self.assertEqual(b"artifact-A", (destination / "index.html").read_bytes())
            self.assertFalse((destination / "index.html").stat().st_mode & 0o222)
            self.assertFalse(destination.stat().st_mode & 0o222)

    def test_rejects_digest_swap_before_launching_browser(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.tar.gz"
            artifact_b = root / "b.tar.gz"
            expected = archive(artifact_a, {"index.html": b"signed-A"})
            archive(artifact_b, {"index.html": b"substituted-B"})
            launched = False

            def launch() -> None:
                nonlocal launched
                launched = True

            with self.assertRaisesRegex(FormalWebError, "FORMAL_WEB_ARTIFACT_DIGEST_MISMATCH"):
                safe_extract_frontend(artifact_b, root / "served", expected, before_extract=launch)
            self.assertFalse(launched)

    def test_path_replacement_after_hash_cannot_change_extracted_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            selected = root / "selected.tar.gz"
            replacement = root / "replacement.tar.gz"
            digest = archive(selected, {"index.html": b"verified-A"})
            archive(replacement, {"index.html": b"swapped-B"})

            def replace_path() -> None:
                os.replace(replacement, selected)

            safe_extract_frontend(selected, root / "served", digest, before_extract=replace_path)
            self.assertEqual(b"verified-A", (root / "served/index.html").read_bytes())

    def test_rejects_traversal_links_duplicates_and_preexisting_output(self) -> None:
        cases = (
            ("traversal", ("../escape", b"bad", "file")),
            ("absolute", ("/escape", b"bad", "file")),
            ("backslash", ("assets\\escape", b"bad", "file")),
            ("symlink", ("assets/link", b"../../escape", "symlink")),
        )
        for label, special in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "frontend.tar.gz"
                digest = archive(source, {"index.html": b"ok"}, special=special)
                with self.assertRaises(FormalWebError):
                    safe_extract_frontend(source, root / "served", digest)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "frontend.tar.gz"
            with tarfile.open(source, "w:gz") as output:
                for payload in (b"one", b"two"):
                    info = tarfile.TarInfo("index.html")
                    info.size = len(payload)
                    output.addfile(info, io.BytesIO(payload))
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            with self.assertRaisesRegex(FormalWebError, "FORMAL_WEB_ARCHIVE_DUPLICATE_PATH"):
                safe_extract_frontend(source, root / "served", digest)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "frontend.tar.gz"
            digest = archive(source, {"index.html": b"ok"})
            (root / "served").mkdir()
            with self.assertRaisesRegex(FormalWebError, "FORMAL_WEB_OUTPUT_ALREADY_EXISTS"):
                safe_extract_frontend(source, root / "served", digest)

    def test_workspace_dist_is_irrelevant_to_frozen_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workspace = root / "workspace/dist"
            workspace.mkdir(parents=True)
            (workspace / "index.html").write_text("current-source-B", encoding="utf-8")
            source = root / "frontend.tar.gz"
            digest = archive(source, {"index.html": b"stored-artifact-A"})
            destination = root / "served"

            safe_extract_frontend(source, destination, digest)

            self.assertEqual("stored-artifact-A", (destination / "index.html").read_text())
            self.assertEqual("current-source-B", (workspace / "index.html").read_text())


class FormalBrowserInstallTest(unittest.TestCase):
    def _installed_fixture(self, root: Path) -> tuple[dict, dict[Path, str]]:
        environment = load_json(TEST_ENV_PATH, legacy_numbers=True)
        records = []
        digests: dict[Path, str] = {}
        for browser in ("chrome", "edge"):
            for channel in ("current", "previous"):
                expected = environment["web"][browser][channel]
                identifier = f"{browser}-{channel}"
                if browser == "chrome":
                    relative = Path(identifier) / "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
                else:
                    relative = Path(identifier) / "package" / f"MicrosoftEdge-{expected['version']}.pkg/Payload/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
                executable = root / relative
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_bytes(identifier.encode("ascii"))
                executable.chmod(0o555)
                digests[executable.resolve()] = expected["executableSha256"]
                records.append(
                    {
                        "id": identifier,
                        "browser": browser,
                        "channel": channel,
                        "version": expected["version"],
                        "major": expected["major"],
                        "artifactPath": f"/controlled-cache/{identifier}",
                        "artifactSha256": expected["artifactSha256"],
                        "executablePath": str(executable.resolve()),
                        "executableSha256": expected["executableSha256"],
                    }
                )
        manifest = {
            "version": "FORMAL-BROWSER-INSTALL-1.0.0",
            "os": "macOS 26.5.2 build 25F84 arm64",
            "browsers": records,
        }
        (root / "browsers.json").write_text(json.dumps(manifest), encoding="utf-8")
        return manifest, digests

    def test_existing_exact_install_is_revalidated_and_reused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "browsers"
            destination.mkdir()
            manifest, _ = self._installed_fixture(destination)
            with mock.patch.object(browser_installer, "validate_install", return_value=manifest) as validate:
                with mock.patch.object(browser_installer, "install") as install:
                    self.assertEqual(manifest, browser_installer.ensure_install(destination, Path(directory) / "cache"))
            validate.assert_called_once_with(destination.resolve())
            install.assert_not_called()

    def test_existing_install_rejects_executable_digest_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "browsers"
            destination.mkdir()
            _, digests = self._installed_fixture(destination)
            first = next(iter(digests))

            def observed(path: Path) -> str:
                return "0" * 64 if path.resolve() == first else digests[path.resolve()]

            with mock.patch.object(browser_installer, "_sha256", side_effect=observed):
                with self.assertRaisesRegex(browser_installer.BrowserInstallError, "FORMAL_BROWSER_EXECUTABLE_DIGEST_MISMATCH"):
                    browser_installer.validate_install(destination)


class FormalWebContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.vgb = load_json(VGB_PATH)
        self.test_environment = load_json(TEST_ENV_PATH, legacy_numbers=True)

    def report(self) -> dict:
        cells = []
        for cell in self.vgb["cells"]:
            cells.append(
                {
                    "id": cell["id"],
                    "browser": cell["browser"],
                    "channel": cell["channel"],
                    "major": cell["major"],
                    "browserVersion": cell["browserVersion"],
                    "viewport": cell["viewport"],
                    "executableSha256": cell["executableSha256"],
                    "actualScreenshotSha256": cell["goldenScreenshotSha256"],
                    "actualScreenshotPath": f"screenshots/{cell['goldenPath']}",
                    "goldenScreenshotSha256": cell["goldenScreenshotSha256"],
                    "diffPixels": 0,
                    "diffRgbaSha256": "0" * 64,
                    "checks": {
                        "artifactServed": "passed",
                        "resources": "passed",
                        "console": "passed",
                        "network": "passed",
                        "keyboardFocus": "passed",
                        "axe": "passed",
                        "zoom200": "passed",
                        "responsive375": "passed",
                        "reflow320": "passed",
                        "uiTokens": "passed",
                        "brandAssets": "passed",
                    },
                    "result": "passed",
                }
            )
        return {
            "version": "FORMAL-WEB-REPORT-1.0.0",
            "subjectArtifactUri": "ghcr.io/keliihall/scholarsense-release-candidate@sha256:" + "a" * 64,
            "subjectArtifactSha256": self.vgb["approvedArtifactSha256"],
            "buildManifestSha256": "b" * 64,
            "testEnvironmentSha256": self.test_environment["contentSha256"],
            "visualBaselineSha256": canonical_sha256(self.vgb),
            "runnerImageSha256": self.vgb["runnerImageSha256"],
            "matrix": cells,
            "scope": {
                "productionEntry": "/scholarsense/",
                "downstreamBusinessPages": "not-in-scope-before-their-own-stories",
                "appApplicability": "not-applicable",
                "appRuntimeEvidenceClaim": "none",
            },
            "result": "passed",
            "createdAt": "2026-07-19T01:00:00Z",
        }

    def test_vgb_freezes_exact_eight_cell_oracle_and_environment(self) -> None:
        self.assertEqual([], visual_baseline_issues(self.vgb, self.test_environment))
        self.assertEqual(8, len(self.vgb["cells"]))
        self.assertEqual(
            {"chrome-current", "chrome-previous", "edge-current", "edge-previous"},
            {f"{cell['browser']}-{cell['channel']}" for cell in self.vgb["cells"]},
        )
        self.assertEqual({"desktop-reference", "desktop-minimum"}, {cell["viewport"] for cell in self.vgb["cells"]})

    def test_formal_report_must_match_every_golden_and_all_nonvisual_gates(self) -> None:
        report = self.report()
        self.assertEqual([], formal_web_report_issues(report, self.vgb, self.test_environment))
        mutations = (
            ("subject swap", lambda value: value.update(subjectArtifactSha256="9" * 64)),
            ("missing cell", lambda value: value["matrix"].pop()),
            ("golden drift", lambda value: value["matrix"][0].update(goldenScreenshotSha256="9" * 64)),
            ("browser drift", lambda value: value["matrix"][0].update(browserVersion="0.0.0.0")),
            ("failed axe", lambda value: value["matrix"][0]["checks"].update(axe="failed")),
            ("pixel diff", lambda value: value["matrix"][0].update(diffPixels=1)),
            ("fake app", lambda value: value["scope"].update(appRuntimeEvidenceClaim="passed")),
        )
        for label, mutate in mutations:
            with self.subTest(label=label):
                candidate = json.loads(json.dumps(report))
                mutate(candidate)
                self.assertTrue(formal_web_report_issues(candidate, self.vgb, self.test_environment))

    def test_release_entrypoint_cannot_build_or_update_the_oracle(self) -> None:
        shell = (PROJECT_ROOT / "scripts/run-formal-web-evidence.sh").read_text(encoding="utf-8")
        harness = (PROJECT_ROOT / "frontend/scripts/run-formal-web-evidence.mjs").read_text(encoding="utf-8")
        workflow = (PROJECT_ROOT / ".github/workflows/golden-approval.yml").read_text(encoding="utf-8")
        combined = shell + harness + workflow
        for forbidden in ("npm run build", "vite build", "frontend/dist", "update-snapshots", "--update-snapshots"):
            self.assertNotIn(forbidden, combined)
        self.assertIn("safe_extract_frontend", combined)
        self.assertIn("visual-baseline-vgb-1.0.0.json", combined)
        self.assertIn("browsers.json", combined)
        self.assertIn("FORMAL_BROWSER_INSTALL_ROOT", combined)
        self.assertIn('chmod -R u+w "$WORK_DIR"', shell)
        self.assertNotIn("$RUNNER_TEMP/golden-browsers", workflow)
        self.assertNotIn("formal-browser-install.json", combined)

    def test_visual_baseline_does_not_claim_two_human_reviewers(self) -> None:
        self.assertEqual("github-user:24710825:keliihall", self.vgb["approvedByUxBrand"])
        self.assertEqual(
            "automated-gate:.github/workflows/release.yml#job:formal-web",
            self.vgb["approvedByWebQa"],
        )
        self.assertEqual(
            "single-accountable-plus-independent-automated-web-qa",
            self.vgb["approvalPolicy"],
        )


if __name__ == "__main__":
    unittest.main()
