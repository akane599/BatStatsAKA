package app.batstats.battery.shizuku

import app.batstats.battery.util.BatteryStatsParser

/** Compatibility view of Android estimates; UID totals must never be split among shared packages. */
object CheckinParser {
    data class Snapshot(val energyByUid: Map<Int, Double>, val packagesByUid: Map<Int, List<String>>, val perPackageMah: Map<String, Double>)
    fun parse(lines: Sequence<String>): Snapshot {
        val apps = BatteryStatsParser.parseCheckin(lines.joinToString("\n")).apps
        return Snapshot(apps.associate { it.uid to it.powerMah }, apps.associate { it.uid to it.packages },
            apps.associate { "uid:${it.uid}" to it.powerMah })
    }
    fun displayNameFor(uid: Int, packagesByUid: Map<Int, List<String>>): String =
        BatteryStatsParser.displayNameFor(uid, packagesByUid[uid].orEmpty())
}
