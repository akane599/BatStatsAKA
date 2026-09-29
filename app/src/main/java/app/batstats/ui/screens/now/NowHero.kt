package app.batstats.ui.screens.now

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import app.batstats.R
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.PowerState
import app.batstats.ui.components.InfoSheet
import app.batstats.ui.components.Notice
import app.batstats.ui.components.Panel
import app.batstats.ui.components.headerActionOverhang
import app.batstats.ui.format.currentLocale
import app.batstats.ui.format.durationAnnotated
import app.batstats.ui.format.formatNumber
import app.batstats.ui.format.unitSpan
import app.batstats.ui.format.percentAnnotated
import app.batstats.ui.theme.BatMotion
import app.batstats.ui.theme.batColors
import app.batstats.ui.theme.numericDisplay
import app.batstats.ui.theme.numericHeadline
import app.batstats.ui.theme.spacing
import app.batstats.viewmodel.EtaPending
import app.batstats.viewmodel.HeroState

private const val LEVEL_SEGMENTS = 10
private const val FULL_LEVEL = 100

/** At or below this level on battery, the level bar turns to the heat color (Android's own low-battery point). */
private const val LOW_LEVEL = 15

/**
 * The hero: state and charger, the level with its segmented bar, time left / to full with its basis, and the one
 * Start/Stop button. The level fill is the screen's single orchestrated moment (with the trace draw-in).
 */
@Composable
internal fun NowHero(hero: HeroState, onToggleMonitoring: () -> Unit, modifier: Modifier = Modifier) {
    val color = directionColor(hero.power, hero.level)
    Panel(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateLine(hero, color, Modifier.weight(1f))
            Box(Modifier.headerActionOverhang(MaterialTheme.spacing.sm)) {
                InfoSheet(stringResource(R.string.now_hero_info_title), stringResource(R.string.now_hero_info_body))
            }
        }
        LevelAndEta(hero)
        LevelBar(hero.level, hero.hasReading, color)
        if (hero.startBlocked) Notice(stringResource(R.string.now_start_blocked))
        MonitoringButton(hero.monitoring, onToggleMonitoring, Modifier.fillMaxWidth())
    }
}

/** Energy direction: in (charge), out (drain), critical (heat); neutral when Android doesn't say. */
@Composable
internal fun directionColor(power: PowerState, level: Int?): Color = when (power) {
    PowerState.CHARGING, PowerState.PLUGGED -> MaterialTheme.batColors.charge
    PowerState.DISCHARGING -> if (level != null && level <= LOW_LEVEL) MaterialTheme.batColors.heat else MaterialTheme.batColors.drain
    PowerState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StateLine(hero: HeroState, color: Color, modifier: Modifier = Modifier) {
    val state = when {
        !hero.hasReading -> stringResource(R.string.now_waiting_reading)
        else -> stateLabel(hero.power, hero.level)
    }
    val charger = hero.charger?.takeIf { hero.hasReading }?.let { chargerLabel(it) }
    Row(
        modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Box(Modifier.size(MaterialTheme.spacing.xs).background(color, CircleShape))
        Text(
            if (charger != null) stringResource(R.string.now_state_with_charger, state, charger) else state,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun stateLabel(power: PowerState, level: Int?): String = stringResource(
    when (power) {
        PowerState.CHARGING -> R.string.now_state_charging
        PowerState.DISCHARGING -> R.string.now_state_discharging
        PowerState.PLUGGED -> if (level == FULL_LEVEL) R.string.now_state_full else R.string.now_state_plugged
        PowerState.UNKNOWN -> R.string.now_state_unknown
    },
)

@Composable
private fun chargerLabel(charger: ChargerType): String = stringResource(
    when (charger) {
        ChargerType.AC -> R.string.now_charger_ac
        ChargerType.USB -> R.string.now_charger_usb
        ChargerType.WIRELESS -> R.string.now_charger_wireless
        ChargerType.DOCK -> R.string.now_charger_dock
    },
)

/** Level on the start edge, time left on the end edge; they stack when both don't fit (large font). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelAndEta(hero: HeroState) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        LevelText(hero.level)
        Spacer(Modifier.width(spacing.md))
        EtaText(hero)
    }
}

@Composable
private fun LevelText(level: Int?) {
    val style = MaterialTheme.typography.numericDisplay
    val quiet = unitSpan(style)
    val number = level?.let { formatNumber(it.toDouble(), 0, currentLocale()) } ?: stringResource(R.string.component_no_value)
    Text(percentAnnotated(number, quiet), style = style, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EtaText(hero: HeroState) {
    val eta = hero.eta
    Column(horizontalAlignment = Alignment.End) {
        if (eta != null) {
            val style = MaterialTheme.typography.numericHeadline
            val quiet = unitSpan(style)
            val direction = stringResource(if (hero.power == PowerState.CHARGING) R.string.now_eta_to_full else R.string.now_eta_left)
            val duration = durationAnnotated(eta.remainingMs, quiet)
            val spoken = stringResource(R.string.now_eta_content_description, duration.text, direction)
            Text(
                duration + AnnotatedString(" $direction", quiet),
                modifier = Modifier.semantics { contentDescription = spoken },
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            eta.basis?.let { basis ->
                Text(
                    stringResource(basisLabel(basis, hero.power)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        } else if (hero.etaPending != null) {
            val waiting = when (hero.etaPending) {
                EtaPending.ESTIMATING_LEFT -> R.string.now_eta_estimating
                EtaPending.ESTIMATING_FULL -> R.string.now_eta_estimating_full
                EtaPending.NEEDS_MONITORING_LEFT -> R.string.now_eta_needs_monitoring
                EtaPending.NEEDS_MONITORING_FULL -> R.string.now_eta_needs_monitoring_charge
            }
            Text(
                stringResource(waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun basisLabel(basis: EtaBasis, power: PowerState): Int = when (basis) {
    EtaBasis.LIVE_RATE -> if (power == PowerState.CHARGING) R.string.now_eta_basis_live_charge else R.string.now_eta_basis_live_drain
    EtaBasis.TYPICAL_7D -> R.string.now_eta_basis_typical
    EtaBasis.ANDROID -> R.string.now_eta_basis_android
    EtaBasis.TAPER_MODEL -> R.string.now_eta_basis_taper
}

/**
 * Ten segments, filled to the level in the direction color. On first load it fills from empty (long, decelerate);
 * later changes animate quickly. Decorative for TalkBack: the level is read from the number.
 */
@Composable
private fun LevelBar(level: Int?, hasReading: Boolean, color: Color) {
    val target = (level ?: 0).coerceIn(0, FULL_LEVEL) / FULL_LEVEL.toFloat()
    val fill = animatedFill(target, hasReading)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val shape = MaterialTheme.shapes.extraSmall
    val gapDp = MaterialTheme.spacing.xxs
    // One draw pass reads the fill: the animation redraws the bar each frame without recomposing anything.
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(MaterialTheme.spacing.sm)
            .clearAndSetSemantics { }
            .drawWithCache {
                val gap = gapDp.toPx()
                val segment = (size.width - gap * (LEVEL_SEGMENTS - 1)) / LEVEL_SEGMENTS
                val segmentSize = Size(segment, size.height)
                val radius = CornerRadius(shape.topStart.toPx(segmentSize, this))
                val rtl = layoutDirection == LayoutDirection.Rtl
                // Segment 0 is at the start edge; each fills from its start.
                val lefts = List(LEVEL_SEGMENTS) { index ->
                    val start = index * (segment + gap)
                    if (rtl) size.width - start - segment else start
                }
                val outlines = lefts.map { left ->
                    Path().apply { addRoundRect(RoundRect(Rect(Offset(left, 0f), segmentSize), radius)) }
                }
                onDrawBehind {
                    val filled = fill() * LEVEL_SEGMENTS
                    lefts.forEachIndexed { index, left ->
                        drawPath(outlines[index], track)
                        val part = (filled - index).coerceIn(0f, 1f)
                        if (part > 0f) {
                            val width = segment * part
                            clipPath(outlines[index]) {
                                drawRect(color, Offset(if (rtl) left + segment - width else left, 0f), Size(width, size.height))
                            }
                        }
                    }
                }
            },
    )
}

/**
 * 0 → [target] once per screen (saved, so no replay on return or rotation); skipped in previews. Read the returned
 * lambda in draw only, so the animation doesn't recompose.
 */
@Composable
private fun animatedFill(target: Float, hasReading: Boolean): () -> Float {
    val inspection = LocalInspectionMode.current
    var played by rememberSaveable { mutableStateOf(inspection) }
    val fill = remember { Animatable(if (played) target else 0f) }
    LaunchedEffect(target, hasReading) {
        when {
            !hasReading -> fill.snapTo(0f)
            played -> fill.animateTo(target, BatMotion.medium())
            else -> {
                played = true
                fill.animateTo(target, BatMotion.long(BatMotion.StandardDecelerate))
            }
        }
    }
    return { fill.value }
}

@Composable
private fun MonitoringButton(monitoring: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val content: @Composable () -> Unit = {
        Icon(
            if (monitoring) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(if (monitoring) R.string.now_stop_monitoring else R.string.now_start_monitoring))
    }
    if (monitoring) {
        FilledTonalButton(onClick = onClick, modifier = modifier) { content() }
    } else {
        Button(onClick = onClick, modifier = modifier) { content() }
    }
}
