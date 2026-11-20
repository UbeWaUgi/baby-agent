package com.yugainanal.app.media

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.yugainanal.app.data.CareZone
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class GalleryMediaKind { PHOTO, VIDEO }

data class GalleryMedia(
    val childId: Long,
    val date: LocalDate,
    val uri: Uri,
    val kind: GalleryMediaKind,
    val displayName: String,
    val reviewed: Boolean = false,
    val dateEstimated: Boolean = false,
)

enum class GalleryAccess {
    NONE, SELECTED, FULL;

    companion object {
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun current(context: Context): GalleryAccess {
            val photos = mediaAccess(context, GalleryMediaKind.PHOTO)
            val videos = mediaAccess(context, GalleryMediaKind.VIDEO)
            return when {
                photos == FULL && videos == FULL -> FULL
                photos == NONE && videos == NONE -> NONE
                else -> SELECTED
            }
        }

        internal fun mediaAccess(context: Context, kind: GalleryMediaKind): GalleryAccess {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (kind == GalleryMediaKind.PHOTO) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (context.hasPermission(permission)) return FULL
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) SELECTED else NONE
        }

        private fun Context.hasPermission(permission: String): Boolean =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

enum class GalleryScanStatus { IDLE, PENDING, SCANNING, COMPLETE, NO_PERMISSION, NEEDS_REFERENCE, ERROR }

data class GalleryScanSummary(
    val automaticEnabled: Boolean = false,
    val processed: Int = 0,
    val matched: Int = 0,
    val needsReview: Int = 0,
    val skipped: Int = 0,
    val unreadable: Int = 0,
    val status: GalleryScanStatus = GalleryScanStatus.IDLE,
    val lastScannedAt: Instant? = null,
    val lastRunAnalyzed: Int = 0,
)

internal enum class IndexedDecision { MATCH, REVIEW, NO_MATCH, ACCEPTED, REJECTED, UNREADABLE }

internal data class CatalogMedia(val media: GalleryMedia, val fingerprint: String)

internal data class GalleryBatch(val items: List<CatalogMedia>, val more: Boolean)

class GalleryIndex(context: Context) {
    private val appContext = context.applicationContext
    private val db = database(appContext)
    private val profileStore = ChildFaceProfileStore(appContext)
    private val catalog = GalleryCatalog(appContext)

    fun matches(childId: Long): Flow<List<GalleryMedia>> =
        observedMedia(childId, "MATCH", "ACCEPTED")

    fun reviewItems(childId: Long): Flow<List<GalleryMedia>> =
        observedMedia(childId, "REVIEW")

    fun summary(childId: Long): Flow<GalleryScanSummary> = changes.map {
        withContext(Dispatchers.IO) {
            val profileRevision = profileStore.load(childId)?.revision
            val scan = readScan(childId)
            val count = if (profileRevision == null) mapOf() else counts(childId, profileRevision)
            GalleryScanSummary(
                automaticEnabled = scan.enabled,
                processed = count.values.sum(),
                matched = count.getOrDefault(IndexedDecision.MATCH, 0) + count.getOrDefault(IndexedDecision.ACCEPTED, 0),
                needsReview = count.getOrDefault(IndexedDecision.REVIEW, 0),
                skipped = count.getOrDefault(IndexedDecision.NO_MATCH, 0) + count.getOrDefault(IndexedDecision.REJECTED, 0),
                unreadable = count.getOrDefault(IndexedDecision.UNREADABLE, 0),
                status = when {
                    profileRevision == null -> GalleryScanStatus.NEEDS_REFERENCE
                    GalleryAccess.current(appContext) == GalleryAccess.NONE -> GalleryScanStatus.NO_PERMISSION
                    childId in running.value -> GalleryScanStatus.SCANNING
                    scan.revision != profileRevision -> GalleryScanStatus.IDLE
                    else -> scan.status
                },
                lastScannedAt = scan.lastScannedAt.takeIf { scan.revision == profileRevision },
                lastRunAnalyzed = scan.lastRunAnalyzed.takeIf { scan.revision == profileRevision } ?: 0,
            )
        }
    }.distinctUntilChanged()

    suspend fun review(childId: Long, uri: Uri, isChild: Boolean) = withContext(Dispatchers.IO) {
        val revision = profileStore.load(childId)?.revision ?: return@withContext
        val decision = if (isChild) IndexedDecision.ACCEPTED else IndexedDecision.REJECTED
        val values = ContentValues().apply { put("decision", decision.name) }
        val updated = db.writableDatabase.update(
            "indexed_media",
            values,
            "child_id=? AND uri=? AND revision=? AND decision IN ('REVIEW','MATCH','ACCEPTED')",
            arrayOf(childId.toString(), uri.toString(), revision),
        )
        if (updated > 0) changed()
    }

    fun profileChanged() = changed()

    fun accessChanged() = changed()

    suspend fun clearChild(childId: Long) = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        val args = arrayOf(childId.toString())
        database.beginTransaction()
        try {
            database.delete("indexed_media", "child_id=?", args)
            database.delete("child_scan", "child_id=?", args)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        changed()
    }

    internal suspend fun automaticEnabled(childId: Long): Boolean = withContext(Dispatchers.IO) { readScan(childId).enabled }

    internal suspend fun setAutomatic(childId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        updateScan(childId, ContentValues().apply {
            put("automatic", if (enabled) 1 else 0)
            if (!enabled && readScan(childId).status == GalleryScanStatus.PENDING) {
                put("status", GalleryScanStatus.IDLE.name)
            }
        })
        changed()
    }

    internal fun setRunning(childId: Long, active: Boolean) {
        running.update { if (active) it + childId else it - childId }
        changed()
    }

    internal suspend fun updateStatus(
        childId: Long,
        revision: String,
        status: GalleryScanStatus,
        analyzed: Int? = null,
    ) = withContext(Dispatchers.IO) {
        if (profileStore.load(childId)?.revision != revision) return@withContext
        updateScan(childId, ContentValues().apply {
            put("status", status.name)
            put("revision", revision)
            if (status == GalleryScanStatus.COMPLETE) put("last_scanned", Instant.now().toEpochMilli())
            else if (readScan(childId).revision != revision) putNull("last_scanned")
            analyzed?.let { put("last_run_analyzed", it) }
        })
        changed()
    }

    internal suspend fun nextBatch(childId: Long, revision: String, limit: Int): GalleryBatch = withContext(Dispatchers.IO) {
        val previous = db.readableDatabase.rawQuery(
            "SELECT uri, fingerprint FROM indexed_media WHERE child_id=? AND revision=?",
            arrayOf(childId.toString(), revision),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
        catalog.unprocessed(childId, previous, limit)
    }

    internal suspend fun remember(item: CatalogMedia, revision: String, decision: IndexedDecision) = withContext(Dispatchers.IO) {
        val media = item.media
        val values = ContentValues().apply {
            put("child_id", media.childId)
            put("uri", media.uri.toString())
            put("revision", revision)
            put("fingerprint", item.fingerprint)
            put("date", media.date.toEpochDay())
            put("kind", media.kind.name)
            put("name", media.displayName)
            put("estimated", if (media.dateEstimated) 1 else 0)
            put("decision", decision.name)
        }
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            if (profileStore.load(media.childId)?.revision != revision) return@withContext
            check(writable.insertWithOnConflict("indexed_media", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Could not commit gallery classification"
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private fun observedMedia(childId: Long, vararg decisions: String): Flow<List<GalleryMedia>> = changes.map {
        withContext(Dispatchers.IO) {
            val revision = profileStore.load(childId)?.revision ?: return@withContext emptyList()
            if (GalleryAccess.current(appContext) == GalleryAccess.NONE) return@withContext emptyList()
            val placeholders = decisions.joinToString(",") { "?" }
            val indexed = db.readableDatabase.rawQuery(
                "SELECT uri, date, kind, name, decision, estimated, fingerprint FROM indexed_media " +
                    "WHERE child_id=? AND revision=? AND decision IN ($placeholders) ORDER BY date DESC, uri",
                arrayOf(childId.toString(), revision, *decisions),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val media = GalleryMedia(
                            childId = childId,
                            date = LocalDate.ofEpochDay(cursor.getLong(1)),
                            uri = Uri.parse(cursor.getString(0)),
                            kind = GalleryMediaKind.valueOf(cursor.getString(2)),
                            displayName = cursor.getString(3),
                            reviewed = cursor.getString(4) == IndexedDecision.ACCEPTED.name,
                            dateEstimated = cursor.getInt(5) != 0,
                        )
                        add(CatalogMedia(media, cursor.getString(6)))
                    }
                }
            }
            val accessible = catalog.visible(indexed)
            if (accessible.deleted.isNotEmpty()) removeDeleted(childId, revision, accessible.deleted)
            accessible.visible.map(CatalogMedia::media)
        }
    }.distinctUntilChanged()

    private fun counts(childId: Long, revision: String): Map<IndexedDecision, Int> =
        db.readableDatabase.rawQuery(
            "SELECT decision, COUNT(*) FROM indexed_media WHERE child_id=? AND revision=? GROUP BY decision",
            arrayOf(childId.toString(), revision),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(IndexedDecision.valueOf(cursor.getString(0)), cursor.getInt(1))
            }
        }

    private fun readScan(childId: Long): ScanRow = db.readableDatabase.rawQuery(
        "SELECT automatic, status, last_scanned, revision, last_run_analyzed FROM child_scan WHERE child_id=?",
        arrayOf(childId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use ScanRow()
        ScanRow(
            enabled = cursor.getInt(0) != 0,
            status = GalleryScanStatus.valueOf(cursor.getString(1)),
            lastScannedAt = if (cursor.isNull(2)) null else Instant.ofEpochMilli(cursor.getLong(2)),
            revision = cursor.getString(3),
            lastRunAnalyzed = cursor.getInt(4),
        )
    }

    private fun removeDeleted(childId: Long, revision: String, deleted: List<CatalogMedia>) {
        val writable = db.writableDatabase
        var removed = 0
        writable.beginTransaction()
        try {
            for (item in deleted) {
                removed += writable.delete(
                    "indexed_media",
                    "child_id=? AND revision=? AND uri=? AND fingerprint=?",
                    arrayOf(childId.toString(), revision, item.media.uri.toString(), item.fingerprint),
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        if (removed > 0) changed()
    }

    private fun updateScan(childId: Long, values: ContentValues) {
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            writable.insertWithOnConflict(
                "child_scan",
                null,
                ContentValues().apply { put("child_id", childId) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            writable.update("child_scan", values, "child_id=?", arrayOf(childId.toString()))
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
    }

    private data class ScanRow(
        val enabled: Boolean = false,
        val status: GalleryScanStatus = GalleryScanStatus.IDLE,
        val lastScannedAt: Instant? = null,
        val revision: String? = null,
        val lastRunAnalyzed: Int = 0,
    )

    private companion object {
        private val changes = MutableStateFlow(0L)
        private val running = MutableStateFlow<Set<Long>>(emptySet())
        @Volatile private var instance: GalleryDatabase? = null

        private fun database(context: Context): GalleryDatabase = instance ?: synchronized(this) {
            instance ?: GalleryDatabase(context).also { instance = it }
        }

        private fun changed() { changes.update { it + 1 } }
    }
}

private class GalleryDatabase(context: Context) : SQLiteOpenHelper(context, "gallery-index.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE indexed_media (
                child_id INTEGER NOT NULL, uri TEXT NOT NULL, revision TEXT NOT NULL,
                fingerprint TEXT NOT NULL, date INTEGER NOT NULL, kind TEXT NOT NULL,
                name TEXT NOT NULL, estimated INTEGER NOT NULL, decision TEXT NOT NULL,
                PRIMARY KEY (child_id, uri)
            )""",
        )
        db.execSQL("CREATE INDEX indexed_media_decision ON indexed_media(child_id, revision, decision, date)")
        db.execSQL(
            """CREATE TABLE child_scan (
                child_id INTEGER PRIMARY KEY, automatic INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'IDLE', last_scanned INTEGER, revision TEXT,
                last_run_analyzed INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No gallery index schema migration from $oldVersion to $newVersion")
    }
}

private class GalleryCatalog(private val context: Context) {
    suspend fun unprocessed(childId: Long, previous: Map<String, String>, limit: Int): GalleryBatch {
        val sources = sources()
        if (sources.isEmpty()) return GalleryBatch(emptyList(), false)
        val perSource = maxOf(1, limit / sources.size)
        var more = false
        val collected = mutableListOf<CatalogMedia>()
        for (source in sources) {
            var count = 0
            query(source, childId) { entry ->
                if (previous[entry.media.uri.toString()] == entry.fingerprint) return@query true
                if (count >= perSource) {
                    more = true
                    return@query false
                }
                collected.add(entry)
                count++
                true
            }
        }
        return GalleryBatch(collected, more)
    }

    suspend fun visible(indexed: List<CatalogMedia>): MediaVisibility {
        if (indexed.isEmpty()) return MediaVisibility(emptyList(), emptyList())
        val sources = sources().associateBy { it.uri.toString() }
        val accepted = mutableSetOf<Uri>()
        val deleted = mutableListOf<CatalogMedia>()
        for ((collection, entries) in indexed.groupBy { it.media.uri.toString().substringBeforeLast('/') }) {
            val source = sources[collection] ?: continue
            for (batch in entries.chunked(250)) {
                val expected = batch.associate { it.media.uri to it.fingerprint }
                val present = mutableSetOf<Uri>()
                try {
                    query(source, batch.first().media.childId, batch.map { ContentUris.parseId(it.media.uri) }) { latest ->
                        present.add(latest.media.uri)
                        if (expected[latest.media.uri] == latest.fingerprint) accepted.add(latest.media.uri)
                        true
                    }
                    if (source.access == GalleryAccess.FULL && GalleryAccess.mediaAccess(context, source.kind) == GalleryAccess.FULL) {
                        deleted.addAll(batch.filter { it.media.uri !in present })
                    }
                } catch (_: SecurityException) {
                    // Android may revoke selected-media access while the query is running.
                } catch (_: IllegalArgumentException) {
                    // The volume may have been removed since it was enumerated.
                } catch (_: IOException) {
                    // A transient provider failure cannot confirm deletion.
                }
            }
        }
        return MediaVisibility(indexed.filter { it.media.uri in accepted }, deleted)
    }

    private fun sources(): List<MediaSource> {
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).sorted()
        } else listOf("external")
        return volumes.flatMap { volume ->
            val version = try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MediaStore.getVersion(context, volume)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.getVersion(context)
                    else -> "legacy"
                }
            } catch (_: IllegalArgumentException) {
                return@flatMap emptyList()
            } catch (_: SecurityException) {
                return@flatMap emptyList()
            }
            GalleryMediaKind.entries.mapNotNull { kind ->
                val access = GalleryAccess.mediaAccess(context, kind)
                if (access == GalleryAccess.NONE) return@mapNotNull null
                val collection = if (kind == GalleryMediaKind.PHOTO) MediaStore.Images.Media.getContentUri(volume)
                else MediaStore.Video.Media.getContentUri(volume)
                MediaSource(kind, collection, version, access)
            }
        }
    }

    private suspend fun query(
        source: MediaSource,
        childId: Long,
        ids: List<Long> = emptyList(),
        visit: (CatalogMedia) -> Boolean,
    ) {
        val columns = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }.toTypedArray()
        val selection = ids.takeIf { it.isNotEmpty() }?.joinToString(",", "_id IN (", ")") { "?" }
        val parameters = ids.takeIf { it.isNotEmpty() }?.map(Long::toString)?.toTypedArray()
        val result = context.contentResolver.query(source.uri, columns, selection, parameters, "_id DESC")
            ?: throw IOException("Gallery provider returned no cursor")
        result.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                if (count++ % 64 == 0) currentCoroutineContext().ensureActive()
                val item = cursor.toMedia(childId, source) ?: continue
                if (!visit(item)) break
            }
        }
    }

    private fun Cursor.toMedia(childId: Long, source: MediaSource): CatalogMedia? {
        val id = getLong(0)
        val taken = getLong(1).takeIf { it > 0 }
        val modified = getLong(2)
        val added = getLong(3)
        val length = getLong(4)
        if (length <= 0) return null
        val recordedAt = taken?.let(Instant::ofEpochMilli)
            ?: modified.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            ?: added.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            ?: return null
        val generation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getLong(6) else 0L
        val itemUri = ContentUris.withAppendedId(source.uri, id)
        val name = getString(5) ?: if (source.kind == GalleryMediaKind.PHOTO) "사진" else "영상"
        val media = GalleryMedia(
            childId = childId,
            date = recordedAt.atZone(CareZone).toLocalDate(),
            uri = itemUri,
            kind = source.kind,
            displayName = name,
            dateEstimated = taken == null,
        )
        val revision = if (source.kind == GalleryMediaKind.VIDEO) ":video-$GALLERY_CLASSIFIER_REVISION" else ""
        return CatalogMedia(media, "${source.version}:$generation:$modified:$added:$length:${taken ?: 0}$revision")
    }

    private data class MediaSource(val kind: GalleryMediaKind, val uri: Uri, val version: String?, val access: GalleryAccess)
    data class MediaVisibility(val visible: List<CatalogMedia>, val deleted: List<CatalogMedia>)
}
