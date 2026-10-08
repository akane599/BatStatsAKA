package com.akane.voltwise.battery.util

import org.junit.Assert.*
import org.junit.Test

class DumpOutputTest {
    @Test fun theAndroid16CrossUserRefusalOfTheAdbModeDumpIsARefusal() {
        // Exact output of `dumpsys batterystats -c --charged` as the app uid with ADB-granted DUMP (API 36 emulator).
        val raw = "Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission: UID 10220 requires " +
            "android.permission.INTERACT_ACROSS_USERS_FULL or android.permission.INTERACT_ACROSS_USERS to access user 0.\n"
        assertEquals(DumpOutput.REFUSED_CROSS_USER, DumpOutput.failure(raw))
        assertTrue(DumpOutput.isRefusal(DumpOutput.failure(raw)))
    }

    @Test fun otherSecurityAndPermissionFailuresAreRefusalsToo() {
        listOf(
            "Security exception: Neither user 10220 nor current process has android.permission.DUMP.",
            "Permission Denial: can't dump BatteryStats from pid=1, uid=10220",
            "java.lang.SecurityException: nope",
        ).forEach { raw ->
            assertEquals(raw, DumpOutput.REFUSED, DumpOutput.failure(raw))
            assertTrue(raw, DumpOutput.isRefusal(DumpOutput.failure(raw)))
        }
    }

    @Test fun otherFailuresAndCleanDumpsAreNotRefusals() {
        assertFalse(DumpOutput.isRefusal(DumpOutput.failure("Can't find service: batterystats")))
        assertFalse(DumpOutput.isRefusal(null))
        assertNull(DumpOutput.failure("9,0,l,bt,2,60000\n9,10001,l,wua,Security exception,2"))
    }
}
