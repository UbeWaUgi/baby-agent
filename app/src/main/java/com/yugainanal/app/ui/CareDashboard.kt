package com.yugainanal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.DaySummary
import com.yugainanal.app.data.entriesForDay
import com.yugainanal.app.data.summarizeDay
import java.time.LocalDate

internal enum class CareTab(val label: String) {
    HOME("홈"), TRACK("하루 트랙"), ALBUM("사진 달력"), REPORT("리포트")
}

@Composable
internal fun CareTopBar(
    child: Child,
    children: List<Child>,
    onSelectChild: (Long) -> Unit,
    onAddChild: () -> Unit,
    onImport: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppName()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                ChildSelector(child, children, onSelectChild, onAddChild)
            }
            TextButton(
                onClick = onImport,
                modifier = Modifier.semantics { contentDescription = "베이비타임 가져오기" },
            ) { Text("가져오기") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun CareBottomTabs(selected: CareTab, onSelect: (CareTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().background(colors.surface).selectableGroup().padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CareTab.entries.forEach { tab ->
                val isSelected = selected == tab
                val color = if (isSelected) colors.primary else colors.onSurfaceVariant
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) colors.surfaceVariant else colors.surface)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CareTabIcon(tab, color)
                    Text(tab.label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CareTabIcon(tab: CareTab, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(19.dp)) {
        val unit = size.width / 20
        val line = 1.55.dp.toPx()
        fun at(x: Int, y: Int) = Offset(x * unit, y * unit)
        fun rule(x: Int, y: Int, toX: Int, toY: Int) =
            drawLine(color, at(x, y), at(toX, toY), line, StrokeCap.Round)
        when (tab) {
            CareTab.HOME -> drawPath(
                Path().apply {
                    moveTo(3 * unit, 9 * unit)
                    lineTo(10 * unit, 3 * unit)
                    lineTo(17 * unit, 9 * unit)
                    lineTo(17 * unit, 17 * unit)
                    lineTo(3 * unit, 17 * unit)
                    close()
                },
                color,
                style = Stroke(line),
            )
            CareTab.TRACK -> {
                rule(4, 4, 4, 16)
                for (y in listOf(5, 10, 15)) {
                    drawCircle(color, 1.55f * unit, at(4, y))
                    rule(8, y, 17, y)
                }
            }
            CareTab.ALBUM, CareTab.REPORT -> {
                drawRoundRect(color, at(3, 3), Size(14 * unit, 14 * unit), CornerRadius(2 * unit), style = Stroke(line))
                if (tab == CareTab.ALBUM) {
                    drawCircle(color, 1.25f * unit, at(7, 7))
                    rule(4, 15, 11, 9)
                    rule(11, 9, 16, 14)
                } else {
                    rule(6, 7, 14, 7)
                    rule(6, 10, 14, 10)
                    rule(6, 13, 11, 13)
                }
            }
        }
    }
}

@Composable
internal fun CareRegistration(onRegister: () -> Unit) {
    Column(Modifier.fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.Center) {
        AppName()
        Text("아이를 등록해 주세요", Modifier.padding(top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineMedium)
        Text("이름과 생년월일을 등록하면 수유, 기저귀, 수면을 날짜별로 볼 수 있어요.")
        Button(onClick = onRegister, modifier = Modifier.padding(top = 18.dp)) { Text("아이 등록") }
    }
}

@Composable
internal fun CareQuickHomeScreen(
    today: LocalDate,
    entries: List<CareEntry>,
    onAddEntry: (EntryCategory) -> Unit,
    onEditEntry: (CareEntry) -> Unit,
    onTrack: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayEntries = remember(entries, today) { entriesForDay(entries, today) }
    val summary = remember(entries, today) { summarizeDay(entries, today) }
    val lastFeeding = dayEntries.firstOrNull { EntryCategory.of(it.draft.type) == EntryCategory.FEEDING }
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘의 기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(today.format(shortDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { LastFeeding(lastFeeding, today) }
            item { HomeTotals(summary) }
            item {
                Text("최근 기록", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (dayEntries.isEmpty()) {
                    Text("오늘 기록이 아직 없어요.", Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(dayEntries.take(3), key = { it.id }) { entry -> EntryCard(entry, today, { onEditEntry(entry) }, compact = true) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTrack, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("하루 트랙 보기") }
                    OutlinedButton(onClick = onReport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("오늘 리포트") }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("빠른 기록", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EntryCategory.entries.forEach { category ->
                    val action = Modifier.weight(1f).heightIn(min = 48.dp)
                    val padding = PaddingValues(horizontal = 4.dp)
                    val shape = RoundedCornerShape(11.dp)
                    val label = category.label
                    if (category == EntryCategory.FEEDING) {
                        Button(onClick = { onAddEntry(category) }, modifier = action, shape = shape, contentPadding = padding) {
                            Text("+ $label")
                        }
                    } else {
                        OutlinedButton(onClick = { onAddEntry(category) }, modifier = action, shape = shape, contentPadding = padding) {
                            Text("+ $label")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastFeeding(entry: CareEntry?, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 5.dp))
            .background(colors.primaryContainer)
            .drawBehind {
                drawCircle(
                    colors.primary.copy(alpha = .14f),
                    radius = 54.dp.toPx(),
                    center = Offset(size.width - 4.dp.toPx(), -4.dp.toPx()),
                    style = Stroke(width = 17.dp.toPx()),
                )
            }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("마지막 수유·식사", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
        if (entry == null) {
            Text("오늘 먹은 기록이 없어요", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.draft.startedAt.format(clockLabel), color = colors.onPrimaryContainer, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOf(entry.draft.type.label, entryDetail(entry.draft, today)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = colors.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun HomeTotals(summary: DaySummary) {
    val consumed = summary.feedings.filter { it.knownConsumedCount > 0 }.groupBy { it.type.amountUnit }
    val measured = consumed.map { (unit, totals) -> "${formatAmount(totals.sumOf { it.consumed })} ${unit.orEmpty()}" }.joinToString(" · ")
    val feeding = measured.ifBlank { if (summary.feedings.isEmpty()) "기록 없음" else "${summary.feedings.sumOf { it.count }}회" }
    val sleep = if (summary.completedSleepCount > 0) durationText(summary.sleepMinutes) else if (summary.sleepHasUnconfirmed) "종료 미확인" else "기록 없음"
    val diaper = if (summary.diaperCount > 0) "${summary.diaperCount}회" else "기록 없음"
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("먹은 양" to feeding, "기저귀" to diaper, "확인된 수면" to sleep).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
