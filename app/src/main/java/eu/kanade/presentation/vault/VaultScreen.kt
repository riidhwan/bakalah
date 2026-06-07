package eu.kanade.presentation.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import eu.kanade.tachiyomi.ui.vault.VaultScreenModel
import tachiyomi.domain.vault.model.ContentVault
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DecimalFormat

@Composable
fun VaultScreen(
    state: VaultScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickRefresh: () -> Unit,
    onClickManga: (Long) -> Unit,
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
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                emptyMessage = MR.strings.vault_empty_collection,
            )
            state.visibleMangaItems.isEmpty() -> VaultList(
                state = state,
                contentPadding = contentPadding,
                onClickManga = onClickManga,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                emptyMessage = MR.strings.no_results_found,
            )
            else -> VaultList(
                state = state,
                contentPadding = contentPadding,
                onClickManga = onClickManga,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
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
    onFilterChange: (VaultScreenModel.Filter) -> Unit,
    onSortChange: (VaultScreenModel.Sort) -> Unit,
    emptyMessage: dev.icerock.moko.resources.StringResource?,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            VaultSummary(
                vault = state.selectedVault,
                mangaCount = state.mangaItems.size,
                localCacheUsageBytes = state.localCacheUsageBytes,
                vaultStorageUsageBytes = state.vaultStorageUsageBytes,
                filter = state.filter,
                sort = state.sort,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                modifier = Modifier.animateItem(),
            )
        }
        if (emptyMessage != null) {
            item(key = "empty", contentType = "empty") {
                EmptyScreen(
                    stringRes = emptyMessage,
                    modifier = Modifier
                        .fillParentMaxSize()
                        .animateItem(),
                )
            }
        } else {
            items(
                items = state.visibleMangaItems,
                key = { "vault-manga-${it.manga.id}" },
                contentType = { "vault-manga" },
            ) { item ->
                VaultMangaListItem(
                    item = item,
                    onClick = { onClickManga(item.manga.id) },
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
    filter: VaultScreenModel.Filter,
    sort: VaultScreenModel.Sort,
    onFilterChange: (VaultScreenModel.Filter) -> Unit,
    onSortChange: (VaultScreenModel.Sort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = vault?.displayName ?: stringResource(MR.strings.label_vault),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ElevatedAssistChip(
                onClick = {},
                leadingIcon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                label = { Text(stringResource(MR.strings.vault_local_cache_usage, formatBytes(localCacheUsageBytes))) },
            )
            ElevatedAssistChip(
                onClick = {},
                leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                label = { Text(stringResource(MR.strings.vault_storage_usage, formatBytes(vaultStorageUsageBytes))) },
            )
        }
        Text(
            text = stringResource(MR.strings.vault_manga_count, mangaCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultFilterMenu(filter = filter, onFilterChange = onFilterChange)
            VaultSortMenu(sort = sort, onSortChange = onSortChange)
        }
        Text(
            text = vault?.lastCatalogueRefreshAt?.let {
                stringResource(MR.strings.vault_last_catalogue_refresh_known)
            } ?: stringResource(MR.strings.vault_last_catalogue_refresh_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultFilterMenu(
    filter: VaultScreenModel.Filter,
    onFilterChange: (VaultScreenModel.Filter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = true },
        leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
        label = { Text(filter.label()) },
    )
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
    AssistChip(
        onClick = { expanded = true },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
        label = { Text(sort.label()) },
    )
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
private fun VaultMangaListItem(
    item: VaultScreenModel.VaultMangaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = item.manga.metadata.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.failedCount > 0) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(MR.strings.vault_state_failed),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        val author = item.manga.metadata.author ?: item.manga.metadata.artist
        if (author != null) {
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                MR.strings.vault_manga_chapter_summary,
                item.chapterCount,
                item.cachedCount,
                item.queuedCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
