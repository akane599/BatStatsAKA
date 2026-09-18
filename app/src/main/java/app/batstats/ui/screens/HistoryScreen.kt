package app.batstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.R
import app.batstats.battery.data.SessionEvidence
import app.batstats.battery.data.db.SessionType
import app.batstats.ui.components.SessionCard
import app.batstats.ui.components.sessionTypeLabel
import app.batstats.viewmodel.HistoryViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit, vm: HistoryViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val recording by vm.recordingObservation.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.history)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "filters") {
                Text(stringResource(R.string.history_scope), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter.type == null, onClick = { vm.filterBy(null) }, label = { Text(stringResource(R.string.history_all)) })
                    SessionType.entries.forEach { type ->
                        FilterChip(selected = filter.type == type, onClick = { vm.filterBy(type) }, label = { Text(sessionTypeLabel(type)) })
                    }
                }
                OutlinedTextField(value = filter.text, onValueChange = vm::search, singleLine = true,
                    label = { Text(stringResource(R.string.history_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
            }
            when {
                ui.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.history_loading)) }
                ui.failed -> item {
                    Text(stringResource(R.string.history_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::retry) { Text(stringResource(R.string.retry)) }
                }
                ui.sessions.isEmpty() -> item {
                    Text(stringResource(if (filter.type != null || filter.text.isNotBlank()) R.string.history_no_matches else R.string.history_empty),
                        style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.history_empty_help))
                }
                else -> {
                    items(ui.sessions, key = { it.sessionId }) { session ->
                        SessionCard(session, isRecording = SessionEvidence.isRecording(session, recording),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenSession(session.sessionId) })
                    }
                    item {
                        if (ui.hasMore) OutlinedButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.history_load_more))
                        } else Text(stringResource(R.string.history_end, ui.sessions.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
