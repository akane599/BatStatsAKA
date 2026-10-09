package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.stats.EffectSize
import com.akane.voltwise.battery.insights.engine.stats.RobustBaseline
import com.akane.voltwise.battery.insights.engine.stats.TheilSen
import com.akane.voltwise.battery.insights.engine.stats.TimedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightStatsTest {
    @Test fun equalWeightsUseMedianAndMadWithTieInterpolation() {
        val baseline = RobustBaseline.of(listOf(1.0, 2.0, 3.0, 100.0).map { TimedValue(0, it) }, 0, 100.0)!!
        assertEquals(2.5, baseline.median, 0.0)
        assertEquals(1.0, baseline.mad, 0.0)
        assertEquals(3.0, baseline.robustZ(2.5 + 3 * 1.4826, 1.0)!!, 1e-9)
    }

    @Test fun recentValuesGetExponentiallyMoreWeight() {
        val baseline = RobustBaseline.of(listOf(TimedValue(0, 100.0), TimedValue(100, 10.0)), 100, 100.0)!!
        assertEquals(10.0, baseline.median, 0.0)
        assertEquals(0.0, baseline.mad, 0.0)
    }

    @Test fun flatBaselineScoresAnAbsoluteFloorAsThree() {
        val baseline = RobustBaseline.of(List(4) { TimedValue(it.toLong(), 5.0) }, 10, 100.0)!!
        assertEquals(3.0, baseline.robustZ(15.0, 10.0)!!, 1e-9)
        assertEquals(0.0, baseline.robustZ(5.0, 10.0)!!, 0.0)
        assertNull(baseline.robustZ(Double.NaN, 1.0))
        assertNull(baseline.robustZ(10.0, 0.0))
    }

    @Test fun nearFlatBaselineDoesNotAmplifyZComparedWithFlatBaseline() {
        val nearFlat = RobustBaseline.of(listOf(0.0, 0.0, 0.2, 0.25, 0.33).map { TimedValue(0, it) }, 0, 100.0)!!
        val flat = RobustBaseline.of(List(5) { TimedValue(0, 0.0) }, 0, 100.0)!!
        val z = nearFlat.robustZ(35.0, 30.0)!!
        assertTrue("Near-flat z ($z) must not exceed flat z", z <= flat.robustZ(35.0, 30.0)!! + 1e-9)
        assertEquals(3.48, z, 1e-9)
        assertEquals(3.0, nearFlat.robustZ(nearFlat.median + 30.0, 30.0)!!, 1e-9)
    }

    @Test fun wideBaselineStillUsesMadScale() {
        val baseline = RobustBaseline.of(listOf(0.0, 20.0, 40.0, 60.0, 80.0).map { TimedValue(0, it) }, 0, 100.0)!!
        assertEquals(20.0, baseline.mad, 0.0)
        assertTrue(RobustBaseline.MAD_SCALE * baseline.mad > 30.0 / RobustBaseline.Z_THRESHOLD)
        assertEquals((100.0 - baseline.median) / (RobustBaseline.MAD_SCALE * baseline.mad), baseline.robustZ(100.0, 30.0)!!, 1e-9)
    }

    @Test fun emptySingletonNonFiniteAndVeryOldSeriesAreDefined() {
        assertNull(RobustBaseline.of(emptyList(), 0, 1.0))
        assertNull(RobustBaseline.of(listOf(TimedValue(0, Double.NaN)), 0, 1.0))
        val one = RobustBaseline.of(listOf(TimedValue(0, 2.0), TimedValue(1, Double.POSITIVE_INFINITY)), 1, 1.0)!!
        assertEquals(1, one.samples)
        assertEquals(2.0, one.median, 0.0)
        val old = RobustBaseline.of(listOf(TimedValue(0, 2.0), TimedValue(1, 3.0)), Long.MAX_VALUE, 1.0)!!
        assertTrue(old.median.isFinite())
        assertTrue(old.mad.isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidHalfLifeIsRejected() {
        RobustBaseline.of(emptyList(), 0, Double.NaN)
    }

    @Test fun effectSizeComparesMediansNotOutliersOrMeans() {
        val effect = EffectSize.between(listOf(10.0, 20.0, 1_000.0), listOf(20.0, 40.0, 60.0))!!
        assertEquals(20.0, effect.absolute, 0.0)
        assertEquals(1.0, effect.relative!!, 0.0)
        assertEquals(-0.5, EffectSize.between(listOf(40.0), listOf(20.0))!!.relative!!, 0.0)
    }

    @Test fun effectSizeDoesNotManufactureInfiniteRelativeChange() {
        assertNull(EffectSize.between(emptyList(), listOf(1.0)))
        assertNull(EffectSize.between(listOf(Double.NaN), listOf(1.0)))
        assertNull(EffectSize.between(listOf(0.0), listOf(10.0))!!.relative)
        assertEquals(0.0, EffectSize.between(listOf(0.0), listOf(0.0))!!.relative!!, 0.0)
    }

    @Test fun theilSenIsMedianPairwiseSlopeWithOutlierResistance() {
        val points = listOf(0.0, 2.0, 4.0, 6.0, 100.0).mapIndexed { index, value -> TimedValue(index.toLong(), value) }
        assertEquals(2.0, TheilSen.slope(points)!!, 0.0)
        assertEquals(2.0, TheilSen.slope(points.reversed())!!, 0.0)
        assertEquals(-2.0, TheilSen.slope(points.map { it.copy(value = -it.value) })!!, 0.0)
    }

    @Test fun theilSenHandlesMissingPairsTiesAndNonFiniteValues() {
        assertNull(TheilSen.slope(emptyList()))
        assertNull(TheilSen.slope(listOf(TimedValue(1, 2.0))))
        assertNull(TheilSen.slope(listOf(TimedValue(1, 2.0), TimedValue(1, 3.0))))
        assertEquals(0.0, TheilSen.slope(listOf(TimedValue(0, 2.0), TimedValue(1, 2.0), TimedValue(2, Double.NaN)))!!, 0.0)
    }
}
