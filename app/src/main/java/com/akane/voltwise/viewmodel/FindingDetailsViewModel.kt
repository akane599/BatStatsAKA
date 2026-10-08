package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class FindingDetailsViewModel(
    source: InsightsRepository,
    applicationScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val flow = InsightApplyFlow(source, applicationScope, savedStateHandle)
    private val key = savedStateHandle.getStateFlow("key", "")
    val effects = flow.effects
    private val content = combine(source.report, source.actions, source.privileged, key) { report, actions, privileged, key ->
        val finding = report?.findings?.firstOrNull { it.key == key }
        val packageName = (finding?.subject as? com.akane.voltwise.battery.insights.model.Subject.App)?.packageName
        val related = actions.filter { row ->
            if (packageName != null) row.packageName == packageName else row.findingKey == key
        }
        FindingDetailsUiState(
            finding = finding?.toInsightState(privileged, actions),
            relatedActions = actionStates(related, report?.findings.orEmpty().map { it.toInsightState(privileged, actions) }),
            privileged = privileged,
        )
    }
    val state: StateFlow<FindingDetailsUiState> = combine(content, flow.state) { content, apply ->
        content.copy(apply = apply)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FindingDetailsUiState(apply = flow.state.value))

    fun onEvent(event: InsightsEvent) = flow.onEvent(event)
}
