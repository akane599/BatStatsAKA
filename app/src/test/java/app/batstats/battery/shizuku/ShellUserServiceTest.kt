package app.batstats.battery.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test
import rikka.shizuku.ShizukuApiConstants

class ShellUserServiceTest {
    @Test
    fun destroyTransactionMatchesShizukuConstant() {
        assertEquals(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy, ShellUserService.TRANSACTION_DESTROY)
    }
}
