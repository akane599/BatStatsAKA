package app.batstats.battery.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat

object PrivilegeChecker {

    private fun hasDump(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun hasAdvancedViaAdb(context: Context): Boolean {
        // Android16 BatteryStatsService -> DumpUtils.checkDumpAndUsageStatsPermission.
        if (!hasDump(context) || ContextCompat.checkSelfPermission(context,
                Manifest.permission.PACKAGE_USAGE_STATS) != PackageManager.PERMISSION_GRANTED) return false
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName) }.getOrNull()
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

}
