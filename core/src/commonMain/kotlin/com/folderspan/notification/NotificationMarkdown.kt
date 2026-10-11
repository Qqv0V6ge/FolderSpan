package com.folderspan.notification

// 通知正文的轻量 Markdown 解析器。
// 支持块级结构（标题、无序/有序列表、代码块、引用、水平线）与行内格式
// （粗体、斜体、删除线、行内代码、行内链接、图片）；不支持的语法按普通文本处理。
// 解析对任意输入保持线性时间，不会出现退化。

sealed interface NotificationBlock {
    data class Paragraph(val segments: List<NotificationInlineSegment>) : NotificationBlock

    data class Heading(
        val level: Int,
        val segments: List<NotificationInlineSegment>,
    ) : NotificationBlock

    data class UnorderedList(
        val items: List<List<NotificationInlineSegment>>,
    ) : NotificationBlock

    data class OrderedList(
        val start: Int,
        val items: List<List<NotificationInlineSegment>>,
    ) : NotificationBlock

    data class CodeBlock(val code: String) : NotificationBlock

    data class Blockquote(val segments: List<NotificationInlineSegment>) : NotificationBlock

    data object HorizontalRule : NotificationBlock
}

sealed interface NotificationInlineSegment {
    data class PlainText(val text: String) : NotificationInlineSegment

    data class Bold(val segments: List<NotificationInlineSegment>) : NotificationInlineSegment

    data class Italic(val segments: List<NotificationInlineSegment>) : NotificationInlineSegment

    data class Strikethrough(val segments: List<NotificationInlineSegment>) : NotificationInlineSegment

    data class Code(val text: String) : NotificationInlineSegment

    data class InlineLink(
        val label: String,
        val target: String,
    ) : NotificationInlineSegment

    data class Image(
        val alt: String,
        val target: String,
        val title: String = "",
    ) : NotificationInlineSegment
}

fun parseNotificationContent(content: String): List<NotificationBlock> =
    NotificationBlockParser(content).parse().blocks

internal data class NotificationContentParseResult(
    val blocks: List<NotificationBlock>,
    val inspectedCharacters: Int,
)

internal fun parseNotificationContentWithMetrics(content: String): NotificationContentParseResult =
    NotificationBlockParser(content).parse()

fun notificationPlainTextPreview(content: String): String = buildString {
    val blocks = parseNotificationContent(content)
    blocks.forEachIndexed { index, block ->
        val previous = blocks.getOrNull(index - 1)
        val needsSeparator = index > 0 &&
            block !== NotificationBlock.HorizontalRule &&
            previous !== NotificationBlock.HorizontalRule
        if (needsSeparator) append('\n')
        when (block) {
            is NotificationBlock.Paragraph -> appendInlinePlainText(block.segments)
            is NotificationBlock.Heading -> appendInlinePlainText(block.segments)
            is NotificationBlock.UnorderedList -> block.items.forEachIndexed { itemIndex, item ->
                if (itemIndex > 0) append('\n')
                append("- ")
                appendInlinePlainText(item)
            }
            is NotificationBlock.OrderedList -> block.items.forEachIndexed { itemIndex, item ->
                if (itemIndex > 0) append('\n')
                append(block.start + itemIndex)
                append(". ")
                appendInlinePlainText(item)
            }
            is NotificationBlock.CodeBlock -> append(block.code)
            is NotificationBlock.Blockquote -> appendInlinePlainText(block.segments)
            NotificationBlock.HorizontalRule -> append("---")
        }
    }
}

private fun StringBuilder.appendInlinePlainText(segments: List<NotificationInlineSegment>) {
    segments.forEach { segment ->
        when (segment) {
            is NotificationInlineSegment.PlainText -> append(segment.text)
            is NotificationInlineSegment.InlineLink -> append(segment.label)
            is NotificationInlineSegment.Image -> append(segment.alt)
            is NotificationInlineSegment.Bold -> appendInlinePlainText(segment.segments)
            is NotificationInlineSegment.Italic -> appendInlinePlainText(segment.segments)
            is NotificationInlineSegment.Strikethrough -> appendInlinePlainText(segment.segments)
            is NotificationInlineSegment.Code -> append(segment.text)
        }
    }
}

private const val MAX_LIST_INDENT = 3
private const val MAX_ORDERED_LIST_DIGITS = 9

private class NotificationBlockParser(
    private val content: String,
) {
    private val blocks = mutableListOf<NotificationBlock>()
    private var inspectedCharacters = 0

    fun parse(): NotificationContentParseResult {
        if (content.isEmpty()) return NotificationContentParseResult(emptyList(), 1)
        val lines = content.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            inspectedCharacters += line.length + 1
            when {
                line.isBlank() -> index += 1

                isFenceLine(line) -> {
                    val match = readCodeBlock(lines, index)
                    blocks += NotificationBlock.CodeBlock(match.code)
                    index = match.nextIndex
                }

                else -> {
                    val heading = readHeading(line)
                    if (heading != null) {
                        blocks += NotificationBlock.Heading(heading.level, parseInline(heading.text))
                        index += 1
                        continue
                    }
                    if (isHorizontalRuleLine(line)) {
                        blocks += NotificationBlock.HorizontalRule
                        index += 1
                        continue
                    }
                    if (readUnorderedListItem(line) != null) {
                        index = readUnorderedList(lines, index)
                        continue
                    }
                    val orderedItem = readOrderedListItem(line)
                    if (orderedItem != null) {
                        index = readOrderedList(lines, index, orderedItem.number)
                        continue
                    }
                    if (isBlockquoteLine(line)) {
                        index = readBlockquote(lines, index)
                        continue
                    }
                    index = readParagraph(lines, index)
                }
            }
        }
        return NotificationContentParseResult(blocks.toList(), inspectedCharacters)
    }

    private fun parseInline(text: String): List<NotificationInlineSegment> {
        val result = NotificationInlineParser(text).parse()
        inspectedCharacters += result.inspectedCharacters
        return result.segments
    }

    private fun readHeading(line: String): HeadingMatch? {
        var hashCount = 0
        while (hashCount < line.length && line[hashCount] == '#') hashCount += 1
        if (hashCount == 0 || hashCount > 6) return null
        if (hashCount == line.length) return HeadingMatch(hashCount, "")
        if (!line[hashCount].isWhitespace()) return null
        return HeadingMatch(
            level = hashCount,
            text = line.substring(hashCount).trim().trimEnd('#').trim(),
        )
    }

    private fun isHorizontalRuleLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= 3 && trimmed.all { it == trimmed.first() } && trimmed.first() in "-*_"
    }

    private fun isBlockquoteLine(line: String): Boolean = line.trimStart().startsWith('>')

    private fun readUnorderedListItem(line: String): Int? {
        var index = 0
        while (index < line.length && index < MAX_LIST_INDENT && line[index] == ' ') index += 1
        if (index >= line.length || line[index] !in "-*+") return null
        if (index + 1 >= line.length || !line[index + 1].isWhitespace()) return null
        return index + 2
    }

    private fun readOrderedListItem(line: String): OrderedListItemMatch? {
        var index = 0
        while (index < line.length && index < MAX_LIST_INDENT && line[index] == ' ') index += 1
        var digits = 0
        while (index < line.length && line[index].isDigit() && digits < MAX_ORDERED_LIST_DIGITS) {
            index += 1
            digits += 1
        }
        if (digits == 0) return null
        if (index >= line.length || line[index] != '.') return null
        if (index + 1 >= line.length || !line[index + 1].isWhitespace()) return null
        val number = line.substring(index - digits, index).toIntOrNull() ?: return null
        return OrderedListItemMatch(number, index + 2)
    }

    private fun readUnorderedList(lines: List<String>, startIndex: Int): Int {
        val items = mutableListOf<List<NotificationInlineSegment>>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            val contentStart = readUnorderedListItem(line) ?: break
            if (index > startIndex) inspectedCharacters += line.length + 1
            items += parseInline(line.substring(contentStart).trimStart())
            index += 1
        }
        blocks += NotificationBlock.UnorderedList(items)
        return index
    }

    private fun readOrderedList(lines: List<String>, startIndex: Int, start: Int): Int {
        val items = mutableListOf<List<NotificationInlineSegment>>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            val item = readOrderedListItem(line) ?: break
            if (index > startIndex) inspectedCharacters += line.length + 1
            items += parseInline(line.substring(item.contentStart).trimStart())
            index += 1
        }
        blocks += NotificationBlock.OrderedList(start, items)
        return index
    }

    private fun readBlockquote(lines: List<String>, startIndex: Int): Int {
        val text = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            if (!isBlockquoteLine(line)) break
            if (index > startIndex) inspectedCharacters += line.length + 1
            val stripped = line.trimStart()
            text += if (stripped.length > 1 && stripped[1] == ' ') stripped.substring(2) else stripped.substring(1)
            index += 1
        }
        blocks += NotificationBlock.Blockquote(parseInline(text.joinToString("\n")))
        return index
    }

    private fun readCodeBlock(lines: List<String>, startIndex: Int): CodeBlockMatch {
        val codeLines = mutableListOf<String>()
        var index = startIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            inspectedCharacters += line.length + 1
            if (isFenceLine(line)) return CodeBlockMatch(codeLines.joinToString("\n"), index + 1)
            codeLines += line
            index += 1
        }
        return CodeBlockMatch(codeLines.joinToString("\n"), index)
    }

    private fun isFenceLine(line: String): Boolean = line.trimStart().startsWith("```")

    private fun readParagraph(lines: List<String>, startIndex: Int): Int {
        val text = mutableListOf<String>()
        var index = startIndex
        while (index < lines.size) {
            val line = lines[index]
            val isBlockStart = line.isBlank() ||
                isFenceLine(line) ||
                readHeading(line) != null ||
                isHorizontalRuleLine(line) ||
                readUnorderedListItem(line) != null ||
                readOrderedListItem(line) != null ||
                isBlockquoteLine(line)
            if (isBlockStart) break
            if (index > startIndex) inspectedCharacters += line.length + 1
            text += line
            index += 1
        }
        blocks += NotificationBlock.Paragraph(parseInline(text.joinToString("\n")))
        return index
    }

    private data class HeadingMatch(val level: Int, val text: String)

    private data class OrderedListItemMatch(val number: Int, val contentStart: Int)

    private data class CodeBlockMatch(val code: String, val nextIndex: Int)
}

private enum class EmphasisKind { Bold, Italic, Strikethrough }

private data class InlineParseResult(
    val segments: List<NotificationInlineSegment>,
    val inspectedCharacters: Int,
)

private class NotificationInlineParser(
    private val content: String,
) {
    private val segments = mutableListOf<NotificationInlineSegment>()
    private val plainText = StringBuilder()
    private var inspectedCharacters = 0

    fun parse(): InlineParseResult {
        var index = 0
        while (index < content.length) {
            inspectedCharacters += 1
            when {
                content[index] == '\\' &&
                    index + 1 < content.length &&
                    content[index + 1] in ESCAPABLE_CHARACTERS -> {
                    plainText.append(content[index])
                    plainText.append(content[index + 1])
                    inspectedCharacters += 1
                    index += 2
                }

                content[index] == '!' &&
                    index + 1 < content.length &&
                    content[index + 1] == '[' -> {
                    when (val candidate = readLink(index + 1, allowEmptyLabel = true)) {
                        is LinkCandidate.Valid -> {
                            flushPlainText()
                            val destination = destinationOf(candidate)
                            if (destination.target.isEmpty()) {
                                appendLiteral(index, candidate.endIndex)
                            } else {
                                segments += NotificationInlineSegment.Image(
                                    alt = unescapeComponent(candidate.labelStart, candidate.labelEnd),
                                    target = destination.target,
                                    title = destination.title,
                                )
                            }
                            index = candidate.endIndex
                        }

                        is LinkCandidate.Literal -> {
                            appendLiteral(index, candidate.endIndex)
                            index = candidate.endIndex
                        }

                        is LinkCandidate.RetryAt -> {
                            appendLiteral(index, candidate.index)
                            index = candidate.index
                        }
                    }
                }

                content[index] == '[' -> {
                    when (val candidate = readLink(index)) {
                        is LinkCandidate.Valid -> {
                            flushPlainText()
                            val destination = destinationOf(candidate)
                            if (destination.target.isEmpty()) {
                                appendLiteral(index, candidate.endIndex)
                            } else {
                                segments += NotificationInlineSegment.InlineLink(
                                    label = unescapeComponent(candidate.labelStart, candidate.labelEnd),
                                    target = destination.target,
                                )
                            }
                            index = candidate.endIndex
                        }

                        is LinkCandidate.Literal -> {
                            appendLiteral(index, candidate.endIndex)
                            index = candidate.endIndex
                        }

                        is LinkCandidate.RetryAt -> {
                            appendLiteral(index, candidate.index)
                            index = candidate.index
                        }
                    }
                }

                content[index] == '*' &&
                    index + 1 < content.length &&
                    content[index + 1] == '*' ->
                    index = readEmphasis(index, "**", EmphasisKind.Bold)
                content[index] == '_' &&
                    index + 1 < content.length &&
                    content[index + 1] == '_' ->
                    index = readEmphasis(index, "__", EmphasisKind.Bold)
                content[index] == '~' &&
                    index + 1 < content.length &&
                    content[index + 1] == '~' ->
                    index = readEmphasis(index, "~~", EmphasisKind.Strikethrough)
                content[index] == '*' ->
                    index = readEmphasis(index, "*", EmphasisKind.Italic)
                content[index] == '_' ->
                    index = readEmphasis(index, "_", EmphasisKind.Italic)
                content[index] == '`' ->
                    index = readCode(index)

                else -> {
                    plainText.append(content[index])
                    index += 1
                }
            }
        }
        flushPlainText()
        return InlineParseResult(segments.toList(), inspectedCharacters)
    }

    private fun readEmphasis(startIndex: Int, marker: String, kind: EmphasisKind): Int {
        val literalEnd = startIndex + marker.length
        if (!canOpenDelimiter(startIndex)) {
            appendLiteral(startIndex, literalEnd)
            return literalEnd
        }
        val innerStart = literalEnd
        val end = content.indexOf(marker, innerStart)
        if (end < 0) {
            appendLiteral(startIndex, literalEnd)
            return literalEnd
        }
        if (end == innerStart || !canCloseDelimiter(end + marker.length)) {
            appendLiteral(startIndex, literalEnd)
            return literalEnd
        }
        if (
            kind == EmphasisKind.Italic &&
            (content[innerStart].isWhitespace() || content[end - 1].isWhitespace())
        ) {
            appendLiteral(startIndex, literalEnd)
            return literalEnd
        }
        val inner = NotificationInlineParser(content.substring(innerStart, end)).parse()
        inspectedCharacters += inner.inspectedCharacters
        flushPlainText()
        segments += when (kind) {
            EmphasisKind.Bold -> NotificationInlineSegment.Bold(inner.segments)
            EmphasisKind.Italic -> NotificationInlineSegment.Italic(inner.segments)
            EmphasisKind.Strikethrough -> NotificationInlineSegment.Strikethrough(inner.segments)
        }
        return end + marker.length
    }

    private fun readCode(startIndex: Int): Int {
        val end = content.indexOf('`', startIndex + 1)
        if (end < 0) {
            appendLiteral(startIndex, startIndex + 1)
            return startIndex + 1
        }
        if (end == startIndex + 1) {
            appendLiteral(startIndex, end + 1)
            return end + 1
        }
        flushPlainText()
        segments += NotificationInlineSegment.Code(content.substring(startIndex + 1, end))
        return end + 1
    }

    private fun canOpenDelimiter(index: Int): Boolean =
        index == 0 || !content[index - 1].isLetterOrDigit()

    private fun canCloseDelimiter(endIndex: Int): Boolean =
        endIndex >= content.length || !content[endIndex].isLetterOrDigit()

    private fun readLink(startIndex: Int, allowEmptyLabel: Boolean = false): LinkCandidate {
        var index = startIndex + 1
        val labelStart = index
        var labelEnd = -1
        while (index < content.length) {
            inspectedCharacters += 1
            when (content[index]) {
                '\\' -> index = skipEscapedCharacter(index)
                '[' -> return LinkCandidate.RetryAt(index)
                ']' -> {
                    labelEnd = index
                    index += 1
                    break
                }
                else -> index += 1
            }
        }
        if (labelEnd < 0) return LinkCandidate.Literal(content.length)
        if (index >= content.length || content[index] != '(') return LinkCandidate.Literal(index)

        inspectedCharacters += 1
        index += 1
        val targetStart = index
        var nestedParenthesis = false
        while (index < content.length) {
            inspectedCharacters += 1
            when (content[index]) {
                '\\' -> index = skipEscapedCharacter(index)
                '[' -> return LinkCandidate.RetryAt(index)
                '(' -> {
                    nestedParenthesis = true
                    index += 1
                }
                ')' -> {
                    val targetEnd = index
                    val endIndex = index + 1
                    if (
                        nestedParenthesis ||
                        (!allowEmptyLabel && unescapedComponentIsEmpty(labelStart, labelEnd)) ||
                        unescapedComponentIsEmpty(targetStart, targetEnd)
                    ) {
                        return LinkCandidate.Literal(endIndex)
                    }
                    return LinkCandidate.Valid(
                        labelStart = labelStart,
                        labelEnd = labelEnd,
                        targetStart = targetStart,
                        targetEnd = targetEnd,
                        endIndex = endIndex,
                    )
                }
                else -> index += 1
            }
        }
        return LinkCandidate.Literal(content.length)
    }

    private fun skipEscapedCharacter(backslashIndex: Int): Int =
        (backslashIndex + 2).coerceAtMost(content.length)

    private fun unescapedComponentIsEmpty(startIndex: Int, endIndex: Int): Boolean =
        startIndex >= endIndex

    private fun destinationOf(candidate: LinkCandidate.Valid): MarkdownDestination =
        parseMarkdownDestination(content.substring(candidate.targetStart, candidate.targetEnd))

    private fun unescapeComponent(startIndex: Int, endIndex: Int): String =
        unescapeMarkdown(content, startIndex, endIndex)

    private fun appendLiteral(startIndex: Int, endIndex: Int) {
        for (index in startIndex until endIndex) plainText.append(content[index])
    }

    private fun flushPlainText() {
        if (plainText.isEmpty()) return
        segments += NotificationInlineSegment.PlainText(plainText.toString())
        plainText.clear()
    }

    private sealed interface LinkCandidate {
        data class Valid(
            val labelStart: Int,
            val labelEnd: Int,
            val targetStart: Int,
            val targetEnd: Int,
            val endIndex: Int,
        ) : LinkCandidate

        data class Literal(val endIndex: Int) : LinkCandidate

        data class RetryAt(val index: Int) : LinkCandidate
    }
}

private val ESCAPABLE_CHARACTERS = setOf('\\', '[', ']', '(', ')', '*', '_', '`', '~', '"', '\'')

internal data class MarkdownDestination(
    val target: String,
    val title: String,
)

internal fun parseMarkdownDestination(raw: String): MarkdownDestination {
    var index = 0
    while (index < raw.length && raw[index].isWhitespace()) index += 1
    val destinationStart = index
    while (index < raw.length && !raw[index].isWhitespace()) {
        index = if (raw[index] == '\\') (index + 2).coerceAtMost(raw.length) else index + 1
    }
    val target = unescapeMarkdown(raw, destinationStart, index)
    while (index < raw.length && raw[index].isWhitespace()) index += 1
    if (index >= raw.length) return MarkdownDestination(target, "")
    val quote = raw[index]
    if (quote != '"' && quote != '\'') {
        return MarkdownDestination(unescapeMarkdown(raw, 0, raw.length).trim(), "")
    }
    index += 1
    val titleStart = index
    while (index < raw.length && raw[index] != quote) {
        index = if (raw[index] == '\\') (index + 2).coerceAtMost(raw.length) else index + 1
    }
    if (index >= raw.length) {
        return MarkdownDestination(unescapeMarkdown(raw, 0, raw.length).trim(), "")
    }
    val title = unescapeMarkdown(raw, titleStart, index)
    index += 1
    while (index < raw.length && raw[index].isWhitespace()) index += 1
    if (index != raw.length) {
        return MarkdownDestination(unescapeMarkdown(raw, 0, raw.length).trim(), "")
    }
    return MarkdownDestination(target, title)
}

private fun unescapeMarkdown(source: String, startIndex: Int, endIndex: Int): String = buildString {
    var index = startIndex
    while (index < endIndex) {
        val character = source[index]
        if (
            character == '\\' &&
            index + 1 < endIndex &&
            source[index + 1] in ESCAPABLE_CHARACTERS
        ) {
            append(source[index + 1])
            index += 2
        } else {
            append(character)
            index += 1
        }
    }
}
