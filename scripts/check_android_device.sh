#!/usr/bin/env bash
# Run only against a disposable Android16 emulator. Screenshots contain simulated test data.
set -euo pipefail
batstats_serial="${ANDROID_SERIAL:-$(adb get-serialno)}"
[[ "$batstats_serial" == emulator-* ]] || { echo 'Select a disposable emulator with ANDROID_SERIAL.' >&2; exit 1; }
export ANDROID_SERIAL="$batstats_serial"
[[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" == 36 ]]
mkdir -p app/build/reports/device-validation
collect_screenshots() {
  adb pull /sdcard/Android/data/org.mlm.batstats.debug/files/validation-screenshots app/build/reports/device-validation/ >/dev/null 2>&1 || true
}
trap collect_screenshots EXIT
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
