package app.batstats.viewmodel

import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.SessionDrain
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.DailySummary
import app.batstats.battery.data.sampling.ChargerType
import app.batstats.battery.data.sampling.DailySummaryReplay
import app.batstats.battery.measurement.BatteryReading
import app.batstats.battery.measurement.CalibrationState
import app.batstats.battery.measurement.CurrentCalibration
import app.batstats.battery.measurement.EtaBasis
import app.batstats.battery.measurement.HealthSummary
import app.batstats.battery.measurement.PowerState
import app.batstats.ui.components.chart.TimePoint
import app.batstats.ui.components.chart.TimeWindow

/**
 * How [NowViewModel] turns repository values into [NowUiState] parts. Pure; the reusable rules live below it
 * ([HealthSummary], [SessionDrain], `TopApps`, `AppLabel`), this is only Now's presentation of them.
 */
internal object NowMapping {
    const val DOWNSAMPLE_ABOVE = 2_400
    const val DOWNSAMPLE_BUCKETS = 600

    /** The live trace breaks where readings are further apart than 3 screen-on polls (the app was away). */
    const val LIVE_MAX_GAP_MS = 95_000L

    /** A capture without an ETA keeps the last one this long (the writer adds it just after the capture). */
    const val ETA_HOLD_MS = 60_000L

    /** A reading with the ETA it shows: the newest one, or the previous one held across a capture without it. */
    data class ReadingEta(
        val reading: BatteryRepository.Realtime = BatteryRepository.Realtime(),
        val eta: HeldEta? = null,
    )

    data class HeldEta(val remainingMs: Long, val basis: EtaBasis?, val power: PowerState, val atMs: Long)

    /**
     * Realtime first carries the raw capture (charging: Android's time to full; discharging: none), then the writer's
     * copy with its estimate; the next capture drops that again. Keep the last estimate while the power state holds,
     * for [ETA_HOLD_MS], counted down to this reading.
     */
    fun withEta(previous: ReadingEta, reading: BatteryRepository.Realtime): ReadingEta {
        val sample = reading.sample ?: return previous.copy(reading = reading)
        val power = reading.powerState
        val eta = sample.etaMs?.takeIf { it > 0 }
        val held = when {
            eta != null -> HeldEta(eta, EtaBasis.entries.firstOrNull { it.name == sample.etaBasis }, power, sample.timestamp)
            else -> previous.eta?.takeIf { it.power == power && sample.timestamp - it.atMs in 0..ETA_HOLD_MS }
        }
        return ReadingEta(reading, held)
    }

    /**
     * Time left needs monitoring (the discharge estimate is the writer's); time to full is shown while charging even
     * without it, because every capture carries Android's own.
     */
    fun hero(reading: ReadingEta, monitoring: Boolean, startBlocked: Boolean): HeroState {
        val realtime = reading.reading
        val sample = realtime.sample
        val showEta = sample != null && (monitoring || realtime.powerState == PowerState.CHARGING)
        val eta = if (showEta && sample != null) {
            reading.eta?.let { held -> Eta((held.remainingMs - (sample.timestamp - held.atMs)).coerceAtLeast(0), held.basis) }
        } else null
        return HeroState(
            hasReading = sample != null,
            level = realtime.level,
            power = realtime.powerState,
            charger = ChargerType.of(realtime.plugged),
            eta = eta,
            monitoring = monitoring,
            startBlocked = startBlocked && !monitoring,
        )
    }

    fun readouts(reading: BatteryRepository.Realtime) = Readouts(
        currentMa = reading.currentMa,
        powerW = reading.powerMw?.div(1_000),
        temperatureC = reading.temperatureC?.toDouble(),
        voltageV = reading.voltageMv?.div(1_000.0),
    )

    /** The fuel gauge's full charge from the latest reading, for %/h (null → the Health estimate stands in). */
    fun counterFullUah(reading: BatteryRepository.Realtime): Long? =
        HealthSummary.counterFullUah(reading.sample?.chargeCounterUah, reading.level)

    /** Adds [sample] when newer than the last one and drops what fell out of the live window; true if added. */
    fun appendLive(buffer: MutableList<BatterySample>, sample: BatterySample): Boolean {
        if (sample.source != DailySummaryReplay.SAMPLE_SOURCE) return false
        val last = buffer.lastOrNull()
        if (last != null && sample.timestamp <= last.timestamp) return false
        buffer += sample
        val cutoff = sample.timestamp - TraceRange.LIVE.spanMs
        buffer.removeAll { it.timestamp < cutoff }
        return true
    }

    /**
     * Rows → calibrated mA points in [endMs]'s window (plus one row before it, so the line reaches the edge). A gap
     * marker goes where monitoring restarted (a new observation id) or a row recorded an interruption.
     */
    fun trace(range: TraceRange, samples: List<BatterySample>, endMs: Long, calibration: CurrentCalibration): TraceState {
        val startMs = endMs - range.spanMs
        val live = samples.filter { it.source == DailySummaryReplay.SAMPLE_SOURCE }
        val first = (live.indexOfFirst { it.timestamp >= startMs }.takeIf { it >= 0 } ?: live.size).minus(1).coerceAtLeast(0)
        val points = ArrayList<TimePoint>()
        var previous: BatterySample? = null
        for (sample in live.subList(first, live.size)) {
            val before = previous
            if (before != null && (before.observationId != sample.observationId || sample.boundaryReason != null)) {
                points += TimePoint((before.timestamp + sample.timestamp) / 2, null)
            }
            points += TimePoint(sample.timestamp, BatteryReading.calibratedUa(sample.currentNowUa, calibration)?.div(1_000.0))
            previous = sample
        }
        return TraceState(
            range = range,
            points = points,
            window = TimeWindow(startMs, endMs),
            maxGapMs = if (range == TraceRange.LIVE) LIVE_MAX_GAP_MS else null,
        )
    }

    /**
     * [newest] is the newest DISCHARGE session. It is the current window only while it is open and monitoring runs
     * (a row left open by a stopped process is history); otherwise it is shown as the last time on battery, ending at
     * its end or last save. All figures come from that one row, so windows never mix.
     */
    fun sinceUnplug(newest: ChargeSession?, monitoring: Boolean, fullUah: Long?): SinceUnplugState? {
        newest ?: return null
        val drain = SessionDrain.of(newest, fullUah)
        return SinceUnplugState(
            current = newest.endTime == null && monitoring,
            startedAtMs = newest.startTime,
            endedAtMs = newest.endTime ?: newest.lastSampleTime ?: newest.startTime,
            screenOn = DrainState(drain.screenOn.durationMs, drain.screenOn.currentMa, drain.screenOn.percentPerHour),
            screenOff = DrainState(drain.screenOff.durationMs, drain.screenOff.currentMa, drain.screenOff.percentPerHour),
            deepSleepPercent = drain.deepSleepPercent,
        )
    }

    fun today(row: DailySummary) = TodayState(
        usedMah = (row.screenOnDischargeUah + row.screenOffDischargeUah) / 1_000.0,
        chargedMah = row.chargedUah / 1_000.0,
        screenOnMs = row.screenOnMs,
    )

    /** The newest sessions' stored estimates → [HealthSummary] (the rule the Health screen shares). */
    fun healthSummary(sessions: List<ChargeSession>, designOverrideMah: Int): HealthSummary? = HealthSummary.of(
        sessions.mapNotNull { HealthSummary.storedEstimate(it.capacityEstimateMah, it.capacityConfidence, it.capacityBasis) },
        designOverrideMah,
    )

    fun health(summary: HealthSummary) = HealthState(summary.estimate.fullMah, summary.estimate.confidence, summary.healthPercent)

    fun notice(state: CalibrationState): CurrentCalibration? =
        if (state.noticePending) state.detected ?: state.effective else null
}
