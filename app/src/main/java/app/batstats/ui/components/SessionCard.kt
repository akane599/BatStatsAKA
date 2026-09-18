package app.batstats.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.batstats.R
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.drain.formatDrainRate
import app.batstats.battery.drain.formatDuration
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@Composable
fun sessionTypeLabel(type: SessionType): String = stringResource(when (type) {
    SessionType.CHARGE -> R.string.session_charging
    SessionType.DISCHARGE -> R.string.session_discharging
    SessionType.PLUGGED -> R.string.session_plugged
    SessionType.UNKNOWN -> R.string.session_unknown
})

@Composable
fun SessionCard(session: ChargeSession, modifier: Modifier = Modifier, isRecording: Boolean = false) {
    val hasCoverage = SessionEvidence.hasCoverage(session)
    Card(modifier, colors = CardDefaults.cardColors(containerColor =
        if (isRecording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sessionTypeLabel(session.type), style = MaterialTheme.typography.titleMedium)
            if (isRecording) Text(stringResource(R.string.session_recording), style = MaterialTheme.typography.labelLarge)
            else if (session.endTime == null) Text(stringResource(R.string.session_interrupted), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(session.startTime)),
                style = MaterialTheme.typography.bodyMedium)
            Text("${session.startLevel?.let { "$it%" } ?: "—"} → ${session.endLevel?.let { "$it%" } ?: "—"}",
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (session.source.startsWith("import:")) R.string.session_imported_source else R.string.session_source, session.source),
                style = MaterialTheme.typography.bodySmall)
            if (!hasCoverage) Text(stringResource(R.string.session_legacy_summary), style = MaterialTheme.typography.bodySmall)
            else {
                Text(stringResource(R.string.session_observed, formatDuration(session.observedMs)))
                if (session.counterCoveredMs >= 60_000) Text(stringResource(R.string.session_average,
                    formatDrainRate(session.avgCurrentUa?.let { abs(it / 1000.0) })), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
