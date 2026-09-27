"""Host-only orchestration regressions: these fakes never contact an Android device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUNNER = Path(__file__).with_name("check_android_device.sh")


class DevicePhasesTest(unittest.TestCase):
    def run_phases(self, ordinary=0, shizuku=0, setup=0, boot="1", page_size="4096", expected_page="4096", group="standard"):
        temporary = tempfile.TemporaryDirectory(prefix="batstats-phase-test-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "bin").mkdir()
        (root / "scripts").mkdir()
        sibling = root / "app/build/reports/device-validation/other-image"
        sibling.mkdir(parents=True)
        (sibling / "retained.txt").write_text("Earlier image evidence")

        def executable(path, body):
            path.write_text("#!/usr/bin/env python3\n" + body)
            path.chmod(0o755)

        executable(root / "bin/adb", """
import os, sys
args = sys.argv[1:]
if args == ['get-serialno']: print('emulator-5554')
elif args[:2] == ['shell', 'getprop']:
    print({'ro.build.version.sdk':'36', 'ro.hardware':'ranchu',
           'sys.boot_completed':os.environ['BATSTATS_TEST_BOOT']}[args[2]])
elif args == ['shell', 'getconf', 'PAGE_SIZE']: print(os.environ['BATSTATS_TEST_PAGE_SIZE'])
elif args[0] == 'pull': sys.exit(1)  # AGP has already uninstalled the package.
""")
        executable(root / "gradlew", """
from pathlib import Path
import os, sys
phase = 'ordinary' if any('notAnnotation=' in a for a in sys.argv) else 'shizuku'
with Path('calls.txt').open('a') as stream: stream.write(phase + '\\n')
for directory in ('app/build/outputs/androidTest-results', 'app/build/reports/androidTests',
                  'app/build/outputs/connected_android_test_additional_output'):
    path = Path(directory); path.mkdir(parents=True, exist_ok=True)
    (path / (phase + '.txt')).write_text('simulated ' + phase + ' report')
sys.exit(int(os.environ['BATSTATS_TEST_' + phase.upper()]))
""")
        (root / "scripts/prepare_shizuku.py").write_text("""
import os, sys
from pathlib import Path
with Path('calls.txt').open('a') as stream: stream.write('setup\\n')
sys.exit(int(os.environ['BATSTATS_TEST_SETUP']))
""")
        environment = dict(os.environ, PATH=str(root / "bin") + os.pathsep + os.environ["PATH"],
                           ANDROID_SERIAL="emulator-5554", BATSTATS_TEST_ORDINARY=str(ordinary),
                           BATSTATS_TEST_SHIZUKU=str(shizuku), BATSTATS_TEST_SETUP=str(setup),
                           BATSTATS_TEST_BOOT=boot, BATSTATS_TEST_PAGE_SIZE=page_size,
                           BATSTATS_EXPECTED_PAGE_SIZE=expected_page, BATSTATS_REPORT_GROUP=group)
        result = subprocess.run(["bash", str(RUNNER)], cwd=root, env=environment,
                                text=True, capture_output=True, timeout=20)
        calls = (root / "calls.txt").read_text().splitlines() if (root / "calls.txt").exists() else []
        return root, result, calls

    def test_both_phases_must_pass_and_keep_distinct_reports(self):
        root, result, calls = self.run_phases()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["ordinary", "setup", "shizuku"], calls)
        reports = root / "app/build/reports/device-validation/standard"
        for phase in ("ordinary", "shizuku"):
            self.assertTrue((reports / f"{phase}-results/{phase}.txt").is_file())
            self.assertTrue((reports / f"{phase}-screenshots/{phase}.txt").is_file())
        self.assertFalse((reports / "shizuku-results/ordinary.txt").exists())

    def test_ordinary_failure_still_runs_shizuku_and_fails_the_command(self):
        _, result, calls = self.run_phases(ordinary=3)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["ordinary", "setup", "shizuku"], calls)
        self.assertIn("ordinary_exit=3", result.stdout)

    def test_shizuku_failure_cannot_be_hidden_by_ordinary_success(self):
        _, result, calls = self.run_phases(shizuku=4)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["ordinary", "setup", "shizuku"], calls)
        self.assertIn("shizuku_exit=4", result.stdout)

    def test_setup_failure_has_no_fictional_shizuku_results(self):
        root, result, calls = self.run_phases(setup=1)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["ordinary", "setup"], calls)
        self.assertFalse((root / "app/build/reports/device-validation/standard/shizuku-results").exists())
        self.assertFalse((root / "app/build/reports/device-validation/standard/shizuku-screenshots").exists())

    def test_16k_reports_preserve_the_actual_page_size(self):
        root, result, calls = self.run_phases(page_size="16384", expected_page="16384", group="16k")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["ordinary", "setup", "shizuku"], calls)
        reports = root / "app/build/reports/device-validation/16k"
        self.assertIn("page_size=16384", (reports / "device-info.txt").read_text())
        self.assertTrue((reports / "ordinary-results/ordinary.txt").is_file())
        self.assertEqual("Earlier image evidence", (root / "app/build/reports/device-validation/other-image/retained.txt").read_text())

    def test_wrong_page_size_is_a_failure_before_test_execution(self):
        _, result, calls = self.run_phases(expected_page="16384")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)
        self.assertIn("Expected 16384-byte pages, found 4096", result.stderr)

    def test_report_group_cannot_escape_generated_report_directory(self):
        _, result, calls = self.run_phases(group="../escape")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_incomplete_boot_stops_before_either_phase(self):
        _, result, calls = self.run_phases(boot="0")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
