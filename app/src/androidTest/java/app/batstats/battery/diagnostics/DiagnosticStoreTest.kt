package app.batstats.battery.diagnostics

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticStoreTest {
    @Test fun atomicallyPersistedLogSurvivesStoreRecreationWithoutPrivatePayloads() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "diagnostic-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DiagnosticStore(context, scope)
            repeat(50) { store.record(DiagnosticCode.ADVANCED_READ_FAILED) }
            val expected = withTimeout(30_000) { store.events.first { it.singleOrNull()?.count == 50 } }
            val file = File(directory, "collection-diagnostics.txt")
            withTimeout(30_000) {
                while (runCatching { DiagnosticLog.decode(file.readText()) }.getOrNull() != expected) delay(50)
            }
            assertTrue(file.length() < DiagnosticLog.MAX_BYTES)
            scope.cancel()
            val restored = DiagnosticStore(context, restoreScope)
            assertEquals(expected, withTimeout(30_000) { restored.events.first { it.isNotEmpty() } })
        } finally { scope.cancel(); restoreScope.cancel(); directory.deleteRecursively() }
    }
}
