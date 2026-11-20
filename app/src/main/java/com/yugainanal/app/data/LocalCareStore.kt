package com.yugainanal.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import com.yugainanal.app.reports.ReportDao
import com.yugainanal.app.reports.ReportRow
import com.yugainanal.app.reports.ReportScheduleRow
import com.yugainanal.app.reports.refreshDailyReports
import com.yugainanal.app.reports.reportMigration
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalCareStore(context: Context) {
    private val database = CareDatabase.get(context)
    private val dao = database.careDao()

    val children: Flow<List<Child>> = dao.observeChildren().map { rows -> rows.map(ChildRow::toChild) }
    val selectedChildId: Flow<Long?> = dao.observeSelection().map { it?.childId }

    fun entries(childId: Long): Flow<List<CareEntry>> =
        dao.observeEntries(childId).map { rows -> rows.map(EntryRow::toEntry) }

    suspend fun selectChild(childId: Long) = database.withTransaction {
        requireChild(childId)
        dao.setSelection(SelectionRow(childId = childId))
    }

    suspend fun addChild(name: String, birthDate: LocalDate): Long = database.withTransaction {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "아이 이름을 입력해 주세요." }
        require(!birthDate.isAfter(LocalDate.now(CareZone))) { "생년월일을 확인해 주세요." }
        val id = dao.insertChild(ChildRow(name = trimmedName, birthDate = birthDate.toString()))
        dao.setSelection(SelectionRow(childId = id))
        id
    }

    suspend fun saveEntry(childId: Long, entryId: Long?, draft: EntryDraft) {
        val stored = draft.toStored()
        database.withTransaction {
            val child = requireChild(childId)
            val now = LocalDateTime.now(CareZone)
            require(draft.startedAt.toLocalDate() >= LocalDate.parse(child.birthDate) &&
                draft.startedAt <= now && (draft.endedAt == null || draft.endedAt <= now)
            ) { "아이의 생년월일부터 현재까지의 시간을 입력해 주세요." }
            val changed = if (entryId == null) {
                dao.insertEntry(EntryRow(childId = childId, record = stored))
                listOf(stored)
            } else {
                val existing = dao.findEntry(childId, entryId)
                    ?: throw IllegalArgumentException("수정할 기록을 찾을 수 없어요.")
                dao.updateEntry(existing.copy(record = stored, caregiverEdited = true))
                listOf(existing.record, stored)
            }
            database.refreshDailyReports(child, changed)
        }
    }

    suspend fun deleteEntry(childId: Long, entryId: Long) {
        database.withTransaction {
            val existing = dao.findEntry(childId, entryId) ?: return@withTransaction
            val child = requireChild(childId)
            dao.clearImportSnapshots(childId, entryId)
            dao.deleteEntry(childId, entryId)
            database.refreshDailyReports(child, listOf(existing.record))
        }
    }

    suspend fun prepareImport(childId: Long, file: ImportFile): ImportPreview = database.withTransaction {
        requireChild(childId)
        if (dao.hasImportBatch(childId, file.sha256)) {
            return@withTransaction ImportPreview(true, emptyList(), file.records.size)
        }

        val versions = ImportedVersions(dao.listImportedRecords(childId))
        val entries = dao.listEntries(childId)
        val entriesById = entries.associateBy { it.id }
        val entriesByType = entries.groupBy { it.record.type }
            .mapValues { (_, rows) -> rows.sortedBy { it.record.startedAt } }
        val items = file.indexedRecords().mapNotNull { incoming ->
            if (versions.shouldSkip(incoming)) return@mapNotNull null
            val linkedEntry = versions.latest(incoming.identity)?.entryId?.let(entriesById::get)
            val candidates = if (linkedEntry == null) {
                findNearby(entriesByType[incoming.record.draft.type.name].orEmpty(), incoming.record.draft)
            } else {
                listOf(linkedEntry)
            }
            ImportItem(incoming.index, incoming.record.draft, candidates.map(EntryRow::toEntry), linkedEntry != null)
        }
        ImportPreview(false, items, file.records.size - items.size)
    }

    suspend fun applyImport(
        childId: Long,
        file: ImportFile,
        decisions: List<ImportDecision>,
    ): ImportResult = database.withTransaction {
        val child = requireChild(childId)
        if (dao.hasImportBatch(childId, file.sha256)) {
            return@withTransaction ImportResult(0, 0, file.records.size)
        }

        val decisionsByIndex = decisions.associateBy { it.index }
        require(decisionsByIndex.size == decisions.size && decisions.all { it.index in file.records.indices }) {
            "가져올 기록을 다시 선택해 주세요."
        }

        val versions = ImportedVersions(dao.listImportedRecords(childId))
        val changed = mutableListOf<StoredRecord>()
        var added = 0
        var linked = 0
        for (incoming in file.indexedRecords()) {
            val decision = decisionsByIndex[incoming.index] ?: continue
            if (versions.shouldSkip(incoming)) continue

            val previous = versions.latest(incoming.identity)
            require(previous?.entryId == null || previous.entryId == decision.existingEntryId
            ) { "이미 연결된 기록이 바뀌었어요. 파일을 다시 열어 주세요." }
            val existingEntryId = previous?.entryId ?: decision.existingEntryId
            val rawRecord = incoming.record.draft.toStored()
            val entryId = if (existingEntryId == null) {
                added++
                changed += rawRecord
                dao.insertEntry(EntryRow(childId = childId, record = rawRecord, source = RecordSource.BABYTIME.name))
            } else {
                val existing = dao.findEntry(childId, existingEntryId)
                    ?: throw IllegalArgumentException("연결할 기록이 바뀌었어요. 파일을 다시 열어 주세요.")
                require(existing.record.type == rawRecord.type) {
                    "기록 종류가 바뀌었어요. 파일을 다시 열어 주세요."
                }
                val updated = when {
                    previous?.entryId == existing.id && existing.source == RecordSource.BABYTIME.name &&
                        !existing.caregiverEdited -> rawRecord
                    previous == null -> existing.record.fillMissingFrom(rawRecord)
                    else -> existing.record
                }
                if (updated != existing.record) {
                    dao.updateEntry(existing.copy(record = updated))
                    changed += existing.record
                    changed += updated
                }
                linked++
                existing.id
            }

            val imported = ImportedRecordRow(
                childId = childId,
                identity = incoming.identity,
                signature = incoming.record.signature,
                entryId = entryId,
                rawRecord = rawRecord,
            )
            val importedId = dao.insertImportedRecord(imported)
            versions.add(imported.copy(id = importedId))
        }

        dao.insertImportBatch(ImportBatchRow(childId, file.sha256))
        database.refreshDailyReports(child, changed)
        ImportResult(added, linked, file.records.size - added - linked)
    }

    private suspend fun requireChild(childId: Long): ChildRow =
        requireNotNull(dao.findChild(childId)) { "아이를 먼저 선택해 주세요." }
}

private fun StoredRecord.fillMissingFrom(imported: StoredRecord): StoredRecord = copy(
    endedAt = endedAt ?: imported.endedAt?.takeIf { it >= startedAt },
    amount = amount ?: imported.amount,
    amountKind = if (amount == null) imported.amountKind else amountKind,
    urine = if (urine == Presence.UNKNOWN.name) imported.urine else urine,
    stool = if (stool == Presence.UNKNOWN.name) imported.stool else stool,
    note = listOf(note, imported.note).filter { it.isNotBlank() }.distinct().joinToString("\n"),
    stoolColor = stoolColor.ifBlank { imported.stoolColor },
    stoolConsistency = stoolConsistency.ifBlank { imported.stoolConsistency },
)

private fun EntryDraft.toStored(): StoredRecord {
    val enteredAmount = amount
    val end = endedAt
    require(enteredAmount == null || enteredAmount.isFinite() && enteredAmount >= 0) { "기록한 양을 확인해 주세요." }
    require(end == null || !end.isBefore(startedAt)) { "시작과 종료 시간을 확인해 주세요." }
    return StoredRecord(
        type = type.name,
        startedAt = startedAt.atZone(CareZone).toInstant().toEpochMilli(),
        endedAt = end?.atZone(CareZone)?.toInstant()?.toEpochMilli(),
        amount = enteredAmount,
        amountKind = amountKind.name,
        urine = urine.name,
        stool = stool.name,
        note = note,
        stoolColor = stoolColor,
        stoolConsistency = stoolConsistency,
    )
}

private fun StoredRecord.toDraft() = EntryDraft(
    type = RecordType.valueOf(type),
    startedAt = Instant.ofEpochMilli(startedAt).atZone(CareZone).toLocalDateTime(),
    endedAt = endedAt?.let { Instant.ofEpochMilli(it).atZone(CareZone).toLocalDateTime() },
    amount = amount,
    amountKind = AmountKind.valueOf(amountKind),
    urine = Presence.valueOf(urine),
    stool = Presence.valueOf(stool),
    note = note,
    stoolColor = stoolColor,
    stoolConsistency = stoolConsistency,
)

private fun ChildRow.toChild() = Child(id, name, LocalDate.parse(birthDate))

internal fun EntryRow.toEntry() = CareEntry(id, childId, record.toDraft(), RecordSource.valueOf(source))

private data class IndexedImport(val index: Int, val identity: String, val record: ImportRecord)

private fun ImportFile.indexedRecords(): List<IndexedImport> {
    val occurrenceBySignature = mutableMapOf<String, Int>()
    val seenVersions = mutableSetOf<Pair<String, String>>()
    return records.mapIndexedNotNull { index, record ->
        val externalId = record.externalId?.trim()?.takeIf(String::isNotEmpty)
        val identity = if (externalId != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(externalId.toByteArray(Charsets.UTF_8))
            "external:${digest.joinToString("") { "%02x".format(it) }}"
        } else {
            val occurrence = (occurrenceBySignature[record.signature] ?: 0) + 1
            occurrenceBySignature[record.signature] = occurrence
            "signature:$occurrence:${record.signature}"
        }
        if (seenVersions.add(identity to record.signature)) IndexedImport(index, identity, record) else null
    }
}

private class ImportedVersions(records: List<ImportedRecordRow>) {
    private val signatures = mutableSetOf<Pair<String, String>>()
    private val latestByIdentity = mutableMapOf<String, ImportedRecordRow>()

    init {
        records.forEach(::add)
    }

    fun latest(identity: String): ImportedRecordRow? = latestByIdentity[identity]

    fun shouldSkip(incoming: IndexedImport): Boolean {
        if ((incoming.identity to incoming.record.signature) in signatures) return true
        val latest = latest(incoming.identity)
        return latest != null && latest.entryId == null
    }

    fun add(record: ImportedRecordRow) {
        signatures += record.identity to record.signature
        latestByIdentity[record.identity] = record
    }
}

private const val DUPLICATE_WINDOW_MILLIS = 5 * 60 * 1000L
private const val MAX_DUPLICATE_CANDIDATES = 5

private fun findNearby(entries: List<EntryRow>, draft: EntryDraft): List<EntryRow> {
    val timestamp = draft.startedAt.atZone(CareZone).toInstant().toEpochMilli()
    val firstTimestamp = timestamp - DUPLICATE_WINDOW_MILLIS
    val position = entries.binarySearchBy(firstTimestamp) { it.record.startedAt }
    var first = if (position >= 0) position else -position - 1
    while (first > 0 && entries[first - 1].record.startedAt == firstTimestamp) first--
    return entries.subList(first, entries.size).asSequence()
        .takeWhile { it.record.startedAt <= timestamp + DUPLICATE_WINDOW_MILLIS }
        .sortedWith(compareBy<EntryRow> { abs(it.record.startedAt - timestamp) }.thenBy { it.id })
        .take(MAX_DUPLICATE_CANDIDATES)
        .toList()
}

@Entity(tableName = "children")
internal data class ChildRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val birthDate: String,
)

@Entity(
    tableName = "selected_child",
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["childId"])],
)
internal data class SelectionRow(
    @PrimaryKey val slot: Int = 0,
    val childId: Long,
)

internal data class StoredRecord(
    val type: String,
    val startedAt: Long,
    val endedAt: Long?,
    val amount: Double?,
    val amountKind: String,
    val urine: String,
    val stool: String,
    val note: String,
    val stoolColor: String,
    val stoolConsistency: String,
)

@Entity(
    tableName = "care_entries",
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["childId", "startedAt"])],
)
internal data class EntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    @Embedded val record: StoredRecord,
    val source: String = RecordSource.MANUAL.name,
    val caregiverEdited: Boolean = false,
)

@Entity(
    tableName = "babytime_batches",
    primaryKeys = ["childId", "sha256"],
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
    ],
)
internal data class ImportBatchRow(val childId: Long, val sha256: String)

@Entity(
    tableName = "babytime_records",
    foreignKeys = [
        ForeignKey(entity = ChildRow::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EntryRow::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["childId", "identity", "signature"], unique = true), Index(value = ["entryId"])],
)
internal data class ImportedRecordRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val identity: String,
    val signature: String,
    val entryId: Long?,
    @Embedded(prefix = "raw_") val rawRecord: StoredRecord?,
)

@Dao
internal interface CareDao {
    @Query("SELECT * FROM children ORDER BY id")
    fun observeChildren(): Flow<List<ChildRow>>

    @Query("SELECT * FROM children WHERE id = :id")
    suspend fun findChild(id: Long): ChildRow?

    @Insert
    suspend fun insertChild(child: ChildRow): Long

    @Query("SELECT * FROM selected_child WHERE slot = 0")
    fun observeSelection(): Flow<SelectionRow?>

    @Upsert
    suspend fun setSelection(selection: SelectionRow)

    @Query("SELECT * FROM care_entries WHERE childId = :childId ORDER BY startedAt DESC, id DESC")
    fun observeEntries(childId: Long): Flow<List<EntryRow>>

    @Query("SELECT * FROM care_entries WHERE childId = :childId")
    suspend fun listEntries(childId: Long): List<EntryRow>

    @Query(
        """
        SELECT * FROM care_entries
        WHERE childId = :childId AND startedAt < :cutoff
            AND (startedAt >= :dayStart OR (type = 'SLEEP' AND endedAt > :dayStart))
        ORDER BY startedAt ASC, id ASC
        """,
    )
    suspend fun reportEntries(childId: Long, dayStart: Long, cutoff: Long): List<EntryRow>

    @Query("SELECT * FROM care_entries WHERE childId = :childId AND id = :entryId")
    suspend fun findEntry(childId: Long, entryId: Long): EntryRow?

    @Insert
    suspend fun insertEntry(entry: EntryRow): Long

    @Update
    suspend fun updateEntry(entry: EntryRow)

    @Query("DELETE FROM care_entries WHERE childId = :childId AND id = :entryId")
    suspend fun deleteEntry(childId: Long, entryId: Long)

    @Query(
        """
        UPDATE babytime_records SET
            raw_type = NULL, raw_startedAt = NULL, raw_endedAt = NULL,
            raw_amount = NULL, raw_amountKind = NULL, raw_urine = NULL, raw_stool = NULL,
            raw_note = NULL, raw_stoolColor = NULL, raw_stoolConsistency = NULL
        WHERE childId = :childId AND entryId = :entryId
        """,
    )
    suspend fun clearImportSnapshots(childId: Long, entryId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM babytime_batches WHERE childId = :childId AND sha256 = :sha256)")
    suspend fun hasImportBatch(childId: Long, sha256: String): Boolean

    @Insert
    suspend fun insertImportBatch(batch: ImportBatchRow)

    @Query("SELECT * FROM babytime_records WHERE childId = :childId ORDER BY id")
    suspend fun listImportedRecords(childId: Long): List<ImportedRecordRow>

    @Insert
    suspend fun insertImportedRecord(record: ImportedRecordRow): Long
}

@Database(
    entities = [
        ChildRow::class, SelectionRow::class, EntryRow::class, ImportBatchRow::class, ImportedRecordRow::class,
        ReportRow::class, ReportScheduleRow::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class CareDatabase : RoomDatabase() {
    abstract fun careDao(): CareDao
    abstract fun reportDao(): ReportDao

    companion object {
        @Volatile
        private var instance: CareDatabase? = null

        fun get(context: Context): CareDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CareDatabase::class.java, "care.db")
                .addMigrations(reportMigration)
                .build()
                .also { instance = it }
        }
    }
}
