# P3a report — 4-tab navigation shell

Worktree: `/home/akane/projects/BatStatsAKA/.claude/worktrees/agent-a6e0ee3cac4db8797`
Branch: `worktree-agent-a6e0ee3cac4db8797` (reset to `2be6ff9` "Merge A1 review fixes" before starting — see Setup note).
Commit: see final report line (created after this file, "P3a: 4-tab shell and deep links").

## Setup note
`git log --oneline -1` initially showed `ff17d6b` (a dependabot merge), not a descendant of
`2be6ff9`. `git status --short` was clean, so per instructions I ran `git reset --hard 2be6ff9`
before starting.

## Route list (`ui/navigation/Routes.kt`)
```kotlin
sealed interface Routes : NavKey {
    data object Now : Routes
    data object History : Routes
    data object Apps : Routes
    data object Settings : Routes

    data class SessionDetails(val sessionId: String) : Routes
    data class AppDetails(val uid: Int, val packageName: String) : Routes
    data object Health : Routes
    data object SettingsData : Routes
    data object SettingsStatus : Routes

    data object DrainStats : Routes // interim only, see below
}
val TOP_LEVEL_TABS: List<Routes> = listOf(Routes.Now, Routes.History, Routes.Apps, Routes.Settings)
```
`Routes.DrainStats` is not in the approved P4 taxonomy. It's kept because Dashboard's existing
"monitor details" button (and the old `open_drain_stats` notification-open flow) was trivial to
keep working by pushing it onto whichever tab is active; it's deleted with the old screens in
P4c. No other old `Screen.*` keys were kept — the old `Screen` sealed interface in `ui/NavGraph.kt`
was deleted outright since nothing outside that file referenced it.

## TopLevelBackStack API (`ui/navigation/TopLevelBackStack.kt`)
```kotlin
class TopLevelBackStack(
    private val backStacks: Map<Routes, NavBackStack<NavKey>>,
    private val getSelectedTab: () -> Routes,
    private val setSelectedTab: (Routes) -> Unit,
) {
    val selectedTab: Routes
    val backStack: NavBackStack<NavKey>              // the visible tab's stack, for NavDisplay
    fun select(tab: Routes)                          // reselect current tab -> pop to root; else switch
    fun navigate(route: NavKey)                       // push onto the currently visible tab
    fun onBack(): Boolean                             // see rules below; false = caller should finish()
}

@Composable fun rememberTopLevelBackStack(): TopLevelBackStack   // 4x rememberNavBackStack + rememberSaveable(Int index)
fun TopLevelBackStack.openDestination(value: String)             // applies a `destination` extra value
```
Rules implemented (unit-tested in `TopLevelBackStackTest`, 12 cases):
- `select(tab)` on the current tab pops that tab's stack to its root (others untouched).
- `select(tab)` on a different tab switches the visible stack; no stack's contents change.
- `onBack()`: pops the visible stack if it has >1 entry (returns `true`); else, if the visible tab
  isn't `Now`, switches to `Now` (returns `true`, that tab's own stack is left as-is so it's there
  next time it's selected); else (on `Now`'s root) returns `false`.
- `navigate(route)` always pushes onto the *currently selected* tab, not a fixed "owning" tab —
  this is what lets interim screens keep their old buttons working (e.g. Dashboard's "diagnostics"
  button pushes `SettingsStatus` onto whichever tab is active, normally `Now`).

The class holds no Compose state itself (selection is read/written via the two lambdas), so
`TopLevelBackStackTest` constructs it directly with `NavBackStack<NavKey>(route)` (a plain
constructor call — no Composable/Robolectric needed) and a local `var` for selection.

**Design risk (flagging for review):** `MainScreen` wraps `NavDisplay` in an extra
`BackHandler(enabled = selectedTab != Now) { onBack() }` to catch "back at a non-Now tab's root"
— a case where `NavDisplay`'s own internal back handling is (per the Navigation 3 "common UI"
recipe docs) disabled because its own back stack has only 1 entry. I could not verify this on
device (see Scope: no device tests this task); the reasoning is documented in
`ui/screens/MainScreen.kt`'s comment. If `/device-check` later shows back doesn't bounce a
non-Now tab root to Now, or double-pops, look here first.

## Deep-link constants (`ui/navigation/Destinations.kt`)
```kotlin
object Destinations {
    const val EXTRA_DESTINATION = "destination"
    const val NOW = "now"; const val HISTORY = "history"; const val APPS = "apps"
    const val SETTINGS = "settings"; const val HEALTH = "health"; const val STATUS = "status"
    fun session(sessionId: String): String        // "session:<id>"
    fun sessionIdOrNull(value: String): String?
}
```
`BatteryMainActivity` reads `intent.getStringExtra(Destinations.EXTRA_DESTINATION)` in `onCreate`
and `onNewIntent`, holds it in a `MutableStateFlow<String?>`, passes it to `MainScreen(destination
= ..., onDestinationHandled = ...)`, which applies it via `topLevelBackStack.openDestination(...)`
inside a `LaunchedEffect(destination)` and then clears the flow + `intent.removeExtra(...)` so it
isn't replayed on rotation — same pattern as the old `openDrain` boolean.

`DrainNotificationManager`'s notification tap now sends `destination=now` (was
`open_drain_stats=true`); per the spec ("Notification tap opens Now") it intentionally no longer
jumps straight into the DrainStats detail screen. Verified no remaining `open_drain_stats`
references anywhere in `app/src`.

## Files changed
- `app/src/main/java/app/batstats/ui/navigation/Routes.kt` (new)
- `app/src/main/java/app/batstats/ui/navigation/TopLevelBackStack.kt` (new)
- `app/src/main/java/app/batstats/ui/navigation/Destinations.kt` (new)
- `app/src/main/java/app/batstats/ui/TestTags.kt` (new): `ROOT`, `TAB_NOW`, `TAB_HISTORY`,
  `TAB_APPS`, `TAB_SETTINGS`
- `app/src/main/java/app/batstats/ui/NavGraph.kt` (rewritten): one shared `entryProvider` for all
  `Routes` keys, rendered against `topLevelBackStack.backStack`; old `Screen` sealed interface
  removed
- `app/src/main/java/app/batstats/ui/screens/MainScreen.kt` (rewritten): `Scaffold` +
  `NavigationBar` below 600dp / `NavigationRail` at/above it (via
  `LocalConfiguration.current.screenWidthDp`, no new dependency), `Modifier.semantics {
  testTagsAsResourceId = true }` + `testTag(TestTags.ROOT)` on the root `Surface`, nav items tagged,
  icons `contentDescription = null` (label text carries the accessible name — this also avoids a
  collision with Dashboard's own settings gear icon, which still uses `contentDescription =
  R.string.settings`)
- `app/src/main/java/app/batstats/battery/BatteryMainActivity.kt`: `openDrain: Boolean` →
  `destination: String?`
- `app/src/main/java/app/batstats/battery/drain/DrainNotificationManager.kt`: extra swap only
- `app/src/main/res/values{,-es,-tr}/strings.xml`: added `tab_now`, `tab_apps`,
  `app_details_placeholder`; reused existing `history`/`settings`/`battery_health` for the other
  tab label and the Health placeholder
- `app/src/test/java/app/batstats/ui/navigation/TopLevelBackStackTest.kt` (new, 12 tests)
- `app/src/androidTest/java/app/batstats/ui/NavigationDeviceTest.kt`: added one new test method;
  the two existing test methods needed **no changes** — they only ever drove the UI through string
  text/contentDescription of Dashboard's retained buttons, which now route through
  `select`/`navigate` instead of stack pushes but land on the same screens with the same visible
  content, so they still exercise real coverage unchanged.

## Interim screen mapping (in `NavGraph.kt`)
Now→DashboardScreen, History→HistoryScreen→SessionDetails→SessionDetailsScreen,
Apps→DetailedStatsScreen, Settings→BatterySettingsScreen→SettingsData→DataScreen,
Settings→SettingsStatus→DiagnosticsScreen, Now→DrainStats→DrainStatsScreen (interim),
AppDetails/Health→one-line `Text` placeholders (`R.string.app_details_placeholder` /
`R.string.battery_health`).

Dashboard's `onOpenAlarms` (previously opened Settings with `initialCategory = "Notifications"`
to auto-scroll) now just does `select(Routes.Settings)` — the initial-category jump is dropped
since the new top-level `Routes.Settings` has no such parameter (it can't, since it's a stable map
key for the tab's whole stack). Accepted regression; P4's real Settings screen isn't scoped to fix
this now.

## Tests + commands + results
```
flock /tmp/batstats-gradle.lock ./gradlew :app:assembleDebug :app:testDebugUnitTest \
  :app:assembleDebugAndroidTest --console=plain -q
```
Exit 0. `grep -E "error:|FAILED|e: |warning: \["` over the log: no matches.

Unit tests: all 30 test classes in `testDebugUnitTest` passed (checked every
`app/build/test-results/testDebugUnitTest/*.xml` for non-zero `failures`/`errors`: none).
`TopLevelBackStackTest`: 12/12 passed (`tests="12" failures="0" errors="0"`).

`:app:assembleDebugAndroidTest` compiled clean, so `NavigationDeviceTest` (including the new
`tabsAreReachableBackReturnsToNowAndRetapPopsToRoot` method) type-checks against the real
`BatteryMainActivity`/`MainScreen`. **Not run on device** per instructions (emulator in use) — the
two pre-existing test methods' behavior and the new method's back/re-tap assertions are unverified
end-to-end; recommend `/device-check` before merge.

## Concerns
1. **BackHandler-vs-NavDisplay interaction is unverified on-device** (see Design risk above) —
   the whole "back on a non-Now root → Now" rule depends on `NavDisplay` disabling its own back
   interception at stack size 1, which I confirmed only from the Navigation 3 recipe docs' prose,
   not by reading its source (no `-sources.jar` available locally) or running on device.
2. Dashboard's own settings gear icon (`onOpenSettings`, `contentDescription = R.string.settings`)
   and Now's bottom-tab icon coexist; I avoided a duplicate-contentDescription test ambiguity by
   giving nav-bar icons `contentDescription = null` (relying on the visible label), but this means
   a screen reader announces the tab twice (icon: nothing, label: "Settings") which is correct
   Material behavior, just flagging since it's a slight change from the rest of the app's icon
   button convention.
3. `onOpenAlarms`'s "jump to Notifications" behavior is dropped (see above) — a real interim
   regression, not just a naming change.
4. Tab-root screens (History, Apps, Settings) keep whatever in-screen "back" button they already
   had (e.g. History's app-bar back arrow); pressing it now jumps to Now (via `popBack` →
   `onBack()`) instead of doing nothing, which is a visible but IMO reasonable behavior given
   nothing else to pop to. Not asked to hide these buttons and didn't.
5. No screenshot test added for the shell (brief said skip unless trivial; the existing helpers
   are per-screen, not shell-level, so skipped).

## Fix round 1 (commit "P3a: rail insets fix")

Reviewer finding (Important): the back-stack design and deep links checked out; the reviewer
verified the `BackHandler`/`NavDisplay` assumption against the nav3 1.1.5 bytecode directly (no
device run needed) — confirmed correct. One issue at `MainScreen.kt:876,908-921`: at ≥600 dp the
`NavigationRail` path double-applied window insets. `Scaffold`'s default `contentWindowInsets`
(systemBarsForVisualComponents, all 4 sides — there's no `bottomBar` in rail mode to consume the
bottom one) already padded the `Row(Modifier.fillMaxSize().padding(padding))`; `NavigationRail`
then applied its own default `windowInsets` (`Vertical + Start`) again on top, producing extra
gaps above/below the rail icons and at the start edge.

**Fix:** `NavigationRail(windowInsets = WindowInsets(0))` — the Row's `padding` from Scaffold
already covers every side the rail needs, so the rail itself now contributes none. Added a comment
at the call site explaining which side owns which inset in this mode. The <600 dp `NavigationBar`
path was untouched (already correct: `bottomBar` consumes only the bottom inset via its own
default, `NavGraph`'s content gets the rest via `padding`).

```
flock /tmp/batstats-gradle.lock ./gradlew :app:assembleDebug \
  :app:testDebugUnitTest --tests app.batstats.ui.navigation.TopLevelBackStackTest \
  --console=plain -q
```
Exit 0. `grep -E "error:|FAILED|e: |warning: \["` over the log: no matches.
`TopLevelBackStackTest`: `tests="12" failures="0" errors="0"` (unaffected by this change; run to
confirm nothing else regressed since navigation logic wasn't touched).

Not re-verified on device (no window-size-class instrumentation exists to catch inset regressions
automatically, and the emulator is in use) — recommend eyeballing the ≥600 dp rail layout in
`/device-check` or a manual resize test before merge.
