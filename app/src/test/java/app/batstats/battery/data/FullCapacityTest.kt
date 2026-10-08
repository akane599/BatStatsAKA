package app.batstats.battery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FullCapacityTest {
    @Test fun resolvesCounterFirstThenStoredEstimate() {
        data class Case(val name: String, val counter: Long?, val level: Int?, val stored: Long?, val expected: Long?)
        val cases = listOf(
            Case("valid counter wins", 2_800_000, 70, 5_000_000, 4_000_000),
            Case("integer division", 2_800_001, 67, null, 4_179_105),
            Case("below ten percent", 400_000, 9, 5_000_000, 5_000_000),
            Case("ten percent allowed", 400_000, 10, 5_000_000, 4_000_000),
            Case("implausibly small counter", 10_000, 100, 5_000_000, 5_000_000),
            Case("implausibly large counter", 50_000_001, 100, 5_000_000, 5_000_000),
            Case("lower plausible bound", 300_000, 100, null, 300_000),
            Case("upper plausible bound", 50_000_000, 100, null, 50_000_000),
            Case("missing level", 2_800_000, null, 5_000_000, 5_000_000),
            Case("missing counter", null, 70, 5_000_000, 5_000_000),
            Case("nothing known", null, null, null, null),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, resolveFullUah(case.counter, case.level, case.stored))
        }
    }
}
