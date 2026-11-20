package com.yugainanal.app.importing

import com.yugainanal.app.data.AmountKind
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BabyTimeParserTest {
    @Test
    fun readsDateRangesMidnightAndMultilineMemo() {
        val result = parse(
            """
            2026-11-05 11:42 PM ~ 2026-11-06 12:18 AM
            Type: Night Sleep
            Duration: 35 (min)
            ====================
            2026-11-06 12:08 PM
            Type: Breastfeeding (right)
            Duration: 11 (min)
            Memo: 수유 뒤 관찰
            보호자 메모: 편안해 보였음
            """,
        )

        val (sleep, breastfeeding) = result.records.map { it.draft }
        assertEquals(RecordType.SLEEP, sleep.type)
        assertEquals(LocalDateTime.of(2026, 11, 5, 23, 42), sleep.startedAt)
        assertEquals(LocalDateTime.of(2026, 11, 6, 0, 18), sleep.endedAt)
        assertEquals(RecordType.DIRECT_BREASTFEEDING, breastfeeding.type)
        assertEquals(LocalDateTime.of(2026, 11, 6, 12, 19), breastfeeding.endedAt)
        assertEquals("수유 뒤 관찰\n보호자 메모: 편안해 보였음", breastfeeding.note)
    }

    @Test
    fun genericAmountDoesNotBecomeActualIntakeAndValueUnitBeatsHeader() {
        val result = parse(
            """
            2026-11-05 07:22 AM
            Type: Formula
            Formula Total Amount(ml): 130 (ml)
            ====================
            2026-11-05 09:18 AM
            Type: Pumped Milk
            Pumped Milk Total Amount(ml): 3.75 (fl_oz)
            ====================
            2026-11-05 01:05 PM
            Type: Baby Food
            Banana, oats: 72 (g)
            ====================
            2026-11-05 02:01 PM
            Type: Pumping
            Pumping Total Amount(ml): 90 (ml)
            """,
        )

        val (formula, expressed, solids) = result.records.map { it.draft }
        assertEquals(130.0, formula.amount)
        assertEquals(AmountKind.UNKNOWN, formula.amountKind)
        assertEquals(RecordType.EXPRESSED_MILK, expressed.type)
        assertNull(expressed.amount)
        assertTrue(expressed.note.contains("3.75 (fl_oz)"))
        assertEquals(RecordType.SOLID_FOOD, solids.type)
        assertEquals(72.0, solids.amount)
        assertTrue(solids.note.contains("Banana, oats"))
        assertEquals(1, result.ignoredRows)
    }

    @Test
    fun readsKoreanWindows949AndRecognizesExplicitIntakeAndOfferedAmount() {
        val text =
            """
            2026년 11월 5일 오후 3:05
            종류: 분유 수유
            먹은 양(ml): 112
            제공량: 120mL
            ====================
            2026.11.05 오전 11:06
            종류: 유축유
            제공량: 105mL
            """.trimIndent()
        val result = BabyTimeParser.parse(text.toByteArray(Charset.forName("x-windows-949")))

        val (consumed, offered) = result.records.map { it.draft }
        assertEquals(LocalDateTime.of(2026, 11, 5, 15, 5), consumed.startedAt)
        assertEquals(AmountKind.CONSUMED, consumed.amountKind)
        assertEquals(112.0, consumed.amount)
        assertTrue(consumed.note.contains("120mL"))
        assertEquals(AmountKind.OFFERED, offered.amountKind)
        assertEquals(105.0, offered.amount)
    }

    @Test
    fun readsDiaperDetailsWithoutInferringAbsence() {
        val result = parse(
            """
            2026-11-05 07:44 AM
            Type: Pee
            Diaper type: Poop
            Stool color: yellow
            Stool consistency: soft
            ====================
            2026-11-05 20:41
            종류: 기저귀
            기저귀 유형: 소변과 대변
            """,
        )

        val (stoolOnly, both) = result.records.map { it.draft }
        assertEquals(RecordType.DIAPER, stoolOnly.type)
        assertEquals(Presence.UNKNOWN, stoolOnly.urine)
        assertEquals(Presence.YES, stoolOnly.stool)
        assertEquals("yellow", stoolOnly.stoolColor)
        assertEquals("soft", stoolOnly.stoolConsistency)
        assertEquals(Presence.YES, both.urine)
        assertEquals(Presence.YES, both.stool)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun zipAndTxtHaveSameActivitySignatureButDifferentFileHashes() {
        val activity =
            """
            2026-11-06 17:08
            Type: Day Sleep
            ====================
            """.trimIndent()
        val txt = BabyTimeParser.parse(activity.toByteArray())
        val zip = BabyTimeParser.parse(zipOf("exports/activity_Example_202611.txt" to activity.replace("\n", "\r\n")))

        assertEquals(txt.records.single().signature, zip.records.single().signature)
        assertEquals(64, zip.records.single().signature.length)
        assertNotEquals(txt.sha256, zip.sha256)
        assertNull(zip.records.single().draft.endedAt)
        assertNull(zip.records.single().externalId)
    }

    @Test
    fun malformedBlockIsSkippedWhileValidActivityIsKept() {
        val result = parse(
            """
            2026-02-29 12:45 AM
            Type: Night Sleep
            ====================
            2026-02-28 12:45 AM ~ 2026-02-27 12:55 AM
            Type: Night Sleep
            """,
        )
        assertEquals(1, result.ignoredRows)
        assertEquals(2, result.warnings.size)
        assertNull(result.records.single().draft.endedAt)
    }

    @Test
    fun rejectsZipWithTwoChildrenAndBoundsArchiveExpansion() {
        val activity = "2026-11-06 17:08\nType: Day Sleep"
        val mixed = zipOf("activity_One_202611.txt" to activity, "activity_Two_202611.txt" to activity)
        assertThrows(IllegalArgumentException::class.java) { BabyTimeParser.parse(mixed) }

        val large = zipOf("unrecognized-file.txt" to "a".repeat(12 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { BabyTimeParser.parse(large) }
    }

    private fun parse(source: String) = BabyTimeParser.parse(source.trimIndent().toByteArray())

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
