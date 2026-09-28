package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.settings.AppSettings
import app.batstats.settings.useFahrenheit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/** The ongoing notification's update stream, before formatting: [DrainNotificationManager.run] gates and posts it. */
object NotificationInputs {
    /** What the notification shows, and whether the screen is on (the update gate's other input). */
    data class Update(val input: NotificationInput, val screenOn: Boolean)

    /**
     * Each reading carries its held estimate ([EtaHold]); only an open DISCHARGE session counts (Now's "since unplug"
     * window); only the status-icon and temperature settings matter. With no capture yet the screen counts as on,
     * so the first content is pushed at once.
     */
    fun of(
        realtime: Flow<BatteryRepository.Realtime>,
        activeSession: Flow<ChargeSession?>,
        settings: Flow<AppSettings>,
        issue: Flow<NotificationIssue?>,
    ): Flow<Update> {
        val readings = realtime.scan(EtaHold.Reading()) { held, reading -> EtaHold.next(held, reading) }.drop(1)
        val session = activeSession.map { open -> open?.takeIf { it.type == SessionType.DISCHARGE } }.distinctUntilChanged()
        val options = settings.map { it.statusIconValue to it.useFahrenheit }.distinctUntilChanged()
        return combine(readings, session, options, issue.distinctUntilChanged()) { reading, open, (icon, fahrenheit), problem ->
            Update(NotificationInput(reading, open, icon, fahrenheit, problem), screenOn = reading.reading.sample?.screenOn != false)
        }
    }
}
