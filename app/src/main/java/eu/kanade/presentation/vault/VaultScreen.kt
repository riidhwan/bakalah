package eu.kanade.presentation.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.tachiyomi.ui.vault.VaultScreenModel
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.plus
import java.text.DecimalFormat

@Composable
fun VaultScreen(
    state: VaultScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickRefresh: () -> Unit,
    onClickManga: (Long) -> Unit,
    onLoadCover: (Long) -> Unit,
    onLabelFilterChange: (String?) -> Unit,
    onSortChange: (VaultScreenModel.Sort) -> Unit,
    onIncludeSensitiveChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { Text(text = stringResource(MR.strings.label_vault)) },
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    val includeSensitiveTitle = stringResource(
                        if (state.includeSensitiveContent) {
                            MR.strings.vault_action_hide_sensitive
                        } else {
                            MR.strings.vault_action_include_sensitive
                        },
                    )
                    val includeSensitiveTint = if (state.includeSensitiveContent) {
                        MaterialTheme.colorScheme.active
                    } else {
                        LocalContentColor.current
                    }
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = includeSensitiveTitle,
                                icon = Icons.Outlined._18UpRating,
                                iconTint = includeSensitiveTint,
                                onClick = { onIncludeSensitiveChange(!state.includeSensitiveContent) },
                            ),
                        ),
                    )
                    VaultSortMenu(sort = state.sort, onSortChange = onSortChange)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            onRefresh = onClickRefresh,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxSize(),
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.vaults.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.vault_empty_no_configured_vault,
                    modifier = Modifier.padding(contentPadding),
                )
                state.mangaItems.isEmpty() -> VaultList(
                    state = state,
                    contentPadding = contentPadding,
                    onClickManga = onClickManga,
                    onLoadCover = onLoadCover,
                    onLabelFilterChange = onLabelFilterChange,
                    emptyMessage = MR.strings.vault_empty_collection,
                )
                state.visibleMangaItems.isEmpty() -> VaultList(
                    state = state,
                    contentPadding = contentPadding,
                    onClickManga = onClickManga,
                    onLoadCover = onLoadCover,
                    onLabelFilterChange = onLabelFilterChange,
                    emptyMessage = MR.strings.no_results_found,
                )
                else -> VaultList(
                    state = state,
                    contentPadding = contentPadding,
                    onClickManga = onClickManga,
                    onLoadCover = onLoadCover,
                    onLabelFilterChange = onLabelFilterChange,
                    emptyMessage = null,
                )
            }
        }
    }
}

@Composable
private fun VaultList(
    state: VaultScreenModel.State,
    contentPadding: PaddingValues,
    onClickManga: (Long) -> Unit,
    onLoadCover: (Long) -> Unit,
    onLabelFilterChange: (String?) -> Unit,
    emptyMessage: dev.icerock.moko.resources.StringResource?,
) {
    FastScrollLazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        item(
            key = "summary",
            contentType = "summary",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            VaultSummary(
                vault = state.selectedVault,
                mangaCount = state.mangaItems.size,
                localCacheUsageBytes = state.localCacheUsageBytes,
                vaultStorageUsageBytes = state.vaultStorageUsageBytes,
                modifier = Modifier.animateItem(),
            )
        }
        if (state.labels.isNotEmpty()) {
            item(
                key = "label-filter",
                contentType = "label-filter",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                VaultLabelFilterChips(
                    labels = state.labels,
                    selectedLabelIdentity = state.selectedLabelIdentity,
                    onLabelFilterChange = onLabelFilterChange,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (emptyMessage != null) {
            item(
                key = "empty",
                contentType = "empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                VaultGridEmptyItem(
                    stringRes = emptyMessage,
                    modifier = Modifier.animateItem(),
                )
            }
        } else {
            items(
                items = state.visibleMangaItems,
                key = { "vault-manga-${it.manga.id}" },
                contentType = { "vault-manga" },
            ) { item ->
                VaultMangaGridItem(
                    item = item,
                    coverUri = state.coverUris[item.manga.id],
                    onClick = { onClickManga(item.manga.id) },
                    onLoadCover = { onLoadCover(item.manga.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun VaultGridEmptyItem(
    stringRes: dev.icerock.moko.resources.StringResource,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(stringRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultSummary(
    vault: ContentVault?,
    mangaCount: Int,
    localCacheUsageBytes: Long,
    vaultStorageUsageBytes: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = buildString {
                append(vault?.displayName ?: stringResource(MR.strings.label_vault))
                append(" · ")
                append(stringResource(MR.strings.vault_manga_count, mangaCount))
                append(" · ")
                append(formatBytes(vaultStorageUsageBytes))
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VaultSortMenu(
    sort: VaultScreenModel.Sort,
    onSortChange: (VaultScreenModel.Sort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Sort,
            contentDescription = stringResource(MR.strings.action_sort),
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        VaultScreenModel.Sort.entries.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label()) },
                onClick = {
                    expanded = false
                    onSortChange(item)
                },
            )
        }
    }
}

@Composable
private fun VaultMangaGridItem(
    item: VaultScreenModel.VaultMangaItem,
    coverUri: String?,
    onClick: () -> Unit,
    onLoadCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(item.manga.id) {
        onLoadCover()
    }
    MangaCompactGridItem(
        title = item.manga.metadata.title,
        coverData = coverUri,
        coverBadgeStart = {
            if (item.cachedCount > 0) {
                Badge(
                    text = "${item.cachedCount}/${item.chapterCount}",
                    color = MaterialTheme.colorScheme.tertiary,
                    textColor = MaterialTheme.colorScheme.onTertiary,
                )
            }
        },
        coverBadgeEnd = {
            when {
                item.failedCount > 0 -> Badge(
                    imageVector = Icons.Outlined.ErrorOutline,
                    color = MaterialTheme.colorScheme.error,
                    iconColor = MaterialTheme.colorScheme.onError,
                )
                item.queuedCount > 0 -> Badge(
                    imageVector = Icons.Outlined.HourglassEmpty,
                )
                item.isSensitive -> Badge(
                    text = stringResource(MR.strings.vault_label_sensitive),
                    color = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        onClick = onClick,
        onLongClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun VaultLabelFilterChips(
    labels: List<VaultLabel>,
    selectedLabelIdentity: String?,
    onLabelFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(null) + labels.map { it.identity.value }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(0, 1).forEach { rowIndex ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.drop(rowIndex)
                    .filterIndexed { index, _ -> index % 2 == 0 }
                    .forEach { labelIdentity ->
                        val label = labels.firstOrNull { it.identity.value == labelIdentity }
                        VaultLabelFilterChip(
                            label = label,
                            selected = labelIdentity == selectedLabelIdentity,
                            onClick = {
                                if (labelIdentity == null || labelIdentity != selectedLabelIdentity) {
                                    onLabelFilterChange(labelIdentity)
                                }
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun VaultLabelFilterChip(
    label: VaultLabel?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = label?.name ?: stringResource(MR.strings.vault_filter_all),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            border = label?.let {
                FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = if (it.isSensitive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    selectedBorderColor = if (it.isSensitive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            } ?: FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
        )
    }
}

@Composable
private fun VaultScreenModel.Sort.label(): String {
    val res = when (this) {
        VaultScreenModel.Sort.TITLE -> MR.strings.action_sort_alpha
        VaultScreenModel.Sort.LATEST_CHAPTER -> MR.strings.action_sort_latest_chapter
        VaultScreenModel.Sort.CHAPTER_COUNT -> MR.strings.action_sort_total
    }
    return stringResource(res)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    val pattern = if (value >= 10 || unitIndex == 0) "0" else "0.0"
    return "${DecimalFormat(pattern).format(value)} ${units[unitIndex]}"
}
