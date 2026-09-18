package app.batstats.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import app.batstats.ui.NavGraph
import app.batstats.ui.Screen

@Composable
fun MainScreen(openDrain: Boolean = false, onDrainOpened: () -> Unit = {}) {
    val backStack = rememberNavBackStack(Screen.Dashboard)
    LaunchedEffect(openDrain) {
        if (openDrain) {
            if (backStack.lastOrNull() != Screen.DrainStats) backStack.add(Screen.DrainStats)
            onDrainOpened()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavGraph(backStack, listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ))
    }
}
