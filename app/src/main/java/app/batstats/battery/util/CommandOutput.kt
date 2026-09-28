package app.batstats.battery.util

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A command succeeds only after a complete, bounded read and a zero exit code. */
object CommandOutput {
    const val MAX_BYTES = 8 * 1024 * 1024

    /** `9,h,` battery-history lines are the bulk of a checkin dump and the parser ignores them;
     * dropping them while reading (instead of after) keeps large dumps under [MAX_BYTES]. */
    private const val HISTORY_LINE_PREFIX = "9,h,"

    data class Result(val output: String = "", val error: String? = null) {
        val successful: Boolean get() = error == null

        /** The (already history-filtered) output as a line sequence, for streaming parse. */
        fun lineSequence(): Sequence<String> = output.lineSequence()
    }

    fun run(arguments: List<String>, timeoutMs: Long, maxBytes: Int = MAX_BYTES): Result {
        require(timeoutMs > 0 && maxBytes in 1..MAX_BYTES)
        var process: Process? = null
        var reader: FutureTask<String>? = null
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val child = ProcessBuilder(arguments).redirectErrorStream(true).start()
            process = child
            child.outputStream.close()
            val read = FutureTask {
                val text = StringBuilder()
                var totalBytes = 0
                var firstLine = true
                BufferedReader(InputStreamReader(child.inputStream, Charsets.UTF_8)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith(HISTORY_LINE_PREFIX)) continue
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        totalBytes += lineBytes
                        if (totalBytes > maxBytes) throw IOException("Output limit exceeded")
                        if (!firstLine) text.append('\n')
                        firstLine = false
                        text.append(line)
                    }
                }
                text.toString()
            }
            reader = read
            Thread(read, "batstats-command-reader").apply { isDaemon = true; start() }
            // Bound the read itself: a descendant can hold stdout after the parent has exited.
            val text = read.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            when {
                !child.waitFor((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) ->
                    Result(error = "Command timed out")
                child.exitValue() != 0 -> Result(error = "Command exited with status ${child.exitValue()}")
                else -> Result(output = text)
            }
        } catch (_: TimeoutException) {
            Result(error = "Command timed out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(error = "Command interrupted")
        } catch (e: ExecutionException) {
            Result(error = e.cause?.message ?: "Command output could not be read")
        } catch (e: Exception) {
            Result(error = e.javaClass.simpleName)
        } finally {
            reader?.cancel(true)
            // Some JVM pipe implementations block close behind an inherited reader.
            // Cleanup must not turn a timed-out read into a blocking caller.
            process?.let { child ->
                Thread({ runCatching { child.destroyForcibly() } }, "batstats-command-cleanup")
                    .apply { isDaemon = true; start() }
            }
        }
    }
}
