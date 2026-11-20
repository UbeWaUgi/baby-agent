package com.yugainanal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.media.GalleryAccess
import com.yugainanal.app.reports.DailyReportPdf
import com.yugainanal.app.reports.OPEN_REPORT_ID
import com.yugainanal.app.reports.OPEN_REPORTS_CHILD_ID
import com.yugainanal.app.reports.ReportSchedule
import com.yugainanal.app.reports.previewDayReport
import com.yugainanal.app.ui.CareAlbumScreen
import com.yugainanal.app.ui.CareBottomTabs
import com.yugainanal.app.ui.CareQuickHomeScreen
import com.yugainanal.app.ui.CareRegistration
import com.yugainanal.app.ui.CareTab
import com.yugainanal.app.ui.CareTheme
import com.yugainanal.app.ui.CareTopBar
import com.yugainanal.app.ui.CareTrackScreen
import com.yugainanal.app.ui.ChildEditor
import com.yugainanal.app.ui.EntryCategory
import com.yugainanal.app.ui.EntryEditor
import com.yugainanal.app.ui.GalleryUiState
import com.yugainanal.app.ui.ReportsScreen
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: CareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CareTheme { CareHome(model) } }
        onReportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onReportIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        model.reconcileReportSchedules()
    }

    override fun onResume() {
        super.onResume()
        model.gallery.refreshAccess()
    }

    private fun onReportIntent(intent: Intent) {
        val childId = intent.getLongExtra(OPEN_REPORTS_CHILD_ID, 0L)
        if (childId <= 0L) return
        val reportId = intent.getLongExtra(OPEN_REPORT_ID, 0L).takeIf { it > 0 }
        intent.removeExtra(OPEN_REPORTS_CHILD_ID)
        intent.removeExtra(OPEN_REPORT_ID)
        model.openReports(childId, reportId)
    }
}

@Composable
private fun CareHome(model: CareViewModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val importScreen by model.importScreen.collectAsStateWithLifecycle()
    val reportsChildId by model.reportChildId.collectAsStateWithLifecycle()
    val reportDate by model.focusedReportDate.collectAsStateWithLifecycle()
    val reportPane by model.reportPane.collectAsStateWithLifecycle()
    val selectedReportId by model.selectedReportId.collectAsStateWithLifecycle()
    val generatingReport by model.generatingReport.collectAsStateWithLifecycle()
    val exportingReport by model.exportingReport.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now(CareZone)) {
        while (true) {
            value = LocalDate.now(CareZone)
            delay(30_000)
        }
    }
    var browseTab by rememberSaveable { mutableStateOf(CareTab.HOME) }
    var browseDateText by rememberSaveable { mutableStateOf<String?>(null) }
    var childEditorOpen by rememberSaveable { mutableStateOf(false) }
    var editorChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorCategory by rememberSaveable { mutableStateOf<EntryCategory?>(null) }
    var editorDateText by rememberSaveable { mutableStateOf<String?>(null) }
    var importChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPhotoChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPhotoDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReferenceChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingGalleryChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var enableGalleryAfterPermission by rememberSaveable { mutableStateOf(false) }
    var pendingPdfReportId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPdfChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingPdfDate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPdfCreatedAt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingScheduleChildId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingScheduleMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val childId = importChildId
        importChildId = null
        if (uri != null && childId != null) model.readImportFile(childId, uri)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        val childId = pendingPhotoChildId
        val date = pendingPhotoDate?.let(LocalDate::parse)
        pendingPhotoChildId = null
        pendingPhotoDate = null
        if (childId != null && date != null && uris.isNotEmpty()) model.importPhotos(childId, date, uris)
    }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val childId = pendingReferenceChildId
        pendingReferenceChildId = null
        if (childId != null && uri != null) model.gallery.registerReference(childId, uri)
    }
    val galleryPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val childId = pendingGalleryChildId
        val enable = enableGalleryAfterPermission
        pendingGalleryChildId = null
        enableGalleryAfterPermission = false
        model.gallery.refreshAccess()
        if (childId != null) {
            if (enable) model.gallery.setAutomatic(childId, true) else model.gallery.scan(childId)
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val childId = pendingPdfChildId
        val date = pendingPdfDate?.let(LocalDate::parse)
        val reportId = pendingPdfReportId
        val createdAt = pendingPdfCreatedAt?.let(LocalDateTime::parse)
        pendingPdfChildId = null
        pendingPdfDate = null
        pendingPdfReportId = null
        pendingPdfCreatedAt = null
        if (uri != null && childId != null && date != null && reportId != null && createdAt != null) {
            model.exportReportPdf(childId, date, reportId, createdAt, uri)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val childId = pendingScheduleChildId
        val minute = pendingScheduleMinute
        pendingScheduleChildId = null
        pendingScheduleMinute = null
        if (childId != null && minute != null) {
            model.changeReportSchedule(childId, ReportSchedule(true, LocalTime.of(minute / 60, minute % 60)))
        }
    }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val savedScreens = rememberSaveableStateHolder()
    LaunchedEffect(model) {
        model.messages.collect { snackbar.showSnackbar(it) }
    }

    val activeChild = screen.children.firstOrNull { it.id == screen.selectedChildId }
    val reportChild = screen.children.firstOrNull { it.id == reportsChildId }
    val visibleChild = reportChild ?: activeChild
    val childPhotos by remember(visibleChild?.id) { model.photos(visibleChild?.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val galleryState = key(visibleChild?.id) {
        val gallery = remember(visibleChild?.id) { model.gallery.observe(visibleChild?.id) }
        gallery.collectAsStateWithLifecycle(initialValue = GalleryUiState()).value
    }
    val selectedTab = if (reportChild != null) CareTab.REPORT else browseTab
    val browseDate = (browseDateText?.let(LocalDate::parse) ?: today)
        .coerceIn(activeChild?.birthDate?.coerceAtMost(today) ?: today, today)
    val selectedReport = reportPane.reports.firstOrNull { it.id == selectedReportId }
    val preview = remember(reportChild, reportDate, reportPane) {
        if (reportChild != null && reportDate != null && reportPane.childId == reportChild.id &&
            reportPane.reports.none { it.date == reportDate }
        ) {
            previewDayReport(reportChild, requireNotNull(reportDate), reportPane.entries, LocalDateTime.now(CareZone))
        } else null
    }
    fun changeDate(date: LocalDate) {
        browseDateText = date.toString().takeUnless { date == today }
    }
    fun allowGallery(childId: Long, enableAutomatic: Boolean) {
        pendingGalleryChildId = childId
        enableGalleryAfterPermission = enableAutomatic
        galleryPermissions.launch(GalleryAccess.permissions())
    }
    fun editEntry(category: EntryCategory, date: LocalDate, entry: CareEntry? = null) {
        editorChildId = activeChild?.id
        editorEntryId = entry?.id
        editorDateText = date.toString()
        editorCategory = category
    }
    fun closeEditor() {
        editorChildId = null
        editorEntryId = null
        editorDateText = null
        editorCategory = null
    }
    fun goToReport(date: LocalDate) {
        val child = activeChild ?: return
        changeDate(date)
        model.openReports(child.id, date = date)
    }
    fun leaveReport() {
        if (activeChild != null && reportChild?.id == activeChild.id) {
            (selectedReport?.date ?: reportDate)?.let(::changeDate)
        }
        model.closeReports()
    }
    BackHandler(enabled = selectedTab != CareTab.HOME) {
        if (selectedTab == CareTab.REPORT) leaveReport() else browseTab = CareTab.HOME
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (screen.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (visibleChild == null) {
                CareRegistration { childEditorOpen = true }
            } else {
                val photos = childPhotos.filter { it.childId == visibleChild.id }
                CareTopBar(
                    child = visibleChild,
                    children = screen.children,
                    onSelectChild = {
                        changeDate(today)
                        browseTab = CareTab.HOME
                        model.closeReports()
                        model.selectChild(it)
                    },
                    onAddChild = { childEditorOpen = true },
                    onImport = {
                        importChildId = visibleChild.id
                        filePicker.launch(arrayOf("*/*"))
                    },
                )
                Box(Modifier.weight(1f).fillMaxSize()) {
                    savedScreens.SaveableStateProvider(selectedTab.name) {
                        when (selectedTab) {
                            CareTab.HOME -> CareQuickHomeScreen(
                                today = today,
                                entries = screen.entries,
                                onAddEntry = { editEntry(it, today) },
                                onEditEntry = { editEntry(EntryCategory.of(it.draft.type), today, it) },
                                onTrack = { changeDate(today); browseTab = CareTab.TRACK },
                                onReport = { goToReport(today) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            CareTab.TRACK -> CareTrackScreen(
                                child = visibleChild,
                                date = browseDate,
                                today = today,
                                entries = screen.entries,
                                onDateChange = ::changeDate,
                                onReport = { goToReport(browseDate) },
                                onEditEntry = { editEntry(EntryCategory.of(it.draft.type), browseDate, it) },
                                onAddEntry = { editEntry(it, browseDate) },
                            )
                            CareTab.ALBUM -> CareAlbumScreen(
                                child = visibleChild,
                                date = browseDate,
                                today = today,
                                entries = screen.entries,
                                photos = photos,
                                onDateChange = ::changeDate,
                                onChoosePhotos = {
                                    pendingPhotoChildId = visibleChild.id
                                    pendingPhotoDate = browseDate.toString()
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onReport = { goToReport(browseDate) },
                                onRemovePhoto = { model.removePhoto(it) },
                                gallery = galleryState,
                                onChooseReference = {
                                    pendingReferenceChildId = visibleChild.id
                                    referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onScanGallery = {
                                    if (GalleryAccess.current(context) == GalleryAccess.NONE) allowGallery(visibleChild.id, false)
                                    else model.gallery.scan(visibleChild.id)
                                },
                                onToggleAutomatic = { enabled ->
                                    if (enabled && GalleryAccess.current(context) == GalleryAccess.NONE) allowGallery(visibleChild.id, true)
                                    else model.gallery.setAutomatic(visibleChild.id, enabled)
                                },
                                onAllowGallery = { allowGallery(visibleChild.id, galleryState.automaticEnabled) },
                                onReviewMedia = { media, isChild -> model.gallery.review(media, isChild) },
                                onClearReference = { model.gallery.clearReference(visibleChild.id) },
                            )
                            CareTab.REPORT -> if (reportPane.childId != visibleChild.id) {
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            } else {
                                ReportsScreen(
                                    child = visibleChild,
                                    reports = reportPane.reports,
                                    schedule = reportPane.schedule,
                                    selectedReport = selectedReport,
                                    generating = generatingReport,
                                    exporting = exportingReport,
                                    onBack = ::leaveReport,
                                    onScheduleChange = { schedule ->
                                        if (schedule.enabled && !reportPane.schedule.enabled &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            pendingScheduleChildId = visibleChild.id
                                            pendingScheduleMinute = schedule.time.hour * 60 + schedule.time.minute
                                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            model.changeReportSchedule(visibleChild.id, schedule)
                                        }
                                    },
                                    onGenerate = { model.createInstantReport(visibleChild.id, it) },
                                    onSelectReport = { model.openReport(it?.id) },
                                    onExport = { report ->
                                        pendingPdfReportId = report.id
                                        pendingPdfChildId = report.childId
                                        pendingPdfDate = report.date.toString()
                                        pendingPdfCreatedAt = report.createdAt.toString()
                                        pdfPicker.launch(DailyReportPdf.suggestedFileName(report))
                                    },
                                    focusedDate = reportDate,
                                    previewReport = preview,
                                    photos = photos,
                                    galleryMedia = galleryState.media,
                                    today = today,
                                    embedded = true,
                                    onShowHistory = model::showReportHistory,
                                )
                            }
                        }
                    }
                }
                CareBottomTabs(selectedTab) { tab ->
                    if (tab == CareTab.REPORT) {
                        if (selectedTab != CareTab.REPORT) goToReport(if (selectedTab == CareTab.HOME) today else browseDate)
                    } else {
                        if (selectedTab == CareTab.REPORT) leaveReport()
                        browseTab = tab
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 66.dp),
        )
    }

    if (childEditorOpen) {
        ChildEditor(
            today = today,
            onDismiss = { childEditorOpen = false },
            onSave = { name, birthDate ->
                childEditorOpen = false
                browseTab = CareTab.HOME
                changeDate(today)
                model.closeReports()
                model.addChild(name, birthDate)
            },
        )
    }
    val editingChild = activeChild?.takeIf { it.id == editorChildId }
    val category = editorCategory
    val editorDate = editorDateText?.let(LocalDate::parse)
    if (editingChild != null && category != null && editorDate != null) {
        val existing = screen.entries.firstOrNull { it.id == editorEntryId }
        if (editorEntryId == null || existing != null) {
            EntryEditor(
                category = category,
                entry = existing,
                initialDate = editorDate,
                earliestDate = editingChild.birthDate.coerceAtMost(today),
                today = today,
                onDismiss = ::closeEditor,
                onSave = { draft -> model.saveEntry(editingChild.id, existing?.id, draft); closeEditor() },
                onDelete = existing?.let { entry -> { model.deleteEntry(editingChild.id, entry.id); closeEditor() } },
            )
        }
    }

    when (val import = importScreen) {
        is ImportScreenState.Busy -> ImportBusyDialog(import.message)
        is ImportScreenState.Review -> ImportReviewDialog(
            pending = import.pending,
            onDismiss = model::closeImport,
            onApply = { model.applyImport(import.pending, it) },
        )
        null -> Unit
    }
}
