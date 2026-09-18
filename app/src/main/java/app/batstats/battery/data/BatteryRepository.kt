package app.batstats.battery.data

import app.batstats.battery.diagnostics.DiagnosticCode
import app.batstats.battery.diagnostics.DiagnosticStore
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import app.batstats.battery.data.db.*
import app.batstats.battery.measurement.*
import app.batstats.settings.AppSettings
import app.batstats.settings.chartTimeRangeMs
import app.batstats.settings.monitoringIntervalMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

/** One bounded event stream owns observations and writes; UI and notifications share its result. */
class BatteryRepository(
    private val context: Context,
    private val db: BatteryDatabase,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val scope: CoroutineScope,
    private val maintenance: HistoryMaintenance,
    private val diagnostics: DiagnosticStore
) {
    private val batteryDao = db.batteryDao()
    val sessionDao = db.sessionDao()
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val engine = ObservationEngine()
    private val sessionEngine = ObservationEngine()
    private val estimator = RemainingTimeEstimator()
    private val events = Channel<Event>(64)
    @Volatile private var activeGeneration: String? = null
    val isClearingHistory: Boolean get() = maintenance.isClearing
    @Volatile private var overflow = false
    private var samplingJob: Job? = null
    private var receiverRegistered = false
    private var samplingIntervalMs = 30_000L
    private var session: ChargeSession? = null
    private var needsSessionRecovery = false
    private var lastPersisted: BatterySample? = null
    private var sampleCount = 0L
    private var lastCleanupElapsed = Long.MIN_VALUE
    private var samplesSinceCleanup = 0
    private var lastCountFlushAttempt = Long.MIN_VALUE

    private val _realtime = MutableStateFlow(Realtime())
    val realtimeFlow = _realtime.asStateFlow()
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow = _isMonitoring.asStateFlow()
    private val _observation = MutableStateFlow(ObservationSummary())
    val observation = _observation.asStateFlow()
    private enum class FailureSource { BATTERY, STATE_EVENTS, HISTORY, RETENTION, SAMPLE_COUNT }
    private val failures = MutableStateFlow<Map<FailureSource, String>>(emptyMap())
    val error = failures.map { values ->
        values.toSortedMap().values.joinToString("\n").ifEmpty { null }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private fun failure(source: FailureSource, message: String? = null) {
        failures.update { if (message == null) it - source else it + (source to message) }
    }
    val activeSessionFlow: Flow<ChargeSession?> = sessionDao.activeFlow()
    val settingsFlow = settingsRepository.flow
    val monitoringInterval = settingsRepository.flow.map { it.monitoringIntervalMs }

    private sealed interface Event {
        data class Start(val generation: String) : Event
        data class Sample(val sample: BatterySample, val point: Observation) : Event
        data object Stop : Event
        data object Reset : Event
        data class Clear(val result: CompletableDeferred<Unit>) : Event
    }

    init {
        scope.launch(Dispatchers.IO) {
            for (event in events) {
                try {
                    when (event) {
                        is Event.Start -> if (activeGeneration == event.generation) {
                            session = null
                            lastPersisted = null
                            engine.reset(); sessionEngine.reset(); estimator.reset()
                            _observation.value = engine.summary
                            needsSessionRecovery = true
                            recoverInterruptedSession()
                        }
                        is Event.Sample -> if (activeGeneration == event.point.generation) process(event)
                        Event.Stop -> {
                            finishSession("Monitoring stopped")
                            engine.stop(); estimator.reset()
                            _observation.value = engine.summary
                            flushSampleCount(force = true)
                        }
                        Event.Reset -> {
                            finishSession("Observation reset by user")
                            engine.reset(); sessionEngine.reset(); estimator.reset()
                            _observation.value = engine.summary
                        }
                        is Event.Clear -> {
                            try {
                                db.withTransaction {
                                    batteryDao.clearAll(); sessionDao.clearAll(); db.appEnergyDao().clearAll()
                                }
                                session = null; lastPersisted = null; sampleCount = 0
                                needsSessionRecovery = false
                                failure(FailureSource.HISTORY); failure(FailureSource.RETENTION)
                                engine.reset(); sessionEngine.reset(); estimator.reset()
                                _observation.value = engine.summary
                                try {
                                    settingsRepository.update { it.copy(totalSamplesCollected = 0) }
                                    failure(FailureSource.SAMPLE_COUNT)
                                }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { failure(FailureSource.SAMPLE_COUNT, "History cleared; the sample-count setting could not be reset") }
                                event.result.complete(Unit)
                            } catch (e: Exception) { event.result.completeExceptionally(e); throw e }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.record(DiagnosticCode.HISTORY_WRITE_FAILED)
                    failure(FailureSource.HISTORY, "History collection failed (${e.javaClass.simpleName}); live battery readings remain available")
                    overflow = true
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val boundary = when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> Boundary.SCREEN
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> Boundary.DOZE
                else -> Boundary.POWER
            }
            capture(boundary, when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> null
            })
        }
    }

    fun startSampling() {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeGeneration != null || isClearingHistory) return@launch
            val generation = UUID.randomUUID().toString()
            activeGeneration = generation
            _isMonitoring.value = true
            diagnostics.record(DiagnosticCode.MONITORING_STARTED)
            events.send(Event.Start(generation))
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            try {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                failure(FailureSource.STATE_EVENTS)
            } catch (e: RuntimeException) {
                diagnostics.record(DiagnosticCode.STATE_EVENTS_UNAVAILABLE)
                failure(FailureSource.STATE_EVENTS, "State events unavailable; observation is paused, ordinary battery readings continue")
            }
            samplingJob = scope.launch(Dispatchers.Main.immediate) {
                settingsRepository.flow.map { it.monitoringIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                    samplingIntervalMs = interval
                    while (isActive && activeGeneration == generation) {
                        capture(Boundary.SAMPLE)
                        delay(interval)
                    }
                }
            }
        }
    }

    fun stopSampling() {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeGeneration != null) diagnostics.record(DiagnosticCode.MONITORING_STOPPED)
            activeGeneration = null // Queued samples become invalid immediately.
            _isMonitoring.value = false
            samplingJob?.cancel(); samplingJob = null
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
            failure(FailureSource.STATE_EVENTS)
            events.send(Event.Stop)
        }
    }

    /** Activity resume/manual refresh gives ordinary information without starting background work. */
    fun refreshNow(onComplete: suspend (Realtime) -> Unit = {}) {
        scope.launch(Dispatchers.Main.immediate) {
            try { capture(Boundary.SAMPLE) }
            finally { onComplete(_realtime.value) }
        }
    }

    fun resetObservation() { scope.launch(Dispatchers.Main.immediate) {
        events.send(Event.Reset)
        capture(Boundary.SAMPLE)
    } }

    suspend fun startSession(type: SessionType) {
        require(activeGeneration != null) { "Start monitoring before creating an observation" }
        val actual = realtimeFlow.value.powerState.toSessionType()
        require(type == actual) { "Session type must match the observed charging state" }
        withContext(Dispatchers.Main.immediate) { events.send(Event.Reset); capture(Boundary.SAMPLE) }
    }

    suspend fun endCurrentSession() = withContext(Dispatchers.Main.immediate) {
        events.send(Event.Reset); capture(Boundary.SAMPLE)
    }

    /** Serialized with imports and the sample writer; no queued sample can survive deletion. */
    suspend fun clearHistory(stopService: () -> Unit) = maintenance.clear(
        stopMonitoring = {
            withContext(Dispatchers.Main.immediate) {
                stopService()
                activeGeneration = null; _isMonitoring.value = false
                samplingJob?.cancel(); samplingJob = null
                if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
                receiverRegistered = false
                failure(FailureSource.STATE_EVENTS)
            }
        },
        delete = {
            val result = CompletableDeferred<Unit>()
            events.send(Event.Clear(result))
            result.await()
        }
    )

    private fun capture(boundary: Boundary, screenOverride: Boolean? = null) {
        try { captureBattery(boundary, screenOverride) }
        catch (e: CancellationException) { throw e }
        catch (e: RuntimeException) {
            diagnostics.record(DiagnosticCode.BATTERY_READ_FAILED)
            failure(FailureSource.BATTERY, "Battery collection failed (${e.javaClass.simpleName})")
            overflow = true
        }
    }

    private fun captureBattery(boundary: Boundary, screenOverride: Boolean?) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            diagnostics.record(DiagnosticCode.BATTERY_UNAVAILABLE)
            failure(FailureSource.BATTERY, "Android has not supplied a battery reading")
            overflow = true
            return
        }
        fun extra(name: String): Int? = if (intent.hasExtra(name)) intent.getIntExtra(name, Int.MIN_VALUE) else null
        val failedProperties = mutableListOf<String>()
        fun property(id: Int, name: String): Long = try { batteryManager.getLongProperty(id) }
        catch (e: CancellationException) { throw e }
        catch (_: RuntimeException) { failedProperties += name; Long.MIN_VALUE }
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val status = extra(BatteryManager.EXTRA_STATUS) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = extra(BatteryManager.EXTRA_PLUGGED)?.takeIf { it >= 0 }
        val power = BatteryReading.powerState(status, plugged)
        val chargeTime = if (Build.VERSION.SDK_INT >= 28 && power == PowerState.CHARGING) {
            try { batteryManager.computeChargeTimeRemaining().takeIf { it in 1..7 * 86_400_000L } }
            catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) { failedProperties += "charge_time"; null }
        } else null
        val sample = BatterySample(
            timestamp = wall,
            levelPercent = BatteryReading.percentage(extra(BatteryManager.EXTRA_LEVEL) ?: -1, extra(BatteryManager.EXTRA_SCALE) ?: -1),
            status = status, plugged = plugged,
            currentNowUa = BatteryReading.currentUa(property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "current_now")),
            chargeCounterUah = BatteryReading.chargeUah(property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, "charge_counter")),
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE)?.let(BatteryReading::voltageMv),
            temperatureDeciC = extra(BatteryManager.EXTRA_TEMPERATURE)?.let(BatteryReading::temperatureDeciC),
            health = extra(BatteryManager.EXTRA_HEALTH),
            screenOn = screenOverride ?: powerManager.isInteractive,
            elapsedMs = elapsed, uptimeMs = SystemClock.uptimeMillis(), observationId = activeGeneration,
            currentAverageUa = BatteryReading.currentUa(property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, "current_average")),
            energyNwh = BatteryReading.energyNwh(property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER, "energy_counter")),
            cycleCount = if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 } else null,
            etaMs = chargeTime,
            etaBasis = if (chargeTime != null) "Android charging estimate" else null,
            source = "BatteryManager"
        )
        failure(FailureSource.BATTERY, failedProperties.takeIf { it.isNotEmpty() }?.let {
            diagnostics.record(DiagnosticCode.BATTERY_READ_FAILED)
            "Battery property reads failed: ${it.joinToString()}"
        })
        _realtime.value = Realtime(sample)
        val generation = activeGeneration ?: return
        // Polling alone cannot establish screen/Doze continuity or prove that no transition happened.
        if (!receiverRegistered) return
        val point = Observation(wall, elapsed, sample.uptimeMs!!, sample.levelPercent, sample.chargeCounterUah,
            sample.currentNowUa, sample.voltageMv, power, sample.screenOn, powerManager.isDeviceIdleMode,
            generation, samplingIntervalMs, if (overflow) Boundary.GAP else boundary)
        overflow = !events.trySend(Event.Sample(sample, point)).isSuccess
    }

    private suspend fun process(event: Event.Sample) {
        recoverInterruptedSession()
        val old = engine.summary
        val summary = engine.accept(event.point)
        val changed = old.latest?.power != event.point.power
        val gap = old.gaps != summary.gaps
        if (gap) diagnostics.record(DiagnosticCode.OBSERVATION_GAP)
        if (old.counterGaps != summary.counterGaps) diagnostics.record(DiagnosticCode.CHARGE_UNAVAILABLE)
        if (session != null && (changed || gap)) {
            if (changed && !gap) session = reportSession(session!!, event.sample, sessionEngine.accept(event.point))
            finishSession(if (gap) summary.lastIssue ?: "Observation gap" else "Power state changed")
        }
        if (session == null) {
            sessionEngine.reset()
            session = ChargeSession(UUID.randomUUID().toString(), event.point.power.toSessionType(),
                event.sample.timestamp, null, event.sample.levelPercent, null, null, null, null,
                observationId = event.point.generation, source = "BatteryManager observed interval")
        }
        val sessionSummary = sessionEngine.accept(event.point)
        if (gap || changed) estimator.reset()
        val eta = estimator.accept(event.point)
        val sample = event.sample.copy(sessionId = session!!.sessionId,
            etaMs = event.sample.etaMs ?: eta?.remainingMs,
            etaBasis = event.sample.etaBasis ?: eta?.let { "Observed discharge over ${it.observedMs / 60_000} min" },
            boundaryReason = if (gap) summary.lastIssue else null)
        val updated = reportSession(session!!, sample, sessionSummary)
        val inserted = db.withTransaction { sessionDao.upsert(updated); batteryDao.insertSample(sample) }
        session = updated; lastPersisted = sample
        _realtime.update { Realtime(mergePersistedReading(it.sample, sample)) }
        _observation.value = summary
        failure(FailureSource.HISTORY)
        if (inserted != -1L) { sampleCount++; samplesSinceCleanup++ }
        if (sampleCount >= 100) flushSampleCount()
        if (lastCleanupElapsed == Long.MIN_VALUE || samplesSinceCleanup >= 200 || event.point.elapsedMs - lastCleanupElapsed >= 86_400_000) {
            lastCleanupElapsed = event.point.elapsedMs
            samplesSinceCleanup = 0
            try { cleanup(sample.timestamp); failure(FailureSource.RETENTION) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                diagnostics.record(DiagnosticCode.HISTORY_WRITE_FAILED)
                failure(FailureSource.RETENTION, "History retention failed (${e.javaClass.simpleName}); retrying on the next maintenance interval")
            }
        }
    }

    private fun reportSession(current: ChargeSession, sample: BatterySample, summary: ObservationSummary): ChargeSession {
        val bucket = if (current.type == SessionType.CHARGE) summary.charging else summary.discharge
        return current.copy(lastSampleTime = sample.timestamp, endLevel = sample.levelPercent,
            observedMs = summary.observedMs, counterCoveredMs = bucket.chargeCoveredMs,
            deltaUah = bucket.chargeChangeUah.takeIf { bucket.chargeCoveredMs > 0 },
            avgCurrentUa = bucket.rateMa?.times(if (current.type == SessionType.CHARGE) 1000 else -1000)?.toLong(),
            screenOnMs = summary.screenOn.durationMs, screenOffMs = summary.screenOff.durationMs,
            screenOnUah = summary.screenOn.chargeChangeUah.takeIf { summary.screenOn.chargeCoveredMs > 0 },
            screenOffUah = summary.screenOff.chargeChangeUah.takeIf { summary.screenOff.chargeCoveredMs > 0 },
            cpuSuspendMs = summary.cpuSuspendMs)
    }

    private suspend fun finishSession(reason: String) {
        session?.let { current ->
            sessionDao.upsert(current.copy(endTime = current.lastSampleTime ?: current.startTime,
                activeKey = null, closeReason = reason))
        }
        session = null; sessionEngine.reset()
    }

    private suspend fun recoverInterruptedSession() {
        if (!needsSessionRecovery) return
        sessionDao.closeInterrupted("Process stopped; interval ended at last stored reading")
        needsSessionRecovery = false
    }

    private suspend fun flushSampleCount(force: Boolean = false) {
        val count = sampleCount
        if (count == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCountFlushAttempt != Long.MIN_VALUE && now - lastCountFlushAttempt < 60_000) return
        lastCountFlushAttempt = now
        try {
            settingsRepository.update {
                it.copy(totalSamplesCollected = it.totalSamplesCollected.coerceIn(0L, Long.MAX_VALUE - count) + count)
            }
            sampleCount = 0
            failure(FailureSource.SAMPLE_COUNT)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            diagnostics.record(DiagnosticCode.HISTORY_WRITE_FAILED)
            failure(FailureSource.SAMPLE_COUNT, "Sample-count preference could not be saved (${e.javaClass.simpleName}); battery history is retained")
        }
    }

    private suspend fun cleanup(now: Long) {
        val settings = settingsRepository.flow.first()
        if (settings.autoCleanupEnabled && settings.dataRetentionIndex != 5) {
            val days = listOf(7L, 30L, 90L, 180L, 365L).getOrElse(settings.dataRetentionIndex) { 90 }
            val cutoff = now - days * 86_400_000
            db.withTransaction { batteryDao.purge(cutoff); sessionDao.purge(cutoff); db.appEnergyDao().purgeOlderThan(cutoff) }
        }
        sessionDao.boundStorage()
        batteryDao.boundStorage() // Trim to 100,000; at most 200 new samples accumulate between trims.
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentSamplesFlow(durationMs: Long): Flow<List<BatterySample>> = flow {
        while (currentCoroutineContext().isActive) { emit(System.currentTimeMillis()); delay(60_000) }
    }.flatMapLatest { now ->
        batteryDao.chartSamples(now - durationMs, Long.MAX_VALUE, (durationMs / 360).coerceAtLeast(1))
    }
    fun samplesBetween(start: Long, end: Long) = batteryDao.samplesBetween(start, end)
    fun samplesForSession(sessionId: String) = batteryDao.samplesForSession(sessionId)
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSamplesFromSettings = settingsRepository.flow.flatMapLatest { recentSamplesFlow(it.chartTimeRangeMs) }
    suspend fun getSettings(): AppSettings = settingsRepository.flow.first()

    data class Realtime(val sample: BatterySample? = null) {
        val level: Int? get() = sample?.levelPercent
        val plugged: Int? get() = sample?.plugged
        val currentMa: Double? get() = sample?.currentNowUa?.div(1000.0)
        val voltageMv: Int? get() = sample?.voltageMv
        val powerMw: Double? get() = BatteryReading.powerMw(sample?.currentNowUa, sample?.voltageMv)
        val temperatureC: Float? get() = sample?.temperatureDeciC?.div(10f)
        val powerState: PowerState get() = BatteryReading.powerState(sample?.status ?: 1, sample?.plugged)
    }
}

private fun PowerState.toSessionType(): SessionType = when (this) {
    PowerState.CHARGING -> SessionType.CHARGE
    PowerState.DISCHARGING -> SessionType.DISCHARGE
    PowerState.PLUGGED -> SessionType.PLUGGED
    PowerState.UNKNOWN -> SessionType.UNKNOWN
}
