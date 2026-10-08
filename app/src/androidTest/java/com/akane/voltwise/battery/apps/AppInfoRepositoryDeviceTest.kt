package com.akane.voltwise.battery.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AppInfoRepositoryDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun labelsFlagsAndFortyEightDpIconsForInstalledAndMissingPackages() = runBlocking {
        val repository = AppInfoRepository(context)
        val own = repository.info(context.packageName)
        assertTrue(own.installed)
        assertFalse(own.isSystem)
        assertNotEquals(context.packageName, own.label)
        assertTrue("QUERY_ALL_PACKAGES sees system apps", repository.info("android").isSystem)

        val size = (AppInfoRepository.ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
        val icon = repository.icon(context.packageName)
        assertNotNull(icon)
        assertEquals(size, icon!!.width)
        assertEquals(size, icon.height)
        assertSame("Served from the cache", icon, repository.icon(context.packageName))
        repository.onTrimMemory()
        assertNotSame("Trim cleared the cache", icon, repository.icon(context.packageName))

        assertEquals(AppInfo("not.installed.example", "not.installed.example", isSystem = false, installed = false),
            repository.info("not.installed.example"))
        assertNull(repository.icon("not.installed.example"))
        assertNull(repository.icon(""))
    }
}
