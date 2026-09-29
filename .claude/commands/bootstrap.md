---
description: Detect this Android project's stack and fill in CLAUDE.md + PROGRESS.md placeholders
allowed-tools: Bash(bash scripts/detect-stack.sh:*), Bash(./gradlew:*), Bash(emulator -list-avds:*), Bash(adb devices:*), Read, Edit, Glob, Grep
---

Fill in every `<PLACEHOLDER>` and the `<!-- STACK:BEGIN -->…<!-- STACK:END -->` block in `CLAUDE.md`, and the header placeholders in `PROGRESS.md`, from what this repository actually contains. Do not guess; if something cannot be determined, write `unknown` and list it under "Open questions" at the end.

## Step 1 — Detect
Run `bash scripts/detect-stack.sh` and read the key=value output. Do not read Gradle files wholesale; the script already extracted what matters. Only open a specific file if a value is `unknown` and one targeted grep would settle it.

## Step 2 — Decide the profile
From the output, pick and record:
- **Language:** `kotlin` / `java` / mixed (state the ratio, e.g. "Kotlin 180 files, Java 40 — new code in Kotlin, don't convert Java files unless asked").
- **UI:** Compose / XML Views (+ViewBinding/DataBinding) / both. This decides which review agent and conventions apply.
- **JDK:** `java_target` is what the build wants; `java_home`/`jdks_installed` is what the machine has. If they disagree, write the exact `JAVA_HOME` path that satisfies the build (e.g. `/usr/lib/jvm/java-21-openjdk-amd64`) and note whether the project pins it in `gradle.properties` (`org.gradle.java.home`).
- **Architecture:** infer from module list and package layout (`ui/domain/data`, `feature-*` modules, single-module, MVVM vs MVI vs MVP). Say "single-module MVVM" rather than inventing layers that don't exist.
- **Design loop (Compose only):** if `screenshot_testing=none` and AGP ≥ 8.5, note in the report that adding `com.android.compose.screenshot` enables the screenshot loop used by `/ui-overhaul` and the `compose-design` skill (offer, don't apply). Record `previews`, `dynamic_color`, `custom_typography`, and `literal_colors_outside_theme` in the STACK block's UI line — a high literal count is the first thing an overhaul fixes.
- **LSP:** Kotlin-primary → the official `kotlin-lsp` plugin (needs JetBrains `kotlin-lsp` on PATH; the fwcd `kotlin-language-server` can't read Kotlin metadata newer than 2.2). Java-primary or mixed with substantial Java → also `jdtls` (`bash scripts/install-jdtls.sh` if `jdtls=missing`).

## Step 3 — Write CLAUDE.md
Replace the STACK block with a compact bulleted summary (≤ 12 lines) and fill every `<…>` placeholder. Rules:
- Real commands only: use the actual app module (`:app` or whatever `app_module` is), the actual `applicationId` and `launcher_activity` for the run command.
- If there is no `androidTest` dir, delete the instrumented-test line rather than leaving a fake one.
- Under **Conventions**, keep only bullets that match the detected stack (delete Compose bullets for an XML app, delete Kotlin-style bullets for a pure-Java app; add `google-java-format`/checkstyle if detected).
- Keep the **Subagent orchestration** and **Token discipline** sections unchanged unless a bullet references a tool the project can't use.
- Keep the whole file under ~90 lines.

## Step 4 — Write PROGRESS.md
Fill `<APP NAME>`, the date, and the `API <NN>` in the audit table using the first AVD found (or `none — create one` if `avds` is empty). Leave task sections empty except a single "Now" item: `Bootstrap complete — verify build command`.

## Step 5 — Verify
Run the build command you just wrote into CLAUDE.md (`--console=plain -q`, failures-only filter). If it fails on JDK mismatch, fix the `JAVA_HOME` line and re-run once. Report the result in one line.

## Step 6 — Report
Print: the detected profile (5–8 bullets), the list of remaining `unknown`s as open questions, and which LSP install command (if any) the user should run next. Do not paste the full files back.
