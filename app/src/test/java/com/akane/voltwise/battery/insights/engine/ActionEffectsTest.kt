package com.akane.voltwise.battery.insights.engine

import com.akane.voltwise.battery.insights.model.ActionStatus
import com.akane.voltwise.battery.insights.model.ActionType
import com.akane.voltwise.battery.insights.model.AppliedActionInput
import com.akane.voltwise.battery.insights.model.Direction
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.SessionKind
import org.junit.Assert.*
import org.junit.Test

class ActionEffectsTest {
    @Test fun `charging actions compare charging metrics rather than unrelated discharge drain`() {
        for ((type, kind, metric) in listOf(
            Triple("HOT_CHARGING", SessionKind.CHARGE, Metric.TEMPERATURE_C),
            Triple("CHARGING_AT_FULL", SessionKind.PLUGGED, Metric.PLUGGED_AT_FULL_MS),
        )) {
            val sessions = (0..3).map { index ->
                session(index, if (index < 2) 3 * HOUR else HOUR).copy(
                    kind = kind, startLevel = 100, endLevel = 100,
                    peakTemperatureDeciC = if (index < 2) 440 else 350,
                )
            }
            val action = AppliedActionInput(1, "$type:device", ActionType.ENABLE_HIGH_BATTERY_ALERT,
                null, null, sessions[1].endMs + HOUR, ActionStatus.APPLIED)
            val input = inputs(sessions, emptyList()).copy(actions = listOf(action))
            val effect = ActionEffects.detect(input).single()
            assertEquals(metric, effect.evidence.single().metric)
            assertEquals(Direction.DOWN, effect.direction)
            assertEquals(if (kind == SessionKind.CHARGE) 44.0 else 3.0 * HOUR, effect.evidence.single().baseline!!, 0.0)
            assertEquals(if (kind == SessionKind.CHARGE) 35.0 else HOUR.toDouble(), effect.evidence.single().observed, 0.0)
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.drop(1))).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(imported = true) })).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(kind = SessionKind.DISCHARGE) })).isEmpty())
            assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map {
                it.copy(startLevel = null, peakTemperatureDeciC = null)
            })).isEmpty())
        }
    }

    @Test fun `app effects use capture boundaries and ignore straddling ineligible and censored windows`() {
        val sessions = (0..4).map { session(it) }
        val at = sessions[2].startMs + HOUR / 2
        val action = AppliedActionInput(3, "WAKEUP_STORM:$APP", ActionType.RESTRICT_BACKGROUND,
            APP, UID, at, ActionStatus.APPLIED)
        val rows = sessions.mapIndexed { i, s -> row(s.id).copy(wakeupAlarms = if (i < 2) 40 else if (i == 2) 999 else 10) }
        val input = inputs(sessions, rows).copy(actions = listOf(action))
        val effect = ActionEffects.detect(input).single()
        assertEquals(40.0, effect.evidence.single().baseline!!, 0.0)
        assertEquals(10.0, effect.evidence.single().observed, 0.0)
        assertEquals(4, effect.evidence.single().sessions)
        assertEquals(effect, ActionEffects.detect(input.copy(sessions = sessions.reversed(), appSessions = rows.reversed())).single())
        for (status in listOf(ActionStatus.FAILED, ActionStatus.UNKNOWN, ActionStatus.ONE_SHOT)) {
            assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(status = status)))).isEmpty())
        }
        assertTrue(ActionEffects.detect(input.copy(actions = listOf(action.copy(uid = null)))).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(appSessions = rows.map { it.copy(wakeupAlarms = 10) })).isEmpty())
        assertTrue(ActionEffects.detect(input.copy(sessions = sessions.map { it.copy(appWindow = null) })).isEmpty())
        val censored = input.copy(sessions = sessions.map { it.copy(appWindow = it.appWindow?.copy(fullRowSet = true)) },
            appSessions = rows.mapIndexed { i, row -> if (i == 0) row.copy(packageName = "other.app") else row })
        assertTrue(ActionEffects.detect(censored).isEmpty())
    }
}
