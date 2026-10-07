package app.batstats.battery.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import app.batstats.battery.apps.AppUsageBasis
import app.batstats.battery.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant

@Serializable
data class BatteryExport(
    val samples: List<BatterySample> = emptyList(),
    val sessions: List<ChargeSession> = emptyList(),
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long? = null,
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val units: Map<String, String> = emptyMap(),
    val reportingPeriod: String? = null,
    val appUsage: List<SessionAppUsage> = emptyList(),  // format 3+: per-app rows of the exported sessions
)

/** 3: v5 session columns and `appUsage`. Files of formats 1 and 2 still import; missing fields stay null. */
internal const val HISTORY_FORMAT_VERSION = 3
private const val MAX_APP_USAGE_ROWS = HistoryLimits.MAX_SESSIONS * SessionAppUsage.MAX_ROWS
internal const val CURRENT_NOW_UA_EXPORT_DESCRIPTION =
    "Raw BatteryManager current as reported by the device; unit and sign are device-dependent, and detected calibration is not applied"

data class HistoryImportResult(val samplesAdded: Int, val sessionsAdded: Int, val sessionsUpdated: Int, val unchanged: Int)

internal fun exportUnits(): Map<String, String> = mapOf(
    "timestamps" to "Unix epoch milliseconds UTC", "durations" to "milliseconds", "currentNowUa" to CURRENT_NOW_UA_EXPORT_DESCRIPTION,
    "chargeCounterUah" to "µAh", "deltaUah" to "µAh, positive gained for CHARGE or consumed for DISCHARGE",
    "voltageMv" to "mV", "temperatureDeciC" to "tenths Celsius", "energyNwh" to "nWh", "estCapacityMah" to "mAh, legacy estimate",
    "capacityEstimateMah" to "mAh, full-capacity estimate", "peakPowerMw" to "mW", "powerMah" to "mAh attributed by Android batterystats",
    "mobileBytes" to "bytes received and sent", "wifiBytes" to "bytes received and sent",
)


/** No writes occur until the complete bounded file passes validation. */
@OptIn(ExperimentalSerializationApi::class)
class ExportImportManager(private val context: Context, private val db: BatteryDatabase, private val maintenance: HistoryMaintenance) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val operations = Mutex()
    private val stringFields = setOf("sessionId", "observationId", "source", "boundaryReason", "etaBasis", "closeReason", "type",
        "chargerType", "capacityConfidence", "capacityBasis", "appUsageStatus", "appUsageBasis", "packageName", "basis")
    private val booleanFields = setOf("screenOn", "isOthers")
    private val decimalFields = setOf("powerMah")
    private val metadataColumns = setOf("exportedAtEpochMs", "fromEpochMs", "toEpochMs", "timestampUtc", "startTimeUtc", "endTimeUtc", "reportingPeriod", "units")

    internal suspend fun snapshot(from: Long, to: Long, samples: Boolean, sessions: Boolean): BatteryExport {
        val end = if (to == 0L) System.currentTimeMillis() else to
        require(from >= 0 && end >= from) { "Invalid export date range" }
        require(samples || sessions) { "Select samples or sessions" }
        return db.withTransaction {
            val points = if (samples) db.batteryDao().samplesBetween(from, end).first() else emptyList()
            val periods = if (sessions) db.sessionDao().sessionsBetween(from, end) else emptyList()
            val usage = if (sessions) db.appUsageDao().usageForSessionsBetween(from, end) else emptyList()
            require(points.size <= HistoryLimits.MAX_SAMPLES && periods.size <= HistoryLimits.MAX_SESSIONS && usage.size <= MAX_APP_USAGE_ROWS) { "Use a smaller export date range" }
            BatteryExport(points, periods, HISTORY_FORMAT_VERSION, System.currentTimeMillis(), from, end,
                exportUnits(),
                "Samples are within the requested range. Sessions overlap the range; their totals cover their complete original windows, not a clipped range. Per-app rows belong to the exported sessions. Missing fields are unavailable. Imports never resume monitoring.",
                usage)
        }
    }
    private class BoundedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var size = 0L
        private fun add(n: Int) { size += n; require(size <= HistoryLimits.MAX_BYTES) { "Export exceeds 64 MiB; select a smaller range" } }
        override fun write(b: Int) { add(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { add(len); out.write(b, off, len) }
    }
    suspend fun exportJson(dest: Uri, from: Long, to: Long, includeSamples: Boolean, includeSessions: Boolean) = operations.withLock {
        withContext(Dispatchers.IO) {
            val payload = snapshot(from, to, includeSamples, includeSessions)
            val temp = File.createTempFile("battery-export-", ".json", context.cacheDir)
            try {
                BoundedOutput(temp.outputStream()).use { json.encodeToStream(BatteryExport.serializer(), payload, it) }
                currentCoroutineContext().ensureActive()
                (context.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("Cannot open export destination")).use { output -> temp.inputStream().use { it.copyTo(output) } }
            } finally { temp.delete() }
        }
    }
    suspend fun exportCsvToFolder(tree: Uri, from: Long, to: Long, includeSamples: Boolean = true, includeSessions: Boolean = true) = operations.withLock {
        withContext(Dispatchers.IO) {
            val payload = snapshot(from, to, includeSamples, includeSessions)
            val folder = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Cannot open export folder")
            val stamp = checkNotNull(payload.exportedAtEpochMs) { "An export snapshot always carries its time" }
            val exportContext = currentCoroutineContext()
            val created = mutableListOf<DocumentFile>()
            try {
                fun write(name: String, records: Sequence<JsonObject>, blank: JsonObject) {
                    val file = folder.createFile("text/csv", "$name-$stamp.csv") ?: throw IOException("Cannot create CSV file")
                    created += file
                    val keys = blank.keys.toList()
                    (context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("Cannot open CSV destination")).let(::BoundedOutput).bufferedWriter().use { writer ->
                        HistoryCsv.writeRow(writer, keys + metadataColumns)
                        for (record in records) {
                            exportContext.ensureActive()
                            fun time(name: String) = record[name]?.jsonPrimitive?.longOrNull?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
                            val meta = listOf(stamp.toString(), payload.fromEpochMs.toString(), payload.toEpochMs.toString(), time("timestamp"), time("startTime"), time("endTime"), payload.reportingPeriod.orEmpty(), payload.units.entries.joinToString("; ") { "${it.key}: ${it.value}" })
                            HistoryCsv.writeRow(writer, keys.map { key -> record[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty() } + meta)
                        }
                    }
                }
                if (includeSamples) write("battery_samples", payload.samples.asSequence().map { json.encodeToJsonElement(BatterySample.serializer(), it).jsonObject },
                    json.encodeToJsonElement(BatterySample.serializer(), emptySample()).jsonObject)
                if (includeSessions) write("charge_sessions", payload.sessions.asSequence().map { json.encodeToJsonElement(ChargeSession.serializer(), it).jsonObject },
                    json.encodeToJsonElement(ChargeSession.serializer(), ChargeSession("header", SessionType.UNKNOWN, 0, 0, null, null, null, null, null)).jsonObject)
                if (includeSessions && payload.appUsage.isNotEmpty()) write("session_app_usage", payload.appUsage.asSequence().map { json.encodeToJsonElement(SessionAppUsage.serializer(), it).jsonObject },
                    json.encodeToJsonElement(SessionAppUsage.serializer(), SessionAppUsage("header", 0, 0, "", 0.0, basis = AppUsageBasis.DELTA)).jsonObject)
            } catch (e: Exception) { created.forEach { runCatching { it.delete() } }; throw e }
        }
    }
    suspend fun importJson(src: Uri): HistoryImportResult = operations.withLock {
        check(!maintenance.isClearing) { "History is being cleared; try importing again afterward" }
        maintenance.mutations.withLock {
        check(!maintenance.isClearing) { "History is being cleared; try importing again afterward" }
        withContext(Dispatchers.IO) {
            val payload = (context.contentResolver.openInputStream(src) ?: throw IOException("Cannot open history file")).let { LimitedHistoryInput(it, json = true) }.use {
                json.decodeFromStream(BatteryExport.serializer(), it)
            }
            importPayload(payload)
        }
    } }
    suspend fun importCsv(src: Uri): HistoryImportResult = operations.withLock {
        check(!maintenance.isClearing) { "History is being cleared; try importing again afterward" }
        maintenance.mutations.withLock {
        check(!maintenance.isClearing) { "History is being cleared; try importing again afterward" }
        withContext(Dispatchers.IO) {
            val samples = mutableListOf<BatterySample>(); val sessions = mutableListOf<ChargeSession>(); val usage = mutableListOf<SessionAppUsage>()
            (context.contentResolver.openInputStream(src) ?: throw IOException("Cannot open CSV file")).let(::LimitedHistoryInput).bufferedReader().use { input ->
                val iterator = HistoryCsv.rows(input).iterator()
                require(iterator.hasNext()) { "CSV file is empty" }
                val header = iterator.next().mapIndexed { index, text -> if (index == 0) text.removePrefix("\uFEFF") else text }
                require(header.size == header.toSet().size) { "Duplicate CSV column" }
                val sampleFile = "timestamp" in header && "levelPercent" in header
                val usageFile = !sampleFile && "sessionId" in header && "rank" in header && "packageName" in header
                require(sampleFile || usageFile || "sessionId" in header && "type" in header && "startTime" in header) { "Unrecognized CSV header" }
                while (iterator.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val row = iterator.next()
                    require(row.size == header.size) { "CSV row has the wrong number of columns" }
                    val obj = JsonObject(header.zip(row).filter { it.first !in metadataColumns }.associate { (key, raw) ->
                        key to when {
                            key == "packageName" -> JsonPrimitive(raw)  // never null; the "others" row may have none
                            raw.isBlank() || raw == "null" -> JsonNull
                            key in stringFields -> JsonPrimitive(raw)
                            key in booleanFields -> JsonPrimitive(raw.toBooleanStrict())
                            key in decimalFields -> JsonPrimitive(raw.toDouble())
                            else -> JsonPrimitive(raw.toLong())
                        }
                    })
                    when {
                        sampleFile -> samples += json.decodeFromJsonElement(BatterySample.serializer(), obj)
                        usageFile -> usage += json.decodeFromJsonElement(SessionAppUsage.serializer(), obj)
                        else -> sessions += json.decodeFromJsonElement(ChargeSession.serializer(), obj)
                    }
                    require(samples.size <= HistoryLimits.MAX_SAMPLES && sessions.size <= HistoryLimits.MAX_SESSIONS && usage.size <= MAX_APP_USAGE_ROWS) { "Too many history records" }
                }
            }
            importPayload(BatteryExport(samples, sessions, appUsage = usage))
        }
    } }

    /** Also used by instrumentation tests; caller holds the maintenance lock in the file entry points. */
    internal suspend fun importPayload(payload: BatteryExport): HistoryImportResult {
        require(payload.formatVersion in 1..HISTORY_FORMAT_VERSION) { "Unsupported history format version" }
        require(payload.samples.size <= HistoryLimits.MAX_SAMPLES && payload.sessions.size <= HistoryLimits.MAX_SESSIONS &&
            payload.appUsage.size <= MAX_APP_USAGE_ROWS) { "Too many history records" }
        val samples = payload.samples.map(HistoryPolicy::sample)
        val sessions = payload.sessions.map(HistoryPolicy::session)
        val usage = HistoryPolicy.appUsage(payload.appUsage)
        require(sessions.map { it.sessionId }.toSet().size == sessions.size) { "Duplicate session identities in file" }
        var addedSamples = 0; var addedSessions = 0; var updated = 0; var skipped = 0
        return db.withTransaction {
            val samplesBefore = db.batteryDao().count()
            val sessionsBefore = db.sessionDao().count()
            val dispositions = mutableMapOf<String, ImportSessionDisposition>()
            for ((index, session) in sessions.withIndex()) {
                currentCoroutineContext().ensureActive()
                val original = payload.sessions[index]
                val local = db.sessionDao().byId(HistoryPolicy.originalId(original.sessionId))
                if (local != null && !local.source.startsWith("import:")) {
                    require(HistoryPolicy.sameOrigin(local, original)) { "Conflicting local session identity" }
                    require((local.lastSampleTime ?: local.endTime ?: local.startTime) >= (original.lastSampleTime ?: original.endTime ?: original.startTime)) { "Import conflicts with a local observation" }
                    if ((local.lastSampleTime ?: local.endTime ?: local.startTime) == (original.lastSampleTime ?: original.endTime ?: original.startTime)) {
                        require(HistoryPolicy.sameMeasurement(HistoryPolicy.session(local), session)) { "Conflicting values for a local session window" }
                    }
                    skipped++; continue
                }
                val plan = HistoryPolicy.planSessionImport(db.sessionDao().byId(session.sessionId), session)
                when (plan.disposition) {
                    ImportSessionDisposition.ADDED -> db.sessionDao().insert(plan.session)
                    ImportSessionDisposition.UPDATED -> db.sessionDao().update(plan.session)
                    ImportSessionDisposition.UNCHANGED, ImportSessionDisposition.STALE -> Unit
                }
                dispositions[session.sessionId] = plan.disposition
                addedSessions += plan.disposition.added
                updated += plan.disposition.updated
                skipped += plan.disposition.unchanged
            }
            for ((index, sample) in samples.withIndex()) {
                currentCoroutineContext().ensureActive()
                val original = payload.samples[index]
                val previous = db.batteryDao().byId(sample.id)
                if (previous != null) {
                    require(HistoryPolicy.sameSample(previous, sample)) { "Imported sample identity collision" }; skipped++; continue
                }
                val localRows = db.batteryDao().atTimestamp(original.timestamp)
                if (localRows.any { HistoryPolicy.sameSample(it, original) }) { skipped++; continue }
                val nativePoint = if (original.observationId != null && original.elapsedMs != null)
                    db.batteryDao().observedPoint(HistoryPolicy.originalId(original.observationId), original.elapsedMs) else null
                require(nativePoint == null) { "Import conflicts with a local observed point" }
                val nativeSession = original.sessionId?.let { db.sessionDao().byId(HistoryPolicy.originalId(it)) }
                val stored = if (nativeSession != null && !nativeSession.source.startsWith("import:")) {
                    require(nativeSession.observationId?.let(HistoryPolicy::originalId) == original.observationId?.let(HistoryPolicy::originalId) &&
                        sample.timestamp in nativeSession.startTime..(nativeSession.lastSampleTime ?: nativeSession.endTime ?: nativeSession.startTime)) {
                        "Imported sample is outside the local session window"
                    }
                    sample.copy(sessionId = nativeSession.sessionId)
                } else sample
                val importedSession = stored.sessionId?.let { db.sessionDao().byId(it) }
                require(importedSession == null || stored.timestamp in importedSession.startTime..(importedSession.endTime ?: importedSession.lastSampleTime ?: importedSession.startTime)) {
                    "Sample is outside its session window"
                }
                val point = if (sample.observationId != null && sample.elapsedMs != null) db.batteryDao().observedPoint(sample.observationId, sample.elapsedMs) else null
                require(point == null) { "Conflicting readings for one observed point" }
                require(db.batteryDao().insertSample(stored) != -1L) { "Sample insert conflicted with existing history" }
                addedSamples++
            }
            // A breakdown follows its parent's merge: stale imports and local sessions keep their own.
            for ((sessionId, rows) in usage.groupBy { it.sessionId }) {
                currentCoroutineContext().ensureActive()
                val local = db.sessionDao().byId(HistoryPolicy.originalId(sessionId))
                val plan = HistoryPolicy.planUsageImport(
                    dispositions[sessionId], local?.source, db.sessionDao().byId(sessionId) != null,
                    db.appUsageDao().sessionUsageRows(sessionId), rows,
                ) ?: continue
                db.appUsageDao().replaceSessionUsageRows(sessionId, plan.rows)
                updated += plan.updated
                skipped += plan.unchanged
            }
            // Refuse rather than silently deleting existing history to make room for an import.
            require(HistoryLimits.importWithinLimit(samplesBefore, db.batteryDao().count(), HistoryLimits.MAX_SAMPLES) &&
                HistoryLimits.importWithinLimit(sessionsBefore, db.sessionDao().count(), HistoryLimits.MAX_SESSIONS)) { "History limit exceeded; clear or export older records first" }
            HistoryImportResult(addedSamples, addedSessions, updated, skipped)
        }
    }
    private fun emptySample() = BatterySample(timestamp = 0, levelPercent = null, status = 1, plugged = null,
        currentNowUa = null, chargeCounterUah = null, voltageMv = null, temperatureDeciC = null, health = null, screenOn = false)
}
