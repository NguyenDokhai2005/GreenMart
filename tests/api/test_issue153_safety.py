"""QA153 guards prevent mutation on a wrong baseline and preserve evidence."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class Qa153SafetyTests(unittest.TestCase):
    def load(self, output):
        spec=importlib.util.spec_from_file_location('qa153_guard',Path(__file__).with_name('issue153_edges.py'))
        module=importlib.util.module_from_spec(spec)
        with patch.dict(os.environ,dict(QA153_SOURCE_COMMIT='a'*40,QA153_PROJECT='greenmart-qa153-guard',
            QA153_PORT='18162',QA153_REPORT=str(output),GREENMART_DEMO_PASSWORD='unit-test-only')):
            spec.loader.exec_module(module)
        return module

    def test_evidence_preserved(self):
        with tempfile.TemporaryDirectory() as work:
            output=Path(work)/'report.json';output.write_text('original evidence',encoding='utf-8')
            with self.assertRaises(ValueError):self.load(output)
            self.assertEqual(output.read_text(encoding='utf-8'),'original evidence')

    def test_wrong_image_stops_before_http_or_fixture_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa=self.load(Path(work)/'report.json')
            with patch.object(qa.subprocess,'check_output',side_effect=['container','wrong-image|sha256:test']), \
                 patch.object(qa,'http') as http,patch.object(qa,'sql') as sql:
                with self.assertRaises(RuntimeError):qa.main()
                http.assert_not_called();sql.assert_not_called()

    def test_wrong_revision_stops_before_http_or_fixture_sql(self):
        with tempfile.TemporaryDirectory() as work:
            qa=self.load(Path(work)/'report.json')
            with patch.object(qa.subprocess,'check_output',side_effect=[
                    'container','greenmart-backend:qa153-aaaaaaa|sha256:test','b'*40]), \
                 patch.object(qa,'http') as http,patch.object(qa,'sql') as sql:
                with self.assertRaises(RuntimeError):qa.main()
                http.assert_not_called();sql.assert_not_called()


if __name__=='__main__':unittest.main()
