package app.batstats.test

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
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
        requireDisposableEmulator()
        require(name.matches(Regex("[a-z0-9-]+")))
        // AGP creates its collection directory as shell before installing the package.
        // Capture as the app in private storage, then publish through the test shell.
        val directory = File(context.cacheDir, "validation-screenshots").apply { mkdirs() }
        val png = File(directory, "$name.png")
        val xml = File(directory, "$name.xml")
        val output = "/sdcard/Download/batstats-validation-screenshots"
        try {
            assertTrue("Screenshot capture failed: $name", device.takeScreenshot(png))
            device.dumpWindowHierarchy(xml)
            val command = "mkdir -p '$output' && " +
                "run-as ${context.packageName} cat '${png.absolutePath}' > '$output/$name.png' && " +
                "run-as ${context.packageName} cat '${xml.absolutePath}' > '$output/$name.xml' && echo BATSTATS_CAPTURE_OK\n"
            // UiAutomation's string command uses Runtime.exec, which does not interpret
            // pipes/redirection/quotes. Feed the fixed script to an actual shell over stdin.
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
            val result = try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use { it.write(command) }
                ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
            } finally { pipes.forEach { it.close() } }
            assertTrue("Screenshot publication failed: $name ($result)", result.trim().endsWith("BATSTATS_CAPTURE_OK"))
        } finally {
            png.delete(); xml.delete()
        }
    }
}

/** Run explicitly after installing/starting official Shizuku; an absent service is a failure. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresShizuku
