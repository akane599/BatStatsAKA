package app.batstats.battery.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUserServiceTest {
    @Test
    fun destroyTransactionMatchesShizuku1315Constant() {
        assertEquals(16777115, ShellUserService.TRANSACTION_DESTROY)
    }
}
