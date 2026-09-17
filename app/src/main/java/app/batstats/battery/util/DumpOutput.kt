package app.batstats.battery.util

/** dumpsys can emit a timeout marker after valid-looking partial records. */
object DumpOutput {
    fun failure(raw: String): String? {
        for (line in raw.lineSequence()) {
            val value = line.trimStart()
            when {
                value.startsWith("Permission Denial", true) || value.startsWith("java.lang.SecurityException") -> return "Command was refused by Android"
                value.startsWith("Can't find service:", true) -> return "Android service unavailable"
                value.startsWith("***") && (value.contains("DUMP TIMEOUT") || value.contains("DUMP FAILED")) -> return "Android service dump was incomplete"
                value.startsWith("Error dumping service info", true) || value.startsWith("ERROR:") -> return "Android service dump failed"
            }
        }
        return null
    }
}
