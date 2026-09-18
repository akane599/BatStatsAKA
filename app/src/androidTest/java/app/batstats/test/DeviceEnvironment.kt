package app.batstats.test

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import java.io.File

/** These tests change simulated battery/display state only on a disposable emulator. */
object DeviceEnvironment {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun requireDisposableEmulator() {
        assertEquals("Only the separate development package may be changed", "org.mlm.batstats.debug", context.packageName)
        assertTrue("Run on an emulator, never a personal device", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("This suite verifies the Android 16 contract", 36, Build.VERSION.SDK_INT)
    }
    fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "validation-screenshots").apply { mkdirs() }
        assertTrue("Screenshot capture failed: $name", device.takeScreenshot(File(directory, "$name.png")))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}

/** Run explicitly after installing/starting official Shizuku; an absent service is a failure. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresShizuku
