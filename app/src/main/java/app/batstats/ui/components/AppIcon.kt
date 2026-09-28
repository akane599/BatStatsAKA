package app.batstats.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Loads an app's launcher icon; `null` when unknown. Provided through [LocalAppIconLoader]. */
fun interface AppIconLoader {
    suspend fun load(packageName: String): ImageBitmap?

    /**
     * An icon already in memory, returned without suspending, so a recycled row shows it from its first frame
     * instead of flashing the placeholder. The default has none; a caching loader should override it.
     */
    fun cached(packageName: String): ImageBitmap? = null
}

/**
 * The icon source for [AppIcon]. The default loads nothing, so previews and screenshots render the placeholder
 * without a PackageManager; the app provides a real loader at its root.
 */
val LocalAppIconLoader = staticCompositionLocalOf { AppIconLoader { null } }

/** Default sizes for [AppIcon]. */
object AppIconDefaults {
    val Size: Dp = 40.dp
}

private val PlaceholderGlyphSize = 20.dp

/**
 * An app's icon from [LocalAppIconLoader], 40 dp unless [modifier] sizes it. Shows [AppIconPlaceholder] (the
 * first letter of [label]) while loading, when there is none, or when the loader throws. The load is keyed on
 * [packageName], so a reused composable never shows another app's icon. Decorative: the row around it carries
 * the label.
 */
@Composable
fun AppIcon(packageName: String, label: String, modifier: Modifier = Modifier) {
    val loader = LocalAppIconLoader.current
    // Keyed on the package, so a reused row (e.g. a recycled lazy item) never shows the previous app's icon: it
    // starts from this package's cached icon, or the placeholder while it loads.
    val icon = remember(packageName, loader) { mutableStateOf(loader.cachedOrNull(packageName)) }
    LaunchedEffect(icon) {
        if (icon.value == null) icon.value = loader.loadOrNull(packageName)
    }
    val sized = modifier.size(AppIconDefaults.Size)
    val bitmap = icon.value
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = sized)
    } else {
        AppIconPlaceholder(label, sized)
    }
}

/**
 * A tonal circle with the first letter of [label], or [icon] (Rounded) when given — e.g. for the aggregated
 * "Other apps" row.
 */
@Composable
fun AppIconPlaceholder(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Box(
        modifier
            .size(AppIconDefaults.Size)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(PlaceholderGlyphSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                monogram(label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** [AppIconLoader.cached], with a throwing loader treated as "none". */
internal fun AppIconLoader.cachedOrNull(packageName: String): ImageBitmap? = try {
    cached(packageName)
} catch (failure: Exception) {
    null
}

/** [AppIconLoader.load]; a failing loader (e.g. a PackageManager error) yields `null`, so the placeholder stays. */
internal suspend fun AppIconLoader.loadOrNull(packageName: String): ImageBitmap? = try {
    load(packageName)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null
}

/** First letter or digit of [label], upper-cased in the user's locale (surrogate-pair safe); "?" when none. */
internal fun monogram(label: String): String {
    var i = 0
    while (i < label.length) {
        val codePoint = label.codePointAt(i)
        if (Character.isLetterOrDigit(codePoint)) return String(Character.toChars(codePoint)).uppercase(Locale.getDefault())
        i += Character.charCount(codePoint)
    }
    return "?"
}
