#!/usr/bin/env bash
# Detects the Android project's stack and prints a compact key=value report.
# Used by /bootstrap. Safe to run anywhere; prints "unknown" for what it can't find.
set -uo pipefail
cd "${1:-.}"

kv() { printf '%s=%s\n' "$1" "${2:-unknown}"; }
first() { head -n1 2>/dev/null; }
has_dep() { grep -rhoE --include='*.gradle' --include='*.gradle.kts' --include='libs.versions.toml' "$1" . 2>/dev/null | first; }

# --- project name / package ---
kv project_name "$(grep -hoE 'rootProject\.name\s*=\s*"[^"]+"' settings.gradle* 2>/dev/null | sed -E 's/.*"([^"]+)"/\1/' | first || basename "$PWD")"
kv app_module "$(grep -hoE 'include\("?:[a-zA-Z0-9_-]+"?\)' settings.gradle* 2>/dev/null | grep -oE ':[a-zA-Z0-9_-]+' | grep -E 'app|mobile' | head -n1 | tr -d ':')"
kv application_id "$(grep -rhoE 'applicationId\s*[=(]?\s*"[^"]+"' --include='*.gradle' --include='*.gradle.kts' . 2>/dev/null | sed -E 's/.*"([^"]+)"/\1/' | first)"
kv namespace "$(grep -rhoE 'namespace\s*[=(]?\s*"[^"]+"' --include='*.gradle' --include='*.gradle.kts' . 2>/dev/null | sed -E 's/.*"([^"]+)"/\1/' | first)"
kv launcher_activity "$(grep -rl 'android.intent.category.LAUNCHER' --include=AndroidManifest.xml . 2>/dev/null | grep -v build/ | head -n1 | xargs -r grep -oE 'android:name="[^"]+"' | grep -iE 'activity|main|launch' | head -n1 | sed -E 's/.*"([^"]+)"/\1/')"

# --- language mix ---
kt=$(find . -path ./build -prune -o -name '*.kt' -print 2>/dev/null | grep -v '/build/' | wc -l)
jv=$(find . -path ./build -prune -o -name '*.java' -print 2>/dev/null | grep -v '/build/' | wc -l)
kv kotlin_files "$kt"; kv java_files "$jv"
if   [ "$kt" -gt 0 ] && [ "$jv" -eq 0 ]; then kv primary_language kotlin
elif [ "$jv" -gt 0 ] && [ "$kt" -eq 0 ]; then kv primary_language java
elif [ "$kt" -ge "$jv" ]; then kv primary_language kotlin-mixed
else kv primary_language java-mixed; fi

# --- UI toolkit ---
compose=$(has_dep 'androidx\.compose|compose-bom|compose\.bom|buildFeatures\s*\{[^}]*compose\s*=\s*true')
xml_layouts=$(find . -path '*/res/layout*' -name '*.xml' 2>/dev/null | grep -v '/build/' | wc -l)
kv compose "$([ -n "$compose" ] && echo yes || echo no)"
kv xml_layouts "$xml_layouts"
kv screenshot_testing "$( { has_dep 'com\.android\.compose\.screenshot|screenshot-validation' >/dev/null && echo compose-preview; } || { has_dep 'paparazzi' >/dev/null && echo paparazzi; } || { has_dep 'roborazzi' >/dev/null && echo roborazzi; } || echo none)"
kv previews "$(grep -rl '@Preview' --include='*.kt' . 2>/dev/null | grep -v '/build/' | wc -l)"
kv dynamic_color "$(grep -rqE 'dynamic(Light|Dark)ColorScheme' --include='*.kt' . 2>/dev/null && echo yes || echo no)"
kv custom_typography "$(grep -rqE 'Typography\s*\(' --include='*.kt' . 2>/dev/null && echo yes || echo no)"
kv literal_colors_outside_theme "$(grep -rE 'Color\(0x' --include='*.kt' . 2>/dev/null | grep -v '/build/' | grep -viE '/theme/' | wc -l)"
kv viewbinding "$(has_dep 'viewBinding\s*=?\s*true' >/dev/null && echo yes || echo no)"
kv databinding "$(has_dep 'dataBinding\s*=?\s*true' >/dev/null && echo yes || echo no)"

# --- toolchain ---
kv java_target "$(grep -rhoE '(JavaVersion\.VERSION_[0-9_]+|jvmToolchain\([0-9]+\)|jvmTarget\s*=?\s*"?[0-9.]+|languageVersion\.set\(JavaLanguageVersion\.of\([0-9]+\)|toolchain\s*\{[^}]*of\([0-9]+\))' --include='*.gradle' --include='*.gradle.kts' . 2>/dev/null | grep -oE '[0-9]{1,2}$|VERSION_1_[0-9]+|VERSION_[0-9]+|\([0-9]+\)' | sed -E 's/VERSION_1_/1./; s/VERSION_//; s/[()]//g; s/^1\.8$/8/' | sort -n | tail -n1)"
kv agp_version "$(grep -hoE '(agp|android-gradle-plugin|androidGradlePlugin)\s*=\s*"[^"]+"' gradle/libs.versions.toml 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | first)"
kv kotlin_version "$(grep -hoE '^kotlin\s*=\s*"[^"]+"' gradle/libs.versions.toml 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | first)"
kv gradle_version "$(grep -oE 'gradle-[0-9.]+' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | grep -oE '[0-9.]+' | first)"
kv compile_sdk "$(grep -rhoE 'compileSdk(Version)?\s*[=(]?\s*[0-9]+' --include='*.gradle' --include='*.gradle.kts' . 2>/dev/null | grep -oE '[0-9]+$' | sort -n | tail -n1)"
kv min_sdk "$(grep -rhoE 'minSdk(Version)?\s*[=(]?\s*[0-9]+' --include='*.gradle' --include='*.gradle.kts' . 2>/dev/null | grep -oE '[0-9]+$' | sort -n | head -n1)"
kv version_catalog "$([ -f gradle/libs.versions.toml ] && echo yes || echo no)"

# --- libraries ---
kv di "$( { has_dep 'dagger\.hilt|hilt-android' >/dev/null && echo hilt; } || { has_dep 'io\.insert-koin|koin-android' >/dev/null && echo koin; } || { has_dep 'com\.google\.dagger:dagger' >/dev/null && echo dagger; } || echo none)"
kv db "$( { has_dep 'androidx\.room' >/dev/null && echo room; } || { has_dep 'sqldelight' >/dev/null && echo sqldelight; } || { has_dep 'io\.realm' >/dev/null && echo realm; } || echo none)"
kv network "$( { has_dep 'retrofit' >/dev/null && echo retrofit; } || { has_dep 'io\.ktor' >/dev/null && echo ktor; } || { has_dep 'okhttp' >/dev/null && echo okhttp; } || { has_dep 'volley' >/dev/null && echo volley; } || echo none)"
kv async "$( { has_dep 'kotlinx-coroutines|kotlinx\.coroutines' >/dev/null && echo coroutines; } || { has_dep 'rxjava|rxandroid' >/dev/null && echo rxjava; } || echo none)"
kv navigation "$( { has_dep 'navigation-compose' >/dev/null && echo compose-navigation; } || { has_dep 'navigation-fragment' >/dev/null && echo fragment-navigation; } || echo none)"
kv firebase "$(has_dep 'com\.google\.firebase' >/dev/null && echo yes || echo no)"
kv lint_tools "$( { has_dep 'detekt' >/dev/null && printf 'detekt '; } ; { has_dep 'ktlint' >/dev/null && printf 'ktlint '; } ; { has_dep 'spotless' >/dev/null && printf 'spotless '; } ; { has_dep 'checkstyle' >/dev/null && printf 'checkstyle '; } ; echo )"

# --- tests ---
kv unit_test_libs "$( { has_dep 'junit\.jupiter|junit5' >/dev/null && printf 'junit5 '; } ; { has_dep 'junit:junit' >/dev/null && printf 'junit4 '; } ; { has_dep 'mockk' >/dev/null && printf 'mockk '; } ; { has_dep 'mockito' >/dev/null && printf 'mockito '; } ; { has_dep 'turbine' >/dev/null && printf 'turbine '; } ; echo )"
kv androidtest_dirs "$(find . -type d -name androidTest 2>/dev/null | grep -v '/build/' | wc -l)"
kv espresso "$(has_dep 'espresso' >/dev/null && echo yes || echo no)"

# --- machine ---
kv java_home "${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java 2>/dev/null || echo /nonexistent)")")" 2>/dev/null)}"
kv java_version "$(java -version 2>&1 | head -n1 | grep -oE '"[^"]+"' | tr -d '"')"
kv jdks_installed "$(ls -d /usr/lib/jvm/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
kv android_home "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
kv avds "$(command -v emulator >/dev/null && emulator -list-avds 2>/dev/null | tr '\n' ' ' || ls "$HOME/.android/avd" 2>/dev/null | grep -oE '[^/]+\.avd' | sed 's/\.avd//' | tr '\n' ' ')"
kv adb_devices "$(command -v adb >/dev/null && adb devices 2>/dev/null | tail -n +2 | awk '{print $1}' | tr '\n' ' ')"
kv kvm "$([ -w /dev/kvm ] && echo yes || echo no)"
kv kotlin_ls "$(command -v kotlin-language-server >/dev/null && echo installed || echo missing)"
kv jdtls "$(command -v jdtls >/dev/null && echo installed || echo missing)"
kv gh_cli "$(command -v gh >/dev/null && echo installed || echo missing)"
kv modules "$(grep -hoE 'include\([^)]*\)' settings.gradle* 2>/dev/null | grep -oE ':[a-zA-Z0-9_:-]+' | tr '\n' ' ')"
