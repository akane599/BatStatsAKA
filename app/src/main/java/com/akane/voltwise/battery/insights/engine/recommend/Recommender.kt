package com.akane.voltwise.battery.insights.engine.recommend

import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightInputs
import com.akane.voltwise.battery.insights.model.Recommendation
import com.akane.voltwise.battery.insights.model.Subject

object Recommender {
    fun recommend(finding: Finding, inputs: InsightInputs, sdkInt: Int): Finding {
        val actions = when (finding.type) {
            FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.STUCK_WAKELOCK,
            FindingType.WAKEUP_STORM, FindingType.JOB_STORM, FindingType.BACKGROUND_LOCATION,
            FindingType.BACKGROUND_RADIO -> appActions
            FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE ->
                appActions.dropLast(1) + ActionType.FORCE_STOP + ActionType.OPEN_APP_SETTINGS
            FindingType.DOZE_WHITELISTED_DRAINER -> listOf(
                ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS,
            )
            FindingType.DOZE_BLOCKED, FindingType.SCREEN_OFF_DRAIN_HIGH ->
                listOf(ActionType.OPEN_BATTERY_OPTIMIZATION_SETTINGS)
            FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING -> listOf(ActionType.ENABLE_HIGH_BATTERY_ALERT)
            FindingType.TREND, FindingType.HEALTH_DECLINE, FindingType.ACTION_EFFECT -> emptyList()
        }
        val app = finding.subject as? Subject.App
        val pkg = app?.packageName
        // A live whitelist finding proves an earlier removal no longer holds.
        val applied = inputs.actions.filter {
            it.status == ActionStatus.APPLIED && it.packageName == pkg &&
                (app == null || it.uid == app.uid) &&
                !(finding.type == FindingType.DOZE_WHITELISTED_DRAINER && it.type == ActionType.REMOVE_DOZE_WHITELIST)
        }.map { it.type }.toSet()
        return finding.copy(recommendations = actions.filterNot { action ->
            action in applied || (sdkInt < 28 &&
                (action == ActionType.STANDBY_BUCKET_RESTRICTED || action == ActionType.STANDBY_BUCKET_RARE))
        }.map { action ->
            Recommendation(action, action != ActionType.FORCE_STOP, action in privilegedActions)
        })
    }

    private val appActions = listOf(
        ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.OPEN_APP_SETTINGS,
    )
    private val privilegedActions = setOf(
        ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED, ActionType.STANDBY_BUCKET_RARE,
        ActionType.FORCE_STOP, ActionType.REMOVE_DOZE_WHITELIST,
    )
}
