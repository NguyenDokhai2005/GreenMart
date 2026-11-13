"""Regression checks for Linux seed ownership and safe error diagnostics."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_qa149", Path(__file__).with_name("prepare-qa.py"))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class SeedSetupTests(unittest.TestCase):
    def test_all_tracked_qa_helpers_supply_fresh_forecast_token_to_compose(self):
        """Exercise setup until config --quiet; never build/start a container."""
        root = Path(__file__).resolve().parents[3]
        paths = [root / 'tests/api/pos149/prepare-qa.py',
                 root / 'tests/api/run_issue148_qa.py',
                 root / 'tests/load/stock150/run.py']
        class ValidationReached(Exception):
            pass
        for index, source in enumerate(paths):
            spec = importlib.util.spec_from_file_location(f'qa_token_{index}', source)
            helper = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(helper)
            values = []
            for attempt in range(2):
                with self.subTest(helper=source.name, attempt=attempt), tempfile.TemporaryDirectory() as work:
                    def fake_run(command, **kwargs):
                        if command[-2:] == ['config', '--quiet']:
                            token = kwargs['env'].get('FORECAST_INTERNAL_TOKEN', '')
                            self.assertTrue(len(token) == 64 and all(c in '0123456789abcdef' for c in token),
                                            'QA must generate a 32-byte token for Compose')
                            self.assertTrue(token != 'inherited-token-must-not-be-reused',
                                            'QA must not reuse inherited credentials')
                            values.append(token)
                            raise ValidationReached()
                        return subprocess.CompletedProcess(command, 0, '' if kwargs.get('text') else b'', '')
                    with patch.object(helper, 'ROOT', Path(work)), \
                            patch.object(helper.subprocess, 'run', side_effect=fake_run), \
                            patch.object(sys, 'argv', ['qa']), \
                            patch.dict(helper.os.environ, {'FORECAST_INTERNAL_TOKEN': 'inherited-token-must-not-be-reused'}):
                        with self.assertRaises(ValidationReached):
                            (helper.prepare if index == 2 else helper.main)()
            self.assertTrue(len(values) == 2 and values[0] != values[1],
                            'Each QA setup must generate a different token')

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
