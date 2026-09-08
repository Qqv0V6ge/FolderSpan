package com.folderspan.pro.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ProCatalogSpacing {
    val hairline = 1.dp
    val rail = 3.dp
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

@Composable
internal fun proCatalogDisplayStyle(): TextStyle =
    MaterialTheme.typography.headlineMedium.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.4).sp,
    )

@Composable
internal fun proCatalogTitleStyle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    )

@Composable
internal fun proCatalogIndexStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )

@Composable
internal fun proCatalogMetaStyle(): TextStyle =
    MaterialTheme.typography.labelMedium.copy(
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.6.sp,
    )

@Composable
internal fun proCatalogKickerStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.2.sp,
    )

@Composable
internal fun ProHairline(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline,
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = ProCatalogSpacing.hairline,
        color = color,
    )
}

@Composable
internal fun ProKicker(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        modifier = modifier,
        style = proCatalogKickerStyle(),
        color = color,
    )
}

@Composable
internal fun ProIndexMark(
    index: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = (index + 1).toString().padStart(2, '0'),
        modifier = modifier.width(40.dp),
        style = proCatalogIndexStyle(),
        color = color,
    )
}

@Composable
internal fun ProLedgerNote(
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(ProCatalogSpacing.rail)
                .fillMaxHeight()
                .background(tone),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = ProCatalogSpacing.md,
                    top = ProCatalogSpacing.sm,
                    end = ProCatalogSpacing.xs,
                    bottom = ProCatalogSpacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(ProCatalogSpacing.xs),
            content = content,
        )
    }
}

@Composable
internal fun ProMasthead(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    containerBrush: Brush? = null,
    contentColor: Color,
    watermark: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (containerBrush != null) {
                    Modifier.background(containerBrush)
                } else {
                    Modifier.background(containerColor)
                },
            ),
    ) {
        if (!watermark.isNullOrBlank()) {
            Text(
                text = watermark,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 18.dp),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                ),
                color = contentColor.copy(alpha = 0.14f),
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ProCatalogSpacing.lg),
            content = content,
        )
    }
}

@Composable
internal fun ProCatalogSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ProCatalogSpacing.xxs),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = proCatalogTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (action != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ProCatalogSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = action,
                )
            }
        }
        Spacer(modifier = Modifier.height(ProCatalogSpacing.sm))
        ProHairline()
        Spacer(modifier = Modifier.height(ProCatalogSpacing.xs))
        content()
    }
}

@Composable
internal fun ProCatalogRow(
    index: Int,
    modifier: Modifier = Modifier,
    showHairline: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showHairline) {
            ProHairline(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = ProCatalogSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            ProIndexMark(index = index)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ProCatalogSpacing.xxs),
                content = content,
            )
            if (trailing != null) {
                Row(
                    modifier = Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}

@Composable
internal fun ProStamp(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = label,
        modifier = modifier
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = proCatalogMetaStyle(),
        color = contentColor,
    )
}

@Composable
internal fun ProCatalogAppear(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val delay = (index * 42).coerceAtMost(336)
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 460, delayMillis = delay),
        label = "proCatalogAlpha",
    )
    val offset by animateDpAsState(
        targetValue = if (shown) 0.dp else 12.dp,
        animationSpec = tween(durationMillis = 460, delayMillis = delay),
        label = "proCatalogOffset",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = offset.toPx()
            },
    ) {
        content()
    }
}
