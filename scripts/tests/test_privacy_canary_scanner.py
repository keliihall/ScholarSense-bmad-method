from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
import zipfile
from unittest import mock
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scan_privacy_canaries import (  # noqa: E402
    CANARIES,
    PrivacyScanError,
    format_finding,
    main,
    scan_paths,
)


class PrivacyCanaryScannerTest(unittest.TestCase):
    def test_clean_recursive_files_and_jar_members_pass_with_counts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "classes").mkdir()
            (root / "classes" / "Example.class").write_bytes(b"production-bytecode")
            with zipfile.ZipFile(root / "application.jar", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("BOOT-INF/classes/application.properties", b"mode=production")

            report = scan_paths([root])

        self.assertEqual((), report.findings)
        self.assertEqual(3, report.files_scanned)
        self.assertEqual(1, report.archive_members_scanned)
        self.assertGreater(report.bytes_scanned, 0)

    def test_plain_file_and_nested_jar_canaries_are_detected_without_value_echo(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            browser_value = CANARIES["S18_BROWSER_STORAGE"]
            crypto_value = CANARIES["S18_CRYPTO_PLAINTEXT"]
            (root / "runtime.log").write_bytes(b"prefix:" + browser_value)

            inner = io.BytesIO()
            with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("payload.txt", b"prefix:" + crypto_value)
            with zipfile.ZipFile(root / "application.jar", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("BOOT-INF/lib/component.jar", inner.getvalue())

            report = scan_paths([root])

        self.assertEqual(
            {"S18_BROWSER_STORAGE", "S18_CRYPTO_PLAINTEXT"},
            {finding.canary_id for finding in report.findings},
        )
        rendered = "\n".join(format_finding(finding) for finding in report.findings)
        self.assertIn("runtime.log", rendered)
        self.assertIn("application.jar!BOOT-INF/lib/component.jar!payload.txt", rendered)
        for value in CANARIES.values():
            self.assertNotIn(value.decode("utf-8"), rendered)

    def test_cli_redacts_a_canary_embedded_in_both_filename_and_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            value = CANARIES["S18_ERROR_REFLECTION"].decode("utf-8")
            target = Path(directory) / f"failure-{value}.log"
            target.write_text(value, encoding="utf-8")
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = main(["scan_privacy_canaries.py", str(target)])

        rendered = stdout.getvalue() + stderr.getvalue()
        self.assertEqual(1, result)
        self.assertIn("id=S18_ERROR_REFLECTION", rendered)
        self.assertIn("[S18_ERROR_REFLECTION]", rendered)
        self.assertNotIn(value, rendered)

    def test_public_integration_sensitive_surfaces_are_scanned(self) -> None:
        expected = {
            "S19_STUDENT_IDENTIFIER",
            "S19_EXTERNAL_BODY",
            "S19_FREE_TEXT",
            "S19_SECRET",
            "S19_NONCE",
            "S19_SIGNATURE",
        }
        self.assertTrue(expected <= set(CANARIES))
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "synthetic-evidence.json"
            target.write_bytes(CANARIES["S19_EXTERNAL_BODY"])
            report = scan_paths([target])
        self.assertEqual(
            {"S19_EXTERNAL_BODY"},
            {finding.canary_id for finding in report.findings},
        )

    def test_invalid_archive_fails_closed_with_a_stable_path_only_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "broken.jar"
            target.write_bytes(b"not-a-zip")
            with self.assertRaises(PrivacyScanError) as raised:
                scan_paths([target])

        self.assertEqual("PRIVACY_SCAN_ARCHIVE_INVALID", raised.exception.code)
        self.assertEqual(str(target), raised.exception.location)

    def test_archive_member_count_budget_is_cumulative_across_nested_archives(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, "w") as archive:
                archive.writestr("a.txt", b"a")
                archive.writestr("b.txt", b"b")
            target = Path(directory) / "outer.jar"
            with zipfile.ZipFile(target, "w") as archive:
                archive.writestr("inner.jar", inner.getvalue())
            with mock.patch("scan_privacy_canaries.MAX_ARCHIVE_MEMBERS", 2):
                with self.assertRaises(PrivacyScanError) as raised:
                    scan_paths([target])

        self.assertEqual("PRIVACY_SCAN_ARCHIVE_MEMBER_LIMIT_EXCEEDED", raised.exception.code)

    def test_archive_uncompressed_byte_budget_is_cumulative(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "many.zip"
            with zipfile.ZipFile(target, "w") as archive:
                archive.writestr("a.txt", b"a" * 8)
                archive.writestr("b.txt", b"b" * 8)
            with mock.patch("scan_privacy_canaries.MAX_ARCHIVE_UNCOMPRESSED_BYTES", 12):
                with self.assertRaises(PrivacyScanError) as raised:
                    scan_paths([target])

        self.assertEqual("PRIVACY_SCAN_ARCHIVE_BYTES_EXCEEDED", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
