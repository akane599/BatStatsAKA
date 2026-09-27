package app.batstats.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.batstats.R
import app.batstats.settings.AppSettings
import app.batstats.ui.PhonePreview
import app.batstats.ui.ScreenPreviews
import app.batstats.ui.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.resources.AndroidStringResourceProvider

// Each category is a header item followed by a section item.
private const val GENERAL_SECTION_ITEM_INDEX = 1
private const val DATA_CATEGORY_ITEM_INDEX = 6

/** Scrolls past the General fields so the inline error below them clears a 500 dp fold. */
private val GeneralErrorScrollOffset = 150.dp

/** Default settings with no-op callbacks; mirrors the app's Koin-provided string provider. */
@Composable
private fun SettingsContentFixture(
    settingsError: String? = null,
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scrollOffsetPx = with(LocalDensity.current) { firstVisibleItemScrollOffset.roundToPx() }
    BatterySettingsContent(
        settings = AppSettings(),
        settingsError = settingsError,
        stringProvider = remember(context) { AndroidStringResourceProvider(context) },
        onBack = {},
        onExportData = {},
        onExportSettings = {},
        onOpenAlertSettings = {},
        onUpdateSetting = { _, _ -> },
        onResetUiSettings = { true },
        onResetAll = { true },
        onImportSettings = { ImportResult.Success(0, 0, emptyList()) },
        onClearHistory = {},
        listState = rememberLazyListState(
            initialFirstVisibleItemIndex = firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = scrollOffsetPx,
        ),
    )
}

@PreviewTest
@ScreenPreviews
@Composable
fun BatterySettingsScreenPreview() {
    ScreenshotTheme {
        SettingsContentFixture()
    }
}

@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenOledPreview() {
    ScreenshotTheme(oledBlack = true) {
        SettingsContentFixture()
    }
}

/** Write failure surfaced by the ViewModel: rendered inline under the General section. */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenErrorPreview() {
    ScreenshotTheme {
        SettingsContentFixture(
            settingsError = stringResource(R.string.settings_write_failed),
            firstVisibleItemIndex = GENERAL_SECTION_ITEM_INDEX,
            firstVisibleItemScrollOffset = GeneralErrorScrollOffset,
        )
    }
}

/** Where `initialCategory = "Data & Export"` lands: the export and clear-data actions. */
@PreviewTest
@PhonePreview
@Composable
fun BatterySettingsScreenDataCategoryPreview() {
    ScreenshotTheme {
        SettingsContentFixture(firstVisibleItemIndex = DATA_CATEGORY_ITEM_INDEX)
    }
}
