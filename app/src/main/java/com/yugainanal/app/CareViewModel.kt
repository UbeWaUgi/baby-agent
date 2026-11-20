package com.yugainanal.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.DayPhoto
import com.yugainanal.app.data.DayPhotoStore
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.ImportDecision
import com.yugainanal.app.data.ImportFile
import com.yugainanal.app.data.ImportPreview
import com.yugainanal.app.data.LocalCareStore
import com.yugainanal.app.importing.BabyTimeParser
import com.yugainanal.app.media.GalleryMediaKind
import com.yugainanal.app.reports.DailyReport
import com.yugainanal.app.reports.DailyReportPdf
import com.yugainanal.app.reports.ReportSchedule
import com.yugainanal.app.reports.ReportStore
import com.yugainanal.app.reports.previewDayReport
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CareScreenState(
    val children: List<Child> = emptyList(),
    val selectedChildId: Long? = null,
    val entries: List<CareEntry> = emptyList(),
    val loading: Boolean = true,
)

data class PendingImport(
    val child: Child,
    val displayName: String,
    val file: ImportFile,
    val preview: ImportPreview,
)

data class ReportPaneState(
    val childId: Long? = null,
    val reports: List<DailyReport> = emptyList(),
    val schedule: ReportSchedule = ReportSchedule(),
    val entries: List<CareEntry> = emptyList(),
)

sealed interface ImportScreenState {
    data class Busy(val message: String) : ImportScreenState
    data class Review(val pending: PendingImport) : ImportScreenState
}

@OptIn(ExperimentalCoroutinesApi::class)
class CareViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalCareStore(application)
    private val reportStore = ReportStore(application)
    private val photoStore = DayPhotoStore(application)
    private val notifications = Channel<String>(Channel.BUFFERED)
    internal val gallery = GalleryController(application, viewModelScope) { notifications.trySend(it) }
    private val importState = MutableStateFlow<ImportScreenState?>(null)
    private val reportsChild = MutableStateFlow<Long?>(null)
    private val reportDate = MutableStateFlow<LocalDate?>(null)
    private val selectedReport = MutableStateFlow<Long?>(null)
    private val reportGeneration = MutableStateFlow(false)
    private val reportExport = MutableStateFlow(false)

    val messages = notifications.receiveAsFlow()
    val importScreen = importState.asStateFlow()
    val reportChildId = reportsChild.asStateFlow()
    val focusedReportDate = reportDate.asStateFlow()
    val selectedReportId = selectedReport.asStateFlow()
    val generatingReport = reportGeneration.asStateFlow()
    val exportingReport = reportExport.asStateFlow()
    val reportPane = reportsChild.flatMapLatest { childId ->
        if (childId == null) {
            flowOf(ReportPaneState())
        } else {
            combine(reportStore.reports(childId), reportStore.schedule(childId), store.entries(childId)) { history, schedule, entries ->
                ReportPaneState(childId, history, schedule, entries)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportPaneState())

    val screen = combine(store.children, store.selectedChildId) { children, selectedId ->
        val activeId = children.find { it.id == selectedId }?.id ?: children.firstOrNull()?.id
        children to activeId
    }.flatMapLatest { (children, activeId) ->
        if (activeId == null) {
            flowOf(CareScreenState(loading = false))
        } else {
            store.entries(activeId).map { entries ->
                CareScreenState(children, activeId, entries, loading = false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CareScreenState())

    fun selectChild(childId: Long) = save("아이를 바꾸지 못했어요.") {
        store.selectChild(childId)
    }

    fun addChild(name: String, birthDate: LocalDate) = save("아이 정보를 저장하지 못했어요.") {
        store.addChild(name, birthDate)
    }

    fun saveEntry(childId: Long, entryId: Long?, draft: EntryDraft) = save("기록을 저장하지 못했어요.") {
        store.saveEntry(childId, entryId, draft)
        notifications.trySend(if (entryId == null) "기록을 저장했어요." else "기록을 수정했어요.")
    }

    fun deleteEntry(childId: Long, entryId: Long) = save("기록을 삭제하지 못했어요.") {
        store.deleteEntry(childId, entryId)
        notifications.trySend("기록을 삭제했어요.")
    }

    fun openReports(childId: Long, reportId: Long? = null, date: LocalDate? = null) {
        selectedReport.value = reportId
        reportDate.value = date
        reportsChild.value = childId
    }

    fun reconcileReportSchedules() = save("자동 리포트 예약을 확인하지 못했어요.") {
        for (child in store.children.first()) reportStore.reconcileSchedule(child.id)
    }

    fun openReport(reportId: Long?) {
        selectedReport.value = reportId
    }

    fun showReportHistory() {
        selectedReport.value = null
        reportDate.value = null
    }

    fun closeReports() {
        showReportHistory()
        reportsChild.value = null
    }

    fun photos(childId: Long?) = if (childId == null) flowOf(emptyList<DayPhoto>()) else photoStore.photos(childId)

    fun importPhotos(childId: Long, date: LocalDate, uris: List<Uri>) = save("사진을 추가하지 못했어요.") {
        if (uris.isEmpty()) return@save
        val child = store.children.first().firstOrNull { it.id == childId } ?: return@save
        require(date in child.birthDate..LocalDate.now(CareZone))
        val result = photoStore.importPhotos(childId, date, uris)
        val message = buildList {
            if (result.added > 0) add("사진 ${result.added}장을 추가했어요.")
            if (result.duplicates > 0) add("중복 사진 ${result.duplicates}장은 건너뛰었어요.")
            if (result.skipped > 0) add("사진 ${result.skipped}장을 읽지 못했어요.")
        }.joinToString(" ")
        if (message.isNotBlank()) notifications.trySend(message)
    }

    fun removePhoto(photo: DayPhoto) = save("사진을 삭제하지 못했어요.") {
        photoStore.removePhoto(photo)
        notifications.trySend("달력에서 사진을 삭제했어요.")
    }

    fun changeReportSchedule(childId: Long, schedule: ReportSchedule) =
        save("자동 리포트 설정을 저장하지 못했어요.") {
            reportStore.updateSchedule(childId, schedule)
        }

    fun createInstantReport(childId: Long, note: String) {
        if (reportGeneration.value) return
        reportGeneration.value = true
        viewModelScope.launch {
            try {
                val report = reportStore.createInstant(childId, note)
                if (reportsChild.value == childId && (reportDate.value == null || reportDate.value == report.date)) {
                    selectedReport.value = report.id
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                notifications.trySend("리포트를 만들지 못했어요. 다시 시도해 주세요.")
            } finally {
                reportGeneration.value = false
            }
        }
    }

    fun exportReportPdf(childId: Long, date: LocalDate, reportId: Long, createdAt: LocalDateTime, uri: Uri) {
        if (reportExport.value) return
        reportExport.value = true
        viewModelScope.launch {
            try {
                val report = if (reportId > 0L) {
                    requireNotNull(reportStore.findReport(reportId)).also {
                        check(it.childId == childId && it.date == date)
                    }
                } else {
                    val child = store.children.first().first { it.id == childId }
                    previewDayReport(child, date, store.entries(childId).first(), createdAt)
                }
                val photos = photoStore.photos(childId).first().filter { it.date == date }.map(photoStore::fileFor)
                val automaticMedia = gallery.matches(childId).first().filter { it.date == date }
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val staging = File.createTempFile("care-report-", ".pdf", app.cacheDir)
                    try {
                        staging.outputStream().use { output ->
                            DailyReportPdf.write(
                                report,
                                output,
                                photos,
                                app.contentResolver,
                                automaticMedia.filter { it.kind == GalleryMediaKind.PHOTO }.map { it.uri },
                                automaticMedia.count { it.kind == GalleryMediaKind.VIDEO },
                            )
                        }
                        val output = requireNotNull(app.contentResolver.openOutputStream(uri, "wt"))
                        output.use { target -> staging.inputStream().use { it.copyTo(target) } }
                    } finally {
                        staging.delete()
                    }
                }
                notifications.trySend("PDF를 저장했어요.")
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                notifications.trySend("PDF를 저장하지 못했어요. 저장 위치를 확인해 주세요.")
            } finally {
                reportExport.value = false
            }
        }
    }

    fun readImportFile(childId: Long, uri: Uri) {
        if (importState.value != null) return
        val child = screen.value.children.find { it.id == childId }
        if (child == null) {
            notifications.trySend("가져올 아이를 다시 선택해 주세요.")
            return
        }
        importState.value = ImportScreenState.Busy("가져올 기록을 읽고 있어요.")
        viewModelScope.launch {
            try {
                val (displayName, bytes) = withContext(Dispatchers.IO) { readDocument(uri) }
                val file = withContext(Dispatchers.Default) {
                    val parsed = BabyTimeParser.parse(bytes)
                    val now = LocalDateTime.now(CareZone)
                    val valid = parsed.records.filter { record ->
                        val draft = record.draft
                        draft.startedAt.toLocalDate() >= child.birthDate && draft.startedAt <= now &&
                            (draft.endedAt == null || draft.endedAt <= now)
                    }
                    val skipped = parsed.records.size - valid.size
                    if (skipped == 0) parsed else parsed.copy(
                        records = valid,
                        ignoredRows = parsed.ignoredRows + skipped,
                        warnings = listOf("생년월일 전이나 현재 이후의 기록 ${skipped}건을 건너뛰어요.") + parsed.warnings,
                    )
                }
                val preview = store.prepareImport(childId, file)
                importState.value = ImportScreenState.Review(PendingImport(child, displayName, file, preview))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: IllegalArgumentException) {
                importState.value = null
                notifications.trySend(failure.message ?: "BabyTime 내보내기 파일을 확인해 주세요.")
            } catch (_: Exception) {
                importState.value = null
                notifications.trySend("파일을 읽지 못했어요. BabyTime에서 내보낸 파일을 다시 선택해 주세요.")
            }
        }
    }

    fun closeImport() {
        if (importState.value is ImportScreenState.Review) importState.value = null
    }

    fun applyImport(pending: PendingImport, decisions: List<ImportDecision>) {
        val current = importState.value as? ImportScreenState.Review ?: return
        if (current.pending !== pending) return
        importState.value = ImportScreenState.Busy("기록을 저장하고 있어요.")
        viewModelScope.launch {
            try {
                val result = store.applyImport(pending.child.id, pending.file, decisions)
                importState.value = null
                notifications.trySend(
                    "새 기록 ${result.added}건 · 연결 ${result.linked}건 · 건너뜀 ${result.skipped}건"
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                importState.value = current
                notifications.trySend("가져오기에 실패했어요. 다시 시도해 주세요.")
            }
        }
    }

    private fun save(errorMessage: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                notifications.trySend(errorMessage)
            }
        }
    }

    private fun readDocument(uri: Uri): Pair<String, ByteArray> {
        val resolver = getApplication<Application>().contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }?.take(200) ?: "BabyTime 내보내기 파일"

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                require(output.size() + length <= BabyTimeParser.MAX_FILE_BYTES) {
                    "파일이 너무 커요. BabyTime에서 기간을 나눠 내보내 주세요."
                }
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("선택한 파일을 열 수 없어요.")
        return displayName to bytes
    }
}
