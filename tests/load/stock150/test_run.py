"""Reject false positives in the final #150 evidence evaluation; no backend mocks."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('qa150', Path(__file__).with_name('run.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.metrics = {'attempts':100,'created':5,'insufficientStock':95,'unexpected':0,'iterations':100,
            'finalAvailable':0,'checksRate':1,'launchOffsetMaxMs':25,
            'scenario':{'executor':'per-vu-iterations','vus':100,'iterationsPerVu':1,'synchronizedStart':True},
            'thresholds':{'pos_created':{'count==5':True}}}
        self.db = {'onHand':0,'reserved':0,'available':0,'orders':5,'quantity':5,
                   'payments':5,'ledgerRows':5,'ledgerDelta':-5,'negativeBalances':0}

    def test_exact_ac_and_persisted_invariants_pass(self):
        self.assertTrue(qa.evaluate(self.metrics,self.db,0))

    def test_overselling_or_missing_successes_cannot_pass(self):
        for successes in [0,4,6,100]:
            metrics = copy.deepcopy(self.metrics);metrics['created'] = successes
            self.assertFalse(qa.evaluate(metrics,self.db,0))

    def test_errors_or_unexecuted_requests_cannot_be_counted_as_stock_rejections(self):
        for key,value in [('attempts',99),('iterations',99),('insufficientStock',94),('unexpected',1),('checksRate',0.99)]:
            metrics = copy.deepcopy(self.metrics);metrics[key] = value
            self.assertFalse(qa.evaluate(metrics,self.db,0))

    def test_api_counts_do_not_override_wrong_database(self):
        for key,value in [('onHand',-1),('reserved',1),('orders',6),('quantity',6),('payments',4),
                          ('ledgerRows',4),('ledgerDelta',-6),('negativeBalances',1)]:
            db = dict(self.db);db[key] = value
            self.assertFalse(qa.evaluate(self.metrics,db,0))

    def test_failed_or_missing_thresholds_and_nonzero_exit_fail(self):
        self.assertFalse(qa.evaluate(self.metrics,self.db,99))
        for thresholds in [{},{'pos_created':{'count==5':False}}]:
            metrics = copy.deepcopy(self.metrics);metrics['thresholds'] = thresholds
            self.assertFalse(qa.evaluate(metrics,self.db,0))

    def test_serial_or_delayed_launch_does_not_qualify(self):
        metrics = copy.deepcopy(self.metrics);metrics['scenario']['vus'] = 1
        self.assertFalse(qa.evaluate(metrics,self.db,0))
        metrics = copy.deepcopy(self.metrics);metrics['launchOffsetMaxMs'] = 2000
        self.assertFalse(qa.evaluate(metrics,self.db,0))


if __name__ == '__main__':
    unittest.main()
