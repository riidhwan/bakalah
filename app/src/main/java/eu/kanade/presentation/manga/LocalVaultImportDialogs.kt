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
import eu.kanade.tachiyomi.ui.manga.vault.LocalVaultImportTargetSelection
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.domain.vault.model.VaultMetadata
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LocalVaultTargetSetupDialog(
    initialTitle: String,
    targets: List<VaultManga>,
    selectedTarget: LocalVaultImportTargetSelection?,
    allowCreateNew: Boolean,
    allowUnlink: Boolean,
    pendingAddToVault: Boolean,
    onTargetSelected: (LocalVaultImportTargetSelection?, Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var selectedTargetId by remember(initialTitle, selectedTarget) {
        mutableStateOf((selectedTarget as? LocalVaultImportTargetSelection.Existing)?.mangaId)
    }
    val targetTitle = title.trim()
    val normalizedTitle = VaultMetadata.normalizeTitle(targetTitle)
    val exactMatches = if (normalizedTitle.isBlank()) {
        emptyList()
    } else {
        targets.filter { it.metadata.normalizedTitle == normalizedTitle }
    }
    val selectedExactTarget = selectedTargetId
        ?.let { id -> targets.firstOrNull { it.id == id } }
        ?.takeIf { it.metadata.normalizedTitle == normalizedTitle }
    val resolvedTarget = when {
        selectedExactTarget != null -> LocalVaultImportTargetSelection.Existing(selectedExactTarget.id)
        exactMatches.size == 1 -> LocalVaultImportTargetSelection.Existing(exactMatches.single().id)
        exactMatches.size > 1 -> null
        allowCreateNew && targetTitle.isNotBlank() -> LocalVaultImportTargetSelection.CreateNew(targetTitle)
        else -> null
    }
    val targetRequired = targetTitle.isBlank()
    val ambiguousTarget = targetTitle.isNotBlank() && exactMatches.size > 1 && selectedExactTarget == null
    val suggestedTargets = if (ambiguousTarget) {
        exactMatches
    } else {
        searchVaultImportTargets(
            targets = targets,
            query = targetTitle,
            selectedTargetId = selectedTargetId,
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.vault_import_choose_target)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        selectedTargetId = null
                    },
                    label = { Text(stringResource(MR.strings.vault_import_target_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = targetRequired || ambiguousTarget,
                    supportingText = {
                        when {
                            targetRequired -> Text(stringResource(MR.strings.vault_import_target_title_required))
                            ambiguousTarget -> Text(stringResource(MR.strings.vault_import_target_ambiguous))
                        }
                    },
                )
                if (suggestedTargets.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Column {
                            suggestedTargets.forEach { target ->
                                TargetSheetItem(
                                    label = target.metadata.title,
                                    selected = selectedTargetId == target.id ||
                                        resolvedTarget == LocalVaultImportTargetSelection.Existing(target.id),
                                    onClick = {
                                        title = target.metadata.title
                                        selectedTargetId = target.id
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
                enabled = resolvedTarget != null,
                onClick = {
                    resolvedTarget?.let { onTargetSelected(it, pendingAddToVault) }
                },
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (allowUnlink) {
                    TextButton(onClick = { onTargetSelected(null, pendingAddToVault) }) {
                        Text(stringResource(MR.strings.vault_import_unlink_target))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

fun searchVaultImportTargets(
    targets: List<VaultManga>,
    query: String,
    selectedTargetId: Long? = null,
    limit: Int = MAX_TARGET_SUGGESTIONS,
): List<VaultManga> {
    val normalizedQuery = VaultMetadata.normalizeTitle(query.trim())
    if (normalizedQuery.isBlank()) return emptyList()

    return targets
        .asSequence()
        .filter { target ->
            target.id == selectedTargetId ||
                target.metadata.normalizedTitle.contains(normalizedQuery)
        }
        .sortedWith(
            compareBy<VaultManga> { it.id != selectedTargetId }
                .thenBy { it.metadata.normalizedTitle != normalizedQuery }
                .thenBy { it.metadata.normalizedTitle },
        )
        .take(limit)
        .toList()
}

@Composable
private fun TargetSheetItem(
    label: String,
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun VaultChapterReplacementDialog(
    chapterTitles: List<String>,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val visibleTitles = chapterTitles.take(MAX_REPLACEMENT_TITLES)
    val remaining = chapterTitles.size - visibleTitles.size
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.vault_import_replace_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(MR.strings.vault_import_replace_confirm_message))
                visibleTitles.forEach { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (remaining > 0) {
                    Text(stringResource(MR.strings.vault_import_replace_confirm_more, remaining))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.vault_action_add_to_vault))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private const val MAX_REPLACEMENT_TITLES = 5
private const val MAX_TARGET_SUGGESTIONS = 8
