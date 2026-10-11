package com.folderspan.editor

import com.folderspan.shared.generated.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings

enum class EditorTextEncoding {
    ASCII,
    UTF8,
    UTF16_LE,
    UTF16_BE,
    GBK,
    ISO_8859_1,
}

enum class EditorEncodingConfidence {
    High,
    Medium,
    Low,
}

data class EditorEncodingDetection(
    val encoding: EditorTextEncoding,
    val confidence: EditorEncodingConfidence,
    val hasBom: Boolean,
)

data class EditorDecodedText(
    val text: String,
    val replacementCount: Int = 0,
    val hadErrors: Boolean = replacementCount > 0,
)

data class EditorEncodedText(
    val bytes: ByteArray,
    val replacementCharacterIndices: List<Int> = emptyList(),
) {
    val replacementCount: Int
        get() = replacementCharacterIndices.size
}

data class EditorDecodedChunk(
    val text: String,
    val pendingByteCount: Int,
    val replacementCount: Int,
)

/**
 * Stateful decoder for independently loaded file blocks. Incomplete code units are retained until
 * the next block so a character crossing a read boundary is emitted exactly once.
 */
class EditorIncrementalTextDecoder internal constructor(
    private val codec: EditorTextCodec,
    private val encoding: EditorTextEncoding,
) {
    private var pending = byteArrayOf()

    fun accept(bytes: ByteArray): EditorDecodedChunk {
        val combined = pending + bytes
        val completedLength = completedPrefixLength(combined, encoding)
        val completed = combined.copyOfRange(0, completedLength)
        pending = combined.copyOfRange(completedLength, combined.size)
        val decoded = codec.decode(completed, encoding, stripBom = false)
        return EditorDecodedChunk(decoded.text, pending.size, decoded.replacementCount)
    }

    fun finish(): EditorDecodedChunk {
        val remaining = pending
        pending = byteArrayOf()
        val decoded = codec.decode(remaining, encoding, stripBom = false)
        return EditorDecodedChunk(decoded.text, 0, decoded.replacementCount)
    }
}

class EditorTextCodec private constructor(
    private val gbkDecodeTable: CharArray,
) {
    private val gbkEncodeTable: Map<Char, Int> by lazy {
        buildMap {
            gbkDecodeTable.forEachIndexed { index, char ->
                if (char != INVALID_GBK_CHAR && char !in this) put(char, index)
            }
        }
    }

    fun detect(bytes: ByteArray): EditorEncodingDetection {
        if (bytes.startsWith(UTF8_BOM)) {
            return EditorEncodingDetection(EditorTextEncoding.UTF8, EditorEncodingConfidence.High, true)
        }
        if (bytes.startsWith(UTF16_LE_BOM)) {
            return EditorEncodingDetection(EditorTextEncoding.UTF16_LE, EditorEncodingConfidence.High, true)
        }
        if (bytes.startsWith(UTF16_BE_BOM)) {
            return EditorEncodingDetection(EditorTextEncoding.UTF16_BE, EditorEncodingConfidence.High, true)
        }
        if (runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isSuccess) {
            if (bytes.any { it == 0.toByte() }) {
                detectUtf16(bytes)?.let { return it }
            }
            if (bytes.all { (it.toInt() and 0xFF) < 0x80 }) {
                return EditorEncodingDetection(EditorTextEncoding.ASCII, EditorEncodingConfidence.High, false)
            }
            return EditorEncodingDetection(EditorTextEncoding.UTF8, EditorEncodingConfidence.High, false)
        }

        val incompleteUtf8Suffix = incompleteUtf8TailLength(bytes)
        if (
            incompleteUtf8Suffix > 0 &&
            runCatching {
                bytes.copyOfRange(0, bytes.size - incompleteUtf8Suffix)
                    .decodeToString(throwOnInvalidSequence = true)
            }.isSuccess
        ) {
            return EditorEncodingDetection(EditorTextEncoding.UTF8, EditorEncodingConfidence.Medium, false)
        }

        detectUtf16(bytes)?.let { return it }
        if (isValidGbk(bytes)) {
            return EditorEncodingDetection(EditorTextEncoding.GBK, EditorEncodingConfidence.Medium, false)
        }
        return EditorEncodingDetection(EditorTextEncoding.ISO_8859_1, EditorEncodingConfidence.Low, false)
    }

    fun decode(
        bytes: ByteArray,
        encoding: EditorTextEncoding,
        stripBom: Boolean = true,
    ): EditorDecodedText {
        val content = if (stripBom) bytes.withoutBom(encoding) else bytes
        return when (encoding) {
            EditorTextEncoding.ASCII -> decodeSingleByte(content, maxValue = 0x7F)
            EditorTextEncoding.UTF8 -> decodeUtf8(content)
            EditorTextEncoding.UTF16_LE -> decodeUtf16(content, littleEndian = true)
            EditorTextEncoding.UTF16_BE -> decodeUtf16(content, littleEndian = false)
            EditorTextEncoding.GBK -> decodeGbk(content)
            EditorTextEncoding.ISO_8859_1 -> EditorDecodedText(
                text = buildString(content.size) {
                    content.forEach { byte -> append((byte.toInt() and 0xFF).toChar()) }
                }
            )
        }
    }

    fun encode(
        text: String,
        encoding: EditorTextEncoding,
        includeBom: Boolean = false,
    ): EditorEncodedText {
        val encoded = when (encoding) {
            EditorTextEncoding.ASCII -> encodeSingleByte(text, maxValue = 0x7F)
            EditorTextEncoding.UTF8 -> EditorEncodedText(text.encodeToByteArray())
            EditorTextEncoding.UTF16_LE -> encodeUtf16(text, littleEndian = true)
            EditorTextEncoding.UTF16_BE -> encodeUtf16(text, littleEndian = false)
            EditorTextEncoding.GBK -> encodeGbk(text)
            EditorTextEncoding.ISO_8859_1 -> encodeSingleByte(text, maxValue = 0xFF)
        }
        if (!includeBom) return encoded
        val bom = when (encoding) {
            EditorTextEncoding.UTF8 -> UTF8_BOM
            EditorTextEncoding.UTF16_LE -> UTF16_LE_BOM
            EditorTextEncoding.UTF16_BE -> UTF16_BE_BOM
            else -> byteArrayOf()
        }
        return encoded.copy(bytes = bom + encoded.bytes)
    }

    fun incrementalDecoder(encoding: EditorTextEncoding): EditorIncrementalTextDecoder =
        EditorIncrementalTextDecoder(this, encoding)

    private fun decodeSingleByte(bytes: ByteArray, maxValue: Int): EditorDecodedText {
        var replacements = 0
        val text = buildString(bytes.size) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                if (value <= maxValue) append(value.toChar()) else {
                    append(REPLACEMENT_CHAR)
                    replacements += 1
                }
            }
        }
        return EditorDecodedText(text, replacements)
    }

    private fun decodeUtf8(bytes: ByteArray): EditorDecodedText {
        val strict = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        if (strict != null) return EditorDecodedText(strict)
        val lenient = bytes.decodeToString()
        return EditorDecodedText(lenient, lenient.count { it == REPLACEMENT_CHAR })
    }

    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): EditorDecodedText {
        val text = StringBuilder(bytes.size / 2)
        var replacements = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val second = bytes[index + 1].toInt() and 0xFF
            val code = if (littleEndian) first or (second shl 8) else (first shl 8) or second
            text.append(code.toChar())
            index += 2
        }
        if (index < bytes.size) {
            text.append(REPLACEMENT_CHAR)
            replacements += 1
        }
        val value = text.toString()
        var charIndex = 0
        while (charIndex < value.length) {
            val char = value[charIndex]
            when {
                char.isHighSurrogate() && charIndex + 1 < value.length && value[charIndex + 1].isLowSurrogate() ->
                    charIndex += 2
                char.isSurrogate() -> {
                    replacements += 1
                    charIndex += 1
                }
                else -> charIndex += 1
            }
        }
        return EditorDecodedText(value, replacements)
    }

    private fun decodeGbk(bytes: ByteArray): EditorDecodedText {
        val text = StringBuilder(bytes.size)
        var replacements = 0
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            if (first < 0x80) {
                text.append(first.toChar())
                index += 1
                continue
            }
            if (index + 1 >= bytes.size) {
                text.append(REPLACEMENT_CHAR)
                replacements += 1
                break
            }
            val tableIndex = gbkIndex(first, bytes[index + 1].toInt() and 0xFF)
            val char = tableIndex?.let(gbkDecodeTable::get) ?: INVALID_GBK_CHAR
            if (char == INVALID_GBK_CHAR) {
                text.append(REPLACEMENT_CHAR)
                replacements += 1
                index += 1
            } else {
                text.append(char)
                index += 2
            }
        }
        return EditorDecodedText(text.toString(), replacements)
    }

    private fun encodeSingleByte(text: String, maxValue: Int): EditorEncodedText {
        val output = ByteArray(text.length)
        val replacements = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            if (char.code <= maxValue) output[index] = char.code.toByte() else {
                output[index] = REPLACEMENT_BYTE
                replacements += index
            }
        }
        return EditorEncodedText(output, replacements)
    }

    private fun encodeUtf16(text: String, littleEndian: Boolean): EditorEncodedText {
        val output = ByteArray(text.length * 2)
        text.forEachIndexed { index, char ->
            val high = (char.code ushr 8).toByte()
            val low = char.code.toByte()
            if (littleEndian) {
                output[index * 2] = low
                output[index * 2 + 1] = high
            } else {
                output[index * 2] = high
                output[index * 2 + 1] = low
            }
        }
        return EditorEncodedText(output)
    }

    private fun encodeGbk(text: String): EditorEncodedText {
        val output = ArrayList<Byte>(text.length * 2)
        val replacements = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            when {
                char.code < 0x80 -> output += char.code.toByte()
                else -> {
                    val tableIndex = gbkEncodeTable[char]
                    if (tableIndex == null) {
                        output += REPLACEMENT_BYTE
                        replacements += index
                    } else {
                        val lead = 0x81 + tableIndex / GBK_TRAIL_COUNT
                        val trailIndex = tableIndex % GBK_TRAIL_COUNT
                        val trail = if (trailIndex < 63) 0x40 + trailIndex else 0x80 + trailIndex - 63
                        output += lead.toByte()
                        output += trail.toByte()
                    }
                }
            }
        }
        return EditorEncodedText(ByteArray(output.size) { output[it] }, replacements)
    }

    private fun detectUtf16(bytes: ByteArray): EditorEncodingDetection? {
        if (bytes.size < 4) return null
        val pairCount = bytes.size / 2
        var evenZeros = 0
        var oddZeros = 0
        repeat(pairCount) { pair ->
            if (bytes[pair * 2] == 0.toByte()) evenZeros += 1
            if (bytes[pair * 2 + 1] == 0.toByte()) oddZeros += 1
        }
        val threshold = (pairCount / 3).coerceAtLeast(1)
        return when {
            oddZeros >= threshold && evenZeros * 3 < oddZeros ->
                EditorEncodingDetection(EditorTextEncoding.UTF16_LE, EditorEncodingConfidence.Medium, false)
            evenZeros >= threshold && oddZeros * 3 < evenZeros ->
                EditorEncodingDetection(EditorTextEncoding.UTF16_BE, EditorEncodingConfidence.Medium, false)
            else -> null
        }
    }

    private fun isValidGbk(bytes: ByteArray): Boolean {
        var index = 0
        var pairs = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            if (first < 0x80) {
                index += 1
                continue
            }
            if (index + 1 >= bytes.size) return false
            val tableIndex = gbkIndex(first, bytes[index + 1].toInt() and 0xFF) ?: return false
            if (gbkDecodeTable[tableIndex] == INVALID_GBK_CHAR) return false
            pairs += 1
            index += 2
        }
        return pairs > 0
    }

    companion object {
        private val loadMutex = Mutex()
        private var cached: EditorTextCodec? = null

        suspend fun load(): EditorTextCodec {
            cached?.let { return it }
            return loadMutex.withLock {
                cached ?: EditorTextCodec(
                    decodeGbkDeltaMap(Res.readBytes("files/editor/gbk-delta-map.bin"))
                ).also { cached = it }
            }
        }
    }
}

private fun completedPrefixLength(bytes: ByteArray, encoding: EditorTextEncoding): Int = when (encoding) {
    EditorTextEncoding.ASCII,
    EditorTextEncoding.ISO_8859_1,
    -> bytes.size
    EditorTextEncoding.UTF8 -> bytes.size - incompleteUtf8TailLength(bytes)
    EditorTextEncoding.GBK -> {
        var index = 0
        var completed = bytes.size
        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xFF
            if (value < 0x80) {
                index += 1
            } else if (index + 1 < bytes.size) {
                index += 2
            } else {
                completed = index
                break
            }
        }
        completed
    }
    EditorTextEncoding.UTF16_LE,
    EditorTextEncoding.UTF16_BE,
    -> {
        var length = bytes.size - bytes.size % 2
        if (length >= 2) {
            val first = bytes[length - 2].toInt() and 0xFF
            val second = bytes[length - 1].toInt() and 0xFF
            val code = if (encoding == EditorTextEncoding.UTF16_LE) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
            if (code in 0xD800..0xDBFF) length -= 2
        }
        length
    }
}

private const val GBK_TRAIL_COUNT = 190
private const val GBK_TABLE_SIZE = 126 * GBK_TRAIL_COUNT
private const val INVALID_GBK_CHAR = '\uFFFF'
private const val REPLACEMENT_CHAR = '\uFFFD'
private const val REPLACEMENT_BYTE: Byte = 0x3F
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

private fun decodeGbkDeltaMap(bytes: ByteArray): CharArray {
    val table = CharArray(GBK_TABLE_SIZE) { INVALID_GBK_CHAR }
    var byteIndex = 0
    var tableIndex = 0
    var previous = 0
    while (tableIndex < table.size) {
        require(byteIndex < bytes.size) { AppStrings.ui_gbk_mapping_resource_incomplete }
        var token = 0
        var shift = 0
        while (true) {
            require(byteIndex < bytes.size && shift <= 28) { AppStrings.ui_gbk_mapping_resource_damaged }
            val value = bytes[byteIndex++].toInt() and 0xFF
            token = token or ((value and 0x7F) shl shift)
            if (value and 0x80 == 0) break
            shift += 7
        }
        if (token != 0) {
            val zigzag = token - 1
            val delta = if (zigzag and 1 == 0) zigzag / 2 else -((zigzag + 1) / 2)
            previous += delta
            table[tableIndex] = previous.toChar()
        }
        tableIndex += 1
    }
    require(byteIndex == bytes.size) { AppStrings.ui_gbk_mapping_resources_contain_extra_data }
    return table
}

private fun gbkIndex(lead: Int, trail: Int): Int? {
    if (lead !in 0x81..0xFE) return null
    val trailIndex = when (trail) {
        in 0x40..0x7E -> trail - 0x40
        in 0x80..0xFE -> 63 + trail - 0x80
        else -> return null
    }
    return (lead - 0x81) * GBK_TRAIL_COUNT + trailIndex
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun ByteArray.withoutBom(encoding: EditorTextEncoding): ByteArray {
    val bom = when (encoding) {
        EditorTextEncoding.UTF8 -> UTF8_BOM
        EditorTextEncoding.UTF16_LE -> UTF16_LE_BOM
        EditorTextEncoding.UTF16_BE -> UTF16_BE_BOM
        else -> return this
    }
    return if (startsWith(bom)) copyOfRange(bom.size, size) else this
}

private fun Char.isSurrogate(): Boolean = code in 0xD800..0xDFFF
private fun Char.isHighSurrogate(): Boolean = code in 0xD800..0xDBFF
private fun Char.isLowSurrogate(): Boolean = code in 0xDC00..0xDFFF

private fun incompleteUtf8TailLength(bytes: ByteArray): Int {
    val firstCandidate = maxOf(0, bytes.size - 4)
    for (index in firstCandidate until bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val sequenceLength = when (first) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> continue
        }
        if (index + sequenceLength <= bytes.size) continue
        val trailing = bytes.size - index - 1
        if ((1..trailing).all { offset -> (bytes[index + offset].toInt() and 0xC0) == 0x80 }) {
            return bytes.size - index
        }
    }
    return 0
}
