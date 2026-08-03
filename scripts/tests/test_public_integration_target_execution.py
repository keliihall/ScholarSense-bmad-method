import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import run_public_integration_sandbox_tests as local_runner
from scripts import run_public_integration_target_sandbox_tests as target_runner


class PublicIntegrationTargetExecutionTests(unittest.TestCase):
    def test_real_mtls_target_runs_locked_vector_and_cleans_every_object(self):
        if subprocess.run(
            ["openssl", "version"], capture_output=True, check=False
        ).returncode != 0:
            self.skipTest("openssl unavailable")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            host = "127.0.0.1"
            material = _create_material(root, host)
            with target_runner.DelegatedTargetSandbox(
                bind_host=host,
                server_cert=material["serverCertFile"],
                server_key=material["serverKeyFile"],
                client_ca=material["caFile"],
                bearer_token="synthetic-target-token",
            ) as sandbox:
                handoff_env = _create_signed_handoff(
                    root,
                    sandbox.base_uri,
                    material,
                    trusted_now="2026-08-03T08:00:00Z",
                )
                with mock.patch.dict(os.environ, handoff_env, clear=False):
                    preflight = target_runner.preflight_target_handoff(
                        dict(os.environ), now="2026-08-03T08:00:00Z"
                    )
                    evidence = target_runner.run_target_scenarios(
                        preflight,
                        subject_commit="a" * 40,
                        subject_tree="b" * 40,
                        run_at="2026-08-03T08:00:00Z",
                    )

            self.assertEqual([], local_runner.validate_evidence(evidence))
            self.assertTrue(evidence["targetSandboxConnected"])
            self.assertTrue(evidence["signatureVerified"])
            self.assertEqual("pass", evidence["overallResult"])
            self.assertEqual(0, evidence["failedCount"])
            self.assertEqual(0, evidence["skippedCount"])
            self.assertEqual("pass", evidence["sandboxCleanupResult"])
            self.assertEqual(0, evidence["orphanCount"])
            self.assertEqual(0, evidence["approvedRetainedCount"])
            self.assertEqual(
                evidence["syntheticExternalObjectsCreated"],
                evidence["syntheticExternalObjectsClosed"]
                + evidence["syntheticExternalObjectsRevoked"],
            )
            self.assertEqual(10, len(evidence["scenarioResults"]))
            self.assertEqual(1, sandbox.transient_failures_served)


def _run(*command: str) -> None:
    subprocess.run(command, check=True, capture_output=True)


def _create_material(root: Path, host: str) -> dict[str, str]:
    ca_key = root / "ca.key.pem"
    ca_cert = root / "ca.cert.pem"
    server_key = root / "server.key.pem"
    server_csr = root / "server.csr.pem"
    server_cert = root / "server.cert.pem"
    client_key = root / "client.key.pem"
    client_csr = root / "client.csr.pem"
    client_cert = root / "client.cert.pem"
    _run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(ca_key), "-out", str(ca_cert), "-days", "2",
         "-subj", "/CN=PIC Delegated Test CA",
         "-addext", "basicConstraints=critical,CA:TRUE",
         "-addext", "keyUsage=critical,keyCertSign,cRLSign",
         "-addext", "subjectKeyIdentifier=hash")
    _run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(server_key), "-out", str(server_csr),
         "-subj", "/CN=" + host,
         "-addext", "basicConstraints=critical,CA:FALSE",
         "-addext", "keyUsage=critical,digitalSignature,keyEncipherment",
         "-addext", "extendedKeyUsage=serverAuth",
         "-addext", "subjectAltName=IP:" + host)
    _run("openssl", "x509", "-req", "-in", str(server_csr),
         "-CA", str(ca_cert), "-CAkey", str(ca_key), "-CAcreateserial",
         "-out", str(server_cert), "-days", "2", "-copy_extensions", "copy")
    _run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(client_key), "-out", str(client_csr),
         "-subj", "/CN=PIC Synthetic Client",
         "-addext", "basicConstraints=critical,CA:FALSE",
         "-addext", "keyUsage=critical,digitalSignature,keyEncipherment",
         "-addext", "extendedKeyUsage=clientAuth")
    _run("openssl", "x509", "-req", "-in", str(client_csr),
         "-CA", str(ca_cert), "-CAkey", str(ca_key), "-CAcreateserial",
         "-out", str(client_cert), "-days", "2", "-copy_extensions", "copy")
    bundle = root / "credential-bundle.json"
    bundle.write_text(json.dumps({
        "clientCertFile": str(client_cert),
        "clientKeyFile": str(client_key),
        "caFile": str(ca_cert),
        "bearerToken": "synthetic-target-token",
    }))
    return {
        "serverCertFile": str(server_cert),
        "serverKeyFile": str(server_key),
        "clientCertFile": str(client_cert),
        "clientKeyFile": str(client_key),
        "caFile": str(ca_cert),
        "bundleFile": str(bundle),
        "peerSpkiSha256": "sha256:" + target_runner.certificate_spki_sha256(
            server_cert
        ),
    }


def _create_signed_handoff(
    root: Path,
    base_uri: str,
    material: dict[str, str],
    *,
    trusted_now: str,
) -> dict[str, str]:
    del trusted_now
    signing_key = root / "handoff-signing.key.pem"
    trust_root = root / "handoff-signing.pub.pem"
    _run("openssl", "genpkey", "-algorithm", "ED25519", "-out", str(signing_key))
    _run("openssl", "pkey", "-in", str(signing_key), "-pubout",
         "-out", str(trust_root))
    trust_digest = "sha256:" + hashlib.sha256(trust_root.read_bytes()).hexdigest()
    handoff_document = target_runner.synthetic_handoff_fixture(trust_digest)
    handoff_document["peerSpkiSha256"] = material["peerSpkiSha256"]
    handoff_document["targetDescriptor"]["baseUri"] = base_uri
    handoff_document["credentialRef"] = (
        "credential-ref:env/SCHOLARSENSE_PIC_DELEGATED_CREDENTIAL"
    )
    handoff = root / "handoff.json"
    handoff_bytes = local_runner.canonical_json_bytes(handoff_document)
    handoff.write_bytes(handoff_bytes)
    signature_raw = root / "handoff.signature.raw"
    _run("openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(signing_key),
         "-in", str(handoff), "-out", str(signature_raw))
    signature = root / "handoff.signature.json"
    signature.write_text(json.dumps(target_runner.synthetic_signature_fixture(
        hashlib.sha256(handoff_bytes).hexdigest(), signature_raw.read_bytes()
    )))
    return {
        "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE": str(handoff),
        "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE": str(signature),
        "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE": str(trust_root),
        "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256": hashlib.sha256(
            handoff_bytes
        ).hexdigest(),
        "SCHOLARSENSE_PIC_DELEGATED_CREDENTIAL": material["bundleFile"],
    }


if __name__ == "__main__":
    unittest.main()
