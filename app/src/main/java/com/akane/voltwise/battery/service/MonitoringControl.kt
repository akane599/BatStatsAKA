package com.akane.voltwise.battery.service

import kotlinx.coroutines.flow.StateFlow

/** Starts and stops the monitoring foreground service; the single entry point for UI, tile and boot. */
interface MonitoringControl {
    val isMonitoring: StateFlow<Boolean>
    /** Whether an app update may resume monitoring; retained across service/process shutdown. */
    val monitoringWanted: Boolean
    fun start(): StartResult
    fun stop()

    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        /** Android refused a foreground-service start from the background; open the app instead. */
        BLOCKED,
    }
}
