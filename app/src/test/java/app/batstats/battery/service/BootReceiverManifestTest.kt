package app.batstats.battery.service

import android.content.Intent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BootReceiverManifestTest {
    @Test
    fun bootReceiverRegistersBothResumeActionsInItsFilter() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val receivers = document.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttribute("android:name") == ".battery.service.BootReceiver" }
        val filters = receiver.getElementsByTagName("intent-filter")
        val actionsByFilter = (0 until filters.length).map { filterIndex ->
            val actions = (filters.item(filterIndex) as Element).getElementsByTagName("action")
            (0 until actions.length).map { (actions.item(it) as Element).getAttribute("android:name") }
        }

        assertTrue(
            "BootReceiver must register BOOT_COMPLETED and MY_PACKAGE_REPLACED in its intent filter: $actionsByFilter",
            actionsByFilter.any { it.containsAll(listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) },
        )
    }
}
