package com.yugainanal.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.AmountKind
import com.yugainanal.app.data.CareEntry
import com.yugainanal.app.data.CareZone
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal enum class EntryCategory(val label: String, val initialType: RecordType) {
    FEEDING("수유·식사", RecordType.FORMULA),
    DIAPER("기저귀", RecordType.DIAPER),
    SLEEP("수면", RecordType.SLEEP);

    companion object {
        fun of(type: RecordType): EntryCategory = when (type) {
            RecordType.DIAPER -> DIAPER
            RecordType.SLEEP -> SLEEP
            else -> FEEDING
        }
    }
}

internal val AmountKind.label: String
    get() = when (this) {
        AmountKind.CONSUMED -> "먹은 양"
        AmountKind.OFFERED -> "준 양"
        AmountKind.UNKNOWN -> "용도 미확인"
    }

private val feedingTypes = listOf(
    RecordType.FORMULA,
    RecordType.SOLID_FOOD,
    RecordType.DIRECT_BREASTFEEDING,
    RecordType.EXPRESSED_MILK,
)
private val fullDate = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)
private val editableDate = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)

@Composable
internal fun ChildEditor(
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val date = birthday?.let(LocalDate::parse)
    val chooseBirthday = dateChooser(date ?: today, null, today) {
        birthday = it.toString()
        error = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("아이 등록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("아이 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = chooseBirthday, modifier = Modifier.fillMaxWidth()) {
                    Text(date?.format(fullDate) ?: "생년월일 선택")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = {
            TextButton(onClick = {
                val enteredName = name.trim()
                when {
                    enteredName.isEmpty() -> error = "아이 이름을 입력해 주세요."
                    enteredName.length > 50 -> error = "아이 이름은 50자까지 입력할 수 있어요."
                    date == null -> error = "생년월일을 선택해 주세요."
                    date > today -> error = "오늘 또는 이전 날짜를 선택해 주세요."
                    else -> onSave(enteredName, date)
                }
            }) { Text("등록") }
        },
    )
}

@Composable
internal fun EntryEditor(
    category: EntryCategory,
    entry: CareEntry?,
    initialDate: LocalDate,
    earliestDate: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (EntryDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val draft = entry?.draft
    val currentTime = LocalTime.now(CareZone).truncatedTo(ChronoUnit.MINUTES)
    var typeName by rememberSaveable(entry?.id, category) { mutableStateOf((draft?.type ?: category.initialType).name) }
    val type = RecordType.valueOf(typeName)
    var startText by rememberSaveable(entry?.id) {
        mutableStateOf((draft?.startedAt ?: initialDate.atTime(currentTime)).toString())
    }
    var endText by rememberSaveable(entry?.id) { mutableStateOf(draft?.endedAt?.toString()) }
    val start = LocalDateTime.parse(startText)
    val end = endText?.let(LocalDateTime::parse)
    var amountInput by rememberSaveable(entry?.id) { mutableStateOf(draft?.amount?.let(::formatAmount).orEmpty()) }
    var amountKindName by rememberSaveable(entry?.id) { mutableStateOf((draft?.amountKind ?: AmountKind.CONSUMED).name) }
    val amountKind = AmountKind.valueOf(amountKindName)
    var urineName by rememberSaveable(entry?.id) { mutableStateOf((draft?.urine ?: Presence.UNKNOWN).name) }
    var stoolName by rememberSaveable(entry?.id) { mutableStateOf((draft?.stool ?: Presence.UNKNOWN).name) }
    val urine = Presence.valueOf(urineName)
    val stool = Presence.valueOf(stoolName)
    var stoolColor by rememberSaveable(entry?.id) { mutableStateOf(draft?.stoolColor.orEmpty()) }
    var stoolConsistency by rememberSaveable(entry?.id) { mutableStateOf(draft?.stoolConsistency.orEmpty()) }
    var note by rememberSaveable(entry?.id) { mutableStateOf(draft?.note.orEmpty()) }
    var error by rememberSaveable(entry?.id) { mutableStateOf<String?>(null) }
    var deletePrompt by rememberSaveable(entry?.id) { mutableStateOf(false) }
    val hasAmount = type.amountUnit != null
    val hasEnd = type == RecordType.DIRECT_BREASTFEEDING || type == RecordType.SLEEP

    if (deletePrompt && onDelete != null) {
        AlertDialog(
            onDismissRequest = { deletePrompt = false },
            title = { Text("기록을 삭제할까요?") },
            text = { Text("이 기록이 시간순 목록과 집계에서 삭제돼요.") },
            dismissButton = { TextButton(onClick = { deletePrompt = false }) { Text("취소") } },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "${category.label} 기록" else "${category.label} 기록 수정") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (category == EntryCategory.FEEDING) {
                    feedingTypes.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { choice ->
                                FilterChip(
                                    selected = type == choice,
                                    onClick = {
                                        if (type != choice) {
                                            if (type.amountUnit != choice.amountUnit) {
                                                amountInput = ""
                                                amountKindName = AmountKind.CONSUMED.name
                                            }
                                            if (choice != RecordType.DIRECT_BREASTFEEDING) endText = null
                                            typeName = choice.name
                                        }
                                        error = null
                                    },
                                    label = { Text(choice.label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                DateTimeInput(
                    label = when (type) {
                        RecordType.SLEEP -> "잠든 시각"
                        RecordType.DIRECT_BREASTFEEDING -> "수유 시작"
                        else -> "기록 시각"
                    },
                    dateTime = start,
                    earliest = earliestDate,
                    today = today,
                    onChange = {
                        startText = it.toString()
                        error = null
                    },
                )
                if (hasEnd) {
                    if (end == null) {
                        OutlinedButton(onClick = {
                            val now = LocalDateTime.now(CareZone).truncatedTo(ChronoUnit.MINUTES)
                            val suggested = if (start.toLocalDate() < today) start.plusMinutes(30) else now
                            endText = maxOf(start, suggested).toString()
                            error = null
                        }) {
                            Text(if (type == RecordType.SLEEP) "깬 시각 추가" else "수유 종료 시각 추가")
                        }
                        Text(
                            if (type == RecordType.SLEEP) "종료 시각은 나중에 기록해도 돼요." else "시작 시각만 기록해도 돼요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        DateTimeInput(
                            label = if (type == RecordType.SLEEP) "깬 시각" else "수유 종료",
                            dateTime = end,
                            earliest = start.toLocalDate(),
                            today = today,
                            onChange = {
                                endText = it.toString()
                                error = null
                            },
                        )
                        TextButton(onClick = {
                            endText = null
                            error = null
                        }) { Text("종료 시각 지우기") }
                    }
                }
                if (hasAmount) {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = {
                            amountInput = it
                            error = null
                        },
                        label = { Text("양 (${type.amountUnit.orEmpty()}, 선택)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("양의 의미", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        AmountKind.entries.forEach { choice ->
                            FilterChip(
                                selected = amountKind == choice,
                                onClick = { amountKindName = choice.name },
                                label = { Text(if (choice == AmountKind.UNKNOWN) "모름" else choice.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (type == RecordType.DIAPER) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            Triple("소변만", Presence.YES, Presence.NO),
                            Triple("대변만", Presence.NO, Presence.YES),
                            Triple("둘 다", Presence.YES, Presence.YES),
                        ).forEach { (label, urineValue, stoolValue) ->
                            FilterChip(
                                selected = urine == urineValue && stool == stoolValue,
                                onClick = {
                                    urineName = urineValue.name
                                    stoolName = stoolValue.name
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    PresencePicker("소변", urine) { urineName = it.name }
                    PresencePicker("대변", stool) { stoolName = it.name }
                    if (stool == Presence.YES || (stool == Presence.UNKNOWN && (stoolColor.isNotEmpty() || stoolConsistency.isNotEmpty()))) {
                        OutlinedTextField(
                            value = stoolColor,
                            onValueChange = { stoolColor = it },
                            label = { Text("대변 색 (선택)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = stoolConsistency,
                            onValueChange = { stoolConsistency = it },
                            label = { Text("대변 상태 (선택)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("메모 (선택)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (onDelete != null) {
                    TextButton(onClick = { deletePrompt = true }) {
                        Text("기록 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = {
            TextButton(onClick = {
                val enteredAmount = if (hasAmount) amountInput.trim().takeIf { it.isNotEmpty() } else null
                val amount = enteredAmount?.toDoubleOrNull()
                val validEnd = if (hasEnd) end else null
                val now = LocalDateTime.now(CareZone)
                error = when {
                    start.toLocalDate() < earliestDate -> "아이 생년월일 이후의 날짜를 선택해 주세요."
                    start > now || (validEnd != null && validEnd > now) -> "지금까지의 시각을 선택해 주세요."
                    validEnd != null && validEnd <= start -> "끝난 시각은 시작한 시각보다 뒤여야 해요."
                    enteredAmount != null && (amount == null || !amount.isFinite() || amount < 0) -> "양을 0 이상의 숫자로 입력해 주세요."
                    else -> null
                }
                if (error == null) {
                    onSave(
                        EntryDraft(
                            type = type,
                            startedAt = start,
                            endedAt = validEnd,
                            amount = amount,
                            amountKind = if (amount == null) AmountKind.UNKNOWN else amountKind,
                            urine = urine,
                            stool = stool,
                            note = note.trim(),
                            stoolColor = if (stool == Presence.NO) "" else stoolColor.trim(),
                            stoolConsistency = if (stool == Presence.NO) "" else stoolConsistency.trim(),
                        ),
                    )
                }
            }) { Text("저장") }
        },
    )
}

@Composable
private fun PresencePicker(label: String, value: Presence, onChange: (Presence) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(Presence.YES to "있음", Presence.NO to "없음", Presence.UNKNOWN to "모름").forEach { (option, title) ->
            FilterChip(
                selected = value == option,
                onClick = { onChange(option) },
                label = { Text(title) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "$label $title" },
            )
        }
    }
}

@Composable
private fun DateTimeInput(
    label: String,
    dateTime: LocalDateTime,
    earliest: LocalDate,
    today: LocalDate,
    onChange: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    val chooseDate = dateChooser(dateTime.toLocalDate(), earliest, today) {
        onChange(LocalDateTime.of(it, dateTime.toLocalTime()))
    }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        val dateLabel = dateTime.format(editableDate)
        val timeLabel = dateTime.format(clockLabel)
        OutlinedButton(
            onClick = chooseDate,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label 날짜 $dateLabel" },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        ) { Text(dateLabel) }
        OutlinedButton(
            onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onChange(dateTime.toLocalDate().atTime(hour, minute)) },
                    dateTime.hour,
                    dateTime.minute,
                    true,
                ).show()
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label $timeLabel" },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        ) { Text(timeLabel) }
    }
}
