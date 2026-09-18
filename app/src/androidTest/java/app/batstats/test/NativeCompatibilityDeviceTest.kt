package app.batstats.test

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android linker and process page-size checks; no simulated battery input is involved. */
@RunWith(AndroidJUnit4::class)
class NativeCompatibilityDeviceTest {
    @Test fun processUsesTheRequestedPageSizeAndBundledNativeLibrariesLink() {
        DeviceEnvironment.requireDisposableEmulator()
        val shellPageSize = DeviceEnvironment.device.executeShellCommand("getconf PAGE_SIZE").trim().toLong()
        val requested = InstrumentationRegistry.getArguments().getString("expectedPageSize")?.toLong() ?: shellPageSize
        assertTrue("Unsupported page-size test environment", requested in setOf(4096L, 16384L))
        assertEquals("The selected system image must match the requested page size", requested, shellPageSize)
        assertEquals("App process must use the requested page size", requested, Os.sysconf(OsConstants._SC_PAGESIZE))
        // Explicit linking also covers dependencies not exercised by the current screen's API path.
        System.loadLibrary("androidx.graphics.path")
        System.loadLibrary("datastore_shared_counter")
    }
}
