package com.yugainanal.app.reports

import com.yugainanal.app.data.AmountKind
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordSource
import com.yugainanal.app.data.RecordType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportModelsTest {
    private val date = LocalDate.of(2026, 11, 20)

    @Test
    fun reportUsesOnlyScopedAmountsAndListsMissingFields() {
        val sections = reportSections(
            listOf(
                entry(1, EntryDraft(RecordType.FORMULA, date.atTime(8, 0), amount = 120.0)),
                entry(2, EntryDraft(RecordType.FORMULA, date.atTime(9, 0), amount = 150.0, amountKind = AmountKind.OFFERED)),
                entry(3, EntryDraft(RecordType.FORMULA, date.atTime(15, 0), amount = 100.0)),
                entry(4, EntryDraft(RecordType.DIAPER, date.atTime(10, 0), urine = Presence.YES)),
                entry(5, EntryDraft(RecordType.SLEEP, date.atTime(11, 0))),
            ),
            date,
            date.atTime(14, 0),
        ).associate { it.title to it.lines }

        assertEquals(listOf("분유 2회 · 먹은 양을 기록한 1회 합계 120mL"), sections["수유·식사"])
        assertEquals(listOf("교체 1회", "소변 있음 1회 기록"), sections["기저귀"])
        assertEquals(
            listOf("분유 1회는 먹은 양을 확인해 주세요.", "기저귀 1건의 대변 여부가 비어 있어요.", "수면 1건의 깬 시각이 비어 있어요."),
            sections["확인할 기록"],
        )
    }

    @Test
    fun reportClipsSleepAtBothWindowBoundaries() {
        val sections = reportSections(
            listOf(
                entry(1, EntryDraft(RecordType.SLEEP, date.minusDays(1).atTime(23, 30), date.atTime(0, 20))),
                entry(2, EntryDraft(RecordType.SLEEP, date.atTime(13, 50), date.atTime(14, 20))),
                entry(3, EntryDraft(RecordType.SLEEP, date.atTime(13, 55), date.atTime(14, 15))),
            ),
            date,
            date.atTime(14, 0),
        )

        assertEquals(listOf("확인된 수면 합계 30분"), sections.single { it.title == "수면" }.lines)
    }

    @Test
    fun blankDayDisplaysMissingRecordsInsteadOfZeroCounts() {
        val content = reportSections(emptyList(), date, date.atTime(9, 0)).flatMap { it.lines }
        assertTrue(content.any { it.contains("기록된 기저귀 교체가 없어요") })
        assertFalse(content.any { it.contains("0회") || it.contains("0mL") })
    }

    private fun entry(id: Long, draft: EntryDraft) = CareEntry(id, 1, draft, RecordSource.MANUAL)
}
