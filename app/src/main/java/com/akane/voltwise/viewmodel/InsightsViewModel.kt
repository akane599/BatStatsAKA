package com.akane.voltwise.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akane.voltwise.battery.insights.actions.*
import com.akane.voltwise.battery.insights.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class InsightsViewModel(
    private val source: InsightsRepository,
    applicationScope: CoroutineScope,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    applyResults: InsightApplyResults,
) : ViewModel() {
    private val flow = InsightApplyFlow(source, applicationScope, savedStateHandle, viewModelScope, applyResults)
    private val analyzing = MutableStateFlow(false)
    private val error = MutableStateFlow<InsightMessageCode?>(null)
    val effects = flow.effects

    private val content = combine(source.report, source.actions, source.privileged) { report, actions, access ->
        val privileged = access == true
        val findings = report?.findings.orEmpty().map { it.toInsightState(privileged, actions) }
        InsightsUiState(
            loaded = report != null,
            headline = report?.headline?.toInsightState(privileged, actions),
            keyFindings = findings.filter { it.type != FindingType.TREND && it.severity != Severity.INFO }.insightSnapshot(),
            changes = findings.filter { it.type == FindingType.TREND && it.direction != null }.insightSnapshot(),
            appliedActions = actionStates(actions, findings),
            privileged = privileged,
            empty = findings.isEmpty(),
        )
    }
    private val status = combine(source.lastAnalyzedAt, source.eligibleSessionCount, analyzing, error) { at, count, busy, failure ->
        InsightsUiState(lastAnalyzedAt = at, eligibleSessionCount = count, lowData = count < 4, analyzing = busy, error = failure)
    }
    val state: StateFlow<InsightsUiState> = combine(content, status, flow.state) { content, status, apply ->
        content.copy(
            lastAnalyzedAt = status.lastAnalyzedAt, eligibleSessionCount = status.eligibleSessionCount,
            lowData = status.lowData, analyzing = status.analyzing, error = status.error, apply = apply,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState(apply = flow.state.value))

    fun onEvent(event: InsightsEvent) {
        when (event) {
            InsightsEvent.AnalyzeNow -> if (!analyzing.value) {
                analyzing.value = true
                launchRead(InsightMessageCode.ANALYSIS_FAILED) {
                    try { source.analyzeNow() } finally { analyzing.value = false }
                }
            }
            is InsightsEvent.Dismiss -> launchRead(InsightMessageCode.FEEDBACK_FAILED) { source.dismiss(event.key) }
            is InsightsEvent.NotAProblem -> launchRead(InsightMessageCode.FEEDBACK_FAILED) { source.notAProblem(event.key) }
            else -> flow.onEvent(event)
        }
    }

    private fun launchRead(code: InsightMessageCode, block: suspend () -> Unit) {
        error.value = null
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                error.value = code
                flow.message(InsightActionMessage(code))
            }
        }
    }
}

/** Application-owned latest unconsumed outcome, shared by both Insights destinations. */
class InsightApplyResults {
    private val mutableResult = MutableStateFlow<InsightActionMessage?>(null)
    val latest = mutableResult.asStateFlow()

    internal fun publish(result: InsightActionMessage) {
        mutableResult.value = result
    }

    internal fun consume(result: InsightActionMessage?) {
        mutableResult.compareAndSet(result, null)
    }
}

/** Saved dialog primitives only; restoring a dialog never executes anything. Action work outlives the screen. */
internal class InsightApplyFlow(
    private val source: InsightsRepository,
    private val applicationScope: CoroutineScope,
    private val saved: SavedStateHandle,
    observationScope: CoroutineScope,
    private val results: InsightApplyResults,
) {
    private val events = Channel<InsightUiEffect>(Channel.UNLIMITED)
    private var ownLast: InsightActionMessage? = null
    val effects: Flow<InsightUiEffect> = events.receiveAsFlow()
    private val mutableState = MutableStateFlow(InsightApplyState(
        pending = saved.get<String>(PENDING_ACTION)?.let { name ->
            val action = ActionType.entries.firstOrNull { it.name == name }
            saved.get<String>(PENDING_KEY)?.let { key -> action?.let { PendingInsightApply(key, it) } }
        },
        selectedKey = saved[SELECTED_KEY],
        lastResult = results.latest.value,
    ))
    val state = mutableState.asStateFlow()

    init {
        observationScope.launch {
            results.latest.collect { result -> mutableState.update { it.copy(lastResult = result) } }
        }
        // Keep saved dialogs current even while the screen has no state collector.
        observationScope.launch {
            combine(source.report, source.actions, source.privileged) { report, actions, privileged ->
                Triple(report, actions, privileged)
            }.collect { (report, actions, privileged) ->
                val request = state.value.pending
                if (report != null && privileged != null && request != null) {
                    val available = report.findings.firstOrNull { it.key == request.key }
                        ?.toInsightState(privileged, actions)?.recommendations
                        ?.any { it.action == request.action && it.available } == true
                    if (!available) pending(null)
                }
            }
        }
    }

    fun onEvent(event: InsightsEvent) {
        when (event) {
            is InsightsEvent.RequestApply -> if (!state.value.working) {
                selected(event.key)
                pending(PendingInsightApply(event.key, event.action))
            }
            InsightsEvent.CancelApply -> pending(null)
            InsightsEvent.ResultShown -> results.consume(state.value.lastResult)
            InsightsEvent.ConfirmApply -> confirm()
            is InsightsEvent.Undo -> runAction { source.undo(event.actionId) }
            is InsightsEvent.OpenFinding -> {
                selected(event.key)
                events.trySend(InsightUiEffect.OpenFinding(event.key))
            }
            InsightsEvent.AnalyzeNow, is InsightsEvent.Dismiss, is InsightsEvent.NotAProblem -> Unit
        }
    }

    private fun selected(key: String) {
        saved[SELECTED_KEY] = key
        mutableState.update { it.copy(selectedKey = key) }
    }

    private fun pending(value: PendingInsightApply?) {
        saved[PENDING_KEY] = value?.key
        saved[PENDING_ACTION] = value?.action?.name
        mutableState.update { it.copy(pending = value) }
    }

    private fun confirm() {
        if (state.value.working) return
        val request = state.value.pending ?: return
        pending(null) // Consume synchronously: repeated Confirm cannot replay the request.
        val finding = source.report.value?.findings?.firstOrNull { it.key == request.key }
        if (finding == null) {
            message(InsightActionMessage(InsightMessageCode.FINDING_UNAVAILABLE))
            return
        }
        val recommendation = finding.recommendations.firstOrNull { it.action == request.action }
        if (recommendation == null) {
            message(InsightActionMessage(InsightMessageCode.RECOMMENDATION_UNAVAILABLE))
            return
        }
        runAction { source.apply(finding, recommendation) }
    }

    private fun runAction(block: suspend () -> ActionResult) {
        if (state.value.working) return
        mutableState.update { it.copy(working = true) }
        applicationScope.launch {
            try {
                when (val result = block()) {
                    is ActionResult.Applied -> message(InsightActionMessage(InsightMessageCode.APPLIED, result.actionId))
                    is ActionResult.AppliedWithFallback -> message(InsightActionMessage(when (result.note) {
                        FallbackCode.RESTRICTED_TO_RARE -> InsightMessageCode.RESTRICTED_TO_RARE
                    }, result.actionId))
                    is ActionResult.Failed -> message(InsightActionMessage(when (result.code) {
                        FailureCode.READ_FAILED -> InsightMessageCode.READ_FAILED
                        FailureCode.EXECUTION_FAILED -> InsightMessageCode.EXECUTION_FAILED
                        FailureCode.STATE_MISMATCH -> InsightMessageCode.STATE_MISMATCH
                        FailureCode.NOT_UNDOABLE -> InsightMessageCode.NOT_UNDOABLE
                        FailureCode.INVALID_JOURNAL -> InsightMessageCode.INVALID_JOURNAL
                    }))
                    ActionResult.Unknown -> message(InsightActionMessage(InsightMessageCode.UNKNOWN))
                    ActionResult.Reverted -> message(InsightActionMessage(InsightMessageCode.REVERTED))
                    is ActionResult.ChangedExternally -> message(InsightActionMessage(InsightMessageCode.CHANGED_EXTERNALLY))
                    is ActionResult.Refused -> message(InsightActionMessage(when (result.reason) {
                        RefusalCode.NOT_PRIVILEGED -> InsightMessageCode.NOT_PRIVILEGED
                        RefusalCode.PROTECTED -> InsightMessageCode.PROTECTED
                        RefusalCode.NOT_INSTALLED -> InsightMessageCode.NOT_INSTALLED
                        RefusalCode.UID_MISMATCH -> InsightMessageCode.UID_MISMATCH
                        RefusalCode.SHARED_UID -> InsightMessageCode.SHARED_UID
                        RefusalCode.ROLE_HOLDER -> InsightMessageCode.ROLE_HOLDER
                        RefusalCode.UNSUPPORTED_SDK -> InsightMessageCode.UNSUPPORTED_SDK
                        RefusalCode.INVALID_SUBJECT -> InsightMessageCode.INVALID_SUBJECT
                        RefusalCode.INVALID_PACKAGE -> InsightMessageCode.INVALID_PACKAGE
                        RefusalCode.UNRESTORABLE_PRIOR -> InsightMessageCode.UNRESTORABLE_PRIOR
                        RefusalCode.INSPECTION_FAILED -> InsightMessageCode.INSPECTION_FAILED
                        RefusalCode.ALREADY_AT_TARGET -> InsightMessageCode.ALREADY_AT_TARGET
                    }))
                    is ActionResult.OneShot -> message(InsightActionMessage(InsightMessageCode.ONE_SHOT, result.actionId))
                    is ActionResult.OpenSettings -> {
                        results.consume(ownLast)
                        events.trySend(InsightUiEffect.OpenSettings(result.spec))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An interrupted journal may be PREPARED/UNKNOWN. Never invent a failed/restored state.
                message(InsightActionMessage(InsightMessageCode.UNKNOWN))
            } finally {
                mutableState.update { it.copy(working = false) }
            }
        }
    }

    fun message(result: InsightActionMessage) {
        ownLast = result
        results.publish(result)
        events.trySend(InsightUiEffect.Message(result))
    }

    private companion object {
        const val PENDING_KEY = "insights.pending.key"
        const val PENDING_ACTION = "insights.pending.action"
        const val SELECTED_KEY = "key"
    }
}
