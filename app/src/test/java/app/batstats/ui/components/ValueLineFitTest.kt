package app.batstats.ui.components

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widths are px at full size: value 100, unit 40, gap 8 unless a test says otherwise. */
class ValueLineFitTest {
    @Test
    fun fitsAtFullSizeWhenThereIsRoom() {
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(100, 40, 8, maxWidth = 148))
    }

    @Test
    fun shrinksValueAndUnitTogetherInSteps() {
        // 0.9 needs 134, 0.8 needs 120.
        assertEquals(ValueLineFit(0.8f, unitBelow = false), fitValueLine(100, 40, 8, maxWidth = 130))
    }

    @Test
    fun wrapsTheUnitWhenEvenTheSmallestStepIsTooWide() {
        // 0.8 on one line needs 120; stacked, the value fits at 0.9.
        assertEquals(ValueLineFit(0.9f, unitBelow = true), fitValueLine(100, 40, 8, maxWidth = 90))
    }

    @Test
    fun stacksRatherThanShrinkingAValueBelowEightyPercent() {
        // 0.7 on one line would just fit (106), but stacked the value keeps its full size.
        assertEquals(ValueLineFit(1f, unitBelow = true), fitValueLine(100, 40, 8, maxWidth = 106))
    }

    @Test
    fun aLoneValueStepsDownToSeventyPercent() {
        assertEquals(ValueLineFit(0.7f, unitBelow = false), fitValueLine(100, null, 8, maxWidth = 75))
    }

    @Test
    fun aValueTooWideForEveryStepShrinksToFitInsteadOfBeingCut() {
        val stacked = fitValueLine(200, 40, 8, maxWidth = 100)
        assertTrue(stacked.unitBelow)
        assertEquals(0.5f, stacked.scale, 1e-6f)
        val noUnit = fitValueLine(200, null, 8, maxWidth = 100)
        assertFalse(noUnit.unitBelow)
        assertEquals(0.5f, noUnit.scale, 1e-6f)
    }

    @Test
    fun unboundedWidthKeepsFullSize() {
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(10_000, 40, 8, Constraints.Infinity))
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(10_000, 40, 8, Constraints.Infinity, templateWidth = 100))
    }

    @Test
    fun aTemplatePinsScaleAndPlacementForEveryLiveValueThatFitsIt() {
        // The template (100 wide, e.g. "−8,888") fits beside its unit at 80 %; every live value keeps that.
        val pinned = ValueLineFit(0.8f, unitBelow = false)
        for (live in listOf(40, 60, 80, 100)) assertEquals(pinned, fitValueLine(live, 40, 8, maxWidth = 130, templateWidth = 100))
        // Without it a short value renders larger: the readout would change size as its digits change.
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(60, 40, 8, maxWidth = 130))
    }

    @Test
    fun aTemplateKeepsAStackedUnitStackedSoTheHeightStays() {
        // The template needs its unit below at 90 %; a short live value alone would fit inline (a shorter cell).
        val pinned = ValueLineFit(0.9f, unitBelow = true)
        assertEquals(pinned, fitValueLine(50, 40, 8, maxWidth = 90, templateWidth = 100))
        assertEquals(pinned, fitValueLine(100, 40, 8, maxWidth = 90, templateWidth = 100))
        assertEquals(ValueLineFit(0.9f, unitBelow = false), fitValueLine(50, 40, 8, maxWidth = 90))
    }

    @Test
    fun aValueWiderThanItsTemplateStillFits() {
        // Within the template fit's slack (129 of 130) nothing changes...
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(81, 40, 8, maxWidth = 130, templateWidth = 80))
        // ...beyond it the value gets its own fit instead of overflowing: here its unit stacks...
        assertEquals(ValueLineFit(1f, unitBelow = true), fitValueLine(120, 40, 8, maxWidth = 130, templateWidth = 80))
        // ...and far beyond every step it shrinks to fit exactly.
        val huge = fitValueLine(260, 40, 8, maxWidth = 130, templateWidth = 80)
        assertTrue(huge.unitBelow)
        assertEquals(0.5f, huge.scale, 1e-6f)
    }

    @Test
    fun zeroOrNegativeWidthNeverThrows() {
        assertEquals(0f, fitValueLine(100, 40, 8, maxWidth = 0).scale, 0f)
        assertEquals(0f, fitValueLine(100, null, 8, maxWidth = -5).scale, 0f)
        assertEquals(ValueLineFit(1f, unitBelow = false), fitValueLine(0, null, 8, maxWidth = 0))
    }
}
