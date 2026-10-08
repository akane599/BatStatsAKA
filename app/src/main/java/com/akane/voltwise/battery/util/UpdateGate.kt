package com.akane.voltwise.battery.util

import kotlinx.coroutines.delay

/**
 * When a display surface (the ongoing notification, the widgets) is refreshed: only while the
 * screen is on, only when its content [K] changed, and at least [minIntervalMs] apart (uptime).
 * The first update after the screen turns on is pushed at once. Not thread-safe: one collector.
 */
class UpdateGate<K : Any>(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    sealed interface Decision {
        data object Push : Decision
        data object Skip : Decision
        /** Changed content within [minIntervalMs] of the last push: decide again after [delayMs]. */
        data class Wait(val delayMs: Long) : Decision
    }

    private var lastKey: K? = null
    private var lastPushMs: Long? = null
    private var screenOn = false

    fun decide(key: K, screenOn: Boolean, nowMs: Long): Decision {
        val turnedOn = screenOn && !this.screenOn
        this.screenOn = screenOn
        val last = lastPushMs
        return when {
            !screenOn -> Decision.Skip
            turnedOn -> Decision.Push
            key == lastKey -> Decision.Skip
            last == null || nowMs - last >= minIntervalMs -> Decision.Push
            else -> Decision.Wait(minIntervalMs - (nowMs - last))
        }
    }

    /** Records a push attempt at [nowMs]; a null [key] (failed push) lets the same content retry later. */
    fun pushed(key: K?, nowMs: Long) {
        lastKey = key
        lastPushMs = nowMs
    }

    /**
     * Suspends through any [Decision.Wait] and returns whether [key] may be pushed now. Collect with
     * `collectLatest`, so a newer update cancels a pending wait and the latest content wins.
     */
    suspend fun awaitTurn(key: K, screenOn: Boolean, clock: () -> Long): Boolean {
        while (true) {
            when (val decision = decide(key, screenOn, clock())) {
                Decision.Push -> return true
                Decision.Skip -> return false
                is Decision.Wait -> delay(decision.delayMs)
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 5_000L
    }
}
