package com.folderspan.editor

import strings.AppStrings

enum class EditorNewlineKind {
    None,
    LF,
    CRLF,
    CR,
    Mixed,
}

data class EditorNewlineSummary(
    val kind: EditorNewlineKind,
    val lfCount: Long,
    val crlfCount: Long,
    val crCount: Long,
) {
    val totalCount: Long
        get() = lfCount + crlfCount + crCount
}

class EditorNewlineDetector {
    private var pendingCr = false
    private var lfCount = 0L
    private var crlfCount = 0L
    private var crCount = 0L

    fun accept(text: String) {
        var index = 0
        if (pendingCr) {
            if (text.startsWith('\n')) {
                crlfCount += 1L
                index = 1
            } else {
                crCount += 1L
            }
            pendingCr = false
        }
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length) {
                        if (text[index + 1] == '\n') {
                            crlfCount += 1L
                            index += 2
                        } else {
                            crCount += 1L
                            index += 1
                        }
                    } else {
                        pendingCr = true
                        index += 1
                    }
                }
                '\n' -> {
                    lfCount += 1L
                    index += 1
                }
                else -> index += 1
            }
        }
    }

    fun finish(): EditorNewlineSummary {
        if (pendingCr) {
            crCount += 1L
            pendingCr = false
        }
        val kinds = listOf(lfCount, crlfCount, crCount).count { it > 0L }
        val kind = when {
            kinds == 0 -> EditorNewlineKind.None
            kinds > 1 -> EditorNewlineKind.Mixed
            crlfCount > 0L -> EditorNewlineKind.CRLF
            lfCount > 0L -> EditorNewlineKind.LF
            else -> EditorNewlineKind.CR
        }
        return EditorNewlineSummary(kind, lfCount, crlfCount, crCount)
    }
}

fun convertNewlines(text: String, target: EditorNewlineKind): String {
    require(target == EditorNewlineKind.LF || target == EditorNewlineKind.CRLF || target == EditorNewlineKind.CR)
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return when (target) {
        EditorNewlineKind.LF -> normalized
        EditorNewlineKind.CRLF -> normalized.replace("\n", "\r\n")
        EditorNewlineKind.CR -> normalized.replace('\n', '\r')
        EditorNewlineKind.None,
        EditorNewlineKind.Mixed,
        -> error(AppStrings.ui_unsupported_newline_conversion_target)
    }
}

class EditorNewlineByteTransformer(
    private val codec: EditorTextCodec,
    encoding: EditorTextEncoding,
    private val target: EditorNewlineKind,
) {
    init {
        require(target in setOf(EditorNewlineKind.LF, EditorNewlineKind.CRLF, EditorNewlineKind.CR))
    }

    private val encoding = encoding
    private val decoder = codec.incrementalDecoder(encoding)
    private var pendingCr = false

    fun accept(bytes: ByteArray): ByteArray = encode(convert(decoder.accept(bytes).text, false))

    fun finish(): ByteArray = encode(convert(decoder.finish().text, true))

    private fun convert(text: String, finishing: Boolean): String = buildString {
        if (pendingCr && text.isEmpty() && !finishing) return@buildString
        var index = 0
        if (pendingCr) {
            if (text.startsWith('\n')) index = 1
            appendTargetNewline()
            pendingCr = false
        }
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length) {
                        if (text[index + 1] == '\n') index += 1
                        appendTargetNewline()
                    } else if (finishing) {
                        appendTargetNewline()
                    } else {
                        pendingCr = true
                    }
                }
                '\n' -> appendTargetNewline()
                else -> append(text[index])
            }
            index += 1
        }
        if (finishing && pendingCr) {
            appendTargetNewline()
            pendingCr = false
        }
    }

    private fun StringBuilder.appendTargetNewline() {
        append(
            when (target) {
                EditorNewlineKind.LF -> "\n"
                EditorNewlineKind.CRLF -> "\r\n"
                EditorNewlineKind.CR -> "\r"
                EditorNewlineKind.None,
                EditorNewlineKind.Mixed,
                -> error(AppStrings.ui_unsupported_newline_conversion_target)
            }
        )
    }

    private fun encode(text: String): ByteArray = codec.encode(text, encoding).bytes
}
