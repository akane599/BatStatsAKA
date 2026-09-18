package app.batstats.viewmodel

import app.batstats.R
import app.batstats.settings.SettingsImportPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.net.Uri
import android.content.Intent
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.service.BatteryMonitorService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.settings.AppSettings
import app.batstats.settings.AppSettingsSchema
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.backup.ExportResult
import io.github.mlmgames.settings.core.backup.ImportResult
import io.github.mlmgames.settings.core.backup.SettingsBackupManager
import io.github.mlmgames.settings.core.managers.ResetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val context: Context,
    private val repository: SettingsRepository<AppSettings>,
    private val resetManager: ResetManager<AppSettings>,
    private val backupManager: SettingsBackupManager<AppSettings>,
    private val batteryRepository: BatteryRepository
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val settings: StateFlow<AppSettings> = repository.flow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettingsSchema.default
        )

    val dynamicColors: Flow<Boolean> = settings.map { it.dynamicColors }
    val themeIndex: Flow<Int> = settings.map { it.themeIndex }

    fun updateSetting(name: String, value: Any) {
        viewModelScope.launch {
            attempt { repository.set(name, value) }
        }
    }

    fun <V> observeField(fieldName: String): Flow<V> = repository.observeField(fieldName)

    suspend fun clearHistory() {
        batteryRepository.clearHistory {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }

    suspend fun resetUISettings(): Boolean = attempt { resetManager.resetUISettings() }
    suspend fun resetAll(): Boolean = attempt { resetManager.resetAll() }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block(); _error.value = null; true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { _error.value = context.getString(R.string.settings_write_failed); false }

    suspend fun exportToFile(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            when (val result = backupManager.export()) {
                is ExportResult.Success -> {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open settings destination")).use { output ->
                        output.write(result.json.toByteArray(Charsets.UTF_8))
                    }
                    context.getString(R.string.settings_saved)
                }
                is ExportResult.Error -> context.getString(R.string.settings_export_failed)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { context.getString(R.string.settings_export_failed) }
    }

    suspend fun import(json: String): ImportResult = withContext(Dispatchers.IO) {
        SettingsImportPolicy.validate(json)
        backupManager.import(json)
    }
}