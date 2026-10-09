package com.akane.voltwise.battery.service

/**
 * A visible surface that needs realtime (2 s) readings: the Now screen, an active session's
 * details, or the QS tile while the shade is open. Holding no token lets sampling fall back to
 * 30 s (screen on) or 300 s (screen off).
 */
interface SamplingDemand {
    /** Safe from any thread. Close the returned token to release it; closing twice is a no-op. */
    fun acquire(tag: String): AutoCloseable
}
