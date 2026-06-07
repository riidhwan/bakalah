package eu.kanade.presentation.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import eu.kanade.tachiyomi.ui.vault.VaultScreenModel
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
    onClickDelete: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = state.manga?.metadata?.title ?: stringResource(MR.strings.label_vault),
                subtitle = state.manga?.metadata?.author ?: state.manga?.metadata?.artist,
                navigateUp = navigateUp,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.vault_action_delete_manga),
                                icon = Icons.Outlined.DeleteForever,
                                iconTint = MaterialTheme.colorScheme.error,
                                enabled = !state.isDeleting,
                                onClick = { showDeleteConfirmation = true },
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.vault_action_cache),
                                icon = Icons.Outlined.Download,
                                onClick = {
                                    state.chapters.firstOrNull { it.state == VaultCacheState.VAULT_ONLY }
                                        ?.let(onClickCache)
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
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .animateItem(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(MR.strings.vault_local_cache_usage, formatBytes(state.localCacheUsageBytes)),
                        )
                    },
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(MR.strings.vault_storage_usage, formatBytes(state.vaultStorageUsageBytes)))
                    },
                )
            }
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
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun VaultChapterListItem(
    item: VaultMangaScreenModel.VaultChapterItem,
    onClickCache: () -> Unit,
    onClickEvict: () -> Unit,
    onClickRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = item.chapter.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StateChip(state = item.state)
        }
        Text(
            text = stringResource(MR.strings.vault_chapter_size, formatBytes(item.chapter.content.sizeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.state) {
                VaultCacheState.VAULT_ONLY -> AssistChip(
                    onClick = onClickCache,
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    label = { Text(stringResource(MR.strings.vault_action_cache)) },
                )
                VaultCacheState.CACHED -> AssistChip(
                    onClick = onClickEvict,
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    label = { Text(stringResource(MR.strings.vault_action_evict)) },
                )
                VaultCacheState.QUEUED,
                VaultCacheState.FAILED,
                VaultCacheState.INTEGRITY_FAULT,
                -> AssistChip(
                    onClick = onClickRetry,
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    label = { Text(stringResource(MR.strings.action_retry)) },
                )
                VaultCacheState.CACHING,
                VaultCacheState.PUBLISHING,
                -> Unit
            }
        }
    }
}

@Composable
private fun StateChip(state: VaultCacheState) {
    AssistChip(
        onClick = {},
        label = { Text(state.label()) },
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
