package app.batstats.battery.util

import org.junit.Assert.*
import org.junit.Test

class CommandOutputTest {
    @Test fun completeOutputIsSuccessful() {
        val result = CommandOutput.run(listOf("sh", "-c", "printf 'complete'"), 1000)
        assertTrue(result.successful)
        assertEquals("complete", result.output)
    }

    @Test fun partialOutputFromFailedCommandIsNotData() {
        val result = CommandOutput.run(listOf("sh", "-c", "printf 'partial'; exit 7"), 1000)
        assertFalse(result.successful)
        assertEquals("", result.output)
    }

    @Test fun timeoutIsNotAnEmptySuccessfulReading() {
        val result = CommandOutput.run(listOf("sleep", "2"), 50)
        assertEquals("Command timed out", result.error)
    }

    @Test fun inheritedOutputPipeCannotDefeatTimeout() {
        val start = System.nanoTime()
        val result = CommandOutput.run(listOf("sh", "-c", "sleep 3 & printf 'partial'; sleep 0.05"), 100)
        assertEquals("Command timed out", result.error)
        assertEquals("", result.output)
        assertTrue("Read must return before descendant exits", (System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test fun oversizedOutputIsRejectedInsteadOfTruncated() {
        val result = CommandOutput.run(listOf("sh", "-c", "printf '123456789'"), 1000, 4)
        assertFalse(result.successful)
        assertEquals("", result.output)
    }
    @Test fun interruptionReturnsPromptlyAndDiscardsPartialOutput() {
        val result = java.util.concurrent.atomic.AtomicReference<CommandOutput.Result>()
        val worker = Thread { result.set(CommandOutput.run(listOf("sh", "-c", "printf partial; sleep 10"), 15000)) }
        worker.start()
        Thread.sleep(100)
        worker.interrupt()
        worker.join(1500)
        assertFalse("Cancelled command must release its caller", worker.isAlive)
        assertEquals("Command interrupted", result.get().error)
        assertEquals("", result.get().output)
    }

    @Test fun nativeDumpTimeoutAfterPartialRecordsIsNotSuccessfulData() {
        assertNotNull(DumpOutput.failure("9,0,l,bt,1,1000,500\n*** SERVICE 'batterystats' DUMP TIMEOUT (10000ms) EXPIRED ***"))
        assertNotNull(DumpOutput.failure("Can't find service: battery"))
        assertNull(DumpOutput.failure("9,10001,l,wua,SecurityException,2"))
    }

}
