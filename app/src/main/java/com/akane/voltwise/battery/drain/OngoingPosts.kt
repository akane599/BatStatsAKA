package com.akane.voltwise.battery.drain

/**
 * The ongoing notification across one monitoring session, without Android so JVM tests can drive it ([N] is the
 * built notification). Every notification of a session (the startup placeholder, each full update, a re-promotion)
 * is built with the one [whenMs] taken at [start]: AOSP 16 maps `when` = 0 to the post's fresh creation time
 * (Notification.getWhen), and shades that sort by time then move the notification to the top on every update.
 * Updates replace it in place under one id; only [stop] cancels it.
 */
internal class OngoingPosts<N : Any>(private val clock: () -> Long, private val poster: Poster<N>) {
    interface Poster<N> {
        fun show(notification: N)
        fun cancel()
    }

    /** The session's `when` (wall clock, ms): taken once at [start], 0 before. */
    var whenMs = 0L
        private set
    private var lastPosted: N? = null

    /** A new monitoring session: its own `when`, kept until the next start. */
    fun start(): Long {
        whenMs = clock()
        lastPosted = null
        return whenMs
    }

    fun post(notification: N) {
        poster.show(notification)
        lastPosted = notification
    }

    /** Re-promoting a running service must not replace the gated live content with startup defaults. */
    fun promotion(fallback: () -> N): N = promotionNotification(lastPosted, fallback)

    fun stop() {
        lastPosted = null
        poster.cancel()
    }
}
