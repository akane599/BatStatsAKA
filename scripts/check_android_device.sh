#!/usr/bin/env bash
# Run only against a disposable Android16 emulator. Screenshots contain simulated test data.
set -euo pipefail
batstats_prebuilt=false
case "${1:-}" in
  '') ;;
  --prebuilt) batstats_prebuilt=true ;;
  *) echo 'Usage: bash scripts/check_android_device.sh [--prebuilt]' >&2; exit 2 ;;
esac
batstats_serial="${ANDROID_SERIAL:-$(adb get-serialno)}"
[[ "$batstats_serial" == emulator-* ]] || { echo 'Select a disposable emulator with ANDROID_SERIAL.' >&2; exit 1; }
export ANDROID_SERIAL="$batstats_serial"
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 36 ]]
batstats_hardware="$(adb shell getprop ro.hardware | tr -d '\r')"
[[ "$batstats_hardware" == ranchu || "$batstats_hardware" == goldfish ]]
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo 'Android has not completed boot; no device tests were started.' >&2
  exit 1
}
# These are only this script's generated reports and test screenshots. A rerun must
# not inherit old images or nest a previous phase's results inside the new report.
rm -rf app/build/reports/device-validation
mkdir -p app/build/reports/device-validation
adb shell rm -rf /sdcard/Android/data/org.mlm.batstats.debug/files/validation-screenshots
collect_screenshots() {
  adb pull /sdcard/Android/data/org.mlm.batstats.debug/files/validation-screenshots app/build/reports/device-validation/ >/dev/null 2>&1 || true
}
trap collect_screenshots EXIT
run_prebuilt_phase() {
  local phase="$1"
  shift
  local report="app/build/reports/device-validation/${phase}-instrumentation.txt"
  adb shell am instrument -w -r "$@" \
    org.mlm.batstats.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
  # adb can exit successfully even when the test runner crashed or assertions failed.
  python3 scripts/check_instrumentation_result.py "$report"
}
if "$batstats_prebuilt"; then
  # Build immediately before this command; existing APKs are intentionally not rebuilt here.
  adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  run_prebuilt_phase ordinary -e notAnnotation app.batstats.test.RequiresShizuku
  python3 scripts/prepare_shizuku.py --serial "$batstats_serial"
  run_prebuilt_phase shizuku -e class app.batstats.battery.shizuku.ShizukuDeviceTest
  exit 0
fi
# Shizuku is a separate, required second phase; it is never silently treated as an ordinary-mode pass.
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.batstats.test.RequiresShizuku
cp -R app/build/outputs/androidTest-results app/build/reports/device-validation/ordinary-results
cp -R app/build/reports/androidTests app/build/reports/device-validation/ordinary-html
python3 scripts/prepare_shizuku.py --serial "$batstats_serial"
./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=app.batstats.battery.shizuku.ShizukuDeviceTest
cp -R app/build/outputs/androidTest-results app/build/reports/device-validation/shizuku-results
cp -R app/build/reports/androidTests app/build/reports/device-validation/shizuku-html
