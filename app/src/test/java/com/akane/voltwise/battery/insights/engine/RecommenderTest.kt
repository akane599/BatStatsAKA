package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.recommend.Recommender
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private val appTypes = listOf(
        FindingType.APP_DRAIN_ANOMALY, FindingType.NEW_HEAVY_APP, FindingType.STUCK_WAKELOCK,
        FindingType.WAKEUP_STORM, FindingType.JOB_STORM, FindingType.BACKGROUND_LOCATION,
        FindingType.BACKGROUND_RADIO, FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE,
    )

    @Test fun backgroundRestrictionRequiresApi28() {
        val input = inputs(emptyList(), emptyList())
        for (type in appTypes) {
            val candidate = finding(type, emptyList(), Subject.App(UID, APP))
            for (sdk in listOf(26, 27, 28)) {
                val actions = Recommender.recommend(candidate, input, sdk).recommendations.map { it.action }
                assertEquals("$type API $sdk restrict gating", sdk >= 28, ActionType.RESTRICT_BACKGROUND in actions)
            }
        }
    }

    @Test fun whitelistedAppsOfferRemovalInsteadOfRestrictions() {
        val input = inputs(emptyList(), emptyList()).copy(dozeUserWhitelist = setOf(APP))
        for (type in appTypes) {
            val candidate = finding(type, emptyList(), Subject.App(UID, APP))
            val remaining = if (type in listOf(FindingType.BACKGROUND_RUNAWAY, FindingType.LINGERING_FOREGROUND_SERVICE)) {
                listOf(ActionType.FORCE_STOP, ActionType.OPEN_APP_SETTINGS)
            } else listOf(ActionType.OPEN_APP_SETTINGS)
            for (sdk in listOf(27, 28, 37)) {
                val recommendations = Recommender.recommend(candidate, input, sdk).recommendations
                assertEquals(
                    "$type API $sdk replaces both restrictions with removal first",
                    listOf(ActionType.REMOVE_DOZE_WHITELIST) + remaining,
                    recommendations.map { it.action },
                )
                assertTrue(recommendations.first().reversible)
                assertTrue(recommendations.first().requiresPrivilege)
            }
            for (whitelist in listOf(null, emptySet(), setOf("other.app"))) {
                assertEquals(
                    "$type keeps restrictions when whitelist membership is absent or unknown",
                    listOf(ActionType.RESTRICT_BACKGROUND, ActionType.STANDBY_BUCKET_RESTRICTED) + remaining,
                    Recommender.recommend(candidate, input.copy(dozeUserWhitelist = whitelist), 28)
                        .recommendations.map { it.action },
                )
            }
        }
    }

    @Test fun knownWhitelistMembershipKeepsRemovalDespiteOldAppliedRow() {
        val candidate = finding(FindingType.APP_DRAIN_ANOMALY, emptyList(), Subject.App(UID, APP))
        val input = inputs(emptyList(), emptyList()).copy(
            dozeUserWhitelist = setOf(APP),
            actions = listOf(AppliedActionInput(
                1, "old", ActionType.REMOVE_DOZE_WHITELIST, APP, UID, 0, ActionStatus.APPLIED,
            )),
        )
        assertEquals(
            listOf(ActionType.REMOVE_DOZE_WHITELIST, ActionType.OPEN_APP_SETTINGS),
            Recommender.recommend(candidate, input, 28).recommendations.map { it.action },
        )
    }

    private val chargingTypes = listOf(FindingType.CHARGING_AT_FULL, FindingType.HOT_CHARGING)
    private val oneShot = AppliedActionInput(
        1, "old", ActionType.ENABLE_HIGH_BATTERY_ALERT, null, null, 0, ActionStatus.ONE_SHOT,
    )

    @Test fun enabledHighBatteryAlertSuppressesChargingRecommendationWithoutJournal() {
        val input = inputs(emptyList(), emptyList()).copy(highBatteryAlertEnabled = true)
        for (type in chargingTypes) {
            val result = Recommender.recommend(finding(type, emptyList()), input, sdkInt = 37)
            assertTrue("$type must not recommend an already enabled alert", result.recommendations.isEmpty())
        }
    }

    @Test fun enabledHighBatteryAlertSuppressesChargingRecommendationAfterOneShot() {
        val input = inputs(emptyList(), emptyList()).copy(
            highBatteryAlertEnabled = true, actions = listOf(oneShot),
        )
        for (type in chargingTypes) {
            val result = Recommender.recommend(finding(type, emptyList()), input, sdkInt = 37)
            assertTrue("$type must not repeat the completed alert action", result.recommendations.isEmpty())
        }
    }

    @Test fun disabledHighBatteryAlertOffersChargingRecommendationEvenAfterOneShot() {
        val input = inputs(emptyList(), emptyList()).copy(highBatteryAlertEnabled = false)
        for (actions in listOf(emptyList(), listOf(oneShot))) {
            for (type in chargingTypes) {
                val result = Recommender.recommend(
                    finding(type, emptyList()), input.copy(actions = actions), sdkInt = 37,
                )
                assertEquals(
                    "$type must offer the alert while disabled",
                    listOf(ActionType.ENABLE_HIGH_BATTERY_ALERT),
                    result.recommendations.map { it.action },
                )
            }
        }
    }
}
