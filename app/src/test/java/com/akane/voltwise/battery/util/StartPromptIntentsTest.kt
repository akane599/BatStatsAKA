package com.akane.voltwise.battery.util

import org.junit.Assert.assertSame
import org.junit.Test

class StartPromptIntentsTest {
    @Test fun `body tap uses the same start intent as the action`() {
        val start = Any()
        val intents = StartPromptIntents(start)

        assertSame("Body tap must start monitoring like the action", intents.action, intents.content)
    }
}
