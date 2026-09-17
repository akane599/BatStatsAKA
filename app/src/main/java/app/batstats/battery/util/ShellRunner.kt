package app.batstats.battery.util

import android.content.Context
import android.os.SystemClock
import app.batstats.battery.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ShellRunner(
    private val context: Context,
    private val shizuku: ShizukuBridge
) {
    companion object {
        private const val CMD_TIMEOUT_SEC = 25L

        private const val MODE_CACHE_MS = 10_000L
    }

    enum class Mode { ROOT, SHIZUKU, ADB, NONE }

    data class ShellResult(
        val output: String,
        val mode: Mode
    )

    sealed class Outcome {
        data class Success(val output: String, val mode: Mode) : Outcome()

        data class Failure(val mode: Mode, val message: String) : Outcome()
    }

    private val modeLock = Mutex()
    private val commandLock = Mutex()
    private val _access = MutableStateFlow(Mode.NONE)
    val access = _access.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()


    @Volatile
    private var cachedMode: Mode? = null

    @Volatile
    private var cachedModeAt = 0L

    suspend fun run(cmd: String): ShellResult? =
        (exec(cmd) as? Outcome.Success)?.let { ShellResult(it.output, it.mode) }

    suspend fun exec(cmd: String, allowEmpty: Boolean = false): Outcome = commandLock.withLock {
        withContext(Dispatchers.IO) {
            // Select one backend for this read. A failure never falls through to another source.
            val mode = detectMode(forceRefresh = true)
            val result = when (mode) {
                Mode.SHIZUKU -> when (val result = shizuku.run(cmd, TimeUnit.SECONDS.toMillis(CMD_TIMEOUT_SEC))) {
                    is ShizukuBridge.RunResult.Success -> CommandOutput.Result(result.output)
                    is ShizukuBridge.RunResult.Error -> CommandOutput.Result(error = result.message)
                }
                Mode.ROOT -> CommandOutput.run(listOf("su", "-c", cmd), CMD_TIMEOUT_SEC * 1000)
                Mode.ADB -> CommandOutput.run(cmd.split(' '), CMD_TIMEOUT_SEC * 1000)
                Mode.NONE -> CommandOutput.Result(error = if (shizuku.ping())
                    "Shizuku authorization required" else "Privileged access unavailable")
            }
            currentCoroutineContext().ensureActive()
            val error = result.error ?: when {
                !allowEmpty && result.output.isBlank() -> "Command returned no data"
                isErrorOutput(result.output) -> "Command was refused by Android"
                else -> null
            }
            _lastError.value = error
            if (error == null) Outcome.Success(result.output, mode) else Outcome.Failure(mode, error)
        }
    }

    suspend fun runDirectOnly(cmd: String): String? = withContext(Dispatchers.IO) {
        if (!PrivilegeChecker.hasAdvancedViaAdb(context)) return@withContext null
        runDirect(cmd)?.takeIf { it.isNotBlank() && !isErrorOutput(it) }
    }

    private fun runDirect(cmd: String): String? {
        val result = CommandOutput.run(cmd.split(' '), CMD_TIMEOUT_SEC * 1000)
        return result.output.takeIf { result.successful }
    }

    private fun isErrorOutput(out: String): Boolean =
        out.startsWith("ERROR") || out.contains("Permission Denial", ignoreCase = true) ||
            out.contains("SecurityException")

    suspend fun detectMode(forceRefresh: Boolean = false): Mode {
        if (!forceRefresh) {
            cachedMode?.let {
                if (SystemClock.elapsedRealtime() - cachedModeAt < MODE_CACHE_MS) return it
            }
        }
        return modeLock.withLock {
            if (!forceRefresh) {
                cachedMode?.let {
                    if (SystemClock.elapsedRealtime() - cachedModeAt < MODE_CACHE_MS) {
                        return@withLock it
                    }
                }
            }
            val mode = probeMode()
            _access.value = mode
            cachedMode = mode
            cachedModeAt = SystemClock.elapsedRealtime()
            mode
        }
    }

    private suspend fun probeMode(): Mode = withContext(Dispatchers.IO) {
        if (shizuku.ping()) return@withContext if (shizuku.hasPermission()) Mode.SHIZUKU else Mode.NONE
        if (RootStatsCollector.isRootAvailable()) return@withContext Mode.ROOT
        if (PrivilegeChecker.hasAdvancedViaAdb(context)) {
            return@withContext Mode.ADB
        }
        Mode.NONE
    }

    fun invalidateMode() {
        cachedMode = null
        cachedModeAt = 0L
        RootStatsCollector.invalidateRootCache()
    }

    suspend fun hasAnyPrivilegedAccess(): Boolean = detectMode() != Mode.NONE
}
