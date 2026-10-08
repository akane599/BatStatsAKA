package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.data.sampling.FakeKeyValueStore
import com.akane.voltwise.battery.insights.model.Confidence
import com.akane.voltwise.battery.insights.model.Finding
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.InsightReport
import com.akane.voltwise.battery.insights.model.Severity
import com.akane.voltwise.battery.insights.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightNotifierPolicyTest {
    private var now = 1_000_000_000L
    private val store = FakeKeyValueStore()
    private val policy = InsightNotificationPolicy(store) { now }

    private fun finding(
        key: String,
        severity: Severity = Severity.HIGH,
        confidence: Confidence = Confidence.MEDIUM,
    ) = Finding(key, FindingType.BACKGROUND_RUNAWAY, severity, confidence, 1.0, Subject.Device, null, emptyList(), emptyList(), emptyList())

    private fun report(vararg findings: Finding, headline: Finding? = null) =
        InsightReport(now, findings.toList(), headline)

    @Test
    fun highSeverityMediumConfidenceIsSelected() {
        assertEquals("a", policy.select(report(finding("a")))?.key)
    }

    @Test
    fun lowSeverityOrLowConfidenceIsIgnored() {
        assertNull(policy.select(report(finding("a", Severity.MEDIUM), finding("b", confidence = Confidence.LOW))))
    }

    @Test
    fun headlineIsPreferredOverOtherFindings() {
        val h = finding("headline")
        assertEquals("headline", policy.select(report(finding("a"), headline = h))?.key)
    }

    @Test
    fun cooldownBlocksUntilTwentyFourHours() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS - 1
        assertNull(policy.select(report(finding("b"))))
        now += 1
        assertEquals("b", policy.select(report(finding("b")))?.key)
    }

    @Test
    fun alreadyNotifiedKeyIsSkippedAfterCooldown() {
        policy.markNotified(finding("a"))
        now += InsightNotificationPolicy.COOLDOWN_MS
        assertNull(policy.select(report(finding("a"))))
        assertEquals("b", policy.select(report(finding("a"), finding("b")))?.key)
    }

    @Test
    fun stateSurvivesANewPolicyOverTheSameStore() {
        policy.markNotified(finding("a"))
        assertNull(InsightNotificationPolicy(store) { now }.select(report(finding("b"))))
    }

    @Test
    fun clockSetBackDoesNotBlockForever() {
        policy.markNotified(finding("a"))
        now -= 1
        assertEquals("b", policy.select(report(finding("b")))?.key)
    }
}
