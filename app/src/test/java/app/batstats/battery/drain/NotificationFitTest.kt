package app.batstats.battery.drain

import app.batstats.R
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.drain.NotificationFitter.Row
import app.batstats.battery.measurement.EtaHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The %/h notification text in Spanish (its longest strings) through [NotificationFitter.fit] and
 * [NotificationFitter.rows] at 1.0x, 1.3x and 2.0x. JVM tests have no text paint, so a stand-in measures widths: an
 * average glyph of 0.55 em at 14 sp (the notification text size), 1 px per dp, against the 260 dp a 360 dp phone's
 * shade gives the custom content. It checks the choice logic and the forms, not real glyph widths.
 */
class NotificationFitTest {
    private val spanish = Locale.forLanguageTag("es-ES")
    private val utc = TimeZone.getTimeZone("UTC")
    private val formats = NotificationContent.Formats(spanish, utc, SimpleDateFormat("HH:mm", spanish).apply { timeZone = utc },
        SimpleDateFormat("d MMM, HH:mm", spanish).apply { timeZone = utc })
    private val hour = 3_600_000L
    private val now = 1_790_000_000_000L

    private val content = NotificationContent.Builder(SpanishStrings::get).build(
        NotificationInput(
            reading = EtaHold.next(EtaHold.Reading(), BatteryRepository.Realtime(BatterySample(timestamp = now, levelPercent = 78,
                status = 3, plugged = 0, currentNowUa = -612_000, chargeCounterUah = 3_000_000, voltageMv = 3_900,
                temperatureDeciC = 312, health = 2, screenOn = true, etaMs = 18_600_000))),
            session = ChargeSession(sessionId = "s", type = SessionType.DISCHARGE, startTime = now - 3 * hour, endTime = null,
                startLevel = 90, endLevel = null, deltaUah = 496_000, avgCurrentUa = null, estCapacityMah = null,
                observedMs = 3 * hour, counterCoveredMs = 3 * hour, screenOnMs = hour, screenOffMs = 2 * hour,
                screenOnUah = 420_000, screenOffUah = 76_000, cpuSuspendMs = 10_152_000),
            fullUah = 4_000_000,
        ),
        formats,
    )

    private val shadePx = 260f
    private val cellPx = shadePx / 3 - 4f
    private fun glyphPx(fontScale: Float) = 0.55f * 14f * fontScale
    private fun lineWidth(fontScale: Float): (String) -> Float = { it.length * glyphPx(fontScale) }
    /** A grid value as [NotificationFitter.styled] draws it: the unit at [NotificationFitter.UNIT_SCALE]. */
    private fun cellWidth(fontScale: Float): (Quantity) -> Float = { quantity ->
        (quantity.number.length + (quantity.unit?.let { 1 + it.length * NotificationFitter.UNIT_SCALE } ?: 0f)) * glyphPx(fontScale)
    }

    @Test fun spanishPercentPerHourFormsAreEachReallyShorter() {
        assertEquals(
            listOf("Encendida 10,5 %/h · Apagada 0,95 %/h · quedan 5 h 10 min", "Encendida 10,5 %/h · Apagada 0,95 %/h · quedan 5:10 h",
                "Enc. 10,5 %/h · Apag. 0,95 %/h · quedan 5:10 h", "Enc. 10,5 · Apag. 0,95 %/h · quedan 5:10 h",
                "Enc. 10,5 %/h · Apag. 0,95 %/h", "Enc. 10,5 · Apag. 0,95 %/h", "Quedan 5:10 h"),
            content.summary,
        )
        assertEquals(listOf("10,5 %/h"), content.cells.single { it.label == "Pant. enc." }.values.map { it.toString() })
        assertEquals(listOf("0,95 %/h"), content.cells.single { it.label == "Pant. apag." }.values.map { it.toString() })
    }

    @Test fun eachScaleShowsTheLongestSummaryThatFitsAndBothRatesAtTheDefaultScale() {
        val picked = listOf(1f, 1.3f, 2f).associateWith { scale ->
            if (Row.SUMMARY in NotificationFitter.rows(scale)) NotificationFitter.fit(content.summary, shadePx, lineWidth(scale)) else null
        }
        listOf(1f, 1.3f).forEach { scale ->
            val shown = picked.getValue(scale)!!
            val index = content.summary.indexOf(shown)
            assertTrue("${scale}x: '$shown' fits", lineWidth(scale)(shown) <= shadePx)
            content.summary.take(index).forEach { longer -> assertTrue("${scale}x: '$longer' would clip", lineWidth(scale)(longer) > shadePx) }
        }
        assertEquals("Enc. 10,5 %/h · Apag. 0,95 %/h", picked[1f])
        assertEquals("2.0x drops the summary row", null, picked[2f])
        assertFalse(Row.FOOTER in NotificationFitter.rows(2f))
    }

    @Test fun thePercentPerHourCellsFitAThirdOfTheShadeUpToTheDesignScale() {
        val rates = content.cells.filter { it.label == "Pant. enc." || it.label == "Pant. apag." }
        listOf(1f, 1.3f).forEach { scale ->
            rates.forEach { cell ->
                val shown = NotificationFitter.fit(cell.values, cellPx, cellWidth(scale))!!
                assertTrue("${scale}x: '$shown' fits its cell", cellWidth(scale)(shown) <= cellPx)
            }
        }
        // Above it every row of the grid still shows; a form that fits nothing falls back to the shortest.
        assertEquals(Quantity("10,5", "%/h"), NotificationFitter.fit(rates.first().values, cellPx, cellWidth(2f)))
    }

    /** The real Spanish templates over the English defaults, as Android resolves `values-es`. */
    private object SpanishStrings {
        private val strings by lazy {
            val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
            val names = listOf("values", "values-es").flatMap { dir ->
                File(res, dir).listFiles { file -> file.name.startsWith("strings") && file.name.endsWith(".xml") }.orEmpty().flatMap { file ->
                    val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
                    (0 until elements.length).map { index ->
                        val element = elements.item(index) as org.w3c.dom.Element
                        element.getAttribute("name") to element.textContent.replace("\\'", "'").replace("\\n", "\n")
                    }
                }
            }.toMap()
            R.string::class.java.fields.mapNotNull { field -> names[field.name]?.let { field.getInt(null) to it } }.toMap()
        }

        fun get(id: Int, arguments: Array<out Any>): String = String.format(Locale.forLanguageTag("es-ES"), strings.getValue(id), *arguments)
    }
}
