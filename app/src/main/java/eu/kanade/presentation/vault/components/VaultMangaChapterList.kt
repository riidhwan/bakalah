package eu.kanade.presentation.vault.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.vault.formatBytes
import eu.kanade.presentation.vault.primaryActionChapter
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import eu.kanade.tachiyomi.ui.vault.remotePathFor
import eu.kanade.tachiyomi.ui.vault.remoteThumbnailPathFor
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VaultChapterList(
    state: VaultMangaScreenModel.State,
    contentPadding: PaddingValues,
    onLongPressPath: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickDownloadCbz: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onLongPressThumbnailPath: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickDownloadThumbnail: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickRead: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onChapterThumbnailVisible: (VaultMangaScreenModel.VaultChapterItem) -> Unit,
    onClickAssignLabel: (VaultLabel) -> Unit,
    onClickCreateLabel: (String) -> Unit,
    onClickRemoveLabel: (VaultLabel) -> Unit,
    onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        item(key = "summary", contentType = "summary") {
            VaultMangaHeader(
                state = state,
                onPrimaryAction = {
                    state.primaryActionChapter()?.let { chapter ->
                        onClickRead(chapter)
                    }
                },
                onClickAssignLabel = onClickAssignLabel,
                onClickCreateLabel = onClickCreateLabel,
                onClickRemoveLabel = onClickRemoveLabel,
                onClickToggleLabelSensitivity = onClickToggleLabelSensitivity,
                modifier = Modifier.animateItem(),
            )
        }
        items(
            items = state.chapters,
            key = { "vault-chapter-${it.chapter.id}" },
            contentType = { "vault-chapter" },
        ) { item ->
            VaultChapterListItem(
                state = state,
                item = item,
                onLongPressPath = { onLongPressPath(item) },
                onClickDownloadCbz = { onClickDownloadCbz(item) },
                onLongPressThumbnailPath = { onLongPressThumbnailPath(item) },
                onClickDownloadThumbnail = { onClickDownloadThumbnail(item) },
                onClickRead = { onClickRead(item) },
                onChapterThumbnailVisible = { onChapterThumbnailVisible(item) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun VaultChapterListItem(
    state: VaultMangaScreenModel.State,
    item: VaultMangaScreenModel.VaultChapterItem,
    onLongPressPath: () -> Unit,
    onClickDownloadCbz: () -> Unit,
    onLongPressThumbnailPath: () -> Unit,
    onClickDownloadThumbnail: () -> Unit,
    onClickRead: () -> Unit,
    onChapterThumbnailVisible: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showProperties by remember { mutableStateOf(false) }

    LaunchedEffect(item.chapter.id, item.needsThumbnailLoad) {
        if (item.needsThumbnailLoad) {
            onChapterThumbnailVisible()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClickRead)
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VaultChapterThumbnail(
                thumbnailUri = item.thumbnailUri,
                modifier = Modifier.size(56.dp),
            )
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
            ChapterStateIcon(state = item.state)
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
                        text = { Text(stringResource(MR.strings.vault_chapter_properties)) },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showProperties = true
                        },
                    )
                }
            }
        }
    }

    if (showProperties) {
        VaultChapterPropertiesSheet(
            state = state,
            item = item,
            onLongPressPath = onLongPressPath,
            onClickDownloadCbz = onClickDownloadCbz,
            onLongPressThumbnailPath = onLongPressThumbnailPath,
            onClickDownloadThumbnail = onClickDownloadThumbnail,
            onDismissRequest = { showProperties = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultChapterPropertiesSheet(
    state: VaultMangaScreenModel.State,
    item: VaultMangaScreenModel.VaultChapterItem,
    onLongPressPath: () -> Unit,
    onClickDownloadCbz: () -> Unit,
    onLongPressThumbnailPath: () -> Unit,
    onClickDownloadThumbnail: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val remotePath = state.remotePathFor(item)
    val thumbnailPath = state.remoteThumbnailPathFor(item)
    val isExporting = item.chapter.id in state.exportingChapterIds
    val isExportingThumbnail = item.chapter.id in state.exportingThumbnailChapterIds
    val canUseRemotePath = remotePath != null
    val canDownload = canUseRemotePath && item.canDownloadCbz && !isExporting
    val canUseThumbnailRemotePath = thumbnailPath != null
    val canDownloadThumbnail = canUseThumbnailRemotePath && !isExportingThumbnail

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = item.chapter.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VaultChapterPathProperty(
                label = stringResource(
                    if (canUseRemotePath) {
                        MR.strings.vault_chapter_remote_path
                    } else {
                        MR.strings.vault_chapter_content_path
                    },
                ),
                value = remotePath ?: item.chapter.content.path,
                isDownloading = isExporting,
                canDownload = canDownload,
                downloadContentDescription = stringResource(MR.strings.vault_chapter_download_cbz),
                onClickDownload = onClickDownloadCbz,
                onLongPressPath = onLongPressPath.takeIf { canUseRemotePath },
            )
            VaultChapterPathProperty(
                label = stringResource(MR.strings.vault_chapter_thumbnail_remote_path),
                value = thumbnailPath ?: item.chapter.thumbnail?.path ?: stringResource(MR.strings.not_applicable),
                isDownloading = isExportingThumbnail,
                canDownload = canDownloadThumbnail,
                downloadContentDescription = stringResource(MR.strings.vault_chapter_download_thumbnail),
                onClickDownload = onClickDownloadThumbnail,
                onLongPressPath = onLongPressThumbnailPath.takeIf { canUseThumbnailRemotePath },
            )
            VaultChapterProperty(
                label = stringResource(MR.strings.vault_chapter_file_size),
                value = formatBytes(item.chapter.content.sizeBytes),
            )
            VaultChapterProperty(
                label = stringResource(MR.strings.vault_chapter_device_state),
                value = item.state.label(),
            )
            if (!canUseRemotePath) {
                Text(
                    text = stringResource(MR.strings.vault_chapter_remote_path_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultChapterPathProperty(
    label: String,
    value: String,
    isDownloading: Boolean,
    canDownload: Boolean,
    downloadContentDescription: String,
    onClickDownload: () -> Unit,
    onLongPressPath: (() -> Unit)?,
) {
    val effectiveDownloadContentDescription = if (isDownloading) {
        stringResource(MR.strings.vault_chapter_downloading_cbz)
    } else {
        downloadContentDescription
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                modifier = Modifier.combinedClickable(
                    enabled = onLongPressPath != null,
                    onClick = {},
                    onLongClick = onLongPressPath,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(
            enabled = canDownload,
            onClick = onClickDownload,
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = effectiveDownloadContentDescription,
            )
        }
    }
}

@Composable
private fun VaultChapterProperty(
    label: String,
    value: String,
    selectable: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selectable) {
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VaultChapterThumbnail(
    thumbnailUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box {
            if (thumbnailUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUri)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterStateIcon(
    state: VaultCacheState,
) {
    when (state) {
        VaultCacheState.VAULT_ONLY,
        VaultCacheState.CACHED,
        -> Unit
        VaultCacheState.FAILED,
        VaultCacheState.INTEGRITY_FAULT,
        -> StateIcon(
            icon = if (state == VaultCacheState.INTEGRITY_FAULT) {
                Icons.Outlined.WarningAmber
            } else {
                Icons.Outlined.ErrorOutline
            },
            tint = MaterialTheme.colorScheme.error,
            contentDescription = state.label(),
        )
        VaultCacheState.QUEUED,
        VaultCacheState.CACHING,
        VaultCacheState.PUBLISHING,
        -> StateIcon(
            icon = Icons.Outlined.HourglassEmpty,
            contentDescription = state.label(),
        )
    }
}

@Composable
private fun StateIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
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
