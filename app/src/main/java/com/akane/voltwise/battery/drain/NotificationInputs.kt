package com.akane.voltwise.battery.drain

import com.akane.voltwise.battery.data.BatteryRepository
import com.akane.voltwise.battery.data.resolveFullUah
import com.akane.voltwise.battery.data.storedFullUah
import com.akane.voltwise.battery.data.db.ChargeSession
import com.akane.voltwise.battery.data.db.SessionType
import com.akane.voltwise.battery.measurement.EtaHold
import com.akane.voltwise.settings.AppSettings
import com.akane.voltwise.settings.useFahrenheit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan

/** The ongoing notification's update stream, before formatting: [DrainNotificationManager.run] gates and posts it. */
object NotificationInputs {
    /** What the notification shows (with the full capacity for its %/h rates) and the update gate's screen state. */
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
        recentSessions: Flow<List<ChargeSession>> = flowOf(emptyList()),
    ): Flow<Update> {
        val readings = realtime.scan(EtaHold.Reading()) { held, reading -> EtaHold.next(held, reading) }.drop(1)
        val session = activeSession.withHistoryFallback(null)
            .map { open -> open?.takeIf { it.type == SessionType.DISCHARGE } }.distinctUntilChanged()
        val options = settings.map { it.statusIconValue to it.useFahrenheit }.distinctUntilChanged()
        val storedEstimate = recentSessions.withHistoryFallback(emptyList()).map(::storedFullUah).distinctUntilChanged()
        return combine(readings, session, options, issue.distinctUntilChanged(), storedEstimate) { reading, open, (icon, fahrenheit), problem, stored ->
            Update(
                input = NotificationInput(reading, open, icon, fahrenheit, problem,
                    fullUah = resolveFullUah(reading.reading.sample?.chargeCounterUah, reading.reading.level, stored)),
                screenOn = reading.reading.sample?.screenOn != false,
            )
        }
    }

    // Clear unavailable history immediately so live readings continue during the query retry backoff.
    // Kept before map/combine: formatting and downstream failures must not be hidden as database failures.
    private fun <T> Flow<T>.withHistoryFallback(fallback: T): Flow<T> = retryWhen { cause, _ ->
        if (cause is CancellationException || cause !is Exception) throw cause
        emit(fallback)
        delay(HISTORY_RETRY_MS)
        true
    }

    private const val HISTORY_RETRY_MS = 60_000L
}
