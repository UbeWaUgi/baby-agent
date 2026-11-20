package com.yugainanal.app.reports

import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordType
import com.yugainanal.app.data.entriesForDay
import com.yugainanal.app.data.summarizeDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

const val REPORT_NOTE_MAX_LENGTH = 500

enum class ReportKind { DAILY, INSTANT }

data class ReportSection(val title: String, val lines: List<String>)

data class DailyReport(
    val id: Long,
    val childId: Long,
    val childName: String,
    val date: LocalDate,
    val cutoff: LocalDateTime,
    val createdAt: LocalDateTime,
    val kind: ReportKind,
    val note: String,
    val sections: List<ReportSection>,
)

data class ReportSchedule(
    val enabled: Boolean = false,
    val time: LocalTime = LocalTime.of(20, 0),
)

internal fun previewDayReport(
    child: Child,
    date: LocalDate,
    entries: List<CareEntry>,
    now: LocalDateTime,
): DailyReport {
    val cutoff = minOf(now, date.plusDays(1).atStartOfDay()).coerceAtLeast(date.atStartOfDay())
    return DailyReport(
        id = 0L,
        childId = child.id,
        childName = child.name,
        date = date,
        cutoff = cutoff,
        createdAt = now,
        kind = ReportKind.DAILY,
        note = "",
        sections = reportSections(entries.filter { it.childId == child.id }, date, cutoff),
    )
}

fun reportSections(entries: List<CareEntry>, date: LocalDate, cutoff: LocalDateTime): List<ReportSection> {
    val summary = summarizeDay(entries, date, cutoff)
    val inScope = entriesForDay(entries, date)
        .map { it.draft }
        .filter { it.startedAt < cutoff }
    val diapers = inScope.filter { it.type == RecordType.DIAPER }
    val unknownWet = diapers.count { it.urine == Presence.UNKNOWN }
    val unknownDirty = diapers.count { it.stool == Presence.UNKNOWN }
    val unfinishedSleep = inScope.count {
        it.type == RecordType.SLEEP && (it.endedAt == null || it.endedAt <= it.startedAt)
    }

    val feedingLines = summary.feedings.map { feeding ->
        val label = "${feeding.type.label} ${feeding.count}회"
        if (feeding.type == RecordType.DIRECT_BREASTFEEDING) {
            val minutes = summary.directBreastfeedingMinutes
            if (minutes > 0) "$label · 기록된 수유 시간 ${durationLabel(minutes)}" else label
        } else if (feeding.knownConsumedCount > 0) {
            val amount = if (feeding.consumed.isFinite()) {
                feeding.consumed.toBigDecimal().stripTrailingZeros().toPlainString() + feeding.type.amountUnit.orEmpty()
            } else {
                "합계를 확인해 주세요"
            }
            "$label · 먹은 양을 기록한 ${feeding.knownConsumedCount}회 합계 $amount"
        } else {
            "$label · 먹은 양 미확인"
        }
    }.ifEmpty { listOf("기록된 수유·식사가 없어요.") }

    val diaperLines = if (diapers.isEmpty()) {
        listOf("기록된 기저귀 교체가 없어요.")
    } else {
        buildList {
            add("교체 ${summary.diaperCount}회")
            if (unknownWet < diapers.size) add("소변 있음 ${summary.wetDiapers}회 기록")
            if (unknownDirty < diapers.size) add("대변 있음 ${summary.dirtyDiapers}회 기록")
        }
    }

    val sleepLines = buildList {
        if (summary.completedSleepCount > 0) {
            add("확인된 수면 합계 ${durationLabel(summary.sleepMinutes)}")
        } else {
            add("시작·종료 시각이 확인된 수면 기록이 없어요.")
        }
    }

    val checks = buildList {
        summary.feedings.filter { it.type.amountUnit != null }.forEach { feeding ->
            val missing = feeding.count - feeding.knownConsumedCount
            if (missing > 0) add("${feeding.type.label} ${missing}회는 먹은 양을 확인해 주세요.")
        }
        if (unknownWet > 0) add("기저귀 ${unknownWet}건의 소변 여부가 비어 있어요.")
        if (unknownDirty > 0) add("기저귀 ${unknownDirty}건의 대변 여부가 비어 있어요.")
        if (unfinishedSleep > 0) add("수면 ${unfinishedSleep}건의 깬 시각이 비어 있어요.")
        if (inScope.isEmpty()) add("이 시간 범위에 기록이 들어오면 하루 요약을 만들 수 있어요.")
    }.ifEmpty { listOf("현재 입력한 기록에서 추가로 확인할 값이 없어요.") }

    return listOf(
        ReportSection("수유·식사", feedingLines),
        ReportSection("기저귀", diaperLines),
        ReportSection("수면", sleepLines),
        ReportSection("확인할 기록", checks),
    )
}

private fun durationLabel(minutes: Long): String =
    when {
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0L -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
