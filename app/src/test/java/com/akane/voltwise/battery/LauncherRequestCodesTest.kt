package com.akane.voltwise.battery

import com.akane.voltwise.battery.tile.MonitorTileService
import com.akane.voltwise.battery.util.Notifier
import com.akane.voltwise.battery.widget.WidgetUpdater
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LauncherRequestCodesTest {
    @Test
    fun tileAndWidgetDoNotShareActivityPendingIntentIdentity() {
        assertNotEquals(
            "Extras do not distinguish the tile's NOW launch from the widget's default launch",
            MonitorTileService.OPEN_APP_REQUEST_CODE,
            WidgetUpdater.OPEN_APP_REQUEST_CODE,
        )
    }

    @Test
    fun tileAndNotifierDoNotShareActivityPendingIntentIdentity() {
        assertNotEquals(Notifier.OPEN_APP_REQUEST_CODE, MonitorTileService.OPEN_APP_REQUEST_CODE)
    }

    @Test
    fun widgetAndNotifierDoNotShareActivityPendingIntentIdentity() {
        assertNotEquals(Notifier.OPEN_APP_REQUEST_CODE, WidgetUpdater.OPEN_APP_REQUEST_CODE)
    }
}
