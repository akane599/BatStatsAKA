package app.batstats.viewmodel

import app.batstats.battery.data.CalibrationOverrides
import app.batstats.battery.data.CalibrationStore
import app.batstats.battery.data.sampling.FakeKeyValueStore
import app.batstats.battery.measurement.CalibrationSource
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.CurrentSign
import app.batstats.battery.measurement.CurrentUnit
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import app.batstats.settings.CurrentSignOverride
import app.batstats.settings.CurrentUnitOverride
import app.batstats.settings.RETENTION_FOREVER_INDEX
import app.batstats.settings.StatusIconValue
import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.TextInput
import io.github.mlmgames.settings.core.types.Toggle
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeSettingsStore()

    /** The calibration store's own preferences; "detected" is its persisted detection (unit/sign/windows). */
    private val calibrationPrefs = FakeKeyValueStore(mapOf("detected" to "MILLIAMPS/INVERTED/4"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.start(): Pair<SettingsViewModel, () -> SettingsUiState> {
        val calibration = CalibrationStore(calibrationPrefs, flowOf(CalibrationOverrides()), backgroundScope)
        val vm = SettingsViewModel(store, calibration)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm to { vm.state.value }
    }

    private fun TestScope.send(vm: SettingsViewModel, vararg events: SettingsEvent) {
        events.forEach(vm::onEvent)
        runCurrent()
    }

    @Test fun stateCombinesTheSettingsAndTheDetectedCalibration() = runTest(dispatcher) {
        val (_, state) = start()

        with(state()) {
            assertEquals(AppSettings(), settings)
            assertEquals(CurrentCalibration(CurrentUnit.MILLIAMPS, CurrentSign.INVERTED), calibration.detected)
            assertEquals(CalibrationSource.DETECTED, calibration.source)
            assertEquals(4, calibration.agreeingWindows)
            assertNull(error)
        }
        store.flow.value = AppSettings(oledBlack = true)
        runCurrent()
        assertTrue(state().settings.oledBlack)
    }

    @Test fun switchesChoicesAndThresholdsAreStoredAsTheSchemaTypesThem() = runTest(dispatcher) {
        val (vm, state) = start()

        send(
            vm,
            SettingsEvent.SetSwitch(SettingsSwitch.LOW_BATTERY_ALERT, false),
            SettingsEvent.SetSwitch(SettingsSwitch.DYNAMIC_COLORS, true),
            SettingsEvent.SetChoice(SettingsChoice.STATUS_ICON, 2),
            SettingsEvent.SetChoice(SettingsChoice.CURRENT_UNIT, 2),
            SettingsEvent.SetChoice(SettingsChoice.CURRENT_SIGN, 2),
            SettingsEvent.SetChoice(SettingsChoice.TEMPERATURE_UNIT, 1),
            SettingsEvent.SetChoice(SettingsChoice.RETENTION, RETENTION_FOREVER_INDEX),
            // Snapped to the schema's step from the range start, clamped to the range (the temperature only clamped).
            SettingsEvent.SetThreshold(SettingsThreshold.LOW_BATTERY, 22.4f),
            SettingsEvent.SetThreshold(SettingsThreshold.HIGH_BATTERY, 10f),
            SettingsEvent.SetThreshold(SettingsThreshold.TEMPERATURE, 47.6f),
            SettingsEvent.SetThreshold(SettingsThreshold.DISCHARGE_CURRENT, 5_000f),
        )

        assertEquals(
            listOf(
                "lowBatteryAlertEnabled" to false,
                "dynamicColors" to true,
                "statusIconValue" to StatusIconValue.POWER_W,
                "currentUnitOverride" to CurrentUnitOverride.MILLIAMPS,
                "currentSignOverride" to CurrentSignOverride.INVERTED,
                "temperatureUnitIndex" to 1,
                "dataRetentionIndex" to RETENTION_FOREVER_INDEX,
                "lowBatteryThreshold" to 20,
                "highBatteryThreshold" to 50,
                "temperatureThreshold" to 47.6f,
                "dischargeCurrentThreshold" to 2_000,
            ),
            store.writes,
        )
        assertEquals(
            AppSettings(
                lowBatteryAlertEnabled = false,
                dynamicColors = true,
                statusIconValue = StatusIconValue.POWER_W,
                currentUnitOverride = CurrentUnitOverride.MILLIAMPS,
                currentSignOverride = CurrentSignOverride.INVERTED,
                temperatureUnitIndex = 1,
                dataRetentionIndex = RETENTION_FOREVER_INDEX,
                lowBatteryThreshold = 20,
                highBatteryThreshold = 50,
                temperatureThreshold = 47.6f,
                dischargeCurrentThreshold = 2_000,
            ),
            state().settings,
        )
        assertNull(state().error)
    }

    @Test fun anOptionOutsideTheSchemaIsRejectedWithoutWriting() = runTest(dispatcher) {
        val (vm, state) = start()

        send(vm, SettingsEvent.SetChoice(SettingsChoice.RETENTION, RETENTION_FOREVER_INDEX + 1))
        assertEquals(SettingsError.WRITE_FAILED, state().error)
        send(vm, SettingsEvent.DismissError, SettingsEvent.SetChoice(SettingsChoice.STATUS_ICON, -1))
        assertEquals(SettingsError.WRITE_FAILED, state().error)
        send(vm, SettingsEvent.SetThreshold(SettingsThreshold.TEMPERATURE, Float.NaN))
        assertEquals(SettingsError.WRITE_FAILED, state().error)
        assertEquals(emptyList<Pair<String, Any>>(), store.writes)
    }

    @Test fun designCapacityTakesAutoOrTheRangeOnly() = runTest(dispatcher) {
        val (vm, state) = start()

        listOf(999, 30_001, -1).forEach { mAh ->
            send(vm, SettingsEvent.SetDesignCapacity(mAh))
            assertEquals("$mAh", SettingsError.INVALID_DESIGN_CAPACITY, state().error)
        }
        assertEquals(emptyList<Pair<String, Any>>(), store.writes)

        send(vm, SettingsEvent.SetDesignCapacity(4_500))
        assertNull(state().error)
        assertEquals(4_500, state().settings.designCapacityMah)
        send(vm, SettingsEvent.SetDesignCapacity(1_000), SettingsEvent.SetDesignCapacity(30_000), SettingsEvent.SetDesignCapacity(0))
        assertEquals(listOf(4_500, 1_000, 30_000, 0), store.writes.map { it.second })
        assertEquals(0, state().settings.designCapacityMah)
    }

    @Test fun aFailedWriteShowsUntilDismissedOrTheNextWriteSucceeds() = runTest(dispatcher) {
        val (vm, state) = start()
        store.fail = true

        send(vm, SettingsEvent.SetSwitch(SettingsSwitch.OLED_BLACK, true))
        assertEquals(SettingsError.WRITE_FAILED, state().error)
        assertFalse(state().settings.oledBlack)
        send(vm, SettingsEvent.DismissError)
        assertNull(state().error)

        send(vm, SettingsEvent.SetSwitch(SettingsSwitch.OLED_BLACK, true))
        assertEquals(SettingsError.WRITE_FAILED, state().error)
        store.fail = false
        send(vm, SettingsEvent.SetSwitch(SettingsSwitch.OLED_BLACK, true))
        assertNull(state().error)
        assertTrue(state().settings.oledBlack)
    }

    @Test fun aWholeFahrenheitTemperatureRoundTripsThroughTheStoredCelsius() = runTest(dispatcher) {
        val (vm, state) = start()

        // The °F slider sends 101 °F as °C; the store keeps °C, read back it is 101 °F again (not 38 °C = 100 °F).
        send(vm, SettingsEvent.SetThreshold(SettingsThreshold.TEMPERATURE, (101 - 32) * 5f / 9))
        assertEquals(101, (state().settings.temperatureThreshold * 9 / 5 + 32).roundToInt())
        // Clamped to the schema's °C range, never snapped to its 1 °C step.
        send(vm, SettingsEvent.SetThreshold(SettingsThreshold.TEMPERATURE, 70f))
        assertEquals(55f, state().settings.temperatureThreshold)
        send(vm, SettingsEvent.SetThreshold(SettingsThreshold.TEMPERATURE, 12f))
        assertEquals(35f, state().settings.temperatureThreshold)
    }

    @Test fun resetCalibrationForgetsTheDetectionAndKeepsTheSettings() = runTest(dispatcher) {
        store.flow.value = AppSettings(currentUnitOverride = CurrentUnitOverride.MILLIAMPS, designCapacityMah = 4_500)
        val (vm, state) = start()

        send(vm, SettingsEvent.ResetCalibration)

        with(state().calibration) {
            assertNull(detected)
            assertEquals(0, agreeingWindows)
            assertFalse(noticePending)
        }
        assertNull(calibrationPrefs.values["detected"])
        assertEquals(emptyList<Pair<String, Any>>(), store.writes)
        assertEquals(AppSettings(currentUnitOverride = CurrentUnitOverride.MILLIAMPS, designCapacityMah = 4_500), state().settings)
    }

    @Test fun navigationEventsAreLeftToTheScreen() = runTest(dispatcher) {
        val (vm, state) = start()
        val before = state()

        send(vm, SettingsEvent.OpenAlertSound, SettingsEvent.OpenData, SettingsEvent.OpenStatus)

        assertEquals(before, state())
        assertEquals(emptyList<Pair<String, Any>>(), store.writes)
    }

    /** The screen's rows cover every v3 setting exactly once, each by a schema field of the matching kind. */
    @Test fun everySettingHasOneRowOfTheSchemaKind() {
        val defaults = AppSettings()
        val covered = mutableListOf<String>()
        fun field(name: String) = AppSettingsSchema.fields.first { it.name == name }.also { covered += name }

        SettingsSwitch.entries.forEach { setting ->
            val field = field(setting.fieldName)
            assertEquals(setting.name, Toggle::class, field.meta?.type)
            assertEquals(setting.name, field.get(defaults), setting.isOn(defaults))
        }
        SettingsChoice.entries.forEach { setting ->
            val field = field(setting.fieldName)
            assertEquals(setting.name, Dropdown::class, field.meta?.type)
            assertEquals(setting.name, field.meta?.options?.size, setting.optionCount)
            assertTrue(setting.name, setting.selectedIndex(defaults) in 0 until setting.optionCount)
        }
        SettingsThreshold.entries.forEach { setting ->
            val field = field(setting.fieldName)
            assertEquals(setting.name, Slider::class, field.meta?.type)
            assertEquals(setting.name, (field.get(defaults) as Number).toFloat(), setting.value(defaults), 0f)
            assertEquals(setting.name, field.get(defaults), setting.stored(setting.value(defaults)))
            assertTrue(setting.name, setting.value(defaults) in setting.range)
            assertEquals(setting.alert.name, Toggle::class, AppSettingsSchema.fields.first { it.name == setting.alert.fieldName }.meta?.type)
        }
        assertEquals(TextInput::class, field("designCapacityMah").meta?.type)

        assertEquals(covered.size, covered.toSet().size)
        assertEquals(AppSettingsSchema.fields.filter { it.meta != null }.map { it.name }.toSet(), covered.toSet())
    }
}

/** Applies writes through the schema field, so a value of the wrong type fails as it would in kmp-settings. */
private class FakeSettingsStore : SettingsStore {
    val flow = MutableStateFlow(AppSettings())
    val writes = mutableListOf<Pair<String, Any>>()
    var fail = false

    override val settings: Flow<AppSettings> = flow

    override suspend fun set(fieldName: String, value: Any) {
        if (fail) throw IOException("disk full")
        @Suppress("UNCHECKED_CAST")
        val field = AppSettingsSchema.fields.first { it.name == fieldName } as SettingField<AppSettings, Any>
        flow.value = field.set(flow.value, value)
        writes += fieldName to value
    }
}
