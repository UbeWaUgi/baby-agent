package com.yugainanal.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySummaryTest {
    private val date = LocalDate.of(2026, 1, 5)

    @Test
    fun onlyConfirmedConsumptionCountsTowardsTheAmount() {
        val records = listOf(
            entry(1, EntryDraft(RecordType.DIRECT_BREASTFEEDING, date.atTime(8, 0), date.atTime(8, 12))),
            entry(2, EntryDraft(RecordType.FORMULA, date.atTime(9, 0), amount = 95.5)),
            entry(3, EntryDraft(RecordType.FORMULA, date.atTime(10, 0), amount = 104.5)),
            entry(4, EntryDraft(RecordType.FORMULA, date.atTime(11, 0), amount = 120.0, amountKind = AmountKind.OFFERED)),
            entry(5, EntryDraft(RecordType.FORMULA, date.atTime(12, 0), amount = 60.0, amountKind = AmountKind.UNKNOWN)),
            entry(6, EntryDraft(RecordType.EXPRESSED_MILK, date.atTime(13, 0), amount = 50.0)),
            entry(7, EntryDraft(RecordType.SOLID_FOOD, date.atTime(18, 0), amount = 20.0)),
        )

        val summary = summarizeDay(records, date)
        val formula = summary.feedings.single { it.type == RecordType.FORMULA }
        assertEquals(4, formula.count)
        assertEquals(2, formula.knownConsumedCount)
        assertEquals(200.0, formula.consumed, 0.0)
        assertEquals(12L, summary.directBreastfeedingMinutes)
        assertEquals(50.0, summary.feedings.single { it.type == RecordType.EXPRESSED_MILK }.consumed, 0.0)
        assertEquals(date.atTime(18, 0), summary.lastFeedingAt)
    }

    @Test
    fun overnightSleepIsClippedAndOverlappingMinutesCountOnce() {
        val previousDate = date.minusDays(1)
        val records = listOf(
            sleep(1, previousDate.atTime(23, 40), date.atTime(0, 20)),
            sleep(2, date.atTime(0, 10), date.atTime(0, 30)),
            sleep(3, date.atTime(14, 0), null),
            sleep(4, previousDate.atTime(23, 30), date.atStartOfDay()),
        )

        assertEquals(30L, summarizeDay(records, previousDate).sleepMinutes)
        assertEquals(30L, summarizeDay(records, date).sleepMinutes)
        assertTrue(summarizeDay(records, date).sleepHasUnconfirmed)
        assertFalse(summarizeDay(records, previousDate).sleepHasUnconfirmed)
        assertEquals(listOf(3L, 2L, 1L), entriesForDay(records, date).map { it.id })
    }

    @Test
    fun diaperTracksBothPresenceValuesAndUnknownSeparately() {
        val records = listOf(
            entry(1, EntryDraft(RecordType.DIAPER, date.atTime(9, 0), urine = Presence.YES, stool = Presence.YES)),
            entry(2, EntryDraft(RecordType.DIAPER, date.atTime(10, 0), urine = Presence.NO, stool = Presence.UNKNOWN)),
            entry(3, EntryDraft(RecordType.DIAPER, date.atTime(11, 0), urine = Presence.NO, stool = Presence.NO)),
        )

        val summary = summarizeDay(records, date)
        assertEquals(3, summary.diaperCount)
        assertEquals(1, summary.wetDiapers)
        assertEquals(1, summary.dirtyDiapers)
        assertEquals(1, summary.diaperUnknownCount)
        assertEquals(date.atTime(9, 0), summary.lastStoolAt)
    }

    private fun entry(id: Long, draft: EntryDraft) = CareEntry(id, 1, draft, RecordSource.MANUAL)

    private fun sleep(id: Long, start: LocalDateTime, end: LocalDateTime?) =
        entry(id, EntryDraft(RecordType.SLEEP, start, end))
}
