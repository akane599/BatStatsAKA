package com.akane.voltwise.ui.screens

import androidx.compose.runtime.Composable
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.CapacityConfidence
import com.akane.voltwise.ui.FIXED_TIME_MS
import com.akane.voltwise.ui.ScreenPreviews
import com.akane.voltwise.ui.ScreenshotTheme
import com.akane.voltwise.ui.TallPhonePreview
import com.akane.voltwise.viewmodel.CapacityPoint
import com.akane.voltwise.viewmodel.CycleCountState
import com.akane.voltwise.viewmodel.DesignCapacityState
import com.akane.voltwise.viewmodel.DesignSource
import com.akane.voltwise.viewmodel.HealthFigures
import com.akane.voltwise.viewmodel.HealthUiState
import com.android.tools.screenshot.PreviewTest
import kotlin.math.sin

// Dates render in the suite's pinned UTC/en-US; the newest estimate is this morning (FIXED_TIME_MS, Oct 9).
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/**
 * Five months of estimates, about one every six days: a slow fade from ~4,480 to ~4,230 mAh with session noise;
 * every third a long overnight charge (high confidence), short discharges low.
 */
private val estimates = List(26) { i ->
    val age = (25 - i) * 6 * DAY + (i % 4) * 5 * HOUR
    val charge = i % 3 == 0
    val confidence = when {
        charge -> CapacityConfidence.HIGH
        i % 3 == 1 -> CapacityConfidence.MEDIUM
        else -> CapacityConfidence.LOW
    }
    val noise = if (confidence == CapacityConfidence.LOW) 70 * sin(i * 1.7) else 25 * sin(i * 2.3)
    CapacityPoint(
        sessionId = "session-$i",
        timeMs = FIXED_TIME_MS - age,
        type = if (charge) SessionType.CHARGE else SessionType.DISCHARGE,
        startLevel = if (charge) 18 + i % 5 else 96 - i % 7,
        endLevel = if (charge) 100 else 41 + i % 9 * 3,
        capacityMah = (4_480 - i * 10 + noise).toInt(),
        confidence = confidence,
    )
}

private fun fullData() = HealthUiState(
    loaded = true,
    summary = HealthFigures(capacityMah = 4_230, confidence = CapacityConfidence.HIGH, healthPercent = 94.0),
    design = DesignCapacityState.Known(4_500, DesignSource.SETTINGS),
    cycles = CycleCountState.Count(312),
    estimates = estimates,
)

/** Auto design capacity and no root: the capacity with its confidence, no health %, and the way to Settings. */
private fun noDesignCapacity() = fullData().copy(
    summary = HealthFigures(capacityMah = 4_230, confidence = CapacityConfidence.MEDIUM, healthPercent = null),
    design = DesignCapacityState.Unknown,
)

/** First days: nothing measured yet (no trend, no list), the design not set, a cycle count from Android. */
private fun noEstimates() = HealthUiState(
    loaded = true,
    design = DesignCapacityState.Unknown,
    cycles = CycleCountState.Count(12),
)

/** Android 13: no cycle count (the cell is hidden); root reports the design capacity; one estimate so far. */
private fun apiBelow34() = HealthUiState(
    loaded = true,
    summary = HealthFigures(capacityMah = 4_390, confidence = CapacityConfidence.LOW, healthPercent = 97.6),
    design = DesignCapacityState.Known(4_500, DesignSource.BATTERY),
    cycles = CycleCountState.Unsupported,
    estimates = listOf(
        CapacityPoint("session-1", FIXED_TIME_MS - 2 * HOUR, SessionType.DISCHARGE, 88, 71, 4_390, CapacityConfidence.LOW),
    ),
)

@Composable
private fun HealthPreviewContent(state: HealthUiState) {
    HealthContent(state = state, onBack = {}, onSetDesignCapacity = {}, onOpenSession = {})
}

@PreviewTest
@ScreenPreviews
@Composable
fun HealthScreenPreview() {
    ScreenshotTheme { HealthPreviewContent(fullData()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun HealthScreenNoDesignPreview() {
    ScreenshotTheme { HealthPreviewContent(noDesignCapacity()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun HealthScreenNoEstimatesPreview() {
    ScreenshotTheme { HealthPreviewContent(noEstimates()) }
}

@PreviewTest
@TallPhonePreview
@Composable
fun HealthScreenApiBelow34Preview() {
    ScreenshotTheme { HealthPreviewContent(apiBelow34()) }
}
