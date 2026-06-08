package eu.kanade.presentation.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
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
    onFilterChange: (VaultScreenModel.Filter) -> Unit,
    onSortChange: (VaultScreenModel.Sort) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { Text(text = stringResource(MR.strings.label_vault)) },
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                actions = {
                    VaultFilterMenu(filter = state.filter, onFilterChange = onFilterChange)
                    VaultSortMenu(sort = state.sort, onSortChange = onSortChange)
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.vault_action_refresh_catalogue),
                                icon = Icons.Outlined.Refresh,
                                onClick = onClickRefresh,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
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
                emptyMessage = MR.strings.vault_empty_collection,
            )
            state.visibleMangaItems.isEmpty() -> VaultList(
                state = state,
                contentPadding = contentPadding,
                onClickManga = onClickManga,
                onLoadCover = onLoadCover,
                emptyMessage = MR.strings.no_results_found,
            )
            else -> VaultList(
                state = state,
                contentPadding = contentPadding,
                onClickManga = onClickManga,
                onLoadCover = onLoadCover,
                emptyMessage = null,
            )
        }
    }
}

@Composable
private fun VaultList(
    state: VaultScreenModel.State,
    contentPadding: PaddingValues,
    onClickManga: (Long) -> Unit,
    onLoadCover: (Long) -> Unit,
    emptyMessage: dev.icerock.moko.resources.StringResource?,
) {
    FastScrollLazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
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
        if (emptyMessage != null) {
            item(
                key = "empty",
                contentType = "empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                EmptyScreen(
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
                append(stringResource(MR.strings.vault_local_cache_usage, formatBytes(localCacheUsageBytes)))
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(stringResource(MR.strings.vault_storage_usage, formatBytes(vaultStorageUsageBytes)))
                append(" · ")
                append(
                    vault?.lastCatalogueRefreshAt?.let {
                        stringResource(MR.strings.vault_last_catalogue_refresh_known)
                    } ?: stringResource(MR.strings.vault_last_catalogue_refresh_never),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VaultFilterMenu(
    filter: VaultScreenModel.Filter,
    onFilterChange: (VaultScreenModel.Filter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Outlined.FilterList,
            contentDescription = stringResource(MR.strings.action_filter),
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        VaultScreenModel.Filter.entries.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label()) },
                onClick = {
                    expanded = false
                    onFilterChange(item)
                },
            )
        }
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
            }
        },
        onClick = onClick,
        onLongClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun VaultScreenModel.Filter.label(): String {
    val res = when (this) {
        VaultScreenModel.Filter.ALL -> MR.strings.vault_filter_all
        VaultScreenModel.Filter.CACHED -> MR.strings.vault_filter_cached
        VaultScreenModel.Filter.VAULT_ONLY -> MR.strings.vault_filter_vault_only
        VaultScreenModel.Filter.QUEUED -> MR.strings.vault_filter_queued
        VaultScreenModel.Filter.FAILED -> MR.strings.vault_filter_failed
    }
    return stringResource(res)
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
