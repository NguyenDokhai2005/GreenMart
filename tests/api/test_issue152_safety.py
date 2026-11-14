"""Guard checks: wrong QA baseline or existing evidence must block before writes."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class Qa152SafetyTests(unittest.TestCase):
    def load(self, output):
        spec=importlib.util.spec_from_file_location('qa152_guard',Path(__file__).with_name('issue152_rbac.py'))
        module=importlib.util.module_from_spec(spec)
        with patch.dict(os.environ,dict(QA152_SOURCE_COMMIT='a'*40,QA152_PROJECT='greenmart-qa152-guard',
            QA152_PORT='18161',QA152_REPORT=str(output),GREENMART_DEMO_PASSWORD='unit-test-only')):
            spec.loader.exec_module(module)
        return module

    def test_existing_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as work:
            output=Path(work)/'evidence.json'
            output.write_text('preserve original evidence',encoding='utf-8')
            with self.assertRaises(ValueError):self.load(output)
            self.assertEqual(output.read_text(encoding='utf-8'),'preserve original evidence')

    def test_wrong_image_blocks_before_http_or_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa=self.load(Path(work)/'evidence.json')
            with patch.object(qa.subprocess,'check_output',side_effect=['container','other-image|sha256:test']), \
                 patch.object(qa,'http') as http,patch.object(qa,'sql') as sql:
                with self.assertRaises(RuntimeError):qa.main()
                http.assert_not_called();sql.assert_not_called()

    def test_wrong_revision_blocks_before_http_or_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa=self.load(Path(work)/'evidence.json')
            with patch.object(qa.subprocess,'check_output',side_effect=[
                    'container','greenmart-backend:qa152-aaaaaaa|sha256:test','b'*40]), \
                 patch.object(qa,'http') as http,patch.object(qa,'sql') as sql:
                with self.assertRaises(RuntimeError):qa.main()
                http.assert_not_called();sql.assert_not_called()


if __name__=='__main__':unittest.main()
