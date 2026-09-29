package app.batstats.ui.navigation

/**
 * String values for the `destination` intent extra used by deep links: notification tap today;
 * the QS tile long-press and widgets follow in P5. [app.batstats.battery.BatteryMainActivity]
 * reads [EXTRA_DESTINATION] once per intent in `onCreate`/`onNewIntent` and removes the extra so
 * it isn't replayed on rotation. [app.batstats.ui.navigation.openDestination] applies a value to a
 * [TopLevelBackStack].
 */
object Destinations {
    const val EXTRA_DESTINATION = "destination"

    const val NOW = "now"
    const val HISTORY = "history"
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val HEALTH = "health"
    const val STATUS = "status"

    private const val SESSION_PREFIX = "session:"

    fun session(sessionId: String) = "$SESSION_PREFIX$sessionId"

    fun sessionIdOrNull(value: String): String? =
        value.takeIf { it.startsWith(SESSION_PREFIX) }?.removePrefix(SESSION_PREFIX)
}
