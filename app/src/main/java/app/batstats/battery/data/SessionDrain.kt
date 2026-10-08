package app.batstats.battery.data

import app.batstats.battery.data.db.ChargeSession

/** One screen state's discharge in a session: its duration, average drain (mA, positive) and %/h of capacity. */
data class DrainRate(val durationMs: Long, val currentMa: Double?, val percentPerHour: Double?)

/**
 * A DISCHARGE session's drain as stored on its row (rewritten at every save): screen on and screen off, and deep
 * sleep over the session. Now's "Since unplug" shows it; SessionDetails can reuse it.
 */
data class SessionDrain(val screenOn: DrainRate, val screenOff: DrainRate, val deepSleepPercent: Double?) {
    companion object {
        /** Like ObservedBucket.rateMa: no average from less than a minute of counter data. */
        private const val MIN_RATE_MS = 60_000L

        /**
         * The row keeps each screen state's charge (`screenOnUah`/`screenOffUah`, null without counter data) and
         * duration and matched counter coverage. Each screen rate uses its own covered time; legacy rows with
         * null coverage retain the complete-session-coverage rule and exact bucket duration. %/h needs
         * [fullUah] (the counter's full charge, or the Health estimate). Deep sleep = `cpuSuspendMs` ÷ `observedMs`.
         */
        fun of(session: ChargeSession, fullUah: Long?): SessionDrain {
            val dischargeMs = session.screenOnMs + session.screenOffMs
            val completeCoverage = session.counterCoveredMs >= dischargeMs
            fun rate(durationMs: Long, uah: Long?, coveredMs: Long?): DrainRate {
                val measuredMs = coveredMs ?: durationMs.takeIf { completeCoverage }
                val milliamps = if (uah != null && measuredMs != null && measuredMs >= MIN_RATE_MS) uah * 3_600.0 / measuredMs else null
                val perHour = if (milliamps != null && fullUah != null && fullUah > 0) milliamps * 100_000 / fullUah else null
                return DrainRate(durationMs, milliamps, perHour)
            }
            return SessionDrain(
                screenOn = rate(session.screenOnMs, session.screenOnUah, session.screenOnCoveredMs),
                screenOff = rate(session.screenOffMs, session.screenOffUah, session.screenOffCoveredMs),
                deepSleepPercent = session.cpuSuspendMs?.takeIf { session.observedMs > 0 }?.let { it * 100.0 / session.observedMs },
            )
        }
    }
}
