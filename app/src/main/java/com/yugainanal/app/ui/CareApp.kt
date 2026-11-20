package com.yugainanal.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.Child
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordSource
import com.yugainanal.app.data.RecordType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
internal val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
internal val clockLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
private val lightColors = lightColorScheme(
    primary = Color(0xFF883A1D),
    onPrimary = Color(0xFFFFFAF4),
    primaryContainer = Color(0xFFF8DABE),
    onPrimaryContainer = Color(0xFF372014),
    secondary = Color(0xFF6C5145),
    secondaryContainer = Color(0xFFF4E6DA),
    onSecondaryContainer = Color(0xFF4C3225),
    tertiary = Color(0xFF4F6454),
    tertiaryContainer = Color(0xFFE6ECE2),
    onTertiaryContainer = Color(0xFF293C32),
    background = Color(0xFFFFF9F2),
    onBackground = Color(0xFF30221C),
    surface = Color(0xFFFFF9F2),
    onSurface = Color(0xFF30221C),
    surfaceVariant = Color(0xFFF7EBDF),
    onSurfaceVariant = Color(0xFF6C5145),
    surfaceContainer = Color(0xFFF8EBE0),
    surfaceContainerHigh = Color(0xFFF2E3D6),
    outline = Color(0xFF8B6C5A),
    outlineVariant = Color(0xFFE7D5C4),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFFFB998),
    onPrimary = Color(0xFF371A0C),
    primaryContainer = Color(0xFF4C2D1F),
    onPrimaryContainer = Color(0xFFFFDDCB),
    secondary = Color(0xFFD9C0B2),
    secondaryContainer = Color(0xFF3F2D25),
    onSecondaryContainer = Color(0xFFF6E2D6),
    tertiary = Color(0xFFAAC9B2),
    tertiaryContainer = Color(0xFF304632),
    onTertiaryContainer = Color(0xFFE0EDDC),
    background = Color(0xFF231B17),
    onBackground = Color(0xFFFFF0E7),
    surface = Color(0xFF231B17),
    onSurface = Color(0xFFFFF0E7),
    surfaceVariant = Color(0xFF3A2A23),
    onSurfaceVariant = Color(0xFFD9C0B2),
    surfaceContainer = Color(0xFF32241D),
    surfaceContainerHigh = Color(0xFF3F2E26),
    outline = Color(0xFFAA8A79),
    outlineVariant = Color(0xFF594238),
)

@Composable
fun CareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors,
        content = content,
    )
}

@Composable
internal fun AppName() {
    Text(
        "육아의나날",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun ChildSelector(
    child: Child,
    children: List<Child>,
    onSelectChild: (Long) -> Unit,
    onAddChild: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${child.name} · 아이 선택", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            children.forEach {
                DropdownMenuItem(
                    text = { Text(if (it.id == child.id) "${it.name} (선택됨)" else it.name) },
                    onClick = {
                        expanded = false
                        onSelectChild(it.id)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("아이 추가") },
                onClick = {
                    expanded = false
                    onAddChild()
                },
            )
        }
    }
}

@Composable
internal fun EntryCard(
    entry: CareEntry,
    date: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val draft = entry.draft
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 6.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                draft.startedAt.displayTime(date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 3.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(draft.type.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val detail = entryDetail(draft, date)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (draft.note.isNotBlank()) Text(draft.note, style = MaterialTheme.typography.bodySmall)
                if (!compact && entry.source == RecordSource.BABYTIME) {
                    Text(
                        "베이비타임",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("수정", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun entryDetail(record: EntryDraft, date: LocalDate): String = when (record.type) {
    RecordType.DIAPER -> listOf(
        presenceText("소변", record.urine),
        presenceText("대변", record.stool),
        record.stoolColor,
        record.stoolConsistency,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    RecordType.SLEEP -> record.endedAt?.let { "${it.displayTime(date)}에 깸" } ?: "종료 시각 미확인"
    RecordType.DIRECT_BREASTFEEDING -> record.endedAt?.let {
        durationText(ChronoUnit.MINUTES.between(record.startedAt, it))
    }.orEmpty()
    else -> record.amount?.let {
        "${record.amountKind.label} ${formatAmount(it)}${record.type.amountUnit.orEmpty()}"
    } ?: "양 미입력"
}

private fun presenceText(label: String, result: Presence): String = when (result) {
    Presence.YES -> "$label 있음"
    Presence.NO -> "$label 없음"
    Presence.UNKNOWN -> "$label 미확인"
}

private fun LocalDateTime.displayTime(date: LocalDate): String {
    val time = format(clockLabel)
    return if (toLocalDate() == date) time else "${format(shortDate)} $time"
}

internal fun formatAmount(amount: Double): String = if (amount.isFinite()) {
    BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()
} else {
    "범위 초과"
}

internal fun durationText(totalMinutes: Long): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    return if (minutes < 60) "${minutes}분" else if (minutes % 60 == 0L) {
        "${minutes / 60}시간"
    } else {
        "${minutes / 60}시간 ${minutes % 60}분"
    }
}

@Composable
internal fun dateChooser(
    selected: LocalDate,
    earliest: LocalDate?,
    latest: LocalDate,
    onSelect: (LocalDate) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    return {
        val zone = ZoneId.systemDefault()
        val initial = selected.coerceAtMost(latest).let { if (earliest != null) it.coerceAtLeast(earliest) else it }
        DatePickerDialog(
            context,
            { _, year, month, day -> onSelect(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).apply {
            datePicker.maxDate = latest.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            if (earliest != null) datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
        }.show()
    }
}
