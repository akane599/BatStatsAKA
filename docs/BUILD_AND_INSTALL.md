# Build and install BatStats

## Download a build from a phone

After the workflow is approved and merged into the default branch:

1. Open this repository in a signed-in mobile browser. Select **Actions → Build installable APK → Run workflow**. Desktop-site mode may help if GitHub hides the control.
2. Select the development branch and keep Android 16 tests enabled. Start the workflow; it builds locally on GitHub’s runner without publishing a release or changing repository files.
3. Wait for a successful run. Open **Artifacts → installable-apks** and extract the downloaded ZIP using your phone’s file manager. Install the universal **preview** APK. Android may ask you to allow installation from that browser or file manager.
4. The artifact includes checksums, public signing fingerprints and `build-info.json`. Test/lint reports are a separate artifact, including on failed runs. Artifacts expire after14 days.

GitHub requires a `workflow_dispatch` file on the default branch before offering **Run workflow**; merely pushing a new workflow on a development branch does not register that button. See [GitHub’s manual-run documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

The APK workflow has read-only repository permissions and does not create releases, tags, commits or PRs. Push/PR CI now calls this same workflow with Android16 tests required, including the ordinary and real Shizuku phases. Publishing this branch and opening a PR automatically starts these checks and uploads APK/test artifacts; the push and PR events can each start a run. Explicit approval is required before these remote actions.

Push/PR CI passes no repository signing secrets and uses an ephemeral development certificate. The directly launched phone workflow can use the optional stable Preview secrets described below. This keeps CI test code separate from stable signing credentials. The manually launched workflow still needs to reach the default branch before its Run workflow button appears.

The separate **Android App Releases** workflow publishes only when **Upload releases** is explicitly selected. It delegates to an external release workflow that can push version/changelog commits and publish a release; it is not the phone download workflow. Its defaults now disable publication, Play upload and version bumping, and mark an explicitly published build as a prerelease.

## Signing and updates

| Build | Package | Signing and compatibility |
| --- | --- | --- |
| Supplied6.2.6 | `org.mlm.batstats` | Original release certificate; its private key is unavailable here. |
| Debug | `org.mlm.batstats.debug` | Development certificate; debuggable. Installs alongside the supplied app. |
| Preview | `org.mlm.batstats.preview` | Optimized, nondebuggable build. Uses an optional preview key, otherwise the local/runner development certificate. Installs alongside both other packages. |

An in-place update requires the same package, a compatible signing certificate and an acceptable version code. This build cannot update the supplied APK without its original signing key. Its original signerSHA256 is `4aed2f691df64a7b0fea25a6b8c80183c6dc520e049dac0178defa1d6472228f`.

Temporary GitHub runners usually generate different development keys. Therefore, APKs from separate runs **may not update each other**, even if both are named Preview. The APK-dist plugin applies ABI-specific version-code offsets; the universal APK uses the source code plus1. Use the actual version code in `build-info.json`. Compare `signerSha256` in that file; never uninstall a history-bearing installation before deliberately exporting its data. Separate packages do not share preferences, grants or history. Legacy exports may require conversion; only formats accepted by the import validator can be imported.

For repeatable preview updates, a maintainer can configure four repository secrets: `PREVIEW_KEYSTORE_BASE64`, `PREVIEW_STORE_PASSWORD`, `PREVIEW_KEY_ALIAS`, `PREVIEW_KEY_PASSWORD`. Secret changes require separate approval. The workflow decodes the keystore only into runner temporary storage and deletes it afterward; artifacts never contain keys. Partial signing configuration fails instead of silently changing certificates. Production signing remains separate.

## Local build

Use JDK21 and Android SDK37 with build-tools36.0.0. Set `ANDROID_HOME` or untracked `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :app:testPreviewUnitTest :app:lintDebug :app:lintPreview
./gradlew :app:assembleDebug :app:assemblePreview :app:assembleDebugAndroidTest
python3 scripts/collect_apks.py
```

On memory-constrained hosts, add `--no-daemon -Dorg.gradle.jvmargs=-Xmx1024m --max-workers=1` and stop the emulator while building. Do not edit sources during Kotlin/KSP compilation.

Local stable preview signing uses `PREVIEW_KEYSTORE_PATH`, `PREVIEW_STORE_PASSWORD`, `PREVIEW_KEY_ALIAS`, `PREVIEW_KEY_PASSWORD`. Keep the file outside the repository. `assembleRelease` requires the original `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; preview signing never supplies production credentials.

Actual results and hardware limits are recorded in [VALIDATION.md](VALIDATION.md). Android emulator success does not validate Samsung current calibration, battery capacity or physical monitoring overhead.

## Android device checks

Use a disposable API36 `google_apis` emulator (not ATD: notification review needs SystemUI). Set `ANDROID_SERIAL=emulator-5554`, then run `bash scripts/check_android_device.sh`. It runs ordinary checks, installs checksum-pinned official Shizuku13.6 as shell UID2000, then runs real authorization/helper restart/access loss/reconnection checks. The tests change simulated battery and font settings and restore them afterward; never run these tests on a personal device. Reports and screenshots are saved under `app/build/reports/device-validation/`. Each phase must pass; an unavailable Shizuku service fails its suite.

On constrained hosts build APKs first and use `adb install`/`am instrument` separately from Gradle so the build JVM and emulator do not compete for memory. Actual execution status is in VALIDATION.md; compiled tests alone do not establish compatibility.
