package app.batstats.viewmodel

import androidx.compose.runtime.Immutable

/** Shizuku access, including a denial that prevents another permission dialog. */
@Immutable
data class ShizukuState(val running: Boolean = false, val granted: Boolean = false, val blocked: Boolean = false)
