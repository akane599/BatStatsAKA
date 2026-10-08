package com.akane.voltwise.battery.shizuku

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
class HelperBindingTest {
    private class Binder(val name: String, var alive: Boolean = true)

    private val binding = HelperBinding<Binder> { it.alive }

    @Test fun theOldHelpersLateDeathAfterAnIdleUnbindDoesNotFailOrDropTheNextBind() {
        val first = binding.begin()
        val old = Binder("old")
        binding.connected(old)
        assertSame(old, first.result.getCompleted())
        binding.end(first)

        assertTrue("Idle unbind drops the binder", binding.forget())
        val next = binding.begin() // A command starts before the old helper's death notice arrives.
        old.alive = false
        binding.died(old)
        binding.disconnected()
        assertFalse("The late notices must not fail the new bind", next.result.isCompleted)

        val fresh = Binder("fresh")
        binding.connected(fresh)
        assertSame(fresh, next.result.getCompleted())
        binding.died(old)
        binding.disconnected()
        assertSame("Notices from the old helper keep the new binder", fresh, binding.current())
    }

    @Test fun theCurrentBinderIsDroppedWhenItDies() {
        val binder = Binder("helper")
        binding.connected(binder)
        binder.alive = false
        assertNull("A dead binder is never handed out", binding.current())
        binding.disconnected()
        assertFalse(binding.forget())
        binding.connected(binder)
        binding.died(binder)
        assertFalse(binding.forget())
    }

    @Test fun forgetDropsOnlyTheNamedBinderWhileItIsCurrent() {
        val a = Binder("a")
        val b = Binder("b")
        binding.connected(a)
        binding.connected(b)
        assertFalse(binding.forget(a))
        assertSame(b, binding.current())
        assertTrue(binding.forget(b))
        assertNull(binding.current())
    }

    @Test fun resetAndNullBindingFailTheWaitingAttemptButEndOnlyClearsItsOwn() {
        val first = binding.begin()
        binding.reset()
        assertNull(first.result.getCompleted())

        val second = binding.begin()
        binding.failAttempt()
        assertNull(second.result.getCompleted())

        val timedOut = binding.begin()
        binding.end(timedOut)
        val third = binding.begin()
        binding.end(timedOut) // The timed-out attempt's cleanup runs late.
        binding.connected(Binder("c"))
        assertEquals("c", third.result.getCompleted()?.name)
    }
}
