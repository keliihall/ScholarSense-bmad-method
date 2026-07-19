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


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "release"))
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from formal_web import (  # noqa: E402
    FormalWebError,
    formal_web_report_issues,
    safe_extract_frontend,
    visual_baseline_issues,
)
from release_json import canonical_sha256, load_json  # noqa: E402


VGB_PATH = PROJECT_ROOT / "contracts/release/fixtures/valid/visual-baseline.json"
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
                    "checks": {
                        "artifactServed": "passed",
                        "resources": "passed",
                        "console": "passed",
                        "network": "passed",
                        "keyboardFocus": "passed",
                        "axe": "passed",
                        "zoom200": "passed",
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
        combined = shell + harness
        for forbidden in ("npm run build", "vite build", "frontend/dist", "update-snapshots", "--update-snapshots"):
            self.assertNotIn(forbidden, combined)
        self.assertIn("safe_extract_frontend", combined)
        self.assertIn("visual-baseline-vgb-1.0.0.json", combined)


if __name__ == "__main__":
    unittest.main()
