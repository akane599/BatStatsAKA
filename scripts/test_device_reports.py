"""Synthetic runner-output regressions; these do not execute Android tests."""
import unittest
from check_instrumentation_result import require_success


class InstrumentationResultTest(unittest.TestCase):
    def test_successful_completed_run(self):
        self.assertEqual(24, require_success("OK (24 tests)\r\nINSTRUMENTATION_CODE: -1\r\n"))
        self.assertEqual(1, require_success("OK (1 test)\nINSTRUMENTATION_CODE: -1\n"))

    def test_failed_assertions_despite_successful_adb_exit(self):
        with self.assertRaises(ValueError):
            require_success("FAILURES!!!\nTests run: 3, Failures: 1\nINSTRUMENTATION_CODE: -1\n")

    def test_crashed_runner(self):
        with self.assertRaises(ValueError):
            require_success("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")

    def test_missing_runner_completion(self):
        with self.assertRaises(ValueError):
            require_success("OK (24 tests)\n")

    def test_zero_tests(self):
        with self.assertRaises(ValueError):
            require_success("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_skipped_or_failed_test_is_not_a_pass(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                require_success(f"INSTRUMENTATION_STATUS_CODE: {code}\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
