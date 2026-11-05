package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.logs.LogEntry
import io.github.derundevu.yaxc.presentation.logs.LogLevel
import io.github.derundevu.yaxc.presentation.logs.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogHistoryTest {
    @Test
    fun defaultEntryLimitEvictsOldestAndPreservesMonotonicIds() {
        val history = LogHistory()
        history.append((1..5_001).map { "row $it" })

        val snapshot = history.snapshot()
        assertEquals(5_000, snapshot.entries.size)
        assertEquals(2L, snapshot.entries.first().id)
        assertEquals(5_001L, snapshot.entries.last().id)
        assertEquals(5_001L, snapshot.received)
        assertEquals(1L, snapshot.dropped)
        assertByteAccounting(snapshot)
    }

    @Test
    fun defaultByteLimitEvictsBeforeEntryLimit() {
        val history = LogHistory()
        val line = "é".repeat(16_000)
        history.append(List(140) { line })

        val snapshot = history.snapshot()
        val fitting = (4 * 1024 * 1024) / (32_000 + 1)
        assertEquals(fitting, snapshot.entries.size)
        assertEquals((140 - fitting).toLong(), snapshot.dropped)
        assertEquals(140L, snapshot.received)
        assertTrue(snapshot.bytes <= 4 * 1024 * 1024)
        assertByteAccounting(snapshot)
    }

    @Test
    fun utf8LimitIncludesNewlinesAndSurrogatePairs() {
        val history = LogHistory(maxEntries = 10, maxBytes = 14)
        history.append(listOf("é😀", "é😀"))
        assertEquals(14, history.snapshot().bytes)
        assertEquals(0L, history.snapshot().dropped)

        history.append(listOf("é😀"))
        val snapshot = history.snapshot()
        assertEquals(listOf(2L, 3L), snapshot.entries.map { it.id })
        assertEquals(14, snapshot.bytes)
        assertEquals(1L, snapshot.dropped)
        assertByteAccounting(snapshot)
    }

    @Test
    fun entryAndByteLimitsAreBothEnforcedAcrossBatches() {
        val history = LogHistory(maxEntries = 2, maxBytes = 20)
        history.append(listOf("one", "two", "three"))
        assertEquals(listOf("two", "three"), history.snapshot().entries.map { it.rawText() })
        assertEquals(1L, history.snapshot().dropped)

        history.append(listOf("1234567890123456"))
        val snapshot = history.snapshot()
        assertEquals(listOf("1234567890123456"), snapshot.entries.map { it.rawText() })
        assertEquals(3L, snapshot.dropped)
        assertEquals(4L, snapshot.received)
        assertByteAccounting(snapshot)
    }

    @Test
    fun oversizedDirectInputIsVisiblyBoundedWithoutWaitingForReader() {
        val history = LogHistory()
        history.append(listOf("x".repeat(100_000)))

        val snapshot = history.snapshot()
        val text = snapshot.entries.single().rawText()
        assertTrue(text.startsWith("x".repeat(16_384)))
        assertTrue(text.length < 16_450)
        assertEquals(0L, snapshot.dropped)
        assertByteAccounting(snapshot)
    }

    @Test
    fun singleRowByteTruncationDoesNotSplitSupplementaryCharacters() {
        val history = LogHistory(maxBytes = 32)
        history.append(listOf("😀".repeat(100)))

        val snapshot = history.snapshot()
        val text = snapshot.entries.single().rawText()
        assertTrue(text.startsWith("😀"))
        assertFalse(text.any { it == '\uFFFD' })
        assertTrue(snapshot.bytes <= 32)
        assertByteAccounting(snapshot)
        assertWellFormedSurrogates(text)
    }

    @Test
    fun physicalCharacterLimitDoesNotSplitSupplementaryCharacters() {
        val history = LogHistory()
        history.append(listOf("x".repeat(16_383) + "😀tail"))

        val text = history.snapshot().entries.single().rawText()
        assertTrue(text.startsWith("x".repeat(16_383)))
        assertFalse(text.contains("tail"))
        assertWellFormedSurrogates(text)
        assertByteAccounting(history.snapshot())
    }

    @Test
    fun tinyBudgetsStillBoundTruncationAndCountUnrepresentableRows() {
        val tiny = LogHistory(maxBytes = 2)
        tiny.append(listOf("too long"))
        assertEquals(1, tiny.snapshot().entries.single().rawText().length)
        assertEquals(2, tiny.snapshot().bytes)

        val newlineOnly = LogHistory(maxBytes = 1)
        newlineOnly.append(listOf("", "cannot fit"))
        val snapshot = newlineOnly.snapshot()
        assertEquals("", snapshot.entries.single().rawText())
        assertEquals(1, snapshot.bytes)
        assertEquals(1L, snapshot.dropped)
        assertEquals(2L, snapshot.received)
    }

    @Test
    fun utf8AccountingMatchesEncoderForUnpairedSurrogates() {
        val history = LogHistory(maxBytes = 100)
        val rows = listOf("\uD800", "\uDC00", "a\uD800b\uDC00", "😀", "é漢")
        history.append(rows)
        assertEquals(rows, history.snapshot().entries.map { it.rawText() })
        assertByteAccounting(history.snapshot())
    }

    @Test
    fun clearResetsLocalCountersButNotIdsOrReceivedAndOldSnapshotsSurvive() {
        val history = LogHistory(maxEntries = 2)
        history.append(listOf("first", "second", "third"))
        val before = history.snapshot()
        history.clear()
        val cleared = history.snapshot()
        assertTrue(cleared.entries.isEmpty())
        assertEquals(0, cleared.bytes)
        assertEquals(0L, cleared.dropped)
        assertEquals(3L, cleared.received)

        history.append(listOf("fourth", "fifth", "sixth"))
        val after = history.snapshot()
        assertEquals(listOf(5L, 6L), after.entries.map { it.id })
        assertEquals(6L, after.received)
        assertEquals(1L, after.dropped)
        assertEquals(listOf("second", "third"), before.entries.map { it.rawText() })
        assertEquals(listOf(2L, 3L), before.entries.map { it.id })
        assertEquals(1L, before.dropped)
        assertEquals(3L, before.received)
        assertByteAccounting(before)
        assertByteAccounting(after)
    }


    @Test
    fun threadtimeParsesMetadataAndPreservesPaddingExactly() {
        val raw = "11-06 12:34:56.123   120  345 W TProxyService  :  permission denied  "
        val row = parse(raw)
        assertEquals("11-06 12:34:56.123", row.timestamp)
        assertEquals(LogLevel.Warning, row.level)
        assertEquals(LogSource.Vpn, row.source)
        assertEquals("TProxyService", row.tag)
        assertEquals(" permission denied  ", row.message)
        assertEquals(raw, row.rawText())
    }

    @Test
    fun legacyTimeFormatParsesPidAndPriorityWithoutLeakingThemIntoMessage() {
        val raw = "11-06 12:34:56.123 D/PingWorker3( 1234): probe started"
        val row = parse(raw)
        assertEquals("11-06 12:34:56.123", row.timestamp)
        assertEquals(LogLevel.Debug, row.level)
        assertEquals(LogSource.Helper, row.source)
        assertEquals("PingWorker3", row.tag)
        assertEquals("probe started", row.message)
        assertEquals(raw, row.rawText())
    }

    @Test
    fun nativeSeverityOverridesGoLogInfoWithoutReplacingOuterTimestamp() {
        for ((native, expected) in listOf("Warning" to LogLevel.Warning, "Error" to LogLevel.Error)) {
            val raw = "11-06 12:34:56.123 120 345 I GoLog : 2026/11/06 12:34:55.123456 [$native] transport failed"
            val row = parse(raw)
            assertEquals(expected, row.level)
            assertEquals(LogSource.Xray, row.source)
            assertEquals("11-06 12:34:56.123", row.timestamp)
            assertEquals("transport failed", row.message)
            assertEquals(raw, row.rawText())
        }
    }

    @Test
    fun rawNativeHeadersSupportTimestampsAndSeverityOnlyRows() {
        val rows = listOf(
            "2026/11/06 12:34:56.123456 [Warning] transport failed" to "2026/11/06 12:34:56.123456",
            "2026-11-06T12:34:56.123Z [Warning] transport failed" to "2026-11-06T12:34:56.123Z",
            "2026-11-06T12:34:56+03:00 [Warning] transport failed" to "2026-11-06T12:34:56+03:00",
            "[Warning] transport failed" to null,
        )
        for ((raw, timestamp) in rows) {
            val row = parse(raw)
            assertEquals(timestamp, row.timestamp)
            assertEquals(LogLevel.Warning, row.level)
            assertEquals(LogSource.Xray, row.source)
            assertNull(row.tag)
            assertEquals("transport failed", row.message)
            assertEquals(raw, row.rawText())
        }
    }

    @Test
    fun timestampedNativeAccessRowsRemainUnknownRatherThanGuessingFromTheirBody() {
        val raw = "2026/11/06 12:34:56 from 127.0.0.1 accepted tcp:example.org:443 [error -> proxy]"
        val row = parse(raw)
        assertEquals("2026/11/06 12:34:56", row.timestamp)
        assertEquals(LogLevel.Unknown, row.level)
        assertEquals(LogSource.Xray, row.source)
        assertEquals(raw, row.rawText())
    }

    @Test
    fun severityWordsAndNonNativeBracketedTextDoNotOverrideAppPriority() {
        val raw = "11-06 12:34:56.123 120 345 D MainActivity : [Error] error Warning failed"
        val row = parse(raw)
        assertEquals(LogLevel.Debug, row.level)
        assertEquals(LogSource.App, row.source)
        assertEquals("[Error] error Warning failed", row.message)
        assertEquals(raw, row.rawText())
        assertEquals(LogLevel.Info, parse("11-06 12:34:56.123 120 345 I GoLog : request [Error] warning").level)
    }

    @Test
    fun knownTagsClassifySourcesAndRootHintWins() {
        val tags = mapOf(
            "GoLog" to LogSource.Xray,
            "Xray" to LogSource.Xray,
            "TProxyService" to LogSource.Vpn,
            "PingWorker" to LogSource.Helper,
            "PingWorker5Service" to LogSource.Helper,
            "HEV" to LogSource.Helper,
            "hev-socks5-tunnel" to LogSource.Helper,
            "tun2socks" to LogSource.Helper,
            "XrayHelper" to LogSource.Helper,
            "Unrelated" to LogSource.App,
            "PingWorkerUnrelated" to LogSource.App,
        )
        for ((tag, source) in tags) {
            val raw = "11-06 12:34:56.123 120 345 I $tag : hello"
            assertEquals(source, parse(raw).source)
            val history = LogHistory()
            history.append(listOf(raw), LogSource.Xray)
            assertEquals(LogSource.Xray, history.snapshot().entries.single().source)
        }
        val history = LogHistory()
        history.append(listOf("unstructured root output"), LogSource.Xray)
        assertEquals(LogSource.Xray, history.snapshot().entries.single().source)
        assertEquals(LogLevel.Unknown, history.snapshot().entries.single().level)
    }

    @Test
    fun malformedRowsAndStackContinuationsRemainVisibleWithoutInventedMetadata() {
        val rows = listOf(
            "",
            "plain error warning debug message",
            "    at io.github.example.Main.run(Main.kt:42)",
            "Caused by: java.io.IOException: error",
            "11-06 12:34:56.123 not-a-pid 345 W GoLog : malformed",
            "11-06 12:34:56.123 120 345 Q GoLog : malformed priority",
            "11-06 12:34:56.123 120 345 W    : missing tag",
            "99-06 12:34:56.123 120 345 W GoLog : invalid month",
            "11-06 25:34:56.123 120 345 W GoLog : invalid hour",
            "[not-a-level] error",
            "2026/no/date [Error] malformed",
            "2026/11-06 12:34:56 [Error] mixed date separators",
            "2026/13/06 12:34:56 [Error] invalid month",
            "--------- beginning of main",
        )
        val history = LogHistory()
        history.append(rows)
        assertEquals(rows, history.snapshot().entries.map { it.rawText() })
        for (row in history.snapshot().entries) {
            assertEquals(LogLevel.Unknown, row.level)
            assertEquals(LogSource.App, row.source)
            assertNull(row.timestamp)
            assertNull(row.tag)
            assertEquals("", row.prefix)
        }
    }

    @Test
    fun androidPrioritiesHaveExplicitMapping() {
        val priorities = mapOf(
            "V" to LogLevel.Verbose,
            "D" to LogLevel.Debug,
            "I" to LogLevel.Info,
            "W" to LogLevel.Warning,
            "E" to LogLevel.Error,
            "F" to LogLevel.Error,
            "A" to LogLevel.Error,
            "S" to LogLevel.Unknown,
        )
        for ((priority, level) in priorities) {
            assertEquals(level, parse("11-06 12:34:56.123 120 345 $priority App : body").level)
        }
    }

    private fun parse(raw: String): LogEntry {
        val history = LogHistory()
        history.append(listOf(raw))
        return history.snapshot().entries.single()
    }

    private fun assertByteAccounting(snapshot: LogHistorySnapshot) {
        assertEquals(snapshot.entries.sumOf { it.rawText().toByteArray(Charsets.UTF_8).size + 1 }, snapshot.bytes)
    }

    private fun assertWellFormedSurrogates(text: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(index + 1 < text.length && Character.isLowSurrogate(text[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(char))
                index++
            }
        }
    }
}
