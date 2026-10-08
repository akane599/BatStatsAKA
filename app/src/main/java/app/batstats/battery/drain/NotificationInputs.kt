package app.batstats.battery.drain

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.resolveFullUah
import app.batstats.battery.data.storedFullUah
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.measurement.EtaHold
import app.batstats.settings.AppSettings
import app.batstats.settings.useFahrenheit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/** The ongoing notification's update stream, before formatting: [DrainNotificationManager.run] gates and posts it. */
object NotificationInputs {
    /** What the notification shows, the update gate's screen state, and capacity reserved for drain conversion. */
    data class Update(val input: NotificationInput, val screenOn: Boolean, val fullUah: Long? = null)

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
        recentSessions: Flow<List<ChargeSession>> = flowOf(emptyList()),
    ): Flow<Update> {
        val readings = realtime.scan(EtaHold.Reading()) { held, reading -> EtaHold.next(held, reading) }.drop(1)
        val session = activeSession.map { open -> open?.takeIf { it.type == SessionType.DISCHARGE } }.distinctUntilChanged()
        val options = settings.map { it.statusIconValue to it.useFahrenheit }.distinctUntilChanged()
        val storedEstimate = recentSessions.map(::storedFullUah).distinctUntilChanged()
        return combine(readings, session, options, issue.distinctUntilChanged(), storedEstimate) { reading, open, (icon, fahrenheit), problem, stored ->
            Update(
                input = NotificationInput(reading, open, icon, fahrenheit, problem),
                screenOn = reading.reading.sample?.screenOn != false,
                fullUah = resolveFullUah(reading.reading.sample?.chargeCounterUah, reading.reading.level, stored),
            )
        }
    }
}
