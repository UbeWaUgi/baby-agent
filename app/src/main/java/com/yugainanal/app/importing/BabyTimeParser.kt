package com.yugainanal.app.importing

import com.yugainanal.app.data.AmountKind
import com.yugainanal.app.data.EntryDraft
import com.yugainanal.app.data.ImportFile
import com.yugainanal.app.data.ImportRecord
import com.yugainanal.app.data.Presence
import com.yugainanal.app.data.RecordType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

/**
 * Reads the date header and key-value activity blocks in BabyTime TXT exports.
 * The public babyTimeR package documents the TXT and ZIP layouts.
 */
object BabyTimeParser {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 12 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 64
    private const val MAX_BLOCKS = 10_000
    private const val MAX_BLOCK_CHARS = 32_768
    private const val MAX_WARNINGS = 12

    private val archiveActivity = Regex("""^activity_(.+)_([0-9]{6})\.txt$""", RegexOption.IGNORE_CASE)
    private val dateAndTime = Regex(
        """^(\d{4})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})[.일]?(?:\s+|T)(?:(AM|PM|오전|오후)\s*)?(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(AM|PM|오전|오후))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val dateRange = Regex("""\s*[~〜∼]\s*""")
    private val field = Regex("""^([^:：]+)[:：]\s*(.*)$""")
    private val quantity = Regex("""^(\d+(?:\.\d+|,\d{1,2})?)\s*(?:\(\s*([^()]+?)\s*\)|([^()]+))?$""")
    private val unitInKey = Regex("""\(([^()]+)\)\s*$""")
    private val spaces = Regex("""\s+""")
    private val typeKeys = setOf("type", "kind", "activity", "활동", "활동 종류", "종류", "유형")
    private val memoKeys = setOf("memo", "note", "notes", "메모", "비고", "특이사항")
    private val diaperKeys = setOf("diaper type", "diaper", "diaper content", "기저귀 종류", "기저귀 유형", "배변 종류", "기저귀")
    private val durationKeys = setOf("duration", "time spent", "시간", "소요 시간", "지속 시간")
    private val colorKeys = setOf("color", "colour", "stool color", "poop color", "대변 색", "대변 색깔", "색", "색깔")
    private val consistencyKeys = setOf("consistency", "stool consistency", "poop consistency", "대변 굳기", "대변 상태", "굳기", "형태")
    private val foodNameKeys = setOf("food", "food type", "food name", "음식", "이유식 종류", "음식 종류")
    private val consumedKeys = setOf("consumed", "consumed amount", "amount consumed", "actual amount", "섭취량", "실제 섭취량", "먹은 양")
    private val offeredKeys = setOf("offered", "offered amount", "amount offered", "provided amount", "제공량", "준 양")
    private val genericAmountKeys = setOf(
        "amount", "total amount", "formula amount", "formula total amount", "pumped milk amount",
        "pumped milk total amount", "baby food amount", "food amount", "양", "총량", "분유량",
        "분유 총량", "분유 수유량", "유축유량", "유축수유량", "유축수유 총량", "이유식량", "수유량",
    )
    private val urineTypes = setOf("pee", "urine", "wet", "wet diaper", "소변", "쉬", "오줌")
    private val stoolTypes = setOf("poop", "poo", "stool", "dirty", "dirty diaper", "대변", "똥")
    private val bothTypes = setOf(
        "both", "pee and poop", "pee & poop", "pee + poop", "pee / poop", "pee/poop", "wet and dirty",
        "소변 대변", "대소변", "소변+대변", "소변 + 대변", "소변·대변", "소변과 대변", "둘 다", "둘다",
    )

    fun parse(bytes: ByteArray): ImportFile {
        require(bytes.isNotEmpty()) { "파일이 비어 있어요." }
        require(bytes.size <= MAX_FILE_BYTES) { "4MB 이하의 베이비타임 파일을 선택해 주세요." }
        val result = ParseResult()
        if (isZip(bytes)) {
            readArchive(bytes, result)
        } else {
            readBlocks(decode(bytes), result)
        }
        require(result.blocks > 0) { "베이비타임 활동 기록이 들어 있는 TXT 또는 ZIP 파일을 선택해 주세요." }
        return ImportFile(
            sha256 = sha256(bytes),
            records = result.records.toList(),
            ignoredRows = result.ignored,
            warnings = result.warnings.toList(),
        )
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
                (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()))

    private fun readArchive(bytes: ByteArray, result: ParseResult) {
        var expanded = 0
        var entries = 0
        var textFiles = 0
        var exportChild: String? = null
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ZIP_ENTRIES) { "압축 파일에 항목이 너무 많아요." }
                    val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val match = archiveActivity.matchEntire(fileName)
                    val document = if (!entry.isDirectory && match != null) ByteArrayOutputStream() else null
                    if (document != null && match != null) {
                        val candidateChild = normalize(match.groupValues[1])
                        require(exportChild == null || exportChild == candidateChild) {
                            "아이마다 별도의 베이비타임 파일을 선택해 주세요."
                        }
                        exportChild = candidateChild
                    }
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        require(expanded <= MAX_EXPANDED_BYTES) { "압축을 푼 기록의 용량이 너무 커요." }
                        document?.write(buffer, 0, count)
                    }
                    if (document != null) {
                        textFiles += 1
                        readBlocks(decode(document.toByteArray()), result)
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("압축 파일을 읽을 수 없어요. 베이비타임에서 다시 내보내 주세요.", error)
        }
        require(textFiles > 0) { "압축 안에서 베이비타임 활동 TXT 파일을 찾지 못했어요." }
    }

    private fun decode(bytes: ByteArray): String {
        val charsetCandidates = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> listOf(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> listOf(Charsets.UTF_16BE)
            else -> listOf(Charsets.UTF_8) + listOf("x-windows-949", "EUC-KR").mapNotNull { name ->
                runCatching { Charset.forName(name) }.getOrNull()
            }
        }
        for (charset in charsetCandidates) {
            val text = try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) {
                continue
            }
            if (text.none { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }) return text
        }
        throw IllegalArgumentException("텍스트 파일의 글자 형식을 읽을 수 없어요.")
    }

    private fun readBlocks(text: String, result: ParseResult) {
        val lines = mutableListOf<String>()
        var blockSize = 0

        fun finishBlock() {
            if (lines.isEmpty()) return
            result.blocks += 1
            require(result.blocks <= MAX_BLOCKS) { "기록이 너무 많아요. 기간을 나누어 내보내 주세요." }
            readBlock(lines, result)
            lines.clear()
            blockSize = 0
        }

        for (line in text.lineSequence()) {
            if (line.trim() == "====================") {
                finishBlock()
            } else if (lines.isNotEmpty() || line.isNotBlank()) {
                blockSize += line.length + 1
                require(blockSize <= MAX_BLOCK_CHARS) { "한 활동 기록의 길이가 너무 길어요." }
                lines += line
            }
        }
        finishBlock()
    }

    private fun readBlock(lines: List<String>, result: ParseResult) {
        val timestamps = normalize(lines.first()).split(dateRange, limit = 2)
        val start = parseTime(timestamps.first())
        if (start == null) {
            result.ignore("시작 시각을 읽을 수 없는 기록이 있어요.")
            return
        }
        val fields = mutableListOf<Field>()
        var memo = ""
        for (index in 1 until lines.size) {
            val match = field.matchEntire(lines[index].trim()) ?: continue
            val rawKey = match.groupValues[1].trim()
            val key = normalize(rawKey)
            val value = match.groupValues[2].trim()
            if (key in memoKeys) {
                memo = (listOf(value) + lines.drop(index + 1)).joinToString("\n").trim()
                break
            }
            fields += Field(rawKey, key, value)
        }

        val rawType = fields.valueFor(typeKeys)
        val type = mapType(rawType)
        if (type == null) {
            result.ignore(if (rawType.isEmpty()) "종류가 비어 있는 기록이 있어요." else "지원하지 않는 활동 기록을 건너뛰었어요.")
            return
        }
        val hasDuration = type == RecordType.SLEEP || type == RecordType.DIRECT_BREASTFEEDING
        val end = if (timestamps.size == 2) {
            parseTime(timestamps[1])?.takeIf { it.isAfter(start) }.also {
                if (it == null && hasDuration) result.warn("종료 시각을 확인할 수 없는 기록이 있어요.")
            }
        } else if (hasDuration) {
            val duration = fields.valueFor(durationKeys)
            durationSeconds(duration)?.let { start.plusSeconds(it) }.also {
                if (it == null && duration.isNotEmpty()) result.warn("기록된 길이를 읽을 수 없는 활동이 있어요.")
            }
        } else {
            null
        }

        val noteParts = mutableListOf<String>()
        if (memo.isNotBlank()) noteParts += memo
        val amountField = when (type) {
            RecordType.FORMULA, RecordType.EXPRESSED_MILK, RecordType.SOLID_FOOD -> findAmount(fields, type)
            else -> null
        }
        val amount = amountField?.let { parseAmount(it, type) }
        val amountKind = when (withoutUnit(amountField?.key.orEmpty())) {
            in consumedKeys -> AmountKind.CONSUMED
            in offeredKeys -> AmountKind.OFFERED
            else -> AmountKind.UNKNOWN
        }
        if (type == RecordType.SOLID_FOOD) {
            val foodName = fields.valueFor(foodNameKeys).ifEmpty {
                amountField?.rawKey?.takeIf { isFoodName(amountField) }.orEmpty()
            }
            if (foodName.isNotBlank()) noteParts += "음식: $foodName"
        }
        if (amountField != null && amount == null && amountField.value.isNotEmpty() &&
            normalize(amountField.value) !in setOf("none", "none (none)", "-", "미입력")
        ) {
            noteParts += "원본 양: " + amountField.value
            result.warn("단위가 명확하지 않은 양은 원본 메모에 남겼어요.")
        }
        if (amountKind == AmountKind.CONSUMED) {
            val offered = fields.firstOrNull { withoutUnit(it.key) in offeredKeys }
            if (offered != null && offered.value.isNotBlank()) {
                noteParts += "원본 제공량: " + offered.value
            }
        }

        var urine = Presence.UNKNOWN
        var stool = Presence.UNKNOWN
        if (type == RecordType.DIAPER) {
            val diaper = fields.valueFor(diaperKeys)
            val detail = diaperContents(diaper)
            val activity = diaperContents(rawType)
            if (diaper.isNotBlank() && detail == null) {
                noteParts += "원본 기저귀 종류: $diaper"
                result.warn("확인할 수 없는 기저귀 상세 기록이 있어요.")
            }
            if (detail != null && activity != null && detail != activity && detail != DiaperContents.BOTH) {
                noteParts += "원본 기저귀 기록: $rawType / $diaper"
                result.warn("기저귀 종류가 서로 다르게 표시된 기록이 있어요. 상세 표시를 가져왔어요.")
            }
            when (detail ?: activity) {
                DiaperContents.URINE -> urine = Presence.YES
                DiaperContents.STOOL -> stool = Presence.YES
                DiaperContents.BOTH -> {
                    urine = Presence.YES
                    stool = Presence.YES
                }
                null -> Unit
            }
        }
        val draft = EntryDraft(
            type = type,
            startedAt = start,
            endedAt = end,
            amount = amount,
            amountKind = amountKind,
            urine = urine,
            stool = stool,
            note = noteParts.joinToString("\n"),
            stoolColor = if (type == RecordType.DIAPER) fields.valueFor(colorKeys) else "",
            stoolConsistency = if (type == RecordType.DIAPER) fields.valueFor(consistencyKeys) else "",
        )
        val normalizedBlock = lines.joinToString("\n") { it.trimEnd() }.trimEnd()
        result.records += ImportRecord(draft, sha256(normalizedBlock.toByteArray(Charsets.UTF_8)))
    }

    private fun mapType(value: String): RecordType? {
        val label = normalize(value).substringBefore(" (").trim()
        return when (label) {
            "breastfeeding", "breast feeding", "nursing", "direct breastfeeding", "모유", "모유수유", "모유 수유", "직수" ->
                RecordType.DIRECT_BREASTFEEDING
            "pumped milk", "expressed milk", "expressed breast milk", "유축유", "유축수유", "유축 수유", "유축 모유 수유" ->
                RecordType.EXPRESSED_MILK
            "formula", "formula feeding", "infant formula", "분유", "분유수유", "분유 수유" -> RecordType.FORMULA
            "baby food", "solid food", "solids", "이유식", "간식", "고형식", "식사" -> RecordType.SOLID_FOOD
            "sleep", "day sleep", "night sleep", "nap", "수면", "잠", "낮잠", "밤잠" -> RecordType.SLEEP
            "diaper", "diaper change", "기저귀", "기저귀 교체", "배변",
            in urineTypes, in stoolTypes, in bothTypes -> RecordType.DIAPER
            else -> null
        }
    }

    private fun diaperContents(value: String): DiaperContents? = when (normalize(value)) {
        in urineTypes -> DiaperContents.URINE
        in stoolTypes -> DiaperContents.STOOL
        in bothTypes -> DiaperContents.BOTH
        else -> null
    }

    private fun findAmount(fields: List<Field>, type: RecordType): Field? {
        for (keys in listOf(consumedKeys, offeredKeys, genericAmountKeys)) {
            val match = fields.firstOrNull { withoutUnit(it.key) in keys && it.value.isNotBlank() }
            if (match != null) return match
        }
        if (type != RecordType.SOLID_FOOD) return null
        return fields.firstOrNull {
            isFoodName(it) && (quantity.matches(normalize(it.value)) || normalize(it.value) == "none (none)")
        }
    }

    private fun isFoodName(value: Field): Boolean =
        withoutUnit(value.key) !in typeKeys + memoKeys + foodNameKeys + durationKeys + consumedKeys + offeredKeys + genericAmountKeys

    private fun withoutUnit(key: String): String = unitInKey.replace(key, "").trim()

    private fun parseAmount(amount: Field, type: RecordType): Double? {
        val value = quantity.matchEntire(normalize(amount.value)) ?: return null
        val number = value.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val explicitUnit = value.groupValues[2].ifEmpty { value.groupValues[3] }.trim()
        val unit = explicitUnit.ifEmpty { unitInKey.find(amount.key)?.groupValues?.get(1).orEmpty() }
            .replace(" ", "")
            .replace("_", "")
            .replace(".", "")
        val multiplier = if (type == RecordType.SOLID_FOOD) {
            when (unit) {
                "g", "gram", "grams", "그램" -> 1.0
                "kg", "kilogram", "kilograms", "킬로그램" -> 1_000.0
                else -> return null
            }
        } else {
            when (unit) {
                "ml", "milliliter", "milliliters", "millilitre", "cc", "밀리리터" -> 1.0
                "l", "liter", "liters", "litre", "리터" -> 1_000.0
                else -> return null
            }
        }
        return (number * multiplier).takeIf { it.isFinite() && it <= 100_000 }
    }

    private fun durationSeconds(value: String): Long? {
        val match = quantity.matchEntire(normalize(value)) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
        val multiplier = when (unit) {
            "h", "hr", "hour", "hours", "시간" -> 3_600
            "m", "min", "minute", "minutes", "분" -> 60
            "s", "sec", "second", "seconds", "초" -> 1
            else -> return null
        }
        val seconds = number * multiplier
        return seconds.takeIf { it in 1.0..172_800.0 }?.roundToLong()
    }

    private fun parseTime(value: String): LocalDateTime? {
        val match = dateAndTime.matchEntire(value) ?: return null
        val parts = match.groupValues
        if (parts[4].isNotEmpty() && parts[8].isNotEmpty()) return null
        val amPm = parts[4].ifEmpty { parts[8] }
        val clockHour = parts[5].toInt()
        val hour = if (amPm.isEmpty()) {
            clockHour
        } else {
            if (clockHour !in 1..12) return null
            clockHour % 12 + if (amPm == "pm" || amPm == "오후") 12 else 0
        }
        return try {
            LocalDateTime.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), hour, parts[6].toInt(), parts[7].toIntOrNull() ?: 0)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun List<Field>.valueFor(keys: Set<String>): String = firstOrNull { it.key in keys }?.value.orEmpty()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(spaces, " ")

    private fun sha256(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val number = byte.toInt() and 0xff
                append(hex[number ushr 4])
                append(hex[number and 0x0f])
            }
        }
    }

    private data class Field(val rawKey: String, val key: String, val value: String)
    private enum class DiaperContents { URINE, STOOL, BOTH }

    private class ParseResult {
        val records = mutableListOf<ImportRecord>()
        val warnings = linkedSetOf<String>()
        var blocks = 0
        var ignored = 0

        fun ignore(reason: String) {
            ignored += 1
            warn(reason)
        }

        fun warn(reason: String) {
            if (warnings.size < MAX_WARNINGS) warnings += reason
        }
    }
}
