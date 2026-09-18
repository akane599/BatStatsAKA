package app.batstats.battery.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class CommandProtocolTest {
    @Test fun largeResponseAvoidsBinderInlineLimits() {
        val value = CommandOutput.Result("battery-data\n".repeat(30000))
        val bytes = ByteArrayOutputStream().also { CommandProtocol.write(it, value) }.toByteArray()
        assertEquals(value, CommandProtocol.read(ByteArrayInputStream(bytes)))
    }

    @Test fun interruptedResponseIsNeverAccepted() {
        val bytes = ByteArrayOutputStream().also {
            CommandProtocol.write(it, CommandOutput.Result("partial"))
        }.toByteArray()
        for (length in listOf(0, 4, bytes.size - 1)) {
            assertThrows(Exception::class.java) {
                CommandProtocol.read(ByteArrayInputStream(bytes.copyOf(length)))
            }
        }
    }

    @Test fun commandFailureSurvivesTransport() {
        val value = CommandOutput.Result(error = "Command timed out")
        val bytes = ByteArrayOutputStream().also { CommandProtocol.write(it, value) }.toByteArray()
        assertEquals(value, CommandProtocol.read(ByteArrayInputStream(bytes)))
    }
}
