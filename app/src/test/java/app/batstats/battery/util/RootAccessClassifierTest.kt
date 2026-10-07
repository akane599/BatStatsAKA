package app.batstats.battery.util

import app.batstats.battery.util.CommandOutput.AccessFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootAccessClassifierTest {
    @Test fun suDenialSamplesHaveOnlyFixedDiagnostics() {
        for (sample in listOf(
            "Permission denied",
            "su: Permission denied",
            "su: exec failed: Permission denied",
            "su: access denied",
            "su: request rejected",
            "su: not allowed",
        )) {
            val result = CommandOutput.run(listOf("sh", "-c", "printf '%s\\n' '$sample' >&2; exit 1"), 1000)
            assertEquals(AccessFailure.DENIED, result.accessFailure)
            assertEquals("", result.output)
            assertEquals("Command exited with status 1", result.error)
            assertFalse(result.toString().contains(sample))
            assertTrue(rootAccessLost(result))
        }
    }

    @Test fun missingSuAndLaunchDenialAreAccessLoss() {
        val missing = CommandOutput.run(listOf("/batstats-missing-su-SQ64"), 1000)
        assertEquals(AccessFailure.EXECUTABLE_UNAVAILABLE, missing.accessFailure)
        assertEquals("IOException", missing.error)
        assertEquals("", missing.output)
        assertTrue(rootAccessLost(missing))
        assertEquals(AccessFailure.DENIED, CommandOutput.classifyLaunchFailure("Cannot run program su: error=13, Permission denied"))
        assertEquals(AccessFailure.DENIED, CommandOutput.classifyLaunchFailure("Exec failed, error: 13 (Permission denied)"))
        assertEquals(AccessFailure.EXECUTABLE_UNAVAILABLE, CommandOutput.classifyLaunchFailure("Exec failed, error: 2 (No such file or directory)"))
        assertNull(CommandOutput.classifyLaunchFailure("Cannot run program su: error=24, Too many open files"))
        assertEquals(AccessFailure.EXECUTABLE_UNAVAILABLE, CommandOutput.classifyAccessFailure("su: not found"))
    }

    @Test fun ordinaryDumpFailuresAndExitCodesDoNotMeanRootWasLost() {
        for (sample in listOf(
            "dumpsys: permission denied",
            "Permission Denial: can't dump batterystats",
            "Security exception: INTERACT_ACROSS_USERS required",
            "Can't find service: batterystats",
            "*** SERVICE 'batterystats' DUMP TIMEOUT (10000ms) EXPIRED ***",
            "9,10001,l,wua,Permission denied,2",
        )) {
            assertNull(CommandOutput.classifyAccessFailure(sample))
        }
        for (status in listOf(1, 126, 127)) {
            val result = CommandOutput.run(listOf("sh", "-c", "printf 'partial'; exit $status"), 1000)
            assertFalse(rootAccessLost(result))
            assertNull(result.accessFailure)
            assertEquals("", result.output)
        }
        assertFalse(rootAccessLost(CommandOutput.Result(error = "Command timed out")))
        assertFalse(rootAccessLost(CommandOutput.Result(error = "Command output could not be read")))
    }

    @Test fun successfulOutputIsNeverAccessLoss() {
        val result = CommandOutput.run(listOf("sh", "-c", "printf 'su: permission denied'"), 1000)
        assertTrue(result.successful)
        assertEquals("su: permission denied", result.output)
        assertNull(result.accessFailure)
        assertFalse(rootAccessLost(result))
    }
}
