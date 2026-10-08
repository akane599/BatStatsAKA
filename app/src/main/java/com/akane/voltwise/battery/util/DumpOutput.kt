package com.akane.voltwise.battery.util

/** dumpsys can emit a timeout marker after valid-looking partial records. */
object DumpOutput {
    /** Android refused the command itself: the current access mode lacks a permission it needs. */
    const val REFUSED = "Command was refused by Android"

    /**
     * The per-app dump asks for every user's data. On Android 16 an app holding only the ADB-granted DUMP and
     * usage-stats permissions is refused that (`Security exception: MATCH_ANY_USER flag requires
     * INTERACT_ACROSS_USERS…`); Shizuku (shell) and root are not.
     */
    const val REFUSED_CROSS_USER =
        "Android refused the per-app dump: it needs cross-user access, which ADB-granted permissions do not give on this Android version. Use Shizuku or root."

    /** A refusal is an access problem, not a failed read: callers report it as "no access". */
    fun isRefusal(message: String?): Boolean = message == REFUSED || message == REFUSED_CROSS_USER

    fun failure(raw: String): String? {
        for (line in raw.lineSequence()) {
            val value = line.trimStart()
            when {
                value.startsWith("Security exception", true) ->
                    return if (value.contains("INTERACT_ACROSS_USERS")) REFUSED_CROSS_USER else REFUSED
                value.startsWith("Permission Denial", true) || value.startsWith("java.lang.SecurityException") -> return REFUSED
                value.startsWith("Can't find service:", true) -> return "Android service unavailable"
                value.startsWith("***") && (value.contains("DUMP TIMEOUT") || value.contains("DUMP FAILED")) -> return "Android service dump was incomplete"
                value.startsWith("Error dumping service info", true) || value.startsWith("ERROR:") -> return "Android service dump failed"
            }
        }
        return null
    }
}
