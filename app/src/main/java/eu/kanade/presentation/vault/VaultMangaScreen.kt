package eu.kanade.presentation.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun VaultMangaScreen(
    state: VaultMangaScreenModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onClickCache: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickEvict: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRetry: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = null,
                navigateUp = navigateUp,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.vault_action_delete_manga),
                                onClick = {
                                    if (!state.isDeleting) {
                                        showDeleteConfirmation = true
                                    }
                                },
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
            state.manga == null -> EmptyScreen(
                stringRes = MR.strings.vault_manga_not_found,
                modifier = Modifier.padding(contentPadding),
            )
            state.chapters.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.vault_empty_chapters,
                modifier = Modifier.padding(contentPadding),
            )
            else -> VaultChapterList(
                state = state,
                contentPadding = contentPadding,
                onClickCache = onClickCache,
                onClickEvict = onClickEvict,
                onClickRetry = onClickRetry,
                onClickRead = onClickRead,
            )
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(MR.strings.vault_delete_manga_confirm_title)) },
            text = { Text(stringResource(MR.strings.vault_delete_manga_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onClickDelete()
                    },
                ) {
                    Text(stringResource(MR.strings.vault_action_delete_manga))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun VaultChapterList(
    state: VaultMangaScreenModel.State,
    contentPadding: PaddingValues,
    onClickCache: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickEvict: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRetry: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            VaultMangaHeader(
                state = state,
                onPrimaryAction = {
                    state.primaryActionChapter()?.let { chapter ->
                        if (chapter.state == VaultCacheState.CACHED) {
                            onClickRead(chapter)
                        } else {
                            onClickCache(chapter)
                        }
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
        items(
            items = state.chapters,
            key = { "vault-chapter-${it.chapter.id}" },
            contentType = { "vault-chapter" },
        ) { item ->
            VaultChapterListItem(
                item = item,
                onClickCache = { onClickCache(item) },
                onClickEvict = { onClickEvict(item) },
                onClickRetry = { onClickRetry(item) },
                onClickRead = { onClickRead(item) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun VaultMangaHeader(
    state: VaultMangaScreenModel.State,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga = state.manga ?: return
    val cachedCount = state.chapters.count { it.state == VaultCacheState.CACHED }
    val primaryAction = state.primaryActionChapter()
    val primaryActionText = when {
        primaryAction == null -> null
        primaryAction.state == VaultCacheState.CACHED -> stringResource(MR.strings.vault_action_read_cached)
        cachedCount > 0 -> stringResource(MR.strings.vault_action_read_cached)
        else -> stringResource(MR.strings.vault_action_cache_first_chapter)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VaultCoverPlaceholder(
                modifier = Modifier
                    .width(88.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = manga.metadata.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manga.metadata.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VaultMangaStats(
                    chapterCount = state.chapters.size,
                    cachedCount = cachedCount,
                    vaultStorageUsageBytes = state.vaultStorageUsageBytes,
                )
                primaryActionText?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultCoverPlaceholder(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun VaultMangaStats(
    chapterCount: Int,
    cachedCount: Int,
    vaultStorageUsageBytes: Long,
) {
    Text(
        text = stringResource(
            MR.strings.vault_manga_detail_stats,
            chapterCount,
            cachedCount,
            formatBytes(vaultStorageUsageBytes),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VaultChapterListItem(
    item: VaultMangaScreenModel.VaultChapterItem,
    onClickCache: () -> Unit,
    onClickEvict: () -> Unit,
    onClickRetry: () -> Unit,
    onClickRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClickRead)
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            MR.strings.vault_chapter_availability,
                            formatBytes(item.chapter.content.sizeBytes),
                            item.state.availabilityLabel(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ChapterStateAction(
                state = item.state,
                onClickCache = onClickCache,
                onClickRetry = onClickRetry,
            )
            if (item.state == VaultCacheState.CACHED) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(MR.strings.action_menu_overflow_description),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.vault_action_evict_from_device)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onClickEvict()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterStateAction(
    state: VaultCacheState,
    onClickCache: () -> Unit,
    onClickRetry: () -> Unit,
) {
    when (state) {
        VaultCacheState.VAULT_ONLY -> IconButton(onClick = onClickCache) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = stringResource(MR.strings.vault_action_cache),
            )
        }
        VaultCacheState.FAILED,
        VaultCacheState.INTEGRITY_FAULT,
        -> IconButton(onClick = onClickRetry) {
            Icon(
                imageVector = if (state == VaultCacheState.INTEGRITY_FAULT) {
                    Icons.Outlined.WarningAmber
                } else {
                    Icons.Outlined.ErrorOutline
                },
                tint = MaterialTheme.colorScheme.error,
                contentDescription = stringResource(MR.strings.action_retry),
            )
        }
        VaultCacheState.QUEUED,
        VaultCacheState.CACHING,
        VaultCacheState.PUBLISHING,
        -> StateIcon(
            icon = Icons.Outlined.HourglassEmpty,
            contentDescription = state.label(),
        )
        VaultCacheState.CACHED -> Unit
    }
}

@Composable
private fun StateIcon(
    icon: ImageVector,
    contentDescription: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .size(24.dp),
    )
}

@Composable
private fun VaultCacheState.label(): String {
    val res = when (this) {
        VaultCacheState.VAULT_ONLY -> MR.strings.vault_state_vault_only
        VaultCacheState.QUEUED -> MR.strings.vault_state_queued
        VaultCacheState.CACHING -> MR.strings.vault_state_caching
        VaultCacheState.PUBLISHING -> MR.strings.vault_state_publishing
        VaultCacheState.CACHED -> MR.strings.vault_state_cached
        VaultCacheState.FAILED -> MR.strings.vault_state_failed
        VaultCacheState.INTEGRITY_FAULT -> MR.strings.vault_state_integrity_fault
    }
    return stringResource(res)
}

@Composable
private fun VaultCacheState.availabilityLabel(): String {
    val res = when (this) {
        VaultCacheState.VAULT_ONLY -> MR.strings.vault_state_in_vault
        VaultCacheState.CACHED -> MR.strings.vault_state_on_device
        VaultCacheState.QUEUED -> MR.strings.vault_state_queued
        VaultCacheState.CACHING -> MR.strings.vault_state_caching
        VaultCacheState.PUBLISHING -> MR.strings.vault_state_publishing
        VaultCacheState.FAILED -> MR.strings.vault_state_failed
        VaultCacheState.INTEGRITY_FAULT -> MR.strings.vault_state_integrity_fault
    }
    return stringResource(res)
}

private fun VaultMangaScreenModel.State.primaryActionChapter(): VaultMangaScreenModel.VaultChapterItem? {
    return chapters.firstOrNull { it.state == VaultCacheState.CACHED }
        ?: chapters.firstOrNull { it.state == VaultCacheState.VAULT_ONLY }
}
