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
}
