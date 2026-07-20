import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "check_host_deployment", ROOT / "scripts/check_host_deployment.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class HostDeploymentTest(unittest.TestCase):
    def test_local_owner_is_strict_but_explicitly_unbound(self):
        self.assertEqual([], MODULE.violations(ROOT))

    def test_review_accepts_the_user_authorized_controlled_response_owner(self):
        self.assertEqual([], MODULE.violations(ROOT, review=True))


if __name__ == "__main__":
    unittest.main()
