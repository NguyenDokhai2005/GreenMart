"""Regression checks for Linux seed ownership and safe error diagnostics."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_qa149", Path(__file__).with_name("prepare-qa.py"))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class SeedSetupTests(unittest.TestCase):
    def test_linux_seed_uses_runner_uid_gid(self):
        source, data = Path("source"), Path("data")
        with patch.object(qa.os, "name", "posix"), \
                patch.object(qa.os, "getuid", return_value=1001, create=True), \
                patch.object(qa.os, "getgid", return_value=118, create=True):
            command = qa.seed_command(source, data)
        self.assertEqual(command[command.index("--user") + 1], "1001:118")
        self.assertIn(f"type=bind,src={source / 'forecast-service/scripts'},dst=/qa/scripts,readonly", command)
        self.assertEqual(command[command.index("--network") + 1], "none")

    def test_windows_retains_image_user(self):
        source, data = Path("source"), Path("data")
        with patch.object(qa.os, "name", "nt"):
            command = qa.seed_command(source, data)
        self.assertNotIn("--user", command)

    def test_failed_seed_identifies_step_without_leaking_output(self):
        failed = subprocess.CompletedProcess(["docker"], 1, b"private payload", b"private credential")
        with patch.object(qa.subprocess, "run", return_value=failed):
            with self.assertRaises(SystemExit) as raised:
                qa.call(["docker"], step="generate SV06 seed")
        self.assertIn("generate SV06 seed", str(raised.exception))
        self.assertNotIn("private", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
