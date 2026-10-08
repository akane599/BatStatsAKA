package com.akane.voltwise.battery.apps

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppInfoCacheTest {
    private data class Icon(val packageName: String, val version: Int)

    private class FakeLookup : PackageLookup<Icon> {
        val installed = mutableMapOf<String, AppInfo>()
        val icons = mutableMapOf<String, Icon>()
        val infoReads = mutableListOf<String>()
        val iconReads = mutableListOf<String>()
        var readThread: String? = null

        override fun info(packageName: String): AppInfo? {
            infoReads += packageName
            readThread = Thread.currentThread().name
            return installed[packageName]
        }

        override fun icon(packageName: String): Icon? {
            iconReads += packageName
            return icons[packageName]
        }
    }

    private class MapIconStore : IconStore<Icon> {
        val map = mutableMapOf<String, Icon>()
        override fun get(packageName: String) = map[packageName]
        override fun put(packageName: String, icon: Icon) { map[packageName] = icon }
        override fun remove(packageName: String) { map.remove(packageName) }
        override fun clear() = map.clear()
    }

    private class LatchedRead<T>(private val value: T) {
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun read(): T {
            started.countDown()
            assertTrue("Lookup must be released", released.await(10, TimeUnit.SECONDS))
            return value
        }

        fun awaitStarted() {
            assertTrue("Lookup must start", started.await(10, TimeUnit.SECONDS))
        }

        fun release() = released.countDown()
    }

    private class LatchedLookup : PackageLookup<Icon> {
        val oldInfo = AppInfo("com.example", "Old", isSystem = false, installed = true)
        val newInfo = AppInfo("com.example", "New", isSystem = true, installed = true)
        val oldIcon = Icon("com.example", 1)
        val newIcon = Icon("com.example", 2)
        val infoRead = LatchedRead(oldInfo)
        val iconRead = LatchedRead(oldIcon)
        val infoReads = AtomicInteger()
        val iconReads = AtomicInteger()

        override fun info(packageName: String): AppInfo =
            if (infoReads.getAndIncrement() == 0) infoRead.read() else newInfo

        override fun icon(packageName: String): Icon =
            if (iconReads.getAndIncrement() == 0) iconRead.read() else newIcon
    }

    private suspend fun TestScope.withLatchedLookup(block: suspend (AppInfoCache<Icon>, LatchedLookup) -> Unit) {
        val lookup = LatchedLookup()
        val io = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            block(AppInfoCache(lookup, MapIconStore(), io), lookup)
        } finally {
            lookup.infoRead.release()
            lookup.iconRead.release()
            io.close()
        }
    }

    private val lookup = FakeLookup().apply {
        installed["com.example"] = AppInfo("com.example", "Example", isSystem = false, installed = true)
        installed["com.android.phone"] = AppInfo("com.android.phone", "Phone", isSystem = true, installed = true)
        icons["com.example"] = Icon("com.example", 1)
    }
    private val icons = MapIconStore()

    private fun TestScope.cache() = AppInfoCache(lookup, icons, StandardTestDispatcher(testScheduler, "io"))

    @Test fun labelsAndIconsAreLoadedOnceAndServedFromTheCache() = runTest {
        val cache = cache()
        assertEquals("Example", cache.info("com.example").label)
        assertEquals("Example", cache.info("com.example").label)
        assertEquals(Icon("com.example", 1), cache.icon("com.example"))
        assertEquals(Icon("com.example", 1), cache.icon("com.example"))
        assertEquals(listOf("com.example"), lookup.infoReads)
        assertEquals(listOf("com.example"), lookup.iconReads)
        assertTrue(cache.info("com.android.phone").isSystem)
    }

    @Test fun aMissingPackageFallsBackToItsNameWithoutAnIcon() = runTest {
        val cache = cache()
        assertEquals(AppInfo("gone.app", "gone.app", isSystem = false, installed = false), cache.info("gone.app"))
        assertNull(cache.icon("gone.app"))
        lookup.installed["blank.label"] = AppInfo("blank.label", " ", isSystem = false, installed = true)
        assertEquals("blank.label", cache.info("blank.label").label)
    }

    @Test fun aPackageBroadcastReloadsOnlyThatPackage() = runTest {
        val cache = cache()
        cache.info("com.example"); cache.icon("com.example"); cache.info("com.android.phone")
        lookup.installed["com.example"] = AppInfo("com.example", "Example 2", isSystem = false, installed = true)
        lookup.icons["com.example"] = Icon("com.example", 2)
        cache.invalidate("com.example")
        assertEquals("Example 2", cache.info("com.example").label)
        assertEquals(Icon("com.example", 2), cache.icon("com.example"))
        cache.info("com.android.phone")
        assertEquals(1, lookup.infoReads.count { it == "com.android.phone" })
    }

    @Test fun theCachedIconPeekNeverLoads() = runTest {
        val cache = cache()
        assertNull(cache.cachedIcon("com.example"))
        assertTrue(lookup.iconReads.isEmpty())
        cache.icon("com.example")
        assertEquals(Icon("com.example", 1), cache.cachedIcon("com.example"))
        cache.invalidate("com.example")
        assertNull(cache.cachedIcon("com.example"))
        assertEquals(1, lookup.iconReads.size)
    }

    @Test fun trimClearsEverything() = runTest {
        val cache = cache()
        cache.info("com.example"); cache.icon("com.example")
        cache.clear()
        assertTrue(icons.map.isEmpty())
        cache.info("com.example"); cache.icon("com.example")
        assertEquals(2, lookup.infoReads.size)
        assertEquals(2, lookup.iconReads.size)
    }

    @Test fun invalidateDuringInfoLookupPreventsStalePublication() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.info("com.example") }
            lookup.infoRead.awaitStarted()
            cache.invalidate("com.example")
            lookup.infoRead.release()
            assertEquals(lookup.oldInfo, old.await())
            assertEquals(lookup.newInfo, cache.info("com.example"))
            assertEquals(lookup.newInfo, cache.info("com.example"))
            assertEquals(2, lookup.infoReads.get())
        }
    }

    @Test fun invalidateDuringIconLookupPreventsStalePublication() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.icon("com.example") }
            lookup.iconRead.awaitStarted()
            cache.invalidate("com.example")
            lookup.iconRead.release()
            assertEquals(lookup.oldIcon, old.await())
            assertNull(cache.cachedIcon("com.example"))
            assertEquals(lookup.newIcon, cache.icon("com.example"))
            assertEquals(lookup.newIcon, cache.cachedIcon("com.example"))
            assertEquals(2, lookup.iconReads.get())
        }
    }

    @Test fun clearDuringInfoLookupPreventsStalePublication() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.info("com.example") }
            lookup.infoRead.awaitStarted()
            cache.clear()
            lookup.infoRead.release()
            assertEquals(lookup.oldInfo, old.await())
            assertEquals(lookup.newInfo, cache.info("com.example"))
            assertEquals(2, lookup.infoReads.get())
        }
    }

    @Test fun clearDuringIconLookupPreventsStalePublication() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.icon("com.example") }
            lookup.iconRead.awaitStarted()
            cache.clear()
            lookup.iconRead.release()
            assertEquals(lookup.oldIcon, old.await())
            assertNull(cache.cachedIcon("com.example"))
            assertEquals(lookup.newIcon, cache.icon("com.example"))
            assertEquals(2, lookup.iconReads.get())
        }
    }

    @Test fun olderInfoLookupCannotOverwriteNewerCompletion() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.info("com.example") }
            lookup.infoRead.awaitStarted()
            assertEquals(lookup.newInfo, cache.info("com.example"))
            lookup.infoRead.release()
            assertEquals(lookup.oldInfo, old.await())
            assertEquals(lookup.newInfo, cache.info("com.example"))
            assertEquals(2, lookup.infoReads.get())
        }
    }

    @Test fun olderIconLookupCannotOverwriteNewerCompletion() = runTest {
        withLatchedLookup { cache, lookup ->
            val old = async(start = CoroutineStart.UNDISPATCHED) { cache.icon("com.example") }
            lookup.iconRead.awaitStarted()
            assertEquals(lookup.newIcon, cache.icon("com.example"))
            lookup.iconRead.release()
            assertEquals(lookup.oldIcon, old.await())
            assertEquals(lookup.newIcon, cache.icon("com.example"))
            assertEquals(lookup.newIcon, cache.cachedIcon("com.example"))
            assertEquals(2, lookup.iconReads.get())
        }
    }

    @Test fun lookupsRunOnTheIoDispatcher() = runTest {
        val io = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }.asCoroutineDispatcher()
        try {
            AppInfoCache(lookup, icons, io).info("com.example")
            assertEquals("fake-io", lookup.readThread)
        } finally {
            io.close()
        }
    }
}
