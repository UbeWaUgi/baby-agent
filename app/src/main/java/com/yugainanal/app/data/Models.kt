package com.yugainanal.app.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val CareZone: ZoneId = ZoneId.of("Asia/Seoul")

data class Child(
    val id: Long,
    val name: String,
    val birthDate: LocalDate,
)

enum class RecordType(val label: String, val amountUnit: String? = null) {
    DIRECT_BREASTFEEDING("직접 모유수유"),
    EXPRESSED_MILK("유축유", "mL"),
    FORMULA("분유", "mL"),
    SOLID_FOOD("이유식·식사", "g"),
    DIAPER("기저귀"),
    SLEEP("수면"),
}

enum class AmountKind { CONSUMED, OFFERED, UNKNOWN }
enum class Presence { YES, NO, UNKNOWN }
enum class RecordSource { MANUAL, BABYTIME }

data class EntryDraft(
    val type: RecordType,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
    val amount: Double? = null,
    val amountKind: AmountKind = AmountKind.CONSUMED,
    val urine: Presence = Presence.UNKNOWN,
    val stool: Presence = Presence.UNKNOWN,
    val note: String = "",
    val stoolColor: String = "",
    val stoolConsistency: String = "",
)

data class CareEntry(
    val id: Long,
    val childId: Long,
    val draft: EntryDraft,
    val source: RecordSource,
)

data class ImportFile(
    val sha256: String,
    val records: List<ImportRecord>,
    val ignoredRows: Int = 0,
    val warnings: List<String> = emptyList(),
)

data class ImportRecord(
    val draft: EntryDraft,
    val signature: String,
    val externalId: String? = null,
)

data class ImportPreview(
    val alreadyImported: Boolean,
    val items: List<ImportItem>,
    val duplicateCount: Int,
)

data class ImportItem(
    val index: Int,
    val draft: EntryDraft,
    val possibleDuplicates: List<CareEntry>,
    val alreadyLinked: Boolean = false,
)

data class ImportDecision(val index: Int, val existingEntryId: Long? = null)
data class ImportResult(val added: Int, val linked: Int, val skipped: Int)

data class FeedingTotal(
    val type: RecordType,
    val count: Int,
    val knownConsumedCount: Int,
    val consumed: Double,
)

data class DaySummary(
    val feedings: List<FeedingTotal>,
    val diaperCount: Int,
    val wetDiapers: Int,
    val dirtyDiapers: Int,
    val diaperUnknownCount: Int,
    val sleepMinutes: Long,
    val completedSleepCount: Int,
    val sleepHasUnconfirmed: Boolean,
    val directBreastfeedingMinutes: Long,
    val lastFeedingAt: LocalDateTime?,
    val lastStoolAt: LocalDateTime?,
)

fun entriesForDay(entries: List<CareEntry>, date: LocalDate): List<CareEntry> {
    val dayStart = date.atStartOfDay()
    val nextDay = date.plusDays(1).atStartOfDay()
    return entries.filter { entry ->
        val draft = entry.draft
        if (draft.type == RecordType.SLEEP && draft.endedAt != null && draft.endedAt > draft.startedAt) {
            draft.startedAt < nextDay && draft.endedAt > dayStart
        } else {
            draft.startedAt.toLocalDate() == date
        }
    }.sortedByDescending { it.draft.startedAt }
}

fun summarizeDay(
    entries: List<CareEntry>,
    date: LocalDate,
    cutoff: LocalDateTime = date.plusDays(1).atStartOfDay(),
): DaySummary {
    val dayStart = date.atStartOfDay()
    val endOfWindow = cutoff.coerceIn(dayStart, date.plusDays(1).atStartOfDay())
    val drafts = entriesForDay(entries, date).map { it.draft }.filter { it.startedAt < endOfWindow }

    val feedings = drafts.filter { it.type != RecordType.DIAPER && it.type != RecordType.SLEEP }
    val diapers = drafts.filter { it.type == RecordType.DIAPER }
    val sleeps = drafts.filter { it.type == RecordType.SLEEP }
    val sleepIntervals = sleeps.mapNotNull { draft ->
        val end = draft.endedAt ?: return@mapNotNull null
        val startInDay = maxOf(draft.startedAt, dayStart)
        val endInDay = minOf(end, endOfWindow)
        if (endInDay > startInDay) startInDay to endInDay else null
    }.sortedBy { it.first }

    var totalSleepSeconds = 0L
    var coveredUntil = dayStart
    for ((start, end) in sleepIntervals) {
        val newStart = maxOf(start, coveredUntil)
        if (end > newStart) totalSleepSeconds += Duration.between(newStart, end).seconds
        coveredUntil = maxOf(coveredUntil, end)
    }

    return DaySummary(
        feedings = feedings.groupBy { it.type }.map { (type, items) ->
            val consumed = items.mapNotNull { draft ->
                draft.amount?.takeIf { type.amountUnit != null && draft.amountKind == AmountKind.CONSUMED }
            }
            FeedingTotal(type, items.size, consumed.size, consumed.sumOf { it.toBigDecimal() }.toDouble())
        }.sortedBy { it.type.ordinal },
        diaperCount = diapers.size,
        wetDiapers = diapers.count { it.urine == Presence.YES },
        dirtyDiapers = diapers.count { it.stool == Presence.YES },
        diaperUnknownCount = diapers.count { it.urine == Presence.UNKNOWN || it.stool == Presence.UNKNOWN },
        sleepMinutes = totalSleepSeconds / 60,
        completedSleepCount = sleepIntervals.size,
        sleepHasUnconfirmed = sleeps.any { it.endedAt == null || it.endedAt <= it.startedAt },
        directBreastfeedingMinutes = feedings.filter { it.type == RecordType.DIRECT_BREASTFEEDING }
            .sumOf { draft ->
                val end = draft.endedAt
                if (end != null && end > draft.startedAt) Duration.between(draft.startedAt, end).toMinutes()
                else 0L
            },
        lastFeedingAt = feedings.maxOfOrNull { it.startedAt },
        lastStoolAt = diapers.filter { it.stool == Presence.YES }.maxOfOrNull { it.startedAt },
    )
}
