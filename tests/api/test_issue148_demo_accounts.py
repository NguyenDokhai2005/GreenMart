"""Regression tests for the QA runner, not evidence that the actual API passes."""
import copy
import unittest
from unittest.mock import patch

import issue148_demo_accounts as qa


def user(name):
    _, _, role, branches = next(a for a in qa.ACCOUNTS if a[1] == name)
    return {"id": 7, "username": name, "fullName": "Demo User", "role": role,
            "branches": [{"id": int(b[-1]), "code": b, "name": b} for b in sorted(branches)]}


def respond(base, path, body=None, token=None):
    if path == "/auth/login":
        return 200, {"accessToken": body["username"], "refreshToken": "not-a-real-token",
                     "tokenType": "Bearer", "expiresIn": 900, "user": user(body["username"])}
    if path == "/auth/me":
        return 200, user(token)
    return 204, None


def database():
    return {"branches": sorted(qa.BRANCHES), "differentHashes": True,
            "users": [{"username": n, "role": r, "active": True, "branches": sorted(bs), "bcrypt": True}
                      for _, n, r, bs in qa.ACCOUNTS]}


class RunnerTests(unittest.TestCase):
    def test_all_cases_and_cleanup(self):
        with patch.object(qa, "request", side_effect=respond) as call:
            results, warnings = qa.run_checks(database(), None, "http://test")
        self.assertEqual(len(results), 26)
        self.assertEqual(len({r["id"] for r in results}), 26)
        self.assertTrue(all(r["result"] == "PASS" for r in results))
        self.assertEqual(warnings, [])
        self.assertEqual(sum(c.args[1] == "/auth/logout" for c in call.call_args_list), 12)

    def test_extra_wrong_or_duplicate_branch_is_rejected(self):
        valid = user("thungan.gm001")
        for branches in [user("admin")["branches"], user("thungan.gm002")["branches"],
                         valid["branches"] * 2]:
            candidate = copy.deepcopy(valid)
            candidate["branches"] = branches
            self.assertFalse(qa.validate_user(candidate, "thungan.gm001", "CASHIER", {"GM-001"}))

    def test_malformed_branch_fields_fail_without_crashing(self):
        for field in ["id", "code"]:
            candidate = user("thungan.gm001")
            candidate["branches"][0][field] = []
            self.assertFalse(qa.validate_user(candidate, "thungan.gm001", "CASHIER", {"GM-001"}))

    def test_last_cashier_or_storekeeper_login_failure_fails_aggregate(self):
        def fail_last(base, path, body=None, token=None):
            if path == "/auth/login" and body["username"] == "thukho.gm003":
                return 401, None
            return respond(base, path, body, token)
        with patch.object(qa, "request", side_effect=fail_last):
            results, _ = qa.run_checks(database(), None, "http://test")
        self.assertEqual(next(r for r in results if r["id"] == "TC-DEMO-21")["result"], "FAIL")

    def test_unavailable_environment_never_passes(self):
        with patch.object(qa, "request", side_effect=qa.Blocked("API unavailable")):
            results, _ = qa.run_checks(None, "DB unavailable", "http://test")
        self.assertEqual(len(results), 26)
        self.assertTrue(all(r["result"] == "BLOCKED" for r in results))

    def test_me_network_failure_does_not_duplicate_login_result(self):
        def fail_me(base, path, body=None, token=None):
            if path == "/auth/me":
                raise qa.Blocked("API unavailable")
            return respond(base, path, body, token)
        with patch.object(qa, "request", side_effect=fail_me):
            results, _ = qa.run_checks(database(), None, "http://test")
        self.assertEqual(len(results), 26)
        self.assertEqual(sum(r["result"] == "BLOCKED" for r in results), 5)

    def test_report_records_no_tokens_passwords_or_hashes(self):
        with patch.object(qa, "request", side_effect=respond):
            results, _ = qa.run_checks(database(), None, "http://test")
        text = str(results)
        self.assertNotIn("not-a-real-token", text)
        self.assertNotIn("Demo@123", text)
        self.assertNotIn("$2a$", text)


if __name__ == "__main__":
    unittest.main()
