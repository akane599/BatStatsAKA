package app.batstats.viewmodel

import android.net.Uri
import android.content.Context
import app.batstats.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.batstats.battery.data.ExportImportManager
import app.batstats.battery.data.HistoryImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DataViewModel(private val manager: ExportImportManager, private val context: Context) : ViewModel() {
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }
    private fun operation(block: suspend () -> String) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try { _message.value = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = context.getString(R.string.history_operation_failed, (if (e is IllegalArgumentException || e is IllegalStateException) e.message else e.javaClass.simpleName)?.take(200).orEmpty()) }
            finally { _isBusy.value = false }
        }
    }
    fun exportJson(uri: Uri, from: Long, to: Long, includeSamples: Boolean, includeSessions: Boolean) = operation {
        manager.exportJson(uri, from, to, includeSamples, includeSessions); context.getString(R.string.history_json_exported)
    }
    fun exportCsv(uri: Uri, from: Long, to: Long, includeSamples: Boolean = true, includeSessions: Boolean = true) = operation {
        manager.exportCsvToFolder(uri, from, to, includeSamples, includeSessions); context.getString(R.string.history_csv_exported)
    }
    fun importJson(uri: Uri) = operation { describe(manager.importJson(uri)) }
    fun importCsv(uri: Uri) = operation { describe(manager.importCsv(uri)) }
    private fun describe(r: HistoryImportResult) = context.getString(R.string.history_imported, r.samplesAdded, r.sessionsAdded, r.sessionsUpdated, r.unchanged)
}
