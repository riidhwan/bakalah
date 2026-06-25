package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.library.LibraryMangaGroupCandidateItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LibraryMangaGroupDialog(
    initialTitle: String,
    candidates: List<LibraryMangaGroupCandidateItem>,
    onConfirm: (List<Long>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var query by remember(initialTitle) { mutableStateOf(initialTitle) }
    var selectedMangaIds by remember(candidates) { mutableStateOf(emptySet<Long>()) }
    val visibleCandidates = remember(candidates, query, selectedMangaIds) {
        searchLibraryMangaGroupCandidates(
            candidates = candidates,
            query = query,
            selectedMangaIds = selectedMangaIds,
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.library_manga_group_add_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(MR.strings.library_manga_group_search_library)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visibleCandidates.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column {
                            visibleCandidates.forEach { candidate ->
                                val selected = candidate.manga.id in selectedMangaIds
                                CandidateItem(
                                    title = candidate.manga.title,
                                    sourceName = candidate.sourceName,
                                    selected = selected,
                                    onClick = {
                                        selectedMangaIds = if (selected) {
                                            selectedMangaIds - candidate.manga.id
                                        } else {
                                            selectedMangaIds + candidate.manga.id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedMangaIds.isNotEmpty(),
                onClick = { onConfirm(selectedMangaIds.toList()) },
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

fun searchLibraryMangaGroupCandidates(
    candidates: List<LibraryMangaGroupCandidateItem>,
    query: String,
    selectedMangaIds: Set<Long> = emptySet(),
    limit: Int = MAX_GROUP_CANDIDATES,
): List<LibraryMangaGroupCandidateItem> {
    val normalizedQuery = query.trim()
    return candidates
        .asSequence()
        .filter {
            it.manga.id in selectedMangaIds ||
                normalizedQuery.isBlank() ||
                it.manga.title.contains(normalizedQuery, ignoreCase = true) ||
                it.sourceName.contains(normalizedQuery, ignoreCase = true)
        }
        .sortedWith(
            compareBy<LibraryMangaGroupCandidateItem> { it.manga.id !in selectedMangaIds }
                .thenBy { it.manga.title.lowercase() }
                .thenBy { it.sourceName.lowercase() },
        )
        .take(limit)
        .toList()
}

@Composable
private fun CandidateItem(
    title: String,
    sourceName: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            }
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MAX_GROUP_CANDIDATES = 12
