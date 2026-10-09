package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.engine.recommend.Recommender
import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.FindingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
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
