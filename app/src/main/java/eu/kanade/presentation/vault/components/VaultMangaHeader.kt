package eu.kanade.presentation.vault.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.kanade.presentation.vault.formatBytes
import eu.kanade.presentation.vault.primaryActionChapter
import eu.kanade.tachiyomi.ui.vault.VaultMangaScreenModel
import tachiyomi.domain.vault.model.VaultCacheState
import tachiyomi.domain.vault.model.VaultLabel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VaultMangaHeader(
    state: VaultMangaScreenModel.State,
    onPrimaryAction: () -> Unit,
    onClickAssignLabel: (VaultLabel) -> Unit,
    onClickCreateLabel: (String) -> Unit,
    onClickRemoveLabel: (VaultLabel) -> Unit,
    onClickToggleLabelSensitivity: (VaultLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga = state.manga ?: return
    var selectedLabel by remember { mutableStateOf<VaultLabel?>(null) }
    var showAddLabel by remember { mutableStateOf(false) }
    val cachedCount = state.chapters.count { it.state == VaultCacheState.CACHED }
    val primaryAction = state.primaryActionChapter()
    val primaryActionText = primaryAction?.let { stringResource(MR.strings.vault_action_read) }

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
            VaultCover(
                coverUri = state.coverUri,
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
                VaultLabelChips(
                    labels = state.mangaLabels,
                    pendingLabelIdentities = state.pendingLabelIdentities,
                    onClickLabel = { selectedLabel = it },
                    onClickAdd = { showAddLabel = true },
                )
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

    selectedLabel?.let { label ->
        VaultLabelActionSheet(
            label = label,
            onToggleSensitivity = {
                selectedLabel = null
                onClickToggleLabelSensitivity(label)
            },
            onRemove = {
                selectedLabel = null
                onClickRemoveLabel(label)
            },
            onDismissRequest = { selectedLabel = null },
        )
    }

    if (showAddLabel) {
        VaultAddLabelDialog(
            state = state,
            onAssignLabel = {
                showAddLabel = false
                onClickAssignLabel(it)
            },
            onCreateLabel = {
                showAddLabel = false
                onClickCreateLabel(it)
            },
            onDismissRequest = { showAddLabel = false },
        )
    }
}

@Composable
private fun VaultCover(
    coverUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box {
            if (coverUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp),
                )
            }
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
