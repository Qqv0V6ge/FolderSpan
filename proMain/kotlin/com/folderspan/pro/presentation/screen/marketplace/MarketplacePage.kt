package com.folderspan.pro.presentation.screen.marketplace

import strings.AppStrings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.folderspan.pro.domain.model.PluginCardUi
import com.folderspan.ui.components.loading.LoadingBase
import com.folderspan.ui.components.pagestate.PageAppendFooter
import com.folderspan.ui.components.pagestate.PageAppendState
import com.folderspan.ui.components.pagestate.PageErrorState
import com.folderspan.ui.components.pagestate.PageErrorType
import com.folderspan.ui.components.pagestate.PageRefreshState
import com.folderspan.ui.components.pagestate.PageStateLayout
import com.folderspan.ui.components.pagestate.PageViewState
import com.folderspan.ui.components.pagestate.resolvePageViewState
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplacePage(
    modifier: Modifier = Modifier,
    state: MarketplaceUiState = MarketplaceUiState(),
    pagingItems: LazyPagingItems<PluginCardUi>? = null,
    onEvent: (MarketplaceEvent) -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
) {
    val heroBanners = remember { marketplaceHeroBanners() }
    val featuredCards = remember { marketplaceFeaturedCards() }
    val newCards = remember { marketplaceNewCards() }
    val trendCards = remember { marketplaceTrendCards() }
    val freeCards = remember { marketplaceFreeCards() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = AppStrings.ui_plug_market,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = AppStrings.ui_search
                        )
                    }
                    IconButton(onClick = onNavigateToUserProfile) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = AppStrings.ui_user_details
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            val layoutMode = resolveMarketplaceLayoutMode(DpSize(maxWidth, maxHeight))
            val verticalPadding = when (layoutMode) {
                MarketplaceLayoutMode.Compact -> 18.dp
                MarketplaceLayoutMode.Medium -> 24.dp
                MarketplaceLayoutMode.Expanded -> 32.dp
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = verticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MarketplaceContent(
                    modifier = Modifier.fillMaxWidth(),
                    layoutMode = layoutMode,
                    query = state.query,
                    heroBanners = heroBanners,
                    featuredCards = featuredCards,
                    newCards = newCards,
                    trendCards = trendCards,
                    freeCards = freeCards,
                    pagingItems = pagingItems,
                    onQueryChange = { onEvent(MarketplaceEvent.QueryChanged(it)) }
                )
            }
        }
    }
}

@Composable
private fun MarketplaceContent(
    modifier: Modifier,
    layoutMode: MarketplaceLayoutMode,
    query: String,
    heroBanners: List<HeroBanner>,
    featuredCards: List<MarketplaceStaticCardUi>,
    newCards: List<MarketplaceStaticCardUi>,
    trendCards: List<MarketplaceStaticCardUi>,
    freeCards: List<MarketplaceStaticCardUi>,
    pagingItems: LazyPagingItems<PluginCardUi>?,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        MarketplaceHeaderZone(
            layoutMode = layoutMode,
            banners = heroBanners
        )

        MarketplaceSectionIntro(layoutMode = layoutMode)
        MarketplaceSearchField(
            query = query,
            onQueryChange = onQueryChange
        )
        MarketplaceCategoryRow()

        MarketplaceSection(
            title = AppStrings.ui_editor_s_picks,
            actionLabel = AppStrings.ui_view_all,
            cards = featuredCards,
            layoutMode = layoutMode
        )
        MarketplaceSection(
            title = AppStrings.ui_new_product_express,
            actionLabel = AppStrings.ui_view_all,
            cards = newCards,
            layoutMode = layoutMode
        )
        MarketplaceSection(
            title = AppStrings.ui_trend_growth,
            actionLabel = AppStrings.ui_view_all,
            cards = trendCards,
            layoutMode = layoutMode
        )
        MarketplaceSection(
            title = AppStrings.ui_free_essentials,
            actionLabel = AppStrings.ui_view_all,
            cards = freeCards,
            layoutMode = layoutMode
        )

        if (pagingItems != null) {
            MarketplacePagingSection(
                title = AppStrings.ui_all_plugins,
                pagingItems = pagingItems,
                layoutMode = layoutMode
            )
        } else {
            OutlinedButton(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text(AppStrings.ui_load_more)
            }
        }
    }
}

@Composable
private fun MarketplaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null
            )
        },
        placeholder = {
            Text(AppStrings.ui_search_plugin)
        }
    )
}

@Composable
private fun MarketplaceHeaderZone(
    layoutMode: MarketplaceLayoutMode,
    banners: List<HeroBanner>,
) {
    BoxWithConstraints {
        val bannerGap = 16.dp
        val bannerWidth = resolveBannerWidth(
            availableWidth = maxWidth,
            layoutMode = layoutMode
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(bannerGap)
            ) {
                banners.forEach { banner ->
                    MarketplaceHeroBanner(
                        banner = banner,
                        width = bannerWidth
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(banners.size.coerceAtMost(4)) { index ->
                    val active = index == 1
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(if (active) 22.dp else 6.dp)
                            .height(6.dp)
                            .background(
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketplaceHeroBanner(
    banner: HeroBanner,
    width: Dp,
) {
    val colors = marketplaceToneColors(banner.tone)
    Column(modifier = Modifier.width(width)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(
                        brush = Brush.linearGradient(colors.heroGradient),
                        shape = MaterialTheme.shapes.extraLarge
                    )
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(colors.heroBadgeContainer, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(colors.accentRole, CircleShape)
                    )
                    Text(
                        text = banner.badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onHero
                    )
                }
                Text(
                    text = banner.tag,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onHero
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    colors.heroScrimLow,
                                    colors.heroScrim,
                                    colors.heroScrimHigh
                                )
                            )
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(
                                        colors.onHero,
                                        colors.accentRole
                                    )
                                ),
                                shape = MaterialTheme.shapes.large
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = banner.iconText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.iconOnContainer
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = banner.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onHero
                        )
                        Text(
                            text = banner.subtitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onHero
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = banner.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MarketplaceMetaItem(label = AppStrings.ui_rating, value = "4.9")
                    MarketplaceMetaItem(label = AppStrings.ui_download, value = "12k+")
                    MarketplaceMetaItem(label = AppStrings.ui_update, value = AppStrings.ui_this_week)
                }

                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Text(AppStrings.ui_view_details)
                }
            }
        }
    }
}

@Composable
private fun MarketplaceMetaItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MarketplaceSectionIntro(layoutMode: MarketplaceLayoutMode) {
    val titleStyle = when (layoutMode) {
        MarketplaceLayoutMode.Compact -> MaterialTheme.typography.headlineMedium
        MarketplaceLayoutMode.Medium -> MaterialTheme.typography.displaySmall
        MarketplaceLayoutMode.Expanded -> MaterialTheme.typography.displayMedium
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = AppStrings.ui_browse_like_app_store,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = AppStrings.ui_discover_tools_that_work_you,
            style = titleStyle,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = AppStrings.ui_filter_purpose_compare_ratings_downloads_then_decide_whether_install,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MarketplaceCategoryRow() {
    val categories = listOf(AppStrings.ui_all to true, AppStrings.ui_productivity_tools to false, AppStrings.ui_developer to false, AppStrings.ui_safe to false)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { (label, active) ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = label,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    labelColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ),
                border = null
            )
        }
    }
}

@Composable
private fun MarketplaceSection(
    title: String,
    actionLabel: String,
    cards: List<MarketplaceStaticCardUi>,
    layoutMode: MarketplaceLayoutMode,
) {
    BoxWithConstraints {
        val minCardWidth = when (layoutMode) {
            MarketplaceLayoutMode.Compact -> 280.dp
            MarketplaceLayoutMode.Medium -> 300.dp
            MarketplaceLayoutMode.Expanded -> 316.dp
        }
        val columns = resolveMarketplaceSectionColumns(
            availableWidth = maxWidth,
            minCardWidth = minCardWidth,
            maxColumns = when (layoutMode) {
                MarketplaceLayoutMode.Compact -> 1
                MarketplaceLayoutMode.Medium -> 2
                MarketplaceLayoutMode.Expanded -> 3
            }
        ).coerceAtMost(max(1, cards.size))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (columns <= 1) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEach { card ->
                        MarketplacePluginCard(card = card)
                    }
                }
            } else {
                MarketplaceCardGrid(cards = cards, columns = columns)
            }
        }
    }
}

@Composable
private fun MarketplaceCardGrid(
    cards: List<MarketplaceStaticCardUi>,
    columns: Int,
) {
    val rows = cards.chunked(columns)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowCards.forEach { card ->
                    MarketplacePluginCard(
                        modifier = Modifier.weight(1f),
                        card = card
                    )
                }
                repeat(columns - rowCards.size) {
                    SpacerCardPlaceholder(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SpacerCardPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}

@Composable
private fun MarketplacePluginCard(
    card: MarketplaceStaticCardUi,
    modifier: Modifier = Modifier,
) {
    val statusToneRole = marketplaceToneColors(card.statusTone).status
    val iconToneRoles = marketplaceToneColors(card.iconTone)
    Column(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(
                            brush = Brush.linearGradient(iconToneRoles.iconContainerRoles),
                            shape = MaterialTheme.shapes.large
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = card.iconText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = iconToneRoles.iconOnContainer
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = card.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = card.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedButton(onClick = {}, shape = MaterialTheme.shapes.large) {
                    Text(AppStrings.ui_get)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 14.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MarketplaceStatPill(icon = Icons.Filled.Star, value = card.rating)
                    MarketplaceTextMeta(text = card.ratingCount)
                    MarketplaceTextMeta(text = card.downloads)
                    MarketplaceTextMeta(text = card.version)
                }

                MarketplaceTextMeta(text = marketplacePublisherText(card.category))

                Text(
                    text = card.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = marketplaceToneColors(card.statusTone).statusContainer,
                            shape = CircleShape
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = card.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusToneRole,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = AppStrings.ui_details,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketplacePagingSection(
    title: String,
    pagingItems: LazyPagingItems<PluginCardUi>,
    layoutMode: MarketplaceLayoutMode,
) {
    BoxWithConstraints {
        val minCardWidth = when (layoutMode) {
            MarketplaceLayoutMode.Compact -> 280.dp
            MarketplaceLayoutMode.Medium -> 300.dp
            MarketplaceLayoutMode.Expanded -> 316.dp
        }
        val columns = resolveMarketplaceSectionColumns(
            availableWidth = maxWidth,
            minCardWidth = minCardWidth,
            maxColumns = when (layoutMode) {
                MarketplaceLayoutMode.Compact -> 1
                MarketplaceLayoutMode.Medium -> 2
                MarketplaceLayoutMode.Expanded -> 3
            }
        )
        val cards = (0 until pagingItems.itemCount).mapNotNull { pagingItems[it] }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            PageStateLayout(
                state = marketplaceSectionPageViewState(
                    refresh = pagingItems.loadState.refresh,
                    itemCount = cards.size,
                ),
                fillMaxSize = false,
                refreshState = marketplaceSectionRefreshState(
                    refresh = pagingItems.loadState.refresh,
                    itemCount = cards.size,
                ),
                onRefresh = pagingItems::refresh,
                onRetry = pagingItems::retry,
                onRetryRefresh = pagingItems::retry,
                loading = { LoadingBase(loadingText = AppStrings.ui_loading_plugin) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (columns <= 1) {
                        cards.forEach { card ->
                            MarketplaceDynamicPluginCard(card = card)
                        }
                    } else {
                        MarketplaceDynamicCardGrid(cards = cards, columns = columns)
                    }
                    val append = pagingItems.loadState.append
                    val appendState = when {
                        append is LoadState.Loading -> PageAppendState.Loading
                        append is LoadState.Error -> PageAppendState.Error(
                            append.error.message ?: AppStrings.ui_failed_load_more,
                        )
                        append is LoadState.NotLoading && append.endOfPaginationReached -> PageAppendState.End
                        else -> PageAppendState.Idle
                    }
                    PageAppendFooter(
                        state = appendState,
                        onRetry = pagingItems::retry,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketplaceDynamicCardGrid(
    cards: List<PluginCardUi>,
    columns: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(columns).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowCards.forEach { card ->
                    MarketplaceDynamicPluginCard(
                        modifier = Modifier.weight(1f),
                        card = card
                    )
                }
                repeat(columns - rowCards.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MarketplaceDynamicPluginCard(
    card: PluginCardUi,
    modifier: Modifier = Modifier,
) {
    val staticCard = MarketplaceStaticCardUi(
        title = card.title,
        category = card.category,
        summary = card.summary,
        rating = card.rating,
        ratingCount = AppStrings.ui_arg0_rating.format(arg0 = card.ratingCount),
        downloads = AppStrings.ui_arg0_download.format(arg0 = card.downloads),
        version = card.version,
        pluginId = card.pluginId,
        authorId = card.authorId,
        status = card.status,
        statusTone = MarketplaceVisualTone.Primary,
        iconText = card.title.take(2).uppercase(),
        iconTone = MarketplaceVisualTone.Secondary
    )
    MarketplacePluginCard(card = staticCard, modifier = modifier)
}

private fun marketplacePublisherText(category: String): String {
    val publisher = category.substringBefore(" · ").trim()
    return if (publisher.isEmpty()) {
        AppStrings.ui_plugin_author
    } else {
        AppStrings.ui_arg0.format(arg0 = publisher)
    }
}

@Composable
private fun MarketplaceStatPill(
    icon: ImageVector,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class MarketplaceToneColors(
    val heroGradient: List<Color>,
    val iconContainerRoles: List<Color>,
    val accentRole: Color,
    val status: Color,
    val statusContainer: Color,
    val onHero: Color,
    val heroBadgeContainer: Color,
    val heroScrimLow: Color,
    val heroScrim: Color,
    val heroScrimHigh: Color,
    val iconOnContainer: Color,
)

@Composable
private fun marketplaceToneColors(tone: MarketplaceVisualTone): MarketplaceToneColors {
    val colorScheme = MaterialTheme.colorScheme
    return when (tone) {
        MarketplaceVisualTone.Primary -> MarketplaceToneColors(
            heroGradient = listOf(colorScheme.primary, colorScheme.primaryContainer),
            iconContainerRoles = listOf(colorScheme.primaryContainer, colorScheme.surfaceContainerHighest),
            accentRole = colorScheme.primaryContainer,
            status = colorScheme.primary,
            statusContainer = colorScheme.primaryContainer,
            onHero = colorScheme.onPrimary,
            heroBadgeContainer = colorScheme.primaryContainer,
            heroScrimLow = colorScheme.primary,
            heroScrim = colorScheme.scrim,
            heroScrimHigh = colorScheme.scrim,
            iconOnContainer = colorScheme.onPrimaryContainer
        )
        MarketplaceVisualTone.Secondary -> MarketplaceToneColors(
            heroGradient = listOf(colorScheme.secondary, colorScheme.secondaryContainer),
            iconContainerRoles = listOf(colorScheme.secondaryContainer, colorScheme.surfaceContainerHighest),
            accentRole = colorScheme.secondaryContainer,
            status = colorScheme.secondary,
            statusContainer = colorScheme.secondaryContainer,
            onHero = colorScheme.onSecondary,
            heroBadgeContainer = colorScheme.secondaryContainer,
            heroScrimLow = colorScheme.secondary,
            heroScrim = colorScheme.scrim,
            heroScrimHigh = colorScheme.scrim,
            iconOnContainer = colorScheme.onSecondaryContainer
        )
        MarketplaceVisualTone.Tertiary -> MarketplaceToneColors(
            heroGradient = listOf(colorScheme.tertiary, colorScheme.tertiaryContainer),
            iconContainerRoles = listOf(colorScheme.tertiaryContainer, colorScheme.surfaceContainerHighest),
            accentRole = colorScheme.tertiaryContainer,
            status = colorScheme.tertiary,
            statusContainer = colorScheme.tertiaryContainer,
            onHero = colorScheme.onTertiary,
            heroBadgeContainer = colorScheme.tertiaryContainer,
            heroScrimLow = colorScheme.tertiary,
            heroScrim = colorScheme.scrim,
            heroScrimHigh = colorScheme.scrim,
            iconOnContainer = colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun MarketplaceTextMeta(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

internal fun marketplaceSectionPageViewState(
    refresh: LoadState,
    itemCount: Int,
): PageViewState = resolvePageViewState(
    isLoading = refresh is LoadState.Loading && itemCount == 0,
    errorState = (refresh as? LoadState.Error)?.takeIf { itemCount == 0 }?.let { failed ->
        PageErrorState(
            PageErrorType.General,
            failed.error.message ?: AppStrings.ui_plugin_loading_failed,
        )
    },
    isEmpty = itemCount == 0,
    emptyMessage = AppStrings.ui_there_no_plugins_yet,
)

internal fun marketplaceSectionRefreshState(
    refresh: LoadState,
    itemCount: Int,
): PageRefreshState = when {
    refresh is LoadState.Loading && itemCount > 0 -> PageRefreshState.Refreshing
    refresh is LoadState.Error && itemCount > 0 -> PageRefreshState.Error(
        refresh.error.message ?: AppStrings.ui_plugin_loading_failed,
    )
    else -> PageRefreshState.Idle
}
