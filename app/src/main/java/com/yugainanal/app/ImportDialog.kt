package com.yugainanal.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugainanal.app.data.AmountKind
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.ImportDecision
import com.yugainanal.app.data.ImportItem
import java.time.format.DateTimeFormatter

private val previewDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private enum class ImportChoiceType { ADD, LINK, SKIP }

private data class ImportChoice(val type: ImportChoiceType, val entryId: Long? = null)

@Composable
fun ImportBusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("BabyTime 가져오기") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(message)
            }
        },
        confirmButton = {},
    )
}

@Composable
fun ImportReviewDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onApply: (List<ImportDecision>) -> Unit,
) {
    val preview = pending.preview
    val choices = remember(pending) { mutableStateMapOf<Int, ImportChoice>() }
    val ambiguous = remember(pending) { preview.items.filter { it.possibleDuplicates.isNotEmpty() } }
    val newItems = remember(pending) { preview.items.filter { it.possibleDuplicates.isEmpty() } }
    val unresolved = ambiguous.count { !choices.containsKey(it.index) }
    val period = remember(pending) {
        val dates = pending.file.records.map { it.draft.startedAt.toLocalDate() }
        val first = dates.minOrNull()
        val last = dates.maxOrNull()
        if (first == last) first?.toString().orEmpty() else "$first ~ $last"
    }
    val canApply = preview.items.isNotEmpty() && unresolved == 0 && !preview.alreadyImported

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BabyTime 가져오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${pending.child.name} 기록으로 가져와요", style = MaterialTheme.typography.titleSmall)
                Text(pending.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (period.isNotEmpty()) Text(period, style = MaterialTheme.typography.bodySmall)
                if (preview.alreadyImported) {
                    Text("이 아이에게 이미 가져온 파일이에요.")
                } else {
                    Text("새 기록 ${newItems.size}건 · 비교할 기록 ${ambiguous.size}건 · 기존 기록 ${preview.duplicateCount}건")
                    if (preview.items.isEmpty()) Text("추가할 새 기록이 없어요.")
                    if (pending.file.ignoredRows > 0) {
                        Text("읽을 수 없거나 지원하지 않는 기록 ${pending.file.ignoredRows}건을 건너뛰어요.")
                    }
                    pending.file.warnings.take(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (unresolved > 0) Text("비교할 기록 ${unresolved}건을 확인해 주세요.")
                    if (ambiguous.isNotEmpty()) {
                        Text("건너뛴 항목은 이 파일을 다시 열어도 추가되지 않아요.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.items.isNotEmpty()) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            items(ambiguous, key = { it.index }) { item ->
                                DuplicateChoice(item, choices[item.index]) { choices[item.index] = it }
                                HorizontalDivider()
                            }
                            items(newItems, key = { it.index }) { item ->
                                Text(describe(item.draft), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (!preview.alreadyImported && preview.items.isNotEmpty()) TextButton(onClick = onDismiss) { Text("취소") }
        },
        confirmButton = {
            if (preview.alreadyImported || preview.items.isEmpty()) {
                TextButton(onClick = onDismiss) { Text("닫기") }
            } else {
                TextButton(enabled = canApply, onClick = {
                    val newDecisions = newItems.map { ImportDecision(it.index) }
                    val resolved = ambiguous.mapNotNull { item ->
                        val choice = choices.getValue(item.index)
                        if (choice.type == ImportChoiceType.SKIP) null
                        else ImportDecision(item.index, choice.entryId)
                    }
                    onApply(newDecisions + resolved)
                }) { Text("가져오기") }
            }
        },
    )
}

@Composable
private fun DuplicateChoice(item: ImportItem, selected: ImportChoice?, onSelect: (ImportChoice) -> Unit) {
    Text(describe(item.draft), style = MaterialTheme.typography.titleSmall)
    for (candidate in item.possibleDuplicates) {
        val choice = ImportChoice(ImportChoiceType.LINK, candidate.id)
        ChoiceRow(
            label = "기존 기록과 연결 · ${describe(candidate.draft)}",
            selected = selected == choice,
            onClick = { onSelect(choice) },
        )
    }
    val separate = ImportChoice(ImportChoiceType.ADD)
    val skip = ImportChoice(ImportChoiceType.SKIP)
    if (!item.alreadyLinked) {
        ChoiceRow("별도 기록 추가", selected == separate) { onSelect(separate) }
    }
    ChoiceRow("이 항목 건너뛰기", selected == skip) { onSelect(skip) }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun describe(draft: EntryDraft): String = buildString {
    append("${draft.startedAt.format(previewDateTime)} · ${draft.type.label}")
    val amount = draft.amount
    val unit = draft.type.amountUnit
    if (amount != null && unit != null) {
        val label = when (draft.amountKind) {
            AmountKind.CONSUMED -> "먹은 양"
            AmountKind.OFFERED -> "준 양"
            AmountKind.UNKNOWN -> "양의 의미 미확인"
        }
        append(" · $label ${amount.toBigDecimal().stripTrailingZeros().toPlainString()}$unit")
    }
    if (draft.endedAt != null) append(" ~ ${draft.endedAt.format(previewDateTime)}")
}
