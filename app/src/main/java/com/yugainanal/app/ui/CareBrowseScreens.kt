package com.yugainanal.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.DayPhoto
import com.yugainanal.app.data.DayPhotoStore
import com.yugainanal.app.data.DaySummary
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordType
import com.yugainanal.app.data.entriesForDay
import com.yugainanal.app.data.summarizeDay
import com.yugainanal.app.media.GalleryMedia
import com.yugainanal.app.media.GalleryMediaKind
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val monthLabel = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)
private val weekDayLabel = DateTimeFormatter.ofPattern("E", Locale.KOREAN)
private const val MINUTES_PER_DAY = 24 * 60
private const val SECONDS_PER_DAY = MINUTES_PER_DAY * 60

@Composable
internal fun CareTrackScreen(
    child: Child,
    date: LocalDate,
    today: LocalDate,
    entries: List<CareEntry>,
    onDateChange: (LocalDate) -> Unit,
    onReport: () -> Unit,
    onEditEntry: ((CareEntry) -> Unit)? = null,
    onAddEntry: ((EntryCategory) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ownEntries = remember(entries, child.id) { entries.filter { it.childId == child.id } }
    val events = remember(ownEntries, date) { trackEvents(ownEntries, date) }
    val groups = remember(events) { events.groupBy { it.minute }.toSortedMap() }
    val times = remember(groups) { groups.keys.toList() }
    val summary = remember(ownEntries, date) { summarizeDay(ownEntries, date) }
    val chooseDate = dateChooser(date, child.birthDate.coerceAtMost(today), today, onDateChange)
    var selectedMinute by rememberSaveable(child.id, date.toEpochDay()) { mutableStateOf<Int?>(null) }
    val activeMinute = selectedMinute?.takeIf(groups::containsKey) ?: times.lastOrNull()
    val currentEntries = activeMinute?.let { groups[it] }.orEmpty()
    val cutoff by produceState(
        if (date == today) LocalTime.now(CareZone).toSecondOfDay() else SECONDS_PER_DAY,
        date,
        today,
    ) {
        value = if (date == today) LocalTime.now(CareZone).toSecondOfDay() else SECONDS_PER_DAY
        if (date == today) {
            while (true) {
                delay(60_000)
                value = LocalTime.now(CareZone).toSecondOfDay()
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BrowseHeading("하루 트랙", date.format(dayLabel), chooseDate)
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("24시간 기록", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (date == today) "${minuteLabel(cutoff / 60)}까지" else "하루 전체",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TrackLanes(events, cutoff, activeMinute)
                    if (times.isNotEmpty()) {
                        val index = times.indexOf(activeMinute).coerceAtLeast(0)
                        val chipState = rememberLazyListState()
                        LaunchedEffect(index, times) {
                            val visible = chipState.layoutInfo.visibleItemsInfo
                            if (visible.none { it.index == index }) {
                                chipState.scrollToItem((index - 2).coerceAtLeast(0))
                            }
                        }
                        LazyRow(
                            modifier = Modifier.selectableGroup(),
                            state = chipState,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            itemsIndexed(times, key = { _, time -> time }) { _, time ->
                                FilterChip(
                                    selected = time == activeMinute,
                                    onClick = { selectedMinute = time },
                                    label = { Text(minuteLabel(time)) },
                                    modifier = Modifier.heightIn(min = 46.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeMinute != null) {
                        Text(
                            "${minuteLabel(activeMinute)}  ·  기록 ${currentEntries.size}건",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text("이날 기록이 없어요.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (onAddEntry != null) BrowseAddEntry(onAddEntry)
                }
                currentEntries.forEach { event ->
                    TrackEntryLine(event, onEditEntry)
                    HorizontalDivider()
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("확인된 수면", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (summary.completedSleepCount > 0) durationText(summary.sleepMinutes) else "기록 없음",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(onClick = onReport) { Text("이날 리포트 보기") }
            }
        }
    }
}

private enum class BrowseLane(val label: String) {
    FEED("수유"), DIAPER("기저귀"), SLEEP("수면")
}

private data class TrackEvent(val entry: CareEntry, val lane: BrowseLane, val second: Int, val endSecond: Int?) {
    val minute get() = second / 60
}

private fun trackEvents(entries: List<CareEntry>, date: LocalDate): List<TrackEvent> {
    val dayStart = date.atStartOfDay()
    val dayEnd = date.plusDays(1).atStartOfDay()
    return entriesForDay(entries, date).map { entry ->
        val draft = entry.draft
        val start = maxOf(draft.startedAt, dayStart)
        val lane = when (draft.type) {
            RecordType.DIAPER -> BrowseLane.DIAPER
            RecordType.SLEEP -> BrowseLane.SLEEP
            else -> BrowseLane.FEED
        }
        val end = draft.endedAt?.let { minOf(it, dayEnd) }?.takeIf { lane == BrowseLane.SLEEP && it > start }
        TrackEvent(
            entry = entry,
            lane = lane,
            second = Duration.between(dayStart, start).seconds.toInt(),
            endSecond = end?.let { Duration.between(dayStart, it).seconds.toInt() },
        )
    }.sortedBy { it.second }
}

@Composable
private fun TrackLanes(events: List<TrackEvent>, cutoff: Int, selectedMinute: Int?) {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val laneColors = mapOf(
        BrowseLane.FEED to colors.primary,
        BrowseLane.DIAPER to if (dark) Color(0xFFA6C7E4) else Color(0xFF426A8D),
        BrowseLane.SLEEP to if (dark) Color(0xFFA5CF9F) else Color(0xFF45643D),
    )
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 49.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24시").forEach { tick ->
                Text(tick, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(49.dp)) {
                BrowseLane.entries.forEach { lane ->
                    Row(Modifier.height(31.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(laneColors.getValue(lane), CircleShape))
                        Text(lane.label, modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Canvas(
                modifier = Modifier.weight(1f).height(93.dp).semantics {
                    contentDescription = "수유, 기저귀, 수면의 24시간 트랙. 아래 시각을 선택하면 상세 기록을 확인할 수 있어요."
                },
            ) {
                val laneHeight = size.height / BrowseLane.entries.size
                val grid = colors.outlineVariant
                val markerRadius = 3.5.dp.toPx()
                val stop = size.width * cutoff / SECONDS_PER_DAY
                if (stop < size.width) {
                    drawRect(colors.surfaceVariant.copy(alpha = .5f), Offset(stop, 0f), Size(size.width - stop, size.height))
                }
                for (quarter in 0..4) {
                    val x = size.width * quarter / 4
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height))
                }
                for (row in 0..BrowseLane.entries.size) {
                    val y = laneHeight * row
                    drawLine(grid, Offset(0f, y), Offset(size.width, y))
                }
                if (selectedMinute != null) {
                    val x = size.width * selectedMinute / MINUTES_PER_DAY
                    drawLine(colors.primary.copy(alpha = .65f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                for (event in events) {
                    val start = size.width * event.second / SECONDS_PER_DAY
                    val y = laneHeight * (event.lane.ordinal + .5f)
                    val tint = laneColors.getValue(event.lane)
                    if (event.endSecond != null) {
                        val end = size.width * event.endSecond / SECONDS_PER_DAY
                        drawRoundRect(
                            tint,
                            topLeft = Offset(start, y - 2.5.dp.toPx()),
                            size = Size((end - start).coerceAtLeast(1.dp.toPx()), 5.dp.toPx()),
                            cornerRadius = CornerRadius(2.5.dp.toPx()),
                        )
                    } else {
                        val center = Offset(start.coerceIn(markerRadius, size.width - markerRadius), y)
                        if (event.lane == BrowseLane.SLEEP) {
                            drawCircle(colors.surface, markerRadius, center)
                            drawCircle(tint, markerRadius, center, style = Stroke(1.5.dp.toPx()))
                        } else {
                            drawCircle(tint, markerRadius, center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackEntryLine(event: TrackEvent, onEdit: ((CareEntry) -> Unit)?) {
    val draft = event.entry.draft
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(draft.type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val detail = when (draft.type) {
                RecordType.DIAPER -> listOf(
                    browsePresence("소변", draft.urine), browsePresence("대변", draft.stool),
                    draft.stoolColor, draft.stoolConsistency,
                ).filter { it.isNotBlank() }.joinToString(" · ")
                RecordType.SLEEP -> {
                    if (event.endSecond == null) "깬 시각 미확인" else {
                        val seal = requireNotNull(draft.endedAt)
                        val end = if (seal.toLocalDate() == draft.startedAt.toLocalDate()) seal.format(clockLabel)
                        else "${seal.format(shortDate)} ${seal.format(clockLabel)}"
                        "$end 깸"
                    }
                }
                RecordType.DIRECT_BREASTFEEDING -> draft.endedAt?.takeIf { it > draft.startedAt }?.let {
                    durationText(Duration.between(draft.startedAt, it).toMinutes())
                } ?: "종료 시각 미확인"
                else -> browseAmount(draft)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (draft.note.isNotBlank()) Text(draft.note, style = MaterialTheme.typography.bodySmall)
        }
        if (onEdit != null) TextButton(onClick = { onEdit(event.entry) }) { Text("수정") }
    }
}

private fun browsePresence(label: String, presence: Presence): String = when (presence) {
    Presence.YES -> "$label 있음"
    Presence.NO -> "$label 없음"
    Presence.UNKNOWN -> "$label 미확인"
}

private fun browseAmount(draft: EntryDraft): String =
    draft.amount?.let { "${draft.amountKind.label} ${formatAmount(it)} ${draft.type.amountUnit.orEmpty()}" }
        ?: "양 미입력"

@Composable
private fun BrowseAddEntry(onChoose: (EntryCategory) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("기록 추가") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EntryCategory.entries.forEach { category ->
                DropdownMenuItem(text = { Text(category.label) }, onClick = {
                    open = false
                    onChoose(category)
                })
            }
        }
    }
}

@Composable
internal fun CareAlbumScreen(
    child: Child,
    date: LocalDate,
    today: LocalDate,
    entries: List<CareEntry>,
    photos: List<DayPhoto>,
    onDateChange: (LocalDate) -> Unit,
    onChoosePhotos: () -> Unit,
    onReport: () -> Unit,
    onRemovePhoto: (DayPhoto) -> Unit,
    photoDates: Set<LocalDate> = photos.map { it.date }.toSet(),
    modifier: Modifier = Modifier,
    gallery: GalleryUiState = GalleryUiState(),
    onChooseReference: () -> Unit = {},
    onScanGallery: () -> Unit = {},
    onToggleAutomatic: (Boolean) -> Unit = {},
    onAllowGallery: () -> Unit = {},
    onReviewMedia: (GalleryMedia, Boolean) -> Unit = { _, _ -> },
    onClearReference: (() -> Unit)? = null,
) {
    val ownEntries = remember(entries, child.id) { entries.filter { it.childId == child.id } }
    val summary = remember(ownEntries, date) { summarizeDay(ownEntries, date) }
    val earliest = child.birthDate.coerceAtMost(today)
    val chooseDate = dateChooser(date, earliest, today, onDateChange)
    val dayPhotos = remember(photos, child.id, date) { photos.filter { it.childId == child.id && it.date == date } }
    val childGallery = remember(gallery.media, child.id) { gallery.media.filter { it.childId == child.id } }
    val dayGallery = remember(childGallery, date) { childGallery.filter { it.date == date } }
    val groupedMediaDates = remember(childGallery, photoDates) {
        val photoDays = photoDates.toMutableSet()
        val videoDays = mutableSetOf<LocalDate>()
        childGallery.forEach { item ->
            if (item.kind == GalleryMediaKind.PHOTO) photoDays.add(item.date) else videoDays.add(item.date)
        }
        photoDays to videoDays
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrowseHeading("사진 달력", date.format(monthLabel), chooseDate) }
        item {
            key(child.id) {
                GallerySetupCard(gallery, onChooseReference, onScanGallery, onToggleAutomatic, onAllowGallery, onReviewMedia, onClearReference)
            }
        }
        item { PhotoWeek(date, today, earliest, groupedMediaDates.first, groupedMediaDates.second, onDateChange) }
        if (dayGallery.isNotEmpty()) {
            item {
                key(child.id, date) {
                    GalleryMediaSection(dayGallery, featured = dayPhotos.isEmpty(), onExclude = { onReviewMedia(it, false) })
                }
            }
        }
        item {
            key(child.id, date) {
                PhotoDay(date, dayPhotos, onChoosePhotos, onRemovePhoto, showEmpty = dayGallery.isEmpty())
            }
        }
        item { AlbumSummary(summary) }
        item {
            Button(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                Text("이날 리포트 보기")
            }
        }
    }
}

@Composable
private fun BrowseHeading(title: String, dateText: String, onChooseDate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = onChooseDate) { Text(dateText, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun PhotoWeek(
    date: LocalDate,
    today: LocalDate,
    earliest: LocalDate,
    photoDates: Set<LocalDate>,
    videoDates: Set<LocalDate>,
    onDateChange: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val weeksBack = ChronoUnit.DAYS.between(date, today).coerceAtLeast(0) / 7
    val stripStart = today.minusDays(weeksBack * 7 + 6)
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(7) { index ->
                val candidate = stripStart.plusDays(index.toLong())
                val selected = candidate == date
                val enabled = candidate >= earliest
                val hasPhoto = candidate in photoDates
                val hasVideo = candidate in videoDates
                val tint = when {
                    selected -> colors.onPrimary
                    enabled -> colors.onSurface
                    else -> colors.onSurfaceVariant.copy(alpha = .55f)
                }
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 49.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) colors.primary else Color.Transparent)
                        .selectable(selected, enabled = enabled, role = Role.RadioButton) { onDateChange(candidate) }
                        .semantics {
                            val items = when {
                                hasPhoto && hasVideo -> ", 사진과 영상 있음"
                                hasPhoto -> ", 사진 있음"
                                hasVideo -> ", 영상 있음"
                                else -> ""
                            }
                            contentDescription = candidate.format(dayLabel) + items
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(candidate.format(weekDayLabel), style = MaterialTheme.typography.labelSmall, color = tint)
                    Text(candidate.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = tint)
                    Box(Modifier.size(3.dp).background(if (hasPhoto || hasVideo) tint else Color.Transparent, CircleShape))
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun PhotoDay(
    date: LocalDate,
    photos: List<DayPhoto>,
    onChoosePhotos: () -> Unit,
    onRemovePhoto: (DayPhoto) -> Unit,
    showEmpty: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var confirmId by rememberSaveable { mutableStateOf<Long?>(null) }
    val photoShape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (photos.isEmpty() && showEmpty) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(photoShape).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("이날 사진과 영상이 없어요.", color = colors.onSurfaceVariant)
            }
        } else if (photos.isNotEmpty()) {
            val pager = rememberPagerState(pageCount = { photos.size })
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(photoShape),
                key = { photos[it].id },
            ) { index ->
                DayPhotoImage(
                    photo = photos[index],
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = "${date.format(shortDate)} 사진 ${index + 1}",
                )
            }
            val active = photos.getOrNull(pager.currentPage) ?: photos.first()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (photos.size == 1) "사진 1장" else "${pager.currentPage.coerceAtMost(photos.lastIndex) + 1} / ${photos.size}장",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                TextButton(onClick = { confirmId = active.id }) { Text("사진 삭제") }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(date.format(dayLabel), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onChoosePhotos) { Text("사진 추가") }
        }
    }
    val confirmPhoto = photos.firstOrNull { it.id == confirmId }
    if (confirmPhoto != null) {
        AlertDialog(
            onDismissRequest = { confirmId = null },
            title = { Text("사진을 앨범에서 지울까요?") },
            text = { Text("이날 사진 달력에서 선택한 사진이 빠져요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmId = null
                    onRemovePhoto(confirmPhoto)
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmId = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun AlbumSummary(summary: DaySummary) {
    val units = summary.feedings.filter { it.knownConsumedCount > 0 }.groupBy { it.type.amountUnit.orEmpty() }
    val consumed = units.entries.joinToString(" · ") { (unit, totals) ->
        "${formatAmount(totals.sumOf { it.consumed })} $unit".trim()
    }
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AlbumMetric("먹은 양", consumed.ifBlank { if (summary.feedings.isEmpty()) "기록 없음" else "양 미확인" }, Modifier.weight(1f))
            AlbumMetric("기저귀 교체", "${summary.diaperCount}회", Modifier.weight(1f))
            AlbumMetric(
                "확인된 수면",
                if (summary.completedSleepCount > 0) durationText(summary.sleepMinutes) else "기록 없음",
                Modifier.weight(1f),
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun AlbumMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun DayPhotoImage(
    photo: DayPhoto,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = "선택한 날의 사진",
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { DayPhotoStore(context) }
    val image by produceState<ImageBitmap?>(null, photo) {
        value = null
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(store.fileFor(photo).absolutePath, options)?.asImageBitmap()
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription, modifier, contentScale = contentScale)
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("사진", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun minuteLabel(minute: Int): String = LocalTime.ofSecondOfDay(
    minute.coerceIn(0, MINUTES_PER_DAY - 1).toLong() * 60,
).format(clockLabel)
