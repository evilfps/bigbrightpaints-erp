import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from scripts.harness_orchestrator import parse_required_check_command


class HarnessRequiredCheckCommandTest(unittest.TestCase):
    def test_parse_required_check_command_accepts_plain_argv(self) -> None:
        argv = parse_required_check_command("python3 scripts/check.py --ticket-id ERP-39")

        self.assertEqual(argv, ["python3", "scripts/check.py", "--ticket-id", "ERP-39"])

    def test_parse_required_check_command_rejects_shell_control_operators(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported shell syntax"):
            parse_required_check_command("python3 scripts/check.py && rm -rf /tmp/nope")

    def test_parse_required_check_command_rejects_redirect_tokens_embedded_in_args(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported shell syntax"):
            parse_required_check_command("python3 scripts/check.py >/tmp/check.log")

        with self.assertRaisesRegex(ValueError, "unsupported shell syntax"):
            parse_required_check_command("python3 scripts/check.py 2>&1")

    def test_parse_required_check_command_rejects_shell_c_invocation(self) -> None:
        with self.assertRaisesRegex(ValueError, "may not invoke an interactive shell"):
            parse_required_check_command("bash -lc 'python3 scripts/check.py'")


if __name__ == "__main__":
    unittest.main()
