package eu.kanade.presentation.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.vault.ImportRequestChaptersScreenModel
import tachiyomi.domain.vault.model.VaultImportRequest
import tachiyomi.domain.vault.model.VaultImportRequestChapter
import tachiyomi.domain.vault.model.VaultImportRequestChapterState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ImportRequestChaptersScreen(
    state: ImportRequestChaptersScreenModel.State,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.vault_import_request_detail_title),
                navigateUp = navigateUp,
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
            state.request == null -> EmptyScreen(
                stringRes = MR.strings.vault_import_request_not_found,
                modifier = Modifier.padding(contentPadding),
            )
            else -> ImportRequestChapterList(
                request = state.request,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun ImportRequestChapterList(
    request: VaultImportRequest,
    contentPadding: PaddingValues,
) {
    LazyColumn(contentPadding = contentPadding) {
        itemsIndexed(
            items = request.chapters,
            key = { _, chapter -> "vault-import-request-${request.id}-${chapter.selectionId}" },
        ) { index, chapter ->
            ImportRequestChapterItem(chapter = chapter)
            if (index < request.chapters.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ImportRequestChapterItem(
    chapter: VaultImportRequestChapter,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = chapter.chapterTitle(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            chapter.failureCategory?.let {
                MetadataLine(text = stringResource(MR.strings.vault_import_request_chapter_failure, it))
            }
            chapter.processedAt?.let {
                MetadataLine(
                    text = stringResource(MR.strings.vault_import_request_chapter_processed, it.compactElapsedTime()),
                )
            }
        }
        Box(
            modifier = Modifier.width(88.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ChapterStateIndicator(state = chapter.state)
        }
    }
}

@Composable
private fun MetadataLine(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VaultImportRequestChapter.chapterTitle(): String {
    return chapterTitle ?: stringResource(MR.strings.vault_import_request_selection_id, selectionId)
}

@Composable
private fun ChapterStateIndicator(
    state: VaultImportRequestChapterState,
) {
    when (state) {
        VaultImportRequestChapterState.PENDING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        VaultImportRequestChapterState.COMPLETED -> {
            SuccessChip()
        }
        VaultImportRequestChapterState.FAILED -> {
            StatusChip(
                text = stringResource(MR.strings.vault_import_request_chapter_status_failed),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SuccessChip() {
    StatusChip(
        text = stringResource(MR.strings.vault_import_request_chapter_status_success),
        containerColor = Color(0xFFE8F8EE),
        contentColor = Color(0xFF0A8F3C),
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF00C853),
            )
        },
    )
}

@Composable
private fun StatusChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun Long.compactElapsedTime(): String {
    val now = System.currentTimeMillis()
    val age = (now - this).coerceAtLeast(0).milliseconds
    return when {
        age.inWholeMinutes < 1 -> stringResource(MR.strings.vault_import_request_created_now)
        age.inWholeHours < 1 -> "${age.inWholeMinutes}m"
        age.inWholeDays < 1 -> "${age.inWholeHours}h"
        age.inWholeDays < 30 -> "${age.inWholeDays}d"
        age.inWholeDays < 365 -> "${age.inWholeDays / 30}mo"
        else -> "${age.inWholeDays / 365}y"
    }
}
