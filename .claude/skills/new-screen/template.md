# new-screen templates

Replace `Xxx`/`xxx`. Imports shown are the non-obvious ones; let the compiler tell you the rest.
State lives in the ViewModel as a nested `Ui` class (the `HistoryViewModel.Ui` pattern), so `viewmodel/` never
imports from `ui/`. If the wrapper instead combines several flows (as `DashboardScreen` does), declare an
`@Immutable data class XxxUiState` in the screen file and build it in the wrapper.

## `ui/screens/XxxScreen.kt`

```kotlin
package app.batstats.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.viewmodel.XxxViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun XxxScreen(onBack: () -> Unit, vm: XxxViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    XxxContent(
        ui = ui,
        onBack = onBack,
        onRefresh = vm::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XxxContent(
    ui: XxxViewModel.Ui,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.xxx_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Stateless UI only: no koin, Intents, system services, clocks.
        }
    }
}
```

## `viewmodel/XxxViewModel.kt` (only if the screen has data)

```kotlin
package app.batstats.viewmodel

class XxxViewModel(private val source: XxxSource) : ViewModel() {
    /** Everything [app.batstats.ui.screens.XxxContent] renders; plain values, so screenshot tests can build it. */
    @Immutable
    data class Ui(
        val loading: Boolean = false,
        val items: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui(loading = true))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch { /* load via source, then _ui.update { … } */ }
    }
}
```
Register in `di/AppModules.kt`: `viewModel { XxxViewModel(get()) }`.

## `src/test/java/app/batstats/viewmodel/XxxViewModelTest.kt`

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class XxxViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `refresh publishes loaded items`() = runTest(dispatcher) {
        val vm = XxxViewModel(FakeXxxSource(listOf("a", "b")))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.ui.value.items)
    }
}
```

## `ui/NavGraph.kt`

```kotlin
// in sealed interface Screen
@Serializable
data object Xxx : Screen

// in entryProvider { … }
entry<Screen.Xxx> {
    XxxScreen(onBack = popBack)
}

// in the caller's entry
onOpenXxx = { backStack.add(Screen.Xxx) },
```

## `src/screenshotTest/kotlin/app/batstats/ui/screens/XxxScreenshotTest.kt`

```kotlin
package app.batstats.ui.screens

import androidx.compose.runtime.Composable
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import app.batstats.viewmodel.XxxViewModel
import com.android.tools.screenshot.PreviewTest

// Any timestamps: FIXED_TIME_MS ± offsets (import app.batstats.ui.FIXED_TIME_MS), never the real clock.
private val populated = XxxViewModel.Ui(items = listOf("First", "Second", "Third"))

@Composable
private fun XxxPreviewContent(ui: XxxViewModel.Ui) {
    XxxContent(ui = ui, onBack = {}, onRefresh = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun XxxScreenPreview() {
    ScreenshotTheme { XxxPreviewContent(populated) }
}

@PreviewTest
@PhonePreview
@Composable
fun XxxScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) { XxxPreviewContent(populated) }
}

@PreviewTest
@PhonePreview
@Composable
fun XxxScreenEmptyPreview() {
    ScreenshotTheme { XxxPreviewContent(XxxViewModel.Ui()) }
}
```
Use `@TallPhonePreview` instead of `@PhonePreview` when the distinguishing content of a secondary state sits below 500 dp.
