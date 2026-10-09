package com.akane.voltwise.viewmodel

import androidx.compose.runtime.Immutable
import com.akane.voltwise.battery.data.db.InsightActionStatus
import com.akane.voltwise.battery.insights.actions.IntentSpec
import com.akane.voltwise.battery.insights.model.*
import java.util.Collections

// Match the adopted project's unmodifiable List snapshots; no new collection dependency.
internal fun <T> Iterable<T>.insightSnapshot(): List<T> = Collections.unmodifiableList(toList())

@Immutable
data class InsightFindingState(
    val key: String,
    val type: FindingType,
    val severity: Severity,
    val confidence: Confidence,
    val score: Double,
    val subject: Subject,
    val direction: Direction?,
    val evidence: List<Evidence>,
    val series: List<SeriesPoint>,
    val recommendations: List<RecommendationState>,
    val attributions: List<Attribution>,
)

@Immutable
data class RecommendationState(
    val action: ActionType,
    val reversible: Boolean,
    val requiresPrivilege: Boolean,
    val available: Boolean,
    val alreadyApplied: Boolean,
)

@Immutable
data class AppliedInsightAction(
    val id: Long,
    val findingKey: String,
    val action: ActionType?,
    val packageName: String?,
    val status: InsightActionStatus,
    val appliedAt: Long?,
    val undoable: Boolean,
    val effect: InsightFindingState?,
)

@Immutable
data class PendingInsightApply(val key: String, val action: ActionType)

@Immutable
data class InsightApplyState(
    val pending: PendingInsightApply? = null,
    val selectedKey: String? = null,
    val working: Boolean = false,
    /** Latest unconsumed result; ResultShown clears it and its saved representation. */
    val lastResult: InsightActionMessage? = null,
)

@Immutable
data class InsightsUiState(
    val loaded: Boolean = false,
    val headline: InsightFindingState? = null,
    val keyFindings: List<InsightFindingState> = emptyList(),
    val changes: List<InsightFindingState> = emptyList(),
    val appliedActions: List<AppliedInsightAction> = emptyList(),
    val lastAnalyzedAt: Long? = null,
    val analyzing: Boolean = false,
    val privileged: Boolean = false,
    val error: InsightMessageCode? = null,
    val empty: Boolean = true,
    val eligibleSessionCount: Int = 0,
    val lowData: Boolean = true,
    val apply: InsightApplyState = InsightApplyState(),
)

@Immutable
data class FindingDetailsUiState(
    val loaded: Boolean = false,
    val finding: InsightFindingState? = null,
    val relatedActions: List<AppliedInsightAction> = emptyList(),
    val privileged: Boolean = false,
    val apply: InsightApplyState = InsightApplyState(),
)

/** Fixed codes only. The UI owns localized copy; shell text is never a message. */
enum class InsightMessageCode {
    APPLIED, RESTRICTED_TO_RARE, UNKNOWN, REVERTED, CHANGED_EXTERNALLY, ONE_SHOT,
    READ_FAILED, EXECUTION_FAILED, STATE_MISMATCH, NOT_UNDOABLE, INVALID_JOURNAL,
    NOT_PRIVILEGED, PROTECTED, NOT_INSTALLED, UID_MISMATCH, SHARED_UID, ROLE_HOLDER,
    UNSUPPORTED_SDK, INVALID_SUBJECT, INVALID_PACKAGE, UNRESTORABLE_PRIOR, INSPECTION_FAILED, ALREADY_AT_TARGET,
    FINDING_UNAVAILABLE, RECOMMENDATION_UNAVAILABLE, ANALYSIS_FAILED, FEEDBACK_FAILED,
}

@Immutable
data class InsightActionMessage(val code: InsightMessageCode, val actionId: Long? = null)

sealed interface InsightUiEffect {
    data class Message(val result: InsightActionMessage) : InsightUiEffect
    data class OpenSettings(val spec: IntentSpec) : InsightUiEffect
    data class OpenFinding(val key: String) : InsightUiEffect
}

sealed interface InsightsEvent {
    data object AnalyzeNow : InsightsEvent
    data class Dismiss(val key: String) : InsightsEvent
    data class NotAProblem(val key: String) : InsightsEvent
    data class OpenFinding(val key: String) : InsightsEvent
    data class Undo(val actionId: Long) : InsightsEvent
    data class RequestApply(val key: String, val action: ActionType) : InsightsEvent
    data object ConfirmApply : InsightsEvent
    data object CancelApply : InsightsEvent
    data object ResultShown : InsightsEvent
}
