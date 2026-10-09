package com.akane.voltwise.battery.data

import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullCapacityTest {
    @Test fun prefersStoredEstimateBelowFiftyPercent() {
        assertEquals(4_000_000L, resolveFullUah(436_000, 10, 4_000_000))
        assertEquals(4_000_000L, resolveFullUah(1_980_000, 49, 4_000_000))
    }

    @Test fun trustsCounterAtFiftyPercent() {
        assertEquals(4_000_000L, resolveFullUah(2_000_000, 50, 3_000_000))
    }

    @Test fun fallsBackToCounterWithoutStoredEstimate() {
        assertEquals(4_000_000L, resolveFullUah(800_000, 20, null))
        assertEquals(4_000_000L, resolveFullUah(400_000, 10, null))
        assertNull(resolveFullUah(360_000, 9, null))
    }

    @Test fun prefersLocalEstimateOverImportedHighConfidence() {
        val imported = session("import:old-phone", "import:observed", 6_000, "HIGH")
        val local = session("local", "observed", 4_000, "LOW")
        assertEquals(4_000_000L, storedFullUah(listOf(imported, local)))
    }

    @Test fun usesImportedEstimateWhenNoLocalEstimateIsUsable() {
        val imported = session("import:backup", "import:observed", 6_000, "HIGH")
        val missing = session("local", "observed", null, null)
        val invalid = session("invalid", "observed", 4_000, "unknown")
        assertEquals(6_000_000L, storedFullUah(listOf(imported, missing, invalid)))
        assertEquals(6_000_000L, storedFullUah(listOf(imported)))
    }

    @Test fun combinesOnlyLocalEstimatesWithExistingConfidenceWeights() {
        val low = session("low", "observed", 4_000, "LOW")
        val high = session("high", "observed", 5_000, "HIGH")
        val imported = session("import:old", "import:observed", 6_000, "HIGH")
        assertEquals(5_000_000L, storedFullUah(listOf(low, high, imported)))
        assertNull(storedFullUah(emptyList()))
    }

    @Test fun recognizesImportSourceAndIdentity() {
        val local = session("local", "observed", 4_000, "LOW")
        val sourceOnly = session("old", "import:observed", 6_000, "HIGH")
        val idOnly = session("import:old", "legacy", 6_000, "HIGH")
        assertEquals(4_000_000L, storedFullUah(listOf(sourceOnly, local)))
        assertEquals(4_000_000L, storedFullUah(listOf(idOnly, local)))
    }

    private fun session(id: String, source: String, mah: Int?, confidence: String?) = ChargeSession(
        sessionId = id,
        type = SessionType.DISCHARGE,
        startTime = 0,
        endTime = 1_000,
        startLevel = 80,
        endLevel = 40,
        deltaUah = null,
        avgCurrentUa = null,
        estCapacityMah = null,
        source = source,
        capacityEstimateMah = mah,
        capacityConfidence = confidence,
        capacityBasis = "COUNTER_SPAN",
    )

    @Test fun resolvesCapacityWithUsabilityAndStoredFallbacks() {
        data class Case(val name: String, val counter: Long?, val level: Int?, val stored: Long?, val expected: Long?)
        val cases = listOf(
            Case("valid counter wins", 2_800_000, 70, 5_000_000, 4_000_000),
            Case("integer division", 2_800_001, 67, null, 4_179_105),
            Case("below ten percent", 400_000, 9, 5_000_000, 5_000_000),
            Case("ten percent prefers stored", 400_000, 10, 5_000_000, 5_000_000),
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
