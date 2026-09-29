package app.batstats.viewmodel

import androidx.compose.runtime.Immutable

/** Shizuku's state as the access banners (Apps, Settings › Status) need it: running, and whether it allowed BatStats. */
@Immutable
data class ShizukuState(val running: Boolean = false, val granted: Boolean = false)
