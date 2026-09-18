package app.batstats.battery.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Framing makes helper death, truncation and timeout distinguishable from valid output. */
object CommandProtocol {
    private const val MAGIC = 0x42535433

    fun write(output: OutputStream, result: CommandOutput.Result) {
        val stream = DataOutputStream(output)
        stream.writeInt(MAGIC)
        stream.writeUTF(result.error.orEmpty().take(250))
        val bytes = result.output.toByteArray(Charsets.UTF_8)
        require(bytes.size <= CommandOutput.MAX_BYTES)
        stream.writeInt(bytes.size)
        stream.write(bytes)
        stream.writeInt(MAGIC)
        stream.flush()
    }

    fun read(input: InputStream): CommandOutput.Result {
        val stream = DataInputStream(input)
        require(stream.readInt() == MAGIC) { "Unsupported helper protocol" }
        val error = stream.readUTF().ifEmpty { null }
        val size = stream.readInt()
        require(size in 0..CommandOutput.MAX_BYTES) { "Invalid output length" }
        val bytes = ByteArray(size)
        stream.readFully(bytes)
        require(stream.readInt() == MAGIC) { "Incomplete command response" }
        return CommandOutput.Result(if (error == null) bytes.toString(Charsets.UTF_8) else "", error)
    }
}
