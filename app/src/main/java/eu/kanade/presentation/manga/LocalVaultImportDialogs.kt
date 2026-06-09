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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.LocalVaultImportTargetSelection
import tachiyomi.domain.vault.model.VaultManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVaultTargetSetupDialog(
    targets: List<VaultManga>,
    exactTitleCandidateIds: Set<Long>,
    selectedTarget: LocalVaultImportTargetSelection?,
    allowCreateNew: Boolean,
    allowUnlink: Boolean,
    pendingAddToVault: Boolean,
    onTargetSelected: (LocalVaultImportTargetSelection?, Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        val exactTitleLabel = stringResource(MR.strings.vault_import_target_exact_title)
        Column(
            modifier = Modifier.padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(MR.strings.vault_import_choose_target),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (allowCreateNew) {
                TargetSheetItem(
                    label = stringResource(MR.strings.vault_import_target_create_new),
                    selected = selectedTarget == LocalVaultImportTargetSelection.CreateNew,
                    onClick = {
                        onTargetSelected(LocalVaultImportTargetSelection.CreateNew, pendingAddToVault)
                    },
                )
            }
            targets.forEach { target ->
                val label = if (target.id in exactTitleCandidateIds) {
                    "${target.metadata.title} · $exactTitleLabel"
                } else {
                    target.metadata.title
                }
                TargetSheetItem(
                    label = label,
                    selected = selectedTarget == LocalVaultImportTargetSelection.Existing(target.id),
                    onClick = {
                        onTargetSelected(LocalVaultImportTargetSelection.Existing(target.id), pendingAddToVault)
                    },
                )
            }
            if (allowUnlink) {
                TargetSheetItem(
                    label = stringResource(MR.strings.vault_import_unlink_target),
                    selected = selectedTarget == null,
                    onClick = { onTargetSelected(null, pendingAddToVault) },
                )
            }
        }
    }
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
