"""Regression for Lead review: unresolved Q-31 and Compose token prerequisite."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    with patch.dict(os.environ, {'QA242_SOURCE_COMMIT': 'a'*40, 'GREENMART_DEMO_PASSWORD': 'fixture-only'}):
        spec.loader.exec_module(module)
    return module


class ReviewFixTests(unittest.TestCase):
    def test_no_role_audit_is_blocked_until_q31_decided(self):
        qa = load('auth242_review_zero', 'check_api.py')
        qa.record_pending_role_audit(403, {'code': 'FORBIDDEN'}, 0)
        self.assertEqual(qa.CASES['TC-AUTH02-41']['result'], 'BLOCKED')
        self.assertEqual(qa.CASES['TC-AUTH02-41']['policyQuestion'], 'Q-31')

    def test_present_role_audit_is_also_not_accepted_without_policy(self):
        qa = load('auth242_review_present', 'check_api.py')
        qa.record_pending_role_audit(403, {'code': 'FORBIDDEN'}, 1)
        self.assertEqual(qa.CASES['TC-AUTH02-41']['result'], 'BLOCKED')
        self.assertEqual(qa.CASES['TC-AUTH02-41']['newAuditRows'], 1)

    def test_wrong_role_status_is_not_hidden_as_policy_blocker(self):
        qa = load('auth242_review_status', 'check_api.py')
        qa.record_pending_role_audit(200, {}, 0)
        self.assertEqual(qa.CASES['TC-AUTH02-41']['result'], 'FAIL')

    def test_wrong_denial_code_is_not_hidden_as_policy_blocker(self):
        qa = load('auth242_review_code', 'check_api.py')
        qa.record_pending_role_audit(403, {'code': 'BRANCH_FORBIDDEN'}, 0)
        self.assertEqual(qa.CASES['TC-AUTH02-41']['result'], 'FAIL')

    def test_forecast_token_reaches_compose_without_starting_services(self):
        qa = load('auth242_prepare_review', 'prepare_qa.py')
        observed = {}
        class StopBeforeServices(Exception):
            pass
        def fake_run(args, **kwargs):
            if args[:3] == ['git', 'rev-parse', 'HEAD']:
                return subprocess.CompletedProcess(args, 0, ('a'*40).encode(), b'')
            if 'config' in args:
                observed['token'] = kwargs['env'].get('FORECAST_INTERNAL_TOKEN')
                raise StopBeforeServices()
            self.assertNotIn('up', args)
            return subprocess.CompletedProcess(args, 0, b'', b'')
        with tempfile.TemporaryDirectory() as work, patch.object(qa, 'ROOT', Path(work)), \
             patch.dict(os.environ, {'GREENMART_DEMO_PASSWORD': 'fixture-only', 'FORECAST_INTERNAL_TOKEN': 'old-inherited-value'}), \
             patch.object(qa.subprocess, 'run', side_effect=fake_run):
            with self.assertRaises(StopBeforeServices):
                qa.main()
        self.assertRegex(observed['token'], r'^[a-f0-9]{64}$')
        self.assertNotEqual(observed['token'], 'old-inherited-value')


if __name__ == '__main__':
    unittest.main()
