package com.folderspan.ui.components.text

import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 高亮显示文本中的指定关键字。如果关键字在文本中匹配成功，则使用指定的样式对关键字进行高亮显示。
 *
 * @param text 原始文本，提供需要被高亮的内容。
 * @param keyword 需要被高亮显示的关键字。如果为空或没有匹配项，则显示原始文本。
 * @param useRegex 指定是否将关键字作为正则表达式进行处理。
 *                 如果为true，`keyword`将以正则表达式形式匹配文本。
 *                 否则，将作为普通字符串匹配。
 * @param modifier 用于修饰组件的Modifier。用于自定义组件的外观和行为。
 */
@Composable
fun HighlightedText(
    text: String,
    keyword: String,
    useRegex: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 如果关键字为空或不在原始文本中，则直接显示原文本
    if (keyword.isEmpty() || (!useRegex && !text.contains(keyword, ignoreCase = true))) {
        Text(text, modifier)
        return
    }

    // 如果使用正则表达式，则直接构造 Regex；否则进行转义以实现普通文本匹配
    val pattern = if (useRegex) {
        runCatching { Regex(keyword, RegexOption.IGNORE_CASE) }.getOrNull()
    } else {
        Regex(Regex.escape(keyword), setOf(RegexOption.IGNORE_CASE))
    }
    if (pattern == null) {
        Text(text, modifier)
        return
    }

    // 用于高亮部分的样式示例
    val spanStyle = SpanStyle(
        background = colorScheme.primary,
        fontWeight = FontWeight.Bold,
        color = colorScheme.background
    )

    val annotatedString = buildAnnotatedString {
        var lastIndex = 0
        // 找到所有匹配项，依次处理
        pattern.findAll(text).forEach { match ->
            // 先追加上次匹配段到本次匹配段之间的普通文本
            append(text.substring(lastIndex, match.range.first))
            // 对关键字匹配段进行高亮
            withStyle(spanStyle) {
                append(match.value)
            }
            // 更新下一段搜索的起始位置
            lastIndex = match.range.last + 1
        }
        // 追加剩余的文本
        append(text.substring(lastIndex))
    }

    Text(annotatedString, modifier)
}
