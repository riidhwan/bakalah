package eu.kanade.presentation.vault

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.vault.components.VaultChapterList
import eu.kanade.presentation.vault.components.VaultMetadataEditDialog
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun VaultMangaScreen(
    state: VaultMangaScreenModel.State,
    snackbarHostState: SnackbarHostState,
    actions: VaultMangaScreenActions,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMetadataEdit by remember { mutableStateOf(false) }

    LaunchedEffect(state.metadataPublishSuccessCount) {
        if (state.metadataPublishSuccessCount > 0) {
            showMetadataEdit = false
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = null,
                navigateUp = actions.navigateUp,
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.vault_action_edit_metadata),
                                onClick = {
                                    showMetadataEdit = true
                                },
                            ),
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
                onClickCache = actions.onClickCache,
                onClickEvict = actions.onClickEvict,
                onClickRetry = actions.onClickRetry,
                onLongPressPath = actions.onLongPressPath,
                onClickDownloadCbz = actions.onClickDownloadCbz,
                onLongPressThumbnailPath = actions.onLongPressThumbnailPath,
                onClickDownloadThumbnail = actions.onClickDownloadThumbnail,
                onClickRead = actions.onClickRead,
                onChapterThumbnailVisible = actions.onChapterThumbnailVisible,
                onClickAssignLabel = actions.onClickAssignLabel,
                onClickCreateLabel = actions.onClickCreateLabel,
                onClickRemoveLabel = actions.onClickRemoveLabel,
                onClickToggleLabelSensitivity = actions.onClickToggleLabelSensitivity,
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
                        actions.onClickDelete()
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

    if (showMetadataEdit && state.manga != null) {
        VaultMetadataEditDialog(
            state = state,
            onDismissRequest = { showMetadataEdit = false },
            onSave = {
                showMetadataEdit = false
                actions.onClickSaveMetadata(it)
            },
        )
    }
}

data class VaultMangaScreenActions(
    val navigateUp: () -> Unit,
    val onClickCache: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickEvict: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickRetry: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onLongPressPath: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickDownloadCbz: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onLongPressThumbnailPath: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickDownloadThumbnail: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onChapterThumbnailVisible: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    val onClickDelete: () -> Unit,
    val onClickSaveMetadata: (VaultMangaScreenModel.VaultMetadataEdit) -> Unit,
    val onClickAssignLabel: (VaultLabel) -> Unit,
    val onClickCreateLabel: (String) -> Unit,
    val onClickRemoveLabel: (VaultLabel) -> Unit,
    val onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
)
