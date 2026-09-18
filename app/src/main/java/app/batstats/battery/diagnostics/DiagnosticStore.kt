package app.batstats.battery.diagnostics

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException

/** Local only, excluded from Android backups. No alarm, worker or wake lock is used. */
class DiagnosticStore(context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "collection-diagnostics.txt"))
    private val incoming = Channel<Pair<DiagnosticCode, Long>>(64, BufferOverflow.DROP_OLDEST)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events = _events.asStateFlow()
    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable = _storageUnavailable.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val restored = try {
                file.openRead().use { input ->
                    val buffer = ByteArray(DiagnosticLog.MAX_BYTES + 1)
                    var size = 0
                    while (size < buffer.size) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    require(size <= DiagnosticLog.MAX_BYTES)
                    DiagnosticLog.decode(String(buffer, 0, size, Charsets.UTF_8))
                }
            } catch (_: FileNotFoundException) { emptyList() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _storageUnavailable.value = true
                val now = System.currentTimeMillis()
                listOf(DiagnosticEvent(DiagnosticCode.LOG_READ_FAILED, now, now))
            }
            val log = DiagnosticLog(restored)
            _events.value = log.snapshot()
            for ((code, timestamp) in incoming) {
                log.record(code, timestamp)
                _events.value = log.snapshot()
                writes.trySend(Unit)
            }
        }
        scope.launch(Dispatchers.IO) {
            // Coalesce a burst, then write at most once per minute. A killed process can lose recent diagnostics.
            for (ignored in writes) {
                delay(1_000)
                val bytes = DiagnosticLog.encode(_events.value).toByteArray(Charsets.UTF_8)
                var output: java.io.FileOutputStream? = null
                try {
                    output = file.startWrite()
                    output.write(bytes)
                    file.finishWrite(output)
                    _storageUnavailable.value = false
                } catch (e: Exception) {
                    file.failWrite(output)
                    _storageUnavailable.value = true
                    if (e is CancellationException) throw e
                }
                delay(60_000)
            }
        }
    }
    fun record(code: DiagnosticCode) { incoming.trySend(code to System.currentTimeMillis()) }
}
