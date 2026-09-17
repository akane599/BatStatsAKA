package app.batstats.battery.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.ShizukuProvider
import app.batstats.battery.util.CommandProtocol
import app.batstats.battery.util.CommandOutput
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine

class ShizukuBridge(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuBridge"

        const val PERMISSION_REQUEST_CODE = 1001

        private const val SERVICE_VERSION = 4

        private const val BIND_TIMEOUT_MS = 10_000L
        private const val DEFAULT_CMD_TIMEOUT_MS = 25_000L

        private const val READ_GRACE_MS = 5_000L


        private const val PING_RETRIES = 4
        private const val PING_RETRY_DELAY_MS = 120L
    }

    enum class Failure { NOT_RUNNING, NO_PERMISSION, BIND_FAILED, TRANSPORT, COMMAND }

    sealed class RunResult {
        data class Success(val output: String) : RunResult()
        data class Error(val message: String, val reason: Failure) : RunResult()
    }

    private val requestIds = AtomicLong(SystemClock.elapsedRealtimeNanos())
    private val binderRef = AtomicReference<IBinder?>(null)
    private val bindMutex = Mutex()
    private val listenersRegistered = AtomicBoolean(false)

    @Volatile
    private var everSeen = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private val args by lazy {
        UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shz")
            .tag("ShellSvc")
            .version(SERVICE_VERSION)
    }

    private val pendingBind = AtomicReference<CompletableDeferred<IBinder?>?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "UserService connected (alive=${service?.isBinderAlive})")
            binderRef.set(service)
            runCatching {
                service?.linkToDeath({
                    binderRef.compareAndSet(service, null)
                    pendingBind.getAndSet(null)?.complete(null)
                }, 0)
            }
            pendingBind.getAndSet(null)?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            binderRef.set(null)
            pendingBind.getAndSet(null)?.complete(null)
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        everSeen = true
        _running.value = true
        binderRef.set(null)
        _granted.value = checkPermissionNow()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        _running.value = false
        _granted.value = false
        binderRef.set(null)
        pendingBind.getAndSet(null)?.complete(null)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                _granted.value = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Permission result: ${_granted.value}")
            }
        }

    fun warmUp() {
        if (!listenersRegistered.compareAndSet(false, true)) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register Shizuku listeners: ${t.message}")
            listenersRegistered.set(false)
        }
    }

    fun ping(): Boolean {
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            Log.d(TAG, "pingBinder threw: ${t.message}")
            false
        }
        _running.value = alive
        if (alive) {
            everSeen = true
        } else {
            _granted.value = false
            binderRef.set(null)
            pendingBind.getAndSet(null)?.complete(null)
        }
        return alive
    }

    suspend fun isRunning(): Boolean {
        if (ping()) return true
        if (!everSeen) return false
        repeat(PING_RETRIES) {
            delay(PING_RETRY_DELAY_MS)
            if (ping()) return true
        }
        Log.w(TAG, "Shizuku stopped responding")
        _running.value = false
        return false
    }

    private fun checkPermissionNow(): Boolean = try {
        if (Shizuku.isPreV11()) {
            ContextCompat.checkSelfPermission(context, ShizukuProvider.PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    } catch (t: Throwable) {
        Log.d(TAG, "checkSelfPermission failed: ${t.message}")
        false
    }

    fun hasPermission(): Boolean {
        if (!ping()) return false
        return checkPermissionNow().also { _granted.value = it }
    }

    suspend fun hasPermissionResilient(): Boolean {
        if (!isRunning()) return false
        return checkPermissionNow().also { _granted.value = it }
    }

    fun isPermanentlyDenied(): Boolean = try {
        ping() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
            !Shizuku.shouldShowRequestPermissionRationale()
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!ping()) {
            Log.w(TAG, "requestPermission: Shizuku not running, ignoring")
            return
        }
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed: ${t.message}")
        }
    }

    suspend fun run(cmd: String, timeoutMs: Long = DEFAULT_CMD_TIMEOUT_MS): RunResult =
        withContext(Dispatchers.IO) {
            if (!isRunning()) {
                return@withContext RunResult.Error("Shizuku is not running", Failure.NOT_RUNNING)
            }
            if (!hasPermissionResilient()) {
                return@withContext RunResult.Error(
                    "Shizuku permission not granted",
                    Failure.NO_PERMISSION
                )
            }

            val binder = ensureBound()
                ?: return@withContext RunResult.Error(
                    "Could not start the Shizuku helper service",
                    Failure.BIND_FAILED
                )

            val first = execute(binder, cmd, timeoutMs)
            if (cmd.contains("--reset") || first !is RunResult.Error || first.reason != Failure.TRANSPORT) {
                return@withContext first
            }

            Log.d(TAG, "Retrying after transport failure: ${first.message}")
            binderRef.set(null)
            val fresh = ensureBound() ?: return@withContext first
            execute(fresh, cmd, timeoutMs)
        }

    suspend fun runOrNull(cmd: String): String? =
        (run(cmd) as? RunResult.Success)?.output

    private suspend fun execute(binder: IBinder, cmd: String, timeoutMs: Long): RunResult {
        if (!binder.isBinderAlive) {
            return RunResult.Error("Helper service is no longer alive", Failure.TRANSPORT)
        }
        return try {
            val result = runViaPipe(binder, cmd, timeoutMs)
            currentCoroutineContext().ensureActive()
            when {
                !ping() || !hasPermission() -> RunResult.Error("Shizuku access lost during collection", Failure.TRANSPORT)
                result == null -> RunResult.Error("Helper protocol unavailable", Failure.TRANSPORT)
                result.error != null -> RunResult.Error(result.error, Failure.COMMAND)
                else -> RunResult.Success(result.output)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Command transport failed: ${t.message}")
            RunResult.Error(t.message ?: t.javaClass.simpleName, Failure.TRANSPORT)
        }
    }

    private suspend fun runViaPipe(binder: IBinder, cmd: String, timeoutMs: Long): CommandOutput.Result? {
        val pipe = ParcelFileDescriptor.createPipe()
        val requestId = requestIds.incrementAndGet()
        fun cancelRemote() {
            val data = Parcel.obtain()
            try {
                data.writeLong(requestId)
                binder.transact(ShellUserService.TRANSACTION_CANCEL, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) { } finally { data.recycle() }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
        return withTimeoutOrNull(timeoutMs + READ_GRACE_MS) {
            suspendCancellableCoroutine { continuation ->
                val worker = Thread({
                    try {
                        val data = Parcel.obtain()
                        val accepted = try {
                            data.writeString(cmd); data.writeLong(timeoutMs); data.writeLong(requestId)
                            pipe[1].writeToParcel(data, 0)
                            binder.transact(ShellUserService.TRANSACTION_RUN_PIPE, data, null, IBinder.FLAG_ONEWAY)
                        } finally { data.recycle(); runCatching { pipe[1].close() } }
                        if (!continuation.isActive) { cancelRemote(); return@Thread }
                        val result = if (accepted) ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use(CommandProtocol::read) else null
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    } finally { runCatching { pipe[0].close() }; runCatching { pipe[1].close() } }
                }, "batstats-shizuku-pipe").apply { isDaemon = true }
                continuation.invokeOnCancellation { cancelRemote(); worker.interrupt() }
                worker.start()
            }
        } ?: CommandOutput.Result(error = "Privileged read timed out")
    }

    private suspend fun ensureBound(): IBinder? {
        binderRef.get()?.takeIf { it.isBinderAlive }?.let { return it }

        return bindMutex.withLock {
            binderRef.get()?.takeIf { it.isBinderAlive }?.let { return@withLock it }

            val deferred = CompletableDeferred<IBinder?>()
            pendingBind.set(deferred)

            val started = withContext(Dispatchers.Main) {
                try {
                    Shizuku.bindUserService(args, connection)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "bindUserService failed", t)
                    false
                }
            }
            if (!started) {
                pendingBind.compareAndSet(deferred, null)
                return@withLock null
            }

            val startedAt = SystemClock.elapsedRealtime()
            val binder = try {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            } finally {
                pendingBind.compareAndSet(deferred, null)
            }

            if (binder == null || !binder.isBinderAlive) {
                Log.e(TAG, "UserService bind failed after ${SystemClock.elapsedRealtime() - startedAt} ms")
                binderRef.set(null)
                null
            } else {
                Log.d(TAG, "UserService bound in ${SystemClock.elapsedRealtime() - startedAt} ms")
                binder
            }
        }
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
        binderRef.set(null)
        pendingBind.getAndSet(null)?.complete(null)
    }
}
