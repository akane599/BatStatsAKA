package app.batstats.battery.data

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class HistoryFilesTest {
    @Test fun quotedCsvRoundTripsCommasQuotesNewlinesAndMissingValues() {
        val row = listOf("1700000000000", "", "one,two", "quote\"value", "line\nbreak", "")
        val out = StringWriter()
        HistoryCsv.writeRow(out, row)
        assertEquals(listOf(row), HistoryCsv.rows(StringReader(out.toString())).toList())
    }
    @Test fun legacyCsvAndCrLfAreAcceptedWithoutInventingValues() {
        val rows = HistoryCsv.rows(StringReader("timestamp,levelPercent\r\n1000,0\r\n2000,\r\n")).toList()
        assertEquals(listOf("1000", "0"), rows[1]); assertEquals(listOf("2000", ""), rows[2])
    }
    @Test fun malformedCsvIsRejectedBeforeImport() {
        for (raw in listOf("\"unfinished", "\"value\"garbage", "unquoted\"quote", "x".repeat(2049))) {
            try { HistoryCsv.rows(StringReader(raw)).toList(); fail("Must reject malformed/oversized CSV") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
    @Test fun fileLimitAppliesToSingleAndBulkReads() {
        for (bulk in listOf(false, true)) {
            val stream = LimitedHistoryInput("12345".byteInputStream(), 4)
            try { if (bulk) stream.readBytes() else while (stream.read() >= 0) { }; fail("Oversized input must fail") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("1234", LimitedHistoryInput("1234".byteInputStream(), 4).readBytes().decodeToString())
    }
    @Test fun jsonBoundsRejectDeepStructuresAndLargeStringsButRespectEscapes() {
        for (raw in listOf("[".repeat(33), "\"" + "x".repeat(8193) + "\"")) {
            assertThrows(IllegalArgumentException::class.java) { LimitedHistoryInput(raw.byteInputStream(), json = true).readBytes() }
        }
        val raw = "{\"text\":\"braces [ { and escaped \\\" quote\"}"
        assertEquals(raw, LimitedHistoryInput(raw.byteInputStream(), json = true).readBytes().decodeToString())
    }
}
