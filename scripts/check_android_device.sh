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
rm -rf app/build/outputs/connected_android_test_additional_output
mkdir -p app/build/reports/device-validation
adb shell rm -rf /sdcard/Download/batstats-validation-screenshots
batstats_phase=ordinary
collect_screenshots() {
  adb pull /sdcard/Download/batstats-validation-screenshots app/build/reports/device-validation/ >/dev/null 2>&1 || true
  # AGP can uninstall the app before this shell regains control. Its additional-output
  # collector copies screenshots before uninstalling, including after failed tests.
  local collected=app/build/outputs/connected_android_test_additional_output
  if [[ -d "$collected" ]]; then
    mkdir -p "app/build/reports/device-validation/${batstats_phase}-screenshots"
    cp -R "$collected"/. "app/build/reports/device-validation/${batstats_phase}-screenshots/"
  fi
}
trap collect_screenshots EXIT
run_prebuilt_phase() {
  local phase="$1"
  shift
  local report="app/build/reports/device-validation/${phase}-instrumentation.txt"
  adb shell am instrument -w -r "$@" \
    org.mlm.batstats.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$report" || return 1
  # adb can exit successfully even when the test runner crashed or assertions failed.
  python3 scripts/check_instrumentation_result.py "$report"
}
run_gradle_phase() {
  local result=0
  # Save each phase before AGP replaces its output with the following phase's results.
  rm -rf app/build/outputs/androidTest-results app/build/reports/androidTests \
    app/build/outputs/connected_android_test_additional_output
  ./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Download/batstats-validation-screenshots \
    "$@" || result=$?
  if [[ -d app/build/outputs/androidTest-results ]]; then
    cp -R app/build/outputs/androidTest-results "app/build/reports/device-validation/${batstats_phase}-results" || return 1
  fi
  if [[ -d app/build/reports/androidTests ]]; then
    cp -R app/build/reports/androidTests "app/build/reports/device-validation/${batstats_phase}-html" || return 1
  fi
  collect_screenshots || return 1
  return "$result"
}
batstats_ordinary_result=0
batstats_shizuku_result=0
if "$batstats_prebuilt"; then
  # Build immediately before this command; existing APKs are intentionally not rebuilt here.
  adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  run_prebuilt_phase ordinary -e notAnnotation app.batstats.test.RequiresShizuku || batstats_ordinary_result=$?
else
  run_gradle_phase -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.batstats.test.RequiresShizuku || batstats_ordinary_result=$?
fi
collect_screenshots
# Collect independent Shizuku evidence even if ordinary assertions failed. Both remain required.
rm -rf app/build/outputs/connected_android_test_additional_output
adb shell rm -rf /sdcard/Download/batstats-validation-screenshots
batstats_phase=shizuku
if python3 scripts/prepare_shizuku.py --serial "$batstats_serial"; then
  if "$batstats_prebuilt"; then
    run_prebuilt_phase shizuku -e class app.batstats.battery.shizuku.ShizukuDeviceTest || batstats_shizuku_result=$?
  else
    run_gradle_phase -Pandroid.testInstrumentationRunnerArguments.class=app.batstats.battery.shizuku.ShizukuDeviceTest || batstats_shizuku_result=$?
  fi
else
  batstats_shizuku_result=1
  echo 'Shizuku setup failed; integration assertions could not run.' >&2
fi
printf 'ordinary_exit=%s\nshizuku_exit=%s\n' "$batstats_ordinary_result" "$batstats_shizuku_result" \
  | tee app/build/reports/device-validation/phase-status.txt
[[ "$batstats_ordinary_result" == 0 && "$batstats_shizuku_result" == 0 ]]
