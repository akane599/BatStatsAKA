package com.akane.voltwise.battery.shizuku

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRestartTest {
    private class Binder(var alive: Boolean = true)

    /** api 13.1.5 caches callbacks by tag; remove=true leaves cleanup to the old binder's death. */
    private class Connections(private val binding: HelperBinding<Binder>) {
        class Connection(val callbacks: MutableSet<HelperBinding<Binder>> = mutableSetOf())

        var cached: Connection? = null
        var removed = false

        fun bind(): Connection = (cached ?: Connection().also { cached = it }).also {
            it.callbacks.add(binding)
        }

        fun unbind(remove: Boolean) {
            if (remove) {
                removed = true // Server record disappears synchronously; process death is asynchronous.
            } else {
                cached?.callbacks?.clear()
                cached = null
            }
        }

        fun died(connection: Connection) {
            connection.callbacks.forEach { it.disconnected() }
            connection.callbacks.clear()
            if (cached === connection) cached = null
        }

        fun connected(connection: Connection, binder: Binder) {
            connection.callbacks.forEach { it.connected(binder) }
        }
    }

    @Test fun firstCommandAfterRemovalSucceedsWhenOldDeathArrivesDuringRebind() = runTest {
        val binding = HelperBinding<Binder> { it.alive }
        val connections = Connections(binding)
        val oldConnection = connections.bind()
        val old = Binder()
        connections.connected(oldConnection, old)

        removeHelperService(connections::unbind)
        binding.reset()
        assertTrue("The helper process must still be removed", connections.removed)
        val attempt = binding.begin()
        val nextConnection = connections.bind()
        old.alive = false
        connections.died(oldConnection) // Happens after rebind but before the fresh binder broadcast.
        binding.died(old)
        val fresh = Binder()
        connections.connected(nextConnection, fresh)
        val bound = withTimeoutOrNull(10_000) { attempt.result.await() }
        binding.end(attempt)
        val result = if (bound == null) {
            ShizukuBridge.RunResult.Error("Could not start the Shizuku helper service", ShizukuBridge.Failure.BIND_FAILED)
        } else {
            ShizukuBridge.RunResult.Success("level: 50")
        }
        assertEquals("The first command must not lose its fresh binder callback", ShizukuBridge.RunResult.Success("level: 50"), result)
        assertSame(fresh, binding.current())
    }

    @Test fun oldDeathAfterFreshConnectionDoesNotDropTheFreshBinder() {
        val binding = HelperBinding<Binder> { it.alive }
        val connections = Connections(binding)
        val oldConnection = connections.bind()
        removeHelperService(connections::unbind)
        binding.reset()
        binding.begin()
        val freshConnection = connections.bind()
        val fresh = Binder()
        connections.connected(freshConnection, fresh)
        connections.died(oldConnection)
        assertSame(fresh, binding.current())
        assertSame("The old registry cleanup must leave the new entry intact", freshConnection, connections.cached)
    }

    @Test fun removalDetachesOldCallbacksBeforeAnAlreadyQueuedConnectionNotice() {
        val binding = HelperBinding<Binder> { it.alive }
        val connections = Connections(binding)
        val oldConnection = connections.bind()
        removeHelperService(connections::unbind)
        binding.reset()
        val attempt = binding.begin()
        connections.connected(oldConnection, Binder())
        assertFalse("A queued old callback must not answer the new bind", attempt.result.isCompleted)
        assertNull(binding.current())
    }
}
