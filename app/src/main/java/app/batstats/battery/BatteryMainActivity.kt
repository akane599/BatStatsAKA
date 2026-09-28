package app.batstats.battery

import android.Manifest
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.settings.AppSettings
import app.batstats.ui.screens.MainScreen
import app.batstats.ui.theme.MainTheme
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject

class BatteryMainActivity : ComponentActivity() {
    private val openDrain = MutableStateFlow(false)
    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: light bar icons over transparent bars on every API level.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        openDrain.value = intent.getBooleanExtra("open_drain_stats", false)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settingsRepository: SettingsRepository<AppSettings> = koinInject()
            val settings by settingsRepository.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
            MainTheme(oled = settings.oledBlack, dynamicColor = settings.dynamicColors) {
                val requested by openDrain.collectAsStateWithLifecycle()
                MainScreen(openDrain = requested, onDrainOpened = {
                    openDrain.value = false
                    intent.removeExtra("open_drain_stats")
                })
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDrain.value = intent.getBooleanExtra("open_drain_stats", false)
    }
}