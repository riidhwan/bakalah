package eu.kanade.presentation.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.vault.ImportRequestsScreenModel
import tachiyomi.domain.vault.model.VaultImportRequestSummary
import tachiyomi.domain.vault.model.VaultImportRequestWorkflow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Duration.Companion.milliseconds
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

@Composable
fun ImportRequestsScreen(
    state: ImportRequestsScreenModel.State,
    onClickRequest: (Long) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_import_requests),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.isError -> EmptyScreen(
                stringRes = MR.strings.internal_error,
                modifier = Modifier.padding(contentPadding),
            )
            state.requests.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.vault_import_requests_empty,
                modifier = Modifier.padding(contentPadding),
            )
            else -> ImportRequestList(
                requests = state.requests,
                contentPadding = contentPadding,
                onClickRequest = onClickRequest,
            )
        }
    }
}

@Composable
private fun ImportRequestList(
    requests: List<VaultImportRequestSummary>,
    contentPadding: PaddingValues,
    onClickRequest: (Long) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding) {
        itemsIndexed(
            items = requests,
            key = { _, request -> "vault-import-request-${request.id}" },
        ) { index, request ->
            ImportRequestListItem(
                request = request,
                onClick = { onClickRequest(request.id) },
            )
            if (index < requests.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ImportRequestListItem(
    request: VaultImportRequestSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = request.sourceCoverData(),
                modifier = Modifier.height(72.dp),
                contentDescription = request.sourceMangaTitle.orEmpty(),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = request.targetTitle(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = request.createdAt.compactCreatedTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = request.sourceSummary(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Badge(text = request.workflow.badgeLabel())
                }
                Text(
                    text = request.countSummary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun VaultImportRequestWorkflow.label(): String {
    return stringResource(
        when (this) {
            VaultImportRequestWorkflow.LOCAL_IMPORT -> MR.strings.vault_import_request_workflow_local_import
            VaultImportRequestWorkflow.LIBRARY_CAPTURE -> MR.strings.vault_import_request_workflow_library_capture
        },
    )
}

@Composable
private fun VaultImportRequestSummary.targetTitle(): String {
    val newTitle = createNewTitle
    val targetTitle = targetMangaTitle
    return when {
        targetTitle != null -> targetTitle
        newTitle != null -> newTitle
        activeMangaIdentity != null || targetMangaId != null -> stringResource(
            MR.strings.vault_import_request_target_unavailable,
        )
        else -> stringResource(MR.strings.vault_import_request_target_unresolved)
    }
}

@Composable
private fun VaultImportRequestSummary.sourceSummary(): String {
    return sourceMangaTitle?.let {
        stringResource(MR.strings.vault_import_request_source_manga, it)
    } ?: stringResource(MR.strings.vault_import_request_source_manga_unavailable)
}

@Composable
private fun VaultImportRequestWorkflow.badgeLabel(): String {
    return stringResource(
        when (this) {
            VaultImportRequestWorkflow.LOCAL_IMPORT -> MR.strings.label_local
            VaultImportRequestWorkflow.LIBRARY_CAPTURE -> MR.strings.label_library
        },
    )
}

private fun VaultImportRequestSummary.sourceCoverData(): MangaCoverModel? {
    val sourceId = sourceMangaSourceId ?: return null
    return MangaCoverModel(
        mangaId = mangaId,
        sourceId = sourceId,
        isMangaFavorite = sourceMangaFavorite,
        url = sourceMangaThumbnailUrl,
        lastModified = sourceMangaCoverLastModified,
    )
}

@Composable
private fun VaultImportRequestSummary.countSummary(): String {
    return listOfNotNull(
        stringResource(MR.strings.vault_import_request_progress, completedChapters, totalChapters),
        failedChapters.takeIf { it > 0 }?.let {
            stringResource(MR.strings.vault_import_request_failed_short, it)
        },
        replacedChapters.takeIf { it > 0 }?.let {
            stringResource(MR.strings.vault_import_request_replaced_short, it)
        },
    ).joinToString(" · ")
}

@Composable
private fun Long.compactCreatedTime(): String {
    val now = System.currentTimeMillis()
    val age = (now - this).coerceAtLeast(0).milliseconds
    val compactTime = when {
        age.inWholeMinutes < 1 -> return stringResource(MR.strings.vault_import_request_created_now)
        age.inWholeHours < 1 -> "${age.inWholeMinutes}m"
        age.inWholeDays < 1 -> "${age.inWholeHours}h"
        age.inWholeDays < 30 -> "${age.inWholeDays}d"
        age.inWholeDays < 365 -> "${age.inWholeDays / 30}mo"
        else -> "${age.inWholeDays / 365}y"
    }
    return stringResource(MR.strings.vault_import_request_created_ago, compactTime)
}
