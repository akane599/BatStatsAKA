package com.akane.voltwise.ui.components.chart

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BreakdownBarTest {
    private val minutes = ValueFormatter { "${it.toInt()} min" }
    private val itemTemplate = "%1\$s: %2\$s"
    private val emptyText = "No readings yet"

    @Test
    fun unknownPartsAreLeftOutAndNegativesCountAsZero() {
        val parts = listedParts(
            listOf(
                BreakdownSegment("Foreground", 72.0, Color.Red),
                BreakdownSegment("Background", Double.NaN, Color.Green),
                BreakdownSegment("Cached", -3.0, Color.Blue),
                BreakdownSegment("Other", Double.POSITIVE_INFINITY, Color.Gray),
            ),
        )
        assertEquals(listOf("Foreground", "Cached"), parts.map { it.label })
        assertEquals(0.0, parts[1].value, 0.0)
        assertEquals("Foreground: 72 min, Cached: 0 min", breakdownSummary(parts, minutes, itemTemplate, emptyText))
    }

    @Test
    fun noPartsSpeakTheEmptyText() {
        assertEquals(emptyText, breakdownSummary(listedParts(emptyList()), minutes, itemTemplate, emptyText))
        val unknownOnly = listedParts(listOf(BreakdownSegment("Foreground", Double.NaN, Color.Gray)))
        assertEquals(emptyText, breakdownSummary(unknownOnly, minutes, itemTemplate, emptyText))
    }
}
