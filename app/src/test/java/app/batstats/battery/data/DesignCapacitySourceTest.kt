package app.batstats.battery.data

import app.batstats.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesignCapacitySourceTest {
    private val settings = MutableStateFlow(AppSettings())
    private var sysfs: CompletableDeferred<Long?> = answered(null)
    private var reads = 0

    private fun TestScope.source() = DesignCapacitySource(
        settings,
        readChargeFullDesignUah = {
            reads++
            sysfs.await()
        },
        scope = backgroundScope,
    )

    /** Collects [source] in the background and returns its latest reading. */
    private fun TestScope.observe(source: DesignCapacitySource): () -> DesignCapacityReading? {
        var latest: DesignCapacityReading? = null
        backgroundScope.launch { source.design.collect { latest = it } }
        runCurrent()
        return { latest }
    }

    @Test fun theSettingsOverrideWinsAndSysfsIsNeverRead() = runTest {
        settings.value = AppSettings(designCapacityMah = 4_800)
        sysfs = answered(5_000_000)
        val design = observe(source())

        assertEquals(DesignCapacityReading.Known(4_800_000, fromSettings = true), design())
        settings.value = AppSettings(designCapacityMah = 5_200)
        runCurrent()
        assertEquals(DesignCapacityReading.Known(5_200_000, fromSettings = true), design())
        assertEquals(0, reads)
    }

    @Test fun autoReadsSysfsOnceForEveryScreenAndResubscription() = runTest {
        val pending = CompletableDeferred<Long?>()
        sysfs = pending
        val source = source()
        val now = observe(source)
        assertEquals(DesignCapacityReading.Checking, now())

        pending.complete(5_000_000)
        runCurrent()
        assertEquals(DesignCapacityReading.Known(5_000_000, fromSettings = false), now())

        // Health opened later (and reopened): the cached reading, no second read and no "Checking".
        assertEquals(DesignCapacityReading.Known(5_000_000, fromSettings = false), source.design.first())
        assertEquals(DesignCapacityReading.Known(5_000_000, fromSettings = false), source.design.first())
        // Other settings changing doesn't re-read either.
        settings.value = AppSettings(temperatureUnitIndex = 1)
        runCurrent()
        assertEquals(1, reads)
    }

    @Test fun switchingTheOverrideBackToAutoReadsAgain() = runTest {
        sysfs = answered(5_000_000)
        val design = observe(source())
        assertEquals(1, reads)

        settings.value = AppSettings(designCapacityMah = 4_500)
        runCurrent()
        assertEquals(DesignCapacityReading.Known(4_500_000, fromSettings = true), design())
        assertEquals(1, reads)

        sysfs = answered(4_900_000)
        settings.value = AppSettings(designCapacityMah = 0)
        runCurrent()
        assertEquals(DesignCapacityReading.Known(4_900_000, fromSettings = false), design())
        assertEquals(2, reads)
    }

    @Test fun invalidValuesAreRejected() = runTest {
        // 500 mAh is outside the setting's range: read as auto. 5,000 "mAh" in the µAh field is implausible.
        settings.value = AppSettings(designCapacityMah = 500)
        sysfs = answered(5_000)
        val design = observe(source())
        assertEquals(DesignCapacityReading.Unknown, design())
        assertEquals(1, reads)

        // No root: unknown.
        sysfs = answered(null)
        settings.value = AppSettings(designCapacityMah = 4_000)
        runCurrent()
        settings.value = AppSettings(designCapacityMah = 0)
        runCurrent()
        assertEquals(DesignCapacityReading.Unknown, design())
    }

    @Test fun aFailingReadIsUnknown() = runTest {
        val source = DesignCapacitySource(settings, readChargeFullDesignUah = { error("su died") }, scope = backgroundScope)
        val design = observe(source)
        assertEquals(DesignCapacityReading.Unknown, design())
    }

    @Test fun knownNeverStartsTheRootReadButUsesOneHealthMade() = runTest {
        sysfs = answered(5_000_000)
        val source = source()
        var now: DesignCapacityReading? = null
        backgroundScope.launch { source.known.collect { now = it } }
        runCurrent()
        assertEquals(DesignCapacityReading.Unknown, now)
        source.recheck()
        runCurrent()
        assertEquals("Now and a recheck alone never read", 0, reads)

        // Health opens: its read is what Now then shows.
        observe(source)
        assertEquals(1, reads)
        assertEquals(DesignCapacityReading.Known(5_000_000, fromSettings = false), now)

        settings.value = AppSettings(designCapacityMah = 4_800)
        runCurrent()
        assertEquals(DesignCapacityReading.Known(4_800_000, fromSettings = true), now)
        assertEquals(1, reads)
    }

    @Test fun recheckReadsAnUnknownCapacityAgainAndKeepsAKnownOne() = runTest {
        sysfs = answered(null) // The su grant answered too late: no root at the first read.
        val source = source()
        val design = observe(source)
        assertEquals(DesignCapacityReading.Unknown, design())
        assertEquals(1, reads)

        sysfs = answered(5_000_000)
        source.recheck()
        runCurrent()
        assertEquals(DesignCapacityReading.Known(5_000_000, fromSettings = false), design())
        assertEquals(2, reads)

        source.recheck()
        runCurrent()
        assertEquals("A known capacity is not read again", 2, reads)
    }

    private companion object {
        // Not CompletableDeferred(null): that overload takes a parent Job and never completes.
        fun answered(uah: Long?) = CompletableDeferred<Long?>().also { it.complete(uah) }
    }
}
