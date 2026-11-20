package com.yugainanal.app.reports

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yugainanal.app.data.CareDatabase
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.ChildRow
import com.yugainanal.app.data.RecordType
import com.yugainanal.app.data.StoredRecord
import com.yugainanal.app.data.toEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class ReportStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = CareDatabase.get(appContext)
    private val dao = database.reportDao()

    fun reports(childId: Long): Flow<List<DailyReport>> =
        dao.observeReports(childId).map { rows -> rows.map(ReportRow::toReport) }

    fun schedule(childId: Long): Flow<ReportSchedule> =
        dao.observeSchedule(childId).map { it?.toSchedule() ?: ReportSchedule() }

    suspend fun findReport(id: Long): DailyReport? = dao.findReport(id)?.toReport()

    suspend fun createInstant(childId: Long, note: String): DailyReport {
        val cleanedNote = note.trim()
        require(cleanedNote.length <= REPORT_NOTE_MAX_LENGTH) { "메모는 ${REPORT_NOTE_MAX_LENGTH}자까지 입력해 주세요." }
        return database.withTransaction {
            val child = requireNotNull(database.careDao().findChild(childId)) { "아이를 먼저 선택해 주세요." }
            val now = LocalDateTime.now(CareZone).truncatedTo(ChronoUnit.MILLIS)
            val row = database.buildReport(child, now.toLocalDate(), now, now, ReportKind.INSTANT, cleanedNote)
            row.copy(id = dao.insertReport(row)).toReport()
        }
    }

    suspend fun updateSchedule(childId: Long, schedule: ReportSchedule) {
        scheduleLock.withLock {
            val time = schedule.time.truncatedTo(ChronoUnit.MINUTES)
            val (previous, updated) = database.withTransaction {
                requireNotNull(database.careDao().findChild(childId)) { "아이를 먼저 선택해 주세요." }
                val old = dao.findSchedule(childId)
                if (old != null && old.enabled == schedule.enabled && old.localTime == time.toString()) {
                    return@withTransaction old to old
                }
                val nextDate = if (schedule.enabled) firstScheduledDate(LocalDateTime.now(CareZone), time) else null
                val row = ReportScheduleRow(
                    childId = childId,
                    enabled = schedule.enabled,
                    localTime = time.toString(),
                    revision = (old?.revision ?: 0L) + 1,
                    nextDate = nextDate?.toString(),
                )
                dao.setSchedule(row)
                old to row
            }
            if (previous != null && previous.revision != updated.revision) {
                DailyReportScheduler.cancel(appContext, previous)
            }
            DailyReportScheduler.enqueue(appContext, updated)
        }
    }

    suspend fun reconcileSchedule(childId: Long) {
        scheduleLock.withLock {
            dao.findSchedule(childId)?.let { DailyReportScheduler.enqueue(appContext, it) }
        }
    }

    internal suspend fun runScheduledReport(childId: Long, revision: Long, date: LocalDate): Boolean =
        scheduleLock.withLock {
            val result = database.withTransaction {
                val schedule = dao.findSchedule(childId)
                if (schedule == null || !schedule.enabled || schedule.revision != revision) {
                    return@withTransaction ScheduledResult()
                }
                if (schedule.nextDate != date.toString()) {
                    return@withTransaction ScheduledResult(next = schedule)
                }
                val now = LocalDateTime.now(CareZone).truncatedTo(ChronoUnit.MILLIS)
                if (now < date.atTime(LocalTime.parse(schedule.localTime))) {
                    return@withTransaction ScheduledResult(waitForTime = true)
                }
                val child = database.careDao().findChild(childId) ?: return@withTransaction ScheduledResult()
                val cutoff = minOf(now, date.plusDays(1).atStartOfDay())
                val row = database.buildReport(child, date, cutoff, now, ReportKind.DAILY)
                val inserted = dao.insertReport(row)
                val next = schedule.copy(nextDate = maxOf(date.plusDays(1), now.toLocalDate()).toString())
                dao.setSchedule(next)
                ScheduledResult(next, row.copy(id = inserted).takeIf { inserted != -1L }?.toReport())
            }
            if (result.waitForTime) return@withLock false
            result.created?.let { DailyReportScheduler.notify(appContext, it) }
            result.next?.let { DailyReportScheduler.enqueue(appContext, it) }
            true
        }

    private data class ScheduledResult(
        val next: ReportScheduleRow? = null,
        val created: DailyReport? = null,
        val waitForTime: Boolean = false,
    )

    private companion object {
        val scheduleLock = Mutex()
    }
}

internal fun firstScheduledDate(now: LocalDateTime, time: LocalTime): LocalDate =
    if (now.toLocalTime().truncatedTo(ChronoUnit.MINUTES) <= time) now.toLocalDate()
    else now.toLocalDate().plusDays(1)

internal suspend fun CareDatabase.refreshDailyReports(child: ChildRow, changed: List<StoredRecord>) {
    if (changed.isEmpty()) return
    val affectedDates = changed.map { record ->
        val end = record.endedAt
        val lastInstant = if (record.type == RecordType.SLEEP.name && end != null && end > record.startedAt) {
            end - 1
        } else {
            record.startedAt
        }
        fromReportMillis(record.startedAt).toLocalDate()..fromReportMillis(lastInstant).toLocalDate()
    }
    val dao = reportDao()
    val firstDate = affectedDates.minOf { it.start }
    val lastDate = affectedDates.maxOf { it.endInclusive }
    val reports = dao.dailyReportsInRange(child.id, firstDate.toString(), lastDate.toString())
    val now = LocalDateTime.now(CareZone).truncatedTo(ChronoUnit.MILLIS)
    for (existing in reports) {
        val date = LocalDate.parse(existing.date)
        if (affectedDates.none { date in it }) continue
        val createdAt = fromReportMillis(existing.createdAt)
        val cutoff = now.coerceIn(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
        val refreshed = buildReport(child, date, cutoff, createdAt, ReportKind.DAILY)
        dao.updateReport(refreshed.copy(id = existing.id))
    }
}

private suspend fun CareDatabase.buildReport(
    child: ChildRow,
    date: LocalDate,
    cutoff: LocalDateTime,
    createdAt: LocalDateTime,
    kind: ReportKind,
    note: String = "",
): ReportRow {
    val entries = careDao().reportEntries(
        child.id,
        date.atStartOfDay().toReportMillis(),
        cutoff.toReportMillis(),
    ).map { it.toEntry() }
    val sections = JSONArray()
    for (section in reportSections(entries, date, cutoff)) {
        sections.put(JSONObject().put("title", section.title).put("lines", JSONArray(section.lines)))
    }
    return ReportRow(
        childId = child.id,
        childName = child.name,
        date = date.toString(),
        dailyDate = if (kind == ReportKind.DAILY) date.toString() else null,
        kind = kind.name,
        cutoff = cutoff.toReportMillis(),
        createdAt = createdAt.toReportMillis(),
        note = note,
        sections = sections.toString(),
    )
}

private fun ReportRow.toReport(): DailyReport {
    val array = JSONArray(sections)
    return DailyReport(
        id = id,
        childId = childId,
        childName = childName,
        date = LocalDate.parse(date),
        cutoff = fromReportMillis(cutoff),
        createdAt = fromReportMillis(createdAt),
        kind = ReportKind.valueOf(kind),
        note = note,
        sections = List(array.length()) { index ->
            val section = array.getJSONObject(index)
            val lines = section.getJSONArray("lines")
            ReportSection(section.getString("title"), List(lines.length()) { lines.getString(it) })
        },
    )
}

private fun ReportScheduleRow.toSchedule() = ReportSchedule(enabled, LocalTime.parse(localTime))
private fun LocalDateTime.toReportMillis() = atZone(CareZone).toInstant().toEpochMilli()
private fun fromReportMillis(value: Long) = Instant.ofEpochMilli(value).atZone(CareZone).toLocalDateTime()

@Entity(
    tableName = "care_reports",
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["childId", "dailyDate"], unique = true)],
)
internal data class ReportRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val childName: String,
    val date: String,
    val dailyDate: String?,
    val kind: String,
    val cutoff: Long,
    val createdAt: Long,
    val note: String,
    val sections: String,
)

@Entity(
    tableName = "report_schedules",
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class ReportScheduleRow(
    @PrimaryKey val childId: Long,
    val enabled: Boolean,
    val localTime: String,
    val revision: Long,
    val nextDate: String?,
)

@Dao
internal interface ReportDao {
    @Query("SELECT * FROM care_reports WHERE childId = :childId ORDER BY createdAt DESC, id DESC")
    fun observeReports(childId: Long): Flow<List<ReportRow>>

    @Query("SELECT * FROM care_reports WHERE id = :id")
    suspend fun findReport(id: Long): ReportRow?

    @Query("SELECT * FROM care_reports WHERE childId = :childId AND dailyDate BETWEEN :first AND :last")
    suspend fun dailyReportsInRange(childId: Long, first: String, last: String): List<ReportRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(row: ReportRow): Long

    @Update
    suspend fun updateReport(row: ReportRow)

    @Query("SELECT * FROM report_schedules WHERE childId = :childId")
    fun observeSchedule(childId: Long): Flow<ReportScheduleRow?>

    @Query("SELECT * FROM report_schedules WHERE childId = :childId")
    suspend fun findSchedule(childId: Long): ReportScheduleRow?

    @Upsert
    suspend fun setSchedule(row: ReportScheduleRow)
}

internal val reportMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE care_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                childId INTEGER NOT NULL,
                childName TEXT NOT NULL,
                date TEXT NOT NULL,
                dailyDate TEXT,
                kind TEXT NOT NULL,
                cutoff INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                note TEXT NOT NULL,
                sections TEXT NOT NULL,
                FOREIGN KEY(childId) REFERENCES children(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_care_reports_childId_dailyDate ON care_reports (childId, dailyDate)")
        db.execSQL(
            """
            CREATE TABLE report_schedules (
                childId INTEGER NOT NULL PRIMARY KEY,
                enabled INTEGER NOT NULL,
                localTime TEXT NOT NULL,
                revision INTEGER NOT NULL,
                nextDate TEXT,
                FOREIGN KEY(childId) REFERENCES children(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
