package app.batstats.battery.shizuku

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import app.batstats.battery.util.CommandOutput
import app.batstats.battery.util.CommandProtocol
import java.util.concurrent.Semaphore

/** Only the app's fixed diagnostic commands are exposed by the privileged helper. */
class ShellUserService : Binder() {
    companion object {
        const val TRANSACTION_RUN_PIPE = 2
        const val TRANSACTION_DESTROY = 16777114
        private val COMMANDS = setOf(
            "dumpsys batterystats --checkin", "dumpsys batterystats --reset",
            "dumpsys deviceidle", "dumpsys power", "dumpsys battery"
        )
    }

    private val permits = Semaphore(1)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_RUN_PIPE -> {
                val command = data.readString().orEmpty()
                val timeout = data.readLong().coerceIn(1_000L, 30_000L)
                val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                reply?.writeInt(1)
                Thread {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        val acquired = permits.tryAcquire()
                        try {
                            val result = when {
                                command !in COMMANDS -> CommandOutput.Result(error = "Unsupported command")
                                !acquired -> CommandOutput.Result(error = "Helper busy; retry later")
                                else -> CommandOutput.run(command.split(' '), timeout)
                            }
                            CommandProtocol.write(output, result)
                        } catch (_: Exception) {
                            // A closed client pipe cancels delivery; no partial result is valid.
                        } finally {
                            if (acquired) permits.release()
                        }
                    }
                }.apply { isDaemon = true; name = "batstats-shell"; start() }
                return true
            }
            TRANSACTION_DESTROY -> {
                Runtime.getRuntime().halt(0)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }
}
