package com.yugainanal.app.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.DayPhoto
import com.yugainanal.app.media.GalleryMedia
import com.yugainanal.app.reports.DailyReport
import com.yugainanal.app.reports.REPORT_NOTE_MAX_LENGTH
import com.yugainanal.app.reports.ReportKind
import com.yugainanal.app.reports.ReportSchedule
import com.yugainanal.app.reports.ReportSection
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reportDateLabel = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)
private val reportDayLabel = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

@Composable
fun ReportsScreen(
    child: Child,
    reports: List<DailyReport>,
    schedule: ReportSchedule,
    selectedReport: DailyReport?,
    generating: Boolean,
    exporting: Boolean,
    onBack: () -> Unit,
    onScheduleChange: (ReportSchedule) -> Unit,
    onGenerate: (String) -> Unit,
    onSelectReport: (DailyReport?) -> Unit,
    onExport: (DailyReport) -> Unit,
    focusedDate: LocalDate? = null,
    previewReport: DailyReport? = null,
    photos: List<DayPhoto> = emptyList(),
    today: LocalDate = LocalDate.now(CareZone),
    embedded: Boolean = false,
    onShowHistory: (() -> Unit)? = null,
    galleryMedia: List<GalleryMedia> = emptyList(),
) {
    var memoEditorOpen by rememberSaveable(child.id, focusedDate, selectedReport?.id) { mutableStateOf(false) }
    var scheduleEditorOpen by rememberSaveable(child.id, focusedDate, selectedReport?.id) { mutableStateOf(false) }
    val visibleReport = selectedReport?.takeIf { focusedDate == null || it.date == focusedDate }
        ?: focusedDate?.let { date ->
            reports.firstOrNull { it.date == date } ?: previewReport?.takeIf { it.date == date }
        }
    val showHistory: () -> Unit = {
        if (focusedDate == null) onSelectReport(null) else (onShowHistory ?: onBack)()
    }
    val backAction: () -> Unit = {
        if (visibleReport == null || (embedded && focusedDate != null)) onBack() else showHistory()
    }
    BackHandler(enabled = !embedded || visibleReport != null, onBack = backAction)

    val reportContent: @Composable (Modifier) -> Unit = { modifier ->
        if (visibleReport == null) {
            ReportList(
                modifier = modifier,
                child = child,
                reports = reports,
                schedule = schedule,
                generating = generating,
                onScheduleChange = onScheduleChange,
                onGenerateClick = { memoEditorOpen = true },
                onOpen = { onSelectReport(it) },
            )
        } else {
            key(visibleReport.date, visibleReport.id) {
                ReportDetail(
                    modifier = modifier,
                    report = visibleReport,
                    preview = visibleReport.id <= 0L || visibleReport === previewReport,
                    photos = photos.filter { it.childId == child.id && it.date == visibleReport.date },
                    galleryMedia = galleryMedia.filter { it.childId == child.id && it.date == visibleReport.date },
                    schedule = schedule,
                    generating = generating,
                    exporting = exporting,
                    onShowHistory = showHistory.takeIf { embedded },
                    onGenerate = if (visibleReport.date == today) ({ memoEditorOpen = true }) else null,
                    onScheduleClick = { scheduleEditorOpen = true },
                    onExport = { onExport(visibleReport) },
                )
            }
        }
    }

    if (embedded) {
        reportContent(Modifier.fillMaxSize())
    } else {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = backAction) {
                        Text(if (visibleReport == null) "오늘로" else "리포트 목록")
                    }
                    Text(
                        text = "육아의나날",
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                reportContent(Modifier.weight(1f))
            }
        }
    }

    if (scheduleEditorOpen) {
        AlertDialog(
            onDismissRequest = { scheduleEditorOpen = false },
            title = { Text("자동 리포트 설정") },
            text = { ScheduleCard(schedule, onScheduleChange) },
            confirmButton = { TextButton(onClick = { scheduleEditorOpen = false }) { Text("닫기") } },
        )
    }

    if (memoEditorOpen) {
        ReportMemoDialog(
            childId = child.id,
            onDismiss = { memoEditorOpen = false },
            onSubmit = { note ->
                memoEditorOpen = false
                onGenerate(note)
            },
        )
    }
}

@Composable
private fun ReportList(
    modifier: Modifier,
    child: Child,
    reports: List<DailyReport>,
    schedule: ReportSchedule,
    generating: Boolean,
    onScheduleChange: (ReportSchedule) -> Unit,
    onGenerateClick: () -> Unit,
    onOpen: (DailyReport) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("하루 리포트", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${child.name} · 수유, 기저귀, 수면",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            ScheduleCard(schedule, onScheduleChange)
        }
        item {
            Button(
                onClick = onGenerateClick,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (generating) "리포트 만드는 중…" else "즉시 리포트 만들기")
            }
        }
        item {
            Text(
                "저장된 리포트",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (reports.isEmpty()) {
            item {
                Text(
                    "아직 만든 리포트가 없어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(reports, key = { it.id }) { report ->
            Card(
                onClick = { onOpen(report) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        report.date.format(reportDateLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${kindLabel(report.kind)} · ${reportWindow(report)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(schedule: ReportSchedule, onScheduleChange: (ReportSchedule) -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "자동 하루 리포트",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = { onScheduleChange(schedule.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "자동 하루 리포트" },
                )
            }
            Text(
                "완성된 리포트는 목록에 저장돼요. 알림을 허용하면 완료 소식도 받을 수 있어요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        onScheduleChange(schedule.copy(time = LocalTime.of(hour, minute)))
                    },
                    schedule.time.hour,
                    schedule.time.minute,
                    true,
                ).show()
            }) {
                Text("목표 시각 ${schedule.time.format(clockLabel)}")
            }
            Text(
                "한국시간 기준 · 기기 상태에 따라 늦어질 수 있어요.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReportDetail(
    modifier: Modifier,
    report: DailyReport,
    preview: Boolean,
    photos: List<DayPhoto>,
    galleryMedia: List<GalleryMedia>,
    schedule: ReportSchedule,
    generating: Boolean,
    exporting: Boolean,
    onShowHistory: (() -> Unit)?,
    onGenerate: (() -> Unit)?,
    onScheduleClick: () -> Unit,
    onExport: () -> Unit,
) {
    val (checks, summaries) = report.sections.partition { it.title == "확인할 기록" }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        report.date.format(reportDayLabel),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (onShowHistory != null) {
                        TextButton(onClick = onShowHistory) { Text("리포트 목록") }
                    }
                }
                Text(
                    "하루 리포트",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${report.childName} · ${if (preview) "기록 요약" else kindLabel(report.kind)}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    reportWindow(report),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (summaries.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        summaries.forEachIndexed { index, summary ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ReportSectionContent(summary, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
        if (photos.isNotEmpty()) {
            item { ReportPhotos(photos) }
        }
        if (galleryMedia.isNotEmpty()) {
            item { GalleryMediaSection(galleryMedia, featured = false) }
        }
        items(checks) { section ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    ReportSectionContent(section, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (report.note.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("보호자 메모", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(report.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            ReportActions(onGenerate, onExport, generating, exporting)
        }
        item {
            OutlinedButton(
                onClick = onScheduleClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("자동 리포트 설정", modifier = Modifier.weight(1f))
                Text(
                    if (schedule.enabled) "매일 ${schedule.time.format(clockLabel)}" else "꺼짐",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportSectionContent(section: ReportSection, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
        section.lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun ReportActions(
    onGenerate: (() -> Unit)?,
    onExport: () -> Unit,
    generating: Boolean,
    exporting: Boolean,
) {
    val generate: @Composable (Modifier) -> Unit = { modifier ->
        if (onGenerate != null) {
            Button(onClick = onGenerate, enabled = !generating, modifier = modifier.heightIn(min = 48.dp)) {
                Text(if (generating) "만드는 중…" else "즉시 리포트 만들기")
            }
        }
    }
    val export: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onExport, enabled = !exporting, modifier = modifier.heightIn(min = 48.dp)) {
            Text(if (exporting) "저장 중…" else "PDF로 저장")
        }
    }
    if (onGenerate != null && LocalDensity.current.fontScale <= 1.25f) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            generate(Modifier.weight(1.25f))
            export(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            generate(Modifier.fillMaxWidth())
            export(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReportPhotos(photos: List<DayPhoto>) {
    var openedPhotoId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openPhoto = photos.firstOrNull { it.id == openedPhotoId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("이날 사진 ${photos.size}장", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(photos, key = { it.id }) { photo ->
                    DayPhotoImage(
                        photo = photo,
                        modifier = Modifier.width(76.dp).height(64.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "사진 크게 보기") { openedPhotoId = photo.id },
                        contentDescription = "이날 사진",
                    )
                }
            }
        }
    }
    if (openPhoto != null) {
        AlertDialog(
            onDismissRequest = { openedPhotoId = null },
            title = { Text("이날 사진") },
            text = {
                DayPhotoImage(
                    photo = openPhoto,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentScale = ContentScale.Fit,
                )
            },
            confirmButton = { TextButton(onClick = { openedPhotoId = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun ReportMemoDialog(childId: Long, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var note by rememberSaveable(childId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("즉시 리포트 만들기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("오늘 지금까지의 기록을 정리해요.")
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= REPORT_NOTE_MAX_LENGTH) note = it },
                    label = { Text("보호자 메모 (선택)") },
                    supportingText = { Text("${note.length}/$REPORT_NOTE_MAX_LENGTH") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = { onSubmit(note.trim()) }) { Text("만들기") } },
    )
}

private fun kindLabel(kind: ReportKind): String = when (kind) {
    ReportKind.DAILY -> "자동 하루 리포트"
    ReportKind.INSTANT -> "즉시 리포트"
}

private fun reportWindow(report: DailyReport): String {
    if (report.cutoff.toLocalDate().isAfter(report.date)) return "하루 전체 기록 기준"
    return "00:00~${report.cutoff.format(clockLabel)} 기록 기준"
}
