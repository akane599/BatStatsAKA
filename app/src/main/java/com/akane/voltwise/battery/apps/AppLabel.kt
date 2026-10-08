package com.akane.voltwise.battery.apps

import com.akane.voltwise.battery.util.BatteryStatsParser

/**
 * What a per-app row is called. Never a raw id: a batterystats row without an installed, labelled package is a
 * [SystemProcess] (a system uid such as 1000 "system" or 1041 "audioserver") or an [Unknown] app (an uninstalled app
 * or an app uid with no package name). The UI turns the two into its own strings.
 */
sealed interface AppLabel {
    data class Named(val text: String) : AppLabel
    data object SystemProcess : AppLabel
    data object Unknown : AppLabel

    companion object {
        /**
         * [info]'s label when the package is installed and has a real one (not blank, not just its package name);
         * otherwise [SystemProcess] for system uids (app id below 10000, any user) and [Unknown] for the rest.
         * [packageName] may be A2's display name for uid-only rows ("System UID 1000", "UID 10123").
         */
        fun of(uid: Int, packageName: String, info: AppInfo?): AppLabel {
            val label = info?.takeIf { it.installed }?.label?.trim()
            return when {
                !label.isNullOrEmpty() && label != packageName -> Named(label)
                uid >= 0 && BatteryStatsParser.isSystemUid(uid) -> SystemProcess
                else -> Unknown
            }
        }
    }
}
