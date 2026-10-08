package com.akane.voltwise.battery.data.sampling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotFailureContinuityTest {
    @Test fun oneShotFailureDoesNotMarkContinuityLoss() {
        assertFalse(shouldMarkContinuityLoss(observe = false))
    }

    @Test fun observedFailureMarksContinuityLoss() {
        assertTrue(shouldMarkContinuityLoss(observe = true))
    }
}
