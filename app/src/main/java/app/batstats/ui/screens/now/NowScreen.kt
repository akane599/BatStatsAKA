package app.batstats.ui.screens.now

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.battery.apps.AppInfoSource
import app.batstats.battery.service.SamplingDemand
import app.batstats.ui.components.AppIconLoader
import app.batstats.ui.components.LocalAppIconLoader
import app.batstats.viewmodel.NowViewModel
import java.util.concurrent.ConcurrentHashMap
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private const val DEMAND_TAG = "NowScreen"

/**
 * Now, wired: the Koin [NowViewModel], and 2 s sampling while the screen is started (the [SamplingDemand] token is
 * released at onStop, so nothing polls fast in the background). App icons come from [AppInfoSource]. Navigation
 * leaves through the lambdas; every other [NowEvent] goes to the ViewModel.
 */
@Composable
fun NowScreen(
    onOpenHistory: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenApp: (uid: Int, packageName: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: NowViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val demand: SamplingDemand = koinInject()
    LifecycleStartEffect(demand) {
        val token = demand.acquire(DEMAND_TAG)
        onStopOrDispose { token.close() }
    }
    val appInfo: AppInfoSource = koinInject()
    val icons = remember(appInfo) { AppInfoIconLoader(appInfo) }
    CompositionLocalProvider(LocalAppIconLoader provides icons) {
        NowContent(
            state = state,
            onEvent = { event ->
                when (event) {
                    NowEvent.OpenHistory -> onOpenHistory()
                    NowEvent.OpenHealth -> onOpenHealth()
                    NowEvent.OpenApps -> onOpenApps()
                    is NowEvent.OpenApp -> onOpenApp(event.uid, event.packageName)
                    else -> vm.onEvent(event)
                }
            },
            modifier = modifier,
        )
    }
}

/** Icons from [AppInfoSource] (its own LRU does the heavy caching); remembers them for [cached] peeks. */
private class AppInfoIconLoader(private val source: AppInfoSource) : AppIconLoader {
    private val loaded = ConcurrentHashMap<String, ImageBitmap>()

    override suspend fun load(packageName: String): ImageBitmap? =
        loaded[packageName] ?: source.icon(packageName)?.asImageBitmap()?.also { loaded[packageName] = it }

    override fun cached(packageName: String): ImageBitmap? = loaded[packageName]
}
