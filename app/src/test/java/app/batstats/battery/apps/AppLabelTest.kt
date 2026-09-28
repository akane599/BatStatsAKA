package app.batstats.battery.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelTest {
    private fun installed(packageName: String, label: String) = AppInfo(packageName, label, isSystem = false, installed = true)
    private fun missing(packageName: String) = AppInfo(packageName, packageName, isSystem = false, installed = false)

    @Test fun anInstalledAppWithARealLabelIsNamed() {
        assertEquals(AppLabel.Named("Chrome"), AppLabel.of(10_123, "com.android.chrome", installed("com.android.chrome", " Chrome ")))
        // A system uid with a labelled package keeps its name ("android" → "Android System").
        assertEquals(AppLabel.Named("Android System"), AppLabel.of(1_000, "android", installed("android", "Android System")))
    }

    @Test fun systemUidsWithoutALabelAreSystemProcesses() {
        assertEquals(AppLabel.SystemProcess, AppLabel.of(1_000, "System UID 1000", missing("System UID 1000")))
        assertEquals(AppLabel.SystemProcess, AppLabel.of(1_041, "UID 1041", null))
        // Any user: the app id decides (user 10's audioserver).
        assertEquals(AppLabel.SystemProcess, AppLabel.of(1_001_041, "UID 1001041", null))
    }

    @Test fun everythingElseIsAnUnknownAppNeverARawId() {
        assertEquals(AppLabel.Unknown, AppLabel.of(10_555, "UID 10555", missing("UID 10555")))
        assertEquals(AppLabel.Unknown, AppLabel.of(10_556, "com.gone.app", missing("com.gone.app")))
        // Installed but its label is only its package name, or blank.
        assertEquals(AppLabel.Unknown, AppLabel.of(10_557, "com.no.label", installed("com.no.label", "com.no.label")))
        assertEquals(AppLabel.Unknown, AppLabel.of(10_558, "com.blank", installed("com.blank", " ")))
        assertEquals(AppLabel.Unknown, AppLabel.of(-1, "", null))
    }
}
