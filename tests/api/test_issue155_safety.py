"""Safety guards for the real QA155 runner; no live Docker/API required."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class SecurityQaGuards(unittest.TestCase):
    def load(self, output, project='greenmart-qa155-guard'):
        spec = importlib.util.spec_from_file_location('audit155_guard', Path(__file__).with_name('issue155_security.py'))
        module = importlib.util.module_from_spec(spec)
        with patch.dict(os.environ, dict(QA155_SOURCE_COMMIT='a'*40, QA155_PROJECT=project,
                                        QA155_PORT='18165', QA155_REPORT=str(output), GREENMART_DEMO_PASSWORD='guard-only')):
            spec.loader.exec_module(module)
        return module

    def test_existing_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as work:
            output = Path(work)/'result.json'
            output.write_text('evidence', encoding='utf-8')
            with self.assertRaises(ValueError):
                self.load(output)
            self.assertEqual(output.read_text(encoding='utf-8'), 'evidence')

    def test_shared_project_rejected_at_import(self):
        with tempfile.TemporaryDirectory() as work:
            with self.assertRaises(ValueError):
                self.load(Path(work)/'result.json', 'greenmart')

    def test_wrong_image_prevents_api_and_fixture_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa = self.load(Path(work)/'result.json')
            outputs = [subprocess.CompletedProcess([], 0, 'backend-id', ''),
                       subprocess.CompletedProcess([], 0, 'wrong-image|sha256:test', '')]
            with patch.object(qa, 'command', side_effect=outputs), patch.object(qa, 'http') as http, patch.object(qa, 'sql') as sql:
                with self.assertRaises(RuntimeError):
                    qa.main()
                http.assert_not_called()
                sql.assert_not_called()

    def test_wrong_revision_prevents_api_and_fixture_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa = self.load(Path(work)/'result.json')
            outputs = [subprocess.CompletedProcess([], 0, 'backend-id', ''),
                       subprocess.CompletedProcess([], 0, 'greenmart-backend:qa155-aaaaaaa|sha256:test', ''),
                       subprocess.CompletedProcess([], 0, 'b'*40, '')]
            with patch.object(qa, 'command', side_effect=outputs), patch.object(qa, 'http') as http, patch.object(qa, 'sql') as sql:
                with self.assertRaises(RuntimeError):
                    qa.main()
                http.assert_not_called()
                sql.assert_not_called()

    def test_sql_mutation_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as work:
            qa = self.load(Path(work)/'result.json')
            with patch.object(qa, 'command') as command:
                with self.assertRaises(ValueError):
                    qa.sql('DELETE FROM product')
                command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
