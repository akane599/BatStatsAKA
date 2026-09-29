package app.batstats.ui.components.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [barLabelPlan]: bars are never left unlabelled by collision; thinning follows one rule. */
class BarLabelPlanTest {
    private fun widths(count: Int, width: Float) = List(count) { width }

    @Test fun fullLabelsWhenTheyFit() {
        val plan = barLabelPlan(widths(7, 30f), widths(7, 10f), slot = 40f, gap = 6f, selected = null)
        assertFalse(plan.short)
        assertEquals((0..6).toSet(), plan.shown)
    }

    @Test fun aWeekAtLargeFontSwitchesEveryBarToItsShortLabel() {
        // "Tue" no longer fits a 1.5x slot; "T" does: all seven stay labelled.
        val plan = barLabelPlan(widths(7, 45f), widths(7, 15f), slot = 40f, gap = 6f, selected = null)
        assertTrue(plan.short)
        assertEquals((0..6).toSet(), plan.shown)
    }

    @Test fun withoutShortLabelsEveryNthFromTheNewestWithTheOldestAlwaysLabelled() {
        // Stride 3 over 8 bars: 7, 4, 1 from the newest; the oldest takes the place of 1.
        val plan = barLabelPlan(widths(8, 90f), null, slot = 35f, gap = 6f, selected = null)
        assertFalse(plan.short)
        assertEquals(setOf(0, 4, 7), plan.shown)
    }

    @Test fun thirtyDaysThinTheShortLabels() {
        val plan = barLabelPlan(widths(30, 40f), widths(30, 14f), slot = 10f, gap = 6f, selected = null)
        assertTrue(plan.short)
        // Stride 2 (20 / 10): 29, 27, … 1, and the oldest instead of 1.
        assertTrue(0 in plan.shown && 29 in plan.shown && 1 !in plan.shown)
        assertEquals(15, plan.shown.size)
    }

    @Test fun theSelectedBarIsAlwaysLabelledAndItsNeighboursYield() {
        val plan = barLabelPlan(widths(8, 90f), null, slot = 35f, gap = 6f, selected = 5)
        assertTrue(5 in plan.shown)
        assertFalse("Within the stride of the selection", 4 in plan.shown || 7 in plan.shown)
        assertTrue(0 in plan.shown)
    }

    @Test fun noBarsNoLabels() {
        assertEquals(emptySet<Int>(), barLabelPlan(emptyList(), null, slot = 10f, gap = 1f, selected = null).shown)
    }
}
