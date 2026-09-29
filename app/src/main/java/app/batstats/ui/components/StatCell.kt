package app.batstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import app.batstats.ui.theme.Spacing
import app.batstats.ui.theme.numericTitle
import app.batstats.ui.theme.spacing
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val IndicatorSize = 8.dp

/** Shared [StatCell] proportions, for number + unit text elsewhere that should match it. */
object StatCellDefaults {
    /** Units (and words next to a number) sit at this fraction of the value's size, on the same baseline. */
    val UnitScale: Float = 0.6f
}

/**
 * Sizes (fractions of [StatCell]'s `valueStyle`) a value line steps through: value and unit share a line down to
 * 80 %; below that the unit moves under the value, which may then go down to 70 %.
 */
private val INLINE_SCALES = floatArrayOf(1f, 0.9f, 0.8f)
private val VALUE_SCALES = floatArrayOf(1f, 0.9f, 0.8f, 0.7f)

/**
 * A labelled number: label above, value (tabular Space Grotesk) with its unit on the same baseline, and an
 * optional supporting line. Read as one item by TalkBack.
 *
 * **Narrow cells never cut a number:** when value + unit don't fit the width, both shrink in 10 % steps down to
 * 80 % of [valueStyle] (keeping the cell's height and value baseline, so a row of cells stays aligned); if they
 * still don't fit, the unit moves under the value, which keeps the number large (down to 70 % if needed); a value
 * wider than the cell even at 70 % shrinks until it fits.
 * Give 4-across rows equal `Modifier.weight(1f)` cells. Supports intrinsic measurement (e.g. `IntrinsicSize.Max`
 * rows of equal-height panels).
 *
 * **Live values:** each value is fitted on its own, so a cell whose digit count changes can change size, move its
 * unit and change height (moving everything below). Pass [sizingTemplate] to fit once instead.
 *
 * @param value the formatted number only ("−412"); put the unit in [unit] so it renders quieter.
 * @param unitFirst the unit goes before the value on a shared line (Turkish "%94"; see `ui/format` `percentUnit`).
 * @param indicator a small dot before the label, tying the value to a chart color (e.g. the trace direction).
 * @param valueStyle `numericTitle` by default; `numericHeadline` for the large Now readouts. A style without a
 *   font size falls back to `numericTitle`'s size.
 * @param sizingTemplate for a live readout: the widest value you expect, in the same format ("−8,888" for mA,
 *   "−88.88" for W). Size step and unit placement are fitted to it instead of to [value], so the cell keeps its
 *   size, unit position and height while the digits change (tabular figures: width follows the character count).
 *   A value that outgrows the template and no longer fits falls back to its own fit; it is never cut. `null` (the
 *   default) fits every value on its own.
 */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    supporting: String? = null,
    indicator: Color? = null,
    valueStyle: TextStyle = MaterialTheme.typography.numericTitle,
    sizingTemplate: String? = null,
    unitFirst: Boolean = false,
) {
    val fallbackSize = MaterialTheme.typography.numericTitle.fontSize
    val sized = if (valueStyle.fontSize.isSpecified) valueStyle else valueStyle.copy(fontSize = fallbackSize)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            if (indicator != null) Box(Modifier.size(IndicatorSize).background(indicator, CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ValueLine(value, unit, sized, sizingTemplate, unitFirst)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValueLine(value: String, unit: String?, style: TextStyle, template: String?, unitFirst: Boolean) {
    val gap = MaterialTheme.spacing.xxs
    val policy = remember(gap, unit != null, template != null, unitFirst) {
        ValueLinePolicy(gap, unit != null, template != null, unitFirst)
    }
    Layout(
        content = {
            Text(value, style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            if (unit != null) {
                Text(
                    unit,
                    style = style.scaled(StatCellDefaults.UnitScale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // Measured for its width only: never placed, and hidden from TalkBack.
            if (template != null) Text(template, Modifier.clearAndSetSemantics { }, style = style, maxLines = 1, softWrap = false)
        },
        measurePolicy = policy,
    )
}

/**
 * Whether a [StatCell] with this [label] (after its indicator dot when [hasIndicator]) and [value] + [unit] fits
 * [maxWidthPx] at full size: the label unclipped and the value line at scale 1 with the unit beside it
 * ([fitValueLine]). Measured with StatCell's own dot, gaps and unit scale, for layouts that pick a grid from it
 * (Now's readouts) and must stay in step with the cell. [valueStyle] must have a specified font size.
 */
internal fun statCellFitsInline(
    measurer: TextMeasurer,
    density: Density,
    spacing: Spacing,
    label: String,
    hasIndicator: Boolean,
    value: String,
    unit: String?,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    maxWidthPx: Float,
): Boolean = with(density) {
    val dot = if (hasIndicator) IndicatorSize.toPx() + spacing.xs.toPx() else 0f
    val labelWidth = measurer.measure(label, labelStyle).size.width + dot
    val valueWidth = measurer.measure(value, valueStyle).size.width
    val unitWidth = unit?.let { measurer.measure(it, valueStyle.scaled(StatCellDefaults.UnitScale)).size.width }
    val fit = fitValueLine(valueWidth, unitWidth, spacing.xxs.roundToPx(), maxWidthPx.toInt())
    labelWidth <= maxWidthPx && fit.scale == 1f && !fit.unitBelow
}

/** Font size (and an sp line height) times [scale]; [this] must have a specified font size. */
private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize * scale,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
)

/** How the value line fits a width: one [scale] for value and unit, and whether the unit sits under the value. */
@Immutable
internal data class ValueLineFit(val scale: Float, val unitBelow: Boolean)

/**
 * The largest step of [INLINE_SCALES] at which value and unit share a line within [maxWidth]; else the largest of
 * [VALUE_SCALES] at which they fit stacked (a lone value: at which it fits); else the exact scale that fits the
 * wider of the two, so a number is never cut short. Widths are at full size; [unitWidth] is `null` without a unit.
 *
 * With a [templateWidth], the fit is decided for the template instead and kept for any value that fits it, so a
 * live readout doesn't change size or placement; a value that doesn't fit the template's fit gets its own.
 * Never throws, for any [maxWidth].
 */
internal fun fitValueLine(valueWidth: Int, unitWidth: Int?, gap: Int, maxWidth: Int, templateWidth: Int? = null): ValueLineFit {
    if (templateWidth != null) {
        val pinned = fitValueLine(templateWidth, unitWidth, gap, maxWidth)
        if (pinned.fits(valueWidth, unitWidth, gap, maxWidth)) return pinned
    }
    if (maxWidth == Constraints.Infinity) return ValueLineFit(1f, unitBelow = false)
    val available = max(maxWidth, 0).toFloat()
    val unitOrZero = unitWidth ?: 0
    val inlineGap = if (unitWidth != null) gap else 0
    for (scale in INLINE_SCALES) {
        if (scale * (valueWidth + unitOrZero) + inlineGap <= available) return ValueLineFit(scale, unitBelow = false)
    }
    val widest = max(valueWidth, unitOrZero)
    val below = unitWidth != null
    for (scale in VALUE_SCALES) if (scale * widest <= available) return ValueLineFit(scale, below)
    return ValueLineFit(if (widest > 0) available / widest else 1f, below)
}

/** Whether a value and unit of these full-size widths fit [maxWidth] at this scale and placement. */
private fun ValueLineFit.fits(valueWidth: Int, unitWidth: Int?, gap: Int, maxWidth: Int): Boolean {
    if (maxWidth == Constraints.Infinity) return true
    val available = max(maxWidth, 0).toFloat()
    val unitOrZero = unitWidth ?: 0
    return if (unitBelow) {
        scale * max(valueWidth, unitOrZero) <= available
    } else {
        scale * (valueWidth + unitOrZero) + (if (unitWidth != null) gap else 0) <= available
    }
}

/**
 * Value and unit measured at full size, then drawn scaled (graphics layer) as [fitValueLine] decides. Scaling
 * keeps them real `Text`s (semantics, font scale) without subcomposition, so intrinsic measurement works.
 * Children: value, then the unit if [hasUnit], then the sizing template if [hasTemplate] (measured, never placed).
 * With [unitFirst] a shared line starts with the unit; stacked, the unit stays under the value.
 */
private class ValueLinePolicy(
    private val gap: Dp,
    private val hasUnit: Boolean,
    private val hasTemplate: Boolean,
    private val unitFirst: Boolean,
) : MeasurePolicy {
    private fun <T> List<T>.unit(): T? = if (hasUnit) this[1] else null

    private fun <T> List<T>.template(): T? = if (hasTemplate) this[if (hasUnit) 2 else 1] else null

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val value = measurables[0].measure(Constraints())
        val unit = measurables.unit()?.measure(Constraints())
        val templateWidth = measurables.template()?.measure(Constraints())?.width
        val gapPx = gap.roundToPx()
        val fit = fitValueLine(value.width, unit?.width, gapPx, constraints.maxWidth, templateWidth)
        val scale = fit.scale
        // The value baseline stays where the full-size line has it, so shrunk and full cells in a row line up.
        val valueBaseline = value.baseline()
        val baseline = max(valueBaseline, unit?.baseline() ?: 0)
        val valueTop = baseline - scale * valueBaseline
        val lineWidth: Float
        val lineHeight: Float
        val valueX: Float
        val unitX: Float
        val unitY: Float
        if (unit != null && fit.unitBelow) {
            valueX = 0f
            unitX = 0f
            unitY = valueTop + scale * value.height
            lineWidth = max(scale * value.width, scale * unit.width)
            lineHeight = unitY + scale * unit.height
        } else {
            val leadingUnit = unit != null && unitFirst
            valueX = if (leadingUnit && unit != null) scale * unit.width + gapPx else 0f
            unitX = if (leadingUnit) 0f else scale * value.width + gapPx
            unitY = baseline - scale * (unit?.baseline() ?: 0)
            lineWidth = if (unit != null) scale * (value.width + unit.width) + gapPx else scale * value.width
            // Full-size line height, whatever the scale: shrinking never changes the cell's height.
            lineHeight = max(value.height - valueBaseline, unit?.let { it.height - it.baseline() } ?: 0).toFloat() + baseline
        }
        val layoutWidth = constraints.constrainWidth(ceil(lineWidth).toInt())
        val layoutHeight = constraints.constrainHeight(ceil(lineHeight).toInt())
        return layout(layoutWidth, layoutHeight) {
            if (scale <= 0f) return@layout // no width at all: nothing fits, nothing to draw
            fun Placeable.placeScaled(x: Float, y: Float) {
                val left = if (layoutDirection == LayoutDirection.Rtl) layoutWidth - x - scale * this.width else x
                if (scale == 1f) {
                    place(left.roundToInt(), y.roundToInt())
                } else {
                    placeWithLayer(left.roundToInt(), y.roundToInt()) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
            value.placeScaled(valueX, valueTop)
            unit?.placeScaled(unitX, unitY)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val widths = naturalWidths(measurables)
        return ceil(VALUE_SCALES.last() * max(max(widths.value, widths.template ?: 0), widths.unit ?: 0)).toInt()
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val widths = naturalWidths(measurables)
        return max(widths.value, widths.template ?: 0) + (widths.unit?.let { it + gap.roundToPx() } ?: 0)
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private class Widths(val value: Int, val unit: Int?, val template: Int?)

    private fun naturalWidths(measurables: List<IntrinsicMeasurable>) = Widths(
        value = measurables[0].maxIntrinsicWidth(Constraints.Infinity),
        unit = measurables.unit()?.maxIntrinsicWidth(Constraints.Infinity),
        template = measurables.template()?.maxIntrinsicWidth(Constraints.Infinity),
    )

    /** Full line height; stacked, an upper bound (the value line plus the scaled unit line). */
    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val widths = naturalWidths(measurables)
        val valueHeight = measurables[0].maxIntrinsicHeight(Constraints.Infinity)
        val unit = measurables.unit() ?: return valueHeight
        val fit = fitValueLine(widths.value, widths.unit, gap.roundToPx(), width, widths.template)
        if (!fit.unitBelow) return max(valueHeight, unit.maxIntrinsicHeight(Constraints.Infinity))
        return valueHeight + ceil(fit.scale * unit.maxIntrinsicHeight(Constraints.Infinity)).toInt()
    }
}

/** First baseline, or the bottom for a child without one. */
private fun Placeable.baseline(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
