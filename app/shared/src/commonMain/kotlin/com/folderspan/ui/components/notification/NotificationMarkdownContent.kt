package com.folderspan.ui.components.notification

import strings.AppStrings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.folderspan.notification.NotificationBlock
import com.folderspan.notification.NotificationInlineSegment
import com.folderspan.notification.isAccountNotificationActionAvailable
import com.folderspan.notification.isHttpUrl
import com.folderspan.notification.notificationLinkAction
import com.folderspan.notification.parseNotificationContent
import com.folderspan.pro.domain.model.NotificationAction
import com.folderspan.ui.components.image.LocalImagePreviewController
import com.folderspan.ui.components.image.ProvideImagePreviewController
import com.folderspan.ui.components.image.imagePreviewCaption
import com.seiko.imageloader.model.ImageAction
import com.seiko.imageloader.rememberImageSuccessPainter
import com.seiko.imageloader.ui.AutoSizeBox

@Composable
fun NotificationMarkdownContent(
    content: String,
    onAction: (NotificationAction) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    ProvideImagePreviewController {
        val blocks = remember(content) { parseNotificationContent(content) }
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEach { block ->
                NotificationBlockView(
                    block = block,
                    onAction = onAction,
                    color = color,
                    style = style,
                )
            }
        }
    }
}

@Composable
private fun NotificationBlockView(
    block: NotificationBlock,
    onAction: (NotificationAction) -> Unit,
    color: Color,
    style: TextStyle,
) {
    when (block) {
        is NotificationBlock.Paragraph ->
            NotificationInlineContent(block.segments, onAction, color, style)

        is NotificationBlock.Heading ->
            NotificationInlineContent(
                segments = block.segments,
                onAction = onAction,
                color = color,
                style = headingTextStyle(block.level),
            )

        is NotificationBlock.UnorderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
                NotificationListItemRow("•", item, onAction, color, style)
            }
        }

        is NotificationBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                NotificationListItemRow("${block.start + index}.", item, onAction, color, style)
            }
        }

        is NotificationBlock.CodeBlock -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = block.code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }

        is NotificationBlock.Blockquote -> Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            )
            NotificationInlineContent(
                segments = block.segments,
                onAction = onAction,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = style,
                modifier = Modifier.weight(1f),
            )
        }

        NotificationBlock.HorizontalRule ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun NotificationListItemRow(
    bullet: String,
    item: List<NotificationInlineSegment>,
    onAction: (NotificationAction) -> Unit,
    color: Color,
    style: TextStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = bullet,
            style = style,
            color = color,
            modifier = Modifier.widthIn(min = 16.dp),
        )
        NotificationInlineContent(
            segments = item,
            onAction = onAction,
            color = color,
            style = style,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun headingTextStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun NotificationInlineContent(
    segments: List<NotificationInlineSegment>,
    onAction: (NotificationAction) -> Unit,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val runs = remember(segments) { splitInlineRuns(segments) }
    when {
        runs.isEmpty() -> Unit
        runs.size == 1 -> when (val run = runs.single()) {
            is NotificationInlineRun.Text ->
                NotificationInlineText(run.segments, onAction, color, style, modifier)
            is NotificationInlineRun.Image ->
                NotificationMarkdownImage(run.image, modifier)
        }
        else -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            runs.forEach { run ->
                when (run) {
                    is NotificationInlineRun.Text ->
                        NotificationInlineText(run.segments, onAction, color, style)
                    is NotificationInlineRun.Image ->
                        NotificationMarkdownImage(run.image)
                }
            }
        }
    }
}

@Composable
private fun NotificationMarkdownImage(
    image: NotificationInlineSegment.Image,
    modifier: Modifier = Modifier,
) {
    val alt = image.alt
    val frameModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
    if (!isLoadableNotificationImageUrl(image.target)) {
        NotificationMarkdownImageStatus(
            loading = false,
            alt = alt,
            modifier = frameModifier,
        )
        return
    }
    var reloadToken by remember(image.target) { mutableIntStateOf(0) }
    val previewController = LocalImagePreviewController.current
    val caption = imagePreviewCaption(image.title, image.alt)
    key(reloadToken) {
        AutoSizeBox(
            url = image.target,
            modifier = frameModifier
                .heightIn(max = NotificationMarkdownImageMaxHeight)
                .then(
                    if (previewController == null) {
                        Modifier
                    } else {
                        Modifier.clickable {
                            previewController.show(image.target, caption)
                        }
                    },
                ),
        ) { action ->
            when (action) {
                is ImageAction.Success -> Image(
                    painter = rememberImageSuccessPainter(action),
                    contentDescription = alt.ifBlank { null },
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
                is ImageAction.Loading -> NotificationMarkdownImageStatus(
                    loading = true,
                    alt = alt,
                )
                is ImageAction.Failure -> NotificationMarkdownImageStatus(
                    loading = false,
                    alt = alt,
                    onRetry = { reloadToken += 1 },
                )
            }
        }
    }
}

@Composable
private fun NotificationMarkdownImageStatus(
    loading: Boolean,
    alt: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val title = if (loading) AppStrings.ui_loading else AppStrings.ui_loading_failed
    Surface(
        modifier = modifier.heightIn(min = NotificationMarkdownImageMinHeight),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (alt.isNotBlank()) {
                Text(
                    text = alt,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(AppStrings.ui_reload)
                }
            }
        }
    }
}

@Composable
private fun NotificationInlineText(
    segments: List<NotificationInlineSegment>,
    onAction: (NotificationAction) -> Unit,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val currentOnAction by rememberUpdatedState(onAction)
    val linkColor = MaterialTheme.colorScheme.primary
    val focusedLinkBackground = MaterialTheme.colorScheme.secondaryContainer
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkStyles = remember(linkColor, focusedLinkBackground) {
        TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            ),
            focusedStyle = SpanStyle(background = focusedLinkBackground),
            hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline),
        )
    }
    val text = remember(segments, linkStyles, codeBackground) {
        buildAnnotatedString {
            appendInlineSegments(segments, linkStyles, codeBackground, currentOnAction)
        }
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
    )
}

private fun AnnotatedString.Builder.appendInlineSegments(
    segments: List<NotificationInlineSegment>,
    linkStyles: TextLinkStyles,
    codeBackground: Color,
    onAction: (NotificationAction) -> Unit,
) {
    segments.forEachIndexed { index, segment ->
        when (segment) {
            is NotificationInlineSegment.PlainText -> append(segment.text)

            is NotificationInlineSegment.Bold ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineSegments(segment.segments, linkStyles, codeBackground, onAction)
                }

            is NotificationInlineSegment.Italic ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineSegments(segment.segments, linkStyles, codeBackground, onAction)
                }

            is NotificationInlineSegment.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineSegments(segment.segments, linkStyles, codeBackground, onAction)
                }

            is NotificationInlineSegment.Code ->
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                ) {
                    append(segment.text)
                }

            is NotificationInlineSegment.InlineLink -> {
                val action = notificationLinkAction(segment.label, segment.target)
                    ?.takeIf(::isAccountNotificationActionAvailable)
                if (action == null) {
                    append(segment.label)
                } else {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "$index:${segment.target}",
                            styles = linkStyles,
                            linkInteractionListener = LinkInteractionListener {
                                onAction(action)
                            },
                        ),
                    ) {
                        append(segment.label)
                    }
                }
            }

            is NotificationInlineSegment.Image -> append(segment.alt)
        }
    }
}

private fun splitInlineRuns(segments: List<NotificationInlineSegment>): List<NotificationInlineRun> {
    val runs = mutableListOf<NotificationInlineRun>()
    val buffer = mutableListOf<NotificationInlineSegment>()

    fun flush() {
        if (buffer.isEmpty()) return
        runs += NotificationInlineRun.Text(buffer.toList())
        buffer.clear()
    }

    segments.forEach { segment ->
        if (segment is NotificationInlineSegment.Image) {
            flush()
            runs += NotificationInlineRun.Image(segment)
        } else {
            buffer += segment
        }
    }
    flush()
    return runs
}

private fun isLoadableNotificationImageUrl(url: String): Boolean =
    isHttpUrl(url) && url.none { character -> character.isWhitespace() || character.isISOControl() }

private val NotificationMarkdownImageMinHeight = 120.dp
private val NotificationMarkdownImageMaxHeight = 360.dp

private sealed interface NotificationInlineRun {
    data class Text(val segments: List<NotificationInlineSegment>) : NotificationInlineRun
    data class Image(val image: NotificationInlineSegment.Image) : NotificationInlineRun
}

@Preview
@Composable
private fun NotificationMarkdownContentPreview() {
    MaterialTheme {
        Column {
            NotificationMarkdownContent(
                content = "# Release notes\n\n" +
                    "- Support **markdown** rendering\n" +
                    "- Open [Settings](route:settings) or `code` blocks\n\n" +
                    "![Screenshot](https://example.com/shot.png)\n\n" +
                    "> Read the [documentation](https://example.com) for details.",
                onAction = {},
            )
        }
    }
}
